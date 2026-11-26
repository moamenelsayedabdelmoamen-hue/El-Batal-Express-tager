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
import java.util.concurrent.atomic.AtomicBoolean

fun interface CloudFunctionInvoker {
    suspend fun call(region: String, functionName: String, payload: Any): Any?
}

data class CaptainRequestResult(
    val orderId: String,
    val orderCode: String,
    val planId: String = "",
    val planName: String = "",
    val captainLimit: Int = 0,
    val activeCaptainsCount: Int = 0,
    val remainingCaptains: Int = 0,
    val subscriptionVersion: Long = 1L,
    val message: String = "تم إنشاء طلب التوصيل بنجاح",
    val isReplay: Boolean = false
)

data class ServerSubscriptionFetchResult(
    val subscriptionSnapshot: RawSubscriptionSnapshot? = null,
    val subscriptionHistoryDocs: List<RawSubscriptionSnapshot> = emptyList(),
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
    val id: String,
    val name: String,
    val captainsLabel: String,
    val maxCaptains: Int,
    val priceEgp: Int,
    val durationDays: Int = 30,
    val isPopular: Boolean = false,
    val planKey: String = id,
    val packageId: String = id,
    val features: List<String> = emptyList(),
    val description: String = "",
    val isActive: Boolean = true,
    val displayOrder: Int = Int.MAX_VALUE
) {
    val planId: String get() = id
    val captainLimit: Int get() = maxCaptains
}

data class VodafoneCashSettings(
    val walletNumber: String = "",
    val walletHolderName: String = "",
    val instructions: String = "1. قم بتحويل قيمة الباقة عبر فودافون كاش إلى الرقم الرسمي الموضح أعلاه.\n2. التقط صورة واضحة (سكرين شوت) لإيصال أو رسالة تأكيد التحويل.\n3. أدخل رقم الهاتف المحوَّل منه وارفع صورة الإيصال ثم اضغط إرسال للمراجعة.\n4. سيتم تفعيل باقتك تلقائياً فور مراجعة وموافقة الإدارة.",
    val plans: List<SubscriptionPlanOption> = emptyList(),
    val isConfiguredOnServer: Boolean = false,
    val isAcceptingSubscriptions: Boolean = false,
    val pauseMessage: String? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val updatedAtMillis: Long = 0L
)

/**
 * Manual Vodafone Cash subscription request payload sent to `submitManualSubscriptionRequest`.
 * The server authoritatively determines package price, captain limit, and duration from `planId`
 * in `subscription_packages` — the client NEVER dictates the authoritative price.
 */
data class ManualSubscriptionRequestPayload(
    val merchantId: String,
    val planId: String,
    val planKey: String = planId,
    val packageId: String = planId,
    val senderPhone: String,
    val transactionReference: String,
    val receiptImagePath: String,
    val idempotencyKey: String,
    val notes: String = ""
) {
    val requestId: String get() = idempotencyKey
    val receiptStoragePath: String get() = receiptImagePath

    fun toCallableMap(): HashMap<String, Any> = hashMapOf(
        "merchantId" to merchantId,
        "planId" to planId,
        "planKey" to planKey.ifBlank { planId },
        "packageId" to packageId.ifBlank { planId },
        "senderPhone" to senderPhone,
        "transactionReference" to transactionReference,
        "receiptImagePath" to receiptImagePath,
        "receiptStoragePath" to receiptImagePath,
        "idempotencyKey" to idempotencyKey,
        "requestId" to idempotencyKey,
        "notes" to notes
    )
}

data class ManualSubscriptionSubmissionResult(
    val requestId: String,
    val requestNumber: String,
    val status: String,
    val paymentStatus: String,
    val planId: String,
    val planName: String,
    val maxCaptains: Int,
    val amount: Double,
    val durationDays: Int,
    val receiptImagePath: String,
    val subscriptionVersion: Long,
    val message: String,
    val isReplay: Boolean = false
) {
    val expectedAmount: Int get() = amount.toInt()
    val receiptStoragePath: String get() = receiptImagePath
}

sealed class ManualSubscriptionUiState {
    data object Idle : ManualSubscriptionUiState()
    data class Submitting(val stepMessage: String = "جاري رفع إيصال التحويل وإرسال الطلب...") : ManualSubscriptionUiState()
    data class Submitted(val result: ManualSubscriptionSubmissionResult) : ManualSubscriptionUiState()
    data class Error(val message: String) : ManualSubscriptionUiState()
}

/**
 * Central repository bridging the Merchant Android App with:
 * 1. Real-time Firestore snapshot listeners (`subscriptions`, `subscription_packages`, `subscription_history`,
 *    `manual_subscription_requests`, `payments`, `settings/manual_subscription_config`, `merchants/{uid}`).
 * 2. Initial & on-demand `Source.SERVER` verification + authoritative Cloud Functions
 *    (`merchantCreateOrder`, `submitManualSubscriptionRequest`, `getMerchantSubscription`, `validateMerchantSubscription`).
 * 3. FCM device token persistence for instant subscription sync push signals.
 */
class MerchantSubscriptionRepository(
    private val callableInvoker: CloudFunctionInvoker = defaultFirebaseCallableInvoker()
) {

    private val orderRequestInFlight = AtomicBoolean(false)
    private val inFlightIdempotencyKeys = mutableSetOf<String>()

    companion object {
        private const val TAG = "MerchantSubRepo"
        const val FUNCTIONS_REGION = "us-central1"
        const val PRIMARY_ORDER_FUNCTION = "merchantCreateOrder"
        const val MANUAL_SUBSCRIPTION_FUNCTION = "submitManualSubscriptionRequest"

        private fun defaultFirebaseCallableInvoker(): CloudFunctionInvoker =
            CloudFunctionInvoker { region, functionName, payload ->
                Firebase.functions(region)
                    .getHttpsCallable(functionName)
                    .call(payload)
                    .await()
                    .getData()
            }

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
         * Builds the official `merchantCreateOrder` callable payload matching Dashboard validators.ts.
         */
        fun buildMerchantCreateOrderPayload(
            merchantId: String,
            destinationAddress: String,
            deliveryFeeAmount: Int,
            formattedServiceFee: String,
            customerName: String,
            customerPhone: String,
            orderDetails: String,
            deliveryNotes: String,
            merchantName: String,
            merchantPhone: String,
            merchantLatitude: Double?,
            merchantLongitude: Double?,
            customerLatitude: Double?,
            customerLongitude: Double?,
            idempotencyKey: String,
            requestedCaptains: Int = 1
        ): HashMap<String, Any?> {
            val trimmedAddress = destinationAddress.trim()
            val trimmedKey = idempotencyKey.trim()
            return hashMapOf(
                "merchantId" to merchantId.trim(),
                "destinationAddress" to trimmedAddress,
                "address" to trimmedAddress,
                "serviceFee" to formattedServiceFee,
                "deliveryFee" to deliveryFeeAmount,
                "price" to formattedServiceFee,
                "customerName" to customerName.trim(),
                "customerPhone" to customerPhone.trim(),
                "orderDetails" to orderDetails.trim(),
                "deliveryNotes" to deliveryNotes.trim(),
                "notes" to deliveryNotes.trim(),
                "merchantName" to merchantName.trim(),
                "merchantPhone" to merchantPhone.trim(),
                "merchantLatitude" to merchantLatitude,
                "merchantLongitude" to merchantLongitude,
                "customerLatitude" to customerLatitude,
                "customerLongitude" to customerLongitude,
                "requestedCaptains" to requestedCaptains.coerceAtLeast(1),
                "captainsCount" to requestedCaptains.coerceAtLeast(1),
                "idempotencyKey" to trimmedKey,
                "requestId" to trimmedKey
            )
        }

        /**
         * Parses a single package map from Firestore `subscription_packages` or settings config.
         * Supports arbitrary string planIds (no forced numeric conversion) and any positive captain limit.
         * Excludes inactive packages (`isActive == false`).
         */
        fun parseSubscriptionPackageMap(
            docId: String,
            map: Map<*, *>
        ): SubscriptionPlanOption? {
            if (map["isActive"] == false) return null
            if (map["enabled"] == false) return null
            val rawPkgStatus = map["status"]?.toString()?.trim()?.lowercase(java.util.Locale.ROOT)
            if (rawPkgStatus == "inactive" || rawPkgStatus == "disabled" || rawPkgStatus == "archived") return null

            val resolvedPlanId = SubscriptionSyncResolver.parsePlanIdString(
                rawPlanId = map["planId"],
                rawPlanKey = map["planKey"],
                rawPackageId = map["packageId"]
            ) ?: docId.trim().takeIf { it.isNotBlank() } ?: return null

            val resolvedPlanKey = SubscriptionSyncResolver.parsePlanIdString(
                rawPlanId = map["planKey"],
                rawPlanKey = map["planId"],
                rawPackageId = map["packageId"]
            ) ?: resolvedPlanId

            val resolvedPackageId = SubscriptionSyncResolver.parsePlanIdString(
                rawPlanId = map["packageId"],
                rawPlanKey = map["planId"],
                rawPackageId = map["planKey"]
            ) ?: resolvedPlanId

            val configuredLimit = (map["maxCaptains"] as? Number)?.toInt()
                ?: (map["captainLimit"] as? Number)?.toInt()
                ?: (map["captainsLimit"] as? Number)?.toInt()
                ?: (map["captainsCount"] as? Number)?.toInt()
                ?: (map["captainCount"] as? Number)?.toInt()
                ?: (map["max_captains"] as? Number)?.toInt()
                ?: (map["captain_limit"] as? Number)?.toInt()
                ?: (map["allowedCaptains"] as? Number)?.toInt()
                ?: map["maxCaptains"]?.toString()?.toIntOrNull()
                ?: map["captainLimit"]?.toString()?.toIntOrNull()
                ?: map["captainsCount"]?.toString()?.toIntOrNull()
                ?: map["max_captains"]?.toString()?.toIntOrNull()
                ?: map["captain_limit"]?.toString()?.toIntOrNull()
                ?: return null
            if (configuredLimit <= 0) return null

            val price = (map["price"] as? Number)?.toInt()
                ?: (map["priceEgp"] as? Number)?.toInt()
                ?: (map["price_egp"] as? Number)?.toInt()
                ?: (map["monthlyPrice"] as? Number)?.toInt()
                ?: (map["amount"] as? Number)?.toInt()
                ?: (map["cost"] as? Number)?.toInt()
                ?: map["price"]?.toString()?.toDoubleOrNull()?.toInt()
                ?: map["priceEgp"]?.toString()?.toDoubleOrNull()?.toInt()
                ?: map["amount"]?.toString()?.toDoubleOrNull()?.toInt()
                ?: return null
            if (price <= 0) return null

            val durationDays = (map["durationDays"] as? Number)?.toInt()
                ?: (map["duration_days"] as? Number)?.toInt()
                ?: (map["duration"] as? Number)?.toInt()
                ?: (map["days"] as? Number)?.toInt()
                ?: map["durationDays"]?.toString()?.toIntOrNull()
                ?: map["duration_days"]?.toString()?.toIntOrNull()
                ?: 30
            if (durationDays !in 1..3650) return null

            val name = (map["planName"] ?: map["packageName"] ?: map["name"] ?: map["title"])
                ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: "باقة $configuredLimit كابتن"

            val featuresList = SubscriptionSyncResolver.parseFeaturesList(map["features"])
            val description = (map["description"] ?: map["subtitle"])
                ?.toString()?.trim().orEmpty()
            val displayOrder = (map["sortOrder"] as? Number)?.toInt()
                ?: (map["order"] as? Number)?.toInt()
                ?: (map["displayOrder"] as? Number)?.toInt()
                ?: Int.MAX_VALUE

            return SubscriptionPlanOption(
                id = resolvedPlanId,
                name = name,
                captainsLabel = "$configuredLimit كابتن",
                maxCaptains = configuredLimit,
                priceEgp = price,
                durationDays = durationDays,
                isPopular = (map["isPopular"] as? Boolean) == true,
                planKey = resolvedPlanKey,
                packageId = resolvedPackageId,
                features = featuresList,
                description = description,
                isActive = true,
                displayOrder = displayOrder
            )
        }

        /**
         * Parses a list of `subscription_packages` entries `(docId to dataMap)`, filtering out
         * disabled/invalid packages and sorting cleanly without hardcoded captain-count limits.
         */
        fun parseSubscriptionPackages(
            entries: List<Pair<String, Map<*, *>>>
        ): List<SubscriptionPlanOption> {
            return entries.mapNotNull { (docId, map) ->
                parseSubscriptionPackageMap(docId, map)
            }.distinctBy { it.id }
                .sortedWith(
                    compareBy<SubscriptionPlanOption>(
                        { it.displayOrder },
                        { it.maxCaptains },
                        { it.priceEgp },
                        { it.name }
                    )
                )
        }

        /**
         * Parses dynamic plans array from `settings/manual_subscription_config` (legacy compatibility
         * if `subscription_packages` is empty), supporting arbitrary string planIds and captain limits.
         */
        fun parseConfiguredPlans(rawPlans: Any?): List<SubscriptionPlanOption> {
            val list = rawPlans as? List<*> ?: return emptyList()
            val pairs = list.mapIndexedNotNull { idx, entry ->
                val map = entry as? Map<*, *> ?: return@mapIndexedNotNull null
                val fallbackId = (map["planId"] ?: map["planKey"] ?: map["packageId"] ?: map["id"])
                    ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "plan_$idx"
                fallbackId to map
            }
            return parseSubscriptionPackages(pairs)
        }

        /**
         * Deterministically reconciles packages across all 3 possible Firestore sources without
         * resurrecting disabled packages or allowing an older document to overwrite newer edits:
         * 1. `subscription_packages` (`isActive == true`) is authoritative whenever non-empty, or
         *    once it has already served active packages in this session (so disabling all packages
         *    there empties the list rather than resurrecting legacy plans from `settings` docs).
         * 2. Between `settings/manual_subscription_config` and `settings/subscription_config`,
         *    when both contain plans, the document with the newer server `updatedAtMillis` wins.
         */
        fun resolveEffectivePackages(
            collectionPackages: List<SubscriptionPlanOption>,
            hasAuthoritativeCollectionPackages: Boolean,
            manualConfigPlans: List<SubscriptionPlanOption>,
            manualConfigUpdatedAtMs: Long = 0L,
            subscriptionConfigPlans: List<SubscriptionPlanOption>,
            subscriptionConfigUpdatedAtMs: Long = 0L
        ): List<SubscriptionPlanOption> {
            if (collectionPackages.isNotEmpty()) return collectionPackages
            if (hasAuthoritativeCollectionPackages) return emptyList()
            if (manualConfigPlans.isNotEmpty() && subscriptionConfigPlans.isNotEmpty()) {
                return if (subscriptionConfigUpdatedAtMs > manualConfigUpdatedAtMs) {
                    subscriptionConfigPlans
                } else {
                    manualConfigPlans
                }
            }
            if (manualConfigPlans.isNotEmpty()) return manualConfigPlans
            if (subscriptionConfigPlans.isNotEmpty()) return subscriptionConfigPlans
            return emptyList()
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
            if (currentSubscription.isPendingReview ||
                currentSubscription.status == "pending_review" ||
                SubscriptionSyncResolver.normalizePaymentStatus(currentSubscription.paymentStatus) == "pending_verification"
            ) {
                return "لديك بالفعل طلب اشتراك قيد مراجعة الإدارة حالياً. يرجى انتظار قرار الإدارة."
            }
            if (currentSubscription.isResubmissionRequired && currentSubscription.pendingRequestId.isNullOrBlank()) {
                return "تعذر العثور على رقم طلب الاشتراك المطلوب إعادة إرساله. حدّث حالة الاشتراك من السيرفر ثم حاول مرة أخرى."
            }
            if (plan == null || !plan.isActive || plan.id.isBlank() || plan.maxCaptains <= 0 || plan.priceEgp <= 0) {
                return "يرجى اختيار إحدى الباقات المتاحة والنشطة حالياً."
            }
            if (normalizeEgyptianMobile(senderPhone) == null) {
                return "يرجى إدخال رقم هاتف مصري صحيح المحوَّل منه (يبدأ بـ 01 ومكون من 11 رقم)."
            }
            if (receiptLocalUri.isNullOrBlank()) {
                return "يجب رفع صورة إيصال التحويل عبر فودافون كاش قبل إرسال الطلب للمراجعة."
            }
            return null
        }

        /**
         * Parses `submitManualSubscriptionRequest` response strictly from server values
         * (`amount`, `requestNumber`, `paymentStatus`, `planId`, `planName`, `maxCaptains`).
         */
        fun parseManualSubscriptionResponse(
            data: Map<*, *>,
            payload: ManualSubscriptionRequestPayload,
            fallbackPlanName: String = "",
            fallbackMaxCaptains: Int = 0,
            fallbackDurationDays: Int = 30
        ): ManualSubscriptionSubmissionResult {
            val requestId = (data["requestId"] ?: data["id"])
                ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.idempotencyKey
            val requestNumber = data["requestNumber"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: requestId
            val status = data["status"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: "pending_review"
            val paymentStatus = SubscriptionSyncResolver.normalizePaymentStatus(
                data["paymentStatus"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "pending_verification"
            )
            val planId = SubscriptionSyncResolver.parsePlanIdString(
                rawPlanId = data["planId"],
                rawPlanKey = data["planKey"],
                rawPackageId = data["packageId"]
            ) ?: payload.planId
            val planName = (data["planName"] ?: data["packageName"])
                ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: fallbackPlanName.ifBlank { "باقة اشتراك" }
            val maxCaptains = (data["maxCaptains"] as? Number)?.toInt()
                ?: (data["captainLimit"] as? Number)?.toInt()
                ?: fallbackMaxCaptains
            // Server is the sole authority for the amount — read `amount` first, then `expectedAmount`/`price`
            val serverAmount = (data["amount"] as? Number)?.toDouble()
                ?: (data["expectedAmount"] as? Number)?.toDouble()
                ?: (data["price"] as? Number)?.toDouble()
                ?: data["amount"]?.toString()?.toDoubleOrNull()
                ?: data["expectedAmount"]?.toString()?.toDoubleOrNull()
                ?: 0.0
            val durationDays = (data["durationDays"] as? Number)?.toInt()
                ?: fallbackDurationDays
            val receiptPath = (data["receiptImagePath"] ?: data["receiptStoragePath"] ?: data["receiptUrl"])
                ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.receiptImagePath
            val version = (data["subscriptionVersion"] as? Number)?.toLong() ?: 1L
            val isReplay = (data["isReplay"] as? Boolean) == true || (data["duplicate"] as? Boolean) == true
            val message = data["message"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: "تم إرسال طلب الاشتراك بنجاح وهو الآن في انتظار مراجعة الإدارة."

            return ManualSubscriptionSubmissionResult(
                requestId = requestId,
                requestNumber = requestNumber,
                status = status,
                paymentStatus = paymentStatus,
                planId = planId,
                planName = planName,
                maxCaptains = maxCaptains,
                amount = serverAmount,
                durationDays = durationDays,
                receiptImagePath = receiptPath,
                subscriptionVersion = version,
                message = message,
                isReplay = isReplay
            )
        }

        /**
         * Parses `merchantCreateOrder` response cleanly.
         */
        fun parseMerchantCreateOrderResponse(data: Map<*, *>): CaptainRequestResult {
            val orderId = (data["orderId"] ?: data["id"])?.toString()?.trim().orEmpty()
            val orderCode = (data["orderCode"] ?: data["code"])?.toString()?.trim().orEmpty()
            if (orderId.isBlank() || orderCode.isBlank()) {
                throw IllegalStateException("تعذر استلام بيانات وكود الطلب من السيرفر.")
            }
            val planId = SubscriptionSyncResolver.parsePlanIdString(
                rawPlanId = data["planId"],
                rawPlanKey = data["planKey"],
                rawPackageId = data["packageId"]
            ).orEmpty()
            return CaptainRequestResult(
                orderId = orderId,
                orderCode = orderCode,
                planId = planId,
                planName = (data["planName"] ?: data["packageName"])?.toString().orEmpty(),
                captainLimit = (data["captainLimit"] as? Number)?.toInt()
                    ?: (data["maxCaptains"] as? Number)?.toInt()
                    ?: 0,
                activeCaptainsCount = (data["activeCaptainsCount"] as? Number)?.toInt() ?: 0,
                remainingCaptains = (data["remainingCaptains"] as? Number)?.toInt()
                    ?: (data["availableCaptainSlots"] as? Number)?.toInt()
                    ?: 0,
                subscriptionVersion = (data["subscriptionVersion"] as? Number)?.toLong() ?: 1L,
                message = data["message"]?.toString().orEmpty().ifBlank { "تم إنشاء طلب التوصيل بنجاح" },
                isReplay = (data["isReplay"] as? Boolean) == true || (data["duplicate"] as? Boolean) == true
            )
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
         * from an application-level `HttpsError("not-found", ...)` returned by Cloud Function code.
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
         * Maps Cloud Function errors for `merchantCreateOrder` to clear Arabic messages.
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
                "PAYMENT_NOT_VERIFIED", "SUBSCRIPTION_UNPAID", "PAYMENT_PENDING_VERIFICATION" ->
                    return cleanedMsg ?: "لا يمكن طلب كابتن قبل تأكيد دفع وتفعيل الاشتراك من الإدارة."
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
                        ?: "تم رفض الطلب من السيرفر: يرجى التحقق من حالة الاشتراك والدفع والحد الأقصى للكباتن."
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
                try {
                    val raw = SubscriptionSyncResolver.fromFirestoreSnapshot(snap)
                    val fromServer = snap != null && !snap.metadata.isFromCache
                    onSnapshot(raw, fromServer)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing subscriptions/$uid snapshot: ${e.message}", e)
                }
            }
    }

    /**
     * Real-time listener for ALL subscription documents linked to this merchant (`subscriptions` where `merchantId == uid`).
     */
    fun observeMerchantSubscriptionsQuery(
        uid: String,
        onSnapshots: (docs: List<RawSubscriptionSnapshot>, fromServer: Boolean) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("subscriptions")
            .whereEqualTo("merchantId", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { querySnap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val docs = querySnap?.documents
                        ?.mapNotNull { doc ->
                            runCatching { SubscriptionSyncResolver.fromFirestoreSnapshot(doc) }
                                .onFailure { Log.w(TAG, "Skipped malformed subscription doc ${doc.id}: ${it.message}") }
                                .getOrNull()
                        }
                        .orEmpty()
                    val fromServer = querySnap != null && !querySnap.metadata.isFromCache
                    onSnapshots(docs, fromServer)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing subscriptions query for $uid: ${e.message}", e)
                }
            }
    }

    /**
     * Real-time listener for active subscription packages in `subscription_packages` where `isActive == true`.
     * Allows Admin to add/edit/disable packages dynamically without updating the APK.
     */
    fun observeSubscriptionPackages(
        onPackages: (List<SubscriptionPlanOption>) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("subscription_packages")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { querySnap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val entries = querySnap?.documents
                        ?.map { doc -> doc.id to (doc.data ?: emptyMap<String, Any?>()) }
                        .orEmpty()
                    onPackages(parseSubscriptionPackages(entries))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing subscription_packages snapshot: ${e.message}", e)
                }
            }
    }

    suspend fun fetchSubscriptionPackagesFromServer(): Result<List<SubscriptionPlanOption>> {
        return try {
            val snap = Firebase.firestore.collection("subscription_packages")
                .whereEqualTo("isActive", true)
                .get(Source.SERVER)
                .await()
            val entries = snap.documents.map { doc -> doc.id to (doc.data ?: emptyMap<String, Any?>()) }
            Result.success(parseSubscriptionPackages(entries))
        } catch (e: Exception) {
            Log.w(TAG, "Source.SERVER read subscription_packages failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Real-time listener for archived past subscription periods in `subscription_history`
     * where `merchantId == uid`. Sorted locally descending by `archivedAtMs` or `endDate`
     * (no Firestore `orderBy` composite index required).
     */
    fun observeSubscriptionHistory(
        uid: String,
        onHistory: (List<SubscriptionHistoryItem>) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("subscription_history")
            .whereEqualTo("merchantId", uid)
            .addSnapshotListener { querySnap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val parsed = querySnap?.documents
                        ?.mapNotNull { doc ->
                            runCatching { SubscriptionSyncResolver.parseSubscriptionHistorySnapshot(doc) }
                                .onFailure { Log.w(TAG, "Skipped malformed subscription_history doc ${doc.id}: ${it.message}") }
                                .getOrNull()
                        }
                        .orEmpty()
                    onHistory(SubscriptionSyncResolver.sortArchivedSubscriptionHistory(parsed))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing subscription_history snapshot: ${e.message}", e)
                }
            }
    }

    suspend fun fetchSubscriptionHistoryFromServer(uid: String): Result<List<SubscriptionHistoryItem>> {
        if (uid.isBlank()) return Result.success(emptyList())
        return try {
            val snap = Firebase.firestore.collection("subscription_history")
                .whereEqualTo("merchantId", uid)
                .get(Source.SERVER)
                .await()
            val parsed = snap.documents.map { SubscriptionSyncResolver.parseSubscriptionHistorySnapshot(it) }
            Result.success(SubscriptionSyncResolver.sortArchivedSubscriptionHistory(parsed))
        } catch (e: Exception) {
            Log.w(TAG, "Source.SERVER read subscription_history for $uid failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Real-time listener for manual Vodafone Cash subscription requests belonging to this merchant.
     */
    fun observeManualSubscriptionRequests(
        uid: String,
        onRequests: (List<ManualSubscriptionRequestRecord>) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("manual_subscription_requests")
            .whereEqualTo("merchantId", uid)
            .addSnapshotListener { querySnap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val parsed = querySnap?.documents
                        ?.mapNotNull { doc ->
                            runCatching { SubscriptionSyncResolver.parseManualRequestSnapshot(doc) }
                                .onFailure { Log.w(TAG, "Skipped malformed manual request doc ${doc.id}: ${it.message}") }
                                .getOrNull()
                        }
                        ?.sortedByDescending { it.createdAtMillis ?: it.updatedAtMillis ?: 0L }
                        .orEmpty()
                    onRequests(parsed)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing manual_subscription_requests snapshot: ${e.message}", e)
                }
            }
    }

    /**
     * Real-time read-only listener for payment records in `payments` where `merchantId == uid`.
     */
    fun observeMerchantPayments(
        uid: String,
        onPayments: (List<MerchantPaymentRecord>) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("payments")
            .whereEqualTo("merchantId", uid)
            .addSnapshotListener { querySnap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val parsed = querySnap?.documents
                        ?.mapNotNull { doc ->
                            runCatching { SubscriptionSyncResolver.parsePaymentSnapshot(doc) }
                                .onFailure { Log.w(TAG, "Skipped malformed payment doc ${doc.id}: ${it.message}") }
                                .getOrNull()
                        }
                        ?.sortedByDescending { it.paymentDateMillis ?: it.createdAtMillis ?: 0L }
                        .orEmpty()
                    onPayments(parsed)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing payments snapshot: ${e.message}", e)
                }
            }
    }

    /**
     * Real-time listener for Vodafone Cash wallet settings & transfer instructions (`settings/manual_subscription_config`).
     */
    fun observeVodafoneCashSettings(
        onSettings: (VodafoneCashSettings) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("settings")
            .document("manual_subscription_config")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    onSettings(parseVodafoneCashSettingsSnapshot(snap))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing settings/manual_subscription_config snapshot: ${e.message}", e)
                }
            }
    }

    /**
     * Real-time listener for `settings/subscription_config` so Dashboard package edits stored
     * in `settings/subscription_config` (`plans` or `packages`) appear immediately without restart.
     */
    fun observeSubscriptionConfigPlans(
        onPlans: (List<SubscriptionPlanOption>, Long) -> Unit,
        onError: (FirebaseFirestoreException) -> Unit
    ): ListenerRegistration {
        return Firebase.firestore.collection("settings")
            .document("subscription_config")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                try {
                    val rawPlans = snap?.get("plans") ?: snap?.get("packages")
                    val updatedAt = extractConfigUpdatedAtMillis(snap)
                    onPlans(parseConfiguredPlans(rawPlans), updatedAt)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing settings/subscription_config snapshot: ${e.message}", e)
                }
            }
    }

    suspend fun fetchSubscriptionConfigPlansFromServer(): Result<Pair<List<SubscriptionPlanOption>, Long>> {
        return try {
            val snap = Firebase.firestore.collection("settings")
                .document("subscription_config")
                .get(Source.SERVER)
                .await()
            val rawPlans = snap.get("plans") ?: snap.get("packages")
            val updatedAt = extractConfigUpdatedAtMillis(snap)
            Result.success(parseConfiguredPlans(rawPlans) to updatedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Source.SERVER read settings/subscription_config failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun extractConfigUpdatedAtMillis(snapshot: DocumentSnapshot?): Long {
        if (snapshot == null || !snapshot.exists()) return 0L
        return SubscriptionSyncResolver.parseDateMillis(snapshot.get("updatedAt"))
            ?: SubscriptionSyncResolver.parseDateMillis(snapshot.get("updated_at"))
            ?: SubscriptionSyncResolver.parseDateMillis(snapshot.get("lastUpdatedAt"))
            ?: SubscriptionSyncResolver.parseDateMillis(snapshot.get("modifiedAt"))
            ?: 0L
    }

    suspend fun fetchMerchantPaymentsFromServer(uid: String): Result<List<MerchantPaymentRecord>> {
        if (uid.isBlank()) return Result.success(emptyList())
        return try {
            val snap = Firebase.firestore.collection("payments")
                .whereEqualTo("merchantId", uid)
                .get(Source.SERVER)
                .await()
            val list = snap.documents
                .map { SubscriptionSyncResolver.parsePaymentSnapshot(it) }
                .sortedByDescending { it.paymentDateMillis ?: it.createdAtMillis ?: 0L }
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Source.SERVER read payments for $uid failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchManualSubscriptionRequestsFromServer(uid: String): Result<List<ManualSubscriptionRequestRecord>> {
        if (uid.isBlank()) return Result.success(emptyList())
        return try {
            val snap = Firebase.firestore.collection("manual_subscription_requests")
                .whereEqualTo("merchantId", uid)
                .get(Source.SERVER)
                .await()
            val list = snap.documents
                .map { SubscriptionSyncResolver.parseManualRequestSnapshot(it) }
                .sortedByDescending { it.createdAtMillis ?: it.updatedAtMillis ?: 0L }
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Source.SERVER read manual_subscription_requests for $uid failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Verifies subscription state directly against:
     * 1. Cloud Function `getMerchantSubscription` (pinned to us-central1)
     * 2. Firestore `Source.SERVER` on `subscriptions/{uid}`, `subscriptions.whereEqualTo("merchantId", uid)`, and `merchants/{uid}`
     * Reconciles canonical + auto-ID subscriptions ensuring an expired doc never beats a valid active subscription.
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
                val rawData = callableInvoker.call(
                    region = FUNCTIONS_REGION,
                    functionName = "getMerchantSubscription",
                    payload = hashMapOf("merchantId" to uid)
                )
                val dataMap = rawData as? Map<*, *>
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

        var serverSubSnap: DocumentSnapshot? = null
        var serverSubQueryDocs: List<RawSubscriptionSnapshot> = emptyList()
        var serverMerchSnap: DocumentSnapshot? = null
        try {
            // The callable already reads subscriptions/{uid} from the server. Reuse
            // that authoritative snapshot instead of paying for the same document twice.
            serverSubSnap = if (callableSnapshot == null) {
                try {
                    Firebase.firestore.collection("subscriptions").document(uid).get(Source.SERVER).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Source.SERVER read subscriptions/$uid failed: ${e.message}")
                    if (lastError == null) lastError = e.localizedMessage ?: e.message
                    null
                }
            } else {
                null
            }
            serverSubQueryDocs = try {
                Firebase.firestore.collection("subscriptions")
                    .whereEqualTo("merchantId", uid)
                    .get(Source.SERVER)
                    .await()
                    .documents
                    .map { SubscriptionSyncResolver.fromFirestoreSnapshot(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Source.SERVER query subscriptions where merchantId=$uid failed: ${e.message}")
                if (lastError == null) lastError = e.localizedMessage ?: e.message
                emptyList()
            }
            serverMerchSnap = try {
                Firebase.firestore.collection("merchants").document(uid).get(Source.SERVER).await()
            } catch (e: Exception) {
                Log.w(TAG, "Source.SERVER read merchants/$uid failed: ${e.message}")
                if (lastError == null) lastError = e.localizedMessage ?: e.message
                null
            }
            if (serverSubSnap != null || serverSubQueryDocs.isNotEmpty() || serverMerchSnap != null) {
                verified = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore Source.SERVER subscription read failed: ${e.message}")
            if (lastError == null) lastError = e.localizedMessage ?: e.message
        }

        val canonicalFirestoreSub = callableSnapshot
            ?: serverSubSnap?.let { SubscriptionSyncResolver.fromFirestoreSnapshot(it) }
            ?: RawSubscriptionSnapshot(exists = false)

        val candidates = buildList {
            if (canonicalFirestoreSub.exists) add(canonicalFirestoreSub)
            addAll(serverSubQueryDocs)
            // When the callable supplied canonicalFirestoreSub, do not add the same
            // snapshot twice. Keep it as an extra candidate only during Firestore fallback.
            if (serverSubSnap != null && callableSnapshot != null && callableSnapshot.exists) {
                add(callableSnapshot)
            }
        }
        val finalSubSnapshot = if (candidates.isNotEmpty()) {
            SubscriptionSyncResolver.selectEffectiveSubscriptionSnapshot(
                canonicalDoc = canonicalFirestoreSub,
                queryDocs = candidates
            )
        } else {
            callableSnapshot ?: serverSubSnap?.let { SubscriptionSyncResolver.fromFirestoreSnapshot(it) }
        }

        return ServerSubscriptionFetchResult(
            subscriptionSnapshot = finalSubSnapshot,
            subscriptionHistoryDocs = buildList {
                // Keep actual Firestore history documents here; callableSnapshot is
                // used for the authoritative current state above, not as a history row.
                if (serverSubSnap?.exists() == true) {
                    add(SubscriptionSyncResolver.fromFirestoreSnapshot(serverSubSnap!!))
                }
                addAll(serverSubQueryDocs)
            },
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
            val rawData = callableInvoker.call(
                region = FUNCTIONS_REGION,
                functionName = "validateMerchantSubscription",
                payload = hashMapOf("requestedCaptains" to requestedCaptains)
            )
            val map = rawData as? Map<*, *> ?: return null
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

    /**
     * Authoritative Cloud Function order creation & captain request (`merchantCreateOrder`).
     * - No fallback to `createOrderWithCode` (prevents incompatible contract & duplicate dispatch).
     * - Prevents duplicate concurrent calls via atomic in-flight guard + `idempotencyKey`.
     */
    suspend fun createMerchantOrder(payload: Map<String, Any?>): Result<CaptainRequestResult> {
        val merchantUid = payload["merchantId"]?.toString()?.trim().orEmpty()
        val idempotencyKey = (payload["idempotencyKey"] ?: payload["requestId"])?.toString()?.trim().orEmpty()
            .ifBlank { "req_${System.currentTimeMillis()}" }

        val acquiredKey = synchronized(inFlightIdempotencyKeys) {
            if (inFlightIdempotencyKeys.contains(idempotencyKey)) {
                false
            } else {
                inFlightIdempotencyKeys.add(idempotencyKey)
                true
            }
        }
        if (!acquiredKey || !orderRequestInFlight.compareAndSet(false, true)) {
            if (acquiredKey) {
                synchronized(inFlightIdempotencyKeys) { inFlightIdempotencyKeys.remove(idempotencyKey) }
            }
            return Result.failure(
                IllegalStateException("جاري إرسال طلب كابتن حالياً، يرجى الانتظار وعدم تكرار الضغط.")
            )
        }

        return try {
            val rawData = callableInvoker.call(
                region = FUNCTIONS_REGION,
                functionName = PRIMARY_ORDER_FUNCTION,
                payload = payload
            )
            val data = rawData as? Map<*, *>
                ?: throw IllegalStateException("استجابة غير صالحة من السيرفر عند إنشاء الطلب.")
            Result.success(parseMerchantCreateOrderResponse(data))
        } catch (primaryErr: FirebaseFunctionsException) {
            val endpointMissing = isEndpointNotFoundError(primaryErr.code, primaryErr.message, primaryErr.details)
            Log.e(
                TAG,
                "Server order callable rejected | functionName=$PRIMARY_ORDER_FUNCTION region=$FUNCTIONS_REGION errorCode=${primaryErr.code} merchantId=$merchantUid requestId=$idempotencyKey timestamp=${System.currentTimeMillis()}"
            )
            val msg = mapRequestCaptainsError(
                code = primaryErr.code,
                rawMessage = primaryErr.message,
                details = primaryErr.details,
                endpointUnavailableOnServer = endpointMissing
            )
            Result.failure(IllegalStateException(msg, primaryErr))
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unexpected error calling $PRIMARY_ORDER_FUNCTION | merchantId=$merchantUid requestId=$idempotencyKey timestamp=${System.currentTimeMillis()}: ${e.message}"
            )
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر إنشاء الطلب عبر السيرفر، يرجى المحاولة مرة أخرى.",
                    e
                )
            )
        } finally {
            orderRequestInFlight.set(false)
            synchronized(inFlightIdempotencyKeys) { inFlightIdempotencyKeys.remove(idempotencyKey) }
        }
    }

    /**
     * Executes all merchant order lifecycle mutations through the authoritative Dashboard
     * Cloud Functions.
     */
    private suspend fun executeMerchantOrderMutation(
        functionName: String,
        payload: Map<String, Any?>
    ): Result<Unit> {
        return try {
            val rawData = callableInvoker.call(
                region = FUNCTIONS_REGION,
                functionName = functionName,
                payload = payload
            )
            val data = rawData as? Map<*, *>
                ?: throw IllegalStateException("استجابة غير صالحة من خادم الطلبات.")
            if (data["success"] == false) {
                throw IllegalStateException(
                    data["message"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: "رفض الخادم تنفيذ العملية."
                )
            }
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Merchant order callable $functionName failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun confirmMerchantOrder(
        orderId: String,
        confirmed: Boolean,
        rejectionReason: String = ""
    ): Result<Unit> = executeMerchantOrderMutation(
        functionName = "merchantConfirmOrder",
        payload = mapOf(
            "orderId" to orderId.trim(),
            "confirmed" to confirmed,
            "rejectionReason" to rejectionReason.trim()
        )
    )

    suspend fun cancelMerchantOrder(
        orderId: String,
        reason: String
    ): Result<Unit> = executeMerchantOrderMutation(
        functionName = "merchantCancelOrder",
        payload = mapOf(
            "orderId" to orderId.trim(),
            "reason" to reason.trim()
        )
    )

    suspend fun updateMerchantOrderDetails(
        orderId: String,
        destinationAddress: String,
        orderDetails: String,
        customerPhone: String,
        customerName: String,
        deliveryNotes: String,
        serviceFee: String
    ): Result<Unit> = executeMerchantOrderMutation(
        functionName = "merchantUpdateOrderDetails",
        payload = mapOf(
            "orderId" to orderId.trim(),
            "destinationAddress" to destinationAddress.trim(),
            "orderDetails" to orderDetails.trim(),
            "customerPhone" to customerPhone.trim(),
            "customerName" to customerName.trim(),
            "deliveryNotes" to deliveryNotes.trim(),
            "serviceFee" to serviceFee.trim()
        )
    )

    fun parseVodafoneCashSettingsSnapshot(
        snapshot: DocumentSnapshot?,
        activePackages: List<SubscriptionPlanOption> = emptyList()
    ): VodafoneCashSettings {
        if (snapshot == null || !snapshot.exists()) {
            return VodafoneCashSettings(
                plans = activePackages,
                loadError = "إعدادات الاشتراك غير موجودة على السيرفر. يرجى التواصل مع الإدارة."
            )
        }
        val wallet = snapshot.getAnyString(
            "vodafoneCashNumber",
            "vodafoneWalletNumber",
            "walletNumber",
            "walletPhone",
            "officialWalletNumber",
            "phoneNumber"
        )?.trim().orEmpty()
        val normalizedWallet = normalizeEgyptianMobile(wallet).orEmpty()
        val walletIsValid = normalizedWallet.isNotEmpty()
        val configPlans = parseConfiguredPlans(snapshot.get("plans") ?: snapshot.get("packages"))
        val effectivePlans = if (activePackages.isNotEmpty()) activePackages else configPlans
        val accepting = snapshot.safeBoolean("isAcceptingSubscriptions")
            ?: snapshot.safeBoolean("enabled")
            ?: snapshot.safeBoolean("isActive")
            ?: true
        val pauseMessage = snapshot.getAnyString("pauseMessage")?.trim()
            ?.takeIf { it.isNotBlank() }
        val configured = walletIsValid && effectivePlans.isNotEmpty()
        val updatedAtMillis = extractConfigUpdatedAtMillis(snapshot)

        return VodafoneCashSettings(
            walletNumber = normalizedWallet,
            walletHolderName = snapshot.getAnyString("walletHolderName", "accountName", "holderName")
                ?.trim().orEmpty(),
            instructions = snapshot.getAnyString(
                "vodafoneCashInstructions",
                "paymentInstructions",
                "instructions"
            )?.trim()?.takeIf { it.isNotBlank() } ?: VodafoneCashSettings().instructions,
            plans = effectivePlans,
            isConfiguredOnServer = configured,
            isAcceptingSubscriptions = accepting,
            pauseMessage = pauseMessage,
            isLoading = false,
            loadError = when {
                !walletIsValid -> "رقم محفظة فودافون كاش غير مضبوط أو غير صالح في إعدادات الإدارة."
                effectivePlans.isEmpty() -> "لا توجد باقات نشطة حالياً في إعدادات الإدارة."
                !accepting -> pauseMessage ?: "استقبال طلبات الاشتراك متوقف مؤقتاً من الإدارة."
                else -> null
            },
            updatedAtMillis = updatedAtMillis
        )
    }

    /**
     * Fetches wallet settings from `settings/manual_subscription_config` (purely without mutating
     * with stale fallbacks; callers merge `subscription_packages`, `manual_subscription_config`,
     * and `subscription_config` deterministically via `resolveEffectivePackages`).
     */
    suspend fun fetchVodafoneCashSettings(
        knownPackages: List<SubscriptionPlanOption> = emptyList()
    ): VodafoneCashSettings {
        return try {
            val manualSnap = try {
                Firebase.firestore.collection("settings")
                    .document("manual_subscription_config")
                    .get(Source.SERVER)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Reading settings/manual_subscription_config from server failed: ${e.message}")
                null
            }
            parseVodafoneCashSettingsSnapshot(manualSnap, activePackages = emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Reading settings/manual_subscription_config from server failed: ${e.message}", e)
            VodafoneCashSettings(
                plans = knownPackages,
                loadError = "تعذر تحميل إعدادات الاشتراك من السيرفر. تحقق من الاتصال والصلاحيات ثم أعد المحاولة."
            )
        }
    }

    /**
     * Calls the trusted Cloud Function `submitManualSubscriptionRequest` (region us-central1).
     * Sends String `planId`, `receiptImagePath`, `senderPhone`, `transactionReference`, `idempotencyKey`
     * without letting the client override the server price.
     * Reads `amount`, `requestNumber`, `paymentStatus` directly from server response.
     */
    suspend fun submitManualSubscriptionRequest(
        payload: ManualSubscriptionRequestPayload,
        fallbackPlanName: String = "",
        fallbackMaxCaptains: Int = 0,
        fallbackDurationDays: Int = 30
    ): Result<ManualSubscriptionSubmissionResult> {
        return try {
            val rawData = callableInvoker.call(
                region = FUNCTIONS_REGION,
                functionName = MANUAL_SUBSCRIPTION_FUNCTION,
                payload = payload.toCallableMap()
            )
            val data = rawData as? Map<*, *>
                ?: throw IllegalStateException("استجابة غير صالحة من السيرفر عند إرسال طلب الاشتراك.")

            Result.success(
                parseManualSubscriptionResponse(
                    data = data,
                    payload = payload,
                    fallbackPlanName = fallbackPlanName,
                    fallbackMaxCaptains = fallbackMaxCaptains,
                    fallbackDurationDays = fallbackDurationDays
                )
            )
        } catch (fnEx: FirebaseFunctionsException) {
            Log.e(
                TAG,
                "submitManualSubscriptionRequest failed | code=${fnEx.code} requestId=${payload.idempotencyKey} msg=${fnEx.message}"
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
