package com.awacker.billsnotifier.domain.schedule

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out how long to wait before the next morning digest.
 *
 * Lives in the domain module purely so it can be tested: the daylight-saving cases below
 * are easy to get wrong and impossible to notice until the clocks change.
 */
object DigestTiming {

    /**
     * Time from [now] until the next occurrence of [target] in [now]'s zone.
     *
     * Computed against zoned date-times rather than by adding 24 hours, because on the two
     * days a year the clocks shift, tomorrow's 7am is 23 or 25 hours away, not 24.
     */
    fun delayUntilNext(target: LocalTime, now: ZonedDateTime): Duration {
        val todayAtTarget = atZonedTime(now, target, daysFromNow = 0)
        val next = if (todayAtTarget.isAfter(now)) {
            todayAtTarget
        } else {
            atZonedTime(now, target, daysFromNow = 1)
        }
        return Duration.between(now, next)
    }

    /**
     * Resolves a wall-clock time on a given day within a zone.
     *
     * [ZonedDateTime.of] handles the two awkward cases for us: a time that does not exist
     * because the clocks sprang forward is pushed to the following valid instant, and a
     * time that happens twice because they fell back resolves to the earlier one.
     */
    private fun atZonedTime(now: ZonedDateTime, target: LocalTime, daysFromNow: Long): ZonedDateTime =
        ZonedDateTime.of(
            LocalDateTime.of(now.toLocalDate().plusDays(daysFromNow), target),
            now.zone,
        )
}
