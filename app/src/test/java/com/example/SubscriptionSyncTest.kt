package com.example

import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionStatusState
import com.example.db.SubscriptionSyncResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated verification covering real-time subscription sync, offline/cache guards,
 * admin activation/suspension/cancellation transitions, and service privilege blocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionSyncTest {

    private val nowMs = 1_760_000_000_000L
    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000L

    @Test
    fun `1_unsubscribed_merchant_is_blocked_from_requesting_captain`() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "unsubscribed"),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertEquals("unsubscribed", state.status)
        assertFalse(state.isSubscribed)
        assertFalse(state.canRequestCaptain(nowMs))
        assertTrue(state.message!!.contains("غير مشترك"))
    }

    @Test
    fun `2_admin_activation_enables_subscription_and_emits_activation_banner`() {
        val prev = SubscriptionStatusState(status = "unsubscribed", isSubscribed = false)
        val activated = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                subscriptionStatus = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 2,
                planName = "الباقة المتوسطة",
                maxCaptains = 4,
                price = 800,
                startDateMillis = nowMs - 1000L,
                endDateMillis = nowMs + thirtyDaysMs,
                updatedAtMillis = nowMs,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", updatedAtMillis = nowMs),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 1,
            hasVerifiedServerOnce = true
        )

        assertTrue(activated.isSubscribed)
        assertEquals("active", activated.status)
        assertEquals(2, activated.planId)
        assertEquals(4, activated.maxCaptains)
        assertEquals(3, activated.availableCaptainSlots)
        assertTrue(activated.canRequestCaptain(nowMs))

        val event = SubscriptionSyncResolver.detectTransitionNotification(
            previous = prev,
            current = activated,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(event)
        assertTrue(event!!.isActivation)
        assertTrue(event.body.contains("الباقة المتوسطة"))
    }

    @Test
    fun `3_admin_suspension_immediately_blocks_services_with_reason_and_notification`() {
        val activeState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 3,
                maxCaptains = 6,
                startDateMillis = nowMs - 5000L,
                endDateMillis = nowMs + thirtyDaysMs,
                updatedAtMillis = nowMs - 1000L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active"),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(activeState.canRequestCaptain(nowMs))

        val suspendedState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "suspended",
                subscriptionStatus = "suspended",
                isSubscribed = false,
                paymentStatus = "paid",
                planId = 3,
                maxCaptains = 6,
                endDateMillis = nowMs + thirtyDaysMs,
                updatedAtMillis = nowMs + 1000L,
                reason = "تأخر تسوية المستحقات",
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", updatedAtMillis = nowMs - 1000L),
            isOnline = true,
            nowMillis = nowMs + 1000L,
            hasVerifiedServerOnce = true
        )

        assertTrue(suspendedState.isSuspended)
        assertFalse(suspendedState.isSubscribed)
        assertEquals("suspended", suspendedState.status)
        assertEquals(0, suspendedState.maxCaptains)
        assertFalse(suspendedState.canRequestCaptain(nowMs + 1000L))
        assertTrue(suspendedState.message!!.contains("تأخر تسوية المستحقات"))
        assertTrue(suspendedState.message!!.contains("التواصل مع الإدارة"))

        val event = SubscriptionSyncResolver.detectTransitionNotification(
            previous = activeState,
            current = suspendedState,
            hadPreviousState = true,
            nowMillis = nowMs + 1000L
        )
        assertNotNull(event)
        assertFalse(event!!.isActivation)
        assertTrue(event.title.contains("إيقاف"))
    }

    @Test
    fun `4_admin_cancellation_blocks_services_and_clears_plan_quota`() {
        val activeState = SubscriptionStatusState(
            isSubscribed = true,
            status = "active",
            maxCaptains = 4,
            paymentStatus = "paid",
            endDateMillis = nowMs + thirtyDaysMs
        )
        val cancelledState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "cancelled",
                isSubscribed = false,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 0,
                updatedAtMillis = nowMs + 2000L,
                reason = "إلغاء بناءً على طلب الإدارة",
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "cancelled", updatedAtMillis = nowMs + 2000L),
            isOnline = true,
            nowMillis = nowMs + 2000L,
            hasVerifiedServerOnce = true
        )

        assertTrue(cancelledState.isCancelled)
        assertFalse(cancelledState.isSubscribed)
        assertEquals("cancelled", cancelledState.status)
        assertFalse(cancelledState.canRequestCaptain(nowMs + 2000L))
        assertTrue(cancelledState.message!!.contains("إلغاء اشتراكك"))

        val event = SubscriptionSyncResolver.detectTransitionNotification(
            previous = activeState,
            current = cancelledState,
            hadPreviousState = true,
            nowMillis = nowMs + 2000L
        )
        assertNotNull(event)
        assertFalse(event!!.isActivation)
        assertTrue(event.title.contains("إلغاء"))
    }

    @Test
    fun `5_admin_reactivation_restores_active_service_without_relogin`() {
        val suspendedPrev = SubscriptionStatusState(
            isSubscribed = false,
            isSuspended = true,
            status = "suspended",
            maxCaptains = 0
        )
        val reactivated = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 4,
                planName = "الباقة المتقدمة",
                maxCaptains = 8,
                price = 1600,
                startDateMillis = nowMs,
                endDateMillis = nowMs + thirtyDaysMs,
                updatedAtMillis = nowMs + 5000L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", updatedAtMillis = nowMs + 5000L),
            isOnline = true,
            nowMillis = nowMs + 5000L,
            hasVerifiedServerOnce = true
        )

        assertFalse(reactivated.isSuspended)
        assertFalse(reactivated.isCancelled)
        assertTrue(reactivated.isSubscribed)
        assertTrue(reactivated.canRequestCaptain(nowMs + 5000L))
        assertEquals(8, reactivated.maxCaptains)

        val event = SubscriptionSyncResolver.detectTransitionNotification(
            previous = suspendedPrev,
            current = reactivated,
            hadPreviousState = true,
            nowMillis = nowMs + 5000L
        )
        assertNotNull(event)
        assertTrue(event!!.isActivation)
    }

    @Test
    fun `6_cold_reopen_cached_active_doc_is_not_trusted_until_server_confirms`() {
        // Simulates reopening the app when local Firestore cache still holds "active"
        // before fresh server confirmation has arrived.
        val cachedOnReopen = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                isFromCache = true
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", isFromCache = true),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = false
        )

        assertTrue(cachedOnReopen.isOfflineUnverified)
        assertFalse(cachedOnReopen.isSubscribed)
        assertFalse(cachedOnReopen.canRequestCaptain(nowMs))

        // Live server snapshot immediately confirms it was suspended while offline
        val liveServerUpdate = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "suspended",
                isSubscribed = false,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "suspended", isFromCache = false),
            isOnline = true,
            nowMillis = nowMs + 100L,
            hasVerifiedServerOnce = true
        )

        assertFalse(liveServerUpdate.isOfflineUnverified)
        assertTrue(liveServerUpdate.isSuspended)
        assertFalse(liveServerUpdate.canRequestCaptain(nowMs + 100L))
    }

    @Test
    fun `7_offline_disconnect_revokes_stale_active_cache_and_blocks_requests`() {
        // Even if server was verified earlier, dropping offline prevents using stale cached active privileges
        val offlineState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 1,
                maxCaptains = 2,
                endDateMillis = nowMs + thirtyDaysMs,
                isFromCache = true
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", isFromCache = true),
            isOnline = false,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(offlineState.isOfflineUnverified)
        assertFalse(offlineState.isSubscribed)
        assertFalse(offlineState.canRequestCaptain(nowMs))
        assertTrue(offlineState.message!!.contains("انقطع الاتصال"))
    }

    @Test
    fun `8_dual_doc_conflict_prefers_newer_restrictive_admin_suspension_on_merchants`() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                updatedAtMillis = nowMs - 10_000L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(
                exists = true,
                subscriptionStatus = "suspended",
                isSubscribed = false,
                updatedAtMillis = nowMs,
                reason = "موقوف من لوحة الإدارة",
                isFromCache = false
            ),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(state.isSuspended)
        assertFalse(state.canRequestCaptain(nowMs))
        assertTrue(state.message!!.contains("موقوف من لوحة الإدارة"))
    }

    @Test
    fun `9_expired_endDate_blocks_even_if_status_string_still_says_active`() {
        val expiredState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 1,
                maxCaptains = 2,
                startDateMillis = nowMs - thirtyDaysMs - 1000L,
                endDateMillis = nowMs - 1L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(expiredState.isExpired)
        assertEquals("expired", expiredState.status)
        assertFalse(expiredState.canRequestCaptain(nowMs))
    }

    @Test
    fun `10_arabic_status_synonyms_and_unpaid_guard_work_deterministically`() {
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("نشطة"))
        assertEquals("suspended", SubscriptionSyncResolver.normalizeStatus("موقوفة"))
        assertEquals("cancelled", SubscriptionSyncResolver.normalizeStatus("ملغاة"))
        assertEquals("expired", SubscriptionSyncResolver.normalizeStatus("منتهية"))

        val unpaid = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "payment_failed",
                paymentStatus = "failed",
                isSubscribed = false,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertEquals("payment_failed", unpaid.status)
        assertFalse(unpaid.canRequestCaptain(nowMs))

        val initialNoHistoryEvent = SubscriptionSyncResolver.detectTransitionNotification(
            previous = SubscriptionStatusState(),
            current = unpaid,
            hadPreviousState = false
        )
        assertNull(initialNoHistoryEvent)
    }
}
