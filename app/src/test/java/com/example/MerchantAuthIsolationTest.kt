package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.auth.AuthUserSnapshot
import com.example.auth.MerchantAuthBackend
import com.example.auth.MerchantAuthManager
import com.example.auth.MerchantDocSnapshot
import com.example.auth.MerchantRegistrationRequest
import com.example.auth.MerchantRegistrationResult
import com.example.auth.MerchantSignInResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated verification tests covering the 7 required merchant auth & isolation scenarios
 * directly through [MerchantAuthManager].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MerchantAuthIsolationTest {

    private lateinit var context: Context
    private lateinit var fakeBackend: InMemoryIsolatedFirebaseBackend

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeBackend = InMemoryIsolatedFirebaseBackend()
        MerchantAuthManager.backend = fakeBackend
    }

    @After
    fun teardown() {
        MerchantAuthManager.resetToDefaultBackend()
    }

    @Test
    fun `1_create_new_merchant_exists_in_auth_and_merchants_only`() = runBlocking {
        // Missing category must be rejected
        val missingCategoryRes = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "متجر النور",
                email = "missingcat@merchant.com",
                phone = "01012345678",
                address = "شارع التحرير - القاهرة",
                password = "Password123!",
                category = ""
            )
        )
        assertTrue(missingCategoryRes is MerchantRegistrationResult.Failure)

        val result = MerchantAuthManager.registerMerchant(
            request = MerchantRegistrationRequest(
                name = "أحمد محمد",
                email = "alnoor@merchant.com",
                phone = "01012345678",
                address = "شارع التحرير - القاهرة",
                password = "Password123!",
                category = "صيدلية"
            ),
            logoUploader = { uid ->
                "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2F$uid%2Fprofile.jpg?alt=media" to
                    "merchants/$uid/profile.jpg"
            }
        )

        assertTrue(result is MerchantRegistrationResult.Success)
        val success = result as MerchantRegistrationResult.Success
        assertTrue(success.verificationEmailSent)

        // Exists in Firebase Auth
        val authUser = fakeBackend.authUsersByEmail["alnoor@merchant.com"]
        assertNotNull(authUser)
        assertEquals(success.uid, authUser!!.uid)
        assertFalse(authUser.isVerified)
        assertTrue(fakeBackend.verificationEmailsSentTo.contains("alnoor@merchant.com"))

        // Exists in `merchants/{uid}` with required fields including Firebase Storage logo & storagePath
        val merchantDoc = fakeBackend.merchantsCollection[success.uid]
        assertNotNull(merchantDoc)
        assertEquals(success.uid, merchantDoc!!["uid"])
        assertEquals("أحمد محمد", merchantDoc["name"])
        assertEquals("صيدلية", merchantDoc["category"])
        assertEquals("alnoor@merchant.com", merchantDoc["email"])
        assertEquals("01012345678", merchantDoc["phone"])
        assertEquals("شارع التحرير - القاهرة", merchantDoc["address"])
        assertEquals(
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2F${success.uid}%2Fprofile.jpg?alt=media",
            merchantDoc["logo"]
        )
        assertEquals("merchants/${success.uid}/profile.jpg", merchantDoc["storagePath"])
        assertEquals("merchant", merchantDoc["accountType"])
        assertEquals(false, merchantDoc["emailVerified"])
        assertEquals("unsubscribed", merchantDoc["subscriptionStatus"])
        assertNotNull(merchantDoc["createdAt"])

        // If Firebase Storage logo upload fails, Auth user must roll back cleanly and no incomplete doc saved
        val failedUploadResult = MerchantAuthManager.registerMerchant(
            request = MerchantRegistrationRequest(
                name = "متجر فشل الرفع",
                email = "failupload@merchant.com",
                phone = "01022223333",
                address = "الدقي",
                password = "Password123!",
                category = "مطعم"
            ),
            logoUploader = { throw IllegalStateException("Storage upload failed") }
        )
        assertTrue(failedUploadResult is MerchantRegistrationResult.Failure)
        assertNull(fakeBackend.authUsersByEmail["failupload@merchant.com"])
        assertFalse(fakeBackend.merchantsCollection.values.any { it["email"] == "failupload@merchant.com" })
    }

    @Test
    fun `2_confirm_merchant_account_does_not_exist_in_captains`() = runBlocking {
        val result = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "متجر السلام",
                email = "salam@merchant.com",
                phone = "01099887766",
                address = "مدينة نصر",
                password = "Password123!",
                category = "سوبر ماركت"
            )
        ) as MerchantRegistrationResult.Success

        assertFalse(fakeBackend.captainsCollection.containsKey(result.uid))
        assertTrue(fakeBackend.captainsCollection.isEmpty())
    }

    @Test
    fun `3_confirm_merchant_account_does_not_exist_in_restaurants`() = runBlocking {
        val result = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "متجر النيل",
                email = "nile@merchant.com",
                phone = "01122334455",
                address = "المعادي",
                password = "Password123!",
                category = "كافيه"
            )
        ) as MerchantRegistrationResult.Success

        assertFalse(fakeBackend.restaurantsCollection.containsKey(result.uid))
        assertTrue(fakeBackend.restaurantsCollection.isEmpty())
        assertTrue(fakeBackend.usersCollection.isEmpty())
    }

    @Test
    fun `4_attempt_login_before_email_verification_must_be_rejected`() = runBlocking {
        val reg = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "متجر الفتح",
                email = "fath@merchant.com",
                phone = "01234567890",
                address = "الجيزة",
                password = "Password123!",
                category = "خضار"
            )
        ) as MerchantRegistrationResult.Success

        // Even if someone tampered Firestore `emailVerified = true`, Firebase Auth is the source of truth
        fakeBackend.merchantsCollection[reg.uid]!!["emailVerified"] = true

        val loginResult = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "fath@merchant.com",
            rawPassword = "Password123!"
        )

        assertTrue(loginResult is MerchantSignInResult.EmailNotVerified)
        // Session must be signed out immediately when unverified
        assertNull(fakeBackend.currentAuthUser)
        // Reload check must also reject unverified email
        val checkResult = MerchantAuthManager.checkEmailVerificationAndSignIn(
            context = context,
            email = "fath@merchant.com",
            password = "Password123!"
        )
        assertTrue(checkResult is MerchantSignInResult.EmailNotVerified)
    }

    @Test
    fun `5_verify_email_via_reload_then_login_must_succeed`() = runBlocking {
        val reg = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "متجر البركة",
                email = "baraka@merchant.com",
                phone = "01055443322",
                address = "الإسكندرية",
                password = "Password123!",
                category = "جزارة"
            )
        ) as MerchantRegistrationResult.Success

        // Initially unverified
        assertTrue(
            MerchantAuthManager.signInWithEmail(context, "baraka@merchant.com", "Password123!")
                is MerchantSignInResult.EmailNotVerified
        )

        // Simulate user clicking verification link -> Firebase Auth reflects verified on reload()
        fakeBackend.markEmailVerifiedInAuth("baraka@merchant.com")

        // Before admin approval, login is deferred cleanly as NotRegistered/pending approval
        val pendingLogin = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "baraka@merchant.com",
            rawPassword = "Password123!"
        )
        assertTrue(pendingLogin is MerchantSignInResult.NotRegistered)

        // Admin approves merchant -> login must succeed
        fakeBackend.approveMerchant(reg.uid)
        val verifiedLogin = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "baraka@merchant.com",
            rawPassword = "Password123!"
        )

        assertTrue(verifiedLogin is MerchantSignInResult.Success)
        val merchant = (verifiedLogin as MerchantSignInResult.Success).merchant
        assertEquals(reg.uid, merchant.uid)
        assertTrue(merchant.emailVerified)
        assertEquals("merchant", merchant.accountType)
        assertTrue(fakeBackend.reloadCalledCount > 0)
        // Confirm Firestore merchants/{uid} emailVerified synced to true
        assertEquals(true, fakeBackend.merchantsCollection[reg.uid]!!["emailVerified"])
    }

    @Test
    fun `6_confirm_merchant_data_loaded_from_merchants_only`() = runBlocking {
        val reg = MerchantAuthManager.registerMerchant(
            MerchantRegistrationRequest(
                name = "اسم التاجر الصحيح من merchants",
                email = "profile@merchant.com",
                phone = "01077778888",
                address = "طنطا",
                password = "Password123!",
                category = "أكل حيوانات",
                logoUrl = "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Flogo.png?alt=media",
                storagePath = "merchants/merchant-uid-6/profile.jpg"
            )
        ) as MerchantRegistrationResult.Success

        // Place decoy/conflicting documents in restaurants and users with the same UID to prove they are NEVER read
        fakeBackend.restaurantsCollection[reg.uid] = mutableMapOf(
            "uid" to reg.uid,
            "name" to "اسم خاطئ من restaurants",
            "accountType" to "restaurant",
            "status" to "active"
        )
        fakeBackend.usersCollection[reg.uid] = mutableMapOf(
            "uid" to reg.uid,
            "name" to "اسم خاطئ من users"
        )

        fakeBackend.markEmailVerifiedInAuth("profile@merchant.com")
        fakeBackend.approveMerchant(reg.uid)

        val loginRes = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "profile@merchant.com",
            rawPassword = "Password123!"
        ) as MerchantSignInResult.Success

        assertEquals("اسم التاجر الصحيح من merchants", loginRes.merchant.name)
        assertEquals("أكل حيوانات", loginRes.merchant.category)
        assertEquals("01077778888", loginRes.merchant.phone)
        assertEquals("طنطا", loginRes.merchant.address)
        assertEquals(
            "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/merchants%2Flogo.png?alt=media",
            loginRes.merchant.logo
        )
        assertEquals("merchants/merchant-uid-6/profile.jpg", loginRes.merchant.storagePath)
        assertEquals(0, fakeBackend.restaurantsReadCount)
        assertEquals(0, fakeBackend.captainsReadCount)
        assertEquals(0, fakeBackend.usersReadCount)
        assertTrue(fakeBackend.merchantsReadCount > 0)
    }

    @Test
    fun `7_attempt_login_using_captain_account_must_be_rejected`() = runBlocking {
        // Seed a verified Auth user whose Firestore profile lives in `captains/{uid}` ONLY (not in merchants)
        val captainUid = "captain-uid-999"
        fakeBackend.seedAuthUser(
            uid = captainUid,
            email = "driver@captain.com",
            password = "CaptainPass123!",
            isVerified = true
        )
        fakeBackend.captainsCollection[captainUid] = mutableMapOf(
            "uid" to captainUid,
            "name" to "كابتن علي",
            "email" to "driver@captain.com",
            "accountType" to "captain",
            "role" to "captain",
            "status" to "approved"
        )

        val captainLoginRes = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "driver@captain.com",
            rawPassword = "CaptainPass123!"
        )

        assertTrue(captainLoginRes is MerchantSignInResult.NotRegistered)
        // Must NEVER auto-create a merchant document on login attempt
        assertFalse(fakeBackend.merchantsCollection.containsKey(captainUid))
        // Session must be signed out
        assertNull(fakeBackend.currentAuthUser)

        // Edge case: even if a doc existed in `merchants/{uid}` with `accountType = "captain"`, it must ALSO be rejected
        fakeBackend.merchantsCollection[captainUid] = mutableMapOf(
            "uid" to captainUid,
            "name" to "كابتن متسلل",
            "email" to "driver@captain.com",
            "accountType" to "captain",
            "status" to "approved",
            "isApproved" to true
        )

        val mismatchedTypeRes = MerchantAuthManager.signInWithEmail(
            context = context,
            rawEmail = "driver@captain.com",
            rawPassword = "CaptainPass123!"
        )
        assertTrue(mismatchedTypeRes is MerchantSignInResult.NotRegistered)
        assertNull(fakeBackend.currentAuthUser)
    }

    /**
     * In-memory backend double tracking all Auth actions and collection reads/writes across
     * `merchants`, `captains`, `restaurants`, and `users`.
     */
    private class InMemoryIsolatedFirebaseBackend : MerchantAuthBackend {
        data class StoredAuthRecord(
            val uid: String,
            val email: String,
            val password: String,
            var isVerified: Boolean
        )

        val authUsersByEmail = mutableMapOf<String, StoredAuthRecord>()
        val merchantsCollection = mutableMapOf<String, MutableMap<String, Any>>()
        val captainsCollection = mutableMapOf<String, MutableMap<String, Any>>()
        val restaurantsCollection = mutableMapOf<String, MutableMap<String, Any>>()
        val usersCollection = mutableMapOf<String, MutableMap<String, Any>>()
        val verificationEmailsSentTo = mutableListOf<String>()

        var currentAuthUser: StoredAuthRecord? = null
        var reloadCalledCount = 0
        var merchantsReadCount = 0
        var captainsReadCount = 0
        var restaurantsReadCount = 0
        var usersReadCount = 0
        private var uidCounter = 1

        fun seedAuthUser(uid: String, email: String, password: String, isVerified: Boolean) {
            authUsersByEmail[email.lowercase()] = StoredAuthRecord(uid, email.lowercase(), password, isVerified)
        }

        fun markEmailVerifiedInAuth(email: String) {
            authUsersByEmail[email.lowercase()]?.isVerified = true
        }

        fun approveMerchant(uid: String) {
            merchantsCollection[uid]?.let {
                it["status"] = "approved"
                it["isApproved"] = true
            }
        }

        private fun StoredAuthRecord.toSnapshot() = AuthUserSnapshot(
            uid = uid,
            email = email,
            displayName = "",
            photoUrl = "",
            isEmailVerified = isVerified
        )

        override fun currentUser(): AuthUserSnapshot? = currentAuthUser?.toSnapshot()

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val cleanEmail = email.lowercase()
            if (authUsersByEmail.containsKey(cleanEmail)) {
                throw IllegalStateException("email-already-in-use")
            }
            val record = StoredAuthRecord(
                uid = "merchant-uid-${uidCounter++}",
                email = cleanEmail,
                password = password,
                isVerified = false
            )
            authUsersByEmail[cleanEmail] = record
            currentAuthUser = record
            return record.toSnapshot()
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val cleanEmail = email.lowercase()
            val record = authUsersByEmail[cleanEmail]
                ?: throw IllegalStateException("user-not-found")
            if (record.password != password) {
                throw IllegalStateException("wrong-password")
            }
            currentAuthUser = record
            return record.toSnapshot()
        }

        override suspend fun signInWithGoogleCredential(idToken: String): AuthUserSnapshot {
            throw UnsupportedOperationException("Not used in email/password isolation tests")
        }

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
            reloadCalledCount++
            val curr = currentAuthUser ?: return null
            val latest = authUsersByEmail[curr.email] ?: curr
            currentAuthUser = latest
            return latest.toSnapshot()
        }

        override suspend fun sendEmailVerificationToCurrentUser() {
            val curr = currentAuthUser ?: throw IllegalStateException("No signed-in user")
            verificationEmailsSentTo.add(curr.email)
        }

        override suspend fun deleteCurrentUser() {
            val curr = currentAuthUser ?: return
            authUsersByEmail.remove(curr.email)
            currentAuthUser = null
        }

        override suspend fun signOut(context: Context) {
            currentAuthUser = null
        }

        override suspend fun getMerchantDocument(uid: String): MerchantDocSnapshot? {
            merchantsReadCount++
            val doc = merchantsCollection[uid] ?: return MerchantDocSnapshot(exists = false)
            return MerchantDocSnapshot(
                exists = true,
                uid = (doc["uid"] as? String).orEmpty(),
                name = (doc["name"] as? String).orEmpty(),
                email = (doc["email"] as? String).orEmpty(),
                phone = (doc["phone"] as? String).orEmpty(),
                address = (doc["address"] as? String).orEmpty(),
                category = (doc["category"] as? String).orEmpty(),
                logo = (doc["logo"] as? String).orEmpty().ifBlank { (doc["photoUrl"] as? String).orEmpty() },
                storagePath = (doc["storagePath"] as? String).orEmpty(),
                accountType = (doc["accountType"] as? String).orEmpty(),
                role = (doc["role"] as? String).orEmpty(),
                emailVerified = doc["emailVerified"] == true,
                subscriptionStatus = (doc["subscriptionStatus"] as? String).orEmpty().ifBlank { "unsubscribed" },
                status = (doc["status"] as? String).orEmpty(),
                isApproved = doc["isApproved"] as? Boolean
            )
        }

        override suspend fun createMerchantDocument(uid: String, data: Map<String, Any>) {
            merchantsCollection[uid] = data.toMutableMap()
        }

        override suspend fun updateMerchantFields(uid: String, updates: Map<String, Any>) {
            val doc = merchantsCollection.getOrPut(uid) { mutableMapOf() }
            doc.putAll(updates)
        }
    }
}
