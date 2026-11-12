package com.example.groceryapp

import com.google.firebase.database.Exclude

/** One product line of an order: orders/{orderId}/items/{productId}. */
data class OrderItem(
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0
)

/**
 * An order placed by a buyer: orders/{orderId}.
 * [items] is keyed by product id; [timestamp] is epoch millis.
 */
data class Order(
    @get:Exclude var id: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val items: Map<String, OrderItem> = emptyMap(),
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val total: Double = 0.0,
    val status: String = STATUS_PENDING,
    val timestamp: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
    }
}
