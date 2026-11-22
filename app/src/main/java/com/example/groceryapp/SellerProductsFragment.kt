package com.example.groceryapp

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.groceryapp.databinding.FragmentSellerProductsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/** Seller "Products" tab: the seller's own items in a 2-column RecyclerView grid (photo + name), with edit/delete. */
class SellerProductsFragment : Fragment() {

    private var _binding: FragmentSellerProductsBinding? = null
    private val binding get() = _binding!!

    private val productsRef = FirebaseDatabase.getInstance().getReference("products")
    private var productsQuery: Query? = null
    private var shopRef: DatabaseReference? = null

    private val adapter = SellerProductAdapter(
        onEdit = { product -> (requireActivity() as SellerHomeActivity).openEditor(product.id) },
        onDelete = { product -> confirmDelete(product) }
    )

    private val shopListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val b = _binding ?: return
            b.tvShopMeta.text =
                if (snapshot.exists()) Shop.from(snapshot).metaLine() else "Setting up your shop…"
        }

        override fun onCancelled(error: DatabaseError) { /* ignore */ }
    }

    private val productsListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val b = _binding ?: return
            val list = snapshot.children.mapNotNull { child ->
                child.getValue(Product::class.java)?.apply { id = child.key ?: "" }
            }
            adapter.submitList(list)
            val inStock = list.count { it.inStock }
            b.tvStatTotal.text = list.size.toString()
            b.tvStatIn.text = inStock.toString()
            b.tvStatOut.text = (list.size - inStock).toString()
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCancelled(error: DatabaseError) {
            context?.let { Toast.makeText(it, error.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSellerProductsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvProducts.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvProducts.adapter = adapter

        binding.ivProfileIcon.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }
        binding.cardShopInfo.setOnClickListener {
            startActivity(Intent(requireContext(), ShopSettingsActivity::class.java))
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        productsQuery = productsRef.orderByChild("sellerId").equalTo(uid).also {
            it.addValueEventListener(productsListener)
        }
        shopRef = FirebaseDatabase.getInstance().getReference("shops").child(uid).also {
            it.addValueEventListener(shopListener)
        }
    }

    // Reload on every return so an edited name/photo shows up after coming back from Profile.
    override fun onStart() {
        super.onStart()
        val host = requireActivity() as AppCompatActivity
        host.loadUserName(binding.tvWelcome)
        host.loadHeaderPhoto(binding.ivProfileIcon)
    }

    /** Alert dialog before deleting a product; also deletes its photo from S3. */
    private fun confirmDelete(product: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete product")
            .setMessage("Remove \"${product.name}\" from your shop?")
            .setPositiveButton("Delete") { _, _ ->
                productsRef.child(product.id).removeValue().addOnSuccessListener {
                    // Keep S3 in sync: the photo of a deleted product is no longer needed.
                    if (product.photoUrl.isNotBlank()) S3Helper.deleteObject(product.photoUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(requireContext().getColor(R.color.danger))
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        productsQuery?.removeEventListener(productsListener)
        shopRef?.removeEventListener(shopListener)
        _binding = null
    }
}
