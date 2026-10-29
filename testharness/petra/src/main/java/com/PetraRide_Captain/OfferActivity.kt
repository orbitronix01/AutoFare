package com.PetraRide_Captain

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.app.Activity

/**
 * Stands in for the Petra Ride offer popup.
 *
 * The only thing that matters here is that pressing accept is observable from
 * outside the process. AutoFare claiming it pressed the button proves that
 * `performAction` returned true; this log line proves the button was actually
 * delivered a click.
 */
class OfferActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.offer)

        findViewById<android.view.View>(R.id.accept_button).setOnClickListener {
            Log.i(TAG, "accept pressed")
            Toast.makeText(this, "ACCEPTED", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.reject_button).setOnClickListener {
            Log.i(TAG, "reject pressed")
        }
    }

    private companion object {
        const val TAG = "HARNESS"
    }
}
