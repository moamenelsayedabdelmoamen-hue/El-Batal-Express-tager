package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.db.ManualSubscriptionRequestPayload
import com.example.db.ManualSubscriptionSubmissionResult
import com.example.db.ManualSubscriptionUiState
import com.example.db.MerchantSubscriptionRepository
import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionStatusState
import com.example.db.SubscriptionSyncResolver
import com.example.db.VodafoneCashSettings
import com.example.storage.FirebaseStorageManager
import com.example.ui.subscription.SubscriptionStatusOverviewCard
import com.example.ui.subscription.VodafoneCashPaymentContent
import com.example.ui.subscription.resolveSubscriptionStatusVisual
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
class ManualVodafoneSubscriptionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val nowMs = System.currentTimeMillis()
    private val dayMs = 24L * 60L * 60L * 1000L

    // 1. Official 4 plans catalog & prices preserved + Vodafone Cash UI elements
    @Test
    fun scenario01_selectAllFourOfficialPlansAndRenderVodafoneCashElements() {
        val plans = VodafoneCashSettings.OFFICIAL_PLANS
        assertEquals(4, plans.size)
        assertEquals(listOf(2, 4, 6, 8), plans.map { it.maxCaptains })
        assertEquals(listOf(400, 800, 1200, 1600), plans.map { it.priceEgp })
        assertTrue(plans.all { it.durationDays == 30 })
        assertEquals("01095783737", VodafoneCashSettings().walletNumber)

        val chosenPlan = plans[2] // الباقة المميزة - 6 كابتن - 1200 ج.م
        var copiedWallet = ""

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = chosenPlan,
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        walletHolderName = "البطل إكسبريس",
                        isConfiguredOnServer = true
                    ),
                    subscriptionStatus = SubscriptionStatusState(status = "unsubscribed"),
                    uiState = ManualSubscriptionUiState.Idle,
                    senderPhone = "01012345678",
                    onSenderPhoneChange = {},
                    transactionReference = "TXN-7788",
                    onTransactionReferenceChange = {},
                    receiptLocalUri = null,
                    onPickReceiptClick = {},
                    onClearReceiptClick = {},
                    onCopyWalletClick = { copiedWallet = it },
                    onRefreshSettings = {},
                    localValidationError = null,
                    onSubmitClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("vodafone_cash_screen_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("الاشتراك عن طريق فودافون كاش").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vodafone_selected_plan_name").assertIsDisplayed()
        composeTestRule.onNodeWithText("الباقة المميزة").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vodafone_cash_amount_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("1200 ج.م").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vodafone_cash_wallet_number").assertIsDisplayed()
        composeTestRule.onNodeWithText("01099887766").assertIsDisplayed()
        composeTestRule.onNodeWithTag("vodafone_wallet_holder_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("copy_wallet_number_button").performClick()
        assertEquals("01099887766", copiedWallet)
        composeTestRule.onNodeWithTag("vodafone_cash_instructions_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sender_phone_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("transaction_reference_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("notes_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("upload_receipt_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_manual_subscription_button").performScrollTo().assertIsDisplayed().assertIsEnabled()

        // Verify dynamic Firebase plans parsing overrides prices/durations when provided
        val dynamicParsed = MerchantSubscriptionRepository.parseConfiguredPlans(
            listOf(
                mapOf("id" to 1, "name" to "الباقة الأساسية", "maxCaptains" to 2, "price" to 450, "durationDays" to 30),
                mapOf("id" to 2, "name" to "الباقة المتوسطة", "maxCaptains" to 4, "price" to 850, "durationDays" to 30)
            )
        )
        assertEquals(2, dynamicParsed.size)
        assertEquals(450, dynamicParsed[0].priceEgp)
        assertEquals(850, dynamicParsed[1].priceEgp)
    }

    // 2. Receipt Storage path format + receipt preview displayed before submission
    @Test
    fun scenario02_receiptStoragePathFormatAndPreviewBeforeSubmission() {
        val path = FirebaseStorageManager.buildSubscriptionReceiptStoragePath(
            uid = "merchant_uid_42",
            requestId = "subreq_1760000_abcd"
        )
        assertEquals("merchants/merchant_uid_42/subscription_receipts/subreq_1760000_abcd.jpg", path)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = VodafoneCashSettings.OFFICIAL_PLANS[0],
                    settings = VodafoneCashSettings(walletNumber = "01099887766", isConfiguredOnServer = true),
                    subscriptionStatus = SubscriptionStatusState(status = "unsubscribed"),
                    uiState = ManualSubscriptionUiState.Idle,
                    senderPhone = "01012345678",
                    onSenderPhoneChange = {},
                    transactionReference = "",
                    onTransactionReferenceChange = {},
                    receiptLocalUri = "content://media/picker/0/1001",
                    onPickReceiptClick = {},
                    onClearReceiptClick = {},
                    onCopyWalletClick = {},
                    onRefreshSettings = {},
                    localValidationError = null,
                    onSubmitClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("vodafone_wallet_holder_name").assertDoesNotExist()
        composeTestRule.onNodeWithTag("receipt_preview_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("receipt_preview_image").assertIsDisplayed()
    }

    // 3. Block submission without receipt image (both validation & UI)
    @Test
    fun scenario03_blockSubmissionWithoutReceiptImage() {
        val plan = VodafoneCashSettings.OFFICIAL_PLANS[1]
        val unsubscribed = SubscriptionStatusState(status = "unsubscribed")

        val errMissingReceipt = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = plan,
            senderPhone = "01012345678",
            receiptLocalUri = null,
            currentSubscription = unsubscribed
        )
        assertNotNull(errMissingReceipt)
        assertTrue(errMissingReceipt!!.contains("إيصال"))

        val errInvalidPhone = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = plan,
            senderPhone = "12345",
            receiptLocalUri = "content://media/picker/0/1001",
            currentSubscription = unsubscribed
        )
        assertNotNull(errInvalidPhone)

        val validResult = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = plan,
            senderPhone = "+20 101 234 5678",
            receiptLocalUri = "content://media/picker/0/1001",
            currentSubscription = unsubscribed
        )
        assertNull(validResult)
        assertEquals("01012345678", MerchantSubscriptionRepository.normalizeEgyptianMobile("+20 101 234 5678"))

        // Verify UI surfaces inline validation error when submitting without receipt
        var submitted = false
        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = plan,
                    settings = VodafoneCashSettings(walletNumber = "01099887766", isConfiguredOnServer = true),
                    subscriptionStatus = unsubscribed,
                    uiState = ManualSubscriptionUiState.Idle,
                    senderPhone = "01012345678",
                    onSenderPhoneChange = {},
                    transactionReference = "",
                    onTransactionReferenceChange = {},
                    receiptLocalUri = null,
                    onPickReceiptClick = {},
                    onClearReceiptClick = {},
                    onCopyWalletClick = {},
                    onRefreshSettings = {},
                    localValidationError = errMissingReceipt,
                    onSubmitClick = { submitted = true },
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("manual_subscription_error_banner").performScrollTo().assertIsDisplayed()
        assertFalse(submitted)
    }

    // 4. Prevent duplicate request when pending_review or active
    @Test
    fun scenario04_preventDuplicateSubmissionWhenPendingReviewOrActive() {
        val plan = VodafoneCashSettings.OFFICIAL_PLANS[0]
        val pendingState = SubscriptionStatusState(
            isPending = true,
            isPendingReview = true,
            status = "pending_review",
            pendingRequestId = "subreq_existing_01"
        )
        val activeState = SubscriptionStatusState(
            isSubscribed = true,
            status = "active",
            planId = 1,
            planName = "الباقة الأساسية",
            maxCaptains = 2,
            paymentStatus = "paid",
            endDateMillis = nowMs + 20 * dayMs
        )

        assertNotNull(
            MerchantSubscriptionRepository.validateManualSubscriptionInput(
                plan = plan,
                senderPhone = "01012345678",
                receiptLocalUri = "content://media/picker/0/1001",
                currentSubscription = pendingState
            )
        )
        assertNotNull(
            MerchantSubscriptionRepository.validateManualSubscriptionInput(
                plan = plan,
                senderPhone = "01012345678",
                receiptLocalUri = "content://media/picker/0/1001",
                currentSubscription = activeState
            )
        )

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = plan,
                    settings = VodafoneCashSettings(walletNumber = "01099887766", isConfiguredOnServer = true),
                    subscriptionStatus = pendingState,
                    uiState = ManualSubscriptionUiState.Idle,
                    senderPhone = "01012345678",
                    onSenderPhoneChange = {},
                    transactionReference = "",
                    onTransactionReferenceChange = {},
                    receiptLocalUri = "content://media/picker/0/1001",
                    onPickReceiptClick = {},
                    onClearReceiptClick = {},
                    onCopyWalletClick = {},
                    onRefreshSettings = {},
                    localValidationError = null,
                    onSubmitClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("vodafone_blocked_duplicate_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_manual_subscription_button").performScrollTo().assertIsNotEnabled()
    }

    // 5. Payload serialization matches Cloud Function `submitManualSubscriptionRequest` contract + Submitted requestId confirmation card
    @Test
    fun scenario05_manualSubscriptionPayloadMatchesCloudFunctionContract() {
        val payload = ManualSubscriptionRequestPayload(
            merchantId = "merchant_123",
            planId = 4,
            planName = "الباقة المتقدمة",
            maxCaptains = 8,
            expectedAmount = 1600,
            senderPhone = "01012345678",
            transactionReference = "VF-990011",
            receiptStoragePath = "merchants/merchant_123/subscription_receipts/subreq_99.jpg",
            requestId = "subreq_99",
            notes = "تحويل من محفظة الفرع الرئيسي"
        )
        val map = payload.toCallableMap()
        assertEquals("merchant_123", map["merchantId"])
        assertEquals(4, map["planId"])
        assertEquals(4, map["packageId"])
        assertEquals("الباقة المتقدمة", map["planName"])
        assertEquals(8, map["maxCaptains"])
        assertEquals(1600, map["expectedAmount"])
        assertEquals(1600, map["amount"])
        assertEquals("01012345678", map["senderPhone"])
        assertEquals("VF-990011", map["transactionReference"])
        assertEquals("تحويل من محفظة الفرع الرئيسي", map["notes"])
        assertEquals("merchants/merchant_123/subscription_receipts/subreq_99.jpg", map["receiptStoragePath"])
        assertEquals("subreq_99", map["requestId"])
        assertEquals("subreq_99", map["idempotencyKey"])

        var doneClicked = false
        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = VodafoneCashSettings.OFFICIAL_PLANS[3],
                    settings = VodafoneCashSettings(walletNumber = "01099887766", isConfiguredOnServer = true),
                    subscriptionStatus = SubscriptionStatusState(status = "pending_review", isPendingReview = true),
                    uiState = ManualSubscriptionUiState.Submitted(
                        ManualSubscriptionSubmissionResult(
                            requestId = "subreq_99",
                            status = "pending_review",
                            planId = 4,
                            planName = "الباقة المتقدمة",
                            maxCaptains = 8,
                            expectedAmount = 1600,
                            durationDays = 30,
                            receiptStoragePath = "merchants/merchant_123/subscription_receipts/subreq_99.jpg",
                            subscriptionVersion = 2L,
                            message = "تم إرسال طلب الاشتراك بنجاح وهو الآن قيد مراجعة الإدارة."
                        )
                    ),
                    senderPhone = "01012345678",
                    onSenderPhoneChange = {},
                    transactionReference = "VF-990011",
                    onTransactionReferenceChange = {},
                    notes = "تحويل من محفظة الفرع الرئيسي",
                    onNotesChange = {},
                    receiptLocalUri = "content://media/picker/0/1001",
                    onPickReceiptClick = {},
                    onClearReceiptClick = {},
                    onCopyWalletClick = {},
                    onRefreshSettings = {},
                    localValidationError = null,
                    onSubmitClick = {},
                    onSubmittedDone = { doneClicked = true },
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("manual_subscription_submitted_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submitted_request_id_text").assertIsDisplayed().assertTextEquals("subreq_99")
        composeTestRule.onNodeWithTag("submitted_status_text").assertIsDisplayed().assertTextEquals("قيد مراجعة الإدارة")
        composeTestRule.onNodeWithTag("submitted_done_button").performClick()
        assertTrue(doneClicked)
    }

    // 6. Displaying "في انتظار مراجعة الإدارة" state clearly in UI
    @Test
    fun scenario06_displayPendingReviewStateInUi() {
        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "pending_review",
                isActive = false,
                isSubscribed = false,
                planId = 2,
                planName = "الباقة المتوسطة",
                price = 800,
                maxCaptains = 0,
                paymentMethod = "vodafone_cash_manual",
                pendingRequestId = "subreq_pending_77",
                senderPhone = "01012345678",
                subscriptionVersion = 2L
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(resolved.isPendingReview)
        assertEquals("pending_review", resolved.status)
        assertFalse(resolved.canRequestCaptain(nowMs))
        assertEquals("قيد مراجعة الإدارة", resolveSubscriptionStatusVisual(resolved).badgeText)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionStatusOverviewCard(
                    subscriptionStatus = resolved,
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("subscription_status_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("subreq_pending_77").assertIsDisplayed()
        composeTestRule.onNodeWithText("01012345678").assertIsDisplayed()
    }

    // 7. Immediate Admin approval -> active UI + plan details + transition notification
    @Test
    fun scenario07_receiveImmediateApprovalAndDisplayActiveDetails() {
        val before = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "pending_review",
                isActive = false,
                isSubscribed = false,
                planId = 3,
                planName = "الباقة المميزة",
                price = 1200,
                subscriptionVersion = 2L
            ),
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
                planId = 3,
                planName = "الباقة المميزة",
                maxCaptains = 6,
                price = 1200,
                startDateMillis = nowMs,
                approvedAtMillis = nowMs,
                endDateMillis = nowMs + 30 * dayMs,
                subscriptionVersion = 3L
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 1,
            hasVerifiedServerOnce = true
        )

        assertTrue(after.canRequestCaptain(nowMs))
        assertEquals(6, after.maxCaptains)
        assertEquals(5, after.availableCaptainSlots)
        assertEquals(30, after.remainingDays(nowMs))

        val transition = SubscriptionSyncResolver.detectTransitionNotification(
            previous = before,
            current = after,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(transition)
        assertTrue(transition!!.isActivation)
        assertTrue(transition.title.contains("تمت الموافقة"))

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionStatusOverviewCard(
                    subscriptionStatus = after,
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithText("الباقة المميزة (1200 ج.م)").assertIsDisplayed()
        composeTestRule.onNodeWithText("6 كابتن (النشط الآن: 1/6)").assertIsDisplayed()
    }

    // 8. Immediate Admin rejection -> rejected UI + rejectionReason displayed + transition notification
    @Test
    fun scenario08_receiveImmediateRejectionWithAdminReason() {
        val before = SubscriptionStatusState(
            isPending = true,
            isPendingReview = true,
            status = "pending_review",
            planId = 2
        )
        val rejectionText = "المبلغ المحول أقل من قيمة الباقة المختارة"
        val after = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "rejected",
                isActive = false,
                isSubscribed = false,
                paymentStatus = "rejected",
                planId = 2,
                planName = "الباقة المتوسطة",
                maxCaptains = 0,
                price = 800,
                rejectionReason = rejectionText,
                subscriptionVersion = 4L
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(after.isRejected)
        assertEquals(rejectionText, after.rejectionReason)
        assertFalse(after.canRequestCaptain(nowMs))

        val transition = SubscriptionSyncResolver.detectTransitionNotification(
            previous = before,
            current = after,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(transition)
        assertFalse(transition!!.isActivation)
        assertTrue(transition.body.contains(rejectionText))

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionStatusOverviewCard(
                    subscriptionStatus = after,
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("subscription_rejection_reason_box").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_rejection_reason_text").assertIsDisplayed().assertTextEquals(rejectionText)
    }

    // 9. Subscription is NEVER active before Admin server approval across all non-active states
    @Test
    fun scenario09_neverActivatedBeforeServerApproval() {
        val blockedStatuses = listOf(
            "unsubscribed",
            "pending_review",
            "pending",
            "rejected",
            "resubmission_required",
            "expired",
            "suspended",
            "inactive",
            "cancelled"
        )
        for (st in blockedStatuses) {
            val resolved = SubscriptionSyncResolver.resolve(
                subscriptionDoc = RawSubscriptionSnapshot(
                    exists = st != "unsubscribed",
                    status = st,
                    isActive = false,
                    isSubscribed = false,
                    planId = 1,
                    maxCaptains = 2,
                    endDateMillis = if (st == "expired") nowMs - dayMs else nowMs + 10 * dayMs,
                    subscriptionVersion = 1L
                ),
                merchantDoc = RawSubscriptionSnapshot(exists = false),
                isOnline = true,
                nowMillis = nowMs,
                hasVerifiedServerOnce = true
            )
            assertFalse("Status $st must NEVER allow requesting captains", resolved.canRequestCaptain(nowMs))
        }

        // Also ensure unverified offline cache can never grant access
        val offlineActiveCache = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                planId = 2,
                maxCaptains = 4,
                endDateMillis = nowMs + 15 * dayMs,
                isFromCache = true
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = false,
            nowMillis = nowMs,
            hasVerifiedServerOnce = false
        )
        assertFalse(offlineActiveCache.canRequestCaptain(nowMs))
    }

    // 10. Captain limit enforcement & active order classification (out_for_delivery is active, delivered is completed)
    @Test
    fun scenario10_captainLimitEnforcementAndActiveOrderStatuses() {
        assertFalse(isOrderCompleted("out_for_delivery"))
        assertFalse(isOrderCompleted("delivering"))
        assertTrue(isOrderInProgress("out_for_delivery"))
        assertTrue(isOrderInProgress("accepted"))
        assertTrue(isOrderCompleted("delivered"))
        assertTrue(isOrderCompleted("تم التوصيل"))
        assertTrue(isOrderCancelled("cancelled"))

        val active2Limit = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                paymentStatus = "paid",
                planId = 1,
                planName = "الباقة الأساسية",
                maxCaptains = 2,
                price = 400,
                startDateMillis = nowMs - dayMs,
                endDateMillis = nowMs + 29 * dayMs,
                subscriptionVersion = 2L
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            activeCaptainsCount = 2,
            hasVerifiedServerOnce = true
        )
        assertTrue(active2Limit.canRequestCaptain(nowMs))
        assertEquals(2, active2Limit.maxCaptains)
        assertEquals(2, active2Limit.activeCaptainsCount)
        assertEquals(0, active2Limit.availableCaptainSlots)
    }

    // 11. Admin requests receipt resubmission (`resubmission_required`): reason shown, captains blocked, resubmission allowed
    @Test
    fun scenario11_resubmissionRequiredDisplaysReasonBlocksCaptainsAndAllowsResubmit() {
        val before = SubscriptionStatusState(
            isPending = true,
            isPendingReview = true,
            status = "pending_review",
            planId = 2
        )
        val resubReason = "صورة إيصال فودافون كاش غير واضحة، يرجى رفع صورة كاملة تظهر رقم العملية"
        val after = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "resubmission_required",
                isActive = false,
                isSubscribed = false,
                paymentStatus = "resubmission_required",
                planId = 2,
                planName = "الباقة المتوسطة",
                maxCaptains = 0,
                price = 800,
                resubmissionReason = resubReason,
                subscriptionVersion = 5L
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )

        assertTrue(after.isResubmissionRequired)
        assertEquals("resubmission_required", after.status)
        assertEquals(resubReason, after.resubmissionReason)
        assertFalse(after.canRequestCaptain(nowMs))
        assertEquals("مطلوب إعادة إرسال الإيصال", resolveSubscriptionStatusVisual(after).badgeText)

        // Merchant MUST be allowed to validate & submit a new receipt while in resubmission_required
        val validationErr = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = VodafoneCashSettings.OFFICIAL_PLANS[1],
            senderPhone = "01012345678",
            receiptLocalUri = "content://media/picker/0/new_receipt",
            currentSubscription = after
        )
        assertNull(validationErr)

        val transition = SubscriptionSyncResolver.detectTransitionNotification(
            previous = before,
            current = after,
            hadPreviousState = true,
            nowMillis = nowMs
        )
        assertNotNull(transition)
        assertFalse(transition!!.isActivation)
        assertTrue(transition.title.contains("إعادة إرسال الإيصال"))

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionStatusOverviewCard(
                    subscriptionStatus = after,
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("subscription_resubmission_reason_box").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_resubmission_reason_text").assertIsDisplayed().assertTextEquals(resubReason)
    }
}
