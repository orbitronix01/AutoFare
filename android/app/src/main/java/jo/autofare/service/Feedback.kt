package jo.autofare.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import jo.autofare.auto.Cue

/**
 * The accept tone and the skip buzz.
 *
 * Runs on its own thread, not the service's. The accessibility events — and so
 * the next offer — arrive on the main thread, and a `ToneGenerator` is a native
 * audio track that can take tens of milliseconds to open on a cheap phone. None
 * of that may queue in front of an event, so [play] only hands the cue over and
 * returns.
 *
 * Every call is wrapped: a phone with no vibrator, an audio stack refusing a
 * tone, a vendor build throwing where the docs say it will not — none of it is
 * allowed to reach the service, because the service is what accepts rides.
 */
class Feedback(context: Context) {

    private val app = context.applicationContext
    private val thread = HandlerThread("autofare-feedback").also { it.start() }
    private val handler = Handler(thread.looper)

    /** Post the cue and return at once. Safe to call from the accept callback. */
    fun play(cue: Cue) {
        handler.post {
            when (cue) {
                Cue.ACCEPTED -> tone()
                Cue.SKIPPED -> buzz()
            }
        }
    }

    fun release() {
        thread.quitSafely()
    }

    /**
     * On the notification stream, so a phone on silent stays silent. A driver
     * who has muted his phone in the car has already answered whether he wants
     * this, and a tone forcing its way through would be the app overruling him.
     */
    private fun tone() {
        runCatching {
            val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            gen.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            // released on this same thread once the tone has played out; releasing
            // straight away cuts it off on some builds
            handler.postDelayed({ runCatching { gen.release() } }, TONE_MS + 150L)
        }
    }

    private fun buzz() {
        runCatching {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** `VibratorManager` from 31; the plain service before it, deprecated but the only one there. */
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        /** Out of 100. Loud enough over an engine, not a fanfare. */
        const val TONE_VOLUME = 80
        const val TONE_MS = 180

        /** "Something happened", felt through a phone mount — not an alarm. */
        const val BUZZ_MS = 70L
    }
}
