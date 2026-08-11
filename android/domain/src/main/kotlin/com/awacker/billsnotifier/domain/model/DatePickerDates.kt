package com.awacker.billsnotifier.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Converts between [LocalDate] and the epoch-millis the Material date picker speaks.
 *
 * The picker's `selectedDateMillis` is **UTC midnight** of the chosen day, not local
 * midnight. Converting it with the system zone therefore shifts the date back a day for
 * anyone west of UTC: pick the 15th in Chicago and read back the 14th.
 *
 * It lives here, tested, rather than inline in the screen for the same reason the sheet's
 * date columns are forced to plain text — a silent one-day error in a due date is the exact
 * failure this app must not have.
 */
object DatePickerDates {

    fun toLocalDate(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun toUtcMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
