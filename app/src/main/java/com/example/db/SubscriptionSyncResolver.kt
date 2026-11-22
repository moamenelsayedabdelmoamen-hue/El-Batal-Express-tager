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
    val planId: String = "",
    val planKey: String? = null,
    val planName: String = "غير مشترك",
    val maxCaptains: Int = 0,
    val price: Int = 0,
    val durationDays: Int = 0,
    val features: List<String> = emptyList(),
    val activeCaptainsCount: Int = 0,
    val availableCaptainSlots: Int = 0,
    val paymentStatus: String = "unpaid",
    val paymentMethod: String? = null,
    val pendingRequestId: String? = null,
    val requestNumber: String? = null,
    val senderPhone: String? = null,
    val transactionReference: String? = null,
    val receiptStoragePath: String? = null,
    val subscriptionVersion: Long = 0L,
    val updatedBy: String? = null,
    val subscriptionId: String? = null,
    val merchantId: String? = null,
    val packageId: String? = null
) {
    fun canRequestCaptain(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isSubscribed || isExpired || isSuspended || isCancelled || isPending || isPendingReview || isRejected || isResubmissionRequired || isOfflineUnverified) return false
        val normalizedStatus = SubscriptionSyncResolver.normalizeStatus(status)
        if (normalizedStatus != "active") return false
        val normalizedPayment = SubscriptionSyncResolver.normalizePaymentStatus(paymentStatus)
        if (normalizedPayment in SubscriptionSyncResolver.BLOCKED_PAYMENT_STATUSES) return false
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
    val planId: String? = null,
    val planKey: String? = null,
    val planName: String? = null,
    val maxCaptains: Int? = null,
    val price: Int? = null,
    val durationDays: Int? = null,
    val features: List<String> = emptyList(),
    val startDateMillis: Long? = null,
    val approvedAtMillis: Long? = null,
    val endDateMillis: Long? = null,
    val archivedAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val subscriptionUpdatedAtMillis: Long? = null,
    val lastLoginAtMillis: Long? = null,
    val reason: String? = null,
    val rejectionReason: String? = null,
    val resubmissionReason: String? = null,
    val paymentMethod: String? = null,
    val pendingRequestId: String? = null,
    val requestNumber: String? = null,
    val senderPhone: String? = null,
    val transactionReference: String? = null,
    val receiptStoragePath: String? = null,
    val subscriptionVersion: Long? = null,
    val updatedBy: String? = null,
    val isFromCache: Boolean = false,
    val documentId: String? = null,
    val subscriptionId: String? = null,
    val merchantId: String? = null,
    val packageId: String? = null,
    val createdAtMillis: Long? = null
)

data class SubscriptionHistoryItem(
    val id: String,
    val subscriptionId: String,
    val merchantId: String,
    val planId: String,
    val packageId: String,
    val planName: String,
    val price: Int,
    val maxCaptains: Int,
    val status: String,
    val paymentStatus: String,
    val paymentMethod: String?,
    val startDateMillis: Long?,
    val endDateMillis: Long?,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
    val archivedAtMillis: Long? = null,
    val durationDays: Int = 0,
    val features: List<String> = emptyList(),
    val reason: String? = null,
    val isCurrent: Boolean = false
)

data class ManualSubscriptionRequestRecord(
    val requestId: String,
    val merchantId: String,
    val planId: String,
    val packageId: String,
    val planName: String,
    val maxCaptains: Int,
    val expectedAmount: Int,
    val senderPhone: String,
    val transactionReference: String,
    val receiptStoragePath: String,
    val status: String,
    val paymentStatus: String = "pending_verification",
    val requestNumber: String? = null,
    val rejectionReason: String? = null,
    val resubmissionReason: String? = null,
    val notes: String = "",
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val reviewedAtMillis: Long? = null
) {
    val amount: Int get() = expectedAmount
    val receiptImagePath: String get() = receiptStoragePath
}

data class MerchantPaymentRecord(
    val paymentId: String,
    val transactionId: String,
    val merchantId: String,
    val subscriptionId: String?,
    val packageId: String?,
    val packageName: String?,
    val amount: Double,
    val currency: String = "EGP",
    val paymentMethod: String,
    val paymentStatus: String,
    val referenceNumber: String?,
    val paymentDateMillis: Long?,
    val createdAtMillis: Long?,
    val notes: String? = null
)

data class SubscriptionTransitionEvent(
    val title: String,
    val body: String,
    val isActivation: Boolean
)

object SubscriptionSyncResolver {

    /**
     * Authoritative set of payment statuses that MUST block captain requests,
     * aligned with server validators.ts and Dashboard Cloud Functions.
     */
    val BLOCKED_PAYMENT_STATUSES: Set<String> = setOf(
        "pending",
        "pending_verification",
        "pending_review",
        "resubmission_requested",
        "resubmission_required",
        "unpaid",
        "rejected",
        "failed"
    )

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

            "pending_review", "pending_verification", "awaiting_review", "awaiting_verification",
            "manual_pending", "under_review", "in_review",
            "في انتظار مراجعة الإدارة", "في انتظار مراجعة الادارة", "قيد مراجعة الإدارة",
            "بانتظار مراجعة الإدارة", "في انتظار التحقق" -> "pending_review"

            "rejected", "request_rejected", "manual_rejected",
            "مرفوض", "مرفوضة", "تم رفض الطلب", "تم الرفض" -> "rejected"

            "resubmission_required", "resubmission_requested", "resubmit", "resubmit_required",
            "reupload_receipt", "receipt_resubmission_required",
            "مطلوب إعادة إرسال الإيصال", "مطلوب اعادة ارسال الايصال",
            "إعادة إرسال الإيصال", "اعادة ارسال الايصال" -> "resubmission_required"

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

    /**
     * Parses canonical String `planId`, preserving arbitrary server string IDs (e.g. `plan_10_captains`,
     * `vip_monthly`, Firestore doc IDs) while transparently supporting legacy numeric IDs (`1`, `2`, `3`, `4`)
     * as well as `planKey` and `packageId`.
     */
    fun parsePlanIdString(
        rawPlanId: Any? = null,
        rawPlanKey: Any? = null,
        rawPackageId: Any? = null
    ): String? {
        fun cleanSingle(raw: Any?): String? {
            if (raw == null) return null
            if (raw is Number) {
                val n = raw.toLong()
                return if (n > 0L) n.toString() else null
            }
            val s = raw.toString().trim()
            if (s.isEmpty() || s == "0" || s.equals("null", ignoreCase = true)) return null
            return s
        }
        return cleanSingle(rawPlanId)
            ?: cleanSingle(rawPlanKey)
            ?: cleanSingle(rawPackageId)
    }

    /**
     * Compares two plan identifiers accounting for exact string equality and legacy numeric aliases.
     */
    fun planIdsMatch(first: String?, second: String?): Boolean {
        val a = first?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val b = second?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (a.equals(b, ignoreCase = true)) return true
        fun canonicalLegacyAlias(id: String): String = when (id.lowercase(Locale.ROOT)) {
            "1", "plan_1", "plan_2_captains" -> "plan_2_captains"
            "2", "plan_2", "plan_4_captains" -> "plan_4_captains"
            "3", "plan_3", "plan_6_captains" -> "plan_6_captains"
            "4", "plan_4", "plan_8_captains" -> "plan_8_captains"
            else -> id.lowercase(Locale.ROOT)
        }
        return canonicalLegacyAlias(a) == canonicalLegacyAlias(b)
    }

    fun parseFeaturesList(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
    }

    fun formatPaymentMethodArabic(rawMethod: String?): String {
        val m = rawMethod?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            m.isEmpty() -> "غير محدد"
            m.contains("vodafone") || m.contains("فودافون") -> "فودافون كاش"
            m == "admin_manual" || m == "manual" || m.contains("admin") || m.contains("يدوي") -> "تسجيل يدوي من الإدارة"
            m.contains("instapay") || m.contains("انستاباي") -> "إنستاباي"
            m.contains("bank") || m.contains("تحويل بنكي") -> "تحويل بنكي"
            m.contains("cash") || m.contains("نقد") || m.contains("كاش") -> "دفع نقدي"
            else -> rawMethod!!.trim()
        }
    }

    fun formatPaymentStatusArabic(rawStatus: String?): String {
        return when (normalizePaymentStatus(rawStatus)) {
            "paid" -> "مدفوع ✓"
            "admin_manual" -> "مفعّل يدوياً من الإدارة ✓"
            "pending_verification" -> "في انتظار التحقق من الدفع"
            "pending_review" -> "قيد مراجعة الإدارة"
            "pending" -> "قيد الانتظار"
            "resubmission_requested", "resubmission_required" -> "مطلوب إعادة إرسال الإيصال"
            "rejected" -> "مرفوض"
            "failed" -> "فشل الدفع"
            "refunded" -> "مسترد"
            else -> "غير مدفوع"
        }
    }

    fun normalizePaymentStatus(raw: String?): String {
        val s = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (s) {
            "paid", "completed", "success", "succeeded", "confirmed", "approved", "verified",
            "مدفوع", "مكتمل", "ناجح", "تم الدفع" -> "paid"
            "admin_manual", "manual_granted", "granted" -> "admin_manual"
            "pending_verification", "awaiting_verification", "في انتظار التحقق" -> "pending_verification"
            "pending_review", "awaiting_review", "قيد مراجعة الإدارة" -> "pending_review"
            "pending", "processing", "قيد المراجعة", "معلق" -> "pending"
            "rejected", "declined", "مرفوض" -> "rejected"
            "resubmission_requested" -> "resubmission_requested"
            "resubmission_required", "resubmit", "resubmit_required" -> "resubmission_required"
            "failed", "payment_failed", "فشل" -> "failed"
            "refunded", "مسترد" -> "refunded"
            "unpaid", "none", "" -> "unpaid"
            else -> s
        }
    }

    private fun extractIntFromMapOrSnapshot(raw: Any?): Int? {
        if (raw == null) return null
        if (raw is Number) return raw.toInt().takeIf { it > 0 }
        return raw.toString().trim().toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    }

    private fun extractNestedPlanMap(raw: Any?): Map<*, *>? = raw as? Map<*, *>

    /**
     * Extracts captain count from structured plan identifiers or Arabic/English plan names
     * such as `plan_2_captains`, `pkg_6_captains`, or `باقة 4 كابتن` when numeric limit fields are omitted.
     */
    fun extractCaptainsFromPlanText(vararg values: String?): Int? {
        val patterns = listOf(
            Regex("""(?:plan|pkg|package)[_\-\s]*(\d{1,3})[_\-\s]*(?:captain|captains|driver|drivers)""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3})\s*(?:كابتن|كباتن|مندوب|مناديب|captains?|drivers?)""", RegexOption.IGNORE_CASE)
        )
        for (raw in values) {
            val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: continue
            for (pattern in patterns) {
                val match = pattern.find(text)
                val count = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (count != null && count in 1..500) return count
            }
        }
        return null
    }

    /**
     * Enriches an EXISTING subscription snapshot with missing metadata (`planId`, `planName`,
     * `maxCaptains`, `durationDays`, `features`, `paymentMethod`) from live Firestore packages
     * and the merchant's own paid payment records.
     *
     * SAFETY: Never creates or activates a subscription when `snapshot.exists == false`.
     */
    fun enrichSnapshotFromPackagesAndPayments(
        snapshot: RawSubscriptionSnapshot,
        packages: List<SubscriptionPlanOption>,
        payments: List<MerchantPaymentRecord> = emptyList()
    ): RawSubscriptionSnapshot {
        if (!snapshot.exists) return snapshot

        // 1. Cross-reference linked paid payment ONLY for missing display/metadata fields
        val linkedPayment = payments.firstOrNull { pay ->
            val subIdMatch = !pay.subscriptionId.isNullOrBlank() && (
                pay.subscriptionId == snapshot.subscriptionId ||
                    pay.subscriptionId == snapshot.documentId
                )
            subIdMatch
        } ?: if (snapshot.planName.isNullOrBlank() || snapshot.paymentMethod.isNullOrBlank()) {
            val subPrice = snapshot.price ?: 0
            if (subPrice > 0) {
                payments.firstOrNull { pay ->
                    normalizePaymentStatus(pay.paymentStatus) in setOf("paid", "admin_manual") &&
                        pay.amount.toInt() == subPrice &&
                        (!pay.packageName.isNullOrBlank() || !pay.packageId.isNullOrBlank() || pay.paymentMethod.isNotBlank())
                }
            } else null
        } else null

        val withPaymentMeta = if (linkedPayment != null) {
            snapshot.copy(
                planName = snapshot.planName?.takeIf { it.isNotBlank() } ?: linkedPayment.packageName,
                packageId = snapshot.packageId?.takeIf { it.isNotBlank() } ?: linkedPayment.packageId,
                planId = snapshot.planId?.takeIf { it.isNotBlank() } ?: linkedPayment.packageId,
                paymentMethod = snapshot.paymentMethod?.takeIf { it.isNotBlank() }
                    ?: linkedPayment.paymentMethod.takeIf { it.isNotBlank() }
            )
        } else {
            snapshot
        }

        // 2. Match live Firestore package by ID, key, name, or unique price when metadata is missing
        val matchedPackage = packages.firstOrNull { pkg ->
            planIdsMatch(pkg.id, withPaymentMeta.planId) ||
                planIdsMatch(pkg.planKey, withPaymentMeta.planId) ||
                planIdsMatch(pkg.packageId, withPaymentMeta.planId) ||
                planIdsMatch(pkg.id, withPaymentMeta.packageId) ||
                planIdsMatch(pkg.id, withPaymentMeta.planKey)
        } ?: withPaymentMeta.planName?.trim()?.takeIf { it.isNotBlank() }?.let { targetName ->
            packages.firstOrNull { pkg ->
                pkg.name.trim().equals(targetName, ignoreCase = true)
            }
        } ?: run {
            val subPrice = withPaymentMeta.price ?: 0
            if (subPrice > 0 && ((withPaymentMeta.maxCaptains ?: 0) <= 0 || withPaymentMeta.planName.isNullOrBlank())) {
                val priceMatches = packages.filter { it.priceEgp == subPrice }
                if (priceMatches.size == 1) priceMatches.first() else null
            } else {
                null
            }
        }

        val derivedDuration = withPaymentMeta.durationDays?.takeIf { it > 0 }
            ?: matchedPackage?.durationDays?.takeIf { it > 0 }
            ?: run {
                val start = withPaymentMeta.startDateMillis
                val end = withPaymentMeta.endDateMillis
                if (start != null && end != null && end > start) {
                    ((end - start) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(1)
                } else null
            }

        if (matchedPackage == null) {
            val extractedCaptains = withPaymentMeta.maxCaptains?.takeIf { it > 0 }
                ?: extractCaptainsFromPlanText(
                    withPaymentMeta.planId,
                    withPaymentMeta.planKey,
                    withPaymentMeta.packageId,
                    withPaymentMeta.planName
                )
            return withPaymentMeta.copy(
                maxCaptains = extractedCaptains ?: withPaymentMeta.maxCaptains,
                durationDays = derivedDuration ?: withPaymentMeta.durationDays
            )
        }

        return withPaymentMeta.copy(
            planId = withPaymentMeta.planId?.takeIf { it.isNotBlank() } ?: matchedPackage.id,
            planKey = withPaymentMeta.planKey?.takeIf { it.isNotBlank() } ?: matchedPackage.planKey,
            packageId = withPaymentMeta.packageId?.takeIf { it.isNotBlank() } ?: matchedPackage.packageId,
            planName = withPaymentMeta.planName?.takeIf { it.isNotBlank() } ?: matchedPackage.name,
            maxCaptains = withPaymentMeta.maxCaptains?.takeIf { it > 0 } ?: matchedPackage.maxCaptains,
            price = withPaymentMeta.price?.takeIf { it > 0 } ?: matchedPackage.priceEgp,
            durationDays = derivedDuration ?: matchedPackage.durationDays,
            features = withPaymentMeta.features.ifEmpty { matchedPackage.features }
        )
    }

    fun fromFirestoreSnapshot(snapshot: DocumentSnapshot?): RawSubscriptionSnapshot {
        if (snapshot == null || !snapshot.exists()) {
            return RawSubscriptionSnapshot(
                exists = false,
                documentId = snapshot?.id,
                isFromCache = snapshot?.metadata?.isFromCache == true
            )
        }
        val nestedPlanMap = extractNestedPlanMap(snapshot.get("plan"))
            ?: extractNestedPlanMap(snapshot.get("package"))
            ?: extractNestedPlanMap(snapshot.get("planDetails"))
            ?: extractNestedPlanMap(snapshot.get("packageDetails"))
        val isActiveField = snapshot.getBoolean("isActive")
        val isSubscribedField = snapshot.getBoolean("isSubscribed")
        val limitKeys = listOf(
            "captainLimit", "maxCaptains", "captainsLimit", "captainsCount", "captainCount",
            "captains_count", "captain_limit", "max_captains", "allowedCaptains", "allowed_captains",
            "numberOfCaptains", "numCaptains", "driversLimit", "maxDrivers", "driverLimit",
            "captains", "drivers", "limit", "quota"
        )
        val rawPlanName = snapshot.getAnyString(
            "planName", "packageName", "plan_name", "package_name",
            "packageTitle", "planTitle", "subscriptionPlanName", "subscriptionPlan",
            "tierName", "title", "name", "label"
        )
            ?: (snapshot.get("plan") as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: (snapshot.get("package") as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: listOf("planName", "packageName", "name", "title", "label")
                .firstNotNullOfOrNull { k -> nestedPlanMap?.get(k)?.toString()?.trim()?.takeIf { it.isNotBlank() } }
        val rawPlanKey = snapshot.getAnyString("planKey", "plan_key")
            ?: nestedPlanMap?.get("planKey")?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val rawPackageId = snapshot.getAnyString(
            "packageId", "package_id", "planId", "plan_id", "planKey", "plan_key", "subscriptionPlanId", "tierId"
        ) ?: listOf("packageId", "planId", "id", "planKey")
            .firstNotNullOfOrNull { k -> nestedPlanMap?.get(k)?.toString()?.trim()?.takeIf { it.isNotBlank() } }
        val parsedPlanId = parsePlanIdString(
            rawPlanId = snapshot.get("planId") ?: snapshot.get("plan_id") ?: nestedPlanMap?.get("planId") ?: nestedPlanMap?.get("id"),
            rawPlanKey = snapshot.get("planKey") ?: snapshot.get("plan_key") ?: nestedPlanMap?.get("planKey"),
            rawPackageId = snapshot.get("packageId") ?: snapshot.get("package_id") ?: snapshot.get("subscriptionPlanId") ?: nestedPlanMap?.get("packageId")
        )
        val limitValue = limitKeys.firstNotNullOfOrNull { key ->
            extractIntFromMapOrSnapshot(snapshot.get(key))
        } ?: limitKeys.firstNotNullOfOrNull { key ->
            extractIntFromMapOrSnapshot(nestedPlanMap?.get(key))
        } ?: extractCaptainsFromPlanText(parsedPlanId, rawPlanKey, rawPackageId, rawPlanName)

        val startMs = parseDateFields(
            snapshot,
            listOf("activatedAt", "startDate", "subscriptionStartDate", "startsAt", "createdAt")
        )
        val endMs = parseDateFields(
            snapshot,
            listOf("expiresAt", "endDate", "subscriptionEndDate", "endsAt", "renewalDate", "nextBillingDate", "expiryDate")
        )
        val durationDays = listOf("durationDays", "duration_days", "duration", "days", "periodDays")
            .firstNotNullOfOrNull { key -> extractIntFromMapOrSnapshot(snapshot.get(key)) }
            ?: listOf("durationDays", "duration", "days")
                .firstNotNullOfOrNull { key -> extractIntFromMapOrSnapshot(nestedPlanMap?.get(key)) }
            ?: if (startMs != null && endMs != null && endMs > startMs) {
                ((endMs - startMs) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(1)
            } else null
        val features = parseFeaturesList(snapshot.get("features"))
            .ifEmpty { parseFeaturesList(nestedPlanMap?.get("features")) }
        val priceValue = listOf("price", "amount", "packagePrice", "planPrice", "subscriptionPrice", "cost", "totalAmount", "fee")
            .firstNotNullOfOrNull { key -> extractIntFromMapOrSnapshot(snapshot.get(key)) }
            ?: listOf("price", "amount", "priceEgp")
                .firstNotNullOfOrNull { key -> extractIntFromMapOrSnapshot(nestedPlanMap?.get(key)) }
        return RawSubscriptionSnapshot(
            exists = true,
            documentId = snapshot.id,
            subscriptionId = snapshot.getAnyString("subscriptionId", "subscription_id") ?: snapshot.id,
            merchantId = snapshot.getAnyString("merchantId", "merchant_id", "uid", "ownerId"),
            packageId = rawPackageId ?: parsedPlanId,
            planKey = rawPlanKey ?: parsedPlanId,
            status = snapshot.safeString("status"),
            subscriptionStatus = snapshot.safeString("subscriptionStatus"),
            planStatus = snapshot.safeString("planStatus"),
            paymentStatus = snapshot.safeString("paymentStatus"),
            isActive = isActiveField,
            isSubscribed = isSubscribedField,
            planId = parsedPlanId,
            planName = rawPlanName,
            maxCaptains = limitValue,
            price = priceValue,
            durationDays = durationDays,
            features = features,
            startDateMillis = startMs,
            approvedAtMillis = parseDateFields(
                snapshot,
                listOf("approvedAt", "activatedAt", "startDate")
            ),
            endDateMillis = endMs,
            archivedAtMillis = parseDateFields(
                snapshot,
                listOf("archivedAtMs", "archivedAt", "archived_at")
            ),
            createdAtMillis = parseDateFields(
                snapshot,
                listOf("createdAt", "created_at", "startDate", "activatedAt")
            ),
            updatedAtMillis = parseDateFields(
                snapshot,
                listOf("updatedAt", "updated_at", "cancelledAt", "suspendedAt", "rejectedAt", "resubmissionRequestedAt")
            ),
            subscriptionUpdatedAtMillis = parseDateFields(
                snapshot,
                listOf("subscriptionUpdatedAt", "activatedAt", "suspendedAt", "cancelledAt", "rejectedAt", "resubmissionRequestedAt")
            ),
            lastLoginAtMillis = parseDateFields(snapshot, listOf("lastLoginAt")),
            reason = snapshot.getAnyString("suspensionReason", "cancellationReason", "resubmissionReason", "rejectionReason", "reason"),
            rejectionReason = snapshot.getAnyString("rejectionReason"),
            resubmissionReason = snapshot.getAnyString("resubmissionReason", "rejectionReason"),
            paymentMethod = snapshot.getAnyString("paymentMethod", "payment_method", "method", "paymentGateway", "gateway", "paymentType", "source"),
            pendingRequestId = snapshot.getAnyString("pendingRequestId", "requestId"),
            requestNumber = snapshot.getAnyString("requestNumber"),
            senderPhone = snapshot.getAnyString("senderPhone"),
            transactionReference = snapshot.getAnyString("transactionReference", "referenceNumber"),
            receiptStoragePath = snapshot.getAnyString("receiptImagePath", "receiptStoragePath", "receiptUrl"),
            subscriptionVersion = (snapshot.get("subscriptionVersion") as? Number)?.toLong(),
            updatedBy = snapshot.getAnyString("updatedBy", "updatedByAdminUid"),
            isFromCache = snapshot.metadata.isFromCache
        )
    }

    fun fromServerCallableMap(data: Map<*, *>): RawSubscriptionSnapshot {
        val rawStatus = (data["status"] ?: data["subscriptionStatus"])?.toString()?.trim()
        val normalized = normalizeStatus(rawStatus)
        val isActive = (data["isActive"] as? Boolean)
            ?: (data["isSubscribed"] as? Boolean)
            ?: (data["isValid"] as? Boolean)
            ?: (normalized == "active")
        val limit = (data["captainLimit"] as? Number)?.toInt()
            ?: (data["maxCaptains"] as? Number)?.toInt()
        val planName = (data["planName"] ?: data["packageName"])?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val planId = parsePlanIdString(
            rawPlanId = data["planId"],
            rawPlanKey = data["planKey"],
            rawPackageId = data["packageId"]
        )
        val price = (data["price"] as? Number)?.toInt()
            ?: (data["amount"] as? Number)?.toInt()
        val durationDays = (data["durationDays"] as? Number)?.toInt()
        val features = parseFeaturesList(data["features"])
        val startMs = parseDateMillis(data["activatedAt"])
            ?: parseDateMillis(data["startDate"])
            ?: parseDateMillis(data["subscriptionStartDate"])
        val approvedMs = parseDateMillis(data["approvedAt"]) ?: startMs
        val endMs = parseDateMillis(data["expiresAt"])
            ?: parseDateMillis(data["endDate"])
            ?: parseDateMillis(data["subscriptionEndDate"])
        val archivedMs = parseDateMillis(data["archivedAtMs"]) ?: parseDateMillis(data["archivedAt"])
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
            "pending_review" -> explicitPaymentStatus ?: "pending_verification"
            "rejected" -> explicitPaymentStatus ?: "rejected"
            "resubmission_required" -> explicitPaymentStatus ?: "resubmission_requested"
            "payment_failed", "pending_payment" -> explicitPaymentStatus ?: "failed"
            else -> explicitPaymentStatus ?: "unpaid"
        }
        return RawSubscriptionSnapshot(
            exists = rawStatus != "not_found",
            documentId = data["subscriptionId"]?.toString()?.takeIf { it.isNotBlank() },
            subscriptionId = data["subscriptionId"]?.toString()?.takeIf { it.isNotBlank() },
            merchantId = data["merchantId"]?.toString()?.takeIf { it.isNotBlank() },
            packageId = (data["packageId"] ?: data["planId"] ?: data["planKey"])?.toString()?.takeIf { it.isNotBlank() },
            planKey = (data["planKey"] ?: data["planId"] ?: data["packageId"])?.toString()?.takeIf { it.isNotBlank() },
            status = rawStatus,
            subscriptionStatus = rawStatus,
            paymentStatus = derivedPaymentStatus,
            isActive = isActive,
            isSubscribed = isActive,
            planId = planId,
            planName = planName,
            maxCaptains = limit,
            price = price,
            durationDays = durationDays,
            features = features,
            startDateMillis = startMs,
            approvedAtMillis = approvedMs,
            endDateMillis = endMs,
            archivedAtMillis = archivedMs,
            createdAtMillis = parseDateMillis(data["createdAt"]) ?: startMs,
            updatedAtMillis = serverTimeMs,
            subscriptionUpdatedAtMillis = serverTimeMs,
            reason = reason,
            rejectionReason = rejectionReason,
            resubmissionReason = resubmissionReason,
            paymentMethod = data["paymentMethod"]?.toString()?.takeIf { it.isNotBlank() },
            pendingRequestId = data["pendingRequestId"]?.toString()?.takeIf { it.isNotBlank() },
            requestNumber = data["requestNumber"]?.toString()?.takeIf { it.isNotBlank() },
            receiptStoragePath = (data["receiptImagePath"] ?: data["receiptStoragePath"] ?: data["receiptUrl"])?.toString()?.takeIf { it.isNotBlank() },
            subscriptionVersion = version,
            updatedBy = updatedBy,
            isFromCache = false
        )
    }

    /**
     * Reconciles canonical `subscriptions/{uid}` and any auto-ID subscription documents returned
     * by `subscriptions.whereEqualTo("merchantId", uid)`.
     * An expired or unpaid subscription MUST NEVER beat a currently active, paid, unexpired subscription.
     */
    fun selectEffectiveSubscriptionSnapshot(
        canonicalDoc: RawSubscriptionSnapshot,
        queryDocs: List<RawSubscriptionSnapshot>,
        nowMillis: Long = System.currentTimeMillis()
    ): RawSubscriptionSnapshot {
        val candidates = buildList {
            if (canonicalDoc.exists) add(canonicalDoc)
            queryDocs.filter { it.exists }.forEach { add(it) }
        }
        if (candidates.isEmpty()) return canonicalDoc

        val deduped = LinkedHashMap<String, RawSubscriptionSnapshot>()
        candidates.forEachIndexed { idx, snap ->
            val key = snap.documentId?.takeIf { it.isNotBlank() }
                ?: snap.subscriptionId?.takeIf { it.isNotBlank() }
                ?: "anon_$idx"
            val existing = deduped[key]
            if (existing == null ||
                (existing.isFromCache && !snap.isFromCache) ||
                (snap.subscriptionVersion ?: 0L) > (existing.subscriptionVersion ?: 0L) ||
                effectiveSnapshotRecency(snap) > effectiveSnapshotRecency(existing)
            ) {
                deduped[key] = snap
            }
        }

        val all = deduped.values.toList()
        if (all.size == 1) return all.first()

        // Active, paid, unexpired subscriptions ALWAYS take first priority over any expired/inactive doc
        return all.maxWithOrNull(
            compareBy<RawSubscriptionSnapshot>(
                { isSnapshotActiveAndValid(it, nowMillis) },
                { it.subscriptionVersion ?: 0L },
                { effectiveSnapshotRecency(it) },
                { it.endDateMillis ?: 0L },
                { !it.isFromCache }
            )
        ) ?: canonicalDoc
    }

    private fun effectiveSnapshotRecency(snap: RawSubscriptionSnapshot): Long {
        return maxOf(
            snap.subscriptionUpdatedAtMillis ?: 0L,
            snap.updatedAtMillis ?: 0L,
            snap.approvedAtMillis ?: 0L,
            snap.startDateMillis ?: 0L,
            snap.createdAtMillis ?: 0L
        )
    }

    private fun isSnapshotActiveAndValid(snap: RawSubscriptionSnapshot, nowMillis: Long): Boolean {
        val norm = normalizeStatus(snap.status ?: snap.subscriptionStatus ?: snap.planStatus)
        if (norm != "active") return false
        if (snap.isActive == false && snap.isSubscribed != true) return false
        val rawPay = snap.paymentStatus?.trim()?.takeIf { it.isNotBlank() }
        if (rawPay != null && normalizePaymentStatus(rawPay) in BLOCKED_PAYMENT_STATUSES) return false
        val end = snap.endDateMillis
        return end == null || nowMillis < end
    }

    /**
     * Parses a document from the `subscription_history` collection (`whereEqualTo("merchantId", uid)`).
     */
    fun parseSubscriptionHistorySnapshot(
        snapshot: DocumentSnapshot,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionHistoryItem {
        val data = snapshot.data ?: emptyMap<String, Any?>()
        return parseSubscriptionHistoryMap(snapshot.id, data, nowMillis)
    }

    fun parseSubscriptionHistoryMap(
        id: String,
        data: Map<*, *>,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionHistoryItem {
        val rawStatus = normalizeStatus(
            (data["status"] ?: data["subscriptionStatus"] ?: data["finalStatus"] ?: data["archiveReason"])?.toString()
        )
        val startDateMillis = parseDateMillis(data["startDate"])
            ?: parseDateMillis(data["activatedAt"])
            ?: parseDateMillis(data["subscriptionStartDate"])
            ?: parseDateMillis(data["createdAt"])
        val endDateMillis = parseDateMillis(data["endDate"])
            ?: parseDateMillis(data["expiresAt"])
            ?: parseDateMillis(data["subscriptionEndDate"])
        val archivedAtMillis = parseDateMillis(data["archivedAtMs"])
            ?: parseDateMillis(data["archivedAt"])
            ?: parseDateMillis(data["archived_at"])
        val createdAtMillis = parseDateMillis(data["createdAt"]) ?: startDateMillis
        val updatedAtMillis = parseDateMillis(data["updatedAt"]) ?: archivedAtMillis ?: endDateMillis
        val isDateExpired = endDateMillis != null && nowMillis >= endDateMillis
        val finalStatus = when {
            rawStatus == "active" && isDateExpired -> "expired"
            rawStatus == "unsubscribed" && archivedAtMillis != null -> "expired"
            else -> rawStatus
        }
        val planId = parsePlanIdString(
            rawPlanId = data["planId"],
            rawPlanKey = data["planKey"],
            rawPackageId = data["packageId"]
        ).orEmpty()
        val maxCaptains = (data["maxCaptains"] as? Number)?.toInt()
            ?: (data["captainLimit"] as? Number)?.toInt()
            ?: 0
        val price = (data["price"] as? Number)?.toInt()
            ?: (data["amount"] as? Number)?.toInt()
            ?: (data["expectedAmount"] as? Number)?.toInt()
            ?: 0
        val durationDays = (data["durationDays"] as? Number)?.toInt() ?: 0
        val planName = (data["planName"] ?: data["packageName"] ?: data["name"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: "باقة اشتراك"
        val paymentMethod = (data["paymentMethod"] ?: data["payment_method"])?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val rawPayStatus = data["paymentStatus"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val payStatus = normalizePaymentStatus(
            rawPayStatus ?: when (finalStatus) {
                "active" -> if (paymentMethod == "admin_manual") "admin_manual" else "paid"
                "pending_review", "pending" -> "pending_verification"
                "rejected" -> "rejected"
                "resubmission_required" -> "resubmission_requested"
                else -> "unpaid"
            }
        )
        return SubscriptionHistoryItem(
            id = id,
            subscriptionId = (data["subscriptionId"] ?: data["originalSubscriptionId"])?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: id,
            merchantId = (data["merchantId"] ?: data["uid"])?.toString()?.trim().orEmpty(),
            planId = planId,
            packageId = (data["packageId"] ?: data["planKey"] ?: data["planId"])?.toString()?.trim().orEmpty().ifBlank { planId },
            planName = planName,
            price = price,
            maxCaptains = maxCaptains,
            status = finalStatus,
            paymentStatus = payStatus,
            paymentMethod = paymentMethod,
            startDateMillis = startDateMillis,
            endDateMillis = endDateMillis,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            archivedAtMillis = archivedAtMillis,
            durationDays = durationDays,
            features = parseFeaturesList(data["features"]),
            reason = (data["archiveReason"] ?: data["rejectionReason"] ?: data["resubmissionReason"] ?: data["cancellationReason"] ?: data["suspensionReason"] ?: data["reason"])
                ?.toString()?.trim()?.takeIf { it.isNotBlank() },
            isCurrent = false
        )
    }

    /**
     * Sorts `subscription_history` items locally descending by `archivedAtMs` or `endDate`
     * without requiring a Firestore composite index.
     */
    fun sortArchivedSubscriptionHistory(items: List<SubscriptionHistoryItem>): List<SubscriptionHistoryItem> {
        return items.sortedWith(
            compareByDescending<SubscriptionHistoryItem> { it.archivedAtMillis ?: it.endDateMillis ?: it.updatedAtMillis ?: it.createdAtMillis ?: 0L }
                .thenByDescending { it.endDateMillis ?: 0L }
        )
    }

    /**
     * Builds the full Subscription History list from canonical + queried `subscriptions` docs
     * merged with all archived records from `subscription_history`.
     * Preserves ALL past periods without deleting any record.
     */
    fun buildSubscriptionHistory(
        canonicalDoc: RawSubscriptionSnapshot,
        queryDocs: List<RawSubscriptionSnapshot>,
        archivedHistoryDocs: List<SubscriptionHistoryItem> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<SubscriptionHistoryItem> {
        val rawAll = buildList {
            if (canonicalDoc.exists) add(canonicalDoc)
            queryDocs.filter { it.exists }.forEach { add(it) }
        }

        val deduped = LinkedHashMap<String, RawSubscriptionSnapshot>()
        rawAll.forEachIndexed { idx, snap ->
            val key = snap.documentId?.takeIf { it.isNotBlank() }
                ?: snap.subscriptionId?.takeIf { it.isNotBlank() }
                ?: "sub_$idx"
            val prev = deduped[key]
            if (prev == null ||
                (prev.isFromCache && !snap.isFromCache) ||
                effectiveSnapshotRecency(snap) >= effectiveSnapshotRecency(prev)
            ) {
                deduped[key] = snap
            }
        }

        val effective = selectEffectiveSubscriptionSnapshot(canonicalDoc, queryDocs, nowMillis)
        val effectiveKey = effective.documentId?.takeIf { it.isNotBlank() }
            ?: effective.subscriptionId?.takeIf { it.isNotBlank() }

        val fromSubscriptions = deduped.entries.map { (key, snap) ->
            val rawStatus = normalizeStatus(snap.status ?: snap.subscriptionStatus ?: snap.planStatus)
            val isDateExpired = snap.endDateMillis != null && nowMillis >= snap.endDateMillis
            val finalStatus = if (rawStatus == "active" && isDateExpired) "expired" else rawStatus
            val planId = snap.planId?.takeIf { it.isNotBlank() }
                ?: parsePlanIdString(
                    rawPlanKey = snap.planKey,
                    rawPackageId = snap.packageId
                ).orEmpty()
            val maxCaptains = snap.maxCaptains?.takeIf { it > 0 } ?: 0
            val planName = snap.planName?.takeIf { it.isNotBlank() } ?: "باقة اشتراك"
            val price = snap.price?.takeIf { it > 0 } ?: 0
            val payStatus = normalizePaymentStatus(
                snap.paymentStatus ?: when (finalStatus) {
                    "active" -> if (snap.paymentMethod == "admin_manual") "admin_manual" else "paid"
                    "pending_review", "pending" -> "pending_verification"
                    "rejected" -> "rejected"
                    "resubmission_required" -> "resubmission_requested"
                    else -> "unpaid"
                }
            )
            SubscriptionHistoryItem(
                id = key,
                subscriptionId = snap.subscriptionId ?: key,
                merchantId = snap.merchantId.orEmpty(),
                planId = planId,
                packageId = snap.packageId ?: snap.planKey ?: planId,
                planName = planName,
                price = price,
                maxCaptains = maxCaptains,
                status = finalStatus,
                paymentStatus = payStatus,
                paymentMethod = snap.paymentMethod,
                startDateMillis = snap.startDateMillis ?: snap.approvedAtMillis ?: snap.createdAtMillis,
                endDateMillis = snap.endDateMillis,
                createdAtMillis = snap.createdAtMillis ?: snap.startDateMillis,
                updatedAtMillis = snap.subscriptionUpdatedAtMillis ?: snap.updatedAtMillis,
                archivedAtMillis = snap.archivedAtMillis,
                durationDays = snap.durationDays ?: 0,
                features = snap.features,
                reason = snap.rejectionReason ?: snap.resubmissionReason ?: snap.reason,
                isCurrent = (key == effectiveKey) && isSnapshotActiveAndValid(snap, nowMillis)
            )
        }

        // Merge archived records from `subscription_history` without overwriting distinct past periods
        val merged = LinkedHashMap<String, SubscriptionHistoryItem>()
        fromSubscriptions.forEach { item ->
            merged["sub_${item.id}"] = item
        }
        archivedHistoryDocs.forEach { archived ->
            val duplicateOfLive = fromSubscriptions.any { live ->
                live.id == archived.id &&
                    live.startDateMillis == archived.startDateMillis &&
                    live.endDateMillis == archived.endDateMillis
            }
            if (!duplicateOfLive) {
                merged["hist_${archived.id}"] = archived.copy(isCurrent = false)
            }
        }

        return merged.values.sortedWith(
            compareByDescending<SubscriptionHistoryItem> { it.isCurrent }
                .thenByDescending { it.archivedAtMillis ?: it.endDateMillis ?: it.startDateMillis ?: it.createdAtMillis ?: it.updatedAtMillis ?: 0L }
                .thenByDescending { it.endDateMillis ?: 0L }
        )
    }

    fun parseManualRequestSnapshot(snapshot: DocumentSnapshot): ManualSubscriptionRequestRecord {
        val data = snapshot.data ?: emptyMap<String, Any?>()
        return parseManualRequestMap(snapshot.id, data)
    }

    fun parseManualRequestMap(id: String, data: Map<*, *>): ManualSubscriptionRequestRecord {
        val maxCaptains = (data["maxCaptains"] as? Number)?.toInt()
            ?: (data["captainLimit"] as? Number)?.toInt()
            ?: 0
        val rawPlanName = (data["planName"] ?: data["packageName"] ?: data["name"])?.toString()?.trim().orEmpty()
        val planId = parsePlanIdString(
            rawPlanId = data["planId"],
            rawPlanKey = data["planKey"],
            rawPackageId = data["packageId"]
        ).orEmpty()
        val resolvedName = rawPlanName.ifBlank { "باقة اشتراك" }
        val amount = (data["amount"] as? Number)?.toInt()
            ?: (data["expectedAmount"] as? Number)?.toInt()
            ?: (data["price"] as? Number)?.toInt()
            ?: 0
        val rawStatus = (data["status"] ?: data["requestStatus"])?.toString()?.trim().orEmpty()
        val normalizedStatus = when (rawStatus.lowercase(Locale.ROOT)) {
            "approved", "active", "accepted", "تمت الموافقة", "مقبول" -> "approved"
            "rejected", "declined", "مرفوض" -> "rejected"
            "resubmission_required", "resubmission_requested", "resubmit", "resubmit_required" -> "resubmission_required"
            "cancelled", "canceled" -> "cancelled"
            else -> "pending_review"
        }
        val rawPaymentStatus = data["paymentStatus"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val paymentStatus = normalizePaymentStatus(
            rawPaymentStatus ?: when (normalizedStatus) {
                "approved" -> "paid"
                "rejected" -> "rejected"
                "resubmission_required" -> "resubmission_requested"
                else -> "pending_verification"
            }
        )
        val receiptPath = (data["receiptImagePath"] ?: data["receiptStoragePath"] ?: data["receiptUrl"])
            ?.toString()?.trim().orEmpty()
        return ManualSubscriptionRequestRecord(
            requestId = (data["requestId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }) ?: id,
            merchantId = (data["merchantId"] ?: data["uid"])?.toString()?.trim().orEmpty(),
            planId = planId,
            packageId = (data["packageId"] ?: data["planKey"] ?: data["planId"])?.toString()?.trim().orEmpty().ifBlank { planId },
            planName = resolvedName,
            maxCaptains = maxCaptains,
            expectedAmount = amount,
            senderPhone = (data["senderPhone"] ?: data["phone"])?.toString()?.trim().orEmpty(),
            transactionReference = (data["transactionReference"] ?: data["referenceNumber"])?.toString()?.trim().orEmpty(),
            receiptStoragePath = receiptPath,
            status = normalizedStatus,
            paymentStatus = paymentStatus,
            requestNumber = data["requestNumber"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            rejectionReason = data["rejectionReason"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: data["adminNote"]?.toString()?.trim()?.takeIf { it.isNotBlank() && normalizedStatus == "rejected" },
            resubmissionReason = data["resubmissionReason"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: data["rejectionReason"]?.toString()?.trim()?.takeIf { it.isNotBlank() && normalizedStatus == "resubmission_required" },
            notes = data["notes"]?.toString()?.trim().orEmpty(),
            createdAtMillis = parseDateMillis(data["createdAt"]) ?: parseDateMillis(data["submittedAt"]),
            updatedAtMillis = parseDateMillis(data["updatedAt"]),
            reviewedAtMillis = parseDateMillis(data["reviewedAt"]) ?: parseDateMillis(data["approvedAt"]) ?: parseDateMillis(data["rejectedAt"])
        )
    }

    fun parsePaymentSnapshot(snapshot: DocumentSnapshot): MerchantPaymentRecord {
        val data = snapshot.data ?: emptyMap<String, Any?>()
        return parsePaymentMap(snapshot.id, data)
    }

    fun parsePaymentMap(id: String, data: Map<*, *>): MerchantPaymentRecord {
        val amount = (data["amount"] as? Number)?.toDouble()
            ?: (data["price"] as? Number)?.toDouble()
            ?: (data["paidAmount"] as? Number)?.toDouble()
            ?: data["amount"]?.toString()?.toDoubleOrNull()
            ?: 0.0
        val paymentId = (data["paymentId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }) ?: id
        val transactionId = (data["transactionId"] ?: data["operationId"] ?: data["paymentCode"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: paymentId
        val packageName = (data["packageName"] ?: data["planName"] ?: data["subscriptionPlanName"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val packageId = (data["packageId"] ?: data["planId"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val subscriptionId = data["subscriptionId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val method = (data["paymentMethod"] ?: data["method"])?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: "admin_manual"
        val rawPayStatus = (data["paymentStatus"] ?: data["status"])?.toString()?.trim()
        val normalizedPayStatus = normalizePaymentStatus(rawPayStatus ?: "paid")
        val refNum = (data["referenceNumber"] ?: data["transactionReference"] ?: data["reference"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val paymentDateMs = parseDateMillis(data["paymentDate"])
            ?: parseDateMillis(data["paidAt"])
            ?: parseDateMillis(data["createdAt"])
            ?: parseDateMillis(data["timestamp"])
        return MerchantPaymentRecord(
            paymentId = paymentId,
            transactionId = transactionId,
            merchantId = (data["merchantId"] ?: data["uid"])?.toString()?.trim().orEmpty(),
            subscriptionId = subscriptionId,
            packageId = packageId,
            packageName = packageName,
            amount = amount,
            currency = data["currency"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "EGP",
            paymentMethod = method,
            paymentStatus = normalizedPayStatus,
            referenceNumber = refNum,
            paymentDateMillis = paymentDateMs,
            createdAtMillis = parseDateMillis(data["createdAt"]) ?: paymentDateMs,
            notes = (data["notes"] ?: data["description"])?.toString()?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    fun isExpiringSoon(
        state: SubscriptionStatusState,
        nowMillis: Long = System.currentTimeMillis(),
        thresholdDays: Int = 3
    ): Boolean {
        if (!state.canRequestCaptain(nowMillis)) return false
        val end = state.endDateMillis ?: return false
        val remaining = end - nowMillis
        val thresholdMs = thresholdDays.toLong() * 24L * 60L * 60L * 1000L
        return remaining in 1L..thresholdMs
    }

    fun buildExpiringSoonNotification(
        state: SubscriptionStatusState,
        nowMillis: Long = System.currentTimeMillis(),
        thresholdDays: Int = 3
    ): SubscriptionTransitionEvent? {
        if (!isExpiringSoon(state, nowMillis, thresholdDays)) return null
        val remainingLabel = state.remainingTimeLabel(nowMillis)
        return SubscriptionTransitionEvent(
            title = "تنبيه قرب انتهاء الاشتراك ⏰",
            body = "سينتهي اشتراكك في ${state.planName} خلال $remainingLabel. يرجى التجديد لتجنب توقف طلب الكباتن.",
            isActivation = false
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
        val explicitPaymentRaw = (primary.paymentStatus ?: secondary.paymentStatus)?.trim()?.takeIf { it.isNotBlank() }
        val normalizedPaymentStatus = if (explicitPaymentRaw != null) {
            normalizePaymentStatus(explicitPaymentRaw)
        } else {
            val method = (primary.paymentMethod ?: secondary.paymentMethod)?.trim()?.lowercase(Locale.ROOT)
            when (chosenStatus) {
                "active" -> if (method == "admin_manual") "admin_manual" else "paid"
                "pending_review", "pending" -> "pending_verification"
                "rejected" -> "rejected"
                "resubmission_required" -> "resubmission_requested"
                "payment_failed", "pending_payment" -> "failed"
                else -> "unpaid"
            }
        }
        val endDateMillis = primary.endDateMillis ?: secondary.endDateMillis
        val startDateMillis = primary.startDateMillis ?: secondary.startDateMillis
        val approvedAtMillis = primary.approvedAtMillis ?: secondary.approvedAtMillis ?: startDateMillis
        val reason = (primary.reason ?: secondary.reason)?.trim()?.takeIf { it.isNotBlank() }
        val rejectionReason = (primary.rejectionReason ?: secondary.rejectionReason)?.trim()?.takeIf { it.isNotBlank() }
        val resubmissionReason = (primary.resubmissionReason ?: secondary.resubmissionReason ?: rejectionReason)?.trim()?.takeIf { it.isNotBlank() }
        val paymentMethod = (primary.paymentMethod ?: secondary.paymentMethod)?.trim()?.takeIf { it.isNotBlank() }
        val pendingRequestId = (primary.pendingRequestId ?: secondary.pendingRequestId)?.trim()?.takeIf { it.isNotBlank() }
        val requestNumber = (primary.requestNumber ?: secondary.requestNumber)?.trim()?.takeIf { it.isNotBlank() }
        val senderPhone = (primary.senderPhone ?: secondary.senderPhone)?.trim()?.takeIf { it.isNotBlank() }
        val transactionReference = (primary.transactionReference ?: secondary.transactionReference)?.trim()?.takeIf { it.isNotBlank() }
        val receiptStoragePath = (primary.receiptStoragePath ?: secondary.receiptStoragePath)?.trim()?.takeIf { it.isNotBlank() }
        val version = maxOf(primary.subscriptionVersion ?: 0L, secondary.subscriptionVersion ?: 0L)
        val updatedBy = primary.updatedBy ?: secondary.updatedBy

        val resolvedPlanId = parsePlanIdString(
            rawPlanId = primary.planId ?: secondary.planId,
            rawPlanKey = primary.planKey ?: secondary.planKey,
            rawPackageId = primary.packageId ?: secondary.packageId
        ).orEmpty()
        val resolvedPlanKey = (primary.planKey ?: secondary.planKey ?: resolvedPlanId).takeIf { it.isNotBlank() }
        val resolvedPackageId = (primary.packageId ?: secondary.packageId ?: resolvedPlanId).takeIf { it.isNotBlank() }
        val rawPlanName = primary.planName?.takeIf { it.isNotBlank() } ?: secondary.planName?.takeIf { it.isNotBlank() }
        val planName = rawPlanName ?: "باقة اشتراك"
        val planPrice = primary.price?.takeIf { it > 0 } ?: secondary.price?.takeIf { it > 0 } ?: 0
        val durationDays = primary.durationDays?.takeIf { it > 0 }
            ?: secondary.durationDays?.takeIf { it > 0 }
            ?: if (startDateMillis != null && endDateMillis != null && endDateMillis > startDateMillis) {
                ((endDateMillis - startDateMillis) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(1)
            } else 0
        val features = primary.features.ifEmpty { secondary.features }
        val rawMaxCaptains = primary.maxCaptains?.takeIf { it > 0 }
            ?: secondary.maxCaptains?.takeIf { it > 0 }
            ?: extractCaptainsFromPlanText(resolvedPlanId, resolvedPlanKey, resolvedPackageId, rawPlanName)
            ?: 0
        val isExplicitlyDeactivatedByFlag = primary.isActive == false && primary.isSubscribed != true
        val hasActiveSubscriptionProof = chosenStatus == "active" &&
            !isExplicitlyDeactivatedByFlag &&
            normalizedPaymentStatus !in BLOCKED_PAYMENT_STATUSES &&
            (planPrice > 0 || (endDateMillis != null && nowMillis < endDateMillis))
        val resolvedMaxCaptains = when {
            rawMaxCaptains > 0 -> rawMaxCaptains
            hasActiveSubscriptionProof -> 1
            else -> 0
        }
        val hasValidPlan = resolvedPlanId.isNotBlank() ||
            !rawPlanName.isNullOrBlank() ||
            resolvedMaxCaptains > 0 ||
            hasActiveSubscriptionProof

        // 1. Explicit admin suspension or inactive state
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
                planId = if (hasValidPlan) resolvedPlanId else "",
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "غير مشترك",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = normalizedPaymentStatus,
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 2. Explicit admin cancellation
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
                planId = "",
                planKey = resolvedPlanKey,
                planName = "ملغي",
                maxCaptains = 0,
                price = 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = normalizedPaymentStatus,
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 3. Pending review / pending verification
        if (chosenStatus == "pending_review" || chosenStatus == "pending" ||
            normalizedPaymentStatus in setOf("pending", "pending_verification", "pending_review")
        ) {
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
                status = if (chosenStatus == "pending") "pending" else "pending_review",
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = "طلب الاشتراك قيد المراجعة وفي انتظار التحقق وموافقة الإدارة. سيتم تفعيل الباقة تلقائياً فور تأكيد الدفع.",
                isChecking = false,
                planId = if (hasValidPlan) resolvedPlanId else "",
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "في انتظار مراجعة الإدارة",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = if (normalizedPaymentStatus in setOf("pending", "pending_verification", "pending_review")) {
                    normalizedPaymentStatus
                } else {
                    "pending_verification"
                },
                paymentMethod = paymentMethod,
                pendingRequestId = pendingRequestId,
                requestNumber = requestNumber,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 4. Admin rejection
        if (chosenStatus == "rejected" || normalizedPaymentStatus == "rejected") {
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
                planId = if (hasValidPlan) resolvedPlanId else "",
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "تم رفض الطلب",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = "rejected",
                paymentMethod = paymentMethod,
                requestNumber = requestNumber,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 5. Resubmission required / requested
        if (chosenStatus == "resubmission_required" ||
            normalizedPaymentStatus in setOf("resubmission_requested", "resubmission_required")
        ) {
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
                planId = if (hasValidPlan) resolvedPlanId else "",
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "مطلوب إعادة إرسال الإيصال",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = if (normalizedPaymentStatus == "resubmission_requested") "resubmission_requested" else "resubmission_required",
                paymentMethod = paymentMethod,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptStoragePath = receiptStoragePath,
                pendingRequestId = pendingRequestId,
                requestNumber = requestNumber,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 6. Expired status or passed endDate (MUST be checked BEFORE active)
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
                planId = resolvedPlanId,
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "اشتراك منتهي",
                maxCaptains = resolvedMaxCaptains,
                price = planPrice,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = normalizedPaymentStatus,
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 7. Unsubscribed (check BEFORE generic unpaid payment block so truly unsubscribed merchants stay "unsubscribed")
        val hasExplicitFailedOrPendingPayment = chosenStatus in listOf("payment_failed", "pending_payment") ||
            normalizedPaymentStatus == "failed"
        if (chosenStatus == "unsubscribed" && !hasExplicitFailedOrPendingPayment) {
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
                planId = "",
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

        // 8. Payment failed / unpaid (enforced EVEN IF status says "active" so unpaid subscriptions NEVER unlock captains)
        val isPaymentBlocked = hasExplicitFailedOrPendingPayment ||
            normalizedPaymentStatus in BLOCKED_PAYMENT_STATUSES
        if (isPaymentBlocked) {
            val isUnpaidOnly = normalizedPaymentStatus == "unpaid" && chosenStatus != "payment_failed"
            return SubscriptionStatusState(
                isChecking = false,
                isSubscribed = false,
                isExpired = false,
                isSuspended = false,
                isCancelled = false,
                isPending = false,
                isOfflineUnverified = !isOnline,
                isFromCache = chosenIsFromCache,
                status = when {
                    chosenStatus == "pending_payment" -> "pending_payment"
                    isUnpaidOnly -> "unpaid"
                    else -> "payment_failed"
                },
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                lastServerSyncMillis = if (!chosenIsFromCache && isOnline) nowMillis else null,
                message = if (isUnpaidOnly) {
                    "الاشتراك غير مدفوع حالياً: لا يمكن طلب كابتن إلا بعد تأكيد الدفع من السيرفر."
                } else {
                    "فشل عملية الدفع للباقة: لا يمكن طلب كابتن إلا بعد نجاح الدفع وتأكيد الاشتراك من السيرفر."
                },
                planId = if (hasValidPlan) resolvedPlanId else "",
                planKey = resolvedPlanKey,
                planName = if (hasValidPlan) planName else "غير مدفوع",
                maxCaptains = 0,
                price = if (hasValidPlan) planPrice else 0,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = normalizedPaymentStatus,
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // 8b. Missing valid plan fallback
        if (!hasValidPlan) {
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
                planId = "",
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

        // 9. Active status: verify server trust & connectivity
        val isExplicitActive = chosenStatus == "active" &&
            !isExplicitlyDeactivatedByFlag &&
            resolvedMaxCaptains > 0 &&
            normalizedPaymentStatus !in BLOCKED_PAYMENT_STATUSES
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
                planId = resolvedPlanId,
                planKey = resolvedPlanKey,
                planName = planName,
                maxCaptains = 0,
                price = planPrice,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = normalizedPaymentStatus,
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
            )
        }

        // Stale-cache & offline guard:
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
                planId = resolvedPlanId,
                planKey = resolvedPlanKey,
                planName = planName,
                maxCaptains = resolvedMaxCaptains,
                price = planPrice,
                durationDays = durationDays,
                features = features,
                activeCaptainsCount = activeCaptainsCount,
                availableCaptainSlots = 0,
                paymentStatus = if (paymentMethod == "admin_manual" || normalizedPaymentStatus == "admin_manual") "admin_manual" else "paid",
                paymentMethod = paymentMethod,
                subscriptionVersion = version,
                updatedBy = updatedBy,
                subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
                merchantId = primary.merchantId ?: secondary.merchantId,
                packageId = resolvedPackageId
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
            planId = resolvedPlanId,
            planKey = resolvedPlanKey,
            planName = planName,
            maxCaptains = resolvedMaxCaptains,
            price = planPrice,
            durationDays = durationDays,
            features = features,
            activeCaptainsCount = activeCaptainsCount,
            availableCaptainSlots = availableSlots,
            paymentStatus = if (paymentMethod == "admin_manual" || normalizedPaymentStatus == "admin_manual") "admin_manual" else "paid",
            paymentMethod = paymentMethod,
            subscriptionVersion = version,
            updatedBy = updatedBy,
            subscriptionId = primary.subscriptionId ?: secondary.subscriptionId ?: primary.documentId ?: secondary.documentId,
            merchantId = primary.merchantId ?: secondary.merchantId,
            packageId = resolvedPackageId
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
