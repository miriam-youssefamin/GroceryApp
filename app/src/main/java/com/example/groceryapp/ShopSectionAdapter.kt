package com.example.groceryapp

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemShopSectionBinding

data class ShopSection(val shop: Shop, val products: List<Product>)

/** One row per shop: clickable shop header (name, rating, delivery info) above a horizontal product list. */
class ShopSectionAdapter(
    private val onShopClick: (Shop) -> Unit
) : RecyclerView.Adapter<ShopSectionAdapter.VH>() {

    private val items = mutableListOf<ShopSection>()
    private val sharedPool = RecyclerView.RecycledViewPool()
    private val photoCache = HashMap<String, Bitmap>()

    fun submitList(newItems: List<ShopSection>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemShopSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        val productAdapter: BuyerProductAdapter

        init {
            val width = (160 * binding.root.resources.displayMetrics.density).toInt()
            val openShop = {
                items.getOrNull(bindingAdapterPosition)?.let { onShopClick(it.shop) }
                Unit
            }
            productAdapter = BuyerProductAdapter(width) { openShop() }
            binding.rvShopProducts.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.rvShopProducts.adapter = productAdapter
            binding.rvShopProducts.setRecycledViewPool(sharedPool)
            binding.headerRow.setOnClickListener { openShop() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemShopSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val section = items[position]
        holder.binding.tvShopEmoji.text = section.shop.categoryInfo.emoji
        bindShopPhoto(holder, section.shop.photoUrl)
        holder.binding.tvShopName.text = section.shop.name
        holder.binding.tvShopMeta.text = section.shop.metaLine()
        holder.productAdapter.submitList(section.products)
    }

    /** Shows the seller's S3 photo in the shop tile; falls back to the category emoji until it loads (or if there is none). */
    private fun bindShopPhoto(holder: VH, photoUrl: String) {
        val photo = holder.binding.ivShopPhoto
        val emoji = holder.binding.tvShopEmoji
        photo.tag = photoUrl

        fun show(bitmap: Bitmap) {
            photo.setImageBitmap(bitmap)
            photo.visibility = View.VISIBLE
            emoji.visibility = View.GONE
        }

        photo.visibility = View.GONE
        emoji.visibility = View.VISIBLE
        if (photoUrl.isBlank() || !S3Helper.isConfigured()) return

        photoCache[photoUrl]?.let { show(it); return }
        S3Helper.downloadImage(photoUrl) { result ->
            result.onSuccess { bitmap ->
                photoCache[photoUrl] = bitmap
                if (photo.tag == photoUrl) show(bitmap)
            }
        }
    }

    override fun getItemCount() = items.size
}