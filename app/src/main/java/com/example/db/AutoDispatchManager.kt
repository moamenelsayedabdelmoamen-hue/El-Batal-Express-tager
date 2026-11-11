package com.example.db

import android.content.Context
import android.util.Log
import com.example.notification.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import kotlin.math.*

/**
 * Smart Auto Dispatch Manager for El-Batal Express.
 * Implements proximity-based captain selection via Haversine distance,
 * fresh location validation, and atomic Firestore transactions to prevent race conditions.
 */
object AutoDispatchManager {
    private const val TAG = "AutoDispatchManager"
    private const val EARTH_RADIUS_KM = 6371.0
    private const val MAX_LOCATION_STALE_MS = 3 * 60 * 1000L // 3 minutes

    /**
     * Calculates distance between two points in kilometers using the Haversine formula:
     * d = 2 * R * asin(sqrt(sin^2(dlat/2) + cos(lat1) * cos(lat2) * sin^2(dlon/2)))
     */
    fun calculateHaversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    data class CandidateCaptain(
        val uid: String,
        val name: String,
        val phone: String,
        val photoUrl: String,
        val location: GeoPoint,
        val lastUpdateMs: Long,
        val distanceKm: Double,
        val fcmToken: String? = null
    )

    /**
     * Executes the Auto Dispatch process for a specific order.
     * Can be invoked from client-side fallback or upon order creation.
     */
    suspend fun dispatchOrder(
        context: Context?,
        orderId: String
    ): Boolean {
        try {
            Log.d(TAG, "Starting auto-dispatch for order: $orderId")
            val firestore = Firebase.firestore
            val orderDocRef = firestore.collection("orders").document(orderId)
            val orderSnapshot = orderDocRef.get().await()

            if (!orderSnapshot.exists()) {
                Log.w(TAG, "Order $orderId does not exist.")
                return false
            }

            val status = orderSnapshot.safeString("status") ?: "pending"
            val dispatchStatus = orderSnapshot.safeString("dispatchStatus") ?: "searching"
            val existingCaptain = orderSnapshot.safeString("captainId")

            if (!existingCaptain.isNullOrBlank() || (status != "pending" && dispatchStatus != "searching")) {
                Log.d(TAG, "Order $orderId already assigned or not in searching state. Skipping.")
                return false
            }

            val restaurantId = orderSnapshot.safeString("restaurantId")
                ?: orderSnapshot.safeString("userId")
                ?: ""

            if (restaurantId.isBlank()) {
                Log.w(TAG, "Order $orderId has no associated restaurantId.")
                return false
            }

            // Verify restaurant subscription
            val restDoc = firestore.collection("restaurants").document(restaurantId).get().await()
            if (restDoc.exists()) {
                val subStatus = restDoc.safeString("subscriptionStatus") ?: "active"
                if (subStatus != "active") {
                    Log.w(TAG, "Restaurant $restaurantId has inactive subscription: $subStatus")
                    orderDocRef.update(
                        mapOf(
                            "status" to "cancelled",
                            "dispatchStatus" to "failed",
                            "cancelReason" to "اشتراك المطعم غير مفعل"
                        )
                    ).await()
                    return false
                }
            }

            // Determine restaurant location
            var pickupLat: Double? = null
            var pickupLng: Double? = null

            val pickupGeo = orderSnapshot.getGeoPoint("pickupLocation")
                ?: orderSnapshot.getGeoPoint("restaurantLocation")
            if (pickupGeo != null) {
                pickupLat = pickupGeo.latitude
                pickupLng = pickupGeo.longitude
            } else {
                val lat = (orderSnapshot.get("restaurantLatitude") as? Number)?.toDouble()
                val lng = (orderSnapshot.get("restaurantLongitude") as? Number)?.toDouble()
                if (lat != null && lng != null) {
                    pickupLat = lat
                    pickupLng = lng
                } else if (restDoc.exists()) {
                    val restGeo = restDoc.getGeoPoint("location")
                    if (restGeo != null) {
                        pickupLat = restGeo.latitude
                        pickupLng = restGeo.longitude
                    } else {
                        pickupLat = (restDoc.get("latitude") as? Number)?.toDouble()
                        pickupLng = (restDoc.get("longitude") as? Number)?.toDouble()
                    }
                }
            }

            if (pickupLat == null || pickupLng == null) {
                Log.w(TAG, "Restaurant coordinates not found for order $orderId.")
                orderDocRef.update(
                    mapOf(
                        "dispatchStatus" to "searching",
                        "dispatchNote" to "في انتظار تحديد إحداثيات المطعم"
                    )
                ).await()
                return false
            }

            // Query captains collection
            val captainsSnapshot = firestore.collection("captains")
                .whereEqualTo("isOnline", true)
                .whereEqualTo("availabilityStatus", "available")
                .whereEqualTo("status", "approved")
                .get().await()

            val nowMs = System.currentTimeMillis()
            val candidates = mutableListOf<CandidateCaptain>()

            for (doc in captainsSnapshot.documents) {
                val currentOrderId = doc.safeString("currentOrderId")
                if (!currentOrderId.isNullOrBlank()) {
                    continue
                }

                val loc = doc.getGeoPoint("location") ?: continue
                val lastUpdateTimestamp = doc.getTimestamp("lastLocationUpdate")
                val lastUpdateMs = lastUpdateTimestamp?.toDate()?.time ?: 0L

                // Fresh location filter (within 3 minutes)
                if (nowMs - lastUpdateMs > MAX_LOCATION_STALE_MS && lastUpdateMs > 0L) {
                    Log.d(TAG, "Captain ${doc.id} skipped due to stale location.")
                    continue
                }

                val distKm = calculateHaversineDistance(pickupLat, pickupLng, loc.latitude, loc.longitude)
                candidates.add(
                    CandidateCaptain(
                        uid = doc.id,
                        name = doc.safeString("name") ?: "الكابتن",
                        phone = doc.safeString("phone") ?: "",
                        photoUrl = doc.safeString("profileImageUrl")
                            ?: doc.safeString("photoUrl")
                            ?: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80",
                        location = loc,
                        lastUpdateMs = lastUpdateMs,
                        distanceKm = distKm,
                        fcmToken = doc.safeString("fcmToken")
                    )
                )
            }

            if (candidates.isEmpty()) {
                Log.d(TAG, "No eligible captains available near restaurant. Keeping order in searching.")
                orderDocRef.update(
                    mapOf(
                        "dispatchStatus" to "searching",
                        "lastSearchAt" to FieldValue.serverTimestamp(),
                        "searchAttempts" to FieldValue.increment(1)
                    )
                ).await()
                return false
            }

            // Sort by proximity ascending
            candidates.sortBy { it.distanceKm }
            Log.d(TAG, "Found ${candidates.size} eligible captains. Closest is ${candidates[0].name} (${candidates[0].distanceKm} km)")

            // Atomic Reservation with Firestore Transaction
            var assignedCaptain: CandidateCaptain? = null

            for (candidate in candidates) {
                try {
                    firestore.runTransaction { transaction ->
                        val currentOrderDoc = transaction.get(orderDocRef)
                        val candidateDocRef = firestore.collection("captains").document(candidate.uid)
                        val currentCaptainDoc = transaction.get(candidateDocRef)

                        // 1. Re-verify Order state
                        val liveCaptId = currentOrderDoc.safeString("captainId")
                        val liveStatus = currentOrderDoc.safeString("status") ?: "pending"
                        val liveDispatch = currentOrderDoc.safeString("dispatchStatus") ?: "searching"
                        if (!liveCaptId.isNullOrBlank() || (liveStatus != "pending" && liveDispatch != "searching")) {
                            throw IllegalStateException("ORDER_ALREADY_ASSIGNED")
                        }

                        // 2. Re-verify Captain state
                        val isOnline = currentCaptainDoc.getBoolean("isOnline") ?: false
                        val availability = currentCaptainDoc.safeString("availabilityStatus") ?: "available"
                        val activeOrder = currentCaptainDoc.safeString("currentOrderId")

                        if (!isOnline || availability != "available" || !activeOrder.isNullOrBlank()) {
                            throw IllegalStateException("CAPTAIN_BUSY_OR_OFFLINE")
                        }

                        val estimatedMins = max(5, (candidate.distanceKm * 2.5 + 5).roundToInt())

                        // Atomically reserve Captain
                        transaction.update(
                            candidateDocRef,
                            mapOf(
                                "availabilityStatus" to "busy",
                                "currentOrderId" to orderId,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )

                        // Atomically assign Order
                        transaction.update(
                            orderDocRef,
                            mapOf(
                                "captainId" to candidate.uid,
                                "captainName" to candidate.name,
                                "captainPhone" to candidate.phone,
                                "captainPhotoUrl" to candidate.photoUrl,
                                "driver_name" to candidate.name,
                                "driverPhone" to candidate.phone,
                                "status" to "accepted",
                                "dispatchStatus" to "assigned",
                                "assignedAt" to FieldValue.serverTimestamp(),
                                "distanceToRestaurantKm" to (round(candidate.distanceKm * 100) / 100),
                                "estimatedArrival" to "$estimatedMins دقيقة",
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }.await()

                    assignedCaptain = candidate
                    Log.d(TAG, "Order $orderId assigned to ${candidate.name} (${candidate.uid}) successfully!")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Transaction failed for captain ${candidate.uid}: ${e.message}")
                    if (e.message == "ORDER_ALREADY_ASSIGNED") {
                        return false
                    }
                    // Try next candidate
                }
            }

            if (assignedCaptain != null) {
                if (context != null) {
                    val restaurantName = orderSnapshot.safeString("restaurantName") ?: "المطعم"
                    val fee = orderSnapshot.safeString("serviceFee") ?: orderSnapshot.safeString("price") ?: "30 جنيه"
                    NotificationHelper.showNewOrderNotification(
                        context = context,
                        orderId = orderId,
                        restaurantName = restaurantName,
                        distanceKm = assignedCaptain.distanceKm,
                        fee = fee
                    )
                }
                return true
            } else {
                orderDocRef.update(
                    mapOf(
                        "dispatchStatus" to "searching",
                        "lastSearchAt" to FieldValue.serverTimestamp(),
                        "searchAttempts" to FieldValue.increment(1)
                    )
                ).await()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running auto-dispatch: ${e.message}", e)
            return false
        }
    }
}
