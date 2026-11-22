package com.example.groceryapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Gets the phone's current location once (used by the seller to save the shop location and by
 * the buyer at checkout to measure the delivery distance). The screens ask for the permission
 * themselves with [PERMISSIONS], then call [getCurrentLocation].
 */
object LocationHelper {

    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Asks for a fresh location; falls back to the last known one. [onResult] gets null when there is
     * no permission or location is turned off on the phone.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission() first
    fun getCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) onResult(location)
                else client.lastLocation
                    .addOnSuccessListener { onResult(it) }
                    .addOnFailureListener { onResult(null) }
            }
            .addOnFailureListener { onResult(null) }
    }
}
