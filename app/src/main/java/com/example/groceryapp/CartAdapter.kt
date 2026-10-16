package com.example.groceryapp

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemCartBinding

class CartAdapter(private val onChanged: () -> Unit) : RecyclerView.Adapter<CartAdapter.VH>() {

    private var items: List<CartManager.Line> = emptyList()

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
        b.tvName.text = line.name
        b.tvUnit.text = "${formatMoney(line.price)} each"
        b.tvQty.text = line.qty.toString()
        b.tvLineTotal.text = formatMoney(line.price * line.qty)

        b.btnMinus.setOnClickListener {
            CartManager.decrement(line.productId)
            onChanged()
        }
        b.btnPlus.setOnClickListener {
            if (!CartManager.increment(line.productId)) {
                Toast.makeText(b.root.context, "Only ${line.maxQty} available", Toast.LENGTH_SHORT).show()
            }
            onChanged()
        }
    }

    override fun getItemCount() = items.size
}
