package com.awacker.billsnotifier.domain.model

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class DatePickerDatesTest {

    /**
     * These two pin the conversion to UTC by value. If someone switches it to the system
     * zone, these fail on any machine that isn't on UTC — which is the whole point, because
     * the bug they prevent is invisible to anyone developing in London.
     */
    @Test
    fun `epoch zero is the first of January 1970`() {
        assertEquals(LocalDate.parse("1970-01-01"), DatePickerDates.toLocalDate(0))
    }

    @Test
    fun `one day after the epoch is exactly 86,400,000 millis`() {
        assertEquals(86_400_000L, DatePickerDates.toUtcMillis(LocalDate.parse("1970-01-02")))
    }

    @Test
    fun `dates round-trip unchanged`() {
        val dates = listOf(
            "2026-01-01", "2026-01-31", "2026-02-28", "2028-02-29",
            "2026-03-15", "2026-12-31", "1999-06-15", "2099-11-02",
        ).map(LocalDate::parse)

        for (date in dates) {
            assertEquals(date, DatePickerDates.toLocalDate(DatePickerDates.toUtcMillis(date)), "$date")
        }
    }

    /**
     * The picker hands back UTC midnight. Read with a negative-offset zone that becomes the
     * previous evening — and the date silently moves back a day.
     */
    @Test
    fun `UTC midnight reads as the same calendar day, not the day before`() {
        val march15 = DatePickerDates.toUtcMillis(LocalDate.parse("2026-03-15"))
        assertEquals(LocalDate.parse("2026-03-15"), DatePickerDates.toLocalDate(march15))
    }

    @Test
    fun `a leap day survives the round trip`() {
        val leapDay = LocalDate.parse("2028-02-29")
        assertEquals(leapDay, DatePickerDates.toLocalDate(DatePickerDates.toUtcMillis(leapDay)))
    }
}
