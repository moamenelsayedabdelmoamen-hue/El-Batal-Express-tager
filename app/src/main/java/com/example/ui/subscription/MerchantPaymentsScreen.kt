package com.example.ui.subscription

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.db.MerchantPaymentRecord
import com.example.db.OrderViewModel
import com.example.db.SubscriptionSyncResolver
import com.example.ui.theme.Primary

/**
 * Dedicated strictly read-only Payments History Screen for the Merchant App.
 * Reads `payments` where `merchantId == currentUser.uid` via [OrderViewModel.merchantPayments].
 * Displays: amount, plan name, payment date, payment method, payment status,
 * transaction ID, reference number, and notes. No merchant write/edit controls.
 */
@Composable
fun MerchantPaymentsScreen(
    onBack: () -> Unit,
    orderViewModel: OrderViewModel
) {
    val payments by orderViewModel.merchantPayments.collectAsStateWithLifecycle()
    val isLoading by orderViewModel.isLoadingPayments.collectAsStateWithLifecycle()
    val errorMessage by orderViewModel.paymentsError.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        orderViewModel.refreshPaymentsAndRequestsFromServer()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("payments_back_button")
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
                        text = stringResource(R.string.payments_screen_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("payments_screen_title")
                    )
                    Text(
                        text = stringResource(R.string.payments_screen_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        MerchantPaymentsListContent(
            payments = payments,
            isLoading = isLoading,
            errorMessage = errorMessage,
            onRefresh = { orderViewModel.refreshPaymentsAndRequestsFromServer() },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MerchantPaymentsListContent(
    payments: List<MerchantPaymentRecord>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalPaidEgp = remember(payments) {
        payments
            .filter { it.paymentStatus.trim().lowercase() in listOf("paid", "admin_manual", "completed", "approved") }
            .sumOf { it.amount }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 680.dp)
            .testTag("merchant_payments_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("payments_summary_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.4.dp, Primary.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "إجمالي المدفوعات المعتمدة",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatEgpAmount(totalPaidEgp),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Primary,
                            modifier = Modifier.testTag("payments_total_amount_text")
                        )
                        Text(
                            text = "عدد العمليات المسجلة: ${payments.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("refresh_payments_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث المدفوعات",
                            tint = Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تحديث", color = Primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isLoading && payments.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = Primary)
                        Text(
                            text = "جاري تحميل سجل المدفوعات...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (!errorMessage.isNullOrBlank() && payments.isEmpty()) {
            item {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HistoryRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payments_error_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = HistoryRed)
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = HistoryRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("إعادة المحاولة")
                        }
                    }
                }
            }
        } else if (payments.isEmpty()) {
            item {
                EmptySectionCard(
                    title = stringResource(R.string.payments_empty_title),
                    subtitle = stringResource(R.string.payments_empty_subtitle),
                    tag = "payments_empty_card"
                )
            }
        } else {
            items(payments, key = { it.paymentId }) { payment ->
                PaymentRecordCard(payment = payment)
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
internal fun PaymentRecordCard(payment: MerchantPaymentRecord) {
    val (statusLabel, accent, bg) = remember(payment.paymentStatus) {
        statusBadgePalette(payment.paymentStatus)
    }
    val packageDisplay = payment.packageName?.takeIf { it.isNotBlank() } ?: "دفعة عامة (غير مرتبطة بباقة)"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("payment_record_card_${payment.paymentId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (payment.paymentStatus.trim().lowercase()) {
                                "paid", "admin_manual", "approved", "completed" -> Icons.Default.CheckCircle
                                "pending", "pending_review", "pending_verification" -> Icons.Default.HourglassTop
                                "rejected", "failed" -> Icons.Default.ErrorOutline
                                else -> Icons.Default.AccountBalanceWallet
                            },
                            contentDescription = statusLabel,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = formatEgpAmount(payment.amount),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = packageDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                StatusPill(label = statusLabel, accent = accent, container = bg)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            DetailPairRow("اسم الباقة:", packageDisplay)
            DetailPairRow("حالة الدفع:", SubscriptionSyncResolver.formatPaymentStatusArabic(payment.paymentStatus), valueColor = accent)
            DetailPairRow("تاريخ الدفع:", formatArabicDateOrDash(payment.paymentDateMillis))
            DetailPairRow("طريقة الدفع:", SubscriptionSyncResolver.formatPaymentMethodArabic(payment.paymentMethod))
            DetailPairRow("رقم العملية:", payment.transactionId.ifBlank { "—" })
            if (!payment.referenceNumber.isNullOrBlank()) {
                DetailPairRow("الرقم المرجعي:", payment.referenceNumber)
            }
            if (!payment.notes.isNullOrBlank()) {
                DetailPairRow("الملاحظات:", payment.notes)
            }
        }
    }
}
