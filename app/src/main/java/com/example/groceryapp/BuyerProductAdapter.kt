package com.example.groceryapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemProductBuyerBinding

class BuyerProductAdapter(
    private val itemWidthPx: Int? = null,
    private val onClick: ((Product) -> Unit)? = null,
    private val cartControls: Boolean = false,
    private val onAdd: ((Product) -> Unit)? = null,
    private val onRemove: ((Product) -> Unit)? = null
) : RecyclerView.Adapter<BuyerProductAdapter.VH>() {

    private var all: List<Product> = emptyList()
    private val items = mutableListOf<Product>()
    private var query = ""
    private var inStockOnly = false

    /** Number of products before search/filter is applied. */
    val totalCount: Int get() = all.size

    fun submitList(newItems: List<Product>) {
        all = newItems
        applyFilters()
    }

    fun setQuery(q: String) {
        query = q.trim()
        applyFilters()
    }

    fun setInStockOnly(value: Boolean) {
        inStockOnly = value
        applyFilters()
    }

    /** Re-draws the cards (e.g. after the cart changed). */
    fun refresh() = notifyDataSetChanged()

    private fun applyFilters() {
        items.clear()
        items.addAll(all.filter {
            (!inStockOnly || it.inStock) && (query.isEmpty() || it.name.contains(query, ignoreCase = true))
        })
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemProductBuyerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProductBuyerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (itemWidthPx != null) {
            (binding.root.layoutParams as ViewGroup.MarginLayoutParams).width = itemWidthPx
        }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val product = items[position]
        val b = holder.binding
        b.tvEmoji.text = productEmoji(product.name)
        b.tvName.text = product.name
        b.tvPrice.text = "EGP %.2f".format(product.price)
        b.root.setOnClickListener { onClick?.invoke(product) }
        b.root.alpha = if (product.inStock) 1f else 0.6f

        if (cartControls && product.inStock) {
            val qty = CartManager.qtyOf(product.id)
            b.btnAdd.visibility = if (qty == 0) View.VISIBLE else View.GONE
            b.stepper.visibility = if (qty == 0) View.GONE else View.VISIBLE
            b.tvQty.text = qty.toString()
            b.btnAdd.setOnClickListener { onAdd?.invoke(product) }
            b.btnPlus.setOnClickListener { onAdd?.invoke(product) }
            b.btnMinus.setOnClickListener { onRemove?.invoke(product) }
        } else {
            b.btnAdd.visibility = View.GONE
            b.stepper.visibility = View.GONE
        }

        if (product.inStock) {
            b.tvStock.text = "In Stock"
            b.tvStock.setTextColor(ContextCompat.getColor(b.root.context, R.color.green_primary))
            b.tvStock.setBackgroundResource(R.drawable.badge_in_stock)
        } else {
            b.tvStock.text = "Out of Stock"
            b.tvStock.setTextColor(ContextCompat.getColor(b.root.context, R.color.danger))
            b.tvStock.setBackgroundResource(R.drawable.badge_out_of_stock)
        }
    }

    override fun getItemCount(): Int = items.size
}
