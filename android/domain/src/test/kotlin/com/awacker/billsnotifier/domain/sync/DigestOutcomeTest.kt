package com.awacker.billsnotifier.domain.sync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wording is the feature. "No email arrived" has several causes that are
 * indistinguishable from a phone, and this is what tells them apart.
 */
class DigestOutcomeTest {

    @Test
    fun `a successful send names the counts`() {
        assertEquals(
            "Emailed 2 payments to 2 addresses.",
            DigestOutcome.describe("sent", dueTodayCount = 2, pendingCount = 2, recipients = 2),
        )
    }

    @Test
    fun `singular and plural both read correctly`() {
        assertEquals(
            "Emailed 1 payment to 1 address.",
            DigestOutcome.describe("sent", dueTodayCount = 1, pendingCount = 1, recipients = 1),
        )
    }

    @Test
    fun `a quiet day explains what to do instead of looking broken`() {
        val message = DigestOutcome.describe("nothing due")
        assertTrue(message.contains("Nothing is due today"), message)
        assertTrue(message.contains("sync"), "should say how to test anyway")
    }

    /** The idempotency guard is correct behaviour and must not read as a failure. */
    @Test
    fun `already-sent explains the once-a-day rule and does not read as an error`() {
        val message = DigestOutcome.describe("already notified today", dueTodayCount = 3)
        assertTrue(message.contains("already went out"), message)
        assertTrue(message.contains("3 payments"), message)
        assertTrue(!message.contains("could not"), "must not imply something failed")
    }

    @Test
    fun `missing recipients points at both places they can be set`() {
        val message = DigestOutcome.describe("no recipients")
        assertTrue(message.contains("No email addresses"), message)
        assertTrue(message.contains("email_recipients"), message)
    }

    @Test
    fun `a disabled digest names the switch`() {
        assertTrue(DigestOutcome.describe("disabled").contains("digest_enabled"))
    }

    @Test
    fun `a busy script suggests retrying`() {
        assertTrue(DigestOutcome.describe("locked").contains("Try again"))
    }

    @Test
    fun `an unrecognised reason is passed through rather than swallowed`() {
        assertEquals(
            "The script reported: something new",
            DigestOutcome.describe("something new"),
        )
    }
}
