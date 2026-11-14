package com.example

import com.squareup.moshi.Json

data class PaymentRequest(
    @Json(name = "restaurant_id") val restaurantId: String,
    @Json(name = "captains_count") val captainsCount: Int,
    val email: String,
    @Json(name = "first_name") val firstName: String,
    @Json(name = "last_name") val lastName: String,
    @Json(name = "payment_method") val paymentMethod: String,
    @Json(name = "phone_number") val phoneNumber: String? = null
)

data class PaymentResponse(
    val url: String,
    val type: String,
    @Json(name = "order_id") val orderId: String? = null
)
