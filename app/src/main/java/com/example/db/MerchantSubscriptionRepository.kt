package com.example.db

import android.util.Log
import com.example.notification.MerchantMessagingService
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.functions
import kotlinx.coroutines.tasks.await

data class CaptainRequestResult(
    val orderId: String,
    val orderCode: String,
    val planId: Int = 0,
    val planName: String = "",
    val captainLimit: Int = 0,
    val activeCaptainsCount: Int = 0,
    val remainingCaptains: Int = 0,
    val subscriptionVersion: Long = 1L,
    val message: String = "تم إنشاء طلب التوصيل بنجاح"
)

data class ServerSubscriptionFetchResult(
    val subscriptionSnapshot: RawSubscriptionSnapshot? = null,
    val merchantDocument: DocumentSnapshot? = null,
    val activeCaptainsCount: Int? = null,
    val verifiedFromServer: Boolean = false,
    val errorMessage: String? = null
)

data class ServerSubscriptionValidationResult(
    val allowed: Boolean,
    val snapshot: RawSubscriptionSnapshot,
    val activeCaptainsCount: Int = 0,
    val message: String? = null
)

/**
 * Central repository bridging the Merchant Android App with:
 * 1. Real-time Firestore snapshot listeners (`subscriptions/{uid}` and `merchants/{uid}`) with MetadataChanges.INCLUDE.
 * 2. Initial & on-demand `Source.SERVER` verification + Firebase Cloud Functions (`getMerchantSubscription`, `validateMerchantSubscription`, `requestCaptains`).
 * 3. FCM device token persistence for instant subscription sync push signals.
 */
class MerchantSubscriptionRepository {

    companion object {
        private const val TAG = "MerchantSubRepo"
        const val FUNCTIONS_REGION = "us-central1"
        const val PRIMARY_ORDER_FUNCTION = "requestCaptains"
        const val LEGACY_SERVER_ORDER_FUNCTION = "createOrderWithCode"

        /**
         * Distinguishes HTTP 404 endpoint-missing `NOT_FOUND` (undeployed Cloud Function or wrong region)
         * from an application-level `HttpsError("not-found", ...)` returned by our Cloud Function code.
         */
        fun isEndpointNotFoundError(
            code: FirebaseFunctionsException.Code,
            rawMessage: String?,
            details: Any?
        ): Boolean {
            if (code != FirebaseFunctionsException.Code.NOT_FOUND) return false
            if (details != null) return false
            val trimmed = rawMessage?.trim().orEmpty()
            return trimmed.isEmpty() ||
                trimmed.equals("NOT_FOUND", ignoreCase = true) ||
                trimmed.equals("Not Found", ignoreCase = true)
        }

        /**
         * Maps Cloud Function errors to clear, accurate Arabic messages matching the true root cause.
         * Never leaks raw technical codes like literal "NOT_FOUND" to the merchant UI.
         */
        fun mapRequestCaptainsError(
            code: FirebaseFunctionsException.Code,
            rawMessage: String?,
            details: Any?,
            endpointUnavailableOnServer: Boolean = false
        ): String {
            val detailsMap = details as? Map<*, *>
            val reasonCode = detailsMap?.get("reasonCode")?.toString()?.trim()?.uppercase().orEmpty()
            val cleanedMsg = rawMessage?.trim()?.takeIf {
                it.isNotBlank() &&
                    !it.equals("NOT_FOUND", ignoreCase = true) &&
                    !it.equals("INTERNAL", ignoreCase = true) &&
                    !it.equals("UNKNOWN", ignoreCase = true)
            }

            if (endpointUnavailableOnServer || isEndpointNotFoundError(code, rawMessage, details)) {
                return "خدمة إنشاء الطلب غير متاحة حالياً على السيرفر (وظيفة $PRIMARY_ORDER_FUNCTION غير منشورة بعد في منطقة $FUNCTIONS_REGION). يرجى نشر Cloud Functions ثم المحاولة مرة أخرى."
            }

            when (reasonCode) {
                "MERCHANT_NOT_FOUND" -> return "تعذر العثور على بيانات حساب التاجر."
                "SUBSCRIPTION_NOT_FOUND" -> return "لم يتم العثور على اشتراك التاجر."
                "MISSING_CUSTOMER_ADDRESS" -> return "بيانات عنوان العميل غير مكتملة. يرجى إدخال عنوان التوصيل."
                "MERCHANT_PENDING_APPROVAL" -> return "حساب التاجر في انتظار موافقة الإدارة."
                "EMAIL_NOT_VERIFIED" -> return "يجب تأكيد البريد الإلكتروني أولاً قبل طلب كابتن."
                "CAPTAIN_LIMIT_REACHED" -> return cleanedMsg ?: "وصلت إلى الحد الأقصى لعدد الكباتن المتزامنين في باقتك الحالية."
            }

            return when (code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    cleanedMsg ?: "يجب تسجيل الدخول أولاً لطلب كابتن."
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    cleanedMsg ?: "بيانات الطلب غير مكتملة. يرجى التحقق من عنوان العميل وخدمة التوصيل."
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    cleanedMsg ?: "تعذر العثور على البيانات المطلوبة لإتمام الطلب على السيرفر."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    cleanedMsg
                        ?: "تم رفض الطلب من السيرفر: يرجى التحقق من حالة الاشتراك والحد الأقصى للكباتن."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "تعذر الاتصال بسيرفر الطلبات حالياً. يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى."
                FirebaseFunctionsException.Code.INTERNAL ->
                    cleanedMsg ?: "تعذر إنشاء الطلب على السيرفر حالياً، يرجى المحاولة مرة أخرى."
                else ->
                    cleanedMsg ?: "حدث خطأ أثناء تنفيذ الطلب على السيرفر."
            }
        }
    }

    private fun functionsClient() = Firebase.functions(FUNCTIONS_REGION)

    fun observeSubscriptionDocument(
        uid: String,
        onSnapshot: (raw: RawSubscriptionSnapshot, fromServer: Boolean) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("subscriptions")
            .document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                val raw = SubscriptionSyncResolver.fromFirestoreSnapshot(snap)
                val fromServer = snap != null && !snap.metadata.isFromCache
                onSnapshot(raw, fromServer)
            }
    }

    /**
     * Verifies subscription state directly against BOTH:
     * 1. Cloud Function `getMerchantSubscription` (pinned to us-central1)
     * 2. Firestore `Source.SERVER` on `subscriptions/{uid}` and `merchants/{uid}`
     * Reconciles by highest `subscriptionVersion` so stale data never overrides fresh Admin activations,
     * and returns a clear `errorMessage` on failure rather than falsely flipping to `inactive`.
     */
    suspend fun fetchAuthoritativeSubscription(uid: String): ServerSubscriptionFetchResult {
        if (uid.isBlank()) return ServerSubscriptionFetchResult()

        var callableSnapshot: RawSubscriptionSnapshot? = null
        var callableActiveCount: Int? = null
        var verified = false
        var lastError: String? = null

        val currentUser = Firebase.auth.currentUser
        if (currentUser != null && currentUser.uid == uid && currentUser.isEmailVerified) {
            try {
                val callableResult = functionsClient()
                    .getHttpsCallable("getMerchantSubscription")
                    .call(hashMapOf("merchantId" to uid))
                    .await()

                val dataMap = callableResult.getData() as? Map<*, *>
                if (dataMap != null) {
                    callableSnapshot = SubscriptionSyncResolver.fromServerCallableMap(dataMap)
                    callableActiveCount = (dataMap["activeCaptainsCount"] as? Number)?.toInt()
                    verified = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "getMerchantSubscription callable fallback to Firestore Source.SERVER: ${e.message}")
                lastError = e.localizedMessage ?: e.message
            }
        }

        // Fetch fresh Firestore Source.SERVER documents directly so profile and dual-doc snapshots stay aligned
        var serverSubSnap: DocumentSnapshot? = null
        var serverMerchSnap: DocumentSnapshot? = null
        try {
            serverSubSnap = try {
                Firebase.firestore.collection("subscriptions").document(uid).get(Source.SERVER).await()
            } catch (e: Exception) {
                Log.w(TAG, "Source.SERVER read subscriptions/$uid failed: ${e.message}")
                if (lastError == null) lastError = e.localizedMessage ?: e.message
                null
            }
            serverMerchSnap = try {
                Firebase.firestore.collection("merchants").document(uid).get(Source.SERVER).await()
            } catch (e: Exception) {
                Log.w(TAG, "Source.SERVER read merchants/$uid failed: ${e.message}")
                if (lastError == null) lastError = e.localizedMessage ?: e.message
                null
            }
            if (serverSubSnap != null || serverMerchSnap != null) {
                verified = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore Source.SERVER subscription read failed: ${e.message}")
            if (lastError == null) lastError = e.localizedMessage ?: e.message
        }

        val firestoreSubSnapshot = serverSubSnap?.let {
            SubscriptionSyncResolver.fromFirestoreSnapshot(it)
        }

        val finalSubSnapshot = when {
            callableSnapshot != null && firestoreSubSnapshot != null -> {
                val callableVer = callableSnapshot.subscriptionVersion ?: 0L
                val fsVer = firestoreSubSnapshot.subscriptionVersion ?: 0L
                val callableNorm = SubscriptionSyncResolver.normalizeStatus(callableSnapshot.status)
                val fsNorm = SubscriptionSyncResolver.normalizeStatus(firestoreSubSnapshot.status ?: firestoreSubSnapshot.subscriptionStatus)
                when {
                    fsVer > callableVer -> firestoreSubSnapshot
                    callableVer > fsVer -> callableSnapshot
                    callableNorm == "unsubscribed" && fsNorm != "unsubscribed" && firestoreSubSnapshot.exists -> firestoreSubSnapshot
                    else -> callableSnapshot
                }
            }
            callableSnapshot != null -> callableSnapshot
            else -> firestoreSubSnapshot
        }

        return ServerSubscriptionFetchResult(
            subscriptionSnapshot = finalSubSnapshot,
            merchantDocument = serverMerchSnap,
            activeCaptainsCount = callableActiveCount,
            verifiedFromServer = verified,
            errorMessage = if (!verified) {
                "تعذر التحقق من حالة الاشتراك مع السيرفر. يرجى التحقق من الاتصال والمحاولة مرة أخرى."
            } else {
                null
            }
        )
    }

    /**
     * Calls Cloud Function `validateMerchantSubscription` before critical captain dispatch actions.
     */
    suspend fun validateSubscriptionOnServer(requestedCaptains: Int = 1): ServerSubscriptionValidationResult? {
        val user = Firebase.auth.currentUser ?: return null
        if (!user.isEmailVerified) return null
        return try {
            val res = functionsClient()
                .getHttpsCallable("validateMerchantSubscription")
                .call(hashMapOf("requestedCaptains" to requestedCaptains))
                .await()
            val map = res.getData() as? Map<*, *> ?: return null
            val allowed = (map["allowed"] as? Boolean) == true || (map["isValid"] as? Boolean) == true
            val activeCount = (map["activeCaptainsCount"] as? Number)?.toInt() ?: 0
            val snapshot = SubscriptionSyncResolver.fromServerCallableMap(map)
            val msg = map["errorMessage"]?.toString()?.takeIf { it.isNotBlank() }
                ?: map["reason"]?.toString()?.takeIf { it.isNotBlank() }
            ServerSubscriptionValidationResult(
                allowed = allowed,
                snapshot = snapshot,
                activeCaptainsCount = activeCount,
                message = msg
            )
        } catch (e: Exception) {
            Log.w(TAG, "validateMerchantSubscription check warning: ${e.message}")
            null
        }
    }

    private suspend fun invokeServerOrderCallable(
        functionName: String,
        payload: Map<String, Any?>
    ): CaptainRequestResult {
        val result = functionsClient()
            .getHttpsCallable(functionName)
            .call(payload)
            .await()

        val data = result.getData() as? Map<*, *>
            ?: throw IllegalStateException("استجابة غير صالحة من السيرفر عند إنشاء الطلب.")

        val orderId = data["orderId"]?.toString()?.trim().orEmpty()
        val orderCode = data["orderCode"]?.toString()?.trim().orEmpty()
        if (orderId.isBlank() || orderCode.isBlank()) {
            throw IllegalStateException("تعذر استلام بيانات وكود الطلب من السيرفر.")
        }

        return CaptainRequestResult(
            orderId = orderId,
            orderCode = orderCode,
            planId = (data["planId"] as? Number)?.toInt() ?: 0,
            planName = data["planName"]?.toString().orEmpty(),
            captainLimit = (data["captainLimit"] as? Number)?.toInt() ?: 0,
            activeCaptainsCount = (data["activeCaptainsCount"] as? Number)?.toInt() ?: 0,
            remainingCaptains = (data["remainingCaptains"] as? Number)?.toInt() ?: 0,
            subscriptionVersion = (data["subscriptionVersion"] as? Number)?.toLong() ?: 1L,
            message = data["message"]?.toString().orEmpty().ifBlank { "تم إنشاء طلب التوصيل بنجاح" }
        )
    }

    /**
     * Authoritative Cloud Function order creation & captain dispatch request (`requestCaptains`).
     * Enforces auth, verified email, active server subscription, non-expired date, and captain limit atomically.
     * If `requestCaptains` returns HTTP 404 (not yet deployed on the project), seamlessly falls back ONLY to
     * the server-enforced callable `createOrderWithCode` with the exact same `idempotencyKey` — NEVER writes
     * directly to Firestore from the client.
     */
    suspend fun requestCaptains(payload: Map<String, Any?>): Result<CaptainRequestResult> {
        val merchantUid = Firebase.auth.currentUser?.uid.orEmpty()
        val requestId = payload["requestId"]?.toString()
            ?: payload["idempotencyKey"]?.toString()
            ?: "unknown"

        return try {
            Result.success(invokeServerOrderCallable(PRIMARY_ORDER_FUNCTION, payload))
        } catch (primaryErr: FirebaseFunctionsException) {
            if (isEndpointNotFoundError(primaryErr.code, primaryErr.message, primaryErr.details)) {
                Log.w(
                    TAG,
                    "Cloud Function endpoint NOT_FOUND | functionName=$PRIMARY_ORDER_FUNCTION region=$FUNCTIONS_REGION errorCode=${primaryErr.code} merchantId=$merchantUid requestId=$requestId timestamp=${System.currentTimeMillis()} -> attempting server callable $LEGACY_SERVER_ORDER_FUNCTION"
                )
                return try {
                    Result.success(invokeServerOrderCallable(LEGACY_SERVER_ORDER_FUNCTION, payload))
                } catch (fallbackErr: FirebaseFunctionsException) {
                    val bothMissing = isEndpointNotFoundError(
                        fallbackErr.code,
                        fallbackErr.message,
                        fallbackErr.details
                    )
                    Log.e(
                        TAG,
                        "Server order callable failed | functionName=$LEGACY_SERVER_ORDER_FUNCTION region=$FUNCTIONS_REGION errorCode=${fallbackErr.code} merchantId=$merchantUid requestId=$requestId timestamp=${System.currentTimeMillis()}"
                    )
                    val mapped = mapRequestCaptainsError(
                        code = fallbackErr.code,
                        rawMessage = fallbackErr.message,
                        details = fallbackErr.details,
                        endpointUnavailableOnServer = bothMissing
                    )
                    Result.failure(IllegalStateException(mapped, fallbackErr))
                } catch (fallbackUnexpected: Exception) {
                    Log.e(
                        TAG,
                        "Unexpected error on $LEGACY_SERVER_ORDER_FUNCTION | merchantId=$merchantUid requestId=$requestId timestamp=${System.currentTimeMillis()}: ${fallbackUnexpected.message}"
                    )
                    Result.failure(
                        IllegalStateException(
                            fallbackUnexpected.localizedMessage ?: "تعذر إنشاء الطلب عبر السيرفر، يرجى المحاولة مرة أخرى.",
                            fallbackUnexpected
                        )
                    )
                }
            }

            Log.e(
                TAG,
                "Server order callable rejected | functionName=$PRIMARY_ORDER_FUNCTION region=$FUNCTIONS_REGION errorCode=${primaryErr.code} merchantId=$merchantUid requestId=$requestId timestamp=${System.currentTimeMillis()}"
            )
            val msg = mapRequestCaptainsError(
                code = primaryErr.code,
                rawMessage = primaryErr.message,
                details = primaryErr.details,
                endpointUnavailableOnServer = false
            )
            Result.failure(IllegalStateException(msg, primaryErr))
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unexpected error calling $PRIMARY_ORDER_FUNCTION | merchantId=$merchantUid requestId=$requestId timestamp=${System.currentTimeMillis()}: ${e.message}"
            )
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر إنشاء الطلب عبر السيرفر، يرجى المحاولة مرة أخرى.",
                    e
                )
            )
        }
    }

    suspend fun syncFcmToken(uid: String) {
        MerchantMessagingService.syncCurrentFcmTokenForMerchant(uid)
    }
}
