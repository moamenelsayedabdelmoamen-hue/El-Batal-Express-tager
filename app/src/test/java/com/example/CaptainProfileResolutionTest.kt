package com.example

import com.example.db.CaptainProfile
import com.example.db.DriverProfile
import com.example.storage.FirebaseStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptainProfileResolutionTest {

    @Test
    fun testAuthoritativeCaptainNameAndPhonePreferRealDatabaseValues() {
        // Captain doc in `captains/{captainId}` has the real name and phone,
        // while the `orders/{orderId}` doc initially only had generic `"الكابتن"` and blank phone.
        val resolvedName = FirebaseStorageManager.selectAuthoritativeCaptainName(
            captainDocName = "محمود حسن علي",
            orderDocName = "الكابتن",
            captainId = "cap_123"
        )
        val resolvedPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
            captainDocPhone = "01022334455",
            orderDocPhone = ""
        )

        assertEquals("محمود حسن علي", resolvedName)
        assertEquals("01022334455", resolvedPhone)
    }

    @Test
    fun testFakeCaptainNameAndPhoneAreRejectedAndReplacedByRealValues() {
        val resolvedName = FirebaseStorageManager.selectAuthoritativeCaptainName(
            captainDocName = "يوسف إبراهيم",
            orderDocName = "الكابتن أحمد محمود",
            captainId = "cap_456"
        )
        val resolvedPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
            captainDocPhone = "",
            orderDocPhone = "01098765432"
        )

        assertEquals("يوسف إبراهيم", resolvedName)
        assertEquals("", resolvedPhone)
        assertFalse(FirebaseStorageManager.isValidCaptainName("الكابتن أحمد محمود"))
        assertFalse(FirebaseStorageManager.isValidCaptainName("الكابتن"))
        assertFalse(FirebaseStorageManager.isValidCaptainName("في انتظار كابتن"))
        assertTrue(FirebaseStorageManager.isValidCaptainName("كابتن كريم سالم"))
    }

    @Test
    fun testSelectBestCaptainPhotoPrefersFirebaseStorageUrlOverNonStorageOrLocalOrPlaceholder() {
        val firebaseStoragePhoto =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/captains%2Fcap_789%2Fprofile.jpg?alt=media&token=xyz"
        val nonStorageHttpPhoto = "https://example.com/old_avatar.jpg"
        val unsplashPlaceholder =
            "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
        val localDeviceUri = "content://media/picker/0/com.android.providers.media.photopicker/media/99"

        // Even if the order doc had a non-Storage HTTP URL, the real Firebase Storage URL from captains/{id} wins
        val selectedFromStorageDoc = FirebaseStorageManager.selectBestCaptainPhotoUrl(
            captainDocPhotoUrl = firebaseStoragePhoto,
            resolvedOrderStorageUrl = null,
            orderDocPhotoUrl = nonStorageHttpPhoto
        )
        assertEquals(firebaseStoragePhoto, selectedFromStorageDoc)

        // Resolved storage path on order doc wins over placeholder / local URI on doc
        val selectedFromOrderStoragePath = FirebaseStorageManager.selectBestCaptainPhotoUrl(
            captainDocPhotoUrl = unsplashPlaceholder,
            resolvedOrderStorageUrl = firebaseStoragePhoto,
            orderDocPhotoUrl = localDeviceUri
        )
        assertEquals(firebaseStoragePhoto, selectedFromOrderStoragePath)

        // Placeholder and local device URIs are never treated as displayable captain photos
        val noneValid = FirebaseStorageManager.selectBestCaptainPhotoUrl(
            captainDocPhotoUrl = unsplashPlaceholder,
            resolvedOrderStorageUrl = localDeviceUri,
            orderDocPhotoUrl = "file:///storage/emulated/0/DCIM/cap.jpg"
        )
        assertEquals("", noneValid)
    }

    @Test
    fun testResolveFirebaseStorageDownloadUrlRejectsLocalDeviceUrisAndPlaceholders() = runBlocking {
        assertEquals("", FirebaseStorageManager.resolveFirebaseStorageDownloadUrl("content://media/external/images/media/1"))
        assertEquals("", FirebaseStorageManager.resolveFirebaseStorageDownloadUrl("file:///sdcard/avatar.jpg"))
        assertEquals(
            "",
            FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(
                "https://images.unsplash.com/photo-1534528741775-53994a69daeb"
            )
        )

        val storageUrl =
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/captains%2Fcap_1%2Fprofile.jpg?alt=media"
        assertEquals(storageUrl, FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(storageUrl))
    }

    @Test
    fun testCaptainAndDriverProfilesDoNotDefaultToHardcodedFakeUrls() {
        val driver = DriverProfile()
        val captain = CaptainProfile()
        assertEquals("", driver.photoUrl)
        assertEquals("", captain.profileImageUrl)
        assertEquals("", driver.phone)
        assertEquals("", captain.phone)
    }
}
