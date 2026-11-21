package com.example.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.db.ManualSubscriptionRequestRecord
import com.example.db.SubscriptionHistoryItem
import com.example.db.SubscriptionSyncResolver
import com.example.ui.theme.Primary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val HistoryGreen = Color(0xFF1B873F)
internal val HistoryAmber = Color(0xFFD97706)
internal val HistoryRed = Color(0xFFC62828)
internal val HistorySlate = Color(0xFF546E7A)

internal fun formatArabicDateOrDash(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    return SimpleDateFormat("dd MMM yyyy - hh:mm a", Locale("ar")).format(Date(millis))
}

internal fun formatArabicShortDate(millis: Long?): String {
    if (millis == null || millis <= 0L) return "—"
    return SimpleDateFormat("dd MMMM yyyy", Locale("ar")).format(Date(millis))
}

internal fun formatEgpAmount(amount: Double): String {
    return if (amount % 1.0 == 0.0) "${amount.toInt()} ج.م" else String.format(Locale.US, "%.2f ج.م", amount)
}

internal fun statusBadgePalette(status: String): Triple<String, Color, Color> {
    return when (status.trim().lowercase(Locale.ROOT)) {
        "active", "approved", "paid", "admin_manual", "completed" -> Triple(
            when (status.trim().lowercase(Locale.ROOT)) {
                "approved" -> "تمت الموافقة ✓"
                "paid", "completed" -> "مدفوع ✓"
                "admin_manual" -> "مفعّل من الإدارة ✓"
                else -> "نشط ✓"
            },
            HistoryGreen,
            Color(0xFFECFDF3)
        )
        "pending_review", "pending", "pending_verification" -> Triple(
            if (status.trim().lowercase(Locale.ROOT) == "pending_verification") "بانتظار التحقق" else "قيد مراجعة الإدارة",
            HistoryAmber,
            Color(0xFFFFFBEB)
        )
        "resubmission_required", "resubmission_requested" -> Triple("مطلوب إعادة إرسال الإيصال", HistoryAmber, Color(0xFFFFF7ED))
        "rejected", "failed" -> Triple(
            if (status.trim().lowercase(Locale.ROOT) == "failed") "فشل الدفع" else "مرفوض",
            HistoryRed,
            Color(0xFFFEF2F2)
        )
        "expired", "archived" -> Triple(
            if (status.trim().lowercase(Locale.ROOT) == "archived") "مؤرشف" else "منتهي",
            HistoryRed,
            Color(0xFFFEF2F2)
        )
        "suspended", "inactive" -> Triple("موقوف", HistoryRed, Color(0xFFFEF2F2))
        "cancelled" -> Triple("ملغي", HistorySlate, Color(0xFFF1F5F9))
        "refunded" -> Triple("مسترد", HistorySlate, Color(0xFFF1F5F9))
        else -> Triple("غير مدفوع", HistorySlate, Color(0xFFF1F5F9))
    }
}

@Composable
fun SubscriptionHistoryAndRequestsContent(
    history: List<SubscriptionHistoryItem>,
    requests: List<ManualSubscriptionRequestRecord>,
    onResubmitPlan: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 680.dp)
            .testTag("subscription_history_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 1: Manual Vodafone Cash Subscription Requests
        item {
            SectionHeaderRow(
                title = stringResource(R.string.manual_requests_section_title),
                count = requests.size,
                icon = Icons.Default.ReceiptLong,
                onRefresh = onRefresh,
                refreshTag = "refresh_history_requests_button"
            )
        }

        if (requests.isEmpty()) {
            item {
                EmptySectionCard(
                    title = stringResource(R.string.manual_requests_empty),
                    subtitle = "عند إرسال طلب اشتراك عبر فودافون كاش سيظهر هنا بحالته المباشرة (قيد المراجعة / تمت الموافقة / مرفوض).",
                    tag = "manual_requests_empty_card"
                )
            }
        } else {
            items(requests, key = { "req_${it.requestId}" }) { request ->
                ManualRequestTrackingCard(
                    request = request,
                    onResubmit = { onResubmitPlan(request.planId) }
                )
            }
        }

        // Section 2: Subscription History (Current + Past Periods from `subscriptions` & `subscription_history`)
        item {
            Spacer(modifier = Modifier.height(4.dp))
            SectionHeaderRow(
                title = stringResource(R.string.subscription_history_section_title),
                count = history.size,
                icon = Icons.Default.History,
                onRefresh = null,
                refreshTag = null
            )
        }

        if (history.isEmpty()) {
            item {
                EmptySectionCard(
                    title = stringResource(R.string.subscription_history_empty),
                    subtitle = "تُحفظ هنا جميع اشتراكاتك الحالية والسابقة مع التواريخ والباقات دون حذف السجل القديم عند التجديد.",
                    tag = "subscription_history_empty_card"
                )
            }
        } else {
            items(history, key = { "sub_${it.id}" }) { item ->
                SubscriptionHistoryCard(item = item)
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ManualRequestTrackingCard(
    request: ManualSubscriptionRequestRecord,
    onResubmit: () -> Unit
) {
    val (badgeLabel, accentColor, bgColor) = remember(request.status) { statusBadgePalette(request.status) }
    val showReason = when (request.status) {
        "rejected" -> request.rejectionReason
        "resubmission_required", "resubmission_requested" -> request.resubmissionReason ?: request.rejectionReason
        else -> null
    }
    val displayNumber = request.requestNumber?.takeIf { it.isNotBlank() } ?: request.requestId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("manual_request_card_${request.requestId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.4f)),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.planName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${request.maxCaptains} كابتن • ${formatEgpAmount(request.amount.toDouble())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                StatusPill(label = badgeLabel, accent = accentColor, container = bgColor)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            DetailPairRow("رقم الطلب:", displayNumber)
            DetailPairRow("حالة الدفع:", SubscriptionSyncResolver.formatPaymentStatusArabic(request.paymentStatus), valueColor = accentColor)
            if (request.senderPhone.isNotBlank()) {
                DetailPairRow("رقم المحوِّل:", request.senderPhone)
            }
            if (request.transactionReference.isNotBlank()) {
                DetailPairRow("رقم المرجع:", request.transactionReference)
            }
            DetailPairRow("تاريخ الإرسال:", formatArabicDateOrDash(request.createdAtMillis))

            if (!showReason.isNullOrBlank()) {
                Surface(
                    color = bgColor,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request_reason_${request.requestId}")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (request.status == "rejected") "سبب الرفض:" else "ملاحظة الإدارة:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor
                        )
                        Text(
                            text = showReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (request.status == "resubmission_required" || request.status == "resubmission_requested" || request.status == "rejected") {
                Button(
                    onClick = onResubmit,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("resubmit_request_button_${request.requestId}")
                ) {
                    Text(
                        text = if (request.status == "resubmission_required" || request.status == "resubmission_requested") {
                            "إعادة رفع الإيصال وإرسال الطلب"
                        } else {
                            "إرسال طلب اشتراك جديد"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubscriptionHistoryCard(item: SubscriptionHistoryItem) {
    val (badgeLabel, accentColor, bgColor) = remember(item.status) { statusBadgePalette(item.status) }
    val (_, payAccent, _) = remember(item.paymentStatus) { statusBadgePalette(item.paymentStatus) }
    val payLabel = remember(item.paymentStatus) { SubscriptionSyncResolver.formatPaymentStatusArabic(item.paymentStatus) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_history_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            if (item.isCurrent) 1.8.dp else 1.dp,
            if (item.isCurrent) HistoryGreen else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (item.isCurrent) 3.dp else 1.dp)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (item.isCurrent) "${item.planName} (الحالي)" else item.planName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val durationPart = if (item.durationDays > 0) " • ${item.durationDays} يوماً" else ""
                    Text(
                        text = "${item.maxCaptains} كابتن • ${item.price} ج.م$durationPart",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
                StatusPill(label = badgeLabel, accent = accentColor, container = bgColor)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            DetailPairRow("تاريخ البداية:", formatArabicShortDate(item.startDateMillis))
            DetailPairRow("تاريخ الانتهاء:", formatArabicShortDate(item.endDateMillis))
            if (item.archivedAtMillis != null && item.archivedAtMillis > 0L) {
                DetailPairRow("تاريخ الأرشفة:", formatArabicShortDate(item.archivedAtMillis))
            }
            DetailPairRow("طريقة الدفع:", SubscriptionSyncResolver.formatPaymentMethodArabic(item.paymentMethod))
            DetailPairRow("حالة الدفع:", payLabel, valueColor = payAccent)
            if (item.features.isNotEmpty()) {
                DetailPairRow("المميزات:", item.features.joinToString(" • "))
            }
            if (!item.reason.isNullOrBlank() && item.status in listOf("rejected", "suspended", "cancelled", "resubmission_required", "resubmission_requested")) {
                DetailPairRow("السبب / الملاحظة:", item.reason, valueColor = HistoryRed)
            }
        }
    }
}

@Composable
internal fun SectionHeaderRow(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRefresh: (() -> Unit)?,
    refreshTag: String?
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
            Icon(imageVector = icon, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (onRefresh != null) {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (refreshTag != null) Modifier.testTag(refreshTag) else Modifier)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "تحديث",
                    tint = Primary
                )
            }
        }
    }
}

@Composable
internal fun StatusPill(label: String, accent: Color, container: Color) {
    Surface(
        color = container,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun DetailPairRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun EmptySectionCard(title: String, subtitle: String, tag: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
