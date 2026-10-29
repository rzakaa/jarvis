package pl.jarvis.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class Income(val id: Long, val date: LocalDate, val grosze: Long, val place: String)

data class Payment(val id: Long, val date: LocalDate, val kind: String, val month: YearMonth, val grosze: Long?)

data class Note(val id: Long, val created: LocalDateTime, val text: String, val done: Boolean = false)

/** Twarde dane Jarvisa: zarobki, płatności i notatki. Zapis na dysk robi warstwa Androida. */
class Ledger {
    val incomes = mutableListOf<Income>()
    val payments = mutableListOf<Payment>()
    val notes = mutableListOf<Note>()
    var nextId = 1L

    fun addIncome(date: LocalDate, grosze: Long, place: String) =
        Income(nextId++, date, grosze, place).also { incomes += it }

    fun addPayment(date: LocalDate, kind: String, month: YearMonth, grosze: Long?) =
        Payment(nextId++, date, kind, month, grosze).also { payments += it }

    fun addNote(created: LocalDateTime, text: String) =
        Note(nextId++, created, text).also { notes += it }

    fun openNotes() = notes.filter { !it.done }

    fun markDone(id: Long) {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = notes[i].copy(done = true)
    }

    fun remove(id: Long): Boolean =
        incomes.removeIf { it.id == id } || payments.removeIf { it.id == id } || notes.removeIf { it.id == id }

    fun incomesIn(month: YearMonth) = incomes.filter { YearMonth.from(it.date) == month }

    fun paymentsFor(month: YearMonth) = payments.filter { it.month == month }

    fun isPaid(kind: String, month: YearMonth) = payments.any { it.kind == kind && it.month == month }
}
