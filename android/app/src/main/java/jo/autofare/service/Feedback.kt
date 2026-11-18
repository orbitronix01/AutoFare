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
        runCatching { pool?.release() }
        thread.quitSafely()
    }

    /*
     * The accept chime: three rising notes, 0.7 s (res/raw/accept_chime.wav).
     *
     * Played on the ALARM channel. As a notification sound (0.7) it was silent
     * for a driver whose phone was on vibrate — reported 2026-11-19, and common
     * in a car — and quiet whenever notification volume was down. The alarm
     * channel plays on vibrate and silent and follows the alarm volume. It is
     * still the driver's choice: the Settings switch turns it off entirely.
     */
    private val pool: android.media.SoundPool? = runCatching {
        android.media.SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(CHIME_ATTRS)
            .build()
    }.getOrNull()

    private val chime: Int = pool?.let { p -> runCatching { p.load(app, jo.autofare.R.raw.accept_chime, 1) }.getOrNull() } ?: 0

    private fun tone() {
        runCatching {
            val played = pool != null && chime != 0 && pool.play(chime, 1f, 1f, 1, 0, 1f) != 0
            // the pool loads asynchronously; the very first accept after a restart may beat it
            if (!played) {
                val gen = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
                gen.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
                handler.postDelayed({ runCatching { gen.release() } }, TONE_MS + 150L)
            }
        }
        buzz(ACCEPT_BUZZ_MS)
    }

    private fun buzz(ms: Long = BUZZ_MS) {
        runCatching {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
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

    companion object {
        private val CHIME_ATTRS: android.media.AudioAttributes =
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        /**
         * Play the accept chime once, exactly as an accept will — for the
         * Settings switch, so a driver hears it and can set the alarm volume
         * before an order ever comes.
         */
        fun preview(context: Context) {
            runCatching {
                val app = context.applicationContext
                val sid = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
                val mp = android.media.MediaPlayer.create(app, jo.autofare.R.raw.accept_chime, CHIME_ATTRS, sid)
                    ?: return
                mp.setOnCompletionListener { it.release() }
                mp.start()
            }
        }

        /** Out of 100. Loud enough over an engine, not a fanfare. */
        private const val TONE_VOLUME = 80
        private const val TONE_MS = 180

        /** "Something happened", felt through a phone mount — not an alarm. */
        private const val BUZZ_MS = 70L
        private const val ACCEPT_BUZZ_MS = 250L
    }
}
