package me.com.easytaxista

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import jo.autofare.fakeride.FakeRideActivity

/**
 * A fake Jeeny offer sheet, built to the structure of the captured ones
 * (app/src/test/resources/captures/jeeny-offer-*.txt):
 *
 *  - **no view ids** on anything the detector reads, as on the real sheet
 *  - the ETA and distance both start with `يبعُد` — with the damma — and are told
 *    apart only by unit
 *  - the fare is `JOD x.xx` behind an invisible U+200E left-to-right mark
 *  - Latin digits for the ETA and fare, Arabic-Indic for the distance, as captured
 *  - `desc="Reject Button"`, `desc="Pick Up Icon"`, `desc="Destination  Icon"`
 *    (two spaces, as captured)
 *  - `قبول العرض` is a **non-clickable TextView inside a clickable View**; the
 *    listener sits on the wrapper, so a detector that pressed the text would see
 *    nothing happen
 *  - the sheet itself is clickable, and a clickable map sits behind it
 *
 * Addresses are invented. None of the captured ones are copied here.
 */
class FakeOfferActivity : FakeRideActivity() {

    override val appLabel = "جيني"

    override fun buildOffer(offer: Offer): View {
        val root = FrameLayout(this)

        // the map: a clickable frame of id-less markers
        root.addView(FrameLayout(this).apply {
            isClickable = true
            setBackgroundColor(Color.rgb(0xE8, 0xEC, 0xE4))
            addView(View(this@FakeOfferActivity).apply {
                contentDescription = "خرائط Google"
                isClickable = true
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@FakeOfferActivity).apply {
                contentDescription = "محدّد الخريطة"
                isClickable = true
                setBackgroundColor(Color.rgb(0x5B, 0x3F, 0xE8))
            }, FrameLayout.LayoutParams(dp(28), dp(40), Gravity.CENTER))
        }, FrameLayout.LayoutParams(-1, -1))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        sheet.addView(text("إيكونومي", 15f, Color.rgb(0x5B, 0x3F, 0xE8), bold = true), wrap())
        sheet.addView(text("$YABUD ${offer.eta} دقائق", 22f, bold = true), wrap())
        /*
         * Digits follow the captures field by field: the ETA and the fare are Latin
         * in every Jeeny capture, the distance comes in both (`يبعُد 1.8كم`,
         * `يبعُد ٢٫٧كم`), so it defaults to Arabic-Indic and `kmDigits latin` flips it.
         */
        val km = if (intent.getStringExtra("kmDigits") == "latin") offer.km else arabicDigits(offer.km)
        sheet.addView(text("$YABUD ${km}كم", 18f, Color.DKGRAY), wrap())

        sheet.addView(ImageView(this).apply {
            contentDescription = "Reject Button"
            isClickable = true
            setBackgroundColor(Color.rgb(0xDD, 0xDD, 0xDD))
            setOnClickListener { onReject() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        // a ride with no price simply has no fare node
        offer.fare?.let { sheet.addView(text("‎JOD ${fmtFare(it)}", 30f, bold = true), wrap()) }

        sheet.addView(View(this).apply {
            contentDescription = "Pick Up Icon"
            setBackgroundColor(Color.rgb(0x1A, 0x7F, 0x37))
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        sheet.addView(text(offer.pickup, 16f), wrap())
        sheet.addView(View(this).apply {
            contentDescription = "Destination  Icon"
            setBackgroundColor(Color.rgb(0xB3, 0x26, 0x1E))
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        sheet.addView(text(offer.dropoff, 16f), wrap())

        // the clickable wrapper, and inside it the text that is NOT clickable
        sheet.addView(LinearLayout(this).apply {
            isClickable = true
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x5B, 0x3F, 0xE8))
            setOnClickListener { onAccept() }
            addView(text("قبول العرض", 22f, Color.WHITE, bold = true).apply {
                isClickable = false
                isFocusable = false
            })
        }, full(dp(64)).apply { topMargin = dp(16) })

        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }

    private companion object {
        /** `يبعُد` — with the damma on the ع, exactly as Jeeny writes it. */
        const val YABUD = "يبعُد"
    }
}
