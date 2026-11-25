package com.example

import android.content.Context
import com.example.auth.AuthUserSnapshot
import com.example.auth.MerchantAuthBackend
import com.example.auth.MerchantAuthManager
import com.example.auth.MerchantDocSnapshot
import com.example.auth.MerchantSignInResult
import com.example.db.MerchantSubscriptionRepository
import com.example.db.OrderCountdownTracker
import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionSyncResolver
import com.example.splash.SplashSessionState
import com.example.storage.FirebaseStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pure-JVM behavioral tests validating startup stability, session preservation,
 * offline resilience, timeout handling, coroutine cancellation propagation,
 * order/captain tracking resilience, and honest PERMISSION_DENIED reporting.
 */
class StartupStabilityScenariosTest {

    private class FakeStartupAuthBackend(
        var user: AuthUserSnapshot? = null,
        var merchantDoc: MerchantDocSnapshot? = null,
        var cachedMerchantDoc: MerchantDocSnapshot? = null,
        var reloadHandler: (suspend () -> AuthUserSnapshot?)? = null,
        var getMerchantHandler: (suspend (String) -> MerchantDocSnapshot?)? = null,
        var updateMerchantHandler: (suspend (String, Map<String, Any>) -> Unit)? = null
    ) : MerchantAuthBackend {
        var signOutCount = 0
        var cachedDocReadCount = 0
        var updateFieldsCallCount = 0

        override fun currentUser(): AuthUserSnapshot? = user

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            throw UnsupportedOperationException()
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            return user ?: throw IllegalStateException("No user")
        }

        override suspend fun signInWithGoogleCredential(idToken: String): AuthUserSnapshot {
            throw UnsupportedOperationException()
        }

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
            reloadHandler?.let { return it() }
            return user
        }

        override suspend fun sendEmailVerificationToCurrentUser() {}

        override suspend fun signOut(context: Context) {
            signOutCount++
            user = null
        }

        override suspend fun getMerchantDocument(uid: String): MerchantDocSnapshot? {
            getMerchantHandler?.let { return it(uid) }
            return merchantDoc
        }

        override suspend fun getCachedMerchantDocument(uid: String): MerchantDocSnapshot? {
            cachedDocReadCount++
            return cachedMerchantDoc
        }

        override suspend fun createMerchantDocument(uid: String, data: Map<String, Any>) {}

        override suspend fun updateMerchantFields(uid: String, updates: Map<String, Any>) {
            updateFieldsCallCount++
            updateMerchantHandler?.invoke(uid, updates)
        }
    }

    private val dummyContext: Context
        get() = android.content.ContextWrapper(null)

    private fun sampleVerifiedUser(uid: String = "merchant_001") = AuthUserSnapshot(
        uid = uid,
        email = "shop@elbatal.com",
        displayName = "البطل إكسبريس",
        photoUrl = "",
        isEmailVerified = true
    )

    private fun sampleValidMerchantDoc(uid: String = "merchant_001", name: String = "مطعم البطل") =
        MerchantDocSnapshot(
            exists = true,
            uid = uid,
            name = name,
            email = "shop@elbatal.com",
            phone = "01012345678",
            address = "مدينة السادات",
            category = "مطعم",
            accountType = "merchant",
            role = "merchant",
            emailVerified = true,
            subscriptionStatus = "active",
            status = "approved",
            isApproved = true
        )

    @Before
    fun setUp() {
        SplashSessionState.resetForTesting()
    }

    @After
    fun tearDown() {
        MerchantAuthManager.resetToDefaultBackend()
        SplashSessionState.resetForTesting()
    }

    @Test
    fun `1 - first launch without account routes cleanly to login without signOut`() = runBlocking {
        val backend = FakeStartupAuthBackend(user = null)
        MerchantAuthManager.backend = backend

        assertFalse(SplashSessionState.shouldSkipIntroVideo())
        val postSplashDest = SplashSessionState.resolvePostSplashDestination(
            hasCurrentUser = backend.currentUser() != null,
            isEmailVerified = false
        )
        assertEquals(MainActivity.START_DESTINATION_LOGIN, postSplashDest)

        val result = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(result is MerchantSignInResult.NotRegistered)
        assertNull(MerchantAuthManager.checkExistingMerchantSession(dummyContext))
        assertEquals(0, backend.signOutCount)
    }

    @Test
    fun `2 - valid merchant session restores cleanly online and routes to dashboard`() = runBlocking {
        val backend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_online"),
            merchantDoc = sampleValidMerchantDoc("merchant_online", "مطعم البطل الرئيسي")
        )
        MerchantAuthManager.backend = backend

        val dest = SplashSessionState.resolvePostSplashDestination(
            hasCurrentUser = true,
            isEmailVerified = true
        )
        assertEquals(MainActivity.START_DESTINATION_DASHBOARD, dest)

        val result = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(result is MerchantSignInResult.Success)
        val restored = (result as MerchantSignInResult.Success).merchant
        assertEquals("merchant_online", restored.uid)
        assertEquals("مطعم البطل الرئيسي", restored.name)
        assertEquals("merchant", restored.accountType)
        assertEquals("active", restored.subscriptionStatus)
        assertEquals(0, backend.signOutCount)
        assertEquals(1, backend.updateFieldsCallCount)
    }

    @Test
    fun `3 - offline launch restores saved merchant session via Source CACHE without logout`() = runBlocking {
        val offlineUser = sampleVerifiedUser("merchant_offline")
        val cachedDoc = sampleValidMerchantDoc("merchant_offline", "متجر أوفلاين من الكاش")
        val backend = FakeStartupAuthBackend(
            user = offlineUser,
            cachedMerchantDoc = cachedDoc,
            reloadHandler = { throw IOException("Network unreachable during offline launch") },
            getMerchantHandler = {
                throw IllegalStateException("UNAVAILABLE: Failed to get document because the client is offline.")
            }
        )
        MerchantAuthManager.backend = backend

        val result = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue("Expected offline cached restoration to succeed", result is MerchantSignInResult.Success)
        val restored = (result as MerchantSignInResult.Success).merchant
        assertEquals("merchant_offline", restored.uid)
        assertEquals("متجر أوفلاين من الكاش", restored.name)
        assertEquals("Expected Source.CACHE fallback to be queried", 1, backend.cachedDocReadCount)
        assertEquals("Offline launch must NEVER sign out Firebase Auth", 0, backend.signOutCount)
        assertNotNull("Firebase Auth user must remain signed in", backend.currentUser())

        // Offline subscription resolver keeps honest unverified state (cannot fake active dispatch)
        val offlineSubState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false, isFromCache = true),
            merchantDoc = RawSubscriptionSnapshot(exists = true, isFromCache = true, subscriptionStatus = "unsubscribed"),
            isOnline = false,
            hasVerifiedServerOnce = false
        )
        assertFalse(offlineSubState.canRequestCaptain())
        assertTrue(offlineSubState.isOfflineUnverified)
    }

    @Test
    fun `4 - getMerchantDocument TimeoutCancellationException preserves Firebase session without signOut`() = runBlocking {
        val slowUser = sampleVerifiedUser("merchant_timeout")
        val cachedDoc = sampleValidMerchantDoc("merchant_timeout", "مطعم محفوظ محلياً")

        // 4a. Timeout with cached merchant doc -> recovers from Source.CACHE without signOut
        val cachedTimeoutBackend = FakeStartupAuthBackend(
            user = slowUser,
            cachedMerchantDoc = cachedDoc,
            getMerchantHandler = {
                // Triggers a genuine Kotlin TimeoutCancellationException (same class as 10s Firestore timeout)
                withTimeout(25L) { delay(5_000L) }
                null
            }
        )
        MerchantAuthManager.backend = cachedTimeoutBackend

        val cachedResult = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(cachedResult is MerchantSignInResult.Success)
        assertEquals("مطعم محفوظ محلياً", (cachedResult as MerchantSignInResult.Success).merchant.name)
        assertEquals(1, cachedTimeoutBackend.cachedDocReadCount)
        assertEquals("Timeout must NEVER trigger signOut", 0, cachedTimeoutBackend.signOutCount)
        assertNotNull(cachedTimeoutBackend.currentUser())

        // 4b. Timeout with cold cache (no cached doc yet) -> STILL preserves session without signOut
        val coldCacheTimeoutBackend = FakeStartupAuthBackend(
            user = slowUser,
            cachedMerchantDoc = null,
            getMerchantHandler = {
                withTimeout(25L) { delay(5_000L) }
                null
            }
        )
        MerchantAuthManager.backend = coldCacheTimeoutBackend

        val coldResult = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(
            "Existing verified session must be preserved on timeout instead of being treated as unapproved",
            coldResult is MerchantSignInResult.Success
        )
        assertEquals("merchant_timeout", (coldResult as MerchantSignInResult.Success).merchant.uid)
        assertEquals("Cold-cache timeout must NEVER sign out Firebase Auth", 0, coldCacheTimeoutBackend.signOutCount)
        assertNotNull(coldCacheTimeoutBackend.currentUser())

        // 4c. Interactive sign-in timeout returns Error (does not fake approval) AND does not signOut
        val interactiveResult = MerchantAuthManager.signInWithEmail(dummyContext, "shop@elbatal.com", "secret123")
        assertTrue(interactiveResult is MerchantSignInResult.Error)
        assertEquals(0, coldCacheTimeoutBackend.signOutCount)
    }

    @Test
    fun `5 - Firestore UNAVAILABLE error preserves session without signing out`() = runBlocking {
        val backend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_unavail"),
            cachedMerchantDoc = null,
            getMerchantHandler = {
                throw RuntimeException("UNAVAILABLE: The service is currently unavailable.")
            }
        )
        MerchantAuthManager.backend = backend

        val result = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(result is MerchantSignInResult.Success)
        assertEquals("merchant_unavail", (result as MerchantSignInResult.Success).merchant.uid)
        assertEquals(1, backend.cachedDocReadCount)
        assertEquals("UNAVAILABLE must NEVER sign out Firebase Auth", 0, backend.signOutCount)
        assertNotNull("Persisted Firebase session must remain intact", backend.currentUser())
    }

    @Test
    fun `6 - updateMerchantFields failure or offline hang never freezes or breaks session restoration`() = runBlocking {
        // 6a. updateMerchantFields throws an error
        val throwingBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_sync_err"),
            merchantDoc = sampleValidMerchantDoc("merchant_sync_err"),
            updateMerchantHandler = { _, _ -> throw RuntimeException("Simulated write failure") }
        )
        MerchantAuthManager.backend = throwingBackend

        val errResult = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(errResult is MerchantSignInResult.Success)
        assertEquals(0, throwingBackend.signOutCount)

        // 6b. updateMerchantFields hangs (simulating offline write waiting for server ACK)
        val hangingBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_sync_hang"),
            merchantDoc = sampleValidMerchantDoc("merchant_sync_hang"),
            updateMerchantHandler = { _, _ -> delay(30_000L) }
        )
        MerchantAuthManager.backend = hangingBackend

        // Must finish well under 4 seconds (updateMerchantFields is bounded to 1.5s and non-blocking in Firebase backend)
        val hangResult = withTimeout(4_000L) {
            MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        }
        assertTrue(hangResult is MerchantSignInResult.Success)
        assertEquals("merchant_sync_hang", (hangResult as MerchantSignInResult.Success).merchant.uid)
        assertEquals(0, hangingBackend.signOutCount)
    }

    @Test
    fun `7 - parent coroutine CancellationException is propagated and never swallowed`() = runBlocking {
        // 7a. Cancel parent while inside reloadCurrentUser()
        val reloadStarted = CompletableDeferred<Unit>()
        val reloadBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_cancel_reload"),
            merchantDoc = sampleValidMerchantDoc("merchant_cancel_reload"),
            reloadHandler = {
                reloadStarted.complete(Unit)
                delay(60_000L)
                sampleVerifiedUser("merchant_cancel_reload")
            }
        )
        MerchantAuthManager.backend = reloadBackend

        var reloadCaughtCancellation = false
        val reloadJob = launch {
            try {
                MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
            } catch (ce: CancellationException) {
                reloadCaughtCancellation = true
                throw ce
            }
        }
        reloadStarted.await()
        reloadJob.cancel()
        reloadJob.join()
        assertTrue("Parent CancellationException during reloadCurrentUser must propagate", reloadCaughtCancellation)
        assertTrue(reloadJob.isCancelled)
        assertEquals(0, reloadBackend.signOutCount)

        // 7b. Cancel parent while inside getMerchantDocument()
        val docFetchStarted = CompletableDeferred<Unit>()
        val fetchBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_cancel_doc"),
            getMerchantHandler = {
                docFetchStarted.complete(Unit)
                delay(60_000L)
                sampleValidMerchantDoc("merchant_cancel_doc")
            }
        )
        MerchantAuthManager.backend = fetchBackend

        var docFetchCaughtCancellation = false
        val docFetchJob = async {
            try {
                MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
            } catch (ce: CancellationException) {
                docFetchCaughtCancellation = true
                throw ce
            }
        }
        docFetchStarted.await()
        docFetchJob.cancel()
        docFetchJob.join()
        assertTrue("Parent CancellationException during getMerchantDocument must propagate", docFetchCaughtCancellation)
        assertTrue(docFetchJob.isCancelled)
        assertEquals(0, fetchBackend.signOutCount)
    }

    @Test
    fun `8 - genuinely deleted or invalid merchant accounts are strictly rejected and signed out`() = runBlocking {
        // 8a. Confirmed missing merchant document (server returned exists = false)
        val deletedBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("deleted_uid"),
            merchantDoc = MerchantDocSnapshot(exists = false)
        )
        MerchantAuthManager.backend = deletedBackend
        val deletedRes = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(deletedRes is MerchantSignInResult.NotRegistered)
        assertEquals(1, deletedBackend.signOutCount)

        // 8b. Wrong accountType (e.g., captain account)
        val captainBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("captain_uid"),
            merchantDoc = sampleValidMerchantDoc("captain_uid").copy(accountType = "captain", role = "captain")
        )
        MerchantAuthManager.backend = captainBackend
        val captainRes = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(captainRes is MerchantSignInResult.NotRegistered)
        assertEquals(1, captainBackend.signOutCount)

        // 8c. Suspended merchant account
        val suspendedBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("suspended_uid"),
            merchantDoc = sampleValidMerchantDoc("suspended_uid").copy(status = "suspended", isApproved = false)
        )
        MerchantAuthManager.backend = suspendedBackend
        val suspendedRes = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(suspendedRes is MerchantSignInResult.NotRegistered)
        assertEquals(1, suspendedBackend.signOutCount)

        // 8d. Unverified email
        val unverifiedBackend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("unverified_uid").copy(isEmailVerified = false),
            merchantDoc = sampleValidMerchantDoc("unverified_uid")
        )
        MerchantAuthManager.backend = unverifiedBackend
        val unverifiedRes = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue(unverifiedRes is MerchantSignInResult.EmailNotVerified)
        assertEquals(1, unverifiedBackend.signOutCount)
    }

    @Test
    fun `9 - order and captain tracking parsers survive malformed snapshot types cleanly`() {
        // Package and timestamp parsers with corrupted/mixed types
        val packages = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "bad_types" to mapOf(
                    "isActive" to true,
                    "planId" to 123,
                    "maxCaptains" to "4",
                    "price" to "500.0",
                    "durationDays" to "30",
                    "features" to "not_a_list"
                ),
                "broken_entry" to mapOf(
                    "isActive" to true,
                    "maxCaptains" to "not_a_number",
                    "price" to null
                )
            )
        )
        assertEquals(1, packages.size)
        assertEquals("123", packages.first().id)
        assertEquals(4, packages.first().maxCaptains)
        assertEquals(500, packages.first().priceEgp)
        assertEquals(emptyList<String>(), packages.first().features)

        assertNotNull(SubscriptionSyncResolver.parseDateMillis(1_763_900_000L))
        assertNotNull(SubscriptionSyncResolver.parseDateMillis(1_763_900_000_000L))
        assertNotNull(SubscriptionSyncResolver.parseDateMillis("2026-11-23T12:00:00.000Z"))
        assertNotNull(SubscriptionSyncResolver.parseDateMillis(mapOf("_seconds" to 1_763_900_000L)))
        assertNull(SubscriptionSyncResolver.parseDateMillis("corrupted_date_value"))

        // Order & captain tracking helper pipeline continues cleanly with real vs placeholder data
        val validStorageUrl = "https://firebasestorage.googleapis.com/v0/b/el-batal-express-956ae.firebasestorage.app/o/captains%2Fc1%2Favatar.jpg?alt=media"
        assertEquals(
            "أحمد محمود",
            FirebaseStorageManager.selectAuthoritativeCaptainName(
                captainDocName = "أحمد محمود",
                orderDocName = "كابتن",
                captainId = "c1"
            )
        )
        assertEquals(
            "01099887766",
            FirebaseStorageManager.selectAuthoritativeCaptainPhone(
                captainDocPhone = "01099887766",
                orderDocPhone = ""
            )
        )
        assertEquals(
            validStorageUrl,
            FirebaseStorageManager.selectBestCaptainPhotoUrl(
                captainDocPhotoUrl = validStorageUrl,
                resolvedOrderStorageUrl = "",
                orderDocPhotoUrl = "content://media/external/images/1"
            )
        )

        val acceptedAt = 1_763_900_000_000L
        OrderCountdownTracker.registerServerAcceptanceTimestamp("order_track_1", "101", "c1", acceptedAt)
        assertEquals(acceptedAt, OrderCountdownTracker.getOrInitStartTimestamp("order_track_1", "c1"))
    }

    @Test
    fun `10 - PERMISSION_DENIED surfaces as a real error and is never converted to fake success`() = runBlocking {
        val permDenied = SecurityException("PERMISSION_DENIED: Missing or insufficient permissions.")

        // 10a. PERMISSION_DENIED is NEVER treated as a transient connectivity error
        assertFalse(MerchantAuthManager.isTransientConnectivityError(permDenied))

        // 10b. Session verification returns real Error (never fake Success)
        val backend = FakeStartupAuthBackend(
            user = sampleVerifiedUser("merchant_perm_denied"),
            cachedMerchantDoc = sampleValidMerchantDoc("merchant_perm_denied"),
            getMerchantHandler = { throw permDenied }
        )
        MerchantAuthManager.backend = backend

        val res = MerchantAuthManager.checkExistingMerchantSessionResult(dummyContext)
        assertTrue("PERMISSION_DENIED must return Error, not Success", res is MerchantSignInResult.Error)
        assertNull(MerchantAuthManager.checkExistingMerchantSession(dummyContext))
        assertEquals("PERMISSION_DENIED must not bypass to cached doc as fake success", 0, backend.cachedDocReadCount)

        // 10c. Subscription resolver never converts server PERMISSION_DENIED into active subscription
        val subState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            hasVerifiedServerOnce = false,
            serverError = "PERMISSION_DENIED: Missing or insufficient permissions."
        )
        assertFalse(subState.canRequestCaptain())
        assertFalse(subState.isSubscribed)
        assertNotNull(subState.serverError)
        assertTrue(subState.serverError!!.contains("PERMISSION_DENIED"))

        // 10d. Interactive login on PERMISSION_DENIED also surfaces real Error (never fake Success)
        val interactivePermRes = MerchantAuthManager.signInWithEmail(dummyContext, "shop@elbatal.com", "secret123")
        assertTrue("Interactive sign-in PERMISSION_DENIED must return Error", interactivePermRes is MerchantSignInResult.Error)
        assertTrue(
            (interactivePermRes as MerchantSignInResult.Error).message.contains("PERMISSION_DENIED") ||
                interactivePermRes.message.contains("فشل التحقق")
        )
    }
}
