package com.example

import androidx.compose.foundation.verticalScroll
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
import com.example.db.SubscriptionPlanOption
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

    private val sampleServerPlans = listOf(
        SubscriptionPlanOption(
            id = "plan_2_captains",
            name = "الباقة الأساسية",
            captainsLabel = "2 كابتن",
            maxCaptains = 2,
            priceEgp = 400,
            durationDays = 30
        ),
        SubscriptionPlanOption(
            id = "plan_4_captains",
            name = "الباقة المتوسطة",
            captainsLabel = "4 كابتن",
            maxCaptains = 4,
            priceEgp = 800,
            durationDays = 30
        ),
        SubscriptionPlanOption(
            id = "plan_6_captains",
            name = "الباقة المميزة",
            captainsLabel = "6 كابتن",
            maxCaptains = 6,
            priceEgp = 1200,
            durationDays = 30,
            isPopular = true
        ),
        SubscriptionPlanOption(
            id = "plan_8_captains",
            name = "الباقة المتقدمة",
            captainsLabel = "8 كابتن",
            maxCaptains = 8,
            priceEgp = 1600,
            durationDays = 30
        )
    )

    // 1. Dynamic server plans catalog & Vodafone Cash UI elements (no hardcoded plans in VodafoneCashSettings)
    @Test
    fun scenario01_selectServerPlansAndRenderVodafoneCashElements() {
        assertTrue(VodafoneCashSettings().plans.isEmpty())
        assertEquals("", VodafoneCashSettings().walletNumber)

        val chosenPlan = sampleServerPlans[2] // الباقة المميزة - 6 كابتن - 1200 ج.م
        var copiedWallet = ""

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = chosenPlan,
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        walletHolderName = "البطل إكسبريس",
                        plans = sampleServerPlans,
                        isConfiguredOnServer = true,
                        isAcceptingSubscriptions = true
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

        val dynamicParsed = MerchantSubscriptionRepository.parseConfiguredPlans(
            listOf(
                mapOf("id" to "plan_basic", "name" to "الباقة الأساسية", "maxCaptains" to 2, "price" to 450, "durationDays" to 30),
                mapOf("id" to "plan_pro", "name" to "الباقة المتوسطة", "maxCaptains" to 4, "price" to 850, "durationDays" to 30)
            )
        )
        assertEquals(2, dynamicParsed.size)
        assertEquals("plan_basic", dynamicParsed[0].id)
        assertEquals(450, dynamicParsed[0].priceEgp)
        assertEquals("plan_pro", dynamicParsed[1].id)
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

        val firstReceipt = FirebaseStorageManager.buildSubscriptionReceiptStoragePath(
            uid = "merchant_uid_42",
            requestId = "subreq_1760000_abcd",
            receiptUploadId = "subreq_1760000_abcd_attempt1"
        )
        val secondReceipt = FirebaseStorageManager.buildSubscriptionReceiptStoragePath(
            uid = "merchant_uid_42",
            requestId = "subreq_1760000_abcd",
            receiptUploadId = "subreq_1760000_abcd_attempt2"
        )
        assertTrue(firstReceipt != secondReceipt)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = sampleServerPlans[0],
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        plans = sampleServerPlans,
                        isConfiguredOnServer = true,
                        isAcceptingSubscriptions = true
                    ),
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
        val plan = sampleServerPlans[1]
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

        var submitted = false
        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = plan,
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        plans = sampleServerPlans,
                        isConfiguredOnServer = true,
                        isAcceptingSubscriptions = true
                    ),
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
        val plan = sampleServerPlans[0]
        val pendingState = SubscriptionStatusState(
            isPending = true,
            isPendingReview = true,
            status = "pending_review",
            pendingRequestId = "subreq_existing_01"
        )
        val activeState = SubscriptionStatusState(
            isSubscribed = true,
            status = "active",
            planId = "plan_2_captains",
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
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        plans = sampleServerPlans,
                        isConfiguredOnServer = true,
                        isAcceptingSubscriptions = true
                    ),
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

    // 5. Payload serialization matches Cloud Function `submitManualSubscriptionRequest` contract (no client price override)
    @Test
    fun scenario05_manualSubscriptionPayloadMatchesCloudFunctionContract() {
        val payload = ManualSubscriptionRequestPayload(
            merchantId = "merchant_123",
            planId = "plan_8_captains",
            senderPhone = "01012345678",
            transactionReference = "VF-990011",
            receiptImagePath = "merchants/merchant_123/subscription_receipts/subreq_99.jpg",
            idempotencyKey = "subreq_99",
            notes = "تحويل من محفظة الفرع الرئيسي"
        )
        val map = payload.toCallableMap()
        assertEquals("merchant_123", map["merchantId"])
        assertEquals("plan_8_captains", map["planId"])
        assertEquals("plan_8_captains", map["packageId"])
        assertEquals("plan_8_captains", map["planKey"])
        assertFalse("Client must not send authoritative amount override", map.containsKey("amount"))
        assertFalse("Client must not send authoritative expectedAmount override", map.containsKey("expectedAmount"))
        assertEquals("01012345678", map["senderPhone"])
        assertEquals("VF-990011", map["transactionReference"])
        assertEquals("تحويل من محفظة الفرع الرئيسي", map["notes"])
        assertEquals("merchants/merchant_123/subscription_receipts/subreq_99.jpg", map["receiptImagePath"])
        assertEquals("merchants/merchant_123/subscription_receipts/subreq_99.jpg", map["receiptStoragePath"])
        assertEquals("subreq_99", map["requestId"])
        assertEquals("subreq_99", map["idempotencyKey"])

        var doneClicked = false
        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                VodafoneCashPaymentContent(
                    plan = sampleServerPlans[3],
                    settings = VodafoneCashSettings(
                        walletNumber = "01099887766",
                        plans = sampleServerPlans,
                        isConfiguredOnServer = true,
                        isAcceptingSubscriptions = true
                    ),
                    subscriptionStatus = SubscriptionStatusState(status = "pending_review", isPendingReview = true),
                    uiState = ManualSubscriptionUiState.Submitted(
                        ManualSubscriptionSubmissionResult(
                            requestId = "subreq_99",
                            requestNumber = "REQ-2026-0099",
                            status = "pending_review",
                            paymentStatus = "pending_verification",
                            planId = "plan_8_captains",
                            planName = "الباقة المتقدمة",
                            maxCaptains = 8,
                            amount = 1600.0,
                            durationDays = 30,
                            receiptImagePath = "merchants/merchant_123/subscription_receipts/subreq_99.jpg",
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
        composeTestRule.onNodeWithTag("submitted_request_id_text").assertIsDisplayed().assertTextEquals("REQ-2026-0099")
        composeTestRule.onNodeWithTag("submitted_status_text").assertIsDisplayed().assertTextEquals("قيد مراجعة الإدارة")
        composeTestRule.onNodeWithTag("submitted_done_button").performClick()
        assertTrue(doneClicked)
    }

    // 6. Displaying "قيد مراجعة الإدارة" state clearly in UI
    @Test
    fun scenario06_displayPendingReviewStateInUi() {
        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "pending_review",
                isActive = false,
                isSubscribed = false,
                planId = "plan_4_captains",
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
                planId = "plan_6_captains",
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
                planId = "plan_6_captains",
                planName = "الباقة المميزة",
                maxCaptains = 6,
                price = 1200,
                durationDays = 30,
                features = listOf("توصيل سريع", "دعم فني"),
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
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    SubscriptionStatusOverviewCard(
                        subscriptionStatus = after,
                        onRefresh = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("الباقة المميزة").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1200 ج.م").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("30 يوماً").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("6 كابتن (النشط الآن: 1/6)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_features_section").performScrollTo().assertIsDisplayed()
    }

    // 8. Immediate Admin rejection -> rejected UI + rejectionReason displayed + transition notification
    @Test
    fun scenario08_receiveImmediateRejectionWithAdminReason() {
        val before = SubscriptionStatusState(
            isPending = true,
            isPendingReview = true,
            status = "pending_review",
            planId = "plan_4_captains"
        )
        val rejectionText = "المبلغ المحول أقل من قيمة الباقة المختارة"
        val after = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "rejected",
                isActive = false,
                isSubscribed = false,
                paymentStatus = "rejected",
                planId = "plan_4_captains",
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
                    planId = "plan_2_captains",
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

        val offlineActiveCache = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "active",
                isActive = true,
                isSubscribed = true,
                planId = "plan_4_captains",
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
                planId = "plan_2_captains",
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
            planId = "plan_4_captains"
        )
        val resubReason = "صورة إيصال فودافون كاش غير واضحة، يرجى رفع صورة كاملة تظهر رقم العملية"
        val after = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "resubmission_required",
                isActive = false,
                isSubscribed = false,
                paymentStatus = "resubmission_required",
                planId = "plan_4_captains",
                planName = "الباقة المتوسطة",
                maxCaptains = 0,
                price = 800,
                pendingRequestId = "subreq_resubmit_001",
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

        val validationErr = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = sampleServerPlans[1],
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

    // 12. Dynamic arbitrary server planIds & captain limits (including > 8 captains) + resubmission keeps original request
    @Test
    fun scenario12_serverPlanIdsAndArbitraryCaptainLimitsAndReceiptResubmission() {
        val configuredPlans = MerchantSubscriptionRepository.parseConfiguredPlans(
            listOf(
                mapOf(
                    "planId" to "plan_2_captains",
                    "planName" to "باقة كابتنين",
                    "maxCaptains" to 2,
                    "price" to 425,
                    "durationDays" to 30,
                    "isActive" to true
                ),
                mapOf(
                    "planId" to "plan_12_captains",
                    "planName" to "باقة 12 كابتن للشركات",
                    "maxCaptains" to 12,
                    "price" to 2400,
                    "durationDays" to 60,
                    "isActive" to true
                )
            )
        )
        assertEquals(2, configuredPlans.size)
        assertEquals("plan_2_captains", configuredPlans[0].id)
        assertEquals(2, configuredPlans[0].maxCaptains)
        assertEquals(425, configuredPlans[0].priceEgp)
        assertEquals("plan_12_captains", configuredPlans[1].id)
        assertEquals(12, configuredPlans[1].maxCaptains)
        assertEquals(2400, configuredPlans[1].priceEgp)
        assertEquals(60, configuredPlans[1].durationDays)
        assertTrue(MerchantSubscriptionRepository.parseConfiguredPlans(emptyList<Any>()).isEmpty())

        val requestId = "subreq_existing_resubmission_01"
        val resubmissionState = SubscriptionSyncResolver.resolve(
            subscriptionDoc = RawSubscriptionSnapshot(
                exists = true,
                status = "resubmission_required",
                isActive = false,
                isSubscribed = false,
                planId = "plan_4_captains",
                planName = "الباقة المتوسطة",
                paymentMethod = "vodafone_cash_manual",
                pendingRequestId = requestId,
                resubmissionReason = "يرجى رفع إيصال أوضح"
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(resubmissionState.isResubmissionRequired)
        assertEquals(requestId, resubmissionState.pendingRequestId)

        val plan = sampleServerPlans[1]
        assertNotNull(
            MerchantSubscriptionRepository.validateManualSubscriptionInput(
                plan = plan,
                senderPhone = "01012345678",
                receiptLocalUri = "content://media/picker/0/1001",
                currentSubscription = resubmissionState.copy(pendingRequestId = null)
            )
        )
        assertNull(
            MerchantSubscriptionRepository.validateManualSubscriptionInput(
                plan = plan,
                senderPhone = "01012345678",
                receiptLocalUri = "content://media/picker/0/1001",
                currentSubscription = resubmissionState
            )
        )
    }
}
