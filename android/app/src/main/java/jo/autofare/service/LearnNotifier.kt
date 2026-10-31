package jo.autofare.service

import jo.autofare.ui.arNum
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import jo.autofare.MainActivity
import jo.autofare.R

/**
 * A silent, ongoing notification showing that learning mode is alive and how much
 * it has recorded.
 *
 * The reason this exists: the first driver session was discovered to be empty a
 * day later, over WhatsApp. A driver cannot be asked to keep opening the app
 * mid-shift, but he will see a line in his shade — and if the count stops moving,
 * or the line disappears because One UI killed us, he can say so while he is still
 * driving instead of after.
 *
 * Nothing here is required for capture to work. If the driver never grants the
 * notification permission, every call below quietly does nothing.
 */
class LearnNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "وضع التعلّم",
            NotificationManager.IMPORTANCE_LOW, // no sound, no heads-up while driving
        ).apply {
            description = "يبيّن إنه التسجيل شغّال وكم شاشة تسجّلت"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun show(total: Int, kept: Int) {
        runCatching {
            ensureChannel()
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val note = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("AutoFare — وضع التعلّم شغّال")
                // same wording as the learn screen, so the driver is looking for one number
                .setContentText("${arNum(total)} شاشة · منها ${arNum(kept)} مهمة")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
            manager.notify(NOTE_ID, note)
        }
    }

    fun hide() {
        runCatching { manager.cancel(NOTE_ID) }
    }

    private companion object {
        const val CHANNEL_ID = "learning"
        const val NOTE_ID = 1001
    }
}
