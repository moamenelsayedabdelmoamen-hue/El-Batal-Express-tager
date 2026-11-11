package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM delivery_orders ORDER BY id DESC")
    fun getAllOrders(): Flow<List<DeliveryOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: DeliveryOrderEntity)

    @Query("SELECT COUNT(*) FROM delivery_orders")
    suspend fun getOrderCount(): Int

    @Query("UPDATE delivery_orders SET status = :status WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Int, status: String)

    @Query("UPDATE delivery_orders SET rating = :rating WHERE id = :orderId")
    suspend fun updateOrderRating(orderId: Int, rating: Int)

    @Query("UPDATE delivery_orders SET destinationAddress = :destinationAddress, orderDetails = :orderDetails, customerPhone = :customerPhone, customerName = :customerName, serviceFee = :serviceFee WHERE id = :orderId")
    suspend fun updateOrderDetails(
        orderId: Int,
        destinationAddress: String,
        orderDetails: String,
        customerPhone: String,
        customerName: String,
        serviceFee: String
    )

    @Query("UPDATE delivery_orders SET status = :status, captainName = :captainName, captainPhone = :captainPhone, captainPhotoUrl = :captainPhotoUrl, estimatedArrival = :estimatedArrival WHERE id = :orderId")
    suspend fun assignCaptain(
        orderId: Int,
        status: String,
        captainName: String,
        captainPhone: String,
        captainPhotoUrl: String,
        estimatedArrival: String
    )
}
