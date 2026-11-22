package com.example.groceryapp

import android.location.Location
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.round

/**
 * Calculates the delivery price dynamically (Milestone 4: "delivery price that is dynamically
 * calculated based on distance, availability, or other logic").
 *
 *     fee = base fee            (set by the seller in Shop settings, waived for orders ≥ FREE_BASE_FROM)
 *         + distance fee        (first FREE_KM km included, then FEE_PER_KM for every started km)
 *         + rush-hour fee       (fewer drivers available at lunch and dinner time)
 *         + large-order fee     (more than LARGE_ORDER_ITEMS items to carry)
 *
 * Why the distance is measured, not chosen:
 * the buyer used to pick a distance zone at checkout, but a buyer would always pick the cheapest one.
 * Now the distance is calculated from two real points:
 *  - the shop's location, saved by the seller in Shop settings (shops/{uid}/latitude, longitude), and
 *  - the delivery location, which is the buyer's phone location at checkout. The driver delivers to
 *    this exact point, so giving a fake, closer location would only send the order to the wrong place.
 *
 * Why the time comes from the server:
 * rush hour used to follow the phone's clock, which the buyer can change. [ServerClock] corrects the
 * phone time with Firebase's server time, and the hour is read in the shop's time zone (Cairo).
 *
 * Pickup orders always cost 0. Delivery is refused beyond MAX_DELIVERY_KM.
 */
object DeliveryPricing {

    /** Distance included in the base fee. */
    const val FREE_KM = 2.0

    /** Price of every started kilometre after [FREE_KM] (e.g. 4.3 km → 3 extra km → EGP 12). */
    const val FEE_PER_KM = 4.0

    /** Shops don't deliver further than this; the buyer can still choose pickup. */
    const val MAX_DELIVERY_KM = 15.0

    /** Extra fee during rush hours, when fewer drivers are available. */
    const val RUSH_HOUR_FEE = 5.0

    /** Rush hours (24-hour clock, start inclusive, end exclusive): lunch 13–15 and dinner 19–22. */
    private val RUSH_HOURS = listOf(13 until 15, 19 until 22)

    /** Orders with more than this many items pay [LARGE_ORDER_FEE]. */
    const val LARGE_ORDER_ITEMS = 10
    const val LARGE_ORDER_FEE = 5.0

    /** From this subtotal on, the shop's base fee is free (distance/rush/large fees still apply). */
    const val FREE_BASE_FROM = 500.0

    /** Rush hours are the shop's local hours, so they don't depend on the buyer's time-zone setting. */
    private val SHOP_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Africa/Cairo")

    /**
     * The result of a calculation, kept as separate parts so the cart can show a breakdown.
     * [total] is the amount the buyer pays for delivery.
     */
    data class Quote(
        val base: Double,
        val distance: Double,
        val rushHour: Double,
        val largeOrder: Double,
        val distanceKm: Double = 0.0
    ) {
        val total: Double get() = round2(base + distance + rushHour + largeOrder)

        /** Short breakdown, e.g. "Base EGP 15 + distance EGP 12 (4.3 km) + rush hour EGP 5". */
        fun breakdown(): String {
            val parts = mutableListOf<String>()
            parts += if (base > 0) "Base ${money(base)}" else "Base free"
            parts += if (distance > 0) "distance ${money(distance)} (${formatKm(distanceKm)})"
                     else "distance free (${formatKm(distanceKm)})"
            if (rushHour > 0) parts += "rush hour ${money(rushHour)}"
            if (largeOrder > 0) parts += "large order ${money(largeOrder)}"
            return parts.joinToString(" + ")
        }

        private fun money(v: Double) = if (v % 1.0 == 0.0) "EGP %.0f".format(v) else "EGP %.2f".format(v)
    }

    /** A zero quote, used for pickup orders. */
    val FREE = Quote(0.0, 0.0, 0.0, 0.0)

    /** Straight-line distance in km between two points, rounded to 0.1 km. */
    fun distanceKm(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val meters = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, meters)
        return round(meters[0] / 100.0) / 10.0
    }

    /** Distance part of the fee: free up to [FREE_KM], then [FEE_PER_KM] per started km. */
    fun distanceFee(km: Double): Double =
        if (km <= FREE_KM) 0.0 else ceil(km - FREE_KM) * FEE_PER_KM

    /** Hour of day (0–23) in the shop's time zone, from a server-corrected time. */
    fun shopHour(timeMillis: Long = ServerClock.now()): Int =
        Calendar.getInstance(SHOP_TIME_ZONE).apply { timeInMillis = timeMillis }.get(Calendar.HOUR_OF_DAY)

    /**
     * Works out the delivery fee.
     *
     * @param shopBaseFee The seller's base fee from shops/{uid}/deliveryFee.
     * @param distanceKm  Measured distance between the shop and the delivery location.
     * @param itemCount   Total number of units in the cart.
     * @param subtotal    Price of the items (without delivery).
     * @param hourOfDay   Hour (0–23) in the shop's time zone; a parameter so it is easy to test.
     */
    fun quote(
        shopBaseFee: Double,
        distanceKm: Double,
        itemCount: Int,
        subtotal: Double,
        hourOfDay: Int = shopHour()
    ): Quote = Quote(
        base = if (subtotal >= FREE_BASE_FROM) 0.0 else shopBaseFee.coerceAtLeast(0.0),
        distance = distanceFee(distanceKm),
        rushHour = if (RUSH_HOURS.any { hourOfDay in it }) RUSH_HOUR_FEE else 0.0,
        largeOrder = if (itemCount > LARGE_ORDER_ITEMS) LARGE_ORDER_FEE else 0.0,
        distanceKm = distanceKm
    )

    /**
     * Checks a delivery address. Returns an error message, or null if it looks valid.
     * A valid address must be at least 10 characters and contain both letters
     * (street/area name) and a number (building or street number).
     */
    fun validateAddress(address: String): String? {
        val a = address.trim()
        return when {
            a.isEmpty() -> "Enter a delivery address"
            a.length < 10 -> "Address is too short, add street and building number"
            a.none { it.isLetter() } -> "Add the street or area name"
            a.none { it.isDigit() } -> "Add a building or street number"
            else -> null
        }
    }

    private fun round2(v: Double) = round(v * 100) / 100.0
}

/** "850 m" under 1 km, otherwise "4.3 km". */
fun formatKm(km: Double): String =
    if (km < 1.0) "%.0f m".format(km * 1000) else "%.1f km".format(km)
