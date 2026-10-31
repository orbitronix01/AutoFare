package com.PetraRide_Captain

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import jo.autofare.fakeride.FakeRideActivity

/**
 * A fake Petra Ride offer popup, built to the structure of the captured ones
 * (app/src/test/resources/captures/petra-offer-*.txt):
 *
 *  - real view ids, declared in res/values/ids.xml under this module's namespace,
 *    so they surface as `com.PetraRide_Captain:id/accept_button` — the exact
 *    selector PetraDetector looks up
 *  - one string carries ETA and distance: `5.72 دقيقة • 1.4 كم`
 *  - a bonus ride has no `estimated_trip_price` node at all; its slot reads
 *    `مكافأة مسافات بعيدة` instead
 *  - every figure in Latin digits, as in every Petra capture
 *  - accept and reject are real, clickable `Button`s reading `قبول` and `رفض`
 *
 * Addresses are invented. None of the captured ones are copied here.
 */
class FakeOfferActivity : FakeRideActivity() {

    override val appLabel = "بترا رايد"

    override fun buildOffer(offer: Offer): View {
        val root = FrameLayout(this)

        root.addView(FrameLayout(this).apply {
            id = R.id.map_view
            isClickable = true
            setBackgroundColor(Color.rgb(0xE4, 0xEA, 0xF0))
            addView(View(this@FakeOfferActivity).apply {
                contentDescription = "خرائط Google"
                isClickable = true
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@FakeOfferActivity).apply {
                contentDescription = "محدّد الخريطة"
                isClickable = true
                setBackgroundColor(Color.rgb(0x0E, 0x76, 0xCC))
            }, FrameLayout.LayoutParams(dp(28), dp(40), Gravity.CENTER))
        }, FrameLayout.LayoutParams(-1, -1))

        root.addView(Button(this).apply {
            id = R.id.reject_button
            text = "رفض"
            setOnClickListener { onReject() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(24); marginStart = dp(16)
        })

        val card = LinearLayout(this).apply {
            id = R.id.left_buttons_layout
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        if (offer.fare != null) {
            card.addView(text("${fmtFare(offer.fare)} د.أ", 30f, bold = true).apply {
                id = R.id.estimated_trip_price
            }, wrap())
        }
        card.addView(text("${offer.eta} دقيقة • ${offer.km} كم", 18f, Color.DKGRAY).apply {
            id = R.id.estimated_time_to_pick_up
        }, wrap())
        if (offer.fare == null) {
            card.addView(text("مكافأة مسافات بعيدة", 16f, Color.rgb(0x0E, 0x76, 0xCC)), wrap())
        }
        card.addView(text("\$\$ Economy +", 15f), wrap())
        card.addView(text(offer.pickup, 16f), wrap())
        card.addView(text(offer.dropoff, 16f).apply {
            id = R.id.drop_off_location_title_label
        }, wrap())

        card.addView(Button(this).apply {
            id = R.id.accept_button
            text = "قبول"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0x0E, 0x76, 0xCC))
            setOnClickListener { onAccept() }
        }, full(dp(64)).apply { topMargin = dp(16) })

        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }
}
