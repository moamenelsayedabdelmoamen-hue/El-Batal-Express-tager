package com.example

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface PaymobApiService {
    @POST("/pay")
    suspend fun createPayment(@Body request: PaymentRequest): PaymentResponse

    @POST("/pay")
    suspend fun createPaymentRaw(@Body request: PaymentRequest): Response<ResponseBody>
}

