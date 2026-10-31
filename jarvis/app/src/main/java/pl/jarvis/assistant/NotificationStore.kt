package pl.jarvis.assistant

import android.app.Notification
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.ZoneId

/**
 * Ostatnie powiadomienia z innych aplikacji (WhatsApp, Messenger, SMS…). Zapisane tylko w telefonie
 * (powiadomienia.json w prywatnych danych), żeby adres od klienta sprzed tygodnia też dało się znaleźć.
 */
object NotificationStore {
    data class Entry(val time: Long, val app: String, val title: String, val text: String)

    private const val MAX = 500
    private val entries = ArrayDeque<Entry>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "powiadomienia.json").also { file = it }
        runCatching {
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                entries.addLast(Entry(o.getLong("t"), o.getString("a"), o.getString("n"), o.getString("x")))
            }
        }
    }

    @Synchronized
    fun add(entry: Entry) {
        // Komunikatory odświeżają to samo powiadomienie przy każdej wiadomości – bez duplikatów.
        entries.removeAll { it.app == entry.app && it.title == entry.title && it.text == entry.text }
        entries.addLast(entry)
        while (entries.size > MAX) entries.removeFirst()
        file?.let { f ->
            val array = JSONArray()
            entries.forEach { array.put(JSONObject().put("t", it.time).put("a", it.app).put("n", it.title).put("x", it.text)) }
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(array.toString())
                tmp.renameTo(f)
            }
        }
    }

    @Synchronized
    fun search(query: String, limit: Int): String {
        val words = PhoneTools.normalize(query).split(' ').filter { it.isNotEmpty() }
        val found = entries.reversed().filter { e ->
            val text = PhoneTools.normalize("${e.app} ${e.title} ${e.text}")
            words.all { it in text }
        }.take(limit.coerceIn(1, 40))
        if (found.isEmpty()) return "Brak powiadomień" + (if (query.isNotBlank()) " pasujących do „$query”." else " (zbieram je od włączenia dostępu).")
        return found.joinToString("\n") { e ->
            val t = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault())
            "${t.toLocalDate()} ${"%02d:%02d".format(t.hour, t.minute)} [${e.app}] ${e.title}: ${e.text.take(600)}"
        }
    }
}

/** Zbiera powiadomienia do [NotificationStore]; włącza się w ustawieniach Androida („Dostęp do powiadomień”). */
class JarvisNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationStore.init(applicationContext)
        runCatching { activeNotifications?.forEach { store(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = store(sbn)

    private fun store(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        // Rozmowy (WhatsApp, Messenger) trzymają kilka ostatnich wiadomości w EXTRA_MESSAGES.
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull {
            val m = it as? android.os.Bundle ?: return@mapNotNull null
            val body = m.getCharSequence("text")?.toString() ?: return@mapNotNull null
            m.getCharSequence("sender")?.let { s -> "$s: $body" } ?: body
        }.orEmpty()
        val text = messages.joinToString(" | ").ifEmpty {
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        NotificationStore.add(NotificationStore.Entry(sbn.postTime, app, title, text))
    }
}
