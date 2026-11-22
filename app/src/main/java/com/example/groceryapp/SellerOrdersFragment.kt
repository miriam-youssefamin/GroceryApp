package com.example.groceryapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.FragmentSellerOrdersBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Seller "Orders" tab: every order placed with this shop, live (Firebase listener), newest first.
 *
 * Pending orders get Accept / Reject; accepted orders get a button for the next step
 * (Preparing → Ready for pickup / Delivering → Delivered). All status changes go through
 * [OrderRepository], which uses transactions so an order can't be handled twice.
 */
class SellerOrdersFragment : Fragment() {

    private var _binding: FragmentSellerOrdersBinding? = null
    private val binding get() = _binding!!

    // Kept so the listener can be removed in onDestroyView (avoids leaks and updates to a dead view).
    private var ordersQuery: Query? = null

    private val adapter = SellerOrderAdapter(
        onAccept = { order ->
            OrderRepository.accept(order) { changed -> if (!changed) notifyAlreadyHandled() }
        },
        onReject = { order -> confirmReject(order) },
        // Preparing → Ready for pickup / Delivering → Delivered
        onAdvance = { order ->
            OrderRepository.advance(order) { changed -> if (!changed) notifyAlreadyHandled() }
        }
    )

    /** Rebuilds the list and the "N orders waiting" summary whenever any order of this shop changes. */
    private val ordersListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val b = _binding ?: return
            val orders = snapshot.children.mapNotNull { child ->
                child.getValue(Order::class.java)?.apply { id = child.key ?: "" }
            }.sortedByDescending { it.timestamp }
            adapter.submitList(orders)
            val waiting = orders.count { it.status == Order.STATUS_PENDING }
            b.tvSummary.text = when {
                orders.isEmpty() -> "Nothing yet"
                waiting == 0 -> "All caught up"
                waiting == 1 -> "1 order waiting for you"
                else -> "$waiting orders waiting for you"
            }
            b.tvEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onCancelled(error: DatabaseError) {
            context?.let { Toast.makeText(it, error.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSellerOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvOrders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOrders.adapter = adapter

        // The shop id is the seller's uid, so this query returns exactly this shop's orders.
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        ordersQuery = FirebaseDatabase.getInstance().getReference("orders")
            .orderByChild("shopId").equalTo(uid)
            .also { it.addValueEventListener(ordersListener) }
    }

    /** Alert dialog before rejecting (rejecting puts the items back into stock). */
    private fun confirmReject(order: Order) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Reject this order?")
            .setMessage("The items will go back into your stock.")
            .setPositiveButton("Reject") { _, _ ->
                OrderRepository.reject(order) { changed -> if (!changed) notifyAlreadyHandled() }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(ctx.getColor(R.color.danger))
                }
            }
            .show()
    }

    /**
     * The status change didn't go through. If Firebase sent an error (e.g. "Permission denied"
     * from the database rules), show it; otherwise someone (e.g. the buyer cancelling) changed
     * the order first.
     */
    private fun notifyAlreadyHandled() {
        val error = OrderRepository.lastStatusError
        val message = if (error != null) "Couldn't update the order: $error" else "This order was already handled"
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ordersQuery?.removeEventListener(ordersListener)
        _binding = null
    }
}
