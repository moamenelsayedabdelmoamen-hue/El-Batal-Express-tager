package com.example

import com.squareup.moshi.Json

data class PaymentRequest(
    val amount: Int,
    val email: String,
    val first_name: String,
    val last_name: String,
    val payment_method: String,
    val phone_number: String? = null,
    val captains_count: Int = 2,
    val merchant_id: String? = null
)

data class PaymentResponse(
    val url: String? = null,
    val type: String? = null,
    val status: String? = null,
    val success: Boolean? = null,
    val message: String? = null
)

