package com.example.notification

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Handles Firebase Cloud Messaging tokens and subscription/order push notifications
 * for the Merchant App.
 * Notification receipt triggers immediate server verification via `subscriptionRefreshSignals`;
 * notifications are NEVER treated as an authoritative state source on their own.
 */
class MerchantMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        serviceScope.launch {
            persistTokenForCurrentMerchant(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"].orEmpty()
        val title = message.notification?.title
            ?: data["title"]
            ?: when (type) {
                "SUBSCRIPTION_ACTIVATED" -> "تم تفعيل اشتراكك"
                "SUBSCRIPTION_PLAN_CHANGED" -> "تم تحديث باقتك"
                "SUBSCRIPTION_SUSPENDED" -> "تم إيقاف الاشتراك"
                "SUBSCRIPTION_EXPIRED" -> "انتهت صلاحية اشتراكك"
                "SUBSCRIPTION_EXPIRING_SOON" -> "تنبيه قرب انتهاء الاشتراك"
                else -> "البطل إكسبريس"
            }
        val body = message.notification?.body
            ?: data["body"]
            ?: "تم تحديث حالة اشتراكك على السيرفر."

        NotificationHelper.showSubscriptionSyncNotification(
            context = applicationContext,
            title = title,
            body = body,
            notificationId = (type.ifBlank { "sub_push" }).hashCode()
        )

        if (type.startsWith("SUBSCRIPTION_")) {
            _subscriptionRefreshSignals.tryEmit(type)
        }
    }

    companion object {
        private const val TAG = "MerchantMessaging"
        private val _subscriptionRefreshSignals = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val subscriptionRefreshSignals: SharedFlow<String> = _subscriptionRefreshSignals.asSharedFlow()

        suspend fun syncCurrentFcmTokenForMerchant(uid: String) {
            if (uid.isBlank()) return
            try {
                val token = Firebase.messaging.token.await()
                if (token.isNotBlank()) {
                    persistToken(uid, token)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync current FCM token: ${e.message}")
            }
        }

        private suspend fun persistTokenForCurrentMerchant(token: String) {
            val user = Firebase.auth.currentUser ?: return
            if (!user.isEmailVerified || token.isBlank()) return
            persistToken(user.uid, token)
        }

        private suspend fun persistToken(uid: String, token: String) {
            try {
                val merchRef = Firebase.firestore.collection("merchants").document(uid)
                val snap = merchRef.get().await()
                if (!snap.exists() || snap.getString("accountType")?.trim()?.lowercase() != "merchant") {
                    return
                }
                val currentToken = snap.getString("fcmToken").orEmpty()
                if (currentToken == token) return
                merchRef.set(
                    mapOf(
                        "fcmToken" to token,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save FCM token to merchants/$uid: ${e.message}")
            }
        }
    }
}
