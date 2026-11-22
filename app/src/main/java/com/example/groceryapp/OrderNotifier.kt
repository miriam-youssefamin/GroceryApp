package com.example.groceryapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Order-status notifications for the buyer.
 *
 * [BuyerHomeActivity] listens to the buyer's orders in Firebase; whenever the seller moves an order
 * to a new status (Preparing, Ready for pickup, Delivering, Delivered, Rejected), [notifyStatus]
 * shows a notification such as "Your order is on the way 🛵".
 *
 * One notification per order: the notification id is derived from the order id, so a newer status
 * replaces the older notification instead of piling up.
 */
object OrderNotifier {

    private const val CHANNEL_ID = "orders"

    /** Title + text for [order]'s current status, or null if this status shouldn't notify. */
    fun messageFor(order: Order): Pair<String, String>? {
        val shop = order.shopName.ifBlank { "The shop" }
        return when (order.status) {
            Order.STATUS_PREPARING, Order.STATUS_ACCEPTED ->
                "Order accepted ✅" to "$shop is preparing your order."
            Order.STATUS_READY ->
                "Ready for pickup 🏪" to "Your order is ready. You can collect it from $shop."
            Order.STATUS_DELIVERING ->
                "Order is on the way 🛵" to "$shop sent your order out for delivery."
            Order.STATUS_DELIVERED ->
                (if (order.isPickup()) "Order picked up 🎉" else "Order delivered 🎉") to "Enjoy! Thanks for ordering from $shop."
            Order.STATUS_REJECTED ->
                "Order rejected" to "$shop couldn't accept your order. You were not charged."
            else -> null // pending / cancelled: the buyer did that themselves, no notification needed
        }
    }

    /** Shows the notification for [order]'s current status (does nothing for statuses without a message). */
    fun notifyStatus(context: Context, order: Order) {
        val (title, text) = messageFor(order) ?: return
        show(context, order.id.hashCode(), title, text)
    }

    /** Creates the "Orders" channel (needed on Android 8+; calling it again is harmless). */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Orders", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Updates about your orders"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Posts a notification; tapping it opens the app. Silently skipped if notifications aren't allowed. */
    fun show(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        // Android 13+: the user must have granted POST_NOTIFICATIONS (asked in BuyerHomeActivity).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Tapping the notification opens the app's launcher screen (which forwards a signed-in buyer home).
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_basket)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
