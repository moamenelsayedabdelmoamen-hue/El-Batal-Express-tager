package com.example

import com.example.db.ManualSubscriptionRequestPayload
import com.example.db.MerchantSubscriptionRepository
import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionPlanOption
import com.example.db.SubscriptionSyncResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests covering Scenarios 1-6, 9, and 10 of real-time package synchronization
 * and server-authoritative subscription request payload contracts.
 */
class RealtimePackageSyncScenariosTest {

    @Test
    fun scenario1_addingNewPackageAppearsImmediatelyWithDynamicIdAndCaptains() {
        val initial = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_basic" to mapOf(
                    "planId" to "pkg_basic",
                    "name" to "الباقة الأساسية",
                    "maxCaptains" to 2,
                    "price" to 400,
                    "durationDays" to 30,
                    "isActive" to true
                )
            )
        )
        val afterAdd = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_basic" to mapOf(
                    "planId" to "pkg_basic",
                    "name" to "الباقة الأساسية",
                    "maxCaptains" to 2,
                    "price" to 400,
                    "durationDays" to 30,
                    "isActive" to true
                ),
                "pkg_vip_15" to mapOf(
                    "planId" to "pkg_vip_15",
                    "name" to "باقة المؤسسات",
                    "maxCaptains" to 15,
                    "price" to 2500,
                    "durationDays" to 30,
                    "isActive" to true
                )
            )
        )

        assertEquals(1, initial.size)
        assertEquals(2, afterAdd.size)
        val vip = afterAdd.first { it.id == "pkg_vip_15" }
        assertEquals("باقة المؤسسات", vip.name)
        assertEquals(15, vip.maxCaptains)
        assertEquals("15 كابتن", vip.captainsLabel)
        assertEquals(2500, vip.priceEgp)
    }

    @Test
    fun scenario2and3_editingPriceAndCaptainsUpdatesPlanAndEnrichedActiveLimit() {
        val updatedPackages = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_basic" to mapOf(
                    "planId" to "pkg_basic",
                    "name" to "الباقة الأساسية",
                    "maxCaptains" to 5,
                    "price" to 550,
                    "durationDays" to 30,
                    "isActive" to true
                )
            )
        )

        val pkg = updatedPackages.single()
        assertEquals(550, pkg.priceEgp)
        assertEquals(5, pkg.maxCaptains)
        assertEquals("5 كابتن", pkg.captainsLabel)

        // When subscription doc references pkg_basic without inline maxCaptains, enrichment picks up updated limit=5
        val nowMs = 1_795_200_000_000L
        val subDoc = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_1",
            status = "active",
            paymentStatus = "paid",
            planId = "pkg_basic",
            price = 550,
            startDateMillis = nowMs,
            endDateMillis = nowMs + 30L * 86_400_000L,
            isFromCache = false
        )
        val enriched = SubscriptionSyncResolver.enrichSnapshotFromPackagesAndPayments(
            snapshot = subDoc,
            packages = updatedPackages,
            payments = emptyList()
        )
        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = enriched,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertEquals(5, resolved.maxCaptains)
        assertEquals(550, resolved.price)
    }

    @Test
    fun scenario4and5_disablingRemovesPackageAndReEnablingRestoresItWithoutResurrectingStaleFallback() {
        val activeBefore = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_basic" to mapOf(
                    "planId" to "pkg_basic",
                    "name" to "الباقة الأساسية",
                    "maxCaptains" to 2,
                    "price" to 400,
                    "isActive" to true
                )
            )
        )
        val staleLegacyFallback = listOf(
            SubscriptionPlanOption(
                id = "pkg_basic",
                name = "باقة قديمة من settings",
                captainsLabel = "2 كابتن",
                maxCaptains = 2,
                priceEgp = 350
            )
        )

        // Active in subscription_packages -> authoritative
        val step1 = MerchantSubscriptionRepository.resolveEffectivePackages(
            collectionPackages = activeBefore,
            hasAuthoritativeCollectionPackages = true,
            manualConfigPlans = staleLegacyFallback,
            manualConfigUpdatedAtMs = 1000L,
            subscriptionConfigPlans = emptyList(),
            subscriptionConfigUpdatedAtMs = 0L
        )
        assertEquals(1, step1.size)
        assertEquals(400, step1.single().priceEgp)

        // Scenario 4: Admin disables the package in subscription_packages -> list becomes empty (does NOT resurrect stale 350 fallback!)
        val disabledParsed = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_basic" to mapOf(
                    "planId" to "pkg_basic",
                    "name" to "الباقة الأساسية",
                    "maxCaptains" to 2,
                    "price" to 400,
                    "isActive" to false
                )
            )
        )
        assertTrue(disabledParsed.isEmpty())

        val step2 = MerchantSubscriptionRepository.resolveEffectivePackages(
            collectionPackages = disabledParsed,
            hasAuthoritativeCollectionPackages = true,
            manualConfigPlans = staleLegacyFallback,
            manualConfigUpdatedAtMs = 1000L,
            subscriptionConfigPlans = emptyList(),
            subscriptionConfigUpdatedAtMs = 0L
        )
        assertTrue(step2.isEmpty())

        // Scenario 5: Admin re-enables the package -> reappears immediately
        val step3 = MerchantSubscriptionRepository.resolveEffectivePackages(
            collectionPackages = activeBefore,
            hasAuthoritativeCollectionPackages = true,
            manualConfigPlans = staleLegacyFallback,
            manualConfigUpdatedAtMs = 1000L,
            subscriptionConfigPlans = emptyList(),
            subscriptionConfigUpdatedAtMs = 0L
        )
        assertEquals(1, step3.size)
        assertEquals("pkg_basic", step3.single().id)
    }

    @Test
    fun scenario6_deletedOrHiddenPackageCannotBeMatchedForSubscription() {
        val activePackages = MerchantSubscriptionRepository.parseSubscriptionPackages(
            listOf(
                "pkg_mid" to mapOf(
                    "planId" to "pkg_mid",
                    "name" to "الباقة المتوسطة",
                    "maxCaptains" to 4,
                    "price" to 800,
                    "isActive" to true
                )
            )
        )
        val deletedPlanId = "pkg_deleted"
        val matched = activePackages.firstOrNull {
            SubscriptionSyncResolver.planIdsMatch(it.id, deletedPlanId) ||
                SubscriptionSyncResolver.planIdsMatch(it.planKey, deletedPlanId) ||
                SubscriptionSyncResolver.planIdsMatch(it.packageId, deletedPlanId)
        }
        assertNull(matched)
    }

    @Test
    fun scenario9_threeSourceConflictResolverAlwaysPrefersNewerTimestampAndNeverOverwritesNewerWithOlder() {
        val olderManualPlan = listOf(
            SubscriptionPlanOption(
                id = "pkg_basic",
                name = "قديم",
                captainsLabel = "2 كابتن",
                maxCaptains = 2,
                priceEgp = 400
            )
        )
        val newerSubConfigPlan = listOf(
            SubscriptionPlanOption(
                id = "pkg_basic",
                name = "محدّث",
                captainsLabel = "3 كابتن",
                maxCaptains = 3,
                priceEgp = 500
            )
        )

        // When subscription_packages is unavailable (hasAuthoritativeCollectionPackages = false),
        // newer settings/subscription_config (ts=2000) beats older settings/manual_subscription_config (ts=1000)
        val pickSubConfig = MerchantSubscriptionRepository.resolveEffectivePackages(
            collectionPackages = emptyList(),
            hasAuthoritativeCollectionPackages = false,
            manualConfigPlans = olderManualPlan,
            manualConfigUpdatedAtMs = 1000L,
            subscriptionConfigPlans = newerSubConfigPlan,
            subscriptionConfigUpdatedAtMs = 2000L
        )
        assertEquals(500, pickSubConfig.single().priceEgp)
        assertEquals(3, pickSubConfig.single().maxCaptains)

        // When manual_subscription_config is later updated (ts=3000 > 2000), manual_subscription_config wins
        val newestManualPlan = listOf(
            SubscriptionPlanOption(
                id = "pkg_basic",
                name = "الأحدث",
                captainsLabel = "4 كابتن",
                maxCaptains = 4,
                priceEgp = 600
            )
        )
        val pickManual = MerchantSubscriptionRepository.resolveEffectivePackages(
            collectionPackages = emptyList(),
            hasAuthoritativeCollectionPackages = false,
            manualConfigPlans = newestManualPlan,
            manualConfigUpdatedAtMs = 3000L,
            subscriptionConfigPlans = newerSubConfigPlan,
            subscriptionConfigUpdatedAtMs = 2000L
        )
        assertEquals(600, pickManual.single().priceEgp)
        assertEquals(4, pickManual.single().maxCaptains)
    }

    @Test
    fun scenario10_clientRequestPayloadNeverDictatesPriceOrCaptainsAndTrustsServerResponse() {
        val payload = ManualSubscriptionRequestPayload(
            merchantId = "merchant_uid_1",
            planId = "pkg_basic",
            senderPhone = "01012345678",
            transactionReference = "VF-998877",
            receiptImagePath = "merchants/merchant_uid_1/subscription_receipts/subreq_1.jpg",
            idempotencyKey = "subreq_1"
        )
        val map = payload.toCallableMap()

        // Client MUST NOT send price, amount, expectedAmount, maxCaptains, or captainLimit
        assertFalse(map.containsKey("price"))
        assertFalse(map.containsKey("amount"))
        assertFalse(map.containsKey("expectedAmount"))
        assertFalse(map.containsKey("maxCaptains"))
        assertFalse(map.containsKey("captainLimit"))
        assertEquals("pkg_basic", map["planId"])

        // Server response overrides client fallbacks with authoritative price & captain limit
        val parsed = MerchantSubscriptionRepository.parseManualSubscriptionResponse(
            data = mapOf(
                "requestId" to "subreq_1",
                "requestNumber" to "REQ-2026-001",
                "planId" to "pkg_basic",
                "planName" to "الباقة الأساسية (من السيرفر)",
                "amount" to 650,
                "maxCaptains" to 5,
                "durationDays" to 30,
                "status" to "pending_review",
                "paymentStatus" to "pending_verification"
            ),
            payload = payload,
            fallbackPlanName = "سعر قديم بالكاش",
            fallbackMaxCaptains = 2,
            fallbackDurationDays = 30
        )
        assertEquals(650, parsed.expectedAmount)
        assertEquals(5, parsed.maxCaptains)
        assertEquals("الباقة الأساسية (من السيرفر)", parsed.planName)
    }
}
