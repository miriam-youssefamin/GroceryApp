package com.example.groceryapp

import kotlin.math.round

/** In-memory cart. A cart holds items from ONE shop at a time (delivery fee is per shop). */
object CartManager {

    class Line(
        val productId: String,
        var name: String,
        var price: Double,
        var maxQty: Int,
        var qty: Int
    )

    var shopId: String? = null
        private set
    var shopName: String = ""
        private set
    var deliveryFee: Double = 0.0
        private set

    private val lines = LinkedHashMap<String, Line>()

    val items: List<Line> get() = lines.values.toList()

    fun isEmpty() = lines.isEmpty()
    fun totalCount() = lines.values.sumOf { it.qty }
    fun qtyOf(productId: String) = lines[productId]?.qty ?: 0

    /** True when the cart already holds items from a different shop. */
    fun belongsToOtherShop(id: String) = lines.isNotEmpty() && shopId != id

    /** Adds one unit. Returns false if the stock limit is already reached. */
    fun add(shop: Shop, product: Product): Boolean {
        shopId = shop.id
        shopName = shop.name
        deliveryFee = shop.deliveryFee
        val line = lines.getOrPut(product.id) { Line(product.id, product.name, product.price, product.quantity, 0) }
        line.name = product.name
        line.price = product.price
        line.maxQty = product.quantity
        if (line.qty >= product.quantity) return false
        line.qty++
        return true
    }

    fun increment(productId: String): Boolean {
        val line = lines[productId] ?: return false
        if (line.qty >= line.maxQty) return false
        line.qty++
        return true
    }

    fun decrement(productId: String) {
        val line = lines[productId] ?: return
        line.qty--
        if (line.qty <= 0) lines.remove(productId)
        if (lines.isEmpty()) reset()
    }

    fun clear() {
        lines.clear()
        reset()
    }

    private fun reset() {
        shopId = null
        shopName = ""
        deliveryFee = 0.0
    }

    private fun round2(v: Double) = round(v * 100) / 100.0

    fun subtotal(): Double = round2(lines.values.sumOf { it.price * it.qty })
    fun fee(): Double = if (lines.isEmpty()) 0.0 else deliveryFee
    fun total(): Double = round2(subtotal() + fee())
}
