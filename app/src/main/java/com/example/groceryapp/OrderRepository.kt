package com.example.groceryapp

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

/**
 * Order logic that has to stay consistent with the stock in Firebase.
 *
 * - [placeOrder] reserves stock first (one transaction per product, so two buyers can never take the
 *   same last item), and only then writes the order. If anything fails, the reserved stock is put back.
 * - [accept] / [reject] / [cancel] change the status with a transaction (only a still-pending order can change);
 *   rejecting or cancelling returns the items to stock. [undoCancel] takes the stock again.
 * - [advance] moves an accepted order through Preparing → Ready for pickup / Delivering → Delivered.
 *
 * Server-side quantity check: the stock is changed inside Firebase transactions, which Firebase runs
 * against the latest value on the server and retries if someone else changed it first. So even if
 * two phones pass the client-side check at the same time, only one of them can take the last item.
 * database.rules.json (project root) adds a second guard: quantity can never be saved below 0.
 */
object OrderRepository {

    private val db get() = FirebaseDatabase.getInstance()

    /** Firebase's error from the last failed status change (e.g. "Permission denied"), or null. */
    var lastStatusError: String? = null
        private set

    /** Adds [delta] (may be negative) to products/{id}/quantity; does nothing if the product no longer exists. */
    private fun adjustStock(productId: String, delta: Int) {
        db.getReference("products").child(productId).child("quantity")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    // null = value not known locally yet: return it unchanged so Firebase fetches the real one and retries.
                    val current = (data.value as? Number)?.toInt() ?: return Transaction.success(data)
                    data.value = (current + delta).coerceAtLeast(0)
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) { /* best effort */ }
            })
    }

    /**
     * Takes every item of [order] out of stock (one transaction per product), failing with a readable
     * message such as "Only 1 of "Kinder Cookie" left" if there isn't enough. On failure nothing stays taken.
     */
    private fun reserveStock(order: Order, onResult: (Result<Unit>) -> Unit) {
        val lines = order.items.entries.toList()
        val reserved = ArrayList<Pair<String, Int>>() // (productId, quantity) already taken from stock

        fun fail(message: String) {
            reserved.forEach { (id, qty) -> adjustStock(id, qty) } // roll back
            onResult(Result.failure(IllegalStateException(message)))
        }

        fun reserve(index: Int) {
            if (index == lines.size) {
                onResult(Result.success(Unit))
                return
            }
            val (productId, item) = lines[index]
            db.getReference("products").child(productId).child("quantity")
                .runTransaction(object : Transaction.Handler {
                    override fun doTransaction(data: MutableData): Transaction.Result {
                        val current = (data.value as? Number)?.toInt() ?: return Transaction.success(data)
                        if (current < item.quantity) return Transaction.abort() // not enough stock
                        data.value = current - item.quantity
                        return Transaction.success(data)
                    }

                    override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                        val left = (snapshot?.value as? Number)?.toInt()
                        when {
                            error != null -> fail(error.message)
                            committed && left == null -> fail("\"${item.name}\" is no longer available")
                            committed -> {
                                reserved.add(productId to item.quantity)
                                reserve(index + 1)
                            }
                            left == null || left <= 0 -> fail("\"${item.name}\" is out of stock")
                            else -> fail("Only $left of \"${item.name}\" left")
                        }
                    }
                })
        }

        if (lines.isEmpty()) fail("Your cart is empty") else reserve(0)
    }

    /** Puts every item of [order] back into stock. */
    private fun releaseStock(order: Order) =
        order.items.forEach { (productId, item) -> adjustStock(productId, item.quantity) }

    /**
     * Places [order]: reserves the stock, then saves the order for the seller.
     * On success returns the new order's id.
     */
    fun placeOrder(order: Order, onResult: (Result<String>) -> Unit) {
        reserveStock(order) { reserved ->
            reserved.onFailure { onResult(Result.failure(it)); return@reserveStock }
            val ref = db.getReference("orders").push()
            ref.setValue(order)
                .addOnSuccessListener { onResult(Result.success(ref.key ?: "")) }
                .addOnFailureListener {
                    releaseStock(order)
                    onResult(Result.failure(IllegalStateException(it.message ?: "Couldn't save the order")))
                }
        }
    }

    /** Buyer cancels a still-pending order; its items go back into stock. */
    fun cancel(order: Order, onResult: (Boolean) -> Unit) = changeStatus(order, Order.STATUS_CANCELLED, onResult = onResult)

    /**
     * Undoes [cancel]: takes the stock again, then puts the order back to pending.
     * Fails if the stock is gone or the order is no longer cancelled.
     */
    fun undoCancel(order: Order, onResult: (Boolean) -> Unit) {
        reserveStock(order) { reserved ->
            if (reserved.isFailure) { onResult(false); return@reserveStock }
            changeStatus(order, Order.STATUS_PENDING, from = Order.STATUS_CANCELLED) { changed ->
                if (!changed) releaseStock(order)
                onResult(changed)
            }
        }
    }

    /** Seller accepts a pending order: it moves to "Preparing". */
    fun accept(order: Order, onResult: (Boolean) -> Unit) = changeStatus(order, Order.STATUS_PREPARING, onResult = onResult)

    /**
     * Seller moves an accepted order one step forward:
     * Preparing → Ready for pickup / Delivering → Delivered (see [Order.nextStatus]).
     * The transaction only succeeds if the order is still in the status the seller saw,
     * so tapping the button twice can't skip a step.
     */
    fun advance(order: Order, onResult: (Boolean) -> Unit) {
        val next = order.nextStatus() ?: return onResult(false)
        changeStatus(order, next, from = order.status, onResult = onResult)
    }

    /** Rejects a pending order and puts its items back into stock. */
    fun reject(order: Order, onResult: (Boolean) -> Unit) = changeStatus(order, Order.STATUS_REJECTED, onResult = onResult)

    /** Changes the status from [from] to [newStatus]; rejecting/cancelling returns the items to stock. */
    private fun changeStatus(
        order: Order,
        newStatus: String,
        from: String = Order.STATUS_PENDING,
        onResult: (Boolean) -> Unit
    ) {
        db.getReference("orders").child(order.id).child("status")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    val current = data.getValue(String::class.java) ?: return Transaction.success(data)
                    if (current != from) return Transaction.abort() // already handled
                    data.value = newStatus
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    val changed = error == null && committed && snapshot?.getValue(String::class.java) == newStatus
                    lastStatusError = error?.message
                    if (changed && (newStatus == Order.STATUS_REJECTED || newStatus == Order.STATUS_CANCELLED)) {
                        releaseStock(order)
                    }
                    onResult(changed)
                }
            })
    }
}
