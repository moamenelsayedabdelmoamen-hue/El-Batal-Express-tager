package com.example.db

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class SubscriptionStatusState(
    val isSubscribed: Boolean = false,
    val isExpired: Boolean = false,
    val isSuspended: Boolean = false,
    val isCancelled: Boolean = false,
    val isPending: Boolean = false,
    val isPendingReview: Boolean = false,
    val isRejected: Boolean = false,
    val isResubmissionRequired: Boolean = false,
    val isOfflineUnverified: Boolean = false,
    val isFromCache: Boolean = false,
    val status: String = "unsubscribed",
    val startDateMillis: Long? = null,
    val approvedAtMillis: Long? = null,
    val endDateMillis: Long? = null,
    val lastServerSyncMillis: Long? = null,
    val message: String? = null,
    val suspensionReason: String? = null,
    val rejectionReason: String? = null,
    val resubmissionReason: String? = null,
    val serverError: String? = null,
    val isChecking: Boolean = false,
    val planId: Int = 0,
    val planName: String = "غير مشترك",
    val maxCaptains: Int = 0,
    val price: Int = 0,
    val activeCaptainsCount: Int = 0,
    val availableCaptainSlots: Int = 0,
    val paymentStatus: String = "unpaid",
    val paymentMethod: String? = null,
    val pendingRequestId: String? = null,
    val senderPhone: String? = null,
    val transactionReference: String? = null,
    val receiptStoragePath: String? = null,
    val subscriptionVersion: Long = 0L,
    val updatedBy: String? = null
) {
    fun canRequestCaptain(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isSubscribed || isExpired || isSuspended || isCancelled || isPending || isPendingReview || isRejected || isResubmissionRequired || isOfflineUnverified) return false
        if (status != "active") return false
        if (paymentStatus in listOf("failed", "unpaid", "pending", "pending_review", "rejected", "resubmission_required")) return false
        if (maxCaptains <= 0) return false
        if (endDateMillis != null && nowMillis >= endDateMillis) return false
        return true
    }

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val end = endDateMillis ?: return 0L
        return (end - nowMillis).coerceAtLeast(0L)
    }

    fun remainingDays(nowMillis: Long = System.currentTimeMillis()): Int {
        val rem = remainingMillis(nowMillis)
        if (rem <= 0L) return 0
        return (rem / (24L * 60L * 60L * 1000L)).toInt()
    }

    fun remainingHours(nowMillis: Long = System.currentTimeMillis()): Int {
        val rem = remainingMillis(nowMillis)
        if (rem <= 0L) return 0
        return ((rem % (24L * 60L * 60L * 1000L)) / (60L * 60L * 1000L)).toInt()
    }

    fun remainingTimeLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val rem = remainingMillis(nowMillis)
        if (rem <= 0L) return "منتهي"
        val days = remainingDays(nowMillis)
        val hours = remainingHours(nowMillis)
        return when {
            days >= 1 && hours > 0 -> "$days يوم و $hours ساعة"
            days >= 1 -> "$days يوم"
            hours >= 1 -> "$hours ساعة"
            else -> "أقل من ساعة"
        }
    }
}

data class RawSubscriptionSnapshot(
    val exists: Boolean = false,
    val status: String? = null,
    val subscriptionStatus: String? = null,
    val planStatus: String? = null,
    val paymentStatus: String? = null,
    val isActive: Boolean? = null,
    val isSubscribed: Boolean? = null,
    val planId: Int? = null,
    val planName: String? = null,
    val maxCaptains: Int? = null,
    val price: Int? = null,
    val startDateMillis: Long? = null,
    val approvedAtMillis: Long? = null,
    val endDateMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val subscriptionUpdatedAtMillis: Long? = null,
    val lastLoginAtMillis: Long? = null,
    val reason: String? = null,
    val rejectionReason: String? = null,
    val resubmissionReason: String? = null,
    val paymentMethod: String? = null,
    val pendingRequestId: String? = null,
    val senderPhone: String? = null,
    val transactionReference: String? = null,
    val receiptStoragePath: String? = null,
    val subscriptionVersion: Long? = null,
    val updatedBy: String? = null,
    val isFromCache: Boolean = false
)

data class SubscriptionTransitionEvent(
    val title: String,
    val body: String,
    val isActivation: Boolean
)

object SubscriptionSyncResolver {

    private val ISO_DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    fun normalizeStatus(raw: String?): String {
        val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (s) {
            "active", "activated", "subscribed", "enabled", "valid", "approved", "paid",
            "نشط", "نشطة", "ساري", "سارية", "فعال", "فعّال", "فعالة", "فعّالة",
            "مفعل", "مفعّل", "مفعلة", "مفعّلة", "مشترك", "مشتركة" -> "active"

            "inactive", "disabled", "not_active", "deactivated",
            "غير نشط", "غير نشطة", "غير فعال", "غير فعّال", "غير مفعل", "غير مفعّل" -> "inactive"

            "suspended", "suspend", "paused", "blocked", "frozen",
            "موقوف", "موقوفة", "متوقف", "متوقفة" -> "suspended"

            "cancelled", "canceled", "cancel", "revoked", "terminated",
            "ملغي", "ملغى", "ملغاة" -> "cancelled"

            "expired", "منتهي", "منتهية", "منتهي الصلاحية" -> "expired"

            "pending_review", "awaiting_review", "manual_pending", "under_review", "in_review",
            "في انتظار مراجعة الإدارة", "في انتظار مراجعة الادارة", "قيد مراجعة الإدارة", "بانتظار مراجعة الإدارة" -> "pending_review"

            "rejected", "request_rejected", "manual_rejected",
            "مرفوض", "مرفوضة", "تم رفض الطلب", "تم الرفض" -> "rejected"

            "resubmission_required", "resubmit", "resubmit_required", "reupload_receipt", "receipt_resubmission_required",
            "مطلوب إعادة إرسال الإيصال", "مطلوب اعادة ارسال الايصال", "إعادة إرسال الإيصال", "اعادة ارسال الايصال" -> "resubmission_required"

            "pending", "merchant_pending",
            "قيد المراجعة", "معلق", "معلقة", "بانتظار التفعيل" -> "pending"

            "payment_failed", "failed", "declined" -> "payment_failed"
            "pending_payment", "awaiting_payment", "في انتظار الدفع" -> "pending_payment"
            "unsubscribed", "none", "not_found", "" -> "unsubscribed"
            else -> s
        }
    }

    fun parseDateMillis(raw: Any?): Long? {
        if (raw == null) return null
        when (raw) {
            is Timestamp -> return raw.toDate().time
            is Date -> return raw.time
            is Number -> {
                val v = raw.toLong()
                if (v <= 0L) return null
                // Normalize Unix timestamps provided in seconds (< 1e12) into milliseconds
                return if (v < 1_000_000_000_000L) v * 1000L else v
            }
            is Map<*, *> -> {
                val secs = (raw["_seconds"] as? Number)?.toLong()
                    ?: (raw["seconds"] as? Number)?.toLong()
                if (secs != null && secs > 0L) return secs * 1000L
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                trimmed.toLongOrNull()?.let { num ->
                    if (num > 1_000_000_000L) {
                        return if (num < 1_000_000_000_000L) num * 1000L else num
                    }
                }
                for (pattern in ISO_DATE_PATTERNS) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                            isLenient = false
                            if (pattern.endsWith("'Z'")) {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                        }
                        val parsed = sdf.parse(trimmed)
                        if (parsed != null) return parsed.time
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    fun fromFirestoreSnapshot(snapshot: DocumentSnapshot?): RawSubscriptionSnapshot {
        if (snapshot == null || !snapshot.exists()) {
            return RawSubscriptionSnapshot(
                exists = false,
                isFromCache = snapshot?.metadata?.isFromCache == true
            )
        }
        val isActiveField = snapshot.getBoolean("isActive")
        val isSubscribedField = snapshot.getBoolean("isSubscribed")
        val limitValue = (snapshot.get("captainLimit") as? Number)?.toInt()
            ?: (snapshot.get("maxCaptains") as? Number)?.toInt()
            ?: (snapshot.get("captains") as? Number)?.toInt()
            ?: snapshot.safeString("captainLimit")?.toIntOrNull()
            ?: snapshot.safeString("maxCaptains")?.toIntOrNull()
        return RawSubscriptionSnapshot(
            exists = true,
            status = snapshot.safeString("status"),
            subscriptionStatus = snapshot.safeString("subscriptionStatus"),
            planStatus = snapshot.safeString("planStatus"),
            paymentStatus = snapshot.safeString("paymentStatus"),
            isActive = isActiveField,
            isSubscribed = isSubscribedField,
            planId = (snapshot.get("planId") as? Number)?.toInt()
                ?: snapshot.safeString("planId")?.toIntOrNull(),
            planName = snapshot.getAnyString("planName", "plan_name", "packageName", "title"),
            maxCaptains = limitValue,
            price = (snapshot.get("price") as? Number)?.toInt()
                ?: (snapshot.get("amount") as? Number)?.toInt()
                ?: snapshot.safeString("price")?.toIntOrNull(),
            startDateMillis = parseDateFields(
                snapshot,
                listOf("activatedAt", "startDate", "subscriptionStartDate", "createdAt")
            ),
            approvedAtMillis = parseDateFields(
                snapshot,
                listOf("approvedAt", "activatedAt", "startDate")
            ),
            endDateMillis = parseDateFields(
                snapshot,
                listOf("expiresAt", "endDate", "subscriptionEndDate", "renewalDate", "nextBillingDate", "expiryDate")
            ),
            updatedAtMillis = parseDateFields(
                snapshot,
                listOf("updatedAt", "cancelledAt", "suspendedAt", "rejectedAt", "resubmissionRequestedAt")
            ),
            subscriptionUpdatedAtMillis = parseDateFields(
                snapshot,
                listOf("subscriptionUpdatedAt", "activatedAt", "suspendedAt", "cancelledAt", "rejectedAt", "resubmissionRequestedAt")
            ),
            lastLoginAtMillis = parseDateFields(snapshot, listOf("lastLoginAt")),
            reason = snapshot.getAnyString("suspensionReason", "cancellationReason", "resubmissionReason", "rejectionReason", "reason"),
            rejectionReason = snapshot.getAnyString("rejectionReason"),
            resubmissionReason = snapshot.getAnyString("resubmissionReason", "rejectionReason"),
            paymentMethod = snapshot.getAnyString("paymentMethod"),
            pendingRequestId = snapshot.getAnyString("pendingRequestId"),
            senderPhone = snapshot.getAnyString("senderPhone"),
            transactionReference = snapshot.getAnyString("transactionReference"),
            receiptStoragePath = snapshot.getAnyString("receiptStoragePath"),
            subscriptionVersion = (snapshot.get("subscriptionVersion") as? Number)?.toLong(),
            updatedBy = snapshot.getAnyString("updatedBy", "updatedByAdminUid"),
            isFromCache = snapshot.metadata.isFromCache
        )
    }

    fun fromServerCallableMap(data: Map<*, *>): RawSubscriptionSnapshot {
        val rawStatus = data["status"]?.toString()?.trim()
        val normalized = normalizeStatus(rawStatus)
        val isActive = (data["isActive"] as? Boolean)
            ?: (data["isSubscribed"] as? Boolean)
            ?: (data["isValid"] as? Boolean)
            ?: (normalized == "active")
        val planId = (data["planId"] as? Number)?.toInt() ?: 0
        val planName = data["planName"]?.toString()
        val limit = (data["captainLimit"] as? Number)?.toInt() ?: (data["maxCaptains"] as? Number)?.toInt()
        val price = (data["price"] as? Number)?.toInt()
        val startMs = parseDateMillis(data["activatedAt"]) ?: parseDateMillis(data["startDate"])
        val approvedMs = parseDateMillis(data["approvedAt"]) ?: startMs
        val endMs = parseDateMillis(data["expiresAt"]) ?: parseDateMillis(data["endDate"])
        val serverTimeMs = parseDateMillis(data["serverTimeMs"]) ?: System.currentTimeMillis()
        val version = (data["subscriptionVersion"] as? Number)?.toLong()
        val updatedBy = data["updatedBy"]?.toString()
        val rejectionReason = data["rejectionReason"]?.toString()?.takeIf { it.isNotBlank() }
        val resubmissionReason = data["resubmissionReason"]?.toString()?.takeIf { it.isNotBlank() } ?: rejectionReason
        val reason = resubmissionReason
            ?: rejectionReason
            ?: data["reason"]?.toString()?.takeIf { it.isNotBlank() }
            ?: data["errorMessage"]?.toString()?.takeIf { it.isNotBlank() && normalized != "active" }
        val explicitPaymentStatus = data["paymentStatus"]?.toString()?.trim()?.ifBlank { null }
        val derivedPaymentStatus = when (normalized) {
            "active" -> explicitPaymentStatus ?: "paid"
            "pending_review" -> explicitPaymentStatus ?: "pending_review"
            "rejected" -> explicitPaymentStatus ?: "rejected"
            "resubmission_required" -> explicitPaymentStatus ?: "resubmission_required"
            "payment_failed", "pending_payment" -> explicitPaymentStatus ?: "failed"
            else -> explicitPaymentStatus ?: "unpaid"
        }
        return RawSubscriptionSnapshot(
            exists = rawStatus != "not_found",
            status = rawStatus,
            subscriptionStatus = rawStatus,
            paymentStatus = derivedPaymentStatus,
            isActive = isActive,
            isSubscribed = isActive,
            planId = planId,
            planName = planName,
            maxCaptains = limit,
            price = price,
            startDateMillis = startMs,
            approvedAtMillis = approvedMs,
            endDateMillis = endMs,
            updatedAtMillis = serverTimeMs,
            subscriptionUpdatedAtMillis = serverTimeMs,
            reason = reason,
            rejectionReason = rejectionReason,
            resubmissionReason = resubmissionReason,
            paymentMethod = data["paymentMethod"]?.toString()?.takeIf { it.isNotBlank() },
            pendingRequestId = data["pendingRequestId"]?.toString()?.takeIf { it.isNotBlank() },
            subscriptionVersion = version,
            updatedBy = updatedBy,
            isFromCache = false
        )
    }

    private fun parseDateFields(snapshot: DocumentSnapshot, fields: List<String>): Long? {
        for (field in fields) {
            try {
                val ts = snapshot.getTimestamp(field)
                if (ts != null) return ts.toDate().time
            } catch (_: Exception) {}
            try {
                val date = snapshot.getDate(field)
                if (date != null) return date.time
            } catch (_: Exception) {}
            try {
                val parsed = parseDateMillis(snapshot.get(field))
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }
        return null
    }

    private fun effectiveMerchantSubTimestamp(merchantDoc: RawSubscriptionSnapshot): Long {
        // Dedicated subscriptionUpdatedAt is immune to profile/login/FCM updatedAt writes.
        merchantDoc.subscriptionUpdatedAtMillis?.let { return it }
        val updated = merchantDoc.updatedAtMillis ?: return 0L
        val login = merchantDoc.lastLoginAtMillis
        // Ignore updatedAt if it was stamped during merchant login/auth sync
        if (login != null && abs(updated - login) <= 10_000L) {
            return 0L
        }
        return updated
    }

    fun resolve(
        subscriptionDoc: RawSubscriptionSnapshot,
        merchantDoc: RawSubscriptionSnapshot,
        isOnline: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        activeCaptainsCount: Int = 0,
        hasVerifiedServerOnce: Boolean = false,
        isInitialServerCheckInProgress: Boolean = false,
        serverError: String? = null
    ): SubscriptionStatusState {
        val subStatusNorm = if (subscriptionDoc.exists) {
            normalizeStatus(subscriptionDoc.status ?: subscriptionDoc.subscriptionStatus ?: subscriptionDoc.planStatus)
        } else {
            "unsubscribed"
        }
        val merchStatusNorm = if (merchantDoc.exists) {
            normalizeStatus(merchantDoc.subscriptionStatus ?: merchantDoc.planStatus)
        } else {
            "unsubscribed"
        }

        // Reconcile canonical subscriptions/{uid} and mirrored merchants/{uid}:
        // 1. Higher subscriptionVersion always wins.
        // 2. On version tie, compare dedicated subscriptionUpdatedAt (never letting login/FCM/profile updatedAt override subscriptions/{uid}).
        // 3. Fallback cleanly to whichever doc exists or holds the active subscription.
        val useMerchantAsPrimary = when {
            !subscriptionDoc.exists && merchantDoc.exists -> true
            subscriptionDoc.exists && merchantDoc.exists -> {
                val subUpdated = subscriptionDoc.subscriptionUpdatedAtMillis ?: subscriptionDoc.updatedAtMillis ?: 0L
                val merchUpdated = effectiveMerchantSubTimestamp(merchantDoc)
                val subVersion = subscriptionDoc.subscriptionVersion ?: 0L
                val merchVersion = merchantDoc.subscriptionVersion ?: 0L
                val merchExplicitRestrictive = merchStatusNorm in listOf("suspended", "inactive", "cancelled", "expired", "payment_failed", "pending", "pending_review", "rejected", "resubmission_required")
                val subExplicitDeactivated = subscriptionDoc.isActive == false && subscriptionDoc.isSubscribed != true
                val subExplicitActive = subStatusNorm == "active" && !subExplicitDeactivated
                when {
                    merchStatusNorm != "unsubscribed" && merchVersion > subVersion -> true
                    subVersion > merchVersion -> false
                    subStatusNorm == "unsubscribed" && merchStatusNorm != "unsubscribed" -> true
                    merchStatusNorm != "unsubscribed" && merchUpdated > subUpdated -> true
                    subUpdated > merchUpdated -> false
                    merchExplicitRestrictive && subStatusNorm != merchStatusNorm && merchUpdated > 0L -> true
                    !subExplicitActive && merchStatusNorm == "active" -> true
                    else -> false
                }
            }
            else -> false
        }

        val primary = if (useMerchantAsPrimary) merchantDoc else subscriptionDoc
        val secondary = if (useMerchantAsPrimary) subscriptionDoc else merchantDoc

        // If we are still waiting on the first authoritative server check while online,
        // keep showing a clean loading state instead of flashing "غير مشترك".
        if (isInitialServerCheckInProgress && isOnline && !hasVerifiedServerOnce && (!primary.exists || primary.isFromCache)) {
            return SubscriptionStatusState(
                isChecking = true,
                isSubscribed = false,
                isOfflineUnverified = false,
                isFromCache = primary.isFromCache || secondary.isFromCache,
                status = "checking",
                message = "جاري التحقق من حالة الاشتراك مع السيرفر...",
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0
            )
        }

        // If server verification failed with a network/server error and we never verified from server,
        // surface a clear error state instead of falsely showing "inactive" or "unsubscribed".
        if (!serverError.isNullOrBlank() && !hasVerifiedServerOnce && (!primary.exists || primary.isFromCache)) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isOfflineUnverified = true,
                isFromCache = primary.isFromCache || secondary.isFromCache,
                status = "error",
                serverError = serverError,
                message = serverError,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0
            )
        }

        if (!primary.exists) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = primary.isFromCache || secondary.isFromCache,
                status = "unsubscribed",
                message = if (!isOnline) {
                    "أنت غير متصل بالإنترنت: تعذر مزامنة حالة الاشتراك مع السيرفر."
                } else {
                    "أنت غير مشترك في أي باقة حالياً"
                },
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "unpaid"
            )
        }

        val chosenStatus = if (useMerchantAsPrimary) merchStatusNorm else subStatusNorm
        val chosenIsFromCache = primary.isFromCache
        val rawPaymentStatus = (primary.paymentStatus ?: secondary.paymentStatus)?.trim()?.lowercase(Locale.ROOT) ?: "paid"
        val endDateMillis = primary.endDateMillis ?: secondary.endDateMillis
        val startDateMillis = primary.startDateMillis ?: secondary.startDateMillis
        val approvedAtMillis = primary.approvedAtMillis ?: secondary.approvedAtMillis ?: startDateMillis
        val reason = (primary.reason ?: secondary.reason)?.trim()?.takeIf { it.isNotBlank() }
        val rejectionReason = (primary.rejectionReason ?: secondary.rejectionReason)?.trim()?.takeIf { it.isNotBlank() }
        val resubmissionReason = (primary.resubmissionReason ?: secondary.resubmissionReason ?: rejectionReason)?.trim()?.takeIf { it.isNotBlank() }
        val paymentMethod = (primary.paymentMethod ?: secondary.paymentMethod)?.trim()?.takeIf { it.isNotBlank() }
        val pendingRequestId = (primary.pendingRequestId ?: secondary.pendingRequestId)?.trim()?.takeIf { it.isNotBlank() }
        val senderPhone = (primary.senderPhone ?: secondary.senderPhone)?.trim()?.takeIf { it.isNotBlank() }
        val transactionReference = (primary.transactionReference ?: secondary.transactionReference)?.trim()?.takeIf { it.isNotBlank() }
        val receiptStoragePath = (primary.receiptStoragePath ?: secondary.receiptStoragePath)?.trim()?.takeIf { it.isNotBlank() }
        val version = maxOf(primary.subscriptionVersion ?: 0L, secondary.subscriptionVersion ?: 0L)
        val updatedBy = primary.updatedBy ?: secondary.updatedBy

        val pId = primary.planId?.takeIf { it > 0 } ?: secondary.planId?.takeIf { it > 0 } ?: 0
        val rawPlanName = primary.planName?.takeIf { it.isNotBlank() } ?: secondary.planName?.takeIf { it.isNotBlank() }
        val effectivePlanId = if (pId > 0) pId else when {
            rawPlanName?.contains("8") == true || rawPlanName?.contains("المتقدمة") == true -> 4
            rawPlanName?.contains("6") == true || rawPlanName?.contains("المميزة") == true -> 3
            rawPlanName?.contains("4") == true || rawPlanName?.contains("المتوسطة") == true -> 2
            else -> 1
        }
        val planName = rawPlanName ?: when (effectivePlanId) {
            2 -> "الباقة المتوسطة"
            3 -> "الباقة المميزة"
            4 -> "الباقة المتقدمة"
            else -> "الباقة الأساسية"
        }
        val rawMaxCaptains = primary.maxCaptains?.takeIf { it > 0 } ?: secondary.maxCaptains?.takeIf { it > 0 } ?: 0
        // If Admin explicitly set status == "active", even a minimal doc without planId/maxCaptains defaults to Basic plan (2 captains)
        val hasValidPlan = pId > 0 || !rawPlanName.isNullOrBlank() || rawMaxCaptains > 0 || chosenStatus == "active"
        val resolvedMaxCaptains = if (rawMaxCaptains > 0) rawMaxCaptains else if (hasValidPlan) {
            when (effectivePlanId) {
                1 -> 2
                2 -> 4
                3 -> 6
                4 -> 8
                else -> 2
            }
        } else 0
        val planPrice = primary.price?.takeIf { it > 0 } ?: secondary.price?.takeIf { it > 0 } ?: when (effectivePlanId) {
            1 -> 400
            2 -> 800
            3 -> 1200
            4 -> 1600
            else -> 400
        }

        // Only treat boolean flags as deactivating an "active" status if isActive was explicitly set to false
        // without isSubscribed == true. A stale signup `isSubscribed: false` alone must NOT override Admin activation.
        val isExplicitlyDeactivatedByFlag = primary.isActive == false && primary.isSubscribed != true

        // Handle explicit admin suspension or inactive state FIRST
        if (chosenStatus == "suspended" || chosenStatus == "inactive" || (chosenStatus == "active" && isExplicitlyDeactivatedByFlag)) {
            val resolvedStatus = if (chosenStatus == "inactive") "inactive" else "suspended"
            val suspendMsg = if (reason != null) {
                "تم إيقاف اشتراكك من قبل الإدارة ($reason). يرجى التواصل مع الإدارة أو تجديد الاشتراك."
            } else {
                "تم إيقاف اشتراكك مؤقتاً من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك."
            }
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = true,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = resolvedStatus,
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = suspendMsg,
                suspensionReason = reason,
                isChecking = false,
                planId = if (hasValidPlan) effectivePlanId else 0,
                planName = if (hasValidPlan) planName else "غير مشترك",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = rawPaymentStatus,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Handle explicit admin cancellation SECOND
        if (chosenStatus == "cancelled") {
            val cancelMsg = if (reason != null) {
                "تم إلغاء اشتراكك من قبل الإدارة ($reason). يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
            } else {
                "تم إلغاء اشتراكك من قبل الإدارة. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
            }
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = true,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "cancelled",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = cancelMsg,
                suspensionReason = reason,
                isChecking = false,
                planId = 0,
                planName = "ملغي",
                maxCaptains = 0,
                price = 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = rawPaymentStatus,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Handle pending_review / pending admin review
        if (chosenStatus == "pending_review" || chosenStatus == "pending") {
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = true,
                isPendingReview = true,
                isRejected = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = if (chosenStatus == "pending_review") "pending_review" else "pending",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = if (chosenStatus == "pending_review") {
                    "طلب الاشتراك في انتظار مراجعة الإدارة. سيتم تفعيل الباقة تلقائياً فور الموافقة على إيصال التحويل."
                } else {
                    "طلب الاشتراك قيد المراجعة من الإدارة. سيتم تفعيل الباقة فور الموافقة."
                },
                isChecking = false,
                planId = if (hasValidPlan) effectivePlanId else 0,
                planName = if (hasValidPlan) planName else if (chosenStatus == "pending_review") "في انتظار مراجعة الإدارة" else "قيد المراجعة",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "pending_review",
                paymentMethod = paymentMethod,
                pendingRequestId = pendingRequestId,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Handle Admin rejection of manual Vodafone Cash request
        if (chosenStatus == "rejected") {
            val resolvedRejection = rejectionReason ?: reason
                ?: "تم رفض طلب الاشتراك من الإدارة. يرجى التأكد من بيانات وإيصال التحويل وإعادة المحاولة."
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isPendingReview = false,
                isRejected = true,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "rejected",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = resolvedRejection,
                rejectionReason = resolvedRejection,
                isChecking = false,
                planId = if (hasValidPlan) effectivePlanId else 0,
                planName = if (hasValidPlan) planName else "تم رفض الطلب",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "rejected",
                paymentMethod = paymentMethod,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Handle Admin request to re-upload/resubmit transfer receipt
        if (chosenStatus == "resubmission_required") {
            val resolvedResubmission = resubmissionReason ?: rejectionReason ?: reason
                ?: "صورة إيصال التحويل غير واضحة. يرجى إعادة رفع صورة واضحة للإيصال وإعادة الإرسال."
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isPendingReview = false,
                isRejected = false,
                isResubmissionRequired = true,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "resubmission_required",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = resolvedResubmission,
                rejectionReason = resolvedResubmission,
                resubmissionReason = resolvedResubmission,
                isChecking = false,
                planId = if (hasValidPlan) effectivePlanId else 0,
                planName = if (hasValidPlan) planName else "مطلوب إعادة إرسال الإيصال",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "resubmission_required",
                paymentMethod = paymentMethod,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                pendingRequestId = pendingRequestId,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Handle payment failed / pending payment (never block an explicitly Admin-activated "active" status)
        val isPaymentFailed = chosenStatus in listOf("payment_failed", "pending_payment") ||
            (chosenStatus != "active" && rawPaymentStatus in listOf("failed", "unpaid", "pending"))
        if (isPaymentFailed) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = if (chosenStatus == "pending_payment") "pending_payment" else "payment_failed",
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = "فشل عملية الدفع للباقة: لا يمكن طلب كابتن إلا بعد نجاح الدفع وتأكيد الاشتراك من السيرفر.",
                planId = 0,
                planName = "غير مشترك (فشل الدفع)",
                maxCaptains = 0,
                price = 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "failed",
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Unsubscribed / missing valid plan
        if (!hasValidPlan || chosenStatus == "unsubscribed") {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "unsubscribed",
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = "أنت غير مشترك في أي باقة حالياً",
                planId = 0,
                planName = "غير مشترك",
                maxCaptains = 0,
                price = 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "unpaid",
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Expired status or passed endDate
        val isExpiredByDate = endDateMillis != null && nowMillis >= endDateMillis
        if (chosenStatus == "expired" || isExpiredByDate) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = true,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "expired",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = "انتهت فترة اشتراكك، يرجى التواصل مع الإدارة أو تجديد الاشتراك.",
                planId = effectivePlanId,
                planName = planName,
                maxCaptains = resolvedMaxCaptains,
                price = planPrice,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = rawPaymentStatus,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Active status: verify server trust & connectivity
        val isExplicitActive = chosenStatus == "active" && !isExplicitlyDeactivatedByFlag && resolvedMaxCaptains > 0
        if (!isExplicitActive) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = chosenStatus.ifBlank { "unsubscribed" },
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                message = "الاشتراك غير نشط حالياً. يرجى التواصل مع الإدارة أو تجديد الاشتراك.",
                planId = effectivePlanId,
                planName = planName,
                maxCaptains = 0,
                price = planPrice,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = rawPaymentStatus,
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        // Stale-cache & offline guard:
        // Never allow offline or unverified cached "active" data to keep services active.
        val requiresLiveVerification = !isOnline || (chosenIsFromCache && !hasVerifiedServerOnce)
        if (requiresLiveVerification) {
            return SubscriptionStatusState(
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = true,
                isFromCache = true,
                status = "active",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = null,
                message = if (!isOnline) {
                    "انقطع الاتصال بالإنترنت: لا يمكن الاعتماد على بيانات مخزنة مؤقتاً لتفعيل الاشتراك. يرجى إعادة الاتصال لمزامنة حالة الاشتراك."
                } else {
                    "جاري التحقق الفوري من حالة الاشتراك مع السيرفر..."
                },
                isChecking = isOnline,
                planId = effectivePlanId,
                planName = planName,
                maxCaptains = resolvedMaxCaptains,
                price = planPrice,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "paid",
                subscriptionVersion = version,
                updatedBy = updatedBy
            )
        }

        val availableSlots = (resolvedMaxCaptains - activeCaptainsCount).coerceAtLeast(0)
        return SubscriptionStatusState(
            isSubscribed = true,
            isExpired = false,
            isSuspended = false,
            isCancelled = false,
            isPending = false,
            isPendingReview = false,
            isRejected = false,
            isResubmissionRequired = false,
            isOfflineUnverified = false,
            isFromCache = chosenIsFromCache,
            status = "active",
            startDateMillis = startDateMillis,
            approvedAtMillis = approvedAtMillis,
            endDateMillis = endDateMillis,
            lastServerSyncMillis = if (!chosenIsFromCache) nowMillis else null,
            message = null,
            suspensionReason = null,
            rejectionReason = null,
            resubmissionReason = null,
            serverError = null,
            isChecking = false,
            planId = effectivePlanId,
            planName = planName,
            maxCaptains = resolvedMaxCaptains,
            price = planPrice,
            activeCaptainsCount = activeCaptainsCount,
            availableCaptainSlots = availableSlots,
            // Admin-activated active subscription is always treated as paid so stale unpaid flags cannot block requests
            paymentStatus = "paid",
            paymentMethod = paymentMethod,
            subscriptionVersion = version,
            updatedBy = updatedBy
        )
    }

    fun detectTransitionNotification(
        previous: SubscriptionStatusState,
        current: SubscriptionStatusState,
        hadPreviousState: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionTransitionEvent? {
        if (!hadPreviousState) return null
        val wasActive = previous.canRequestCaptain(nowMillis)
        val isNowActive = current.canRequestCaptain(nowMillis)

        return when {
            !wasActive && isNowActive && !current.isOfflineUnverified -> SubscriptionTransitionEvent(
                title = "تمت الموافقة على الاشتراك ✓",
                body = "تم تفعيل ${current.planName} (${current.maxCaptains} كابتن) بنجاح. يمكنك الآن طلب الكباتن.",
                isActivation = true
            )
            wasActive && isNowActive && !current.isOfflineUnverified &&
                (previous.planId != current.planId || previous.maxCaptains != current.maxCaptains) ->
                SubscriptionTransitionEvent(
                    title = "تم تغيير الباقة 🔄",
                    body = "تم تحديث اشتراكك إلى ${current.planName} بحد أقصى ${current.maxCaptains} كابتن.",
                    isActivation = true
                )
            wasActive && current.isSuspended -> SubscriptionTransitionEvent(
                title = "تم إيقاف الاشتراك",
                body = current.message ?: "تم إيقاف اشتراكك من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك.",
                isActivation = false
            )
            wasActive && current.isCancelled -> SubscriptionTransitionEvent(
                title = "تم إلغاء الاشتراك",
                body = current.message ?: "تم إلغاء اشتراكك. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة.",
                isActivation = false
            )
            wasActive && current.isExpired -> SubscriptionTransitionEvent(
                title = "انتهى الاشتراك",
                body = current.message ?: "انتهت فترة اشتراكك، يرجى تجديد الاشتراك.",
                isActivation = false
            )
            !previous.isRejected && current.isRejected -> SubscriptionTransitionEvent(
                title = "تم رفض طلب الاشتراك ❌",
                body = current.rejectionReason ?: current.message ?: "تم رفض طلب الاشتراك من الإدارة.",
                isActivation = false
            )
            !previous.isResubmissionRequired && current.isResubmissionRequired -> SubscriptionTransitionEvent(
                title = "مطلوب إعادة إرسال الإيصال 📄",
                body = current.resubmissionReason ?: current.message ?: "يرجى إعادة رفع صورة واضحة لإيصال التحويل وإعادة الإرسال.",
                isActivation = false
            )
            !previous.isPendingReview && current.isPendingReview -> SubscriptionTransitionEvent(
                title = if (current.status == "pending_review") {
                    "طلب الاشتراك قيد مراجعة الإدارة ⏳"
                } else {
                    "طلب الاشتراك قيد المراجعة ⏳"
                },
                body = current.message ?: "تم إرسال طلب اشتراكك للمراجعة من الإدارة.",
                isActivation = false
            )
            else -> null
        }
    }
}
