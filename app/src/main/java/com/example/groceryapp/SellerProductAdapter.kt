package com.example.groceryapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemProductSellerBinding

class SellerProductAdapter(
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : RecyclerView.Adapter<SellerProductAdapter.VH>() {

    private val items = mutableListOf<Product>()

    fun submitList(newItems: List<Product>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemProductSellerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProductSellerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val product = items[position]
        val b = holder.binding
        b.tvEmoji.text = productEmoji(product.name)
        b.tvName.text = product.name
        b.tvDetails.text = "EGP %.2f  ·  Qty: %d".format(product.price, product.quantity)
        if (product.inStock) {
            b.tvStock.text = "In Stock"
            b.tvStock.setTextColor(ContextCompat.getColor(b.root.context, R.color.green_primary))
            b.tvStock.setBackgroundResource(R.drawable.badge_in_stock)
        } else {
            b.tvStock.text = "Out of Stock"
            b.tvStock.setTextColor(ContextCompat.getColor(b.root.context, R.color.danger))
            b.tvStock.setBackgroundResource(R.drawable.badge_out_of_stock)
        }
        holder.binding.btnEdit.setOnClickListener { onEdit(product) }
        holder.binding.btnDelete.setOnClickListener { onDelete(product) }
    }

    override fun getItemCount() = items.size
}