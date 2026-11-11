package com.example

import com.squareup.moshi.Json

data class PaymentRequest(
    val amount: Int,
    val email: String,
    val first_name: String,
    val last_name: String,
    val payment_method: String,
    val phone_number: String? = null
)

data class PaymentResponse(
    val url: String,
    val type: String
)

