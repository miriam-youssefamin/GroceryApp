package com.example.groceryapp

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.DialogReviewBinding
import com.example.groceryapp.databinding.FragmentProductsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Buyer "Products" screen for one shop: delivery info, rating, every product of the shop in a 2-column
 * RecyclerView grid (photo + name + price) with add-to-cart and +/- quantity controls, and the shop's reviews.
 */
class ProductsFragment : Fragment() {

    private var _binding: FragmentProductsBinding? = null
    private val binding get() = _binding!!

    private lateinit var shopId: String
    private lateinit var shopRef: DatabaseReference
    private lateinit var productsQuery: Query

    private val productAdapter = BuyerProductAdapter(
        cartControls = true,
        onAdd = { addToCart(it) },
        onRemove = { CartManager.decrement(it.id); refreshCart() }
    )
    private val reviewAdapter = ReviewAdapter()
    private var currentShop: Shop? = null
    private val myUid get() = FirebaseAuth.getInstance().currentUser?.uid

    private val shopListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val shop = if (snapshot.exists()) Shop.from(snapshot) else Shop(id = shopId)
            currentShop = shop
            bindShop(shop)
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    private val productsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val b = _binding ?: return
            val list = snapshot.children.mapNotNull { child ->
                child.getValue(Product::class.java)?.apply { id = child.key ?: "" }
            }
            productAdapter.submitList(list)
            b.tvNoProducts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shopId = requireArguments().getString(Roles.EXTRA_SHOP_ID).orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProductsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val db = FirebaseDatabase.getInstance()
        shopRef = db.getReference("shops").child(shopId)
        productsQuery = db.getReference("products").orderByChild("sellerId").equalTo(shopId)

        binding.ivBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.rvProducts.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvProducts.adapter = productAdapter
        binding.rvReviews.layoutManager = LinearLayoutManager(requireContext())
        binding.rvReviews.adapter = reviewAdapter
        binding.btnWriteReview.setOnClickListener { showReviewDialog() }
        binding.cardCartBar.setOnClickListener {
            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_cart)
        }

        shopRef.addValueEventListener(shopListener)
        productsQuery.addValueEventListener(productsListener)
    }

    override fun onResume() {
        super.onResume()
        refreshCart()
    }

    /**
     * Adds one unit to the cart. If the cart holds another shop's items, an alert dialog asks
     * whether to start a new cart (one shop per cart). Shows a toast when the stock limit is reached.
     */
    private fun addToCart(product: Product) {
        val shop = currentShop ?: return
        if (CartManager.belongsToOtherShop(shop.id)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Start a new cart?")
                .setMessage("Your cart has items from ${CartManager.shopName}. Clear it and add from ${shop.name}?")
                .setPositiveButton("Start new cart") { _, _ ->
                    CartManager.clear()
                    addToCart(product)
                }
                .setNegativeButton("Keep current cart", null)
                .show()
            return
        }
        if (!CartManager.add(shop, product)) {
            Toast.makeText(requireContext(), "Only ${product.quantity} available", Toast.LENGTH_SHORT).show()
        }
        refreshCart()
    }

    /** Updates the +/- counters on the cards and the floating "View cart" bar. */
    private fun refreshCart() {
        val b = _binding ?: return
        productAdapter.refresh()
        val count = CartManager.totalCount()
        b.cardCartBar.visibility = if (count == 0) View.GONE else View.VISIBLE
        b.tvCartInfo.text = if (count == 1) "View cart · 1 item" else "View cart · $count items"
        b.tvCartTotal.text = formatMoney(CartManager.subtotal())
    }

    /** Fills the header with the shop's category, name, rating and delivery info. */
    private fun bindShop(shop: Shop) {
        val b = _binding ?: return
        b.tvShopCategory.text = "${shop.categoryInfo.emoji} ${shop.categoryInfo.label}"
        b.tvShopName.text = shop.name
        b.tvRating.text = if (shop.ratingCount == 0) "New" else "★ %.1f".format(shop.avgRating)
        b.tvRatingSub.text = when (shop.ratingCount) {
            0 -> "No reviews yet"
            1 -> "1 review"
            else -> "${shop.ratingCount} reviews"
        }
        b.tvTime.text = "${shop.deliveryMinutes} min"
        b.tvFee.text = shop.feeText()

        val sorted = shop.reviews.sortedByDescending { it.timestamp }
        reviewAdapter.submitList(sorted)
        b.tvNoReviews.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        b.btnWriteReview.text =
            if (shop.reviews.any { it.userId == myUid }) "Edit your review" else "Write a review"
    }

    /** Alert dialog with a star rating and comment; saved to shops/{shopId}/reviews/{buyerUid}. */
    private fun showReviewDialog() {
        val uid = myUid ?: return
        val ctx = requireContext()
        val existing = currentShop?.reviews?.firstOrNull { it.userId == uid }
        val d = DialogReviewBinding.inflate(layoutInflater)
        d.ratingBar.rating = existing?.rating?.toFloat() ?: 0f
        d.etComment.setText(existing?.comment ?: "")

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Rate ${currentShop?.name ?: "this shop"}")
            .setView(d.root)
            .setPositiveButton("Submit", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply {
                setTextColor(ctx.getColor(R.color.green_primary))
                setOnClickListener {
                    val rating = d.ratingBar.rating.toInt()
                    if (rating < 1) {
                        Toast.makeText(ctx, "Please choose a star rating", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    isEnabled = false
                    val comment = d.etComment.text.toString().trim()
                    fetchUserName { name ->
                        val review = Review(rating = rating, comment = comment, userName = name,
                            timestamp = System.currentTimeMillis())
                        shopRef.child("reviews").child(uid).setValue(review)
                            .addOnSuccessListener {
                                dialog.dismiss()
                                Toast.makeText(ctx, "Thanks for your review!", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener {
                                isEnabled = true
                                Toast.makeText(ctx, it.message ?: "Couldn't save review", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                .setTextColor(ctx.getColor(R.color.text_secondary))
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::shopRef.isInitialized) shopRef.removeEventListener(shopListener)
        if (::productsQuery.isInitialized) productsQuery.removeEventListener(productsListener)
        _binding = null
    }

    companion object {
        fun newInstance(shopId: String) = ProductsFragment().apply {
            arguments = Bundle().apply { putString(Roles.EXTRA_SHOP_ID, shopId) }
        }
    }
}
