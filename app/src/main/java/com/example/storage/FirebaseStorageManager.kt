package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class StorageUploadResult(
    val downloadUrl: String,
    val storagePath: String,
    val sizeBytes: Long = 0L
)

enum class MigrationStepStatus {
    PENDING,
    FETCHED_OLD_IMAGE,
    UPLOADED_TO_STORAGE,
    VERIFIED_UPLOAD,
    VERIFIED_READABLE_URL,
    UPDATED_FIRESTORE,
    SUCCESS,
    FAILED
}

data class ImageMigrationRecord(
    val id: String,
    val merchantUid: String,
    val legacyUrl: String,
    val newFirebaseUrl: String? = null,
    val storagePath: String? = null,
    val status: MigrationStepStatus = MigrationStepStatus.PENDING,
    val errorMessage: String? = null,
    val attemptCount: Int = 1,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Official Firebase Storage Manager for El-Batal Express Merchant App.
 *
 * Responsibilities:
 * 1. Uploads all new merchant images exclusively to Firebase Storage.
 * 2. Preserves existing remote URLs (including legacy external URLs) for backward compatibility.
 * 3. Protects existing merchant logos from being lost or overwritten by local URIs if an upload fails.
 * 4. Provides an explicit, opt-in 6-step safe migration mechanism for legacy images with audit logging and retry support.
 */
object FirebaseStorageManager {
    private const val TAG = "FirebaseStorageManager"

    private fun safeLogD(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {}
    }

    private fun safeLogW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {}
    }

    private fun safeLogE(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        } catch (_: Throwable) {}
    }

    // In-memory audit log of migration records per merchant, enabling inspection and retry of failed items
    private val migrationAuditLog = ConcurrentHashMap<String, ImageMigrationRecord>()

    fun getMigrationRecordsForMerchant(merchantUid: String): List<ImageMigrationRecord> {
        return migrationAuditLog.values
            .filter { it.merchantUid == merchantUid }
            .sortedByDescending { it.timestampMs }
    }

    fun getFailedMigrationRecords(merchantUid: String): List<ImageMigrationRecord> {
        return getMigrationRecordsForMerchant(merchantUid)
            .filter { it.status == MigrationStepStatus.FAILED }
    }

    fun clearMigrationAuditLogForTest() {
        migrationAuditLog.clear()
    }

    /**
     * Checks whether a given URL is hosted on Firebase Storage.
     */
    fun isFirebaseStorageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("firebasestorage.googleapis.com") ||
            trimmed.contains(".firebasestorage.app") ||
            trimmed.startsWith("gs://")
    }

    /**
     * Checks whether a URL is a valid remote HTTP/HTTPS image URL (either Firebase Storage or legacy external host).
     */
    fun isRemoteHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
    }

    /**
     * Checks whether a URL is a legacy external image URL (such as old third-party hosted links)
     * that is still displayed for backward compatibility and eligible for manual migration.
     */
    fun isLegacyExternalUrl(url: String?): Boolean {
        if (!isRemoteHttpUrl(url)) return false
        if (isFirebaseStorageUrl(url)) return false
        val trimmed = url!!.trim().lowercase()
        // Ignore default placeholder unsplash avatars
        if (trimmed.contains("images.unsplash.com")) return false
        return true
    }

    /**
     * Checks whether a URI string points to a newly selected local device file (`content://` or `file://`).
     */
    fun isLocalDeviceUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val trimmed = uriString.trim().lowercase()
        return trimmed.startsWith("content://") || trimmed.startsWith("file://")
    }

    /**
     * Pure fail-safe resolution logic for merchant logo updates:
     * - If a new local image upload succeeds, returns the new Firebase Storage downloadUrl & storagePath.
     * - If a new local image upload fails, strictly preserves `existingRemoteUrl` and `existingStoragePath`
     *   so the merchant NEVER loses their previous logo or saves a broken local `content://` URI to Firestore.
     * - If no new local image was selected, preserves the current remote URL.
     */
    fun resolveSafeLogoUpdate(
        existingRemoteUrl: String?,
        existingStoragePath: String?,
        candidateUri: String?,
        uploadResult: Result<StorageUploadResult>?
    ): Pair<String, String> {
        val safeExistingUrl = existingRemoteUrl?.trim().orEmpty()
        val safeExistingPath = existingStoragePath?.trim().orEmpty()
        val candidate = candidateUri?.trim().orEmpty()

        if (candidate.isEmpty()) {
            return Pair(safeExistingUrl, safeExistingPath)
        }

        if (isLocalDeviceUri(candidate)) {
            return if (uploadResult != null && uploadResult.isSuccess) {
                val uploaded = uploadResult.getOrNull()
                if (uploaded != null && isFirebaseStorageUrl(uploaded.downloadUrl)) {
                    Pair(uploaded.downloadUrl, uploaded.storagePath)
                } else {
                    Pair(safeExistingUrl, safeExistingPath)
                }
            } else {
                // Upload failed: preserve the existing remote URL without losing it
                Pair(safeExistingUrl, safeExistingPath)
            }
        }

        // Candidate is already a remote HTTP/HTTPS URL (either Firebase Storage or preserved legacy URL)
        if (isRemoteHttpUrl(candidate)) {
            return Pair(candidate, safeExistingPath)
        }

        return Pair(safeExistingUrl, safeExistingPath)
    }

    /**
     * Uploads a newly selected local image to Firebase Storage under `merchants/{uid}/profile.jpg`.
     * Uses direct `putFile` first with a stream `putBytes` fallback for content provider URIs.
     */
    suspend fun uploadMerchantProfileImage(
        context: Context,
        uid: String,
        localUriString: String
    ): Result<StorageUploadResult> = withContext(Dispatchers.IO) {
        if (uid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("معرف التاجر (UID) غير صالح"))
        }
        if (!isLocalDeviceUri(localUriString)) {
            return@withContext Result.failure(IllegalArgumentException("الرابط المحدد ليس ملفاً محلياً للرفع"))
        }

        val storagePath = "merchants/$uid/profile.jpg"
        val storageRef = Firebase.storage.reference.child(storagePath)
        val parsedUri = Uri.parse(localUriString)
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("merchantUid", uid)
            .setCustomMetadata("source", "firebase_storage_official")
            .build()

        try {
            val taskSnapshot = storageRef.putFile(parsedUri, metadata).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            if (!isRemoteHttpUrl(downloadUrl)) {
                throw IllegalStateException("تعذر استخراج رابط تحميل صالح من Firebase Storage")
            }
            safeLogD("Uploaded merchant image to Firebase Storage ($storagePath): $downloadUrl")
            return@withContext Result.success(
                StorageUploadResult(
                    downloadUrl = downloadUrl,
                    storagePath = storagePath,
                    sizeBytes = taskSnapshot.totalByteCount
                )
            )
        } catch (putFileEx: Exception) {
            safeLogW("Direct putFile failed (${putFileEx.message}), attempting stream putBytes fallback")
            return@withContext try {
                val inputStream = context.contentResolver.openInputStream(parsedUri)
                    ?: throw IllegalStateException("تعذر فتح ملف الصورة المختارة من الجهاز")
                val imageBytes = inputStream.use { it.readBytes() }
                if (imageBytes.isEmpty()) {
                    throw IllegalStateException("ملف الصورة المختار فارغ")
                }
                val taskSnapshot = storageRef.putBytes(imageBytes, metadata).await()
                val downloadUrl = storageRef.downloadUrl.await().toString()
                if (!isRemoteHttpUrl(downloadUrl)) {
                    throw IllegalStateException("تعذر التحقق من رابط الصورة المرفوعة في Firebase Storage")
                }
                safeLogD("Uploaded merchant image via putBytes ($storagePath): $downloadUrl")
                Result.success(
                    StorageUploadResult(
                        downloadUrl = downloadUrl,
                        storagePath = storagePath,
                        sizeBytes = taskSnapshot.totalByteCount
                    )
                )
            } catch (fallbackEx: Exception) {
                safeLogE("Firebase Storage upload failed completely for $uid: ${fallbackEx.message}", fallbackEx)
                Result.failure(fallbackEx)
            }
        }
    }

    /**
     * Explicit, safe 6-step migration of a single legacy external image to Firebase Storage.
     * NEVER called automatically without explicit user/caller invocation.
     *
     * Steps enforced:
     * 1. Fetch old image bytes from legacy URL.
     * 2. Upload image bytes to Firebase Storage (`merchants/{uid}/profile.jpg`).
     * 3. Verify upload succeeded (`totalByteCount > 0`).
     * 4. Verify new image download URL is readable.
     * 5. Update image URL and `storagePath` in Firestore (`restaurants/{uid}` and `merchants/{uid}`).
     * 6. Retain `legacyImageBackupUrl` until all steps succeed; log result and support retry on failure.
     */
    suspend fun migrateLegacyImageSafely(
        merchantUid: String,
        legacyImageUrl: String,
        fetcher: suspend (String) -> ByteArray = ::downloadImageBytesFromUrl,
        uploader: suspend (String, ByteArray) -> StorageUploadResult = ::uploadBytesToFirebaseStorage,
        urlVerifier: suspend (String) -> Boolean = ::verifyUrlIsReadable,
        firestoreUpdater: suspend (String, String, String, String) -> Unit = ::updateFirestoreWithMigratedImage
    ): ImageMigrationRecord = withContext(Dispatchers.IO) {
        val recordId = "${merchantUid}_${legacyImageUrl.hashCode()}"
        val previousRecord = migrationAuditLog[recordId]
        val attempt = (previousRecord?.attemptCount ?: 0) + 1

        var currentStep = MigrationStepStatus.PENDING
        fun updateRecord(
            step: MigrationStepStatus,
            newUrl: String? = null,
            path: String? = null,
            err: String? = null
        ): ImageMigrationRecord {
            currentStep = step
            val rec = ImageMigrationRecord(
                id = recordId,
                merchantUid = merchantUid,
                legacyUrl = legacyImageUrl,
                newFirebaseUrl = newUrl,
                storagePath = path,
                status = step,
                errorMessage = err,
                attemptCount = attempt,
                timestampMs = System.currentTimeMillis()
            )
            migrationAuditLog[recordId] = rec
            return rec
        }

        if (merchantUid.isBlank() || !isRemoteHttpUrl(legacyImageUrl)) {
            return@withContext updateRecord(
                step = MigrationStepStatus.FAILED,
                err = "رابط الصورة القديمة أو معرف التاجر غير صالح للترحيل"
            )
        }

        if (isFirebaseStorageUrl(legacyImageUrl)) {
            return@withContext updateRecord(
                step = MigrationStepStatus.SUCCESS,
                newUrl = legacyImageUrl,
                path = "merchants/$merchantUid/profile.jpg"
            )
        }

        try {
            // 1. جلب الصورة القديمة
            val imageBytes = fetcher(legacyImageUrl)
            if (imageBytes.isEmpty()) {
                throw IllegalStateException("فشل جلب بيانات الصورة القديمة (الملف فارغ)")
            }
            updateRecord(MigrationStepStatus.FETCHED_OLD_IMAGE)

            // 2. رفعها إلى Firebase Storage
            val uploadResult = uploader(merchantUid, imageBytes)
            updateRecord(
                step = MigrationStepStatus.UPLOADED_TO_STORAGE,
                newUrl = uploadResult.downloadUrl,
                path = uploadResult.storagePath
            )

            // 3. التأكد من نجاح الرفع
            if (uploadResult.sizeBytes <= 0L || !isFirebaseStorageUrl(uploadResult.downloadUrl)) {
                throw IllegalStateException("فشل التحقق من سلامة الرفع إلى Firebase Storage")
            }
            updateRecord(
                step = MigrationStepStatus.VERIFIED_UPLOAD,
                newUrl = uploadResult.downloadUrl,
                path = uploadResult.storagePath
            )

            // 4. التأكد من إمكانية قراءة الصورة الجديدة
            val isReadable = urlVerifier(uploadResult.downloadUrl)
            if (!isReadable) {
                throw IllegalStateException("تعذر التحقق من إمكانية قراءة رابط Firebase Storage الجديد")
            }
            updateRecord(
                step = MigrationStepStatus.VERIFIED_READABLE_URL,
                newUrl = uploadResult.downloadUrl,
                path = uploadResult.storagePath
            )

            // 5 & 6. تحديث رابط الصورة في Firestore بعد نجاح جميع الخطوات مع الاحتفاظ بالرابط القديم كنسخة احتياطية
            firestoreUpdater(
                merchantUid,
                uploadResult.downloadUrl,
                uploadResult.storagePath,
                legacyImageUrl
            )
            updateRecord(
                step = MigrationStepStatus.UPDATED_FIRESTORE,
                newUrl = uploadResult.downloadUrl,
                path = uploadResult.storagePath
            )

            safeLogD("Migration succeeded for $merchantUid: $legacyImageUrl -> ${uploadResult.downloadUrl}")
            return@withContext updateRecord(
                step = MigrationStepStatus.SUCCESS,
                newUrl = uploadResult.downloadUrl,
                path = uploadResult.storagePath
            )
        } catch (e: Exception) {
            safeLogE("Migration failed at step $currentStep for $merchantUid: ${e.message}", e)
            return@withContext updateRecord(
                step = MigrationStepStatus.FAILED,
                err = e.localizedMessage ?: e.message ?: "فشل ترحيل الصورة"
            )
        }
    }

    private fun downloadImageBytesFromUrl(urlString: String): ByteArray {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            doInput = true
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code عند جلب الصورة القديمة")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadBytesToFirebaseStorage(
        merchantUid: String,
        bytes: ByteArray
    ): StorageUploadResult {
        val storagePath = "merchants/$merchantUid/profile.jpg"
        val storageRef = Firebase.storage.reference.child(storagePath)
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("merchantUid", merchantUid)
            .setCustomMetadata("migrated", "true")
            .build()
        val snapshot = storageRef.putBytes(bytes, metadata).await()
        val downloadUrl = storageRef.downloadUrl.await().toString()
        return StorageUploadResult(
            downloadUrl = downloadUrl,
            storagePath = storagePath,
            sizeBytes = snapshot.totalByteCount
        )
    }

    private fun verifyUrlIsReadable(urlString: String): Boolean {
        if (!isFirebaseStorageUrl(urlString)) return false
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
            }
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun updateFirestoreWithMigratedImage(
        merchantUid: String,
        newDownloadUrl: String,
        storagePath: String,
        legacyBackupUrl: String
    ) {
        val firestore = Firebase.firestore
        val updates = hashMapOf<String, Any>(
            "logoUrl" to newDownloadUrl,
            "photoUrl" to newDownloadUrl,
            "storagePath" to storagePath,
            "legacyImageBackupUrl" to legacyBackupUrl,
            "imageMigratedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection("restaurants").document(merchantUid)
            .set(updates, SetOptions.merge())
            .await()

        firestore.collection("merchants").document(merchantUid)
            .set(
                hashMapOf(
                    "photoUrl" to newDownloadUrl,
                    "storagePath" to storagePath,
                    "legacyImageBackupUrl" to legacyBackupUrl,
                    "imageMigratedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .await()
    }
}
