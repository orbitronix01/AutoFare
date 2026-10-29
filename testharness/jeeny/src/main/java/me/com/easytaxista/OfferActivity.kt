package me.com.easytaxista

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.app.Activity

/**
 * Stands in for the Jeeny offer sheet.
 *
 * The listener is on the **wrapper**, not on the `قبول العرض` text inside it,
 * which is the whole point: the real app is built the same way, and a detector
 * that pressed the text would see `performAction` succeed and nothing happen.
 * If this log line appears, the climb to the clickable parent worked.
 */
class OfferActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.offer)

        findViewById<android.view.View>(R.id.harness_accept_wrapper).setOnClickListener {
            Log.i(TAG, "accept pressed")
            Toast.makeText(this, "ACCEPTED", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "HARNESS"
    }
}
