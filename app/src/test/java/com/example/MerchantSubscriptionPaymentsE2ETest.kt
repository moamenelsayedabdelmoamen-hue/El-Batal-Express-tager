package com.example

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.db.ManualSubscriptionRequestPayload
import com.example.db.MerchantSubscriptionRepository
import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionStatusState
import com.example.db.SubscriptionSyncResolver
import com.example.storage.FirebaseStorageManager
import com.example.ui.subscription.MerchantPaymentsListContent
import com.example.ui.subscription.SubscriptionHistoryAndRequestsContent
import com.example.ui.subscription.SubscriptionStatusOverviewCard
import com.example.ui.theme.ElBatalExpressTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MerchantSubscriptionPaymentsE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val nowMs = System.currentTimeMillis()
    private val dayMs = 24L * 60L * 60L * 1000L

    // Scenario 1: Admin creates subscription from Dashboard (auto-ID doc with merchantId & packageId/packageName) -> appears immediately
    @Test
    fun test01_adminCreatedAutoIdSubscriptionAppearsImmediately() {
        val canonicalEmpty = RawSubscriptionSnapshot(exists = false, documentId = "merchant_uid_1")
        val adminAutoIdSub = SubscriptionSyncResolver.fromServerCallableMap(
            mapOf(
                "subscriptionId" to "sub_auto_9876",
                "merchantId" to "merchant_uid_1",
                "packageId" to "plan_6_captains",
                "packageName" to "الباقة المميزة",
                "status" to "active",
                "captainLimit" to 6,
                "amount" to 1200,
                "startDate" to nowMs - dayMs,
                "endDate" to nowMs + 29 * dayMs,
                "paymentStatus" to "admin_manual",
                "paymentMethod" to "admin_manual",
                "subscriptionVersion" to 1L
            )
        )

        val effective = SubscriptionSyncResolver.selectEffectiveSubscriptionSnapshot(
            canonicalDoc = canonicalEmpty,
            queryDocs = listOf(adminAutoIdSub),
            nowMillis = nowMs
        )
        assertTrue(effective.exists)
        assertEquals("sub_auto_9876", effective.subscriptionId)
        assertEquals("plan_6_captains", effective.planId)
        assertEquals("الباقة المميزة", effective.planName)
        assertEquals(6, effective.maxCaptains)
        assertEquals(1200, effective.price)

        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = effective,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 0,
            hasVerifiedServerOnce = true
        )

        assertTrue(resolved.canRequestCaptain(nowMs))
        assertEquals("active", resolved.status)
        assertEquals("admin_manual", resolved.paymentStatus)
        assertEquals("admin_manual", resolved.paymentMethod)
        assertEquals("sub_auto_9876", resolved.subscriptionId)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    SubscriptionStatusOverviewCard(subscriptionStatus = resolved, onRefresh = {})
                }
            }
        }
        composeTestRule.onNodeWithText("الباقة المميزة").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1200 ج.م").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("تسجيل يدوي من الإدارة").performScrollTo().assertIsDisplayed()
    }

    // Scenario 2: Admin records new payment in `payments` -> appears in Payments UI; unlinked payment does NOT activate subscription
    @Test
    fun test02_adminPaymentAppearsInPaymentsUiAndUnlinkedPaymentDoesNotActivate() {
        val linkedPayment = SubscriptionSyncResolver.parsePaymentMap(
            id = "pay_001",
            data = mapOf(
                "merchantId" to "merchant_uid_1",
                "transactionId" to "OP-2026-1001",
                "packageName" to "الباقة المتوسطة",
                "amount" to 800,
                "paymentMethod" to "vodafone_cash",
                "paymentStatus" to "paid",
                "referenceNumber" to "VF-884422",
                "paymentDate" to nowMs - 2 * dayMs
            )
        )
        val unlinkedPayment = SubscriptionSyncResolver.parsePaymentMap(
            id = "pay_002",
            data = mapOf(
                "merchantId" to "merchant_uid_1",
                "amount" to 250,
                "paymentMethod" to "admin_manual",
                "paymentStatus" to "paid",
                "paymentDate" to nowMs - dayMs
            )
        )

        assertEquals("OP-2026-1001", linkedPayment.transactionId)
        assertEquals("الباقة المتوسطة", linkedPayment.packageName)
        assertEquals(800.0, linkedPayment.amount, 0.01)
        assertEquals("paid", linkedPayment.paymentStatus)
        assertEquals("VF-884422", linkedPayment.referenceNumber)
        assertNull(unlinkedPayment.packageName)

        // Unlinked payment alone NEVER activates subscription when no active subscription doc exists
        val unsubscribed = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertFalse(unsubscribed.canRequestCaptain(nowMs))

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                MerchantPaymentsListContent(
                    payments = listOf(linkedPayment, unlinkedPayment),
                    isLoading = false,
                    errorMessage = null,
                    onRefresh = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("payments_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("1050 ج.م").assertIsDisplayed()
        composeTestRule.onNodeWithTag("merchant_payments_list")
            .performScrollToNode(hasTestTag("payment_record_card_pay_001"))
        composeTestRule.onNodeWithTag("payment_record_card_pay_001").assertIsDisplayed()
        composeTestRule.onNodeWithText("OP-2026-1001").assertExists()
        composeTestRule.onNodeWithText("VF-884422").assertExists()
        composeTestRule.onNodeWithTag("merchant_payments_list")
            .performScrollToNode(hasTestTag("payment_record_card_pay_002"))
        composeTestRule.onNodeWithTag("payment_record_card_pay_002").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("دفعة عامة (غير مرتبطة بباقة)")[0].assertExists()
    }

    // Scenario 3: Approved subscription grants captain requests within captainLimit and blocks above limit
    @Test
    fun test03_approvedSubscriptionAllowsCaptainsWithinLimitAndBlocksAboveLimit() {
        val activeSnap = RawSubscriptionSnapshot(
            exists = true,
            status = "active",
            isActive = true,
            isSubscribed = true,
            paymentStatus = "paid",
            planId = "plan_4_captains",
            planName = "الباقة المتوسطة",
            maxCaptains = 4,
            price = 800,
            startDateMillis = nowMs - dayMs,
            endDateMillis = nowMs + 29 * dayMs
        )

        val underLimit = SubscriptionSyncResolver.resolve(
            subscriptionDoc = activeSnap,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 3,
            hasVerifiedServerOnce = true
        )
        assertTrue(underLimit.canRequestCaptain(nowMs))
        assertEquals(1, underLimit.availableCaptainSlots)

        val atLimit = SubscriptionSyncResolver.resolve(
            subscriptionDoc = activeSnap,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 4,
            hasVerifiedServerOnce = true
        )
        assertTrue(atLimit.canRequestCaptain(nowMs))
        assertEquals(0, atLimit.availableCaptainSlots)
    }

    // Scenario 4: Unsubscribed merchant is blocked from requesting captains
    @Test
    fun test04_unsubscribedMerchantBlockedFromCaptainRequest() {
        val state = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = false),
            merchantDoc = RawSubscriptionSnapshot(exists = true, subscriptionStatus = "unsubscribed"),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertFalse(state.canRequestCaptain(nowMs))
        assertEquals("unsubscribed", state.status)
        assertEquals(0, state.maxCaptains)
    }

    // Scenario 5: Dynamic plan with features parsed from Firestore + valid Vodafone Cash request payload & Storage path
    @Test
    fun test05_dynamicPlansWithFeaturesAndManualVodafoneSubmissionContract() {
        val plans = MerchantSubscriptionRepository.parseConfiguredPlans(
            listOf(
                mapOf(
                    "packageId" to "plan_2_captains",
                    "packageName" to "الباقة الأساسية",
                    "captainLimit" to 2,
                    "price" to 450,
                    "durationDays" to 30,
                    "isActive" to true,
                    "features" to listOf("توصيل فوري", "دعم فني 24/7", "تتبع مباشر")
                ),
                mapOf(
                    "packageId" to "plan_4_captains",
                    "packageName" to "باقة موقوفة",
                    "captainLimit" to 4,
                    "price" to 800,
                    "durationDays" to 30,
                    "isActive" to false
                )
            )
        )
        assertEquals(1, plans.size)
        val plan = plans.first()
        assertEquals(450, plan.priceEgp)
        assertEquals(listOf("توصيل فوري", "دعم فني 24/7", "تتبع مباشر"), plan.features)

        val validationErr = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = plan,
            senderPhone = "01095783737",
            receiptLocalUri = "content://media/picker/0/receipt_99",
            currentSubscription = SubscriptionStatusState(status = "unsubscribed")
        )
        assertNull(validationErr)

        val storagePath = FirebaseStorageManager.buildSubscriptionReceiptStoragePath("m_uid_7", "subreq_7001")
        assertTrue(storagePath.startsWith("merchants/m_uid_7/subscription_receipts/"))

        val payloadMap = ManualSubscriptionRequestPayload(
            merchantId = "m_uid_7",
            planId = plan.id,
            senderPhone = "01095783737",
            transactionReference = "VF-100200",
            receiptImagePath = storagePath,
            idempotencyKey = "subreq_7001"
        ).toCallableMap()

        assertEquals("m_uid_7", payloadMap["merchantId"])
        assertEquals("plan_2_captains", payloadMap["planId"])
        assertEquals(storagePath, payloadMap["receiptImagePath"])
        assertEquals(storagePath, payloadMap["receiptStoragePath"])
        assertFalse(payloadMap.containsKey("expectedAmount"))
    }

    // Scenario 6: Submitted request appears with pending_review in requests tracking UI and cannot self-activate
    @Test
    fun test06_pendingRequestDisplaysInTrackingUiAndBlocksActivation() {
        val pendingReq = SubscriptionSyncResolver.parseManualRequestMap(
            id = "subreq_100",
            data = mapOf(
                "merchantId" to "m_uid_7",
                "packageId" to "plan_4_captains",
                "planName" to "الباقة المتوسطة",
                "captainLimit" to 4,
                "expectedAmount" to 800,
                "senderPhone" to "01011223344",
                "transactionReference" to "TX-5566",
                "status" to "pending",
                "createdAt" to nowMs
            )
        )
        assertEquals("pending_review", pendingReq.status)

        val pendingState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = true, status = "pending_review", planId = "plan_4_captains"),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertFalse(pendingState.canRequestCaptain(nowMs))

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionHistoryAndRequestsContent(
                    history = emptyList(),
                    requests = listOf(pendingReq),
                    onResubmitPlan = {},
                    onRefresh = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("manual_request_card_subreq_100").assertIsDisplayed()
        composeTestRule.onNodeWithText("قيد مراجعة الإدارة").assertIsDisplayed()
        composeTestRule.onNodeWithText("TX-5566").assertIsDisplayed()
    }

    // Scenario 7: Admin approves request -> subscription activates and triggers activation notification
    @Test
    fun test07_adminApprovalActivatesSubscriptionAndEmitsNotification() {
        val before = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(exists = true, status = "pending_review", planId = "plan_8_captains"),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        val after = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                paymentMethod = "vodafone_cash",
                planId = "plan_8_captains",
                planName = "الباقة المتقدمة",
                maxCaptains = 8,
                price = 1600,
                startDateMillis = nowMs,
                endDateMillis = nowMs + 30 * dayMs
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(after.canRequestCaptain(nowMs))
        val event = SubscriptionSyncResolver.detectTransitionNotification(before, after, hadPreviousState = true, nowMillis = nowMs)
        assertNotNull(event)
        assertTrue(event!!.isActivation)
        assertTrue(event.body.contains("الباقة المتقدمة"))
    }

    // Scenario 8: Admin rejects request -> reason shown in tracking UI + resubmission CTA + rejection notification
    @Test
    fun test08_adminRejectionDisplaysReasonAllowsResubmitAndEmitsNotification() {
        val reason = "إيصال التحويل غير مطابق للمبلغ المطلوب"
        val rejectedReq = SubscriptionSyncResolver.parseManualRequestMap(
            id = "subreq_rej_01",
            data = mapOf(
                "merchantId" to "m_uid_7",
                "planId" to 2,
                "planName" to "الباقة المتوسطة",
                "maxCaptains" to 4,
                "expectedAmount" to 800,
                "senderPhone" to "01011223344",
                "status" to "rejected",
                "rejectionReason" to reason,
                "createdAt" to nowMs
            )
        )
        assertEquals("rejected", rejectedReq.status)
        assertEquals(reason, rejectedReq.rejectionReason)

        var resubmitClickedPlan = ""
        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionHistoryAndRequestsContent(
                    history = emptyList(),
                    requests = listOf(rejectedReq),
                    onResubmitPlan = { resubmitClickedPlan = it },
                    onRefresh = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("request_reason_subreq_rej_01").assertIsDisplayed()
        composeTestRule.onNodeWithText(reason).assertIsDisplayed()
        composeTestRule.onNodeWithTag("resubmit_request_button_subreq_rej_01").performClick()
        assertEquals("2", resubmitClickedPlan)
    }

    // Scenario 9: Expired subscription blocks captain requests + expiring-soon triggers warning notification
    @Test
    fun test09_expiredSubscriptionBlocksCaptainsAndExpiringSoonWarnsMerchant() {
        val expiringSoonState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                planId = "plan_2_captains",
                planName = "الباقة الأساسية",
                maxCaptains = 2,
                startDateMillis = nowMs - 28 * dayMs,
                endDateMillis = nowMs + 2 * dayMs
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(SubscriptionSyncResolver.isExpiringSoon(expiringSoonState, nowMs))
        val expiringNotice = SubscriptionSyncResolver.buildExpiringSoonNotification(expiringSoonState, nowMs)
        assertNotNull(expiringNotice)
        assertTrue(expiringNotice!!.title.contains("قرب انتهاء الاشتراك"))

        val expiredState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                planId = "plan_2_captains",
                planName = "الباقة الأساسية",
                maxCaptains = 2,
                startDateMillis = nowMs - 31 * dayMs,
                endDateMillis = nowMs - dayMs
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(expiredState.isExpired)
        assertFalse(expiredState.canRequestCaptain(nowMs))
        assertTrue(expiredState.message!!.contains("تجديد الاشتراك"))

        val expiredEvent = SubscriptionSyncResolver.detectTransitionNotification(
            previous = expiringSoonState,
            current = expiredState,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(expiredEvent)
        assertTrue(expiredEvent!!.title.contains("انتهى الاشتراك"))
    }

    // Scenario 10: Renewal preserves old subscription record alongside new active subscription in history
    @Test
    fun test10_renewalPreservesOldSubscriptionHistoryAlongsideNewActivePlan() {
        val oldSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_2026_jan",
            subscriptionId = "sub_2026_jan",
            merchantId = "m_uid_7",
            status = "expired",
            planId = "plan_2_captains",
            planName = "الباقة الأساسية",
            maxCaptains = 2,
            price = 400,
            paymentMethod = "vodafone_cash",
            paymentStatus = "paid",
            startDateMillis = nowMs - 60 * dayMs,
            endDateMillis = nowMs - 30 * dayMs,
            updatedAtMillis = nowMs - 30 * dayMs
        )
        val renewedSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_2026_feb",
            subscriptionId = "sub_2026_feb",
            merchantId = "m_uid_7",
            status = "active",
            isActive = true,
            isSubscribed = true,
            planId = "plan_6_captains",
            planName = "الباقة المميزة",
            maxCaptains = 6,
            price = 1200,
            paymentMethod = "vodafone_cash",
            paymentStatus = "paid",
            startDateMillis = nowMs - dayMs,
            endDateMillis = nowMs + 29 * dayMs,
            updatedAtMillis = nowMs - dayMs
        )

        val effective = SubscriptionSyncResolver.selectEffectiveSubscriptionSnapshot(
            canonicalDoc = RawSubscriptionSnapshot(exists = false),
            queryDocs = listOf(oldSub, renewedSub),
            nowMillis = nowMs
        )
        assertEquals("sub_2026_feb", effective.subscriptionId)

        val history = SubscriptionSyncResolver.buildSubscriptionHistory(
            canonicalDoc = RawSubscriptionSnapshot(exists = false),
            queryDocs = listOf(oldSub, renewedSub),
            nowMillis = nowMs
        )
        assertEquals(2, history.size)
        assertEquals("sub_2026_feb", history[0].id)
        assertTrue(history[0].isCurrent)
        assertEquals("sub_2026_jan", history[1].id)
        assertFalse(history[1].isCurrent)
        assertEquals("expired", history[1].status)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionHistoryAndRequestsContent(
                    history = history,
                    requests = emptyList(),
                    onResubmitPlan = {},
                    onRefresh = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("subscription_history_list")
            .performScrollToNode(hasTestTag("subscription_history_card_sub_2026_feb"))
        composeTestRule.onNodeWithTag("subscription_history_card_sub_2026_feb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_history_list")
            .performScrollToNode(hasTestTag("subscription_history_card_sub_2026_jan"))
        composeTestRule.onNodeWithTag("subscription_history_card_sub_2026_jan").assertIsDisplayed()
    }

    // Scenario 11: Logout resets state cleanly and re-login restores exact subscription from server
    @Test
    fun test11_logoutClearsStateAndReloginRestoresExactSubscription() {
        val clearedState = SubscriptionStatusState(isChecking = false, isExpired = false)
        assertFalse(clearedState.canRequestCaptain(nowMs))
        assertEquals("unsubscribed", clearedState.status)

        val restoredFromServer = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                documentId = "sub_relogin_1",
                merchantId = "m_uid_7",
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                planId = "plan_4_captains",
                planName = "الباقة المتوسطة",
                maxCaptains = 4,
                price = 800,
                startDateMillis = nowMs - 5 * dayMs,
                endDateMillis = nowMs + 25 * dayMs,
                isFromCache = false
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(restoredFromServer.canRequestCaptain(nowMs))
        assertEquals("plan_4_captains", restoredFromServer.planId)
        assertEquals(4, restoredFromServer.maxCaptains)
    }

    // Scenario 12: Offline cache blocked -> on reconnect, Admin cancellation replaces old active state immediately
    @Test
    fun test12_offlineBlocksCachedActiveAndReconnectAppliesServerCancellationImmediately() {
        val cachedActive = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_live_1",
            status = "active",
            isActive = true,
            isSubscribed = true,
            planId = "plan_6_captains",
            maxCaptains = 6,
            endDateMillis = nowMs + 20 * dayMs,
            isFromCache = true
        )
        val offlineState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = cachedActive,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = false,
            nowMillis = nowMs,
            hasVerifiedServerOnce = false
        )
        assertFalse(offlineState.canRequestCaptain(nowMs))
        assertTrue(offlineState.isOfflineUnverified)

        // On reconnect, server returns cancelled (even without subscriptionVersion) -> stale active state is cleared
        val serverCancelled = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_live_1",
            status = "cancelled",
            isActive = false,
            isSubscribed = false,
            reason = "تم إلغاء الاشتراك من الإدارة",
            updatedAtMillis = nowMs,
            isFromCache = false
        )
        val reconnectedState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = serverCancelled,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(reconnectedState.isCancelled)
        assertFalse(reconnectedState.canRequestCaptain(nowMs))
        assertEquals(0, reconnectedState.maxCaptains)
    }
}
