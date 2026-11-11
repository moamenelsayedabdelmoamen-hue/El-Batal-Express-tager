package com.example.db

import kotlinx.coroutines.flow.Flow

class OrderRepository(private val orderDao: OrderDao) {
    val allOrders: Flow<List<DeliveryOrderEntity>> = orderDao.getAllOrders()

    suspend fun insertOrder(order: DeliveryOrderEntity) {
        orderDao.insertOrder(order)
    }

    suspend fun updateOrderStatus(orderId: Int, status: String) {
        orderDao.updateOrderStatus(orderId, status)
    }

    suspend fun updateOrderRating(orderId: Int, rating: Int) {
        orderDao.updateOrderRating(orderId, rating)
    }

    suspend fun updateOrderDetails(
        orderId: Int,
        destinationAddress: String,
        orderDetails: String,
        customerPhone: String,
        customerName: String,
        serviceFee: String
    ) {
        orderDao.updateOrderDetails(
            orderId = orderId,
            destinationAddress = destinationAddress,
            orderDetails = orderDetails,
            customerPhone = customerPhone,
            customerName = customerName,
            serviceFee = serviceFee
        )
    }

    suspend fun updateOrder(order: DeliveryOrderEntity) {
        orderDao.insertOrder(order)
    }

    suspend fun assignCaptain(
        orderId: Int,
        status: String,
        captainName: String,
        captainPhone: String,
        captainPhotoUrl: String,
        estimatedArrival: String
    ) {
        orderDao.assignCaptain(
            orderId = orderId,
            status = status,
            captainName = captainName,
            captainPhone = captainPhone,
            captainPhotoUrl = captainPhotoUrl,
            estimatedArrival = estimatedArrival
        )
    }

    suspend fun ensureInitialData() {
        // No dummy data added so new accounts start with an empty state
    }
}
