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
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

/**
 * Buyer "Orders" tab: the buyer's orders, live, newest first.
 * Cancel -> "Are you sure?" dialog (Yes / No) -> "Order is cancelled" Snackbar with UNDO.
 * Reuses the seller orders layout (same list screen, different title).
 */
class MyOrdersFragment : Fragment() {

    private var _binding: FragmentSellerOrdersBinding? = null
    private val binding get() = _binding!!

    private var ordersQuery: Query? = null
    private val adapter = BuyerOrderAdapter(onCancel = { confirmCancel(it) })

    private val ordersListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val b = _binding ?: return
            val orders = snapshot.children.mapNotNull { child ->
                child.getValue(Order::class.java)?.apply { id = child.key ?: "" }
            }.sortedByDescending { it.timestamp }
            adapter.submitList(orders)
            b.tvSummary.text = if (orders.isEmpty()) "Nothing yet" else "${orders.size} order(s)"
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
        binding.tvTitle.text = "My orders"
        binding.tvEmpty.text = "No orders yet.\nOrders you place show up here."
        binding.rvOrders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOrders.adapter = adapter

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        ordersQuery = FirebaseDatabase.getInstance().getReference("orders")
            .orderByChild("buyerId").equalTo(uid)
            .also { it.addValueEventListener(ordersListener) }
    }

    /** Dialog: "Are you sure?" -> Yes cancels the order, No does nothing. */
    private fun confirmCancel(order: Order) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cancel order")
            .setMessage("Are you sure you want to cancel this order?")
            .setPositiveButton("Yes") { _, _ -> cancelOrder(order) }
            .setNegativeButton("No", null)
            .show()
    }

    /** Cancels via a transaction (fails if the seller already accepted it); items go back to stock. */
    private fun cancelOrder(order: Order) {
        OrderRepository.cancel(order) { changed ->
            if (!changed) {
                val error = OrderRepository.lastStatusError
                toast(if (error != null) "Couldn't cancel: $error" else "This order was already handled by the seller")
                return@cancel
            }
            showUndoSnackbar(order)
        }
    }

    /** Snackbar: "Order is cancelled" with UNDO, which puts the order back. */
    private fun showUndoSnackbar(order: Order) {
        val root = view ?: return
        Snackbar.make(root, "Order is cancelled", Snackbar.LENGTH_LONG)
            .setAnchorView(requireActivity().findViewById(R.id.bottomNav))
            .setAction("UNDO") {
                OrderRepository.undoCancel(order) { restored ->
                    if (restored) {
                        toast("Order restored")
                    } else {
                        toast("Couldn't restore the order (items may be out of stock)")
                    }
                }
            }
            .show()
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ordersQuery?.removeEventListener(ordersListener)
        _binding = null
    }
}
