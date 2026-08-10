package com.awacker.billsnotifier.domain.sync

import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPayloadTest {

    @Nested
    inner class JsonEncoding {

        @Test
        fun `encodes the primitive types`() {
            assertEquals("\"hi\"", Json.Str("hi").encode())
            assertEquals("true", Json.Bool(true).encode())
            assertEquals("false", Json.Bool(false).encode())
            assertEquals("250.00", Json.Num(BigDecimal("250.00")).encode())
        }

        @Test
        fun `amounts keep their trailing zeros and never use exponent notation`() {
            assertEquals("0.01", Json.Num(BigDecimal.valueOf(1, 2)).encode())
            assertEquals("0.00", Json.Num(BigDecimal.valueOf(0, 2)).encode())
            assertEquals("12345678.90", Json.Num(BigDecimal.valueOf(1234567890, 2)).encode())
        }

        @Test
        fun `escapes quotes and backslashes so a bill name cannot break the payload`() {
            assertEquals("\"He said \\\"hi\\\"\"", Json.Str("He said \"hi\"").encode())
            assertEquals("\"C:\\\\path\"", Json.Str("C:\\path").encode())
        }

        @Test
        fun `escapes control characters`() {
            assertEquals(""""a\nb"""", Json.Str("a\nb").encode())
            assertEquals(""""a\tb"""", Json.Str("a\tb").encode())
            assertEquals(""""\u0000"""", Json.Str("\u0000").encode())
        }

        @Test
        fun `passes non-ascii through as-is for UTF-8`() {
            assertEquals("\"café ☕\"", Json.Str("café ☕").encode())
        }

        @Test
        fun `encodes nested structures`() {
            val json = Json.obj(
                "a" to Json.Arr(listOf(Json.Num(BigDecimal.ONE), Json.Bool(false))),
                "b" to Json.obj("c" to Json.Str("d")),
            )
            assertEquals("""{"a":[1,false],"b":{"c":"d"}}""", json.encode())
        }

        @Test
        fun `empty collections encode as empty literals`() {
            assertEquals("[]", Json.Arr(emptyList()).encode())
            assertEquals("{}", Json.Obj(emptyList()).encode())
        }

        @Test
        fun `row output follows the declared column order, not map order`() {
            val scrambled = mapOf(
                "amount" to SheetValue.Number(BigDecimal("1.00")),
                "occurrence_id" to SheetValue.Text("a#1"),
            )
            val encoded = Json.row(listOf("occurrence_id", "amount"), scrambled).encode()
            assertTrue(
                encoded.indexOf("occurrence_id") < encoded.indexOf("amount"),
                "columns must serialize in schema order: $encoded",
            )
        }

        @Test
        fun `a column with no value becomes an empty string rather than being dropped`() {
            val encoded = Json.row(listOf("a", "b"), mapOf("a" to SheetValue.Text("x"))).encode()
            assertEquals("""{"a":"x","b":""}""", encoded)
        }
    }

    @Nested
    inner class Requests {

        @Test
        fun `ping carries the secret and the action`() {
            assertEquals(
                """{"secret":"s3cret","action":"ping"}""",
                SyncPayload.ping("s3cret"),
            )
        }

        @Test
        fun `a secret containing quotes is escaped rather than breaking the request`() {
            val encoded = SyncPayload.ping("""a"b\c""")
            assertEquals("""{"secret":"a\"b\\c","action":"ping"}""", encoded)
        }

        @Test
        fun `an empty sync still sends well-formed empty collections`() {
            val encoded = SyncPayload.sync(
                secret = "s",
                plans = emptyList(),
                today = LocalDate.parse("2026-03-15"),
                updatedAt = "2026-03-15T07:00:00",
            )
            assertTrue(encoded.contains(""""bills":[]"""), encoded)
            assertTrue(encoded.contains(""""occurrences":[]"""), encoded)
        }

        @Test
        fun `every occurrence of every plan is included`() {
            val payload = SyncPayload.sync(
                secret = "s",
                plans = listOf(samplePlan("a", 4), samplePlan("b", 3)),
                today = LocalDate.parse("2026-03-15"),
                updatedAt = "2026-03-15T07:00:00",
            )
            assertEquals(7, Regex("\"occurrence_id\"").findAll(payload).count())
            assertEquals(2, Regex("\"bill_id\":\"[ab]\",\"name\"").findAll(payload).count())
        }
    }

    /**
     * The payload's exact shape is pinned to a fixture that the node test in
     * `appsscript/test/contract.test.js` feeds through the real Apps Script functions.
     * Between them, the two tests prove both sides agree — not just that each is
     * self-consistent.
     *
     * If this fails because the payload legitimately changed, regenerate with:
     *   ./gradlew :domain:test -DupdateFixture=true
     */
    @Nested
    inner class FixtureContract {

        @Test
        fun `the sync payload matches the committed fixture`() {
            val fixture = File(
                System.getProperty("repoRoot") ?: error("repoRoot not set"),
                "appsscript/test/fixtures/sync-payload.json",
            )
            val generated = canonicalPayload()

            if (System.getProperty("updateFixture") == "true") {
                fixture.parentFile.mkdirs()
                fixture.writeText(generated + "\n")
            }

            assertTrue(fixture.isFile, "Missing fixture at ${fixture.absolutePath}")
            assertEquals(
                fixture.readText().trim(),
                generated,
                "Sync payload changed. If intended, regenerate with " +
                    "`./gradlew :domain:test -DupdateFixture=true` and re-run the node tests.",
            )
        }
    }

    private fun samplePlan(id: String, payments: Int): PlanSnapshot {
        val bill = Bill(
            id = id,
            name = "Plan $id",
            installmentAmountCents = 25_000,
            recurrence = Recurrence.Monthly(),
            firstDueDate = LocalDate.parse("2026-01-15"),
            end = PlanEnd.AfterPayments(payments),
        )
        return PlanSnapshot(bill, ScheduleGenerator.generate(bill))
    }

    /** Fixed inputs so the generated payload is byte-for-byte reproducible. */
    private fun canonicalPayload(): String {
        val carLoan = Bill(
            id = "car-loan",
            name = "Car loan",
            payee = "First Credit Union",
            installmentAmountCents = 25_000,
            finalAmountCents = 24_500,
            recurrence = Recurrence.Monthly(),
            firstDueDate = LocalDate.parse("2026-01-15"),
            end = PlanEnd.AfterPayments(4),
            notes = "Account #1234",
        )
        val dentist = Bill(
            id = "dentist",
            name = "Dentist payment plan",
            payee = "Bright Smiles",
            installmentAmountCents = 12_500,
            recurrence = Recurrence.Weekly(2),
            firstDueDate = LocalDate.parse("2026-03-01"),
            end = PlanEnd.AfterPayments(3),
            autopay = true,
        )

        val carSchedule = ScheduleGenerator.generate(carLoan).map {
            // First payment already made, so the fixture exercises paid state too.
            if (it.sequence == 1) it.copy(paidOn = LocalDate.parse("2026-01-14")) else it
        }

        return SyncPayload.sync(
            secret = "test-secret",
            plans = listOf(
                PlanSnapshot(carLoan, carSchedule),
                PlanSnapshot(dentist, ScheduleGenerator.generate(dentist)),
            ),
            today = LocalDate.parse("2026-03-15"),
            updatedAt = "2026-03-15T07:00:00",
            settings = mapOf("email_recipients" to "a@example.com,b@example.com"),
        )
    }
}
