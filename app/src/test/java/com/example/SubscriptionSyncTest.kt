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

    @Test
    fun `11_inactive_status_blocks_captain_requests_and_maps_clearly`() {
        assertEquals("inactive", SubscriptionSyncResolver.normalizeStatus("غير نشط"))
        assertEquals("inactive", SubscriptionSyncResolver.normalizeStatus("inactive"))

        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "inactive",
                isSubscribed = false,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                reason = "إيقاف إداري مؤقت",
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "inactive", isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(state.isSuspended)
        assertEquals("inactive", state.status)
        assertFalse(state.isSubscribed)
        assertFalse(state.canRequestCaptain(nowMs))
        assertTrue(state.message!!.contains("إيقاف إداري مؤقت"))
    }

    @Test
    fun `12_pending_subscription_status_maps_isPending_and_blocks_requests`() {
        assertEquals("pending", SubscriptionSyncResolver.normalizeStatus("قيد المراجعة"))

        val prev = SubscriptionStatusState(status = "unsubscribed", isSubscribed = false)
        val pendingState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "pending",
                isSubscribed = false,
                paymentStatus = "paid",
                planId = 1,
                maxCaptains = 2,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "pending", isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(pendingState.isPending)
        assertEquals("pending", pendingState.status)
        assertFalse(pendingState.isSuspended)
        assertFalse(pendingState.canRequestCaptain(nowMs))
        assertTrue(pendingState.message!!.contains("قيد المراجعة"))

        val event = SubscriptionSyncResolver.detectTransitionNotification(
            previous = prev,
            current = pendingState,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(event)
        assertTrue(event!!.title.contains("قيد المراجعة"))
    }

    @Test
    fun `13_plan_change_upgrade_2_to_6_emits_notification_and_updates_slots`() {
        val oldPlan = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 1,
                planName = "الباقة الأساسية",
                maxCaptains = 2,
                startDateMillis = nowMs - thirtyDaysMs / 2,
                endDateMillis = nowMs + thirtyDaysMs / 2,
                subscriptionVersion = 1L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", subscriptionVersion = 1L, isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 2,
            hasVerifiedServerOnce = true
        )
        assertEquals(2, oldPlan.maxCaptains)
        assertEquals(0, oldPlan.availableCaptainSlots)

        val upgradedPlan = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 3,
                planName = "الباقة المميزة",
                maxCaptains = 6,
                startDateMillis = nowMs,
                endDateMillis = nowMs + thirtyDaysMs,
                subscriptionVersion = 2L,
                updatedBy = "admin_001",
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", subscriptionVersion = 2L, isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 2,
            hasVerifiedServerOnce = true
        )

        assertEquals(6, upgradedPlan.maxCaptains)
        assertEquals(4, upgradedPlan.availableCaptainSlots)
        assertEquals(2L, upgradedPlan.subscriptionVersion)
        assertEquals("admin_001", upgradedPlan.updatedBy)

        val changeEvent = SubscriptionSyncResolver.detectTransitionNotification(
            previous = oldPlan,
            current = upgradedPlan,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(changeEvent)
        assertTrue(changeEvent!!.isActivation)
        assertTrue(changeEvent.title.contains("تغيير الباقة"))
        assertTrue(changeEvent.body.contains("6 كابتن"))
    }

    @Test
    fun `14_server_callable_map_parsing_and_remaining_duration_labels`() {
        val fiveDaysThreeHours = 5L * 24 * 60 * 60 * 1000L + 3L * 60 * 60 * 1000L
        val serverMap = mapOf(
            "status" to "active",
            "isActive" to true,
            "planId" to 2,
            "planName" to "الباقة المتوسطة",
            "captainLimit" to 4,
            "price" to 800,
            "activatedAt" to nowMs - 1000L,
            "expiresAt" to nowMs + fiveDaysThreeHours,
            "serverTimeMs" to nowMs,
            "subscriptionVersion" to 4L,
            "updatedBy" to "admin_uid"
        )
        val raw = SubscriptionSyncResolver.fromServerCallableMap(serverMap)
        assertTrue(raw.exists)
        assertFalse(raw.isFromCache)
        assertEquals(4, raw.maxCaptains)
        assertEquals(4L, raw.subscriptionVersion)

        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = raw,
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", subscriptionVersion = 4L, isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 1,
            hasVerifiedServerOnce = true
        )
        assertTrue(resolved.canRequestCaptain(nowMs))
        assertEquals(5, resolved.remainingDays(nowMs))
        assertEquals(3, resolved.remainingHours(nowMs))
        assertEquals("5 يوم و 3 ساعة", resolved.remainingTimeLabel(nowMs))

        val expired = SubscriptionStatusState(endDateMillis = nowMs - 1000L)
        assertEquals("منتهي", expired.remainingTimeLabel(nowMs))
    }

    @Test
    fun `15_subscriptionVersion_conflict_prefers_higher_version_over_older_snapshot`() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                subscriptionVersion = 5L,
                updatedAtMillis = nowMs,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(
                exists = true,
                subscriptionStatus = "suspended",
                isSubscribed = false,
                subscriptionVersion = 4L,
                updatedAtMillis = nowMs + 10_000L, // even if clock skewed
                isFromCache = false
            ),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(state.isSubscribed)
        assertEquals("active", state.status)
        assertEquals(5L, state.subscriptionVersion)
        assertTrue(state.canRequestCaptain(nowMs))
    }

    @Test
    fun `16_admin_activation_with_stale_isSubscribed_false_and_unpaid_paymentStatus_succeeds`() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                subscriptionStatus = "active",
                isActive = true,
                isSubscribed = false, // stale signup value
                paymentStatus = "unpaid", // stale value not cleared by dashboard
                planId = 2,
                planName = "الباقة المتوسطة",
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                subscriptionVersion = 2L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(
                exists = true,
                subscriptionStatus = "unsubscribed",
                isSubscribed = false,
                paymentStatus = "unpaid",
                subscriptionVersion = 1L,
                isFromCache = false
            ),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(state.isSubscribed)
        assertFalse(state.isSuspended)
        assertEquals("active", state.status)
        assertEquals("paid", state.paymentStatus)
        assertEquals(4, state.maxCaptains)
        assertTrue(state.canRequestCaptain(nowMs))
    }

    @Test
    fun `17_merchant_login_updatedAt_bump_does_not_override_active_subscription`() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 2,
                planName = "الباقة المتوسطة",
                maxCaptains = 4,
                endDateMillis = nowMs + thirtyDaysMs,
                subscriptionVersion = 2L,
                subscriptionUpdatedAtMillis = nowMs - 60_000L,
                updatedAtMillis = nowMs - 60_000L,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(
                exists = true,
                subscriptionStatus = "suspended", // stale before mirror
                isSubscribed = false,
                subscriptionVersion = 2L,
                lastLoginAtMillis = nowMs,
                updatedAtMillis = nowMs, // bumped when Ali logged in / synced FCM token
                isFromCache = false
            ),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(state.isSubscribed)
        assertFalse(state.isSuspended)
        assertEquals("active", state.status)
        assertTrue(state.canRequestCaptain(nowMs))
    }

    @Test
    fun `18_arabic_active_synonyms_and_unix_seconds_and_iso8601_dates_parse_correctly`() {
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("فعال"))
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("فعّال"))
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("مفعل"))
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("مفعّل"))
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("subscribed"))
        assertEquals("active", SubscriptionSyncResolver.normalizeStatus("enabled"))

        // Unix seconds (< 1e12) converted cleanly to milliseconds
        assertEquals(1_760_000_000_000L, SubscriptionSyncResolver.parseDateMillis(1_760_000_000L))
        assertEquals(1_760_000_000_000L, SubscriptionSyncResolver.parseDateMillis("1760000000"))
        assertEquals(1_760_000_000_000L, SubscriptionSyncResolver.parseDateMillis(mapOf("seconds" to 1_760_000_000L)))
        assertNotNull(SubscriptionSyncResolver.parseDateMillis("2026-12-18T17:00:00.000Z"))
        assertNotNull(SubscriptionSyncResolver.parseDateMillis("2026-12-18"))
    }

    @Test
    fun `19_initial_server_check_in_progress_shows_loading_instead_of_unsubscribed`() {
        val checking = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false, isFromCache = true),
            merchantDoc = RawSubscriptionSnapshot(exists = false, isFromCache = true),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = false,
            isInitialServerCheckInProgress = true
        )

        assertTrue(checking.isChecking)
        assertEquals("checking", checking.status)
        assertFalse(checking.isSuspended)
        assertFalse(checking.canRequestCaptain(nowMs))
    }

    @Test
    fun `20_server_verification_error_surfaces_error_state_not_inactive`() {
        val errorState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false, isFromCache = true),
            merchantDoc = RawSubscriptionSnapshot(exists = false, isFromCache = true),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = false,
            isInitialServerCheckInProgress = false,
            serverError = "تعذر التحقق من حالة الاشتراك مع السيرفر."
        )

        assertFalse(errorState.isChecking)
        assertEquals("error", errorState.status)
        assertNotNull(errorState.serverError)
        assertFalse(errorState.isSuspended)
        assertFalse(errorState.canRequestCaptain(nowMs))
    }

    @Test
    fun `21_full_ali_lifecycle_login_activate_suspend_reactivate_and_online_reconnect`() {
        // 1. Ali logs in before Admin activation -> unsubscribed
        val initial = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false, isFromCache = false),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "unsubscribed", isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertFalse(initial.canRequestCaptain(nowMs))
        assertEquals("unsubscribed", initial.status)

        // 2. Admin activates Ali's subscription -> immediately active without re-login
        val activeSubDoc = RawSubscriptionSnapshot(
            exists = true,
            status = "active",
            isActive = true,
            isSubscribed = true,
            paymentStatus = "paid",
            planId = 2,
            planName = "الباقة المتوسطة",
            maxCaptains = 4,
            price = 800,
            startDateMillis = nowMs,
            endDateMillis = nowMs + thirtyDaysMs,
            subscriptionVersion = 2L,
            subscriptionUpdatedAtMillis = nowMs,
            isFromCache = false
        )
        val active = SubscriptionSyncResolver.resolve(
            subscriptionDoc = activeSubDoc,
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", subscriptionVersion = 2L, isFromCache = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(active.canRequestCaptain(nowMs))
        assertEquals("الباقة المتوسطة", active.planName)
        assertEquals(4, active.maxCaptains)
        assertNull(active.serverError)
        assertNull(active.message)

        // 3. Offline disconnect -> revokes cached privilege; reconnect -> restored from server
        val offline = SubscriptionSyncResolver.resolve(
            subscriptionDoc = activeSubDoc.copy(isFromCache = true),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", isFromCache = true),
            isOnline = false,
            nowMillis = nowMs + 5000L,
            hasVerifiedServerOnce = true
        )
        assertFalse(offline.canRequestCaptain(nowMs + 5000L))
        assertTrue(offline.isOfflineUnverified)

        val reconnected = SubscriptionSyncResolver.resolve(
            subscriptionDoc = activeSubDoc,
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "active", subscriptionVersion = 2L, isFromCache = false),
            isOnline = true,
            nowMillis = nowMs + 10_000L,
            hasVerifiedServerOnce = true
        )
        assertTrue(reconnected.canRequestCaptain(nowMs + 10_000L))
        assertFalse(reconnected.isOfflineUnverified)
    }

    @Test
    fun `22_endpoint_not_found_detection_distinguishes_undeployed_function_from_application_not_found`() {
        assertTrue(
            com.example.db.MerchantSubscriptionRepository.isEndpointNotFoundError(
                code = com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
                rawMessage = "NOT_FOUND",
                details = null
            )
        )
        assertTrue(
            com.example.db.MerchantSubscriptionRepository.isEndpointNotFoundError(
                code = com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
                rawMessage = null,
                details = null
            )
        )
        assertFalse(
            com.example.db.MerchantSubscriptionRepository.isEndpointNotFoundError(
                code = com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
                rawMessage = "تعذر العثور على بيانات حساب التاجر.",
                details = mapOf("reasonCode" to "MERCHANT_NOT_FOUND")
            )
        )
        assertFalse(
            com.example.db.MerchantSubscriptionRepository.isEndpointNotFoundError(
                code = com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                rawMessage = "NOT_FOUND",
                details = null
            )
        )
    }

    @Test
    fun `23_request_captains_error_mapping_never_leaks_raw_NOT_FOUND_and_returns_accurate_arabic_messages`() {
        val undeployedMsg = com.example.db.MerchantSubscriptionRepository.mapRequestCaptainsError(
            code = com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
            rawMessage = "NOT_FOUND",
            details = null,
            endpointUnavailableOnServer = true
        )
        assertFalse(undeployedMsg.equals("NOT_FOUND", ignoreCase = true))
        assertTrue(undeployedMsg.contains("requestCaptains"))
        assertTrue(undeployedMsg.contains("us-central1"))

        val merchantMissingMsg = com.example.db.MerchantSubscriptionRepository.mapRequestCaptainsError(
            code = com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
            rawMessage = "تعذر العثور على بيانات حساب التاجر.",
            details = mapOf("reasonCode" to "MERCHANT_NOT_FOUND")
        )
        assertEquals("تعذر العثور على بيانات حساب التاجر.", merchantMissingMsg)

        val subMissingMsg = com.example.db.MerchantSubscriptionRepository.mapRequestCaptainsError(
            code = com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            rawMessage = "لم يتم العثور على اشتراك التاجر.",
            details = mapOf("reasonCode" to "SUBSCRIPTION_NOT_FOUND")
        )
        assertEquals("لم يتم العثور على اشتراك التاجر.", subMissingMsg)

        val addressMissingMsg = com.example.db.MerchantSubscriptionRepository.mapRequestCaptainsError(
            code = com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            rawMessage = "بيانات عنوان العميل غير مكتملة.",
            details = mapOf("reasonCode" to "MISSING_CUSTOMER_ADDRESS")
        )
        assertTrue(addressMissingMsg.contains("عنوان العميل"))
    }
}

