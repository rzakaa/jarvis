package pl.jarvis.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Jarvis w telefonie: kontakty, połączenia, SMS-y, kalendarz, lokalizacja i nawigacja, komunikatory,
 * powiadomienia, aplikacje, przełączniki i sterowanie ekranem. Narzędzia AI wołają [run] w tle.
 *
 * Rzeczy, które zabierają ekran albo mikrofon (połączenie, nawigacja, otwarcie aplikacji), czekają
 * w [pending] – [Assistant] odpala je dopiero, gdy Jarvis skończy mówić.
 */
object PhoneTools {
    private const val TAG = "JarvisPhone"

    /** Uprawnienia nadawane w Opcjach („Dostęp do telefonu”). */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Volatile
    private var pending: (() -> Unit)? = null

    /** Akcja do wykonania po odpowiedzi Jarvisa (i zakończeniu rozmowy); null – nic nie czeka. */
    fun takePending(): (() -> Unit)? = pending.also { pending = null }

    fun missingPermissions(context: Context) =
        PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun notificationsEnabled(context: Context) =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(context.packageName)

    fun screenControlEnabled(context: Context) =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .contains(context.packageName)

    /** Wykonuje narzędzie telefonu; null – to nie jest narzędzie telefonu. */
    fun run(context: Context, name: String, input: Map<String, Any?>): String? {
        fun s(key: String) = (input[key] as? String)?.trim().orEmpty()
        return try {
            when (name) {
                "szukaj_kontaktu" -> need(context, Manifest.permission.READ_CONTACTS) ?: findContacts(context, s("fraza"))
                "zadzwon" -> need(context, Manifest.permission.CALL_PHONE) ?: call(context, s("numer"))
                "wyslij_sms" -> need(context, Manifest.permission.SEND_SMS) ?: sendSms(context, s("numer"), s("tekst"))
                "czytaj_sms" -> need(context, Manifest.permission.READ_SMS) ?: readSms(context, s("fraza"), (input["ile"] as? Number)?.toInt() ?: 10)
                "polaczenia" -> need(context, Manifest.permission.READ_CALL_LOG) ?: callLog(context, (input["ile"] as? Number)?.toInt() ?: 10)
                "kalendarz_telefonu" -> need(context, Manifest.permission.READ_CALENDAR) ?: calendar(context, s("od"), s("do"))
                "dodaj_wydarzenie" -> need(context, Manifest.permission.WRITE_CALENDAR)
                    ?: addEvent(context, s("tytul"), s("poczatek"), s("koniec"), s("miejsce"), s("opis"))
                "lokalizacja" -> need(context, Manifest.permission.ACCESS_FINE_LOCATION) ?: location(context)
                "nawiguj" -> navigate(context, s("cel"), s("tryb"))
                "wyslij_wiadomosc" -> message(context, s("aplikacja"), s("numer"), s("tekst"))
                "powiadomienia" -> if (!notificationsEnabled(context)) {
                    "Brak dostępu do powiadomień – użytkownik musi go włączyć w Opcjach → Dostęp do telefonu."
                } else {
                    NotificationStore.search(s("fraza"), (input["ile"] as? Number)?.toInt() ?: 15)
                }
                "otworz_aplikacje" -> openApp(context, s("nazwa"))
                "przelacz" -> toggle(context, s("co"), input["wlacz"] as? Boolean ?: true)
                "glosnosc" -> volume(context, (input["procent"] as? Number)?.toInt() ?: 50)
                "ekran" -> JarvisAccessibility.instance?.readScreen() ?: NO_SCREEN
                "wypelnij_pole" -> JarvisAccessibility.instance?.fill(s("pole"), s("tekst")) ?: NO_SCREEN
                "kliknij" -> JarvisAccessibility.instance?.click(s("tekst"), s("systemowy")) ?: NO_SCREEN
                else -> null
            }
        } catch (e: SecurityException) {
            "Brak uprawnienia: ${e.message}"
        }
    }

    private const val NO_SCREEN =
        "Sterowanie ekranem jest wyłączone – użytkownik musi je włączyć w Opcjach → Dostęp do telefonu."

    private fun need(context: Context, permission: String): String? =
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) null
        else "Brak uprawnienia ${permission.substringAfterLast('.')} – użytkownik musi je nadać w Opcjach → Dostęp do telefonu."

    // ---- kontakty ----

    private class Contact(val name: String) {
        val phones = linkedSetOf<String>()
        val details = mutableListOf<String>()
    }

    private fun loadContacts(context: Context): Map<Long, Contact> {
        val contacts = linkedMapOf<Long, Contact>()
        val mimes = mapOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE to "tel",
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE to "adres",
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE to "e-mail",
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE to "pseudonim",
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE to "firma",
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE to "notatka",
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (${mimes.keys.joinToString { "?" }})",
            mimes.keys.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val value = c.getString(3)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val contact = contacts.getOrPut(c.getLong(0)) { Contact(name) }
                val kind = mimes[c.getString(2)]
                if (kind == "tel") contact.phones += value else contact.details += "$kind: ${value.replace('\n', ' ')}"
            }
        }
        return contacts
    }

    private fun findContacts(context: Context, query: String): String {
        if (query.isBlank()) return "Błąd: podaj imię, nazwisko albo inne słowo (np. miasto)."
        val words = normalize(query).split(' ').filter { it.length >= 2 }
        val scored = loadContacts(context).values.mapNotNull { contact ->
            val haystack = normalize(contact.name + " " + contact.details.joinToString(" ")).split(' ')
            var score = 0
            for (w in words) {
                val stem = w.take(maxOf(3, w.length - 2))
                score += when {
                    haystack.any { it == w } -> 3
                    haystack.any { it.startsWith(w) } -> 2
                    haystack.any { it.startsWith(stem) } -> 1
                    else -> return@mapNotNull null
                }
            }
            contact to score
        }.sortedByDescending { it.second }.take(8)
        if (scored.isEmpty()) return "Brak kontaktu pasującego do „$query”."
        return scored.joinToString("\n") { (c, _) ->
            c.name + ": " + (c.phones.joinToString(", ").ifEmpty { "bez numeru" }) +
                (if (c.details.isNotEmpty()) "; " + c.details.joinToString("; ") else "")
        }
    }

    /** Nazwa kontaktu dla numeru (po ostatnich 9 cyfrach). */
    private fun nameIndex(context: Context): Map<String, String> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val index = hashMapOf<String, String>()
        loadContacts(context).values.forEach { c -> c.phones.forEach { index[digits(it).takeLast(9)] = c.name } }
        return index
    }

    // ---- połączenia i SMS ----

    private fun call(context: Context, number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.length < 3) return "Błąd: podaj numer (najpierw znajdź go narzędziem szukaj_kontaktu)."
        pending = {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Połączenie z $n ruszy, gdy skończysz mówić – powiedz krótko, do kogo dzwonisz."
    }

    private fun sendSms(context: Context, number: String, text: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.length < 3 || text.isBlank()) return "Błąd: brak numeru albo treści."
        @Suppress("DEPRECATION")
        val sms = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null)
        return "SMS do $n wysłany."
    }

    private fun readSms(context: Context, query: String, limit: Int): String {
        val names = nameIndex(context)
        val q = normalize(query)
        val out = mutableListOf<String>()
        context.contentResolver.query(
            Uri.parse("content://sms"), arrayOf("address", "body", "date", "type"), null, null, "date DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < 2000 && out.size < limit.coerceIn(1, 30)) {
                val address = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                val who = names[digits(address).takeLast(9)] ?: address
                if (q.isNotEmpty() && q.split(' ').any { w -> w !in normalize("$who $address $body") }) continue
                val dir = if (c.getInt(3) == 2) "do" else "od"
                out += "${time(c.getLong(2))} $dir $who: ${body.take(500)}"
            }
        }
        return out.joinToString("\n").ifEmpty { "Brak SMS-ów" + (if (query.isNotBlank()) " pasujących do „$query”." else ".") }
    }

    private fun callLog(context: Context, limit: Int): String {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit.coerceIn(1, 30)) {
                val kind = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "przychodzące"
                    CallLog.Calls.OUTGOING_TYPE -> "wychodzące"
                    CallLog.Calls.MISSED_TYPE -> "nieodebrane"
                    CallLog.Calls.REJECTED_TYPE -> "odrzucone"
                    else -> "inne"
                }
                val who = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(0).orEmpty()
                out += "${time(c.getLong(3))} $kind $who (${c.getString(0)}), ${c.getLong(4)} s"
            }
        }
        return out.joinToString("\n").ifEmpty { "Brak połączeń." }
    }

    // ---- kalendarz ----

    private fun calendar(context: Context, from: String, to: String): String {
        val zone = ZoneId.systemDefault()
        val start = from.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val end = to.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } ?: start
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start.atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val out = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val allDay = c.getInt(3) == 1
                // Wydarzenia całodniowe są zapisane w UTC.
                val begin = if (allDay) Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.of("UTC")).toLocalDate().toString() + " cały dzień"
                else time(c.getLong(1)) + "–" + time(c.getLong(2)).substringAfter(' ')
                out += "$begin: ${c.getString(0).orEmpty()}" + (c.getString(4)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            }
        }
        return out.joinToString("\n").ifEmpty { "Brak wydarzeń od $start do $end." }
    }

    private fun addEvent(context: Context, title: String, begin: String, end: String, place: String, notes: String): String {
        if (title.isBlank()) return "Błąd: brak tytułu."
        val start = parseDateTime(begin) ?: return "Błąd: podaj początek jako RRRR-MM-DDTGG:MM."
        val stop = parseDateTime(end) ?: start.plusHours(1)
        val calendarId = writableCalendar(context) ?: return "Brak kalendarza do zapisu (dodaj konto Google w telefonie)."
        val zone = ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, stop.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (place.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, place)
            if (notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return "Nie udało się zapisać wydarzenia."
        return "Dodano do kalendarza: $title, $start" + (if (place.isNotBlank()) ", $place" else "") + "."
    }

    private fun writableCalendar(context: Context): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            var best: Long? = null
            var bestScore = -1
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = c.getLong(0)
                }
            }
            return best
        }
        return null
    }

    // ---- lokalizacja i nawigacja ----

    @SuppressLint("MissingPermission")
    private fun location(context: Context): String {
        val lm = context.getSystemService(LocationManager::class.java)
        var best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        // Stara pozycja (sprzed 5 min) – prosimy o świeżą, najwyżej 10 s.
        if (best == null || System.currentTimeMillis() - best.time > 5 * 60_000) {
            val latch = CountDownLatch(1)
            var fresh: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    fresh = location
                    latch.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { lm.isProviderEnabled(it) }
            if (providers.isEmpty() && best == null) return "Lokalizacja w telefonie jest wyłączona."
            providers.forEach {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(it, listener, Looper.getMainLooper())
            }
            latch.await(10, TimeUnit.SECONDS)
            lm.removeUpdates(listener)
            fresh?.let { best = it }
        }
        val loc = best ?: return "Nie udało się ustalić pozycji."
        val age = (System.currentTimeMillis() - loc.time) / 60_000
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.forLanguageTag("pl-PL")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
        return "Pozycja: ${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)} " +
            "(dokładność ok. ${loc.accuracy.toInt()} m, sprzed $age min)" + (address?.let { ". Adres: $it" } ?: "")
    }

    private fun navigate(context: Context, target: String, mode: String): String {
        if (target.isBlank()) return "Błąd: brak celu."
        val m = when (normalize(mode)) {
            "pieszo" -> "w"
            "rower" -> "b"
            else -> "d"
        }
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(target)}&mode=$m"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (maps.resolveActivity(context.packageManager) != null) maps
        else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(target)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        pending = { context.startActivity(intent) }
        return "Nawigacja do „$target” ruszy w Mapach Google, gdy skończysz mówić."
    }

    // ---- komunikatory ----

    private val MESSENGERS = mapOf(
        "whatsapp" to "com.whatsapp",
        "messenger" to "com.facebook.orca",
        "telegram" to "org.telegram.messenger",
        "signal" to "org.thoughtcrime.securesms",
    )

    private fun message(context: Context, app: String, number: String, text: String): String {
        if (text.isBlank()) return "Błąd: brak treści."
        val key = normalize(app).ifEmpty { "whatsapp" }
        if (key == "sms") {
            return need(context, Manifest.permission.SEND_SMS) ?: sendSms(context, number, text)
        }
        val pkg = MESSENGERS.entries.firstOrNull { key.contains(it.key) }?.value
            ?: return "Nie znam komunikatora „$app” – obsługuję WhatsApp, Messenger, Telegram, Signal i SMS."
        if (context.packageManager.getLaunchIntentForPackage(pkg) == null) return "Aplikacja $app nie jest zainstalowana."
        var digits = digits(number)
        if (digits.length == 9) digits = "48$digits"
        if (pkg == "com.whatsapp" && digits.length >= 10) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}"))
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val auto = JarvisAccessibility.instance != null
            pending = {
                context.startActivity(intent)
                if (auto) JarvisAccessibility.pressSendWhenReady(pkg)
            }
            return if (auto) "Wiadomość na WhatsAppie do +$digits zostanie wysłana, gdy skończysz mówić."
            else "Otworzę WhatsAppa z gotową wiadomością do +$digits – użytkownik musi nacisnąć „wyślij” (automatyczne wysyłanie wymaga sterowania ekranem w Opcjach)."
        }
        // Messenger, Telegram, Signal (i WhatsApp bez numeru): gotowy tekst, odbiorcę wybiera użytkownik.
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        pending = { context.startActivity(share) }
        return "Otworzę $app z gotową treścią – użytkownik wybiera odbiorcę i wysyła sam."
    }

    // ---- aplikacje i przełączniki ----

    private fun openApp(context: Context, name: String): String {
        if (name.isBlank()) return "Błąd: brak nazwy aplikacji."
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = normalize(name)
        val match = apps.firstOrNull { normalize(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { normalize(it.loadLabel(pm).toString()).contains(q) }
            ?: apps.firstOrNull { q.contains(normalize(it.loadLabel(pm).toString())) && it.loadLabel(pm).length >= 3 }
            ?: return "Nie ma aplikacji „$name”. Zainstalowane: " +
                apps.map { it.loadLabel(pm).toString() }.sorted().joinToString(", ").take(1500)
        val label = match.loadLabel(pm).toString()
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "Nie da się otworzyć $label."
        pending = { context.startActivity(intent) }
        return "Otworzę $label, gdy skończysz mówić."
    }

    @SuppressLint("MissingPermission")
    private fun toggle(context: Context, what: String, on: Boolean): String {
        val word = if (on) "włączone" else "wyłączone"
        return when (normalize(what).replace(" ", "")) {
            "latarka" -> {
                val cm = context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "Ten telefon nie ma latarki."
                cm.setTorchMode(id, on)
                "Latarka $word."
            }
            "wifi" -> {
                @Suppress("DEPRECATION")
                val ok = context.getSystemService(WifiManager::class.java).setWifiEnabled(on)
                if (ok) "Wi-Fi $word." else openSettings(context, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi")
            }
            "bluetooth" -> {
                @Suppress("DEPRECATION")
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Brak Bluetooth."
                @Suppress("DEPRECATION")
                val ok = runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
                if (ok) "Bluetooth $word." else openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
            }
            else -> "Umiem przełączać: latarka, wifi, bluetooth."
        }
    }

    private fun openSettings(context: Context, action: String, label: String): String {
        pending = { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "Android nie pozwala przełączyć $label samemu – otworzę ustawienia, gdy skończysz mówić."
    }

    private fun volume(context: Context, percent: Int): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent.coerceIn(0, 100) + 50) / 100, 0)
        return "Głośność multimediów: ${percent.coerceIn(0, 100)}%."
    }

    // ---- pomocnicze ----

    private fun parseDateTime(value: String): LocalDateTime? =
        value.takeIf { it.isNotBlank() }?.replace(' ', 'T')?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun time(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return "${t.toLocalDate()} ${"%02d:%02d".format(t.hour, t.minute)}"
    }

    private fun digits(value: String) = value.filter { it.isDigit() }

    /** Małe litery bez polskich znaków i interpunkcji – do porównań („Łódź” = „lodz”). */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9+ ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    internal val main = Handler(Looper.getMainLooper())

    internal fun log(message: String) = Log.d(TAG, message)
}
