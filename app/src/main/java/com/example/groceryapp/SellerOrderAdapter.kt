package com.example.groceryapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One card per incoming order: buyer, time, items, total and (while pending) Accept / Reject buttons. */
class SellerOrderAdapter(
    private val onAccept: (Order) -> Unit,
    private val onReject: (Order) -> Unit
) : RecyclerView.Adapter<SellerOrderAdapter.VH>() {

    private val items = mutableListOf<Order>()
    private val timeFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

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
        val ctx = b.root.context

        b.tvBuyer.text = order.buyerName.ifBlank { "Customer" }
        b.tvTime.text = timeFormat.format(Date(order.timestamp))
        b.tvItems.text = order.items.values.joinToString("\n") { "${it.quantity} × ${it.name}" }
        b.tvFee.text = "Subtotal ${formatMoney(order.subtotal)} · Delivery " +
                if (order.deliveryFee <= 0.0) "free" else formatMoney(order.deliveryFee)
        b.tvTotal.text = formatMoney(order.total)

        when (order.status) {
            Order.STATUS_ACCEPTED -> {
                b.tvStatus.text = "Accepted"
                b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.green_primary))
                b.tvStatus.setBackgroundResource(R.drawable.badge_in_stock)
            }
            Order.STATUS_REJECTED -> {
                b.tvStatus.text = "Rejected"
                b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
                b.tvStatus.setBackgroundResource(R.drawable.badge_out_of_stock)
            }
            else -> {
                b.tvStatus.text = "Waiting for approval"
                b.tvStatus.setTextColor(0xFFE65100.toInt())
                b.tvStatus.setBackgroundResource(R.drawable.badge_pending)
            }
        }

        val pending = order.status == Order.STATUS_PENDING
        b.actionRow.visibility = if (pending) View.VISIBLE else View.GONE
        b.btnAccept.setOnClickListener { onAccept(order) }
        b.btnReject.setOnClickListener { onReject(order) }
    }

    override fun getItemCount() = items.size
}
