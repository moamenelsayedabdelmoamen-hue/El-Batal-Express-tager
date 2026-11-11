package com.example

import com.example.db.RestaurantProfile
import com.example.storage.FirebaseStorageManager
import com.example.storage.MigrationStepStatus
import com.example.storage.StorageUploadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FirebaseStorageMigrationTest {

    @Before
    fun setUp() {
        FirebaseStorageManager.clearMigrationAuditLogForTest()
    }

    @Test
    fun testZeroImgBbReferencesOrApiKeysExistInSourceCode() {
        val mainJavaDir = File("src/main/java")
        val rootDir = if (mainJavaDir.exists()) mainJavaDir else File("app/src/main/java")
        assertTrue("Source directory must exist", rootDir.exists())

        val sourceFiles = rootDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Should find Kotlin source files", sourceFiles.isNotEmpty())

        val forbiddenPatterns = listOf(
            "api.imgbb.com",
            "IMGBB_API_KEY",
            "imgbb",
            "ibb.co"
        )

        for (file in sourceFiles) {
            val content = file.readText()
            for (pattern in forbiddenPatterns) {
                assertFalse(
                    "File ${file.name} must not contain any reference to '$pattern'",
                    content.contains(pattern, ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun testNewImageUploadUsesFirebaseStorageOnlyAndSavesUrlAndPath() {
        val merchantUid = "merchant_uid_101"
        val expectedPath = "merchants/$merchantUid/profile.jpg"
        val expectedFirebaseUrl =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Fmerchant_uid_101%2Fprofile.jpg?alt=media&token=abc12345"

        val simulatedUpload = Result.success(
            StorageUploadResult(
                downloadUrl = expectedFirebaseUrl,
                storagePath = expectedPath,
                sizeBytes = 48_512L
            )
        )

        val (resolvedUrl, resolvedPath) = FirebaseStorageManager.resolveSafeLogoUpdate(
            existingRemoteUrl = "",
            existingStoragePath = "",
            candidateUri = "content://media/picker/0/com.android.providers.media.photopicker/media/100001",
            uploadResult = simulatedUpload
        )

        assertTrue("Resolved URL must be a valid Firebase Storage URL", FirebaseStorageManager.isFirebaseStorageUrl(resolvedUrl))
        assertEquals(expectedFirebaseUrl, resolvedUrl)
        assertEquals(expectedPath, resolvedPath)
    }

    @Test
    fun testLegacyExternalImagesContinueToDisplayWithoutAutoDeletion() {
        val legacyExternalUrl = "https://cdn.legacy-host.example.com/merchant_old_logo.png"

        // Verify legacy external URL is recognized as valid remote HTTP URL and legacy external URL
        assertTrue(FirebaseStorageManager.isRemoteHttpUrl(legacyExternalUrl))
        assertTrue(FirebaseStorageManager.isLegacyExternalUrl(legacyExternalUrl))
        assertFalse(FirebaseStorageManager.isFirebaseStorageUrl(legacyExternalUrl))

        // When merchant updates profile text fields without picking a new local photo, the legacy URL stays intact
        val (resolvedUrl, _) = FirebaseStorageManager.resolveSafeLogoUpdate(
            existingRemoteUrl = legacyExternalUrl,
            existingStoragePath = "",
            candidateUri = legacyExternalUrl,
            uploadResult = null
        )

        assertEquals(
            "Old external image URL must be preserved and continue displaying without broken compatibility",
            legacyExternalUrl,
            resolvedUrl
        )
    }

    @Test
    fun testLogoChangePreservesOldImageWhenUploadFails() {
        val oldLogoUrl =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Fuid_55%2Fprofile.jpg?alt=media"
        val oldStoragePath = "merchants/uid_55/profile.jpg"
        val newLocalPickerUri = "content://media/external/images/media/5050"

        val failedUploadResult = Result.failure<StorageUploadResult>(
            RuntimeException("Network disconnected during Firebase Storage upload")
        )

        val (resolvedUrl, resolvedPath) = FirebaseStorageManager.resolveSafeLogoUpdate(
            existingRemoteUrl = oldLogoUrl,
            existingStoragePath = oldStoragePath,
            candidateUri = newLocalPickerUri,
            uploadResult = failedUploadResult
        )

        assertEquals(
            "Must strictly preserve old remote logo URL when new upload fails (never overwrite with local content:// URI)",
            oldLogoUrl,
            resolvedUrl
        )
        assertEquals(
            "Must strictly preserve old storagePath when new upload fails",
            oldStoragePath,
            resolvedPath
        )
        assertFalse(
            "Resolved URL must never be a local device URI",
            FirebaseStorageManager.isLocalDeviceUri(resolvedUrl)
        )
    }

    @Test
    fun testSafe6StepMigrationLogsFailureAndSupportsRetryUntilSuccess() = runBlocking {
        val merchantUid = "merchant_migrate_77"
        val legacyUrl = "https://legacy-images.example.org/store77/logo.jpg"
        val expectedFirebaseUrl =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Fmerchant_migrate_77%2Fprofile.jpg?alt=media"
        val expectedStoragePath = "merchants/$merchantUid/profile.jpg"

        var firestoreSavedUrl: String? = null
        var firestoreSavedPath: String? = null
        var firestoreBackupUrl: String? = null

        // 1. First attempt: simulate readability verification failure (Step 4 fails)
        val failedRecord = FirebaseStorageManager.migrateLegacyImageSafely(
            merchantUid = merchantUid,
            legacyImageUrl = legacyUrl,
            fetcher = { "fake_image_bytes_content".toByteArray() },
            uploader = { _, bytes ->
                StorageUploadResult(
                    downloadUrl = expectedFirebaseUrl,
                    storagePath = expectedStoragePath,
                    sizeBytes = bytes.size.toLong()
                )
            },
            urlVerifier = { false }, // Readability check fails!
            firestoreUpdater = { _, newUrl, path, backup ->
                firestoreSavedUrl = newUrl
                firestoreSavedPath = path
                firestoreBackupUrl = backup
            }
        )

        assertEquals(MigrationStepStatus.FAILED, failedRecord.status)
        assertEquals(1, failedRecord.attemptCount)
        assertEquals(
            "Firestore must NOT be updated if readability verification fails",
            null,
            firestoreSavedUrl
        )
        assertEquals(1, FirebaseStorageManager.getFailedMigrationRecords(merchantUid).size)

        // 2. Retry failed migration: all 6 steps succeed
        val retryRecord = FirebaseStorageManager.migrateLegacyImageSafely(
            merchantUid = merchantUid,
            legacyImageUrl = legacyUrl,
            fetcher = { "fake_image_bytes_content".toByteArray() },
            uploader = { _, bytes ->
                StorageUploadResult(
                    downloadUrl = expectedFirebaseUrl,
                    storagePath = expectedStoragePath,
                    sizeBytes = bytes.size.toLong()
                )
            },
            urlVerifier = { true }, // Readability check passes!
            firestoreUpdater = { _, newUrl, path, backup ->
                firestoreSavedUrl = newUrl
                firestoreSavedPath = path
                firestoreBackupUrl = backup
            }
        )

        assertEquals(MigrationStepStatus.SUCCESS, retryRecord.status)
        assertEquals(2, retryRecord.attemptCount)
        assertEquals(expectedFirebaseUrl, retryRecord.newFirebaseUrl)
        assertEquals(expectedStoragePath, retryRecord.storagePath)

        // Verify Firestore received the new URL, storagePath, and retained the old URL backup
        assertEquals(expectedFirebaseUrl, firestoreSavedUrl)
        assertEquals(expectedStoragePath, firestoreSavedPath)
        assertEquals(legacyUrl, firestoreBackupUrl)
        assertEquals(0, FirebaseStorageManager.getFailedMigrationRecords(merchantUid).size)
    }

    @Test
    fun testCrossDeviceLoginDisplaysNewFirebaseStorageImage() {
        val uid = "merchant_cross_device_99"
        val firebaseStorageDownloadUrl =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Fmerchant_cross_device_99%2Fprofile.jpg?alt=media&token=xyz987"
        val storagePath = "merchants/$uid/profile.jpg"

        // Simulate profile loaded from Firestore on a second authorized device (Device B)
        val syncedProfileOnDeviceB = RestaurantProfile(
            name = "مطعم البطل الذهبي",
            phone = "01012345678",
            address = "القاهرة - مدينة نصر",
            imageUri = firebaseStorageDownloadUrl,
            logoUrl = firebaseStorageDownloadUrl,
            photoUrl = firebaseStorageDownloadUrl,
            storagePath = storagePath,
            email = "merchant99@elbatal.com",
            ownerId = uid,
            uid = uid
        )

        assertNotNull(syncedProfileOnDeviceB.photoUrl)
        assertTrue(FirebaseStorageManager.isFirebaseStorageUrl(syncedProfileOnDeviceB.photoUrl))
        assertFalse(FirebaseStorageManager.isLocalDeviceUri(syncedProfileOnDeviceB.photoUrl))
        assertEquals(storagePath, syncedProfileOnDeviceB.storagePath)
    }
}
