package com.example.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class MerchantAccount(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val logo: String = "",
    val storagePath: String = "",
    val phone: String = "",
    val address: String = "",
    val category: String = "",
    val accountType: String = "merchant",
    val emailVerified: Boolean = false,
    val subscriptionStatus: String = "unsubscribed",
    val status: String = "pending",
    val isApproved: Boolean = false,
    val role: String = "merchant"
)

data class MerchantRegistrationRequest(
    val name: String,
    val email: String,
    val phone: String,
    val address: String,
    val password: String,
    val category: String = "",
    val logoUrl: String = "",
    val storagePath: String = ""
)

sealed interface MerchantRegistrationResult {
    data class Success(
        val uid: String,
        val email: String,
        val verificationEmailSent: Boolean,
        val verificationEmailWarning: String? = null
    ) : MerchantRegistrationResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : MerchantRegistrationResult
}

sealed interface MerchantSignInResult {
    data class Success(val merchant: MerchantAccount) : MerchantSignInResult
    data class EmailNotVerified(val email: String, val message: String) : MerchantSignInResult
    data class NotRegistered(val message: String) : MerchantSignInResult
    data class Error(val message: String) : MerchantSignInResult
    object Cancelled : MerchantSignInResult
}

data class AuthUserSnapshot(
    val uid: String,
    val email: String,
    val displayName: String,
    val photoUrl: String,
    val isEmailVerified: Boolean
)

data class MerchantDocSnapshot(
    val exists: Boolean,
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val category: String = "",
    val logo: String = "",
    val storagePath: String = "",
    val accountType: String = "",
    val role: String = "",
    val emailVerified: Boolean = false,
    val subscriptionStatus: String = "unsubscribed",
    val status: String = "",
    val isApproved: Boolean? = null
)

interface MerchantAuthBackend {
    fun currentUser(): AuthUserSnapshot?
    suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    suspend fun signInWithGoogleCredential(idToken: String): AuthUserSnapshot
    suspend fun reloadCurrentUser(): AuthUserSnapshot?
    suspend fun sendEmailVerificationToCurrentUser()
    suspend fun deleteCurrentUser() {}
    suspend fun signOut(context: Context)

    /**
     * Exclusively reads `merchants/{uid}`. Never reads `captains`, `restaurants`, or `users`.
     */
    suspend fun getMerchantDocument(uid: String): MerchantDocSnapshot?

    /**
     * Exclusively creates `merchants/{uid}`. Never writes to `captains`, `restaurants`, or `users`.
     */
    suspend fun createMerchantDocument(uid: String, data: Map<String, Any>)

    /**
     * Updates verification/login fields exclusively in `merchants/{uid}`.
     */
    suspend fun updateMerchantFields(uid: String, updates: Map<String, Any>)
}

object FirebaseMerchantAuthBackend : MerchantAuthBackend {
    private const val TAG = "MerchantAuthBackend"
    private const val COLLECTION_MERCHANTS = "merchants"

    private fun FirebaseUser.toSnapshot(): AuthUserSnapshot = AuthUserSnapshot(
        uid = uid,
        email = email.orEmpty().trim().lowercase(),
        displayName = displayName.orEmpty(),
        photoUrl = photoUrl?.toString().orEmpty(),
        isEmailVerified = isEmailVerified
    )

    override fun currentUser(): AuthUserSnapshot? = Firebase.auth.currentUser?.toSnapshot()

    override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
        val result = Firebase.auth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: throw IllegalStateException("تعذر إنشاء حساب المصادقة في Firebase Auth")
        return user.toSnapshot()
    }

    override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
        val result = withTimeout(10000L) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
        }
        val user = result.user ?: throw IllegalStateException("تعذر استرجاع بيانات الحساب من Firebase Auth")
        return user.toSnapshot()
    }

    override suspend fun signInWithGoogleCredential(idToken: String): AuthUserSnapshot {
        val authCredential = GoogleAuthProvider.getCredential(idToken, null)
        val result = Firebase.auth.signInWithCredential(authCredential).await()
        val user = result.user ?: throw IllegalStateException("تعذر استرجاع بيانات الحساب بعد مصادقة Google")
        return user.toSnapshot()
    }

    override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
        val user = Firebase.auth.currentUser ?: return null
        user.reload().await()
        return Firebase.auth.currentUser?.toSnapshot()
    }

    override suspend fun sendEmailVerificationToCurrentUser() {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("لا توجد جلسة مستخدم نشطة لإرسال رابط التحقق")
        user.sendEmailVerification().await()
    }

    override suspend fun deleteCurrentUser() {
        try {
            Firebase.auth.currentUser?.delete()?.await()
        } catch (e: Exception) {
            Log.w(TAG, "Rollback deleteCurrentUser warning: ${e.message}")
        }
    }

    override suspend fun signOut(context: Context) {
        try {
            Firebase.auth.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase signOut warning: ${e.message}")
        }
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "CredentialManager clear warning: ${e.message}")
        }
    }

    override suspend fun getMerchantDocument(uid: String): MerchantDocSnapshot? {
        val doc = Firebase.firestore.collection(COLLECTION_MERCHANTS).document(uid).get().await()
        if (!doc.exists()) {
            return MerchantDocSnapshot(exists = false)
        }
        val resolvedLogo = doc.getString("logo")?.takeIf { it.isNotBlank() }
            ?: doc.getString("photoUrl")?.takeIf { it.isNotBlank() }
            ?: doc.getString("logoUrl")?.takeIf { it.isNotBlank() }
            ?: ""
        return MerchantDocSnapshot(
            exists = true,
            uid = doc.getString("uid").orEmpty().ifBlank { doc.id },
            name = doc.getString("name").orEmpty(),
            email = doc.getString("email").orEmpty(),
            phone = doc.getString("phone").orEmpty(),
            address = doc.getString("address").orEmpty(),
            category = doc.getString("category").orEmpty(),
            logo = resolvedLogo,
            storagePath = doc.getString("storagePath").orEmpty(),
            accountType = doc.getString("accountType").orEmpty(),
            role = doc.getString("role").orEmpty(),
            emailVerified = doc.getBoolean("emailVerified") == true,
            subscriptionStatus = doc.getString("subscriptionStatus").orEmpty().ifBlank { "unsubscribed" },
            status = doc.getString("status").orEmpty(),
            isApproved = doc.getBoolean("isApproved")
        )
    }

    override suspend fun createMerchantDocument(uid: String, data: Map<String, Any>) {
        Firebase.firestore.collection(COLLECTION_MERCHANTS)
            .document(uid)
            .set(data)
            .await()
    }

    override suspend fun updateMerchantFields(uid: String, updates: Map<String, Any>) {
        Firebase.firestore.collection(COLLECTION_MERCHANTS)
            .document(uid)
            .set(updates, SetOptions.merge())
            .await()
    }
}

object MerchantAuthManager {
    private const val TAG = "MerchantAuth"
    const val COLLECTION_MERCHANTS = "merchants"
    const val ACCOUNT_TYPE_MERCHANT = "merchant"

    val MERCHANT_CATEGORIES = listOf(
        "مطعم",
        "صيدلية",
        "سوبر ماركت",
        "خضار",
        "كافيه",
        "جزارة",
        "أكل حيوانات"
    )

    @Volatile
    internal var backend: MerchantAuthBackend = FirebaseMerchantAuthBackend

    fun resetToDefaultBackend() {
        backend = FirebaseMerchantAuthBackend
    }

    fun getFirestore(context: Context): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }

    /**
     * Registers a new merchant:
     * 1. Creates Firebase Auth user via Email & Password.
     * 2. Creates ONE document exclusively in `merchants/{uid}` with `accountType = "merchant"`.
     * 3. Never creates or modifies documents in `captains`, `restaurants`, or `users`.
     * 4. Sends Firebase Email Verification link. User cannot access the dashboard until verified.
     */
    suspend fun registerMerchant(
        request: MerchantRegistrationRequest,
        logoUploader: (suspend (uid: String) -> Pair<String, String>)? = null
    ): MerchantRegistrationResult {
        val cleanName = request.name.trim()
        val cleanEmail = request.email.trim().lowercase()
        val cleanPhone = request.phone.trim()
        val cleanAddress = request.address.trim().ifBlank { "العنوان غير محدد" }
        val cleanCategory = request.category.trim()
        val cleanPassword = request.password.trim()

        if (cleanName.isEmpty()) {
            return MerchantRegistrationResult.Failure("يرجى إدخال الاسم الكامل")
        }
        if (cleanCategory.isEmpty()) {
            return MerchantRegistrationResult.Failure("يرجى اختيار التصنيف")
        }
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return MerchantRegistrationResult.Failure("يرجى إدخال بريد إلكتروني صحيح")
        }
        if (cleanPhone.isEmpty()) {
            return MerchantRegistrationResult.Failure("يرجى إدخال رقم الهاتف")
        }
        if (cleanPassword.length < 6) {
            return MerchantRegistrationResult.Failure("كلمة المرور يجب أن لا تقل عن 6 أحرف")
        }

        return try {
            val user = backend.createUserWithEmailAndPassword(cleanEmail, cleanPassword)
            val uid = user.uid

            var finalLogoUrl = request.logoUrl
            var finalStoragePath = request.storagePath
            try {
                if (logoUploader != null) {
                    val (uploadedUrl, uploadedPath) = logoUploader(uid)
                    if (uploadedUrl.isNotBlank()) finalLogoUrl = uploadedUrl
                    if (uploadedPath.isNotBlank()) finalStoragePath = uploadedPath
                }

                val merchantData = hashMapOf<String, Any>(
                    "uid" to uid,
                    "name" to cleanName,
                    "email" to cleanEmail,
                    "phone" to cleanPhone,
                    "address" to cleanAddress,
                    "category" to cleanCategory,
                    "logo" to finalLogoUrl,
                    "photoUrl" to finalLogoUrl,
                    "logoUrl" to finalLogoUrl,
                    "storagePath" to finalStoragePath,
                    "accountType" to ACCOUNT_TYPE_MERCHANT,
                    "role" to ACCOUNT_TYPE_MERCHANT,
                    "emailVerified" to false,
                    "status" to "pending",
                    "isSubscribed" to false,
                    "subscriptionStatus" to "unsubscribed",
                    "maxCaptains" to 0,
                    "planId" to 0,
                    "authProvider" to "password",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                // Exclusively create merchants/{uid}; NEVER touch captains/restaurants/users
                backend.createMerchantDocument(uid, merchantData)
            } catch (postAuthEx: Exception) {
                // Roll back the brand-new Auth user if logo upload or Firestore write failed
                backend.deleteCurrentUser()
                throw postAuthEx
            }

            var verificationSent = false
            var verificationWarning: String? = null
            try {
                backend.sendEmailVerificationToCurrentUser()
                verificationSent = true
                Log.d(TAG, "Verification email sent to $cleanEmail (uid=$uid)")
            } catch (verEx: Exception) {
                Log.w(TAG, "Verification email warning for $cleanEmail: ${verEx.message}")
                verificationWarning = verEx.localizedMessage ?: verEx.message
            }

            MerchantRegistrationResult.Success(
                uid = uid,
                email = cleanEmail,
                verificationEmailSent = verificationSent,
                verificationEmailWarning = verificationWarning
            )
        } catch (e: Exception) {
            Log.e(TAG, "Merchant registration failed for $cleanEmail: ${e.message}", e)
            MerchantRegistrationResult.Failure(
                message = mapAuthExceptionToArabic(e),
                cause = e
            )
        }
    }

    /**
     * Re-sends Firebase email verification to the currently signed-in (unverified) user.
     */
    suspend fun resendVerificationEmail(
        context: Context,
        email: String? = null,
        password: String? = null
    ): Result<Unit> {
        return try {
            var current = backend.currentUser()
            if (current == null && !email.isNullOrBlank() && !password.isNullOrBlank()) {
                current = backend.signInWithEmailAndPassword(email.trim().lowercase(), password.trim())
            }
            if (current == null) {
                return Result.failure(IllegalStateException("يرجى إدخال البريد الإلكتروني وكلمة المرور لإعادة إرسال رابط التفعيل"))
            }
            backend.sendEmailVerificationToCurrentUser()
            if (!email.isNullOrBlank() && !password.isNullOrBlank() && !current.isEmailVerified) {
                // Keep unverified user signed out after explicit login-dialog resend
                backend.signOut(context)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reloads Firebase Authentication user state via `reload()` and verifies whether `isEmailVerified == true`.
     * Never relies on Firestore's `emailVerified` field alone — Firebase Auth is the source of truth.
     */
    suspend fun checkEmailVerificationAndSignIn(
        context: Context,
        email: String? = null,
        password: String? = null
    ): MerchantSignInResult {
        return try {
            if (backend.currentUser() == null && !email.isNullOrBlank() && !password.isNullOrBlank()) {
                backend.signInWithEmailAndPassword(email.trim().lowercase(), password.trim())
            }
            val reloaded = backend.reloadCurrentUser()
                ?: return MerchantSignInResult.Error("لا توجد جلسة مستخدم نشطة. يرجى تسجيل الدخول بالبريد وكلمة المرور أولاً.")

            if (!reloaded.isEmailVerified) {
                return MerchantSignInResult.EmailNotVerified(
                    email = reloaded.email,
                    message = "لم يتم تأكيد البريد الإلكتروني بعد. يرجى فتح رسالة التأكيد في بريدك والضغط على الرابط ثم المحاولة مجدداً."
                )
            }

            verifyMerchantIdentity(
                context = context,
                userSnapshot = reloaded,
                reloadBeforeCheck = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading verification status: ${e.message}", e)
            MerchantSignInResult.Error(mapAuthExceptionToArabic(e))
        }
    }

    /**
     * Interactive Google Sign-In using Jetpack Credential Manager.
     * Accepts only pre-registered merchant accounts in `merchants/{uid}` with `accountType == "merchant"`.
     */
    suspend fun signInWithGoogle(
        context: Context,
        activity: Activity
    ): MerchantSignInResult {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            Log.e(TAG, "default_web_client_id missing from resources", e)
            return MerchantSignInResult.Error("إعدادات Google Sign-In غير متوفرة (default_web_client_id)")
        }

        val credentialManager = CredentialManager.create(context)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        val userSnapshot: AuthUserSnapshot = try {
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                backend.signInWithGoogleCredential(googleIdToken)
            } else {
                return MerchantSignInResult.Error("نوع بيانات الاعتماد غير مدعوم")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled: ${e.message}")
            return MerchantSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.message}", e)
            return MerchantSignInResult.Error(e.localizedMessage ?: "فشل في تسجيل الدخول بحساب Google")
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed: ${e.message}", e)
            return MerchantSignInResult.Error(e.localizedMessage ?: "حدث خطأ أثناء المصادقة بحساب Google")
        }

        return verifyMerchantIdentity(context, userSnapshot, reloadBeforeCheck = true)
    }

    /**
     * Strict Merchant Email/Password Sign-In:
     * 1. Authenticates via Firebase Auth.
     * 2. Calls `reload()` and enforces `isEmailVerified == true` from Firebase Auth.
     * 3. Fetches account EXCLUSIVELY from `merchants/{uid}`.
     * 4. Rejects missing documents (no auto-creation) and rejects any non-merchant account (e.g. captains).
     */
    suspend fun signInWithEmail(
        context: Context,
        rawEmail: String,
        rawPassword: String
    ): MerchantSignInResult {
        val cleanInputEmail = rawEmail.trim().lowercase()
        if (cleanInputEmail.isEmpty()) {
            return MerchantSignInResult.Error("يرجى إدخال البريد الإلكتروني")
        }
        val cleanInputPassword = rawPassword.trim()
        if (cleanInputPassword.isEmpty()) {
            return MerchantSignInResult.Error("يرجى إدخال كلمة المرور")
        }

        val authUser: AuthUserSnapshot = try {
            backend.signInWithEmailAndPassword(cleanInputEmail, cleanInputPassword)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed for $cleanInputEmail: ${e.message}", e)
            return MerchantSignInResult.Error(mapSignInErrorToArabic(e))
        }

        return verifyMerchantIdentity(context, authUser, reloadBeforeCheck = true)
    }

    /**
     * Preserved compatibility alias delegating directly to strict [signInWithEmail].
     */
    suspend fun signInWithEmailOrProvision(
        context: Context,
        rawEmail: String,
        rawPassword: String
    ): MerchantSignInResult = signInWithEmail(context, rawEmail, rawPassword)

    /**
     * Validates an existing [FirebaseUser] strictly against Firebase Auth `isEmailVerified`
     * and `merchants/{uid}` (`accountType == "merchant"`).
     */
    suspend fun verifyAndProvisionMerchant(
        context: Context,
        user: FirebaseUser
    ): MerchantSignInResult {
        val initialSnapshot = AuthUserSnapshot(
            uid = user.uid,
            email = user.email.orEmpty().trim().lowercase(),
            displayName = user.displayName.orEmpty(),
            photoUrl = user.photoUrl?.toString().orEmpty(),
            isEmailVerified = user.isEmailVerified
        )
        return verifyMerchantIdentity(context, initialSnapshot, reloadBeforeCheck = true)
    }

    internal suspend fun verifyMerchantIdentity(
        context: Context,
        userSnapshot: AuthUserSnapshot,
        reloadBeforeCheck: Boolean = true
    ): MerchantSignInResult {
        return try {
            val freshUser = if (reloadBeforeCheck) {
                try {
                    backend.reloadCurrentUser() ?: userSnapshot
                } catch (_: Exception) {
                    userSnapshot
                }
            } else {
                userSnapshot
            }

            // Step 1: Authoritative Email Verification check against Firebase Authentication
            if (!freshUser.isEmailVerified) {
                Log.w(TAG, "Login rejected: Email not verified for uid=${freshUser.uid}, email=${freshUser.email}")
                backend.signOut(context)
                return MerchantSignInResult.EmailNotVerified(
                    email = freshUser.email,
                    message = "لا يمكن تسجيل الدخول قبل تأكيد البريد الإلكتروني. يرجى فتح بريدك الإلكتروني والضغط على رابط التأكيد ثم إعادة المحاولة."
                )
            }

            val uid = freshUser.uid

            // Step 2: Read EXCLUSIVELY from `merchants/{uid}`.
            // Never fall back to `captains`, `restaurants`, or `users`.
            val merchantDoc = backend.getMerchantDocument(uid)
            if (merchantDoc == null || !merchantDoc.exists) {
                Log.w(TAG, "Login rejected: No document in merchants/$uid")
                backend.signOut(context)
                return MerchantSignInResult.NotRegistered(
                    "هذا الحساب غير مسجل كتاجر في قاعدة البيانات. لا يمكن الدخول بهذا الحساب إلى تطبيق التاجر."
                )
            }

            // Step 3: Strictly require accountType == "merchant" (rejects captain or other account types)
            val docAccountType = merchantDoc.accountType.trim().lowercase()
            if (docAccountType != ACCOUNT_TYPE_MERCHANT) {
                Log.w(TAG, "Login rejected: uid=$uid has invalid accountType='$docAccountType' in merchants/$uid")
                backend.signOut(context)
                return MerchantSignInResult.NotRegistered(
                    "هذا الحساب ليس حساب تاجر (accountType=$docAccountType) ولا يمكنه الدخول إلى تطبيق التاجر."
                )
            }

            // Step 4: Ensure the account is not suspended/blocked
            val status = merchantDoc.status.trim().lowercase()
            if (status in listOf("pending", "awaiting_approval", "under_review", "قيد المراجعة", "في انتظار الموافقة")) {
                Log.i(TAG, "Login deferred: merchant account is awaiting admin approval (uid=$uid)")
                backend.signOut(context)
                return MerchantSignInResult.NotRegistered(
                    "تم استلام طلب تسجيلك بنجاح. حسابك في انتظار موافقة الإدارة، وسيتمكن من الدخول بعد اعتماد الحساب."
                )
            }
            val isExplicitlyBlocked = status in listOf(
                "rejected", "suspended", "blocked", "banned", "inactive", "pending", "مرفوض", "موقوف", "محظور", "قيد المراجعة"
            ) || merchantDoc.isApproved == false

            if (isExplicitlyBlocked) {
                Log.w(TAG, "Login rejected: Merchant account uid=$uid is blocked/suspended (status=$status)")
                backend.signOut(context)
                return MerchantSignInResult.NotRegistered(
                    "حساب التاجر قيد مراجعة الإدارة أو موقوف. يرجى انتظار الموافقة قبل تسجيل الدخول."
                )
            }

            // Sync verified status & login timestamps ONLY in `merchants/{uid}`
            try {
                backend.updateMerchantFields(
                    uid,
                    mapOf(
                        "emailVerified" to true,
                        "lastLoginAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } catch (syncEx: Exception) {
                Log.w(TAG, "Non-fatal sync warning for merchants/$uid: ${syncEx.message}")
            }

            val finalName = merchantDoc.name.takeIf { it.isNotBlank() }
                ?: freshUser.displayName.takeIf { it.isNotBlank() }
                ?: "تاجر البطل"
            val finalLogo = merchantDoc.logo.takeIf { it.isNotBlank() } ?: freshUser.photoUrl
            val finalEmail = merchantDoc.email.takeIf { it.isNotBlank() } ?: freshUser.email

            MerchantSignInResult.Success(
                MerchantAccount(
                    uid = uid,
                    name = finalName,
                    email = finalEmail,
                    photoUrl = finalLogo,
                    logo = finalLogo,
                    storagePath = merchantDoc.storagePath,
                    phone = merchantDoc.phone,
                    address = merchantDoc.address,
                    category = merchantDoc.category,
                    accountType = ACCOUNT_TYPE_MERCHANT,
                    emailVerified = true,
                    subscriptionStatus = merchantDoc.subscriptionStatus.ifBlank { "unsubscribed" },
                    status = status.ifBlank { "approved" },
                    isApproved = true,
                    role = ACCOUNT_TYPE_MERCHANT
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying merchant session: ${e.message}", e)
            backend.signOut(context)
            MerchantSignInResult.Error(
                "فشل التحقق من حساب التاجر في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            )
        }
    }

    /**
     * Restores session ONLY if Firebase Auth user exists, `isEmailVerified == true` (after `reload()`),
     * and `merchants/{uid}` exists with `accountType == "merchant"`.
     */
    suspend fun checkExistingMerchantSession(context: Context): MerchantAccount? {
        val current = backend.currentUser() ?: return null
        return when (val res = verifyMerchantIdentity(context, current, reloadBeforeCheck = true)) {
            is MerchantSignInResult.Success -> res.merchant
            else -> null
        }
    }

    /**
     * Signs out from Firebase Authentication and clears Credential Manager session.
     */
    suspend fun signOutMerchant(context: Context) {
        backend.signOut(context)
        Log.d(TAG, "Merchant successfully signed out.")
    }

    private fun mapSignInErrorToArabic(error: Throwable): String {
        if (error is FirebaseAuthException) {
            return when (error.errorCode) {
                "ERROR_WRONG_PASSWORD", "auth/wrong-password", "ERROR_INVALID_CREDENTIAL", "auth/invalid-credential" ->
                    "البريد الإلكتروني أو كلمة المرور غير صحيحة"
                "ERROR_USER_NOT_FOUND", "auth/user-not-found" ->
                    "هذا الحساب غير مسجل"
                "ERROR_INVALID_EMAIL", "auth/invalid-email" ->
                    "صيغة البريد الإلكتروني غير صحيحة"
                "ERROR_USER_DISABLED", "auth/user-disabled" ->
                    "تم تعطيل هذا الحساب"
                "ERROR_TOO_MANY_REQUESTS", "auth/too-many-requests" ->
                    "تم إجراء محاولات كثيرة، يرجى الانتظار والمحاولة لاحقاً"
                "ERROR_NETWORK_REQUEST_FAILED", "auth/network-request-failed" ->
                    "تعذر الاتصال بالإنترنت، يرجى التحقق من الشبكة"
                else -> "تعذر تسجيل الدخول: ${error.localizedMessage ?: error.message}"
            }
        }
        val msg = error.message.orEmpty()
        return if (msg.contains("timeout", ignoreCase = true) || msg.contains("network", ignoreCase = true)) {
            "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
        } else {
            "لا يمكن تسجيل الدخول: البريد الإلكتروني أو كلمة المرور غير صحيحة، أو أن الحساب غير مسجل"
        }
    }

    private fun mapAuthExceptionToArabic(error: Throwable): String {
        if (error is FirebaseAuthException) {
            return when (error.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE", "auth/email-already-in-use", "email-already-in-use" ->
                    "البريد الإلكتروني مستخدم مسبقًا"
                "ERROR_INVALID_EMAIL", "auth/invalid-email", "invalid-email" ->
                    "البريد الإلكتروني غير صحيح"
                "ERROR_WEAK_PASSWORD", "auth/weak-password", "weak-password" ->
                    "كلمة المرور ضعيفة (يجب ألا تقل عن 6 أحرف)"
                "ERROR_TOO_MANY_REQUESTS", "auth/too-many-requests" ->
                    "تم إرسال طلبات كثيرة، يرجى الانتظار قليلاً والمحاولة لاحقاً"
                "ERROR_NETWORK_REQUEST_FAILED", "auth/network-request-failed" ->
                    "تعذر الاتصال بالشبكة، يرجى التحقق من الإنترنت"
                else -> error.localizedMessage ?: error.message ?: "حدث خطأ غير معروف"
            }
        }
        return error.localizedMessage ?: error.message ?: "حدث خطأ أثناء المصادقة"
    }
}
