package com.example.groceryapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.groceryapp.databinding.ItemReviewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.VH>() {

    private val items = mutableListOf<Review>()
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    fun submitList(newItems: List<Review>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemReviewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val b = holder.binding
        b.tvAvatar.text = r.userName.firstOrNull()?.uppercase() ?: "?"
        b.tvUser.text = r.userName.ifBlank { "Customer" }
        b.tvDate.text = if (r.timestamp > 0) dateFormat.format(Date(r.timestamp)) else ""
        b.tvStars.text = "★".repeat(r.rating.coerceIn(0, 5)) + "☆".repeat(5 - r.rating.coerceIn(0, 5))
        b.tvComment.text = r.comment
        b.tvComment.visibility = if (r.comment.isBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = items.size
}
