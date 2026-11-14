package com.example

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface PaymobApiService {
    @POST("pay")
    suspend fun createPayment(
        @Header("Authorization") authorization: String,
        @Body request: PaymentRequest
    ): PaymentResponse
}
