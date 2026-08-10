package com.awacker.billsnotifier.domain.schedule

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigestTimingTest {

    private val central = ZoneId.of("America/Chicago")
    private val sevenAm = LocalTime.of(7, 0)

    private fun at(text: String) = ZonedDateTime.of(
        java.time.LocalDateTime.parse(text),
        central,
    )

    @Test
    fun `before the target time it waits until later the same day`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-03-10T05:30:00"))
        assertEquals(Duration.ofMinutes(90), delay)
    }

    @Test
    fun `after the target time it waits until tomorrow`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-03-10T09:00:00"))
        assertEquals(Duration.ofHours(22), delay)
    }

    @Test
    fun `exactly at the target time it schedules tomorrow rather than firing twice`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-03-10T07:00:00"))
        assertEquals(Duration.ofHours(24), delay)
    }

    @Test
    fun `a second before the target still fires today`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-03-10T06:59:59"))
        assertEquals(Duration.ofSeconds(1), delay)
    }

    /**
     * US clocks spring forward at 2am on 2026-03-08, so 7am the next morning is only
     * 23 hours away. Adding a flat 24 hours would drift the digest an hour later every
     * spring and never recover.
     */
    @Test
    fun `spring forward makes tomorrow's digest 23 hours away`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-03-07T07:00:00"))
        assertEquals(Duration.ofHours(23), delay)
    }

    /** Clocks fall back at 2am on 2026-11-01, so that morning is 25 hours out. */
    @Test
    fun `fall back makes tomorrow's digest 25 hours away`() {
        val delay = DigestTiming.delayUntilNext(sevenAm, at("2026-10-31T07:00:00"))
        assertEquals(Duration.ofHours(25), delay)
    }

    @Test
    fun `a digest time inside the spring-forward gap still resolves`() {
        // 2:30am does not exist on 2026-03-08 in Central time.
        val delay = DigestTiming.delayUntilNext(
            LocalTime.of(2, 30),
            at("2026-03-07T12:00:00"),
        )
        assertTrue(!delay.isNegative, "delay must never be negative: $delay")
        assertTrue(delay < Duration.ofHours(24), "should still land the next morning: $delay")
    }

    @Test
    fun `the delay is never negative for any time of day`() {
        val now = at("2026-06-15T13:45:30")
        for (hour in 0..23) {
            val delay = DigestTiming.delayUntilNext(LocalTime.of(hour, 0), now)
            assertTrue(!delay.isNegative, "negative delay for $hour:00")
            assertTrue(delay <= Duration.ofHours(24), "delay over 24h for $hour:00: $delay")
        }
    }

    @Test
    fun `works the same in other timezones`() {
        val london = ZonedDateTime.of(
            java.time.LocalDateTime.parse("2026-06-15T05:00:00"),
            ZoneId.of("Europe/London"),
        )
        assertEquals(Duration.ofHours(2), DigestTiming.delayUntilNext(sevenAm, london))
    }
}
