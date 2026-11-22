package com.example.groceryapp

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemCartBinding

/**
 * One row per cart line: photo (emoji fallback), name, unit price, quantity stepper and line total.
 * @param onChanged Called after +/- so the cart screen can update the totals and delivery fee.
 */
class CartAdapter(private val onChanged: () -> Unit) : RecyclerView.Adapter<CartAdapter.VH>() {

    private var items: List<CartManager.Line> = emptyList()

    /** Replaces the rows with a fresh snapshot of the cart. */
    fun submitList(list: List<CartManager.Line>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCartBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = items[position]
        val b = holder.binding
        b.tvEmoji.text = productEmoji(line.name)
        showRemoteImage(b.ivPhoto, b.tvEmoji, line.photoUrl)
        b.tvName.text = line.name
        b.tvUnit.text = "${formatMoney(line.price)} each"
        b.tvQty.text = line.qty.toString()
        b.tvLineTotal.text = formatMoney(line.price * line.qty)

        b.btnMinus.setOnClickListener {
            CartManager.decrement(line.productId)
            onChanged()
        }
        b.btnPlus.setOnClickListener {
            // Client-side stock check: refuse to go above the available quantity.
            if (!CartManager.increment(line.productId)) {
                Toast.makeText(b.root.context, "Only ${line.maxQty} available", Toast.LENGTH_SHORT).show()
            }
            onChanged()
        }
    }

    override fun getItemCount() = items.size
}
