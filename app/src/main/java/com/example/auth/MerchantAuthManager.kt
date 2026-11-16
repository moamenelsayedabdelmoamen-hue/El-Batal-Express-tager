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
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

data class MerchantAccount(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val phone: String = "",
    val address: String = "",
    val status: String = "approved",
    val isApproved: Boolean = true,
    val role: String = "merchant"
)

sealed interface MerchantSignInResult {
    data class Success(val merchant: MerchantAccount) : MerchantSignInResult
    data class NotRegistered(val message: String) : MerchantSignInResult
    data class Error(val message: String) : MerchantSignInResult
    object Cancelled : MerchantSignInResult
}

object MerchantAuthManager {
    private const val TAG = "MerchantAuth"
    private const val ADMIN_WHITELIST_EMAIL = "moamen.elsayed.abdelmoamen@gmail.com"
    private const val ADMIN_ALT_EMAIL = "admin@elbatalexpress.com"

    fun getFirestore(context: Context): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }

    /**
     * Interactive Google Sign-In using Jetpack Credential Manager.
     * Enforces that only pre-registered and approved merchant accounts can access the merchant dashboard.
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

        val firebaseUser: FirebaseUser = try {
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                authResult.user ?: throw IllegalStateException("FirebaseUser is null after Google authentication")
            } else {
                return MerchantSignInResult.Error("نوع بيانات الاعتماد غير مدعوم")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In was cancelled by user: ${e.message}")
            return MerchantSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: ${e.message}", e)
            return MerchantSignInResult.Error(e.localizedMessage ?: "فشل في تسجيل الدخول بحساب Google")
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed: ${e.message}", e)
            return MerchantSignInResult.Error(e.localizedMessage ?: "حدث خطأ أثناء المصادقة بحساب Google")
        }

        // Verify that the signed-in user is pre-registered and approved as a merchant
        return verifyAndProvisionMerchant(context, firebaseUser)
    }

    /**
     * Strict Email/Password Sign-In for Merchants.
     * Allows login ONLY if the user is already registered in Firebase Auth AND has a verified/approved
     * record in the Firestore database (`merchants`, `restaurants`, or `users`).
     * Never auto-creates accounts or subscriptions on login.
     */
    suspend fun signInWithEmailOrProvision(
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

        val auth = FirebaseAuth.getInstance()

        val firebaseUser: FirebaseUser = try {
            val res = kotlinx.coroutines.withTimeout(10000L) {
                auth.signInWithEmailAndPassword(cleanInputEmail, cleanInputPassword).await()
            }
            res.user ?: throw IllegalStateException("تعذر التحقق من بيانات الحساب")
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed for $cleanInputEmail: ${e.message}", e)
            return MerchantSignInResult.Error(
                "لا يمكن تسجيل الدخول: البريد الإلكتروني أو كلمة المرور غير صحيحة، أو أن الحساب غير مسجل"
            )
        }

        return verifyAndProvisionMerchant(context, firebaseUser)
    }

    /**
     * Strictly verifies that the user is already registered in the Firestore database
     * (`merchants`, `restaurants`, or `users`) and that their account is verified/approved.
     * If not registered or not verified, immediately signs out and rejects login.
     */
    suspend fun verifyAndProvisionMerchant(
        context: Context,
        user: FirebaseUser
    ): MerchantSignInResult {
        val db = getFirestore(context)
        val uid = user.uid
        val email = user.email.orEmpty().trim().lowercase()
        val displayName = user.displayName.orEmpty()
        val photoUrl = user.photoUrl?.toString().orEmpty()

        try {
            try {
                user.reload().await()
            } catch (_: Exception) {}

            // 1. Check if document exists in 'merchants' by UID
            val merchantDocByUid = try {
                db.collection("merchants").document(uid).get().await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get merchants/$uid: ${e.message}")
                null
            }

            // 2. Check if document exists in 'merchants' by email
            val merchantDocByEmail = if ((merchantDocByUid == null || !merchantDocByUid.exists()) && email.isNotBlank()) {
                try {
                    val query = db.collection("merchants").whereEqualTo("email", email).limit(1).get().await()
                    if (!query.isEmpty) query.documents.first() else null
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query merchants by email: ${e.message}")
                    null
                }
            } else null

            // 3. Check if document exists in 'restaurants' by UID or email
            val restaurantDoc = if ((merchantDocByUid == null || !merchantDocByUid.exists()) && (merchantDocByEmail == null || !merchantDocByEmail.exists())) {
                try {
                    val byUid = db.collection("restaurants").document(uid).get().await()
                    if (byUid.exists()) byUid else if (email.isNotBlank()) {
                        val byEmail = db.collection("restaurants").whereEqualTo("email", email).limit(1).get().await()
                        if (!byEmail.isEmpty) byEmail.documents.first() else null
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to check restaurants collection: ${e.message}")
                    null
                }
            } else null

            // 4. Check if document exists in 'users' by UID
            val userDoc = if (merchantDocByUid?.exists() != true && merchantDocByEmail?.exists() != true && restaurantDoc?.exists() != true) {
                try {
                    val byUid = db.collection("users").document(uid).get().await()
                    if (byUid.exists()) byUid else null
                } catch (e: Exception) {
                    null
                }
            } else null

            val existingDoc = merchantDocByUid?.takeIf { it.exists() }
                ?: merchantDocByEmail?.takeIf { it.exists() }
                ?: restaurantDoc?.takeIf { it.exists() }
                ?: userDoc?.takeIf { it.exists() }

            if (existingDoc == null) {
                Log.w(TAG, "Login rejected: Account $email (uid: $uid) is not registered in Firestore.")
                signOutMerchant(context)
                return MerchantSignInResult.NotRegistered(
                    "لا يمكن تسجيل الدخول: هذا الحساب غير مسجل في قاعدة البيانات"
                )
            }

            // Verify that the account is confirmed/approved in the database
            val status = existingDoc.getString("status")?.trim()?.lowercase() ?: ""
            val isApprovedField = existingDoc.getBoolean("isApproved")
            val isVerifiedField = existingDoc.getBoolean("isVerified") ?: existingDoc.getBoolean("verified")

            val isExplicitlyBlocked = status in listOf("rejected", "suspended", "pending", "unverified", "inactive", "قيد المراجعة", "مرفوض", "موقوف", "غير مفعل") ||
                isApprovedField == false ||
                isVerifiedField == false

            val isConfirmedInDb = (isApprovedField == true) ||
                (isVerifiedField == true) ||
                status in listOf("approved", "active", "verified", "معتمد", "مفعل", "نشط")

            if (isExplicitlyBlocked || !isConfirmedInDb) {
                Log.w(TAG, "Login rejected: Merchant account $email is not verified/approved (status=$status, isApproved=$isApprovedField, isVerified=$isVerifiedField).")
                signOutMerchant(context)
                return MerchantSignInResult.NotRegistered(
                    "لا يمكن تسجيل الدخول: حسابك غير مؤكد أو لم يتم تفعيله بعد في قاعدة البيانات"
                )
            }

            val existingName = existingDoc.getString("name")?.takeIf { it.isNotBlank() }
                ?: existingDoc.getString("restaurantName")?.takeIf { it.isNotBlank() }
            val existingPhone = existingDoc.getString("phone")?.takeIf { it.isNotBlank() }.orEmpty()
            val existingPhoto = existingDoc.getString("photoUrl")?.takeIf { it.isNotBlank() }
                ?: existingDoc.getString("logoUrl")?.takeIf { it.isNotBlank() }.orEmpty()
            val existingAddress = existingDoc.getString("address")?.takeIf { it.isNotBlank() }.orEmpty()

            val finalName = existingName ?: displayName.takeIf { it.isNotBlank() } ?: "تاجر البطل"
            val finalPhoto = if (existingPhoto.isNotBlank()) existingPhoto else photoUrl
            val finalPhone = existingPhone
            val finalAddress = existingAddress

            // Update lastLoginAt only on existing verified account
            try {
                db.collection("merchants").document(uid).set(
                    mapOf(
                        "lastLoginAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            } catch (_: Exception) {}

            val merchantAccount = MerchantAccount(
                uid = uid,
                name = finalName,
                email = email,
                photoUrl = finalPhoto,
                phone = finalPhone,
                address = finalAddress,
                status = status.ifBlank { "approved" },
                isApproved = true,
                role = "merchant"
            )

            return MerchantSignInResult.Success(merchantAccount)

        } catch (e: Exception) {
            Log.e(TAG, "Error verifying merchant: ${e.message}", e)
            signOutMerchant(context)
            return MerchantSignInResult.Error("فشل التحقق من حساب التاجر في قاعدة البيانات: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Restores session ONLY if the current Firebase user exists in the database and is verified/approved.
     */
    suspend fun checkExistingMerchantSession(context: Context): MerchantAccount? {
        val user = Firebase.auth.currentUser ?: return null
        return when (val res = verifyAndProvisionMerchant(context, user)) {
            is MerchantSignInResult.Success -> res.merchant
            else -> null
        }
    }

    /**
     * Signs out from Firebase Authentication and clears Credential Manager session.
     */
    suspend fun signOutMerchant(context: Context) {
        try {
            Firebase.auth.signOut()
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Log.d(TAG, "Merchant successfully signed out and credential state cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during signOutMerchant: ${e.message}", e)
        }
    }
}
