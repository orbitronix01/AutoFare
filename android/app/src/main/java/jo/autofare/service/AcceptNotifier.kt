package jo.autofare.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import jo.autofare.MainActivity
import jo.autofare.R
import java.util.Locale

/**
 * What auto-accept is doing, in the shade.
 *
 * The driver opens AutoFare once, arms it, and spends the whole shift inside Jeeny
 * or Petra. He is never looking at our screens while the work happens, so every
 * figure on Home and Stats is an *end-of-shift record* — useful, but it cannot be
 * the only place the app speaks. This is the live channel, and it is the same
 * reasoning `LearnNotifier` was built on.
 *
 * Two notifications, deliberately on two channels:
 *
 *  - [showStatus] is ongoing and silent. It says armed or not, and how much today
 *    has brought in. Crucially, **if EMUI kills the service the line disappears**,
 *    and a missing line is something a driver notices at a traffic light. That is
 *    the cheapest health check this app will ever have.
 *  - [alertCannotAccept] makes noise, because it means he is losing rides right
 *    now. A lapsed subscription refuses every offer silently otherwise, and he
 *    would find out at the end of a shift he has already wasted.
 *
 * Nothing here is required for accepting to work. Without the notification
 * permission every call quietly does nothing.
 */
class AcceptNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private fun ensureChannels() {
        val status = NotificationChannel(
            STATUS_CHANNEL,
            "القبول التلقائي",
            // no sound and no heads-up: this one updates on every accepted ride,
            // and a chime per ride in a moving car is an app he turns off
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "يبيّن إنه القبول التلقائي شغّال وكم رحلة قبلنا اليوم"
            setShowBadge(false)
        }
        val alert = NotificationChannel(
            ALERT_CHANNEL,
            "تنبيهات القبول",
            // this one is allowed to interrupt — it only fires when the app has
            // stopped being able to earn him anything
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "بينبّهك لما يصير إشي بيمنع القبول التلقائي"
        }
        context.getSystemService(NotificationManager::class.java)?.let {
            it.createNotificationChannel(status)
            it.createNotificationChannel(alert)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The ongoing line.
     *
     * [armed] is the full answer, not just the rule switches — an app switched on
     * with no subscription is not armed, and saying otherwise here would repeat
     * the exact lie the readiness screen was built to stop telling.
     */
    fun showStatus(armed: Boolean, acceptedToday: Int, earningsToday: Double) {
        runCatching {
            ensureChannels()
            val title =
                if (armed) "AutoFare — القبول التلقائي شغّال"
                else "AutoFare — القبول التلقائي مطفي"
            val body =
                if (acceptedToday == 0) {
                    if (armed) "لساته ما إجا ولا طلب"
                    else "شغّله من التطبيق عشان نبلّش"
                } else {
                    "اليوم: $acceptedToday ${rides(acceptedToday)} · ${jod(earningsToday)} د.أ"
                }

            val note = NotificationCompat.Builder(context, STATUS_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openApp())
                .build()
            manager.notify(STATUS_ID, note)
        }
    }

    /**
     * Something is stopping the app accepting, and he is in another app right now.
     *
     * [reason] is the driver-facing Arabic, straight off `Reason`, so the shade
     * says the same words the decision list will.
     */
    fun alertCannotAccept(reason: String) {
        runCatching {
            ensureChannels()
            val note = NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("AutoFare ما قبل الطلب")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$reason — افتح التطبيق وصلّحها، إنت هلأ بتضيّع طلبات."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            manager.notify(ALERT_ID, note)
        }
    }

    fun hide() {
        runCatching {
            manager.cancel(STATUS_ID)
            manager.cancel(ALERT_ID)
        }
    }

    /** "رحلة" / "رحلتين" / "رحلات" — the numeral is already printed before this. */
    private fun rides(n: Int): String = when {
        n == 1 -> "رحلة"
        n == 2 -> "رحلتين"
        n % 100 in 3..10 -> "رحلات"
        else -> "رحلة"
    }

    private fun jod(v: Double): String = String.format(Locale.US, "%.2f", v)

    private companion object {
        const val STATUS_CHANNEL = "auto_accept"
        const val ALERT_CHANNEL = "auto_accept_alert"
        const val STATUS_ID = 1002
        const val ALERT_ID = 1003
    }
}
