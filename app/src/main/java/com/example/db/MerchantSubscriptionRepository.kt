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

data class SubscriptionPlanOption(
    val id: Int,
    val name: String,
    val captainsLabel: String,
    val maxCaptains: Int,
    val priceEgp: Int,
    val durationDays: Int = 30,
    val isPopular: Boolean = false
)

data class VodafoneCashSettings(
    val walletNumber: String = "",
    val walletHolderName: String = "",
    val instructions: String = "1. قم بتحويل قيمة الباقة عبر فودافون كاش إلى الرقم الرسمي الموضح أعلاه.\n2. التقط صورة واضحة (سكرين شوت) لإيصال أو رسالة تأكيد التحويل.\n3. أدخل رقم الهاتف المحوَّل منه وارفع صورة الإيصال ثم اضغط إرسال للمراجعة.\n4. سيتم تفعيل باقتك تلقائياً فور مراجعة وموافقة الإدارة.",
    val plans: List<SubscriptionPlanOption> = emptyList(),
    val isConfiguredOnServer: Boolean = false,
    val isAcceptingSubscriptions: Boolean = false,
    val pauseMessage: String? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null
) {
    companion object {
        // Official 4 subscription plans matching server OFFICIAL_SUBSCRIPTION_PLANS (prices preserved untouched)
        val OFFICIAL_PLANS: List<SubscriptionPlanOption> = listOf(
            SubscriptionPlanOption(
                id = 1,
                name = "الباقة الأساسية",
                captainsLabel = "2 كابتن",
                maxCaptains = 2,
                priceEgp = 400,
                durationDays = 30,
                isPopular = false
            ),
            SubscriptionPlanOption(
                id = 2,
                name = "الباقة المتوسطة",
                captainsLabel = "4 كابتن",
                maxCaptains = 4,
                priceEgp = 800,
                durationDays = 30,
                isPopular = false
            ),
            SubscriptionPlanOption(
                id = 3,
                name = "الباقة المميزة",
                captainsLabel = "6 كابتن",
                maxCaptains = 6,
                priceEgp = 1200,
                durationDays = 30,
                isPopular = true
            ),
            SubscriptionPlanOption(
                id = 4,
                name = "الباقة المتقدمة",
                captainsLabel = "8 كابتن",
                maxCaptains = 8,
                priceEgp = 1600,
                durationDays = 30,
                isPopular = false
            )
        )
    }
}

data class ManualSubscriptionRequestPayload(
    val merchantId: String,
    val planId: Int,
    val planName: String,
    val maxCaptains: Int,
    val expectedAmount: Int,
    val senderPhone: String,
    val transactionReference: String,
    val receiptStoragePath: String,
    val requestId: String,
    val notes: String = ""
) {
    fun toCallableMap(): HashMap<String, Any> = hashMapOf(
        "merchantId" to merchantId,
        "planId" to planId,
        "packageId" to planId,
        "planName" to planName,
        "maxCaptains" to maxCaptains,
        "expectedAmount" to expectedAmount,
        "amount" to expectedAmount,
        "senderPhone" to senderPhone,
        "transactionReference" to transactionReference,
        "notes" to notes,
        "receiptStoragePath" to receiptStoragePath,
        "requestId" to requestId,
        "idempotencyKey" to requestId
    )
}

data class ManualSubscriptionSubmissionResult(
    val requestId: String,
    val status: String,
    val planId: Int,
    val planName: String,
    val maxCaptains: Int,
    val expectedAmount: Int,
    val durationDays: Int,
    val receiptStoragePath: String,
    val subscriptionVersion: Long,
    val message: String,
    val isReplay: Boolean = false
)

sealed class ManualSubscriptionUiState {
    data object Idle : ManualSubscriptionUiState()
    data class Submitting(val stepMessage: String = "جاري رفع إيصال التحويل وإرسال الطلب...") : ManualSubscriptionUiState()
    data class Submitted(val result: ManualSubscriptionSubmissionResult) : ManualSubscriptionUiState()
    data class Error(val message: String) : ManualSubscriptionUiState()
}

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
        const val PRIMARY_ORDER_FUNCTION = "merchantCreateOrder"
        const val MANUAL_SUBSCRIPTION_FUNCTION = "submitManualSubscriptionRequest"

        fun normalizeEgyptianMobile(rawPhone: String?): String? {
            val cleaned = rawPhone?.trim()?.replace(Regex("[\\s\\-()]"), "").orEmpty()
            if (cleaned.isEmpty()) return null
            val normalized = when {
                cleaned.startsWith("+20") -> "0" + cleaned.removePrefix("+20")
                cleaned.startsWith("20") && cleaned.length == 12 -> "0" + cleaned.removePrefix("20")
                cleaned.startsWith("1") && cleaned.length == 10 -> "0$cleaned"
                else -> cleaned
            }
            return if (Regex("^01[0125][0-9]{8}$").matches(normalized)) normalized else null
        }

        /**
         * Parses the server-authored plan catalog by captain limit and excludes inactive or
         * unsupported plans. No local price catalog is used as a payment fallback.
         */
        fun parseConfiguredPlans(rawPlans: Any?): List<SubscriptionPlanOption> {
            val list = rawPlans as? List<*> ?: return emptyList()
            return list.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                if (map["isActive"] == false) return@mapNotNull null

                val legacyId = (map["id"] as? Number)?.toInt()
                    ?: (map["packageId"] as? Number)?.toInt()
                    ?: (map["planId"] as? Number)?.toInt()
                val configuredLimit = (map["maxCaptains"] as? Number)?.toInt()
                    ?: (map["captainLimit"] as? Number)?.toInt()
                    ?: when (legacyId) {
                        1 -> 2
                        2 -> 4
                        3 -> 6
                        4 -> 8
                        else -> null
                    }
                    ?: return@mapNotNull null

                val canonicalId = when (configuredLimit) {
                    2 -> 1
                    4 -> 2
                    6 -> 3
                    8 -> 4
                    else -> return@mapNotNull null
                }
                val defaults = VodafoneCashSettings.OFFICIAL_PLANS.first { it.id == canonicalId }
                val price = (map["price"] as? Number)?.toInt()
                    ?: (map["priceEgp"] as? Number)?.toInt()
                    ?: (map["amount"] as? Number)?.toInt()
                    ?: return@mapNotNull null
                if (price <= 0) return@mapNotNull null

                val durationDays = (map["durationDays"] as? Number)?.toInt()
                    ?.takeIf { it in 1..365 } ?: return@mapNotNull null
                val name = (map["planName"] ?: map["name"] ?: map["title"])
                    ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaults.name
                SubscriptionPlanOption(
                    id = canonicalId,
                    name = name,
                    captainsLabel = "$configuredLimit كابتن",
                    maxCaptains = configuredLimit,
                    priceEgp = price,
                    durationDays = durationDays,
                    isPopular = (map["isPopular"] as? Boolean) ?: defaults.isPopular
                )
            }.distinctBy { it.maxCaptains }.sortedBy { it.id }
        }

        /**
         * Validates user inputs before receipt upload or server submission.
         * Returns an Arabic error message if invalid, or null if valid.
         */
        fun validateManualSubscriptionInput(
            plan: SubscriptionPlanOption?,
            senderPhone: String,
            receiptLocalUri: String?,
            currentSubscription: SubscriptionStatusState
        ): String? {
            if (currentSubscription.canRequestCaptain()) {
                return "لديك باقة نشطة وسارية حالياً ولا يمكن إرسال طلب اشتراك جديد قبل انتهائها."
            }
            if (currentSubscription.isPendingReview || currentSubscription.status == "pending_review") {
                return "لديك بالفعل طلب اشتراك قيد مراجعة الإدارة حالياً. يرجى انتظار قرار الإدارة."
            }
            if (currentSubscription.isResubmissionRequired && currentSubscription.pendingRequestId.isNullOrBlank()) {
                return "تعذر العثور على رقم طلب الاشتراك المطلوب إعادة إرساله. حدّث حالة الاشتراك من السيرفر ثم حاول مرة أخرى."
            }
            if (plan == null || plan.id !in 1..4 || plan.maxCaptains !in listOf(2, 4, 6, 8) || plan.priceEgp <= 0) {
                return "يرجى اختيار إحدى الباقات الرسمية المتاحة."
            }
            if (normalizeEgyptianMobile(senderPhone) == null) {
                return "يرجى إدخال رقم هاتف مصري صحيح المحوَّل منه (يبدأ بـ 01 ومكون من 11 رقم)."
            }
            if (receiptLocalUri.isNullOrBlank()) {
                return "يجب رفع صورة إيصال التحويل عبر فودافون كاش قبل إرسال الطلب للمراجعة."
            }
            return null
        }

        fun mapManualSubscriptionError(
            code: FirebaseFunctionsException.Code,
            rawMessage: String?,
            details: Any?
        ): String {
            if (isEndpointNotFoundError(code, rawMessage, details)) {
                return "خدمة إرسال طلبات الاشتراك غير متاحة حالياً على السيرفر ($MANUAL_SUBSCRIPTION_FUNCTION في منطقة $FUNCTIONS_REGION). يرجى التأكد من نشر Cloud Functions ثم المحاولة مرة أخرى."
            }
            val cleaned = rawMessage?.trim()?.takeIf {
                it.isNotBlank() &&
                    !it.equals("NOT_FOUND", ignoreCase = true) &&
                    !it.equals("INTERNAL", ignoreCase = true) &&
                    !it.equals("UNKNOWN", ignoreCase = true)
            }
            return when (code) {
                FirebaseFunctionsException.Code.ALREADY_EXISTS ->
                    cleaned ?: "لديك بالفعل طلب اشتراك في انتظار مراجعة الإدارة حالياً."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    cleaned ?: "تعذر إرسال طلب الاشتراك: يرجى التأكد من رفع الإيصال وحالة اشتراكك الحالي."
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    cleaned ?: "بيانات طلب الاشتراك غير مكتملة أو غير صالحة. يرجى مراجعة الرقم وصورة الإيصال."
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    cleaned ?: "يجب تسجيل الدخول أولاً لإرسال طلب الاشتراك."
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    cleaned ?: "غير مسموح بتنفيذ هذا الإجراء لهذا الحساب."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "تعذر الاتصال بالسيرفر حالياً. يرجى التحقق من الإنترنت والمحاولة مرة أخرى."
                else ->
                    cleaned ?: "حدث خطأ أثناء إرسال طلب الاشتراك للمراجعة. يرجى المحاولة مرة أخرى."
            }
        }

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
     * Creates a delivery order through the single Dashboard Cloud Function.
     * Delivery fee is sent only from the merchant's input; the server reads the
     * merchant name, registered phone, and logo from the trusted merchants/{uid} document.
     * No legacy client-side or alternate-dispatch fallback is allowed.
     */
    suspend fun requestCaptains(payload: Map<String, Any?>): Result<CaptainRequestResult> {
        val merchantUid = Firebase.auth.currentUser?.uid.orEmpty()
        val requestId = payload["requestId"]?.toString()
            ?: payload["idempotencyKey"]?.toString()
            ?: "unknown"

        return try {
            Result.success(invokeServerOrderCallable(PRIMARY_ORDER_FUNCTION, payload))
        } catch (e: FirebaseFunctionsException) {
            Log.e(
                TAG,
                "Server order callable rejected | functionName=$PRIMARY_ORDER_FUNCTION region=$FUNCTIONS_REGION errorCode=${e.code} merchantId=$merchantUid requestId=$requestId",
                e
            )
            val message = mapRequestCaptainsError(
                code = e.code,
                rawMessage = e.message,
                details = e.details,
                endpointUnavailableOnServer = isEndpointNotFoundError(e.code, e.message, e.details)
            )
            Result.failure(IllegalStateException(message, e))
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unexpected error calling $PRIMARY_ORDER_FUNCTION | merchantId=$merchantUid requestId=$requestId: ${e.message}",
                e
            )
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر إنشاء الطلب عبر السيرفر، يرجى المحاولة مرة أخرى.",
                    e
                )
            )
        }
    }

    /**
     * Confirms or rejects the delivery details submitted by the assigned captain.
     * All order state changes are applied transactionally by the Dashboard Cloud Function.
     */
    suspend fun confirmMerchantOrder(
        orderId: String,
        confirmed: Boolean,
        rejectionReason: String = ""
    ): Result<Unit> {
        return runCatching {
            require(orderId.isNotBlank()) { "معرف الطلب غير صالح." }
            functionsClient()
                .getHttpsCallable("merchantConfirmOrder")
                .call(
                    hashMapOf(
                        "orderId" to orderId,
                        "confirmed" to confirmed,
                        "rejectionReason" to rejectionReason.trim().take(500)
                    )
                )
                .await()
            Unit
        }
    }

    /**
     * Cancels a merchant-owned order through the server. The server validates the
     * merchant owner, lifecycle status and releases any captain reservation atomically.
     */
    suspend fun cancelMerchantOrder(
        orderId: String,
        reason: String
    ): Result<Unit> {
        return runCatching {
            require(orderId.isNotBlank()) { "معرف الطلب غير صالح." }
            functionsClient()
                .getHttpsCallable("merchantCancelOrder")
                .call(
                    hashMapOf(
                        "orderId" to orderId,
                        "reason" to reason.trim().take(500)
                    )
                )
                .await()
            Unit
        }
    }

    /**
     * Fetches the official Vodafone Cash wallet number, optional wallet holder name, dynamic plans,
     * and instructions configured by Admin in Firestore.
     */
    suspend fun fetchVodafoneCashSettings(): VodafoneCashSettings {
        return try {
            // This is the canonical settings document written by the Dashboard Cloud Function.
            // Never fall back to a hard-coded wallet or stale legacy settings documents.
            val snapshot = Firebase.firestore.collection("settings")
                .document("manual_subscription_config")
                .get(Source.SERVER)
                .await()

            if (!snapshot.exists()) {
                VodafoneCashSettings(
                    loadError = "إعدادات الاشتراك غير موجودة على السيرفر. يرجى التواصل مع الإدارة."
                )
            } else {
                val wallet = snapshot.getAnyString("vodafoneCashNumber", "walletNumber")
                    ?.trim().orEmpty()
                val walletIsValid = Regex("^01[0125][0-9]{8}$").matches(wallet)
                val plans = parseConfiguredPlans(snapshot.get("plans"))
                val accepting = snapshot.getBoolean("isAcceptingSubscriptions") == true
                val pauseMessage = snapshot.getAnyString("pauseMessage")?.trim()
                    ?.takeIf { it.isNotBlank() }
                val configured = walletIsValid && plans.isNotEmpty()

                VodafoneCashSettings(
                    walletNumber = if (walletIsValid) wallet else "",
                    walletHolderName = snapshot.getAnyString("walletHolderName", "accountName")
                        ?.trim().orEmpty(),
                    instructions = snapshot.getAnyString(
                        "vodafoneCashInstructions",
                        "paymentInstructions",
                        "instructions"
                    )?.trim()?.takeIf { it.isNotBlank() } ?: VodafoneCashSettings().instructions,
                    plans = plans,
                    isConfiguredOnServer = configured,
                    isAcceptingSubscriptions = accepting,
                    pauseMessage = pauseMessage,
                    isLoading = false,
                    loadError = when {
                        !walletIsValid -> "رقم محفظة فودافون كاش غير مضبوط أو غير صالح في إعدادات الإدارة."
                        plans.isEmpty() -> "لا توجد باقات نشطة ومدعومة في إعدادات الاشتراك على السيرفر."
                        !accepting -> pauseMessage ?: "استقبال طلبات الاشتراك متوقف مؤقتاً من الإدارة."
                        else -> null
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reading settings/manual_subscription_config from server failed: ${e.message}", e)
            VodafoneCashSettings(
                loadError = "تعذر تحميل إعدادات الاشتراك من السيرفر. تحقق من الاتصال والصلاحيات ثم أعد المحاولة."
            )
        }
    }

    /**
     * Calls the trusted Cloud Function `submitManualSubscriptionRequest` (region us-central1).
     * Never activates the subscription locally — only registers a `pending_review` request for Admin review.
     */
    suspend fun submitManualSubscriptionRequest(
        payload: ManualSubscriptionRequestPayload
    ): Result<ManualSubscriptionSubmissionResult> {
        return try {
            val result = functionsClient()
                .getHttpsCallable(MANUAL_SUBSCRIPTION_FUNCTION)
                .call(payload.toCallableMap())
                .await()

            val data = result.getData() as? Map<*, *>
                ?: throw IllegalStateException("استجابة غير صالحة من السيرفر عند إرسال طلب الاشتراك.")

            val requestId = data["requestId"]?.toString()?.trim().orEmpty().ifBlank { payload.requestId }
            val status = data["status"]?.toString()?.trim().orEmpty().ifBlank { "pending_review" }
            val planId = (data["planId"] as? Number)?.toInt() ?: payload.planId
            val planName = data["planName"]?.toString()?.trim().orEmpty().ifBlank { payload.planName }
            val maxCaptains = (data["maxCaptains"] as? Number)?.toInt() ?: payload.maxCaptains
            val expectedAmount = (data["expectedAmount"] as? Number)?.toInt() ?: payload.expectedAmount
            val durationDays = (data["durationDays"] as? Number)?.toInt() ?: 30
            val receiptPath = data["receiptStoragePath"]?.toString()?.trim().orEmpty().ifBlank { payload.receiptStoragePath }
            val version = (data["subscriptionVersion"] as? Number)?.toLong() ?: 1L
            val isReplay = (data["isReplay"] as? Boolean) == true
            val message = data["message"]?.toString()?.trim().orEmpty()
                .ifBlank { "تم إرسال طلب الاشتراك بنجاح وهو الآن في انتظار مراجعة الإدارة." }

            Result.success(
                ManualSubscriptionSubmissionResult(
                    requestId = requestId,
                    status = status,
                    planId = planId,
                    planName = planName,
                    maxCaptains = maxCaptains,
                    expectedAmount = expectedAmount,
                    durationDays = durationDays,
                    receiptStoragePath = receiptPath,
                    subscriptionVersion = version,
                    message = message,
                    isReplay = isReplay
                )
            )
        } catch (fnEx: FirebaseFunctionsException) {
            Log.e(
                TAG,
                "submitManualSubscriptionRequest failed | code=${fnEx.code} requestId=${payload.requestId} msg=${fnEx.message}"
            )
            Result.failure(
                IllegalStateException(
                    mapManualSubscriptionError(fnEx.code, fnEx.message, fnEx.details),
                    fnEx
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling submitManualSubscriptionRequest: ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر إرسال طلب الاشتراك للمراجعة. يرجى المحاولة مرة أخرى.",
                    e
                )
            )
        }
    }

    suspend fun syncFcmToken(uid: String) {
        MerchantMessagingService.syncCurrentFcmTokenForMerchant(uid)
    }
}
