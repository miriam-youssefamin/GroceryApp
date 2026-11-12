package com.example.groceryapp

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.example.groceryapp.databinding.FragmentAddEditProductBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File

/**
 * Seller "Add" tab, also used to edit an existing item (opened with a product id).
 * The product photo can come from the camera or the gallery; it is compressed (< 0.5 MB) and uploaded to
 * S3 (product_photos/{sellerUid}/...), and its S3 URL is saved on the product in Firebase.
 */
class AddEditProductFragment : Fragment() {

    private var _binding: FragmentAddEditProductBinding? = null
    private val binding get() = _binding!!

    private val productsRef = FirebaseDatabase.getInstance().getReference("products")
    private var editingId: String? = null
    private var existingPhotoUrl = ""
    private var pendingUri: Uri? = null   // newly chosen photo, not uploaded yet
    private var cameraUri: Uri? = null    // where the camera app writes its picture

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) setPicked(uri)
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) cameraUri?.let { setPicked(it) }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = arguments?.getString(Roles.EXTRA_PRODUCT_ID)
        existingPhotoUrl = savedInstanceState?.getString(STATE_EXISTING_PHOTO) ?: ""
        pendingUri = savedInstanceState?.getParcelable(STATE_PENDING)
        cameraUri = savedInstanceState?.getParcelable(STATE_CAMERA)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddEditProductBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listOf(binding.tilName, binding.tilPrice, binding.tilQuantity).forEach { it.clearErrorOnType() }

        binding.photoFrame.setOnClickListener { pickFromGallery() }
        binding.btnGallery.setOnClickListener { pickFromGallery() }
        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnSave.setOnClickListener { save() }

        val id = editingId
        if (id != null) {
            binding.tvHeading.text = "Edit product"
            binding.tvSubtitle.text = "Update the product details"
            if (savedInstanceState == null) loadExisting(id)
        }

        // Re-show a photo chosen before a rotation / process restart.
        pendingUri?.let { showPreview(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXISTING_PHOTO, existingPhotoUrl)
        outState.putParcelable(STATE_PENDING, pendingUri)
        outState.putParcelable(STATE_CAMERA, cameraUri)
    }

    // ---- photo ----

    private fun pickFromGallery() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "product_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No camera app available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun setPicked(uri: Uri) {
        if (showPreview(uri)) {
            pendingUri = uri
        } else {
            Toast.makeText(requireContext(), "Couldn't read that image", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows a downscaled preview of [uri]; returns false if the image can't be decoded. */
    private fun showPreview(uri: Uri): Boolean {
        val b = _binding ?: return false
        val bitmap = runCatching { ImageUtils.decodeBitmap(requireContext(), uri, 800) }.getOrNull() ?: return false
        b.ivPreview.tag = null // ignore a late-arriving download of the old S3 photo
        b.ivPreview.setImageBitmap(bitmap)
        b.ivPreview.visibility = View.VISIBLE
        b.tvPhotoHint.visibility = View.GONE
        return true
    }

    // ---- load / save ----

    private fun loadExisting(id: String) {
        productsRef.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val b = _binding ?: return
                val product = snapshot.getValue(Product::class.java) ?: return
                b.etName.setText(product.name)
                b.etPrice.setText(product.price.toString())
                b.etQuantity.setText(product.quantity.toString())
                existingPhotoUrl = product.photoUrl
                if (pendingUri == null && product.photoUrl.isNotBlank()) {
                    b.ivPreview.visibility = View.GONE
                    showRemoteImage(b.ivPreview, b.tvPhotoHint, product.photoUrl)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                context?.let { Toast.makeText(it, error.message, Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun save() {
        val b = binding
        val name = b.etName.text.toString().trim()
        val priceText = b.etPrice.text.toString().trim()
        val qtyText = b.etQuantity.text.toString().trim()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val ctx = requireContext().applicationContext

        if (name.isEmpty()) { b.tilName.error = "Enter a product name"; return }
        val price = priceText.toDoubleOrNull()
        if (price == null || price < 0) { b.tilPrice.error = "Enter a valid price"; return }
        val quantity = qtyText.toIntOrNull()
        if (quantity == null || quantity < 0) { b.tilQuantity.error = "Enter a valid quantity"; return }
        if (uid == null) { Toast.makeText(ctx, "Not signed in", Toast.LENGTH_LONG).show(); return }

        val photo = pendingUri
        if (photo == null) {
            // No new picture: keep whatever photo the product already has.
            b.btnSave.isEnabled = false
            writeProduct(name, price, quantity, uid, existingPhotoUrl, newUpload = false)
            return
        }
        if (!S3Helper.isConfigured()) {
            Toast.makeText(ctx, "AWS S3 isn't configured (aws.properties)", Toast.LENGTH_LONG).show()
            return
        }

        b.btnSave.isEnabled = false
        b.btnSave.text = "Uploading photo…"
        S3Helper.uploadProductPhoto(ctx, photo, uid) { result ->
            val url = result.getOrNull()
            if (url == null) {
                _binding?.let { it.btnSave.isEnabled = true; it.btnSave.text = "Save product" }
                Toast.makeText(ctx, "Photo upload failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                    Toast.LENGTH_LONG).show()
            } else {
                writeProduct(name, price, quantity, uid, url, newUpload = true)
            }
        }
    }

    private fun writeProduct(name: String, price: Double, quantity: Int, uid: String, photoUrl: String, newUpload: Boolean) {
        val ctx = requireContext().applicationContext
        val product = Product(name = name, price = price, quantity = quantity, sellerId = uid, photoUrl = photoUrl)
        val ref = editingId?.let { productsRef.child(it) } ?: productsRef.push()
        val oldUrl = existingPhotoUrl

        ref.setValue(product)
            .addOnSuccessListener {
                // The photo was replaced: drop the old S3 object so the bucket stays small.
                if (newUpload && oldUrl.isNotBlank() && oldUrl != photoUrl) S3Helper.deleteObject(oldUrl)
                Toast.makeText(ctx, if (editingId != null) "Product updated" else "Product added", Toast.LENGTH_SHORT).show()
                if (isAdded) onSaved()
            }
            .addOnFailureListener {
                // Don't leave an orphaned upload behind if the database write failed.
                if (newUpload) S3Helper.deleteObject(photoUrl)
                _binding?.let { b -> b.btnSave.isEnabled = true; b.btnSave.text = "Save product" }
                Toast.makeText(ctx, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
            }
    }

    private fun onSaved() {
        if (editingId != null) {
            parentFragmentManager.popBackStack()
        } else {
            // Added a new item: show it in the product grid.
            (requireActivity() as SellerHomeActivity).selectTab(R.id.nav_products)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val STATE_EXISTING_PHOTO = "state_existing_photo"
        private const val STATE_PENDING = "state_pending_uri"
        private const val STATE_CAMERA = "state_camera_uri"

        /** [productId] == null -> add a new product; otherwise edit that product. */
        fun newInstance(productId: String?) = AddEditProductFragment().apply {
            arguments = Bundle().apply { productId?.let { putString(Roles.EXTRA_PRODUCT_ID, it) } }
        }
    }
}
