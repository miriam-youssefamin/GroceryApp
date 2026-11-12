package com.example.groceryapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.groceryapp.databinding.FragmentCartBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth

/** Buyer "Cart" tab: review the items, adjust quantities, see the total, clear the cart, or place the order. */
class CartFragment : Fragment() {

    private var _binding: FragmentCartBinding? = null
    private val binding get() = _binding!!
    private val adapter = CartAdapter { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvCart.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCart.adapter = adapter
        binding.btnBrowse.setOnClickListener {
            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_shops)
        }
        binding.tvClearCart.setOnClickListener { confirmClear() }
        binding.btnPlaceOrder.setOnClickListener { placeOrder() }
        render()
    }

    private fun render() {
        val b = _binding ?: return
        val items = CartManager.items
        val empty = items.isEmpty()
        adapter.submitList(items)

        b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.rvCart.visibility = if (empty) View.GONE else View.VISIBLE
        b.cardSummary.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvClearCart.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvCartShop.text = if (empty) "Nothing here yet" else "From ${CartManager.shopName}"

        b.tvSubtotal.text = formatMoney(CartManager.subtotal())
        b.tvFee.text = if (CartManager.fee() <= 0.0) "Free" else formatMoney(CartManager.fee())
        b.tvTotal.text = formatMoney(CartManager.total())
    }

    /** Asks for confirmation, then empties the cart. */
    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear cart?")
            .setMessage("Remove all items from your cart?")
            .setPositiveButton("Clear") { _, _ ->
                CartManager.clear()
                render()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setTextColor(requireContext().getColor(R.color.danger))
                }
            }
            .show()
    }

    private fun placeOrder() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val shopId = CartManager.shopId
        if (uid == null || shopId == null || CartManager.isEmpty()) return

        val ctx = requireContext()
        binding.btnPlaceOrder.isEnabled = false
        fetchUserName { name ->
            val total = CartManager.total()
            val order = Order(
                buyerId = uid,
                buyerName = name,
                shopId = shopId,
                shopName = CartManager.shopName,
                items = CartManager.items.associate {
                    it.productId to OrderItem(name = it.name, price = it.price, quantity = it.qty)
                },
                subtotal = CartManager.subtotal(),
                deliveryFee = CartManager.fee(),
                total = total,
                status = Order.STATUS_PENDING,
                timestamp = System.currentTimeMillis()
            )
            // Reserves the stock first, then saves the order for the seller (see OrderRepository).
            OrderRepository.placeOrder(order) { result ->
                result.onSuccess {
                    CartManager.clear()
                    if (!isAdded) return@onSuccess
                    render()
                    _binding?.btnPlaceOrder?.isEnabled = true
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Order placed 🎉")
                        .setMessage("Total: ${formatMoney(total)}\nPay cash on delivery when your order arrives.")
                        .setCancelable(false)
                        .setPositiveButton("Done") { _, _ ->
                            (requireActivity() as BuyerHomeActivity).selectTab(R.id.nav_shops)
                        }
                        .show()
                }.onFailure { e ->
                    _binding?.btnPlaceOrder?.isEnabled = true
                    if (isAdded) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Couldn't place the order")
                            .setMessage((e.message ?: "Something went wrong") +
                                    "\n\nPlease adjust your cart and try again.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(ctx, e.message ?: "Couldn't place the order", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
