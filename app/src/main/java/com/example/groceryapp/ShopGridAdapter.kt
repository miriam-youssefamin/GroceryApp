package com.example.groceryapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemShopCardBinding

/** Grid cell per shop: the shop's photo (S3, emoji fallback), its name and delivery/rating line. */
class ShopGridAdapter(
    private val onShopClick: (Shop) -> Unit
) : RecyclerView.Adapter<ShopGridAdapter.VH>() {

    private val items = mutableListOf<Shop>()

    /** Replaces the shops shown (called after filtering). */
    fun submitList(newItems: List<Shop>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemShopCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemShopCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val shop = items[position]
        val b = holder.binding
        b.tvShopEmoji.text = shop.categoryInfo.emoji
        showRemoteImage(b.ivShopPhoto, b.tvShopEmoji, shop.photoUrl)
        b.tvShopName.text = shop.name
        b.tvShopMeta.text = shop.metaLine()
        b.root.setOnClickListener { onShopClick(shop) }
    }

    override fun getItemCount() = items.size
}
