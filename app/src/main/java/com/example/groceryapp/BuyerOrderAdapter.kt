package com.example.groceryapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One card per buyer order: shop, time, items, delivery/pickup details, total, the current status
 * (Waiting → Preparing → Ready for pickup / Delivering → Delivered) and, while the seller hasn't
 * accepted it yet, a "Cancel order" button. Reuses item_order.xml from the seller list.
 */
class BuyerOrderAdapter(
    private val onCancel: (Order) -> Unit
) : RecyclerView.Adapter<BuyerOrderAdapter.VH>() {

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

        b.tvBuyer.text = order.shopName.ifBlank { "Shop" }
        b.tvTime.text = timeFormat.format(Date(order.timestamp))
        b.tvItems.text = order.items.values.joinToString("\n") { "${it.quantity} × ${it.name}" }
        bindDeliveryInfo(b.tvDelivery, order) // pickup, or delivery address + measured distance
        b.tvFee.text = feeLine(order)
        b.tvTotal.text = formatMoney(order.total)
        bindOrderStatus(b.tvStatus, order.status)

        // Only an order the seller hasn't handled yet can be cancelled.
        b.actionRow.visibility = if (order.status == Order.STATUS_PENDING) View.VISIBLE else View.GONE
        b.btnAccept.visibility = View.GONE
        b.btnReject.text = "Cancel order"
        b.btnReject.setOnClickListener { onCancel(order) }
    }

    override fun getItemCount() = items.size
}
