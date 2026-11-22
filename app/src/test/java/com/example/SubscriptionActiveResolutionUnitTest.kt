package com.example

import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionPlanOption
import com.example.db.SubscriptionSyncResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests verifying subscription state consistency between
 * Tab 1 (Packages & Subscription), Tab 2 (History & Requests), and Home (Request Captain).
 */
class SubscriptionActiveResolutionUnitTest {

    private val nowMs = 1_795_200_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun activePaidSubscriptionWithoutPlanMetadataStaysActiveAndCanRequestCaptain() {
        val rawActiveDoc = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_live_400",
            subscriptionId = "sub_live_400",
            merchantId = "merchant_uid_55",
            status = "active",
            paymentStatus = "paid",
            planId = null,
            planName = null,
            maxCaptains = null,
            price = 400,
            startDateMillis = nowMs,
            endDateMillis = nowMs + 30 * dayMs,
            isFromCache = false
        )

        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = rawActiveDoc,
            merchantDoc = RawSubscriptionSnapshot(
                exists = true,
                subscriptionStatus = "unsubscribed",
                isFromCache = false
            ),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertEquals("active", resolved.status)
        assertTrue(resolved.isSubscribed)
        assertTrue(resolved.canRequestCaptain(nowMs))
        assertEquals("paid", resolved.paymentStatus)
        assertEquals(400, resolved.price)
        assertEquals(30, resolved.durationDays)
        assertTrue(resolved.maxCaptains >= 1)
    }

    @Test
    fun enrichedSubscriptionIsConsistentAcrossActiveStateAndHistoryList() {
        val rawActiveDoc = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_live_400",
            subscriptionId = "sub_live_400",
            merchantId = "merchant_uid_55",
            status = "active",
            paymentStatus = "paid",
            planId = null,
            planName = null,
            maxCaptains = null,
            price = 400,
            startDateMillis = nowMs,
            endDateMillis = nowMs + 30 * dayMs,
            isFromCache = false
        )
        val livePackages = listOf(
            SubscriptionPlanOption(
                id = "plan_2_captains",
                name = "الباقة الأساسية",
                captainsLabel = "2 كابتن",
                maxCaptains = 2,
                priceEgp = 400,
                durationDays = 30,
                features = listOf("2 كابتن متزامن", "دعم فني")
            ),
            SubscriptionPlanOption(
                id = "plan_4_captains",
                name = "الباقة المتوسطة",
                captainsLabel = "4 كابتن",
                maxCaptains = 4,
                priceEgp = 800,
                durationDays = 30
            )
        )
        val payments = listOf(
            SubscriptionSyncResolver.parsePaymentMap(
                id = "pay_400",
                data = mapOf(
                    "merchantId" to "merchant_uid_55",
                    "subscriptionId" to "sub_live_400",
                    "amount" to 400,
                    "packageName" to "الباقة الأساسية",
                    "paymentMethod" to "vodafone_cash",
                    "paymentStatus" to "paid"
                )
            )
        )

        val enriched = SubscriptionSyncResolver.enrichSnapshotFromPackagesAndPayments(
            snapshot = rawActiveDoc,
            packages = livePackages,
            payments = payments
        )

        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = enriched,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        val history = SubscriptionSyncResolver.buildSubscriptionHistory(
            canonicalDoc = enriched,
            queryDocs = emptyList(),
            archivedHistoryDocs = emptyList(),
            nowMillis = nowMs
        )

        assertEquals("active", resolved.status)
        assertTrue(resolved.canRequestCaptain(nowMs))
        assertEquals("الباقة الأساسية", resolved.planName)
        assertEquals(2, resolved.maxCaptains)
        assertEquals("vodafone_cash", resolved.paymentMethod)
        assertEquals(listOf("2 كابتن متزامن", "دعم فني"), resolved.features)

        assertEquals(1, history.size)
        assertTrue(history.first().isCurrent)
        assertEquals("active", history.first().status)
        assertEquals("الباقة الأساسية", history.first().planName)
        assertEquals(2, history.first().maxCaptains)
        assertEquals("vodafone_cash", history.first().paymentMethod)
    }

    @Test
    fun unpaidOrExpiredOrMissingSubscriptionNeverBecomesActive() {
        // 1. Missing subscription + paid payment must stay non-existent
        val emptySnapshot = RawSubscriptionSnapshot(exists = false)
        val payments = listOf(
            SubscriptionSyncResolver.parsePaymentMap(
                id = "pay_unlinked",
                data = mapOf(
                    "merchantId" to "merchant_uid_55",
                    "amount" to 400,
                    "packageName" to "الباقة الأساسية",
                    "paymentStatus" to "paid"
                )
            )
        )
        val enrichedEmpty = SubscriptionSyncResolver.enrichSnapshotFromPackagesAndPayments(
            snapshot = emptySnapshot,
            packages = emptyList(),
            payments = payments
        )
        assertFalse(enrichedEmpty.exists)

        // 2. Active status with unpaid paymentStatus must be blocked
        val unpaidSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_unpaid",
            status = "active",
            paymentStatus = "unpaid",
            price = 400,
            startDateMillis = nowMs,
            endDateMillis = nowMs + 30 * dayMs,
            isFromCache = false
        )
        val resolvedUnpaid = SubscriptionSyncResolver.resolve(
            subscriptionDoc = unpaidSub,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertFalse(resolvedUnpaid.isSubscribed)
        assertFalse(resolvedUnpaid.canRequestCaptain(nowMs))

        // 3. Active status with expired endDate must become expired
        val expiredSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_expired",
            status = "active",
            paymentStatus = "paid",
            price = 400,
            startDateMillis = nowMs - 31 * dayMs,
            endDateMillis = nowMs - dayMs,
            isFromCache = false
        )
        val resolvedExpired = SubscriptionSyncResolver.resolve(
            subscriptionDoc = expiredSub,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertEquals("expired", resolvedExpired.status)
        assertFalse(resolvedExpired.canRequestCaptain(nowMs))
    }

    @Test
    fun extractsCaptainsFromPlanIdentifiersAndArabicLabels() {
        assertEquals(2, SubscriptionSyncResolver.extractCaptainsFromPlanText("plan_2_captains"))
        assertEquals(6, SubscriptionSyncResolver.extractCaptainsFromPlanText("باقة 6 كابتن"))
        assertEquals(12, SubscriptionSyncResolver.extractCaptainsFromPlanText("pkg_12_captains"))
    }

    @Test
    fun parsesUpdatedDashboardPackagesAndFiltersDisabledOnes() {
        val rawUpdatedPlans = listOf(
            mapOf(
                "planId" to "pkg_basic",
                "name" to "الباقة الأساسية المطورة",
                "captainsCount" to 3,
                "price_egp" to 450,
                "duration_days" to 30,
                "isActive" to true
            ),
            mapOf(
                "planId" to "pkg_disabled",
                "name" to "باقة قديمة",
                "maxCaptains" to 2,
                "price" to 300,
                "status" to "inactive"
            ),
            mapOf(
                "planId" to "pkg_pro",
                "planName" to "باقة برو",
                "max_captains" to 10,
                "monthlyPrice" to 1500,
                "days" to 30,
                "enabled" to true
            )
        )

        val parsed = com.example.db.MerchantSubscriptionRepository.parseConfiguredPlans(rawUpdatedPlans)
        assertEquals(2, parsed.size)
        assertEquals("pkg_basic", parsed[0].id)
        assertEquals("الباقة الأساسية المطورة", parsed[0].name)
        assertEquals(3, parsed[0].maxCaptains)
        assertEquals(450, parsed[0].priceEgp)
        assertEquals("pkg_pro", parsed[1].id)
        assertEquals(10, parsed[1].maxCaptains)
        assertEquals(1500, parsed[1].priceEgp)
    }
}
