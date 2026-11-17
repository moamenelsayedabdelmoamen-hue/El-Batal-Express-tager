package com.example.db

import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Locale

data class SubscriptionStatusState(
    val isSubscribed: Boolean = false,
    val isExpired: Boolean = false,
    val isSuspended: Boolean = false,
    val isCancelled: Boolean = false,
    val isOfflineUnverified: Boolean = false,
    val isFromCache: Boolean = false,
    val status: String = "unsubscribed",
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null,
    val lastServerSyncMillis: Long? = null,
    val message: String? = null,
    val suspensionReason: String? = null,
    val isChecking: Boolean = false,
    val planId: Int = 0,
    val planName: String = "غير مشترك",
    val maxCaptains: Int = 0,
    val price: Int = 0,
    val activeCaptainsCount: Int = 0,
    val availableCaptainSlots: Int = 0,
    val paymentStatus: String = "unpaid"
) {
    fun canRequestCaptain(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isSubscribed || isExpired || isSuspended || isCancelled || isOfflineUnverified) return false
        if (status != "active") return false
        if (paymentStatus in listOf("failed", "unpaid", "pending")) return false
        if (maxCaptains <= 0) return false
        if (endDateMillis != null && nowMillis >= endDateMillis) return false
        return true
    }
}

data class RawSubscriptionSnapshot(
    val exists: Boolean = false,
    val status: String? = null,
    val subscriptionStatus: String? = null,
    val planStatus: String? = null,
    val paymentStatus: String? = null,
    val isSubscribed: Boolean? = null,
    val planId: Int? = null,
    val planName: String? = null,
    val maxCaptains: Int? = null,
    val price: Int? = null,
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val reason: String? = null,
    val isFromCache: Boolean = false
)

data class SubscriptionTransitionEvent(
    val title: String,
    val body: String,
    val isActivation: Boolean
)

object SubscriptionSyncResolver {

    fun normalizeStatus(raw: String?): String {
        val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (s) {
            "active", "نشط", "نشطة", "سارية" -> "active"
            "suspended", "suspend", "paused", "موقوف", "موقوفة", "متوقف", "متوقفة", "inactive" -> "suspended"
            "cancelled", "canceled", "cancel", "ملغي", "ملغى", "ملغاة" -> "cancelled"
            "expired", "منتهي", "منتهية" -> "expired"
            "payment_failed", "failed", "declined" -> "payment_failed"
            "pending_payment", "في انتظار الدفع" -> "pending_payment"
            "unsubscribed", "none", "" -> "unsubscribed"
            else -> s
        }
    }

    fun fromFirestoreSnapshot(snapshot: DocumentSnapshot?): RawSubscriptionSnapshot {
        if (snapshot == null || !snapshot.exists()) {
            return RawSubscriptionSnapshot(
                exists = false,
                isFromCache = snapshot?.metadata?.isFromCache == true
            )
        }
        return RawSubscriptionSnapshot(
            exists = true,
            status = snapshot.safeString("status"),
            subscriptionStatus = snapshot.safeString("subscriptionStatus"),
            planStatus = snapshot.safeString("planStatus"),
            paymentStatus = snapshot.safeString("paymentStatus"),
            isSubscribed = snapshot.getBoolean("isSubscribed"),
            planId = (snapshot.get("planId") as? Number)?.toInt()
                ?: snapshot.safeString("planId")?.toIntOrNull(),
            planName = snapshot.getAnyString("planName", "plan_name", "packageName", "title"),
            maxCaptains = (snapshot.get("maxCaptains") as? Number)?.toInt()
                ?: (snapshot.get("captains") as? Number)?.toInt()
                ?: snapshot.safeString("maxCaptains")?.toIntOrNull(),
            price = (snapshot.get("price") as? Number)?.toInt()
                ?: (snapshot.get("amount") as? Number)?.toInt()
                ?: snapshot.safeString("price")?.toIntOrNull(),
            startDateMillis = parseDateFields(
                snapshot,
                listOf("startDate", "subscriptionStartDate", "createdAt")
            ),
            endDateMillis = parseDateFields(
                snapshot,
                listOf("endDate", "subscriptionEndDate", "renewalDate", "nextBillingDate", "expiryDate", "expiresAt")
            ),
            updatedAtMillis = parseDateFields(
                snapshot,
                listOf("updatedAt", "cancelledAt", "suspendedAt")
            ),
            reason = snapshot.getAnyString("suspensionReason", "cancellationReason", "reason"),
            isFromCache = snapshot.metadata.isFromCache
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
                val num = snapshot.get(field) as? Number
                if (num != null) return num.toLong()
            } catch (_: Exception) {}
            try {
                val str = snapshot.safeString(field)
                if (!str.isNullOrBlank()) {
                    str.toLongOrNull()?.let { return it }
                    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(str)
                    if (parsed != null) return parsed.time
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun resolve(
        subscriptionDoc: RawSubscriptionSnapshot,
        merchantDoc: RawSubscriptionSnapshot,
        isOnline: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        activeCaptainsCount: Int = 0,
        hasVerifiedServerOnce: Boolean = false
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

        // Reconcile subscriptions/{uid} and merchants/{uid}:
        // 1. Both exist -> prefer newer updatedAt; on tie/missing updatedAt, restricted states (suspended/cancelled/expired) win for security.
        // 2. Fallback to whichever exists.
        val useMerchantAsPrimary = when {
            !subscriptionDoc.exists && merchantDoc.exists -> true
            subscriptionDoc.exists && merchantDoc.exists -> {
                val subUpdated = subscriptionDoc.updatedAtMillis ?: 0L
                val merchUpdated = merchantDoc.updatedAtMillis ?: 0L
                val merchExplicitRestrictive = merchStatusNorm in listOf("suspended", "cancelled", "expired", "payment_failed")
                val subExplicitActive = subStatusNorm == "active" && subscriptionDoc.isSubscribed != false
                when {
                    merchStatusNorm != "unsubscribed" && merchUpdated > subUpdated -> true
                    subUpdated > merchUpdated -> false
                    merchExplicitRestrictive && subStatusNorm != merchStatusNorm -> true
                    !subExplicitActive && merchStatusNorm == "active" -> true
                    else -> false
                }
            }
            else -> false
        }

        val primary = if (useMerchantAsPrimary) merchantDoc else subscriptionDoc
        val secondary = if (useMerchantAsPrimary) subscriptionDoc else merchantDoc

        if (!primary.exists) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
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
        val reason = (primary.reason ?: secondary.reason)?.trim()?.takeIf { it.isNotBlank() }

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
        val hasValidPlan = pId > 0 || !rawPlanName.isNullOrBlank() || rawMaxCaptains > 0
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

        // Handle explicit admin suspension FIRST
        if (chosenStatus == "suspended" || (chosenStatus == "active" && primary.isSubscribed == false)) {
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
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "suspended",
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
                paymentStatus = rawPaymentStatus
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
                paymentStatus = rawPaymentStatus
            )
        }

        // Handle payment failed / pending payment
        val isPaymentFailed = chosenStatus in listOf("payment_failed", "pending_payment") ||
            rawPaymentStatus in listOf("failed", "unpaid", "pending")
        if (isPaymentFailed && chosenStatus != "active") {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = "payment_failed",
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = "فشل عملية الدفع للباقة: لا يمكن طلب كابتن إلا بعد نجاح الدفع وتأكيد الاشتراك من السيرفر.",
                planId = 0,
                planName = "غير مشترك (فشل الدفع)",
                maxCaptains = 0,
                price = 0,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "failed"
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
                paymentStatus = "unpaid"
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
                paymentStatus = rawPaymentStatus
            )
        }

        // Active status: verify server trust & connectivity
        val isExplicitActive = chosenStatus == "active" && primary.isSubscribed != false && resolvedMaxCaptains > 0
        if (!isExplicitActive) {
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
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
                paymentStatus = rawPaymentStatus
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
                paymentStatus = rawPaymentStatus
            )
        }

        val availableSlots = (resolvedMaxCaptains - activeCaptainsCount).coerceAtLeast(0)
        return SubscriptionStatusState(
            isSubscribed = true,
            isExpired = false,
            isSuspended = false,
            isCancelled = false,
            isOfflineUnverified = false,
            isFromCache = chosenIsFromCache,
            status = "active",
            startDateMillis = startDateMillis,
            endDateMillis = endDateMillis,
            lastServerSyncMillis = if (!chosenIsFromCache) nowMillis else null,
            message = null,
            suspensionReason = null,
            isChecking = false,
            planId = effectivePlanId,
            planName = planName,
            maxCaptains = resolvedMaxCaptains,
            price = planPrice,
            activeCaptainsCount = activeCaptainsCount,
            availableCaptainSlots = availableSlots,
            paymentStatus = rawPaymentStatus
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
                title = "تم تفعيل الاشتراك ✓",
                body = "تم تفعيل ${current.planName} (${current.maxCaptains} كابتن) بنجاح. يمكنك الآن طلب الكباتن.",
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
            else -> null
        }
    }
}
