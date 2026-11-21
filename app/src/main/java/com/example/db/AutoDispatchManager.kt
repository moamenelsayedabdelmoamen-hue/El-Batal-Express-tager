package com.example.db

import android.content.Context
import android.util.Log

/**
 * Legacy client-side dispatcher intentionally disabled.
 *
 * Order selection, captain reservation, offer expiry and reassignment are owned
 * exclusively by Dashboard Cloud Functions. This compatibility stub remains so
 * older callers fail closed instead of attempting direct Firestore writes.
 */
@Deprecated("Order dispatch is server-owned by Dashboard Cloud Functions.")
object AutoDispatchManager {
    private const val TAG = "AutoDispatchManager"

    @Suppress("UNUSED_PARAMETER")
    suspend fun dispatchOrder(context: Context?, orderId: String): Boolean {
        Log.w(
            TAG,
            "Ignoring legacy client-side dispatch for $orderId; use Dashboard Cloud Functions."
        )
        return false
    }
}
