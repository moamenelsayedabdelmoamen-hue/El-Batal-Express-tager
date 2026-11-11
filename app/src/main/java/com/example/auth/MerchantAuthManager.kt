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
        return try {
            val dbId = context.getString(R.string.firestore_database_id)
            if (dbId.isNotBlank()) FirebaseFirestore.getInstance(dbId) else FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            FirebaseFirestore.getInstance()
        }
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
     * Verifies merchant pre-registration status.
     * If not registered, immediately signs out and returns the strict error message.
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

        val isSystemAdmin = email == ADMIN_WHITELIST_EMAIL.lowercase() || email == ADMIN_ALT_EMAIL.lowercase()

        try {
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

            // 3. Check if document exists in 'restaurants' by UID or email (existing merchant store)
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

            val existingDoc = merchantDocByUid?.takeIf { it.exists() }
                ?: merchantDocByEmail?.takeIf { it.exists() }
                ?: restaurantDoc?.takeIf { it.exists() }

            val isPreRegistered = existingDoc != null || isSystemAdmin

            if (!isPreRegistered) {
                // Requirement 7, 8, 9: Do NOT create a new account. Reject immediately!
                Log.w(TAG, "Merchant sign in rejected: Account $email (uid: $uid) is not registered as a merchant.")
                signOutMerchant(context)
                return MerchantSignInResult.NotRegistered(
                    "هذا الحساب غير مسجل كتاجر في البطل أكسبريس، يرجى التواصل مع الإدارة"
                )
            }

            // Check if account was explicitly rejected or suspended
            val status = existingDoc?.getString("status")?.lowercase()
            val isApprovedField = existingDoc?.getBoolean("isApproved")
            if (status == "rejected" || status == "suspended" || isApprovedField == false) {
                Log.w(TAG, "Merchant account is rejected or suspended.")
                signOutMerchant(context)
                return MerchantSignInResult.NotRegistered(
                    "حساب التاجر هذا موقوف أو قيد المراجعة، يرجى التواصل مع الإدارة"
                )
            }

            // Account is verified! Retrieve existing merchant profile
            val existingName = existingDoc?.getString("name")?.takeIf { it.isNotBlank() }
                ?: existingDoc?.getString("restaurantName")?.takeIf { it.isNotBlank() }
            val existingPhone = existingDoc?.getString("phone")?.takeIf { it.isNotBlank() }.orEmpty()
            val existingPhoto = existingDoc?.getString("photoUrl")?.takeIf { it.isNotBlank() }
                ?: existingDoc?.getString("logoUrl")?.takeIf { it.isNotBlank() }.orEmpty()
            val existingAddress = existingDoc?.getString("address")?.takeIf { it.isNotBlank() }.orEmpty()

            // CRITICAL: Preserve custom profile data! Only use Google default if merchant hasn't set one yet.
            val finalName = existingName ?: displayName.takeIf { it.isNotBlank() } ?: "تاجر البطل"
            val finalPhoto = if (existingPhoto.isNotBlank()) existingPhoto else photoUrl
            val finalPhone = existingPhone
            val finalAddress = existingAddress

            val merchantPayload = hashMapOf<String, Any>(
                "uid" to uid,
                "name" to finalName,
                "email" to email,
                "photoUrl" to finalPhoto,
                "phone" to finalPhone,
                "address" to finalAddress,
                "role" to "merchant",
                "status" to (existingDoc?.getString("status") ?: "approved"),
                "isApproved" to true,
                "authProvider" to "google.com",
                "lastLoginAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // Save in merchants/{uid}
            try {
                db.collection("merchants").document(uid).set(merchantPayload, SetOptions.merge()).await()
                Log.d(TAG, "Successfully updated merchants/$uid")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to merchants/$uid: ${e.message}")
            }

            // Also keep restaurants/{uid} synchronized so restaurant orders and dashboard functions continue seamlessly
            try {
                val restaurantPayload = hashMapOf<String, Any>(
                    "uid" to uid,
                    "ownerId" to uid,
                    "name" to finalName,
                    "email" to email,
                    "phone" to finalPhone,
                    "photoUrl" to finalPhoto,
                    "logoUrl" to finalPhoto,
                    "address" to finalAddress,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                db.collection("restaurants").document(uid).set(restaurantPayload, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.w(TAG, "Warning: Syncing restaurants/$uid: ${e.message}")
            }

            val merchantAccount = MerchantAccount(
                uid = uid,
                name = finalName,
                email = email,
                photoUrl = finalPhoto,
                phone = finalPhone,
                address = finalAddress,
                status = "approved",
                isApproved = true,
                role = "merchant"
            )

            return MerchantSignInResult.Success(merchantAccount)

        } catch (e: Exception) {
            Log.e(TAG, "Error verifying merchant: ${e.message}", e)
            signOutMerchant(context)
            return MerchantSignInResult.Error("فشل التحقق من حساب التاجر: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Restores session if the current Firebase user is an approved merchant.
     */
    suspend fun checkExistingMerchantSession(context: Context): MerchantAccount? {
        val user = Firebase.auth.currentUser ?: return null
        val db = getFirestore(context)
        val uid = user.uid
        val email = user.email.orEmpty().trim().lowercase()

        val isSystemAdmin = email == ADMIN_WHITELIST_EMAIL.lowercase() || email == ADMIN_ALT_EMAIL.lowercase()

        return try {
            val merchantDoc = db.collection("merchants").document(uid).get().await()
            if (merchantDoc.exists()) {
                val status = merchantDoc.getString("status")?.lowercase()
                val isApproved = merchantDoc.getBoolean("isApproved") != false
                if (status != "rejected" && isApproved) {
                    MerchantAccount(
                        uid = uid,
                        name = merchantDoc.getString("name") ?: user.displayName.orEmpty(),
                        email = merchantDoc.getString("email") ?: email,
                        photoUrl = merchantDoc.getString("photoUrl") ?: user.photoUrl?.toString().orEmpty(),
                        phone = merchantDoc.getString("phone").orEmpty(),
                        address = merchantDoc.getString("address").orEmpty(),
                        status = status ?: "approved",
                        isApproved = isApproved,
                        role = "merchant"
                    )
                } else null
            } else if (isSystemAdmin) {
                MerchantAccount(
                    uid = uid,
                    name = user.displayName ?: "مدير النظام",
                    email = email,
                    photoUrl = user.photoUrl?.toString().orEmpty(),
                    status = "approved",
                    isApproved = true,
                    role = "admin"
                )
            } else {
                // Check existing restaurants collection for legacy login
                val resDoc = db.collection("restaurants").document(uid).get().await()
                if (resDoc.exists()) {
                    MerchantAccount(
                        uid = uid,
                        name = resDoc.getString("name") ?: user.displayName.orEmpty(),
                        email = resDoc.getString("email") ?: email,
                        photoUrl = resDoc.getString("photoUrl") ?: resDoc.getString("logoUrl") ?: user.photoUrl?.toString().orEmpty(),
                        phone = resDoc.getString("phone").orEmpty(),
                        address = resDoc.getString("address").orEmpty(),
                        status = "approved",
                        isApproved = true,
                        role = "merchant"
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking merchant session: ${e.message}")
            null
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
