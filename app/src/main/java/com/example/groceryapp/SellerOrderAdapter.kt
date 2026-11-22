package com.example.groceryapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One card per incoming order (seller side): buyer, time, items, delivery/pickup details, total,
 * a status badge and the button(s) for the next step:
 *
 *  - Waiting for approval → [Reject] [Accept]
 *  - Preparing            → [Ready for pickup] or [Out for delivery]
 *  - Ready / Delivering   → [Mark as picked up] or [Mark as delivered]
 *  - Finished orders      → no buttons
 *
 * @param onAccept  Called when the seller accepts a pending order.
 * @param onReject  Called when the seller rejects a pending order.
 * @param onAdvance Called when the seller moves an accepted order to its next status.
 */
class SellerOrderAdapter(
    private val onAccept: (Order) -> Unit,
    private val onReject: (Order) -> Unit,
    private val onAdvance: (Order) -> Unit
) : RecyclerView.Adapter<SellerOrderAdapter.VH>() {

    private val items = mutableListOf<Order>()
    private val timeFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    /** Replaces the list (called on every Firebase update). */
    fun submitList(newItems: List<Order>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val order = items[position]
        val b = holder.binding

        b.tvBuyer.text = order.buyerName.ifBlank { "Customer" }
        b.tvTime.text = timeFormat.format(Date(order.timestamp))
        b.tvItems.text = order.items.values.joinToString("\n") { "${it.quantity} × ${it.name}" }
        bindDeliveryInfo(b.tvDelivery, order, openMap = true) // seller can open the delivery spot in Maps
        b.tvFee.text = feeLine(order)
        b.tvTotal.text = formatMoney(order.total)
        bindOrderStatus(b.tvStatus, order.status)

        // Views are recycled, so every branch sets the visibility of both buttons explicitly.
        val next = order.nextStatus()
        when {
            order.status == Order.STATUS_PENDING -> {
                b.actionRow.visibility = View.VISIBLE
                b.btnReject.visibility = View.VISIBLE
                b.btnAccept.visibility = View.VISIBLE
                b.btnAccept.text = "Accept"
                b.btnAccept.setOnClickListener { onAccept(order) }
                b.btnReject.setOnClickListener { onReject(order) }
            }
            next != null -> {
                b.actionRow.visibility = View.VISIBLE
                b.btnReject.visibility = View.GONE
                b.btnAccept.visibility = View.VISIBLE
                b.btnAccept.text = nextStepLabel(next)
                b.btnAccept.setOnClickListener { onAdvance(order) }
            }
            else -> b.actionRow.visibility = View.GONE // delivered / rejected / cancelled
        }
    }

    override fun getItemCount() = items.size

    /** Button text for moving the order to [next]. */
    private fun nextStepLabel(next: String) = when (next) {
        Order.STATUS_READY -> "Ready for pickup"
        Order.STATUS_DELIVERING -> "Out for delivery"
        else -> "Mark as completed"
    }
}

/** "Subtotal EGP 120.00 · Delivery EGP 25.00" (or "· Pickup" for pickup orders). */
fun feeLine(order: Order): String {
    val delivery = when {
        order.isPickup() -> "Pickup"
        order.deliveryFee <= 0.0 -> "Delivery free"
        else -> "Delivery ${formatMoney(order.deliveryFee)}"
    }
    return "Subtotal ${formatMoney(order.subtotal)} · $delivery"
}

/**
 * Shows how the order gets to the buyer: "🏪 Pickup from the shop" or "🛵 Delivery to <address> (4.3 km)".
 * With [openMap], tapping a delivery order opens the exact delivery spot in a maps app.
 */
fun bindDeliveryInfo(tv: TextView, order: Order, openMap: Boolean = false) {
    tv.setOnClickListener(null)
    tv.isClickable = false
    if (order.isPickup()) {
        tv.text = "🏪 Pickup from the shop"
        return
    }
    val distance = when {
        order.distanceKm > 0 -> " (${formatKm(order.distanceKm)})"
        order.distanceZone.isNotBlank() -> " (${legacyZoneLabel(order.distanceZone)})" // older orders
        else -> ""
    }
    val address = order.deliveryAddress.ifBlank { "address not given" }
    val lat = order.deliveryLat
    val lng = order.deliveryLng
    if (openMap && lat != null && lng != null) {
        tv.text = "🛵 Delivery to $address$distance\n📍 Tap to open the location in Maps"
        tv.setOnClickListener {
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Delivery)")
            try {
                tv.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                android.widget.Toast.makeText(tv.context, "No maps app installed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        tv.text = "🛵 Delivery to $address$distance"
    }
}

/** Labels of the distance zones buyers picked before the distance was measured (older orders only). */
private fun legacyZoneLabel(key: String): String = when (key) {
    "near" -> "Under 2 km"
    "medium" -> "2–5 km"
    "far" -> "5–10 km"
    else -> key
}

/** Human-readable name of a status, as shown on the badge and in notifications. */
fun statusLabel(status: String): String = when (status) {
    Order.STATUS_PENDING -> "Waiting for approval"
    Order.STATUS_PREPARING, Order.STATUS_ACCEPTED -> "Preparing"
    Order.STATUS_READY -> "Ready for pickup"
    Order.STATUS_DELIVERING -> "Delivering"
    Order.STATUS_DELIVERED -> "Delivered"
    Order.STATUS_REJECTED -> "Rejected"
    Order.STATUS_CANCELLED -> "Cancelled"
    else -> status
}

/**
 * Shows an order status as a coloured badge (shared by the seller and buyer order lists):
 * orange = waiting / in progress, green = ready or finished, red = rejected / cancelled.
 */
fun bindOrderStatus(tv: TextView, status: String) {
    val ctx = tv.context
    tv.text = statusLabel(status)
    when (status) {
        Order.STATUS_READY, Order.STATUS_DELIVERING, Order.STATUS_DELIVERED -> {
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.green_primary))
            tv.setBackgroundResource(R.drawable.badge_in_stock)
        }
        Order.STATUS_REJECTED, Order.STATUS_CANCELLED -> {
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
            tv.setBackgroundResource(R.drawable.badge_out_of_stock)
        }
        else -> { // pending, preparing
            tv.setTextColor(0xFFE65100.toInt())
            tv.setBackgroundResource(R.drawable.badge_pending)
        }
    }
}
