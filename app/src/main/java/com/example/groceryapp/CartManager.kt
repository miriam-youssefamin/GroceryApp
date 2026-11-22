package com.example.groceryapp

import kotlin.math.round

/**
 * The buyer's shopping cart, kept in memory (it is not saved to Firebase until the order is placed).
 *
 * Rules:
 *  - A cart holds items from ONE shop at a time, because the delivery fee belongs to that shop.
 *  - A line can never go above the stock the buyer saw ([Line.maxQty]). This is the client-side
 *    quantity check; [OrderRepository.placeOrder] repeats it on the server with a transaction.
 *  - The delivery method (delivery / pickup) and the buyer's location stay set between orders,
 *    so a returning buyer doesn't have to pick them again.
 *  - The delivery distance is measured between the shop's saved location and the buyer's phone
 *    location ([distanceKm]); the buyer can't choose it.
 */
object CartManager {

    /**
     * One product in the cart.
     * @property maxQty Stock available when the product was added (upper limit for [qty]).
     * @property qty    How many units the buyer wants.
     */
    class Line(
        val productId: String,
        var name: String,
        var price: Double,
        var maxQty: Int,
        var qty: Int,
        var photoUrl: String = ""
    )

    /** Shop the cart belongs to, or null when the cart is empty. */
    var shopId: String? = null
        private set
    var shopName: String = ""
        private set

    /** The shop's base delivery fee (set by the seller); the final fee comes from [quote]. */
    var deliveryFee: Double = 0.0
        private set

    /** The shop's location (null if the seller hasn't saved it yet). */
    var shopLat: Double? = null
        private set
    var shopLng: Double? = null
        private set

    /** Where the order is delivered: the buyer's phone location at checkout (null until fetched). */
    var buyerLat: Double? = null
        private set
    var buyerLng: Double? = null
        private set

    /** [Order.METHOD_DELIVERY] or [Order.METHOD_PICKUP], chosen in the cart. */
    var deliveryMethod: String = Order.METHOD_DELIVERY

    /** Address typed in the cart (pre-filled from the profile); kept while the buyer switches tabs. */
    var deliveryAddress: String = ""

    // LinkedHashMap keeps the lines in the order the buyer added them.
    private val lines = LinkedHashMap<String, Line>()

    /** A snapshot of the cart lines (safe to hand to an adapter). */
    val items: List<Line> get() = lines.values.toList()

    fun isEmpty() = lines.isEmpty()

    /** Total number of units across all lines (used for the cart badge and the large-order fee). */
    fun totalCount() = lines.values.sumOf { it.qty }

    /** How many units of [productId] are already in the cart (0 if none). */
    fun qtyOf(productId: String) = lines[productId]?.qty ?: 0

    /** True when the cart already holds items from a different shop. */
    fun belongsToOtherShop(id: String) = lines.isNotEmpty() && shopId != id

    fun isPickup() = deliveryMethod == Order.METHOD_PICKUP

    /** Adds one unit. Returns false if the stock limit is already reached. */
    fun add(shop: Shop, product: Product): Boolean {
        shopId = shop.id
        updateShop(shop)
        val line = lines.getOrPut(product.id) { Line(product.id, product.name, product.price, product.quantity, 0, product.photoUrl) }
        // Refresh the line with the latest product data (the seller may have changed price or stock).
        line.name = product.name
        line.price = product.price
        line.maxQty = product.quantity
        line.photoUrl = product.photoUrl
        if (line.qty >= product.quantity) return false // client-side stock check
        line.qty++
        return true
    }

    /**
     * Refreshes the shop details the price depends on (name, base fee, location), e.g. when the
     * seller changed them after the buyer added items. Ignored if [shop] isn't the cart's shop.
     */
    fun updateShop(shop: Shop) {
        if (shop.id != shopId) return
        shopName = shop.name
        deliveryFee = shop.deliveryFee
        shopLat = shop.latitude
        shopLng = shop.longitude
    }

    /** Saves the delivery location (from the phone's location, never typed by the buyer). */
    fun setBuyerLocation(lat: Double, lng: Double) {
        buyerLat = lat
        buyerLng = lng
    }

    /** Adds one unit of a product already in the cart. Returns false if that would exceed the stock. */
    fun increment(productId: String): Boolean {
        val line = lines[productId] ?: return false
        if (line.qty >= line.maxQty) return false // client-side stock check
        line.qty++
        return true
    }

    /** Removes one unit; the line disappears at 0, and the cart forgets the shop when it becomes empty. */
    fun decrement(productId: String) {
        val line = lines[productId] ?: return
        line.qty--
        if (line.qty <= 0) lines.remove(productId)
        if (lines.isEmpty()) reset()
    }

    /** Empties the cart (after placing an order or when the buyer taps "Clear cart"). */
    fun clear() {
        lines.clear()
        reset()
    }

    /**
     * Lines asking for more than the known stock. Normally always empty thanks to [add]/[increment];
     * checked once more right before checkout as a safety net.
     */
    fun overStockLines(): List<Line> = lines.values.filter { it.qty > it.maxQty }

    // Forgets the shop; the delivery method and buyer location are kept on purpose.
    private fun reset() {
        shopId = null
        shopName = ""
        deliveryFee = 0.0
        shopLat = null
        shopLng = null
    }

    private fun round2(v: Double) = round(v * 100) / 100.0

    /** Price of the items only. */
    fun subtotal(): Double = round2(lines.values.sumOf { it.price * it.qty })

    /** Measured distance between the shop and the buyer, or null if either location is missing. */
    fun distanceKm(): Double? {
        val sLat = shopLat ?: return null
        val sLng = shopLng ?: return null
        val bLat = buyerLat ?: return null
        val bLng = buyerLng ?: return null
        return DeliveryPricing.distanceKm(sLat, sLng, bLat, bLng)
    }

    /**
     * Why delivery can't be priced right now, or null if it can (always null for pickup).
     * The cart shows this instead of a fee, and checkout is blocked until it is solved.
     */
    fun deliveryProblem(): String? {
        if (lines.isEmpty() || isPickup()) return null
        if (shopLat == null || shopLng == null)
            return "$shopName hasn't set its location yet, so delivery can't be priced. Choose pickup instead."
        val km = distanceKm()
            ?: return "Tap \"Use my current location\" so we can measure the distance to the shop."
        if (km > DeliveryPricing.MAX_DELIVERY_KM)
            return "You're ${formatKm(km)} from the shop. Delivery is only available within " +
                "${DeliveryPricing.MAX_DELIVERY_KM.toInt()} km. Choose pickup instead."
        return null
    }

    /** Delivery fee breakdown for the current cart (all zeros for pickup, an empty cart, or no distance yet). */
    fun quote(): DeliveryPricing.Quote {
        if (lines.isEmpty() || isPickup() || deliveryProblem() != null) return DeliveryPricing.FREE
        val km = distanceKm() ?: return DeliveryPricing.FREE
        return DeliveryPricing.quote(deliveryFee, km, totalCount(), subtotal())
    }

    /** Delivery fee the buyer pays. */
    fun fee(): Double = quote().total

    /** Items + delivery. */
    fun total(): Double = round2(subtotal() + fee())
}
