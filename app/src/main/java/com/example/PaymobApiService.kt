package com.example

import retrofit2.http.Body
import retrofit2.http.POST

interface PaymobApiService {
    @POST("/pay")
    suspend fun createPayment(@Body request: PaymentRequest): PaymentResponse
}
