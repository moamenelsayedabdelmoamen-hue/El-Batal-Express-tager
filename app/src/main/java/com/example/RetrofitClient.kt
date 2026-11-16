package com.example

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks
import okhttp3.OkHttpClient
import java.io.IOException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://paymob-backend-sf2w.onrender.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val user = FirebaseAuth.getInstance().currentUser
                ?: throw IOException("يجب تسجيل الدخول قبل بدء عملية الدفع.")
            val token = try {
                Tasks.await(user.getIdToken(false)).token
            } catch (e: Exception) {
                throw IOException("تعذر التحقق من جلسة Firebase قبل الدفع.", e)
            }
            if (token.isNullOrBlank()) throw IOException("تعذر الحصول على رمز المصادقة.")
            val authenticatedRequest = chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(authenticatedRequest)
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val instance: PaymobApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PaymobApiService::class.java)
    }
}

