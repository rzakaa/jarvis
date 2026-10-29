package pl.jarvis.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Zapisuje zarobki, płatności i notatki w prywatnym pliku aplikacji (tylko na telefonie). */
object LedgerStore {
    private fun file(context: Context) = File(context.filesDir, "jarvis_dane.json")

    fun load(context: Context): Ledger {
        val ledger = Ledger()
        val f = file(context)
        if (!f.exists()) return ledger
        runCatching {
            val root = JSONObject(f.readText())
            ledger.nextId = root.optLong("nextId", 1)
            root.optJSONArray("incomes")?.forEachObject {
                ledger.incomes += Income(
                    it.getLong("id"), LocalDate.parse(it.getString("date")), it.getLong("grosze"), it.getString("place"),
                )
            }
            root.optJSONArray("payments")?.forEachObject {
                ledger.payments += Payment(
                    it.getLong("id"), LocalDate.parse(it.getString("date")), it.getString("kind"),
                    YearMonth.parse(it.getString("month")), if (it.isNull("grosze")) null else it.getLong("grosze"),
                )
            }
            root.optJSONArray("notes")?.forEachObject {
                ledger.notes += Note(
                    it.getLong("id"), LocalDateTime.parse(it.getString("created")), it.getString("text"),
                    it.optBoolean("done", false),
                )
            }
        }
        return ledger
    }

    fun save(context: Context, ledger: Ledger) = synchronized(ledger) {
        val root = JSONObject()
            .put("nextId", ledger.nextId)
            .put("incomes", JSONArray(ledger.incomes.map {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("grosze", it.grosze).put("place", it.place)
            }))
            .put("payments", JSONArray(ledger.payments.map {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("kind", it.kind)
                    .put("month", it.month.toString()).put("grosze", it.grosze ?: JSONObject.NULL)
            }))
            .put("notes", JSONArray(ledger.notes.map {
                JSONObject().put("id", it.id).put("created", it.created.toString()).put("text", it.text).put("done", it.done)
            }))
        // Najpierw do pliku tymczasowego – przerwany zapis nie zniszczy danych.
        val target = file(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(target)
    }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }
}
