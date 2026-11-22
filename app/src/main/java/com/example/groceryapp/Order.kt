package com.example.groceryapp

import com.google.firebase.database.Exclude

/**
 * One product line of an order, stored at orders/{orderId}/items/{productId}.
 *
 * The name and price are copied into the order when it is placed, so the order
 * keeps showing what the buyer actually paid even if the seller later edits the product.
 */
data class OrderItem(
    val name: String = "",
    val price: Double = 0.0,
    val quantity: Int = 0
)

/**
 * An order placed by a buyer, stored at orders/{orderId}.
 *
 * Status flow (Milestone 4, "Checkout Constraint Implementation"):
 *
 *     pending ──accept──► preparing ──► ready (pickup) ──────► delivered
 *        │                         └──► delivering (delivery) ─► delivered
 *        ├──reject (seller)──► rejected   (items go back to stock)
 *        └──cancel (buyer) ──► cancelled  (items go back to stock)
 *
 * Only the seller moves the order forward ([nextStatus]); a buyer can only cancel while it is pending.
 *
 * Firebase maps every public property/getter to a JSON field, so the helper logic below is written
 * as functions (or marked with @Exclude) to keep it out of the database.
 *
 * @property id              Firebase push key (not stored inside the node itself).
 * @property items           Product lines keyed by product id.
 * @property deliveryMethod  [METHOD_DELIVERY] or [METHOD_PICKUP].
 * @property deliveryAddress Where to deliver (empty for pickup).
 * @property distanceKm      Measured shop → delivery-location distance used for the fee (0 for pickup).
 * @property deliveryLat     Delivery location (buyer's phone location at checkout); null for pickup.
 * @property deliveryLng     See [deliveryLat].
 * @property distanceZone    Old field: the zone the buyer used to pick. Only read for older orders.
 * @property deliveryFee     Final fee calculated by [DeliveryPricing] at checkout (0 for pickup).
 * @property timestamp       When the order was placed (epoch millis).
 */
data class Order(
    @get:Exclude var id: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val items: Map<String, OrderItem> = emptyMap(),
    val subtotal: Double = 0.0,
    val deliveryMethod: String = METHOD_DELIVERY,
    val deliveryAddress: String = "",
    val distanceKm: Double = 0.0,
    val deliveryLat: Double? = null,
    val deliveryLng: Double? = null,
    val distanceZone: String = "",
    val deliveryFee: Double = 0.0,
    val total: Double = 0.0,
    val status: String = STATUS_PENDING,
    val timestamp: Long = 0L
) {

    /**
     * True when the buyer chose to collect the order from the shop.
     * @Exclude: Firebase would otherwise save "isPickup()" as an extra "pickup" field.
     */
    @Exclude
    fun isPickup(): Boolean = deliveryMethod == METHOD_PICKUP

    /**
     * The status the seller can move this order to next, or null if the order is finished
     * (delivered / rejected / cancelled) or still needs Accept/Reject (pending).
     */
    fun nextStatus(): String? = when (status) {
        // "accepted" is the old Milestone 3 status: treat it like "preparing" so old orders still work.
        STATUS_PREPARING, STATUS_ACCEPTED -> if (isPickup()) STATUS_READY else STATUS_DELIVERING
        STATUS_READY, STATUS_DELIVERING -> STATUS_DELIVERED
        else -> null
    }

    companion object {
        // ---- Status values (stored as plain strings in Firebase) ----
        const val STATUS_PENDING = "pending"       // Waiting for seller approval
        const val STATUS_PREPARING = "preparing"   // Seller accepted, packing the items
        const val STATUS_READY = "ready"           // Ready for pickup (pickup orders)
        const val STATUS_DELIVERING = "delivering" // On the way (delivery orders)
        const val STATUS_DELIVERED = "delivered"   // Finished
        const val STATUS_REJECTED = "rejected"     // Seller refused it
        const val STATUS_CANCELLED = "cancelled"   // Buyer cancelled it

        /** Milestone 3 value for an accepted order; kept only so older orders still display. */
        const val STATUS_ACCEPTED = "accepted"

        // ---- Delivery methods ----
        const val METHOD_DELIVERY = "delivery"
        const val METHOD_PICKUP = "pickup"
    }
}
