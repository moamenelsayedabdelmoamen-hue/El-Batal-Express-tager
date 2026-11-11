package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "delivery_orders")
data class DeliveryOrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val firestoreOrderId: String = "",
    val orderCode: String = "",
    val orderDate: String,
    val destinationAddress: String,
    val serviceFee: String,
    val customerName: String = "",
    val customerPhone: String = "",
    val orderDetails: String = "",
    val deliveryNotes: String = "",
    val customerLatitude: Double? = null,
    val customerLongitude: Double? = null,
    val status: String = "جاري البحث",
    val rating: Int = 0,
    val captainId: String? = null,
    val captainName: String = "في انتظار كابتن",
    val captainPhone: String = "",
    val captainPhotoUrl: String = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=250&q=80",
    val estimatedArrival: String = "",
    val receiptImageUrl: String = "",
    val customerDetailsSubmitted: Boolean = false,
    val customerDetailsConfirmed: Boolean = false,
    val customerDetailsRejected: Boolean = false,
    val rejectionReason: String = "",
    val captainReachedStore: Boolean = false,
    val orderPickedUp: Boolean = false
)
