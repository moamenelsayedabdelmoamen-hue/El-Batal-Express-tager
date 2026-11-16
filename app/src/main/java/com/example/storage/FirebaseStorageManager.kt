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
    private val resolvedStorageUrlCache = ConcurrentHashMap<String, String>()

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
     * Returns true if the URL is blank or a stock placeholder avatar (e.g., Unsplash default)
     * rather than a real captain/merchant image stored in Firebase Storage.
     */
    fun isPlaceholderAvatarUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.trim().lowercase()
        return lower.contains("images.unsplash.com") ||
            lower.contains("via.placeholder")
    }

    /**
     * Checks whether a captain name is a real registered name rather than a generic status/placeholder label.
     */
    fun isValidCaptainName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return trimmed !in listOf(
            "في انتظار كابتن",
            "في انتظار قبول كابتن",
            "الكابتن",
            "كابتن التوصيل",
            "الكابتن أحمد محمود"
        )
    }

    /**
     * Checks whether a URL is hosted on Firebase Storage (`https://firebasestorage...` or `gs://...`).
     */
    fun isFirebaseStorageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("firebasestorage.googleapis.com") ||
            trimmed.contains(".firebasestorage.app") ||
            trimmed.startsWith("gs://")
    }

    /**
     * Checks whether a URL is a valid remote HTTP/HTTPS image URL (or cloud-persisted data URI).
     */
    fun isRemoteHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("https://") ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("data:image/")
    }

    /**
     * Checks whether a string is a displayable remote captain image URL (not blank, not placeholder, not local device URI).
     */
    fun isValidDisplayableCaptainPhoto(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return !isPlaceholderAvatarUrl(trimmed) &&
            !isLocalDeviceUri(trimmed) &&
            isRemoteHttpUrl(trimmed)
    }

    /**
     * Selects the best captain photo URL, strictly preferring authentic Firebase Storage URLs
     * over generic remote URLs, and always preferring authoritative captain DB/Storage records
     * over potentially stale or placeholder values on the order document.
     */
    fun selectBestCaptainPhotoUrl(
        captainDocPhotoUrl: String?,
        resolvedOrderStorageUrl: String?,
        orderDocPhotoUrl: String?
    ): String {
        val candidates = listOf(captainDocPhotoUrl, resolvedOrderStorageUrl, orderDocPhotoUrl)
            .map { it?.trim().orEmpty() }
            .filter { isValidDisplayableCaptainPhoto(it) }

        return candidates.firstOrNull { isFirebaseStorageUrl(it) }
            ?: candidates.firstOrNull()
            ?: ""
    }

    /**
     * Selects the authoritative captain name and phone, preferring non-placeholder data from
     * the real `captains/{captainId}` Firestore profile over stale/generic values on the order doc.
     */
    fun selectAuthoritativeCaptainName(
        captainDocName: String?,
        orderDocName: String?,
        captainId: String?
    ): String {
        val docName = captainDocName?.trim().orEmpty()
        val orderName = orderDocName?.trim().orEmpty()
        return when {
            isValidCaptainName(docName) -> docName
            isValidCaptainName(orderName) -> orderName
            docName.isNotEmpty() -> docName
            orderName.isNotEmpty() -> orderName
            !captainId.isNullOrBlank() -> "الكابتن"
            else -> "في انتظار كابتن"
        }
    }

    fun selectAuthoritativeCaptainPhone(
        captainDocPhone: String?,
        orderDocPhone: String?
    ): String {
        val docPhone = captainDocPhone?.trim().orEmpty()
            .takeIf { it != "01098765432" }
            .orEmpty()
        val ordPhone = orderDocPhone?.trim().orEmpty()
            .takeIf { it != "01098765432" }
            .orEmpty()
        return docPhone.ifBlank { ordPhone }
    }

    /**
     * Resolves a stored image string into a model supported by Coil AsyncImage
     * (converts `data:image/...;base64,...` into a decoded ByteArray, and passes URLs/URIs as-is).
     */
    fun resolveDisplayableImageModel(model: String?): Any? {
        if (model.isNullOrBlank()) return null
        val trimmed = model.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true) && trimmed.contains("base64,")) {
            return try {
                val base64Part = trimmed.substringAfter("base64,")
                android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                trimmed
            }
        }
        return trimmed
    }

    /**
     * Resolves a Firebase Storage path (e.g. `captains/{uid}/profile.jpg`), `gs://...` reference,
     * or direct HTTPS URL into a displayable download URL.
     */
    suspend fun resolveFirebaseStorageDownloadUrl(rawPathOrUrl: String?): String = withContext(Dispatchers.IO) {
        val trimmed = rawPathOrUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || isPlaceholderAvatarUrl(trimmed) || isLocalDeviceUri(trimmed)) {
            return@withContext ""
        }
        if (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true)
        ) {
            return@withContext trimmed
        }
        resolvedStorageUrlCache[trimmed]?.let { cached ->
            if (cached.isNotBlank()) return@withContext cached
        }
        return@withContext try {
            val storageRef = if (trimmed.startsWith("gs://", ignoreCase = true)) {
                Firebase.storage.getReferenceFromUrl(trimmed)
            } else {
                Firebase.storage.reference.child(trimmed.removePrefix("/"))
            }
            val url = kotlinx.coroutines.withTimeout(6000L) {
                storageRef.downloadUrl.await().toString()
            }
            if (url.isNotBlank()) {
                resolvedStorageUrlCache[trimmed] = url
            }
            url
        } catch (e: Exception) {
            safeLogW("Could not resolve storage path '$trimmed': ${e.message}")
            ""
        }
    }

    /**
     * Searches Firebase Storage under `captains/{captainId}/...`, `drivers/{captainId}/...`,
     * `captain_photos/{captainId}/...`, `profile_images/{captainId}/...`, `avatars/{captainId}/...`,
     * and `users/{captainId}/...` to find a captain's real uploaded photo URL.
     */
    suspend fun findCaptainPhotoInStorage(
        captainId: String,
        bypassCache: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val cleanId = captainId.trim()
        if (cleanId.isEmpty()) return@withContext ""
        val cacheKey = "captain_lookup:$cleanId"
        if (!bypassCache) {
            resolvedStorageUrlCache[cacheKey]?.let { cached ->
                if (cached.isNotBlank()) return@withContext cached
            }
        } else {
            resolvedStorageUrlCache.remove(cacheKey)
        }

        val storage = Firebase.storage
        val folderPrefixes = listOf(
            "captains/$cleanId",
            "drivers/$cleanId",
            "captain_photos/$cleanId",
            "profile_images/$cleanId",
            "avatars/$cleanId",
            "users/$cleanId"
        )
        for (prefix in folderPrefixes) {
            try {
                val listResult = kotlinx.coroutines.withTimeout(4500L) {
                    storage.reference.child(prefix).list(10).await()
                }
                val preferredItem = listResult.items.firstOrNull { item ->
                    val n = item.name.lowercase()
                    n.contains("profile") || n.contains("avatar") || n.contains("photo") ||
                        n.contains("face") || n.contains("image") || n.contains("logo") || n.contains("selfie")
                } ?: listResult.items.firstOrNull()
                if (preferredItem != null) {
                    val url = kotlinx.coroutines.withTimeout(4500L) {
                        preferredItem.downloadUrl.await().toString()
                    }
                    if (url.isNotBlank()) {
                        resolvedStorageUrlCache[cacheKey] = url
                        return@withContext url
                    }
                }
            } catch (_: Exception) {
            }
        }

        val directCandidates = listOf(
            "captains/$cleanId/profile.jpg",
            "captains/$cleanId/profile.png",
            "captains/$cleanId/avatar.jpg",
            "captains/$cleanId/avatar.png",
            "captains/$cleanId/photo.jpg",
            "captains/$cleanId/photo.png",
            "captains/$cleanId/logo.jpg",
            "captains/$cleanId/logo.png",
            "captains/$cleanId/image.jpg",
            "captains/$cleanId.jpg",
            "captains/$cleanId.png",
            "drivers/$cleanId/profile.jpg",
            "drivers/$cleanId/avatar.jpg",
            "drivers/$cleanId/photo.jpg",
            "drivers/$cleanId.jpg",
            "users/$cleanId/profile.jpg",
            "users/$cleanId/photo.jpg",
            "profile_images/$cleanId/profile.jpg",
            "profile_images/$cleanId.jpg",
            "profile_images/$cleanId.png",
            "captain_photos/$cleanId/profile.jpg",
            "captain_photos/$cleanId.jpg",
            "captain_photos/$cleanId.png",
            "avatars/$cleanId.jpg",
            "avatars/$cleanId.png"
        )
        for (path in directCandidates) {
            try {
                val url = kotlinx.coroutines.withTimeout(3500L) {
                    storage.reference.child(path).downloadUrl.await().toString()
                }
                if (url.isNotBlank()) {
                    resolvedStorageUrlCache[cacheKey] = url
                    return@withContext url
                }
            } catch (_: Exception) {
            }
        }
        ""
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
     * Reads and compresses a local image URI into a compact JPEG ByteArray (~15-30 KB)
     * for fast upload and reliable persistence.
     */
    private fun readAndCompressLocalImage(context: Context, parsedUri: Uri): ByteArray {
        val rawBytes = context.contentResolver.openInputStream(parsedUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذر فتح ملف الصورة المختارة من الجهاز")
        if (rawBytes.isEmpty()) {
            throw IllegalStateException("ملف الصورة المختار فارغ")
        }

        return try {
            val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            if (originalBitmap != null) {
                val maxDim = 720
                val width = originalBitmap.width.coerceAtLeast(1)
                val height = originalBitmap.height.coerceAtLeast(1)
                val scale = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height, 1f)
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)
                val scaledBitmap = if (targetW != width || targetH != height) {
                    android.graphics.Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
                } else {
                    originalBitmap
                }
                val out = java.io.ByteArrayOutputStream()
                scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray().takeIf { it.isNotEmpty() } ?: rawBytes
            } else {
                rawBytes
            }
        } catch (_: Throwable) {
            rawBytes
        }
    }

    /**
     * Uploads a newly selected local image exclusively to Firebase Storage under `merchants/{uid}/profile.jpg`.
     * Returns failure if the upload or download URL retrieval fails so callers can surface the error
     * instead of silently losing the logo or saving a non-Storage placeholder.
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
        val parsedUri = Uri.parse(localUriString)

        val compressedBytes = try {
            readAndCompressLocalImage(context, parsedUri)
        } catch (readEx: Exception) {
            safeLogE("Failed to read local image $localUriString: ${readEx.message}", readEx)
            return@withContext Result.failure(readEx)
        }

        val storage = Firebase.storage
        try {
            storage.maxUploadRetryTimeMillis = 15_000L
            storage.maxOperationRetryTimeMillis = 10_000L
        } catch (_: Throwable) {}

        val storageRef = storage.reference.child(storagePath)
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("merchantUid", uid)
            .setCustomMetadata("source", "firebase_storage_official")
            .build()

        return@withContext try {
            val taskSnapshot = kotlinx.coroutines.withTimeout(25_000L) {
                storageRef.putBytes(compressedBytes, metadata).await()
            }
            val downloadUrl = kotlinx.coroutines.withTimeout(10_000L) {
                storageRef.downloadUrl.await().toString()
            }
            if (!isFirebaseStorageUrl(downloadUrl)) {
                throw IllegalStateException("تعذر استخراج رابط تحميل صالح من Firebase Storage")
            }
            resolvedStorageUrlCache[storagePath] = downloadUrl
            safeLogD("Uploaded merchant logo to Firebase Storage ($storagePath): $downloadUrl")
            Result.success(
                StorageUploadResult(
                    downloadUrl = downloadUrl,
                    storagePath = storagePath,
                    sizeBytes = taskSnapshot.totalByteCount.coerceAtLeast(compressedBytes.size.toLong())
                )
            )
        } catch (storageEx: Exception) {
            safeLogE("Firebase Storage upload failed for $storagePath: ${storageEx.message}", storageEx)
            Result.failure(storageEx)
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
     * 5. Update image URL and `storagePath` only in `merchants/{uid}`.
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
        firestore.collection("merchants").document(merchantUid)
            .set(
                hashMapOf(
                    "logo" to newDownloadUrl,
                    "logoUrl" to newDownloadUrl,
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
