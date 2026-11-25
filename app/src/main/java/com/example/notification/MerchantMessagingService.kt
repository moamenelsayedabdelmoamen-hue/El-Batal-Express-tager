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

    private val serviceExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "Unhandled error in MerchantMessagingService coroutine: ${throwable.message}")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        if (token.isNotBlank()) {
            fcmRegistrationHardFailed = false
            lastKnownToken = token
        }
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
                "SUBSCRIPTION_REJECTED" -> "تم رفض طلب الاشتراك"
                "SUBSCRIPTION_RESUBMISSION_REQUIRED" -> "مطلوب إعادة إرسال الإيصال"
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

        private val tokenSyncMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var fcmRegistrationHardFailed: Boolean = false

        @Volatile
        private var lastSyncedUid: String? = null

        @Volatile
        private var lastKnownToken: String? = null

        internal fun isHardRegistrationFailure(throwable: Throwable?): Boolean {
            var current = throwable
            while (current != null) {
                val msg = current.message.orEmpty()
                if (
                    msg.contains("TOO_MANY_REGISTRATIONS", ignoreCase = true) ||
                    msg.contains("MISSING_INSTANCEID_SERVICE", ignoreCase = true) ||
                    msg.contains("INVALID_SENDER", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        internal fun resetSyncStateForTests() {
            fcmRegistrationHardFailed = false
            lastSyncedUid = null
            lastKnownToken = null
        }

        suspend fun syncCurrentFcmTokenForMerchant(uid: String) {
            val cleanUid = uid.trim()
            if (cleanUid.isBlank() || fcmRegistrationHardFailed) return

            tokenSyncMutex.lock()
            try {
                if (fcmRegistrationHardFailed) return
                // Avoid hammering FirebaseInstanceId on every screen transition / listener re-attach.
                if (lastSyncedUid == cleanUid && !lastKnownToken.isNullOrBlank()) {
                    return
                }

                val token = try {
                    Firebase.messaging.token.await()
                } catch (e: Exception) {
                    if (isHardRegistrationFailure(e)) {
                        fcmRegistrationHardFailed = true
                        runCatching { Firebase.messaging.isAutoInitEnabled = false }
                        Log.w(
                            TAG,
                            "FCM registration hit hard device limit (TOO_MANY_REGISTRATIONS); disabling retries for this session. Firestore real-time sync remains active."
                        )
                    } else {
                        Log.w(TAG, "Failed to sync current FCM token: ${e.message}")
                    }
                    return
                }

                if (token.isNotBlank()) {
                    lastKnownToken = token
                    runCatching { Firebase.messaging.isAutoInitEnabled = true }
                    persistToken(cleanUid, token)
                    lastSyncedUid = cleanUid
                }
            } finally {
                tokenSyncMutex.unlock()
            }
        }

        /**
         * Detaches this installation's token from the merchant before logout so the next
         * signed-in account cannot receive the previous merchant's notifications.
         */
        suspend fun clearCurrentFcmTokenForMerchant(uid: String) {
            val cleanUid = uid.trim()
            if (cleanUid.isBlank()) return
            val cachedToken = lastKnownToken
            lastSyncedUid = null
            if (fcmRegistrationHardFailed && cachedToken.isNullOrBlank()) {
                return
            }
            var resolvedToken = cachedToken
            try {
                if (resolvedToken.isNullOrBlank() && !fcmRegistrationHardFailed) {
                    resolvedToken = Firebase.messaging.token.await()
                }
                if (!resolvedToken.isNullOrBlank()) {
                    val merchantRef = Firebase.firestore.collection("merchants").document(cleanUid)
                    val snapshot = merchantRef.get().await()
                    if (snapshot.exists() && snapshot.getString("fcmToken") == resolvedToken) {
                        merchantRef.update(
                            mapOf(
                                "fcmToken" to FieldValue.delete(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }
            } catch (e: Exception) {
                if (isHardRegistrationFailure(e)) {
                    fcmRegistrationHardFailed = true
                    runCatching { Firebase.messaging.isAutoInitEnabled = false }
                }
                Log.w(TAG, "Failed to detach merchant FCM token: ${e.message}")
            } finally {
                lastKnownToken = null
                if (!fcmRegistrationHardFailed && !resolvedToken.isNullOrBlank()) {
                    try {
                        Firebase.messaging.deleteToken().await()
                    } catch (e: Exception) {
                        if (isHardRegistrationFailure(e)) {
                            fcmRegistrationHardFailed = true
                            runCatching { Firebase.messaging.isAutoInitEnabled = false }
                        }
                        Log.w(TAG, "Failed to delete local FCM token: ${e.message}")
                    }
                }
            }
        }

        private suspend fun persistTokenForCurrentMerchant(token: String) {
            val user = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return
            if (!user.isEmailVerified || token.isBlank()) return
            persistToken(user.uid, token)
            lastSyncedUid = user.uid
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
