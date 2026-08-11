package com.awacker.billsnotifier.domain.sync

/**
 * Turns the Apps Script's digest result into a sentence for the app to show.
 *
 * Lives here rather than in the UI so it can be tested. The wording matters more than it
 * looks: "no email was sent" has at least four causes that are indistinguishable from a
 * phone, and the whole point of the test button is telling them apart.
 */
object DigestOutcome {

    fun describe(
        reason: String,
        dueTodayCount: Int = 0,
        pendingCount: Int = 0,
        recipients: Int = 0,
    ): String = when (reason) {
        "sent" -> {
            val payments = plural(pendingCount, "payment")
            val people = plural(recipients, "address", "addresses")
            "Emailed $payments to $people."
        }

        "nothing due" ->
            "Nothing is due today, so no email was sent. Add a plan with a payment due " +
                "today and sync, then try again."

        // Not a failure — the idempotency guard doing its job.
        "already notified today" ->
            "Today's email already went out for ${plural(dueTodayCount, "payment")}. " +
                "It only sends once a day, so nothing was re-sent."

        "no recipients" ->
            "No email addresses are set. Add them above and sync, or fill in the " +
                "email_recipients row on the sheet's Settings tab."

        "disabled" ->
            "The digest is switched off — set digest_enabled to TRUE on the sheet's " +
                "Settings tab."

        "locked" ->
            "The script was busy with another run. Try again in a moment."

        else -> "The script reported: $reason"
    }

    private fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        if (count == 1) "1 $singular" else "$count $plural"
}
