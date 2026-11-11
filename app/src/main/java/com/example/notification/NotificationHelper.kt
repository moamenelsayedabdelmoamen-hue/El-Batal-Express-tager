package com.example.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R

object NotificationHelper {
    private const val CHANNEL_ID = "order_status_channel"
    private const val CHANNEL_NAME = "Order Status Updates"

    fun showOutForDeliveryNotification(context: Context, destination: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("NotificationHelper", "POST_NOTIFICATIONS permission not granted, skipping system tray notification")
                    return
                }
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications when order status changes to Out for Delivery"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val title = context.getString(R.string.notification_out_for_delivery_title)
            val body = context.getString(R.string.notification_out_for_delivery_body, destination)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show notification: ${e.message}", e)
        }
    }

    fun showNewOrderNotification(
        context: Context,
        orderId: String,
        restaurantName: String,
        distanceKm: Double,
        fee: String
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("NotificationHelper", "POST_NOTIFICATIONS permission not granted, skipping notification")
                    return
                }
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for Auto Dispatch and Order Updates"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val title = "طلب توصيل جديد 🛵"
            val distFormatted = if (distanceKm > 0) " (${String.format(java.util.Locale.US, "%.1f", distanceKm)} كم)" else ""
            val body = "تم إسناد طلب جديد من $restaurantName$distFormatted بقيمة $fee. انقر لعرض التفاصيل والبدء بالتوصيل!"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(orderId.hashCode(), notification)
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show new order notification: ${e.message}", e)
        }
    }

    fun showNoInternetNotification(context: Context, action: String = "المتابعة") {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("NotificationHelper", "POST_NOTIFICATIONS permission not granted for network alert")
                    return
                }
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val networkChannelId = "network_alert_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    networkChannelId,
                    "تنبيهات الاتصال بالإنترنت",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "تنبيهات عند انقطاع الاتصال بالإنترنت"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val title = "⚠️ لا يوجد اتصال بالإنترنت"
            val body = "أنت غير متصل بالإنترنت حالياً! يرجى التحقق من اتصال الشبكة (Wi-Fi أو بيانات الهاتف) لـ $action."

            val notification = NotificationCompat.Builder(context, networkChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(8888, notification)
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to show no internet notification: ${e.message}", e)
        }
    }
}
