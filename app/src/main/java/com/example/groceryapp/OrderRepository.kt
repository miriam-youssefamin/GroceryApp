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
 * - [accept] / [reject] change the status with a transaction (only a still-pending order can change);
 *   rejecting returns the items to stock.
 */
object OrderRepository {

    private val db get() = FirebaseDatabase.getInstance()

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
     * Places [order]: decrements the stock of every item (failing with a readable message such as
     * "Only 1 of "Kinder Cookie" left" if there isn't enough), then saves the order for the seller.
     */
    fun placeOrder(order: Order, onResult: (Result<Unit>) -> Unit) {
        val lines = order.items.entries.toList()
        val reserved = ArrayList<Pair<String, Int>>() // (productId, quantity) already taken from stock

        fun rollback() = reserved.forEach { (id, qty) -> adjustStock(id, qty) }

        fun fail(message: String) {
            rollback()
            onResult(Result.failure(IllegalStateException(message)))
        }

        fun writeOrder() {
            db.getReference("orders").push().setValue(order)
                .addOnSuccessListener { onResult(Result.success(Unit)) }
                .addOnFailureListener { fail(it.message ?: "Couldn't save the order") }
        }

        fun reserve(index: Int) {
            if (index == lines.size) {
                writeOrder()
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

    fun accept(order: Order, onResult: (Boolean) -> Unit) = changeStatus(order, Order.STATUS_ACCEPTED, onResult)

    /** Rejects a pending order and puts its items back into stock. */
    fun reject(order: Order, onResult: (Boolean) -> Unit) = changeStatus(order, Order.STATUS_REJECTED, onResult)

    private fun changeStatus(order: Order, newStatus: String, onResult: (Boolean) -> Unit) {
        db.getReference("orders").child(order.id).child("status")
            .runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    val current = data.getValue(String::class.java) ?: return Transaction.success(data)
                    if (current != Order.STATUS_PENDING) return Transaction.abort() // already handled
                    data.value = newStatus
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    val changed = error == null && committed && snapshot?.getValue(String::class.java) == newStatus
                    if (changed && newStatus == Order.STATUS_REJECTED) {
                        order.items.forEach { (productId, item) -> adjustStock(productId, item.quantity) }
                    }
                    onResult(changed)
                }
            })
    }
}
