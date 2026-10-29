package pl.jarvis.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import pl.jarvis.MainActivity
import pl.jarvis.brain.Ledger
import java.time.ZoneId

/**
 * Przypomnienia i pobudki: system budzi aplikację o czasie najbliższego wpisu ([AlarmManager.setAlarmClock] –
 * działa też przy wygaszonym ekranie i w trybie uśpienia), a resztę robi [Assistant.onRemindersDue].
 * Zawsze ustawiony jest tylko jeden alarm – na najbliższe przypomnienie.
 */
object Reminders {
    private const val TAG = "JarvisRemind"
    private const val ACTION_DUE = "pl.jarvis.action.REMINDER"

    fun schedule(context: Context, ledger: Ledger) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_DUE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = ledger.pendingReminders().firstOrNull()
        if (next == null) {
            alarms.cancel(intent)
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            .coerceAtLeast(System.currentTimeMillis() + 1000)
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), intent)
        } else {
            // Bez zgody na dokładne alarmy – system może się spóźnić o kilka minut.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        Log.d(TAG, "następne: #${next.id} ${next.at}${if (next.wake) " (pobudka)" else ""}")
    }
}

/** Pora przypomnienia albo restart telefonu (wtedy tylko ustawiamy alarm od nowa). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Assistant.init(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.schedule(context.applicationContext, Assistant.ledger)
        } else {
            Assistant.onRemindersDue()
        }
    }
}
