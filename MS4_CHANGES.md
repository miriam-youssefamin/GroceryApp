# Milestone 4 changes

## 1. Code cleanup (comments)
KDoc comments added to every class and to the non-obvious functions, including the files that had none
(`LoginActivity`, `SignUpActivity`, `RoleSelectionActivity`, `CartAdapter`, `ReviewAdapter`, `Roles`,
`ShopSettingsActivity`), plus inline comments where the logic isn't obvious.

## 2. Checkout constraints

### Order states
`Order.kt` now follows the required sequence:

    pending (Waiting for approval) → preparing → ready (pickup) / delivering (delivery) → delivered
                                   ↘ rejected (seller)   ↘ cancelled (buyer)

- Seller **Orders** tab: Accept/Reject while pending, then one button for the next step
  ("Ready for pickup" / "Out for delivery" → "Mark as completed").
- `OrderRepository.advance()` moves the order forward inside a transaction (can't skip or repeat a step).
- Old Milestone 3 orders with status `accepted` are shown as "Preparing" and still work.

### Delivery or pickup
- Cart has a **Delivery / Pickup** toggle. Pickup = no delivery fee and no address.
- Delivery requires a **valid address** (`DeliveryPricing.validateAddress`: at least 10 characters, a
  street/area name and a building number). It is pre-filled from the buyer's profile.
- Orders store `deliveryMethod`, `deliveryAddress`, `distanceZone`; both order lists show them.

### Dynamic delivery price (`DeliveryPricing.kt`)
    fee = base fee (seller's Shop settings; free for orders ≥ EGP 500)
        + distance (first 2 km included, then EGP 4 per started km; no delivery beyond 15 km)
        + rush hour (13:00–15:00 and 19:00–22:00 Cairo time: +5, fewer drivers available)
        + large order (more than 10 items: +5)

The distance is **measured, not chosen by the buyer** (a buyer would always pick the cheapest zone):
- The seller saves the shop's location once in **Shop settings → 📍 Use my current location**
  (`shops/{uid}/latitude`, `longitude`). Until then, buyers of that shop can only pick up.
- At checkout the buyer taps **Use my location**; the phone's location becomes the delivery spot and
  the straight-line distance to the shop sets the fee (`LocationHelper`, `CartManager.distanceKm`).
  The driver delivers to that exact spot, so faking a closer location only sends the order to the
  wrong place. The order saves `distanceKm`, `deliveryLat`, `deliveryLng`; the seller can tap the
  delivery line on the order card to open the spot in Maps.
- Rush hour uses Firebase server time (`ServerClock`, `.info/serverTimeOffset`) in the Cairo time
  zone, so changing the phone's clock doesn't avoid the fee.
- The cart shows the breakdown under the fee, e.g. "Base EGP 15 + distance EGP 12 (4.3 km) + rush hour EGP 5".
- New dependency: `com.google.android.gms:play-services-location:21.3.0`; new permissions:
  `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
- Limitation: the fee is still calculated on the phone. A modified app could send a different fee;
  fully preventing that needs a server (e.g. a Cloud Function) that recalculates it.

### Quantity check
- Client side: `CartManager` never goes above the available stock; checked again before checkout.
- Server side: stock is taken in Firebase transactions (`OrderRepository.reserveStock`), and
  `database.rules.json` refuses any quantity below 0. **Paste it into Firebase Console → Realtime
  Database → Rules and publish.**

### Inventory update
Unchanged from before: stock goes down when the order is placed and back up on reject/cancel.

### Notifications
The old fixed 30-second "Order is on the way" timer was replaced by real status notifications:
`BuyerHomeActivity` watches the buyer's orders and `OrderNotifier` shows "Order accepted",
"Ready for pickup", "Order is on the way", "Order delivered" or "Order rejected" when the seller
changes the status (while the app is running). WorkManager is no longer needed and was removed.

## 3. Swipe navigation between fragments
- Buyer (Shops | Cart | Orders) and seller (Products | Add | Orders) tabs are now pages of a
  **ViewPager2**: swipe left/right, and the bottom navigation follows (tapping a tab scrolls the pager).
- Screens opened on top of a tab (a shop's products, the product editor) open in an overlay above
  the pager; swiping is paused while it is open, and Back / tapping the tab again closes it.
- The cart refreshes whenever its page becomes visible; the Add form clears itself after saving.
- New dependency: `androidx.viewpager2:viewpager2:1.1.0`.

## 4. Notifications for both roles
- Buyer: order-status notifications (see above).
- Seller: **"New order 🛒"** notification whenever a buyer places an order with the shop
  (`SellerHomeActivity`), plus the existing badge on the Orders tab. The seller app now also asks
  for the notification permission on Android 13+.

## 5. VoIP with SIP signaling and RTP media (prepares Milestone 5)
The call screen still takes **the other phone's IP**, **their SIP port** and **my SIP port**
(default 5060), but the call now uses real protocols so Wireshark can analyse it:

    Caller                               Callee
      |--- INVITE (SDP: my RTP port, PCMU) --->|
      |<-- 100 Trying -------------------------|
      |<-- 180 Ringing ------------------------|   Answer / Decline buttons
      |<-- 200 OK (SDP: their RTP port) -------|
      |--- ACK -------------------------------->|
      |<== RTP voice, G.711 µ-law, 20 ms =====>|
      |--- BYE -------------------------------->|
      |<-- 200 OK -----------------------------|

Also handled: 603 Decline, 486 Busy Here, CANCEL → 200 OK → 487 Request Terminated → ACK,
and a 30-second no-answer timeout.

New files: `SipMessage.kt` (SIP messages + SDP), `RtpPacket.kt` (12-byte RTP header),
`G711.kt` (µ-law codec). `VoipActivity` shows a live log of every SIP message sent (→) and
received (←) plus RTP packet counts.

**For Milestone 5 (Wireshark):** open the call screen on both phones (same Wi-Fi), capture on
the laptop's Wi-Fi interface (or with PCAPdroid on a phone), then call, talk and hang up.
- Filter `sip` → the INVITE / 180 / 200 / ACK / BYE flow.
- Filter `rtp` → the voice packets (Wireshark finds them through the SDP in the SIP messages;
  if not, enable Analyze → Enabled Protocols → `rtp_udp`).
- Telephony → VoIP Calls → Flow Sequence draws the ladder diagram; Play Streams plays the voice.
- Filter `icmp` while running `ping <phone IP>` for the ICMP part.
