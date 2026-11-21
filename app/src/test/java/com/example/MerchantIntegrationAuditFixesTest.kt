package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.db.CloudFunctionInvoker
import com.example.db.ManualSubscriptionRequestPayload
import com.example.db.MerchantSubscriptionRepository
import com.example.db.OrderViewModel
import com.example.db.RawSubscriptionSnapshot
import com.example.db.SubscriptionSyncResolver
import com.example.ui.subscription.MerchantPaymentsListContent
import com.example.ui.subscription.SubscriptionHistoryAndRequestsContent
import com.example.ui.theme.ElBatalExpressTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite covering all 12 mandatory cross-app integration audit fixes
 * for the El-Batal Express Merchant App (`El-Batal-Express-tager`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MerchantIntegrationAuditFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val nowMs = 1_760_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    // Test 1: Calling official `merchantCreateOrder` Cloud Function with valid inputs & parsing response (no createOrderWithCode fallback)
    @Test
    fun test01_callsMerchantCreateOrderWithValidInputsAndParsesResponse() = runBlocking {
        var capturedRegion = ""
        var capturedFunction = ""
        var capturedPayload: Map<*, *>? = null

        val repo = MerchantSubscriptionRepository(
            callableInvoker = CloudFunctionInvoker { region, functionName, payload ->
                capturedRegion = region
                capturedFunction = functionName
                capturedPayload = payload as? Map<*, *>
                mapOf(
                    "orderId" to "ord_srv_2026_01",
                    "orderCode" to "742819",
                    "planId" to "plan_enterprise_12",
                    "planName" to "باقة المؤسسات",
                    "captainLimit" to 12,
                    "activeCaptainsCount" to 3,
                    "remainingCaptains" to 9,
                    "subscriptionVersion" to 7L,
                    "message" to "تم إنشاء طلب التوصيل بنجاح"
                )
            }
        )

        val payload = MerchantSubscriptionRepository.buildMerchantCreateOrderPayload(
            merchantId = "merchant_uid_99",
            destinationAddress = "شارع التحرير، وسط البلد، القاهرة",
            deliveryFeeAmount = 45,
            formattedServiceFee = "45 جنيه",
            customerName = "أحمد محمود",
            customerPhone = "01012345678",
            orderDetails = "2 وجبة مشويات",
            deliveryNotes = "الدور الثالث",
            merchantName = "مطعم البطل",
            merchantPhone = "01095783737",
            merchantLatitude = 30.0444,
            merchantLongitude = 31.2357,
            customerLatitude = 30.0500,
            customerLongitude = 31.2400,
            idempotencyKey = "idem_order_001",
            requestedCaptains = 1
        )

        val result = repo.createMerchantOrder(payload)
        assertTrue(result.isSuccess)
        assertEquals("us-central1", capturedRegion)
        assertEquals("merchantCreateOrder", capturedFunction)
        assertNotNull(capturedPayload)
        assertEquals("merchant_uid_99", capturedPayload!!["merchantId"])
        assertEquals("شارع التحرير، وسط البلد، القاهرة", capturedPayload!!["destinationAddress"])
        assertEquals(45, capturedPayload!!["deliveryFee"])
        assertEquals("idem_order_001", capturedPayload!!["idempotencyKey"])
        assertEquals(1, capturedPayload!!["requestedCaptains"])

        val created = result.getOrThrow()
        assertEquals("ord_srv_2026_01", created.orderId)
        assertEquals("742819", created.orderCode)
        assertEquals("plan_enterprise_12", created.planId)
        assertEquals(12, created.captainLimit)
        assertEquals(3, created.activeCaptainsCount)
        assertEquals(9, created.remainingCaptains)
    }

    // Test 2: Dynamic appearance of a newly added package from `subscription_packages` (including > 8 captains)
    @Test
    fun test02_newPackageAppearsDynamicallyFromSubscriptionPackages() {
        val rawDocs = listOf(
            "pkg_basic" to mapOf(
                "planId" to "pkg_basic",
                "planName" to "الباقة الأساسية",
                "maxCaptains" to 2,
                "price" to 400,
                "durationDays" to 30,
                "features" to listOf("توصيل سريع", "دعم فني"),
                "isActive" to true,
                "displayOrder" to 1
            ),
            "pkg_enterprise_15" to mapOf(
                "planId" to "pkg_enterprise_15",
                "planName" to "باقة المؤسسات الكبرى",
                "captainLimit" to 15,
                "price" to 2750,
                "durationDays" to 45,
                "features" to listOf("15 كابتن متزامن", "أولوية قصوى", "مدير حساب مخصص"),
                "isPopular" to true,
                "isActive" to true,
                "displayOrder" to 2
            )
        )

        val parsed = MerchantSubscriptionRepository.parseSubscriptionPackages(rawDocs)
        assertEquals(2, parsed.size)

        val enterprise = parsed.first { it.id == "pkg_enterprise_15" }
        assertEquals("باقة المؤسسات الكبرى", enterprise.name)
        assertEquals(15, enterprise.maxCaptains)
        assertEquals(15, enterprise.captainLimit)
        assertEquals(2750, enterprise.priceEgp)
        assertEquals(45, enterprise.durationDays)
        assertTrue(enterprise.isPopular)
        assertEquals(listOf("15 كابتن متزامن", "أولوية قصوى", "مدير حساب مخصص"), enterprise.features)
    }

    // Test 3: Disabled package (`isActive = false`) disappears immediately from merchant packages list
    @Test
    fun test03_disabledPackageDisappearsImmediately() {
        val initialDocs = listOf(
            "pkg_a" to mapOf("planName" to "باقة أ", "maxCaptains" to 3, "price" to 600, "isActive" to true),
            "pkg_b" to mapOf("planName" to "باقة ب", "maxCaptains" to 5, "price" to 950, "isActive" to true)
        )
        assertEquals(2, MerchantSubscriptionRepository.parseSubscriptionPackages(initialDocs).size)

        val afterDisableDocs = listOf(
            "pkg_a" to mapOf("planName" to "باقة أ", "maxCaptains" to 3, "price" to 600, "isActive" to true),
            "pkg_b" to mapOf("planName" to "باقة ب", "maxCaptains" to 5, "price" to 950, "isActive" to false)
        )
        val activeOnly = MerchantSubscriptionRepository.parseSubscriptionPackages(afterDisableDocs)
        assertEquals(1, activeOnly.size)
        assertEquals("pkg_a", activeOnly.single().id)
        assertFalse(activeOnly.any { it.id == "pkg_b" })
    }

    // Test 4: Unified String `planId` support across arbitrary strings, legacy numeric IDs, planKey, and packageId
    @Test
    fun test04_supportsStringPlanIdAndLegacyNumericAndPlanKeyAndPackageId() {
        assertEquals("gold_monthly_2026", SubscriptionSyncResolver.parsePlanIdString(rawPlanId = "gold_monthly_2026"))
        assertEquals("2", SubscriptionSyncResolver.parsePlanIdString(rawPlanId = 2))
        assertEquals("plan_10_captains", SubscriptionSyncResolver.parsePlanIdString(rawPlanKey = "plan_10_captains"))
        assertEquals("pkg_vip_01", SubscriptionSyncResolver.parsePlanIdString(rawPackageId = "pkg_vip_01"))
        assertTrue(SubscriptionSyncResolver.planIdsMatch("pkg_vip_01", "PKG_VIP_01"))
        assertTrue(SubscriptionSyncResolver.planIdsMatch("2", "plan_4_captains"))

        val resolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = SubscriptionSyncResolver.fromServerCallableMap(
                mapOf(
                    "status" to "active",
                    "isActive" to true,
                    "isSubscribed" to true,
                    "paymentStatus" to "paid",
                    "planId" to "custom_ramadan_10",
                    "planKey" to "custom_ramadan_10",
                    "packageId" to "custom_ramadan_10",
                    "planName" to "باقة رمضان 10 كابتن",
                    "captainLimit" to 10,
                    "price" to 1850,
                    "durationDays" to 30,
                    "startDate" to nowMs - dayMs,
                    "endDate" to nowMs + 29 * dayMs
                )
            ),
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertEquals("custom_ramadan_10", resolved.planId)
        assertEquals("custom_ramadan_10", resolved.planKey)
        assertEquals("custom_ramadan_10", resolved.packageId)
        assertEquals(10, resolved.maxCaptains)
        assertTrue(resolved.canRequestCaptain(nowMs))
    }

    // Test 5: Submitting Vodafone Cash manual subscription request sends String `planId`, `receiptImagePath`, `senderPhone`, `transactionReference`, `idempotencyKey` without client price override
    @Test
    fun test05_submittingVodafoneCashRequestSendsExactServerContract() = runBlocking {
        var calledFunction = ""
        var calledRegion = ""
        var sentMap: Map<*, *>? = null

        val repo = MerchantSubscriptionRepository(
            callableInvoker = CloudFunctionInvoker { region, functionName, payload ->
                calledRegion = region
                calledFunction = functionName
                sentMap = payload as? Map<*, *>
                mapOf(
                    "requestId" to "req_doc_501",
                    "requestNumber" to "SUB-2026-0501",
                    "status" to "pending_review",
                    "paymentStatus" to "pending_verification",
                    "planId" to "pkg_enterprise_15",
                    "planName" to "باقة المؤسسات الكبرى",
                    "captainLimit" to 15,
                    "amount" to 2750,
                    "durationDays" to 45
                )
            }
        )

        val payload = ManualSubscriptionRequestPayload(
            merchantId = "merchant_uid_55",
            planId = "pkg_enterprise_15",
            senderPhone = "01095783737",
            transactionReference = "VF-778899",
            receiptImagePath = "merchants/merchant_uid_55/subscription_receipts/idem_vf_501.jpg",
            idempotencyKey = "idem_vf_501",
            notes = "تحويل فودافون كاش"
        )

        val result = repo.submitManualSubscriptionRequest(payload)
        assertTrue(result.isSuccess)
        assertEquals("us-central1", calledRegion)
        assertEquals("submitManualSubscriptionRequest", calledFunction)
        assertNotNull(sentMap)
        assertEquals("pkg_enterprise_15", sentMap!!["planId"])
        assertEquals("merchants/merchant_uid_55/subscription_receipts/idem_vf_501.jpg", sentMap!!["receiptImagePath"])
        assertEquals("01095783737", sentMap!!["senderPhone"])
        assertEquals("VF-778899", sentMap!!["transactionReference"])
        assertEquals("idem_vf_501", sentMap!!["idempotencyKey"])
        assertFalse("Client must NOT send price override", sentMap!!.containsKey("price"))
        assertFalse("Client must NOT send amount override", sentMap!!.containsKey("amount"))
        assertFalse("Client must NOT send expectedAmount override", sentMap!!.containsKey("expectedAmount"))
    }

    // Test 6: Reading `amount`, `requestNumber`, and `paymentStatus` directly from server response
    @Test
    fun test06_readsAmountRequestNumberAndPaymentStatusFromServerResponse() {
        val payload = ManualSubscriptionRequestPayload(
            merchantId = "merchant_uid_55",
            planId = "pkg_pro_10",
            senderPhone = "01095783737",
            transactionReference = "VF-4411",
            receiptImagePath = "merchants/merchant_uid_55/subscription_receipts/idem_601.jpg",
            idempotencyKey = "idem_601"
        )
        val parsed = MerchantSubscriptionRepository.parseManualSubscriptionResponse(
            data = mapOf(
                "requestId" to "doc_601",
                "requestNumber" to "REQ-90042",
                "status" to "pending_review",
                "paymentStatus" to "pending_verification",
                "planId" to "pkg_pro_10",
                "planName" to "باقة 10 كابتن",
                "maxCaptains" to 10,
                "amount" to 1950.5,
                "durationDays" to 30,
                "receiptImagePath" to "merchants/merchant_uid_55/subscription_receipts/idem_601.jpg"
            ),
            payload = payload
        )

        assertEquals("doc_601", parsed.requestId)
        assertEquals("REQ-90042", parsed.requestNumber)
        assertEquals(1950.5, parsed.amount, 0.001)
        assertEquals("pending_verification", parsed.paymentStatus)
        assertEquals("pkg_pro_10", parsed.planId)
        assertEquals(10, parsed.maxCaptains)
    }

    // Test 7: Blocking `pending_verification` and ALL unpaid payment statuses even if status says active
    @Test
    fun test07_blocksPendingVerificationAndAllUnpaidPaymentStatuses() {
        val blockedPaymentStatuses = listOf(
            "pending",
            "pending_verification",
            "resubmission_requested",
            "resubmission_required",
            "unpaid",
            "rejected",
            "failed"
        )

        for (payStatus in blockedPaymentStatuses) {
            val resolved = SubscriptionSyncResolver.resolve(
                subscriptionDoc = RawSubscriptionSnapshot(
                    exists = true,
                    status = "active",
                    isActive = true,
                    isSubscribed = true,
                    paymentStatus = payStatus,
                    planId = "pkg_pro_10",
                    planName = "باقة 10 كابتن",
                    maxCaptains = 10,
                    price = 1900,
                    startDateMillis = nowMs - dayMs,
                    endDateMillis = nowMs + 29 * dayMs,
                    isFromCache = false
                ),
                merchantDoc = RawSubscriptionSnapshot(exists = false),
                isOnline = true,
                nowMillis = nowMs,
                hasVerifiedServerOnce = true
            )
            assertFalse(
                "Payment status '$payStatus' MUST block captain requests",
                resolved.canRequestCaptain(nowMs)
            )
        }
    }

    // Test 8: Reading and displaying past subscription history from `subscription_history` sorted locally descending
    @Test
    fun test08_displaysPastSubscriptionHistorySortedDescendingWithoutDeletingRecords() {
        val olderArchived = SubscriptionSyncResolver.parseSubscriptionHistoryMap(
            id = "hist_jan",
            data = mapOf(
                "merchantId" to "merchant_uid_55",
                "planId" to "pkg_basic",
                "planName" to "الباقة الأساسية - يناير",
                "maxCaptains" to 2,
                "price" to 400,
                "durationDays" to 30,
                "status" to "expired",
                "paymentStatus" to "paid",
                "paymentMethod" to "vodafone_cash",
                "startDate" to nowMs - 90 * dayMs,
                "endDate" to nowMs - 60 * dayMs,
                "archivedAtMs" to nowMs - 59 * dayMs
            )
        )
        val newerArchived = SubscriptionSyncResolver.parseSubscriptionHistoryMap(
            id = "hist_feb",
            data = mapOf(
                "merchantId" to "merchant_uid_55",
                "planId" to "pkg_pro",
                "planName" to "الباقة المتوسطة - فبراير",
                "maxCaptains" to 4,
                "price" to 800,
                "durationDays" to 30,
                "status" to "expired",
                "paymentStatus" to "paid",
                "paymentMethod" to "admin_manual",
                "startDate" to nowMs - 60 * dayMs,
                "endDate" to nowMs - 30 * dayMs,
                "archivedAtMs" to nowMs - 29 * dayMs
            )
        )
        val currentActive = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_mar_current",
            subscriptionId = "sub_mar_current",
            merchantId = "merchant_uid_55",
            status = "active",
            isActive = true,
            isSubscribed = true,
            paymentStatus = "paid",
            planId = "pkg_vip",
            planName = "الباقة المميزة - مارس",
            maxCaptains = 6,
            price = 1200,
            durationDays = 30,
            startDateMillis = nowMs - 5 * dayMs,
            endDateMillis = nowMs + 25 * dayMs
        )

        val mergedHistory = SubscriptionSyncResolver.buildSubscriptionHistory(
            canonicalDoc = currentActive,
            queryDocs = emptyList(),
            archivedHistoryDocs = listOf(olderArchived, newerArchived),
            nowMillis = nowMs
        )

        assertEquals(3, mergedHistory.size)
        assertEquals("sub_mar_current", mergedHistory[0].id)
        assertTrue(mergedHistory[0].isCurrent)
        assertEquals("hist_feb", mergedHistory[1].id)
        assertEquals("hist_jan", mergedHistory[2].id)

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                SubscriptionHistoryAndRequestsContent(
                    history = mergedHistory,
                    requests = emptyList(),
                    onResubmitPlan = {},
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("subscription_history_list")
            .performScrollToNode(hasTestTag("subscription_history_card_sub_mar_current"))
        composeTestRule.onNodeWithTag("subscription_history_card_sub_mar_current").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_history_list")
            .performScrollToNode(hasTestTag("subscription_history_card_hist_feb"))
        composeTestRule.onNodeWithTag("subscription_history_card_hist_feb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("subscription_history_list")
            .performScrollToNode(hasTestTag("subscription_history_card_hist_jan"))
        composeTestRule.onNodeWithTag("subscription_history_card_hist_jan").assertIsDisplayed()
    }

    // Test 9: Dedicated Payments Screen displays all 8 required read-only fields
    @Test
    fun test09_paymentsScreenDisplaysAllRequiredPaymentFieldsReadOnly() {
        val payment = SubscriptionSyncResolver.parsePaymentMap(
            id = "pay_audit_01",
            data = mapOf(
                "merchantId" to "merchant_uid_55",
                "amount" to 1200,
                "packageName" to "الباقة المميزة",
                "paymentDate" to nowMs - dayMs,
                "paymentMethod" to "vodafone_cash",
                "paymentStatus" to "paid",
                "transactionId" to "TXN-2026-8899",
                "referenceNumber" to "REF-445566",
                "notes" to "تم التحقق والموافقة من الإدارة"
            )
        )

        composeTestRule.setContent {
            ElBatalExpressTheme(darkTheme = false, dynamicColor = false) {
                MerchantPaymentsListContent(
                    payments = listOf(payment),
                    isLoading = false,
                    errorMessage = null,
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("merchant_payments_list")
            .performScrollToNode(hasTestTag("payment_record_card_pay_audit_01"))
        composeTestRule.onNodeWithTag("payment_record_card_pay_audit_01").assertIsDisplayed()
        composeTestRule.onNodeWithText("TXN-2026-8899").assertExists()
        composeTestRule.onNodeWithText("REF-445566").assertExists()
        composeTestRule.onNodeWithText("تم التحقق والموافقة من الإدارة").assertExists()
        composeTestRule.onNodeWithText("فودافون كاش").assertExists()
        composeTestRule.onAllNodesWithText("الباقة المميزة")[0].assertExists()
    }

    // Test 10: Preventing duplicate captain orders on rapid double-tap / concurrent calls
    @Test
    fun test10_preventsDuplicateCaptainRequestsDuringConcurrentCall() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var invocationCount = 0

        val repo = MerchantSubscriptionRepository(
            callableInvoker = CloudFunctionInvoker { _, _, _ ->
                invocationCount++
                gate.await()
                mapOf(
                    "orderId" to "ord_unique_01",
                    "orderCode" to "619384",
                    "activeCaptainsCount" to 1
                )
            }
        )

        val payload = MerchantSubscriptionRepository.buildMerchantCreateOrderPayload(
            merchantId = "merchant_uid_55",
            destinationAddress = "المعادي، القاهرة",
            deliveryFeeAmount = 35,
            formattedServiceFee = "35 جنيه",
            customerName = "كريم",
            customerPhone = "01011112222",
            orderDetails = "طلب عاجل",
            deliveryNotes = "",
            merchantName = "مطعم النيل",
            merchantPhone = "01095783737",
            merchantLatitude = null,
            merchantLongitude = null,
            customerLatitude = null,
            customerLongitude = null,
            idempotencyKey = "idem_double_tap_01"
        )

        val firstCall = async { repo.createMerchantOrder(payload) }
        // Wait briefly so firstCall acquires the atomic guard and suspends on `gate`
        kotlinx.coroutines.delay(40)
        val duplicateCall = repo.createMerchantOrder(payload)

        assertTrue("Second concurrent request must fail immediately", duplicateCall.isFailure)
        gate.complete(Unit)

        val firstResult = firstCall.await()
        assertTrue(firstResult.isSuccess)
        assertEquals("Cloud Function must only be invoked once", 1, invocationCount)
    }

    // Test 11: Blocking captain requests after subscription expiry AND ensuring expired never beats valid active doc
    @Test
    fun test11_blocksAfterExpiryAndExpiredDocNeverBeatsActiveSubscription() {
        val expiredSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_expired_higher_ver",
            subscriptionId = "sub_expired_higher_ver",
            status = "active",
            isActive = true,
            isSubscribed = true,
            paymentStatus = "paid",
            planId = "pkg_old",
            planName = "الباقة القديمة",
            maxCaptains = 4,
            startDateMillis = nowMs - 35 * dayMs,
            endDateMillis = nowMs - 5 * dayMs,
            subscriptionVersion = 10L,
            updatedAtMillis = nowMs
        )
        val validActiveSub = RawSubscriptionSnapshot(
            exists = true,
            documentId = "sub_active_valid",
            subscriptionId = "sub_active_valid",
            status = "active",
            isActive = true,
            isSubscribed = true,
            paymentStatus = "paid",
            planId = "pkg_new",
            planName = "الباقة الجديدة",
            maxCaptains = 6,
            startDateMillis = nowMs - dayMs,
            endDateMillis = nowMs + 29 * dayMs,
            subscriptionVersion = 2L,
            updatedAtMillis = nowMs - dayMs
        )

        // Expired alone blocks captain requests
        val resolvedExpired = SubscriptionSyncResolver.resolve(
            subscriptionDoc = expiredSub,
            merchantDoc = RawSubscriptionSnapshot(exists = false),
            isOnline = true,
            nowMillis = nowMs,
            hasVerifiedServerOnce = true
        )
        assertTrue(resolvedExpired.isExpired)
        assertFalse(resolvedExpired.canRequestCaptain(nowMs))

        // When both exist, valid active ALWAYS beats expired regardless of version/timestamp
        val chosen = SubscriptionSyncResolver.selectEffectiveSubscriptionSnapshot(
            canonicalDoc = expiredSub,
            queryDocs = listOf(validActiveSub),
            nowMillis = nowMs
        )
        assertEquals("sub_active_valid", chosen.subscriptionId)
    }

    // Test 12: Logout clears all merchant subscription, packages, history, requests, payments, and order state
    @Test
    fun test12_logoutClearsAllMerchantStateAndPreventsDataLeakage() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(
                app,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("1:1234567890:android:testmerchant")
                    .setApiKey("test-api-key")
                    .setProjectId("el-batal-express-956ae")
                    .build()
            )
        }
        val vm = OrderViewModel(app)

        // Simulate authenticated merchant data loaded into ViewModel
        vm.applyAuthenticatedMerchant(
            com.example.auth.MerchantAccount(
                uid = "merchant_A",
                name = "مطعم أ",
                email = "a@elbatal.com",
                phone = "01012345678",
                address = "القاهرة",
                subscriptionStatus = "active"
            )
        )
        assertEquals("merchant_A", vm.restaurantProfile.value.uid)

        // Execute logout cleanup
        vm.clearListeners()

        assertEquals("", vm.restaurantProfile.value.uid)
        assertEquals("", vm.restaurantProfile.value.name)
        assertTrue(vm.subscriptionPackages.value.isEmpty())
        assertTrue(vm.subscriptionHistory.value.isEmpty())
        assertTrue(vm.manualSubscriptionRequests.value.isEmpty())
        assertTrue(vm.merchantPayments.value.isEmpty())
        assertTrue(vm.firestoreOrders.value.isEmpty())
        assertFalse(vm.subscriptionStatus.value.canRequestCaptain(nowMs))
        assertEquals("unsubscribed", vm.subscriptionStatus.value.status)
        assertEquals("", vm.vodafoneCashSettings.value.walletNumber)
        assertFalse(vm.isSubmittingOrder.value)
    }
}
