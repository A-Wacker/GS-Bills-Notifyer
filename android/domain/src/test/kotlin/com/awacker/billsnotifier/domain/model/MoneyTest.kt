package com.awacker.billsnotifier.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {

    @Test
    fun `formats cents with grouping and two decimal places`() {
        assertEquals("$0.00", Money.format(0))
        assertEquals("$0.07", Money.format(7))
        assertEquals("$1.00", Money.format(100))
        assertEquals("$1,234.56", Money.format(123_456))
        assertEquals("$1,000,000.00", Money.format(100_000_000))
    }

    @Test
    fun `formats negatives with the sign outside the currency symbol`() {
        assertEquals("-$12.34", Money.format(-1_234))
    }

    @Test
    fun `writes a plain decimal string for the sheet`() {
        assertEquals("1234.56", Money.toDecimalString(123_456))
        assertEquals("0.05", Money.toDecimalString(5))
    }

    @Test
    fun `parses the shapes a user might actually type`() {
        assertEquals(123_456, Money.parseToCents("1234.56"))
        assertEquals(123_456, Money.parseToCents("$1,234.56"))
        assertEquals(123_456, Money.parseToCents("  1234.56  "))
        assertEquals(120_000, Money.parseToCents("1200"))
        assertEquals(50, Money.parseToCents(".50"))
    }

    @Test
    fun `rounds a third decimal place rather than truncating`() {
        assertEquals(1_235, Money.parseToCents("12.345"))
        assertEquals(1_234, Money.parseToCents("12.344"))
    }

    @Test
    fun `returns null for input that is not an amount`() {
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("   "))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("12.34.56"))
    }

    @Test
    fun `parse and format round-trip`() {
        val cents = Money.parseToCents("$9,876.54")
        assertEquals("$9,876.54", Money.format(cents!!))
    }

    @Test
    fun `splits a total that does not divide evenly, remainder on the last payment`() {
        val split = Money.splitEvenly(totalCents = 100_000, count = 3)
        assertEquals(33_333, split.regularCents)
        assertEquals(33_334, split.finalCents)
        // The parts must add back up to the original total exactly.
        assertEquals(100_000, split.regularCents * 2 + split.finalCents)
    }

    @Test
    fun `splits a total that divides evenly with no remainder`() {
        val split = Money.splitEvenly(totalCents = 120_000, count = 24)
        assertEquals(5_000, split.regularCents)
        assertEquals(5_000, split.finalCents)
    }

    @Test
    fun `a single-payment split is the whole total`() {
        val split = Money.splitEvenly(totalCents = 45_678, count = 1)
        assertEquals(45_678, split.finalCents)
    }
}
