package pl.jarvis.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class Income(val id: Long, val date: LocalDate, val grosze: Long, val place: String)

data class Payment(val id: Long, val date: LocalDate, val kind: String, val month: YearMonth, val grosze: Long?)

data class Note(val id: Long, val created: LocalDateTime, val text: String, val done: Boolean = false)

/**
 * Twarde dane Jarvisa: zarobki, płatności i notatki. Zapis na dysk robi warstwa Androida.
 * Metody są synchronizowane – AI zapisuje dane w tle, a ekran czyta je jednocześnie.
 */
class Ledger {
    val incomes = mutableListOf<Income>()
    val payments = mutableListOf<Payment>()
    val notes = mutableListOf<Note>()
    var nextId = 1L

    @Synchronized
    fun addIncome(date: LocalDate, grosze: Long, place: String) =
        Income(nextId++, date, grosze, place).also { incomes += it }

    @Synchronized
    fun addPayment(date: LocalDate, kind: String, month: YearMonth, grosze: Long?) =
        Payment(nextId++, date, kind, month, grosze).also { payments += it }

    @Synchronized
    fun addNote(created: LocalDateTime, text: String) =
        Note(nextId++, created, text).also { notes += it }

    @Synchronized
    fun openNotes() = notes.filter { !it.done }

    @Synchronized
    fun markDone(id: Long) {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = notes[i].copy(done = true)
    }

    @Synchronized
    fun remove(id: Long): Boolean =
        incomes.removeIf { it.id == id } || payments.removeIf { it.id == id } || notes.removeIf { it.id == id }

    @Synchronized
    fun incomesIn(month: YearMonth) = incomes.filter { YearMonth.from(it.date) == month }

    @Synchronized
    fun paymentsFor(month: YearMonth) = payments.filter { it.month == month }

    @Synchronized
    fun isPaid(kind: String, month: YearMonth) = payments.any { it.kind == kind && it.month == month }
}
