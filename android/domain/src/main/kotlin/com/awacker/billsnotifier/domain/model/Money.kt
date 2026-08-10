package com.awacker.billsnotifier.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is stored as whole cents in a [Long] everywhere in this app. These helpers are the
 * only place cents become text or come back from it, so rounding happens once.
 *
 * [BigDecimal], never [Double] — binary floating point cannot represent 0.10 exactly, and
 * summing installments is precisely where that shows up.
 */
object Money {

    /** `123456` becomes `"$1,234.56"`. */
    fun format(cents: Long): String {
        val amount = BigDecimal.valueOf(cents, 2)
        val sign = if (amount.signum() < 0) "-" else ""
        return sign + "$" + amount.abs().toPlainStringGrouped()
    }

    /** `123456` becomes `"1234.56"` — the plain form written to the sheet. */
    fun toDecimalString(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /**
     * Parses user input such as `"1,234.56"`, `"$1234.56"` or `"1234"` into cents.
     * Returns null when the text isn't a usable amount, so callers can show a field error.
     */
    fun parseToCents(text: String): Long? {
        val cleaned = text.trim().removePrefix("$").replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        return runCatching {
            BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /**
     * Splits [totalCents] into [count] installments, returning the regular installment and
     * the remainder-bearing final one. Handy when entering a plan as "$5,000 over 24
     * payments" rather than as a per-payment amount.
     */
    fun splitEvenly(totalCents: Long, count: Int): Installments {
        require(count >= 1) { "Installment count must be at least 1, was $count" }
        val base = totalCents / count
        val remainder = totalCents - base * count
        return Installments(regularCents = base, finalCents = base + remainder)
    }

    data class Installments(val regularCents: Long, val finalCents: Long)

    private fun BigDecimal.toPlainStringGrouped(): String {
        val plain = setScale(2, RoundingMode.HALF_UP).toPlainString()
        val (whole, fraction) = plain.split(".")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return "$grouped.$fraction"
    }
}
