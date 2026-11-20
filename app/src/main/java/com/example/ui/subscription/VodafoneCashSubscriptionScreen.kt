package com.example.ui.subscription

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.db.ManualSubscriptionUiState
import com.example.db.MerchantSubscriptionRepository
import com.example.db.OrderViewModel
import com.example.db.SubscriptionPlanOption
import com.example.db.SubscriptionStatusState
import com.example.db.VodafoneCashSettings
import com.example.ui.theme.Primary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StatusActiveGreen = Color(0xFF1B873F)
private val StatusPendingAmber = Color(0xFFD97706)
private val StatusDangerRed = Color(0xFFC62828)
private val StatusNeutralSlate = Color(0xFF546E7A)
private val VodafoneBrandRed = Color(0xFFE60000)

data class SubscriptionStatusVisual(
    val badgeText: String,
    val headline: String,
    val subtitle: String,
    val accentColor: Color,
    val containerColor: Color,
    val isActive: Boolean
)

/**
 * Maps every server subscription status cleanly to Arabic merchant-facing copy & styling.
 * Covers all 7 official states:
 * 1) لم يتم الاشتراك (unsubscribed)
 * 2) في انتظار مراجعة الإدارة (pending_review / pending)
 * 3) تمت الموافقة على الاشتراك • فعال (active)
 * 4) تم رفض الطلب (rejected)
 * 5) الاشتراك منتهي (expired)
 * 6) الاشتراك موقوف (suspended / inactive / cancelled)
 * 7) جاري التحقق / غير متصل / خطأ سيرفر
 */
fun resolveSubscriptionStatusVisual(status: SubscriptionStatusState): SubscriptionStatusVisual {
    val isActive = status.canRequestCaptain()
    return when {
        status.isChecking && !isActive -> SubscriptionStatusVisual(
            badgeText = "جاري التحقق...",
            headline = "جاري التحقق من حالة الاشتراك مع السيرفر",
            subtitle = "يتم مزامنة بيانات اشتراكك مباشرة من خوادم البطل إكسبريس.",
            accentColor = Primary,
            containerColor = Primary.copy(alpha = 0.08f),
            isActive = false
        )
        !status.serverError.isNullOrBlank() && !isActive -> SubscriptionStatusVisual(
            badgeText = "تعذر التحقق",
            headline = "تعذر التحقق من حالة الاشتراك مع السيرفر",
            subtitle = status.serverError,
            accentColor = StatusPendingAmber,
            containerColor = Color(0xFFFFF7ED),
            isActive = false
        )
        status.isOfflineUnverified -> SubscriptionStatusVisual(
            badgeText = "غير متصل",
            headline = "بانتظار مزامنة السيرفر",
            subtitle = status.message ?: "أنت غير متصل بالإنترنت حالياً. يرجى الاتصال لتأكيد حالة الاشتراك.",
            accentColor = StatusPendingAmber,
            containerColor = Color(0xFFFFF7ED),
            isActive = false
        )
        isActive -> SubscriptionStatusVisual(
            badgeText = "تمت الموافقة • الاشتراك فعال ✓",
            headline = "تمت الموافقة على الاشتراك — الباقة فعالة الآن",
            subtitle = "يمكنك طلب الكباتن فوراً ضمن حد الباقة المفعلة.",
            accentColor = StatusActiveGreen,
            containerColor = Color(0xFFECFDF3),
            isActive = true
        )
        status.isPendingReview || status.isPending || status.status == "pending_review" || status.status == "pending" -> SubscriptionStatusVisual(
            badgeText = "قيد مراجعة الإدارة",
            headline = "قيد مراجعة الإدارة",
            subtitle = status.message ?: "تم استلام طلب الاشتراك وإيصال فودافون كاش. سيتم التفعيل فور موافقة الإدارة.",
            accentColor = StatusPendingAmber,
            containerColor = Color(0xFFFFFBEB),
            isActive = false
        )
        status.isResubmissionRequired || status.status == "resubmission_required" -> SubscriptionStatusVisual(
            badgeText = "مطلوب إعادة إرسال الإيصال",
            headline = "مطلوب إعادة إرسال الإيصال",
            subtitle = status.resubmissionReason ?: status.rejectionReason ?: status.message
                ?: "يرجى إعادة رفع صورة واضحة لإيصال تحويل فودافون كاش وإعادة إرسال الطلب.",
            accentColor = StatusPendingAmber,
            containerColor = Color(0xFFFFF7ED),
            isActive = false
        )
        status.isRejected || status.status == "rejected" -> SubscriptionStatusVisual(
            badgeText = "مرفوض",
            headline = "تم رفض طلب الاشتراك",
            subtitle = status.rejectionReason ?: status.message
                ?: "تم رفض طلب الاشتراك من الإدارة. يمكنك مراجعة السبب وإعادة إرسال طلب جديد.",
            accentColor = StatusDangerRed,
            containerColor = Color(0xFFFEF2F2),
            isActive = false
        )
        status.isExpired || status.status == "expired" -> SubscriptionStatusVisual(
            badgeText = "الاشتراك منتهي",
            headline = "الاشتراك منتهي",
            subtitle = status.message ?: "انتهت مدة اشتراكك. اختر باقة وأرسل طلب تجديد عبر فودافون كاش.",
            accentColor = StatusDangerRed,
            containerColor = Color(0xFFFEF2F2),
            isActive = false
        )
        status.isSuspended || status.status == "suspended" || status.status == "inactive" -> SubscriptionStatusVisual(
            badgeText = "الاشتراك موقوف",
            headline = "الاشتراك موقوف",
            subtitle = status.suspensionReason?.takeIf { it.isNotBlank() }
                ?: status.message
                ?: "تم إيقاف الاشتراك من قبل الإدارة. يرجى التواصل مع الإدارة.",
            accentColor = StatusDangerRed,
            containerColor = Color(0xFFFEF2F2),
            isActive = false
        )
        status.isCancelled || status.status == "cancelled" -> SubscriptionStatusVisual(
            badgeText = "الاشتراك ملغي",
            headline = "الاشتراك موقوف / ملغي",
            subtitle = status.suspensionReason?.takeIf { it.isNotBlank() }
                ?: status.message
                ?: "تم إلغاء الاشتراك من الإدارة. يمكنك الاشتراك في باقة جديدة.",
            accentColor = StatusDangerRed,
            containerColor = Color(0xFFFEF2F2),
            isActive = false
        )
        else -> SubscriptionStatusVisual(
            badgeText = "لم يتم الاشتراك",
            headline = "لم يتم الاشتراك",
            subtitle = "اختر الباقة المناسبة لنشاطك وأرسل طلب الاشتراك عبر فودافون كاش لتفعيل طلب الكباتن.",
            accentColor = StatusNeutralSlate,
            containerColor = Color(0xFFF1F5F9),
            isActive = false
        )
    }
}

private fun formatArabicSubscriptionDate(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    return SimpleDateFormat("dd MMMM yyyy", Locale("ar")).format(Date(millis))
}

@Composable
fun SubscriptionStatusOverviewCard(
    subscriptionStatus: SubscriptionStatusState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = remember(subscriptionStatus) { resolveSubscriptionStatusVisual(subscriptionStatus) }
    val isActive = visual.isActive
    val startDateText = remember(subscriptionStatus.startDateMillis) {
        formatArabicSubscriptionDate(subscriptionStatus.startDateMillis)
    }
    val endDateText = remember(subscriptionStatus.endDateMillis) {
        formatArabicSubscriptionDate(subscriptionStatus.endDateMillis)
    }
    val remainingText = remember(subscriptionStatus.endDateMillis, isActive, subscriptionStatus.isExpired) {
        when {
            isActive && subscriptionStatus.endDateMillis != null -> subscriptionStatus.remainingTimeLabel()
            subscriptionStatus.isExpired -> "منتهي"
            else -> "—"
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("current_subscription_summary_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, visual.accentColor.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusIcon = when {
                        isActive -> Icons.Default.CheckCircle
                        subscriptionStatus.isPendingReview || subscriptionStatus.isPending -> Icons.Default.HourglassTop
                        subscriptionStatus.isResubmissionRequired -> Icons.Default.Warning
                        subscriptionStatus.isOfflineUnverified -> Icons.Default.WifiOff
                        subscriptionStatus.isRejected || subscriptionStatus.isSuspended || subscriptionStatus.isExpired || subscriptionStatus.isCancelled -> Icons.Default.ErrorOutline
                        else -> Icons.Default.AccountBalanceWallet
                    }
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = visual.badgeText,
                        tint = visual.accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.subscription_status_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = visual.containerColor,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, visual.accentColor.copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("subscription_status_badge")
                ) {
                    Text(
                        text = visual.badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = visual.accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                color = visual.containerColor,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, visual.accentColor.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("subscription_status_banner")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = visual.headline,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = visual.accentColor
                    )
                    Text(
                        text = visual.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }

            if (subscriptionStatus.isResubmissionRequired && !subscriptionStatus.resubmissionReason.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFFFF7ED),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StatusPendingAmber.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("subscription_resubmission_reason_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.subscription_resubmission_reason_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = StatusPendingAmber
                        )
                        Text(
                            text = subscriptionStatus.resubmissionReason,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF92400E),
                            modifier = Modifier.testTag("subscription_resubmission_reason_text")
                        )
                    }
                }
            }

            if (subscriptionStatus.isRejected && !subscriptionStatus.rejectionReason.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StatusDangerRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("subscription_rejection_reason_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.subscription_rejection_reason_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = StatusDangerRed
                        )
                        Text(
                            text = subscriptionStatus.rejectionReason,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7F1D1D),
                            modifier = Modifier.testTag("subscription_rejection_reason_text")
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            if (isActive || subscriptionStatus.isPendingReview || subscriptionStatus.isResubmissionRequired || subscriptionStatus.isExpired || subscriptionStatus.isSuspended) {
                val planDisplay = if (subscriptionStatus.planId > 0 || subscriptionStatus.maxCaptains > 0) {
                    val pricePart = if (subscriptionStatus.price > 0) " (${subscriptionStatus.price} ج.م)" else ""
                    "${subscriptionStatus.planName}$pricePart"
                } else {
                    "لم يتم الاشتراك"
                }
                SubscriptionDetailRow(
                    label = if (subscriptionStatus.isPendingReview) "الباقة المطلوبة:" else "اسم الباقة:",
                    value = planDisplay,
                    valueColor = if (isActive) Primary else MaterialTheme.colorScheme.onSurface
                )
            }

            if (isActive) {
                SubscriptionDetailRow(
                    label = "الحد الأقصى للكباتن:",
                    value = "${subscriptionStatus.maxCaptains} كابتن (النشط الآن: ${subscriptionStatus.activeCaptainsCount}/${subscriptionStatus.maxCaptains})",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
                if (startDateText != "—") {
                    SubscriptionDetailRow(
                        label = "تاريخ البداية:",
                        value = startDateText,
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (endDateText != "—") {
                    SubscriptionDetailRow(
                        label = "تاريخ الانتهاء:",
                        value = endDateText,
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                }
                SubscriptionDetailRow(
                    label = "الأيام المتبقية:",
                    value = remainingText,
                    valueColor = StatusActiveGreen
                )
            } else if (subscriptionStatus.isPendingReview) {
                if (!subscriptionStatus.senderPhone.isNullOrBlank()) {
                    SubscriptionDetailRow(
                        label = "رقم التحويل المرسل:",
                        value = subscriptionStatus.senderPhone,
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!subscriptionStatus.pendingRequestId.isNullOrBlank()) {
                    SubscriptionDetailRow(
                        label = "رقم الطلب:",
                        value = subscriptionStatus.pendingRequestId,
                        valueColor = StatusPendingAmber
                    )
                }
            } else if (subscriptionStatus.isExpired && endDateText != "—") {
                SubscriptionDetailRow(
                    label = "تاريخ الانتهاء:",
                    value = endDateText,
                    valueColor = StatusDangerRed
                )
            }

            if (subscriptionStatus.isSuspended && !subscriptionStatus.suspensionReason.isNullOrBlank()) {
                SubscriptionDetailRow(
                    label = "سبب الإيقاف:",
                    value = subscriptionStatus.suspensionReason,
                    valueColor = StatusDangerRed
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, visual.accentColor.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("refresh_subscription_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.subscription_refresh_button),
                        tint = visual.accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.subscription_refresh_button),
                        color = visual.accentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDetailRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Route-bound Vodafone Cash manual subscription payment screen.
 * Uses Android's zero-permission Photo Picker (ActivityResultContracts.PickVisualMedia)
 * and submits exclusively via Firebase Storage + Cloud Function `submitManualSubscriptionRequest`.
 */
@Composable
fun VodafoneCashPaymentScreen(
    planId: Int,
    onBack: () -> Unit,
    onSubmitted: () -> Unit,
    orderViewModel: OrderViewModel
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val vodafoneSettings by orderViewModel.vodafoneCashSettings.collectAsStateWithLifecycle()
    val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
    val manualUiState by orderViewModel.manualSubscriptionUiState.collectAsStateWithLifecycle()

    val selectedPlan = remember(planId, vodafoneSettings.plans) {
        vodafoneSettings.plans.firstOrNull { it.id == planId }
            ?: VodafoneCashSettings.OFFICIAL_PLANS.firstOrNull { it.id == planId }
            ?: VodafoneCashSettings.OFFICIAL_PLANS.first()
    }

    var senderPhone by rememberSaveable(planId) { mutableStateOf("") }
    var transactionReference by rememberSaveable(planId) { mutableStateOf("") }
    var notes by rememberSaveable(planId) { mutableStateOf("") }
    var receiptLocalUri by rememberSaveable(planId) { mutableStateOf<String?>(null) }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        orderViewModel.resetManualSubscriptionUiState()
        orderViewModel.loadVodafoneCashSettings()
    }

    DisposableEffect(Unit) {
        onDispose {
            orderViewModel.resetManualSubscriptionUiState()
        }
    }

    val receiptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            receiptLocalUri = uri.toString()
            localValidationError = null
        }
    }

    VodafoneCashPaymentContent(
        plan = selectedPlan,
        settings = vodafoneSettings,
        subscriptionStatus = subscriptionStatus,
        uiState = manualUiState,
        senderPhone = senderPhone,
        onSenderPhoneChange = {
            senderPhone = it
            localValidationError = null
        },
        transactionReference = transactionReference,
        onTransactionReferenceChange = { transactionReference = it },
        notes = notes,
        onNotesChange = { notes = it },
        receiptLocalUri = receiptLocalUri,
        onPickReceiptClick = {
            receiptPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onClearReceiptClick = { receiptLocalUri = null },
        onCopyWalletClick = { wallet ->
            if (wallet.isNotBlank()) {
                clipboardManager.setText(AnnotatedString(wallet))
                Toast.makeText(
                    context,
                    context.getString(R.string.vodafone_wallet_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        onRefreshSettings = { orderViewModel.loadVodafoneCashSettings() },
        localValidationError = localValidationError,
        onSubmitClick = {
            val validationError = MerchantSubscriptionRepository.validateManualSubscriptionInput(
                plan = selectedPlan,
                senderPhone = senderPhone,
                receiptLocalUri = receiptLocalUri,
                currentSubscription = subscriptionStatus
            )
            if (validationError != null) {
                localValidationError = validationError
                return@VodafoneCashPaymentContent
            }
            localValidationError = null
            orderViewModel.submitManualVodafoneSubscription(
                plan = selectedPlan,
                senderPhone = senderPhone,
                transactionReference = transactionReference,
                receiptLocalUri = receiptLocalUri,
                notes = notes,
                onSuccess = { res ->
                    Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                },
                onError = { errMsg ->
                    localValidationError = errMsg
                }
            )
        },
        onSubmittedDone = onSubmitted,
        onBack = onBack
    )
}

/**
 * Pure, testable Compose content for the Manual Vodafone Cash Subscription screen.
 * Contains all required merchant elements with accessibility & testTag annotations.
 */
@Composable
fun VodafoneCashPaymentContent(
    plan: SubscriptionPlanOption,
    settings: VodafoneCashSettings,
    subscriptionStatus: SubscriptionStatusState,
    uiState: ManualSubscriptionUiState,
    senderPhone: String,
    onSenderPhoneChange: (String) -> Unit,
    transactionReference: String,
    onTransactionReferenceChange: (String) -> Unit,
    receiptLocalUri: String?,
    onPickReceiptClick: () -> Unit,
    onClearReceiptClick: () -> Unit,
    onCopyWalletClick: (String) -> Unit,
    onRefreshSettings: () -> Unit,
    localValidationError: String?,
    onSubmitClick: () -> Unit,
    onBack: () -> Unit,
    notes: String = "",
    onNotesChange: (String) -> Unit = {},
    onSubmittedDone: () -> Unit = onBack
) {
    val isSubmitting = uiState is ManualSubscriptionUiState.Submitting
    val submittedResult = (uiState as? ManualSubscriptionUiState.Submitted)?.result
    val isBlockedByExistingState = subscriptionStatus.canRequestCaptain() ||
        subscriptionStatus.isPendingReview ||
        subscriptionStatus.status == "pending_review"
    val isPaymentFlowUnavailable = !settings.isConfiguredOnServer || !settings.isAcceptingSubscriptions
    val errorText = localValidationError ?: (uiState as? ManualSubscriptionUiState.Error)?.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header Bar with exact required Arabic title
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("vodafone_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.vodafone_back_desc),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vodafone_cash_screen_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("vodafone_cash_screen_title")
                    )
                    Text(
                        text = stringResource(R.string.vodafone_cash_screen_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(VodafoneBrandRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = VodafoneBrandRed,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Post-submission confirmation card displaying server request ID and pending_review status
            if (submittedResult != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_subscription_submitted_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF3)),
                    border = BorderStroke(1.5.dp, StatusActiveGreen),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusActiveGreen,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.vodafone_submitted_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StatusActiveGreen
                                )
                                Text(
                                    text = stringResource(R.string.vodafone_submitted_status_value),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusPendingAmber,
                                    modifier = Modifier.testTag("submitted_status_text")
                                )
                            }
                        }

                        HorizontalDivider(color = StatusActiveGreen.copy(alpha = 0.25f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.vodafone_submitted_request_id_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF14532D)
                            )
                            Text(
                                text = submittedResult.requestId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF14532D),
                                modifier = Modifier.testTag("submitted_request_id_text")
                            )
                        }

                        SubscriptionDetailRow(
                            label = "الباقة المطلوبة:",
                            value = "${submittedResult.planName} (${submittedResult.expectedAmount} ج.م)",
                            valueColor = Color(0xFF14532D)
                        )

                        Text(
                            text = submittedResult.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF166534)
                        )

                        Button(
                            onClick = onSubmittedDone,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusActiveGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("submitted_done_button")
                        ) {
                            Text(
                                text = stringResource(R.string.vodafone_submitted_done_button),
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Resubmission guidance notice when Admin requested a clearer receipt
            if (submittedResult == null && subscriptionStatus.isResubmissionRequired) {
                Surface(
                    color = Color(0xFFFFF7ED),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, StatusPendingAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vodafone_resubmission_notice_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.subscription_status_resubmission_required),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = StatusPendingAmber
                        )
                        Text(
                            text = subscriptionStatus.resubmissionReason
                                ?: subscriptionStatus.message
                                ?: "يرجى رفع صورة واضحة لإيصال تحويل فودافون كاش وإعادة إرسال الطلب.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF92400E)
                        )
                    }
                }
            }

            // Guard notice if merchant already has pending_review or active subscription
            if (submittedResult == null && isBlockedByExistingState) {
                Surface(
                    color = Color(0xFFFFFBEB),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, StatusPendingAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vodafone_blocked_duplicate_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = StatusPendingAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = if (subscriptionStatus.canRequestCaptain()) {
                                "لديك باقة نشطة وسارية حالياً، لا يمكن إرسال طلب اشتراك جديد قبل انتهاء الباقة الحالية."
                            } else {
                                "لديك بالفعل طلب اشتراك قيد مراجعة الإدارة حالياً. يرجى انتظار قرار الإدارة."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E)
                        )
                    }
                }
            }

            // 1 & 2. Selected Plan Name + Required Transfer Amount
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_plan_summary_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.5.dp, Primary.copy(alpha = 0.45f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.vodafone_selected_plan_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = plan.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("vodafone_selected_plan_name")
                            )
                            Text(
                                text = "${plan.captainsLabel} • المدة: ${plan.durationDays} يوماً",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = Primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.vodafone_amount_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary
                                )
                                Text(
                                    text = "${plan.priceEgp} ج.م",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Primary,
                                    modifier = Modifier.testTag("vodafone_cash_amount_text")
                                )
                            }
                        }
                    }
                }
            }

            // 3 & 4. Official Vodafone Cash Wallet Number + Copy Button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vodafone_wallet_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.5.dp, VodafoneBrandRed.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = VodafoneBrandRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.vodafone_official_wallet_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (settings.isLoading && settings.walletNumber.isBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = VodafoneBrandRed
                            )
                            Text(
                                text = "جاري تحميل رقم محفظة فودافون كاش الرسمي...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (settings.walletNumber.isNotBlank()) {
                        Surface(
                            color = VodafoneBrandRed.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, VodafoneBrandRed.copy(alpha = 0.28f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = settings.walletNumber,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = VodafoneBrandRed,
                                        modifier = Modifier.testTag("vodafone_cash_wallet_number")
                                    )
                                    if (settings.walletHolderName.isNotBlank()) {
                                        Text(
                                            text = "باسم: ${settings.walletHolderName}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("vodafone_wallet_holder_name")
                                        )
                                    }
                                }
                                Button(
                                    onClick = { onCopyWalletClick(settings.walletNumber) },
                                    colors = ButtonDefaults.buttonColors(containerColor = VodafoneBrandRed),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("copy_wallet_number_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.vodafone_copy_number),
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.vodafone_copy_number),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            color = Color(0xFFFFF7ED),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StatusPendingAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = settings.loadError
                                        ?: "لم يتم ضبط رقم محفظة فودافون كاش الرسمي في إعدادات السيرفر بعد. يرجى التواصل مع الإدارة.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF92400E),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("vodafone_wallet_missing_notice")
                                )
                                OutlinedButton(
                                    onClick = onRefreshSettings,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إعادة تحميل رقم المحفظة")
                                }
                            }
                        }
                    }
                }
            }

            if (!settings.isLoading && settings.isConfiguredOnServer && !settings.isAcceptingSubscriptions) {
                Surface(
                    color = Color(0xFFFFF7ED),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StatusPendingAmber),
                    modifier = Modifier.fillMaxWidth().testTag("vodafone_subscriptions_paused_notice")
                ) {
                    Text(
                        text = settings.pauseMessage
                            ?: settings.loadError
                            ?: "استقبال طلبات الاشتراك متوقف مؤقتاً من الإدارة.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF92400E),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. Clear Transfer Instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vodafone_cash_instructions_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.vodafone_instructions_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = settings.instructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }

            // 6 & 7. Sender Phone + Optional Transaction Reference Inputs
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.vodafone_transfer_details_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = senderPhone,
                        onValueChange = onSenderPhoneChange,
                        enabled = !isSubmitting && !isBlockedByExistingState && !isPaymentFlowUnavailable,
                        label = { Text(stringResource(R.string.vodafone_sender_phone_label)) },
                        placeholder = { Text("010xxxxxxxx") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sender_phone_input")
                    )

                    OutlinedTextField(
                        value = transactionReference,
                        onValueChange = onTransactionReferenceChange,
                        enabled = !isSubmitting && !isBlockedByExistingState && !isPaymentFlowUnavailable && submittedResult == null,
                        label = { Text(stringResource(R.string.vodafone_reference_label)) },
                        placeholder = { Text(stringResource(R.string.vodafone_reference_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("transaction_reference_input")
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = onNotesChange,
                        enabled = !isSubmitting && !isBlockedByExistingState && !isPaymentFlowUnavailable && submittedResult == null,
                        label = { Text(stringResource(R.string.vodafone_notes_label)) },
                        placeholder = { Text(stringResource(R.string.vodafone_notes_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        minLines = 2,
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notes_input")
                    )
                }
            }

            // 8 & 9. Receipt Image Upload Button + Preview Before Submit
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.2.dp,
                    if (receiptLocalUri.isNullOrBlank()) MaterialTheme.colorScheme.outlineVariant else StatusActiveGreen.copy(alpha = 0.55f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.vodafone_receipt_section_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.vodafone_receipt_required_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!receiptLocalUri.isNullOrBlank()) {
                            Surface(
                                color = Color(0xFFECFDF3),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "تم الاختيار ✓",
                                    color = StatusActiveGreen,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (!receiptLocalUri.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, StatusActiveGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("receipt_preview_card")
                        ) {
                            AsyncImage(
                                model = receiptLocalUri,
                                contentDescription = stringResource(R.string.vodafone_receipt_preview_desc),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("receipt_preview_image")
                            )
                            IconButton(
                                onClick = onClearReceiptClick,
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.58f))
                                    .testTag("clear_receipt_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.vodafone_remove_receipt_desc),
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onPickReceiptClick,
                        enabled = !isSubmitting && !isBlockedByExistingState && !isPaymentFlowUnavailable,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.2.dp, Primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("upload_receipt_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (receiptLocalUri.isNullOrBlank()) {
                                stringResource(R.string.vodafone_upload_receipt_button)
                            } else {
                                stringResource(R.string.vodafone_change_receipt_button)
                            },
                            color = Primary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Inline Validation or Server Error Banner
            if (!errorText.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, StatusDangerRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_subscription_error_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusDangerRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusDangerRed
                        )
                    }
                }
            }

            // 10. Primary Submit Button ("إرسال طلب الاشتراك للمراجعة")
            if (submittedResult == null) {
                Button(
                    onClick = onSubmitClick,
                    enabled = !isSubmitting && !isBlockedByExistingState && !isPaymentFlowUnavailable,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("submit_manual_subscription_button")
                ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = (uiState as? ManualSubscriptionUiState.Submitting)?.stepMessage
                            ?: "جاري إرسال طلب الاشتراك للمراجعة...",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.vodafone_submit_review_button),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                }
            }

            // Transparency note: Admin must approve before activation
            Text(
                text = stringResource(R.string.vodafone_server_approval_disclaimer),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
