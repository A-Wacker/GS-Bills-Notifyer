# GS-Bills-Notifyer — Android payment-plan tracker with Sheets-backed email notifications

## Context

We track a handful of ongoing payment plans (financed purchases, settlements, installment
agreements). Each has a fixed installment amount, a recurring schedule (bi-weekly, monthly, …),
and — crucially — an **end date**, because a payment plan is finite. Today there is nothing:
the repo holds a one-line README and a single "Initial commit".

Two people need to know when something is due, but only one of them will have the app:

- **Phone owner** — needs a push notification each morning listing what's due today.
- **Spouse** — will *not* install the app, so needs an email instead.

Rather than run a server to serve the second case, we use a Google Sheet as the shared
persistence layer and a Google Apps Script time-driven trigger to send the email. The Sheet
doubles as backup, as a read-anywhere view of the plans, and as the notification source of truth
for the email path.

**Outcome:** an offline-first Android app that owns the data and fires a local morning digest, a
Google Sheet that mirrors it, and an Apps Script that emails a matching digest each morning —
with no server, no OAuth consent screen, and no Play Store publication required.

---

## Architecture decisions

These were confirmed with the user; the reasoning is recorded so it isn't relitigated.

**1. Local DB is the source of truth; the Sheet is a mirror.**
Notifications must never depend on connectivity. Room holds everything; the morning digest is
computed purely from Room. Sync to Sheets is a background, retryable side effect.

**2. Transport is an Apps Script Web App, not the Sheets REST API.**
The app POSTs JSON to a `doPost` endpoint with a shared secret. This avoids a Google Cloud
project, an OAuth client + SHA-1 fingerprints, a consent screen, sensitive-scope review, and any
sign-in UI. Trade-off accepted: the secret ships inside the APK, so it is obfuscation rather
than real security. That is fine here — the sheet holds bill amounts, not credentials, and the
deployment URL is unlisted.

**3. The phone is the only writer of bill data.**
Apps Script owns exactly two columns (`notified_on`, and `paid`/`paid_on` if we ever add
mark-paid-from-email). Everything else flows one way. This removes conflict resolution entirely.
Row schema still carries `updated_at` so two-way sync is addable later without a migration.

**4. Recurrence math lives in Kotlin only — the whole schedule is materialized.**
This is the most important call in the design. If Kotlin computed due dates *and* Apps Script
computed due dates, the two would drift on end-of-month, leap-year, and interval edge cases.
Instead, because plans are finite, the app expands each plan into **every** installment row and
writes them all to an `Occurrences` tab. Apps Script then contains zero date arithmetic — it
asks only "any unpaid row whose `due_date` string equals today?".

Three things fall out of this for free:
- Payoff progress ("payment 7 of 24", remaining balance, projected payoff date).
- Emails keep working indefinitely even if the phone never syncs again — all future dates are
  already in the sheet. Only *paid* state goes stale.
- Per-installment amounts, so a final remainder payment can differ from the rest.

**5. Inexact daily scheduling via WorkManager, not exact alarms.**
Android 12+ gates exact alarms behind `SCHEDULE_EXACT_ALARM` (user-granted) or `USE_EXACT_ALARM`
(Play-restricted to alarm/calendar apps). A bill digest does not need minute precision. See
"Known risks" for the Doze/OEM caveat.

**Scope note:** the user selected *only* the morning digest (no advance warning, no overdue nag,
no weekly email). Paid-tracking is in, which means overdue detection is already computable — but
we are deliberately **not** shipping it. The schema supports adding all three later with no
migration.

---

## Data model

### Room entities

`BillEntity` — the plan definition
```
id                    String (UUID)      PK
name                  String
payee                 String?
installmentAmountCents Long              amounts are Long cents, never Double
frequency             Frequency          WEEKLY | MONTHLY | SEMI_MONTHLY | YEARLY
intervalCount         Int                bi-weekly = WEEKLY with intervalCount 2
firstDueDate          LocalDate
endMode               EndMode            BY_COUNT | BY_DATE
installmentCount      Int?               exactly one of these two is set;
endDate               LocalDate?         the other is derived at generation time
finalAmountCents      Long?              optional remainder for the last payment
autopay               Boolean            informational; still notified, worded differently
notificationsEnabled  Boolean
notes                 String?
createdAt / updatedAt Instant
archivedAt            Instant?           soft delete, so sync can tombstone
```

`OccurrenceEntity` — one materialized installment
```
id           String   PK, deterministic: "$billId#$sequence"
billId       String   FK, indexed, cascade delete
sequence     Int      1-based
dueDate      LocalDate
amountCents  Long
paidOn       LocalDate?
paidAmountCents Long?
updatedAt    Instant
```

The deterministic ID matters: when a plan is edited we regenerate the schedule, and paid marks
must survive. **Regeneration rule:** rebuild occurrences, then re-apply `paidOn`/`paidAmountCents`
from the old rows by matching `sequence`. Sequences past the new count are dropped.

Derived (not stored): `paidCount`, `remainingBalanceCents`, `projectedPayoffDate` (= due date of
the last unpaid occurrence), and status per occurrence (UPCOMING / DUE_TODAY / OVERDUE / PAID).

### Schedule generation — the one genuinely risky piece

`domain/schedule/ScheduleGenerator.kt`, a pure function with no Android dependencies so it is
trivially unit-testable.

```kotlin
fun generate(bill: Bill): List<Occurrence>
```

Rules that must be encoded explicitly:

- **WEEKLY(n):** `firstDueDate.plusWeeks(n * i)`. Straightforward.
- **MONTHLY(n):** must anchor on the *original* day-of-month, not the previously clamped one.
  Starting Jan 31 must yield Jan 31 → Feb 28 → **Mar 31**, not Mar 28. Implement as
  `YearMonth.from(first).plusMonths(n*i).atDay(min(anchorDay, month.lengthOfMonth()))`.
- **SEMI_MONTHLY:** two anchor days per month (commonly 1st and 15th).
- **YEARLY(n):** `plusYears`, with Feb 29 clamping to Feb 28 in non-leap years.
- **Termination:** by `installmentCount` (inclusive) or by `endDate` (inclusive — an occurrence
  landing exactly on `endDate` counts). Hard safety cap at 600 occurrences to prevent a
  misconfigured plan from generating unbounded rows.
- **Amounts:** every installment gets `installmentAmountCents`, except the last which gets
  `finalAmountCents` when set.
- Explicitly **out of scope:** business-day / weekend adjustment. Noted so nobody adds it by
  accident.

This file gets table-driven unit tests before any UI exists.

---

## Google Sheet layout

One spreadsheet, three tabs. **Format the `due_date`, `paid_on`, and `notified_on` columns as
plain text**, and always read them with `getDisplayValues()`. Sheets will otherwise silently
coerce `2026-08-10` into a Date object that `getValues()` re-materializes in the script's
timezone — a classic off-by-one-day bug. Dates are `yyyy-MM-dd` strings end to end and are
compared as strings; no timezone math anywhere.

**`Bills`** — `bill_id, name, payee, frequency, interval, installment_amount, first_due,
end_date, installment_count, autopay, notes, active, updated_at`

**`Occurrences`** — `occurrence_id, bill_id, bill_name, sequence, due_date, amount, paid,
paid_on, notified_on, updated_at`

**`Settings`** — key/value: `email_recipients` (comma-separated), `timezone`, `last_sync_at`,
`digest_enabled`.

`notified_on` is written **only** by Apps Script and must survive a sync overwrite (see below).

---

## Sync protocol

The app never writes cells. It POSTs a full snapshot and Apps Script owns all sheet writes —
which keeps merge logic in exactly one place.

Request to the Web App URL:
```json
{ "secret": "…", "action": "sync",
  "payload": { "bills": [...], "occurrences": [...], "settings": {...} } }
```

`doPost` then:
1. Validates the shared secret; returns `{ok:false, error:"unauthorized"}` otherwise.
2. Takes a `LockService.getScriptLock()` so a concurrent trigger can't interleave.
3. Reads existing `Occurrences` into a `Map<occurrence_id, notified_on>`.
4. Clears and rewrites both data tabs from the snapshot in one `setValues()` call per tab.
5. **Restores `notified_on`** from the map by `occurrence_id`.
6. Stamps `Settings!last_sync_at` and returns `{ok:true, billCount, occurrenceCount}`.

Full-snapshot replace rather than diffing: the dataset is a few hundred rows, and replace is
impossible to get subtly wrong. Note that Apps Script web apps 302-redirect to
`script.googleusercontent.com`; OkHttp follows redirects by default, but the POST body must be
preserved — verify this during implementation.

**When the app syncs:** debounced after any mutation, on app foreground, and via a
`CoroutineWorker` with a `NetworkType.CONNECTED` constraint and exponential backoff. A local
`isDirty` flag gates it and clears only on a successful response.

---

## Android app

### Stack
Kotlin • Jetpack Compose + Material 3 • Navigation Compose • Room (KSP) • WorkManager • Hilt
(`@HiltWorker` makes worker injection one annotation) • OkHttp + kotlinx-serialization (Retrofit
is overkill for a single endpoint) • DataStore Preferences.
`minSdk 26` (java.time without desugaring), `compileSdk/targetSdk 35`. Single `app` module.

### Screens
1. **Home** — "Due today" card, "This week" section, then active plans each with a progress bar
   (`7 / 24 · $1,840 left · payoff Mar 2027`).
2. **Bill detail** — plan summary, payoff stats, full occurrence list with paid toggles.
3. **Add/Edit bill** — form with a **live schedule preview** showing the first ~6 generated dates
   and the computed end date. This is the highest-value UX detail in the app: a wrong frequency or
   anchor day is caught at entry instead of six weeks later.
4. **Settings** — digest time (default 07:00), Web App URL + secret with a "Test connection"
   button, "Sync now" with last-sync status, and a debug "Run digest now".

### Morning digest
`work/DailyDigestWorker.kt`, a `CoroutineWorker`:
- Queries Room for unpaid occurrences where `dueDate == today`.
- If non-empty, posts **one grouped notification** on a HIGH-importance `due_today` channel —
  title "3 payments due today", body listing name + amount. Tapping deep-links to Home.
- Silent on days with nothing due.
- Re-enqueues itself for the next occurrence of the configured time before returning.

Enqueued as unique work named `daily-digest` via `OneTimeWorkRequest` with an initial delay to
the next configured local time. Use **`ExistingWorkPolicy.KEEP`** on app start (so opening the
app doesn't push the pending run out) and **`REPLACE`** only when the digest time setting
changes. WorkManager survives reboot on its own; a `BOOT_COMPLETED` receiver is optional
belt-and-braces.

`POST_NOTIFICATIONS` is requested at first launch on API 33+.

---

## Apps Script

`appsscript/` in this repo, pushed with `clasp`. Set `"timeZone"` in `appsscript.json` to the
household timezone and keep it consistent with the phone.

- **`Sync.gs`** — `doPost(e)`, per the protocol above.
- **`Digest.gs`** — `sendDailyDigest()`:
  1. Read `Occurrences` via `getDisplayValues()`.
  2. Filter `due_date === todayStr && paid !== "TRUE" && notified_on !== todayStr`.
  3. If empty, return without sending — no "nothing due" noise.
  4. Compose an HTML table (bill, amount, payment n of N) and `MailApp.sendEmail()` to the
     recipients in `Settings`.
  5. Write `notified_on = todayStr` for exactly those rows.

  Step 2's `notified_on` check plus step 5 make this **idempotent** — a double-fired trigger
  cannot double-send. Worth testing explicitly.
  If `last_sync_at` is more than 7 days old, append a "⚠️ phone hasn't synced in N days — paid
  status may be stale" footer.
- **`Setup.gs`** — `installTrigger()` creating a daily time-driven trigger in the 6–7am window
  (Apps Script time triggers are hour-windowed, not exact — acceptable, and it matches the
  inexact WorkManager schedule on the phone).

Apps Script emails the project owner automatically when a trigger throws, so failure alerting is
free.

---

## Repository layout

```
android/                      Gradle project
  app/src/main/java/com/awacker/billsnotifier/
    data/{local,remote,repository}/
    domain/{model,schedule}/
    ui/{home,detail,edit,settings}/
    work/
    di/
  app/src/test/…              ScheduleGenerator tests
appsscript/
  appsscript.json  Sync.gs  Digest.gs  Setup.gs  .clasp.json.example
docs/SETUP.md
.gitignore
```

Package `com.awacker.billsnotifier` (easily changed if you'd rather it match the repo name).

**Secrets:** `WEBAPP_URL` and `SHARED_SECRET` live in `local.properties` (gitignored) and reach
code via `buildConfigField`. `docs/SETUP.md` documents creating the sheet, deploying the web app
("Execute as: me", "Who has access: anyone with the link"), generating a secret, and installing
the trigger.

---

## Build order

Sequenced so the riskiest logic is proven before anything is built on top of it.

| Phase | Work |
|---|---|
| 0 | Repo scaffolding: Gradle project, `.gitignore`, `docs/SETUP.md` skeleton |
| 1 | **Domain model + `ScheduleGenerator` + unit tests.** No UI. Highest risk, done first |
| 2 | Room entities, DAOs, `BillRepository` with regenerate-preserving-paid-state logic |
| 3 | Compose UI: Home → Detail → Add/Edit (with live preview) → Settings |
| 4 | `DailyDigestWorker`, notification channel, `POST_NOTIFICATIONS`, scheduling + re-enqueue |
| 5 | Apps Script project, sheet tabs, `doPost` sync, sync worker, `sendDailyDigest` |
| 6 | `docs/SETUP.md` completion and end-to-end verification |

---

## Verification

**Unit — schedule correctness (the part most likely to be wrong):**
```
cd android && ./gradlew :app:testDebugUnitTest
```
Table-driven cases that must pass: bi-weekly 26 payments; monthly starting Jan 31 (asserting
Feb 28 → **Mar 31**); monthly starting on the 15th; semi-monthly 1st/15th; yearly starting Feb 29;
`BY_DATE` termination landing exactly on `endDate`; final-remainder amount applied to the last
row only; the 600-row safety cap.

Room DAO tests in-memory: regenerating a plan after an edit preserves paid marks by `sequence`.

**Manual — app:**
1. Create a bi-weekly, 24-payment plan. Confirm the edit-screen preview dates and computed end
   date are right *before* saving.
2. Detail screen shows `0 / 24`, correct remaining balance, correct projected payoff date.
3. Mark payments 1–3 paid; confirm progress, balance, and payoff date all move.
4. Edit the plan's amount; confirm those three stay paid.

**Manual — notification:**
Set the digest time two minutes out in Settings, background the app, confirm the digest fires
listing only unpaid items due today. Then use Settings → "Run digest now" for the fast path.
Verify a day with nothing due produces no notification.

**Manual — sync:**
Settings → "Test connection" (expects `{ok:true}`), then "Sync now". Open the sheet and confirm
`Bills` and `Occurrences` match the app, and that `Settings!last_sync_at` updated.

**Manual — email + idempotency:**
1. Temporarily set an occurrence's `due_date` to today in the app and sync.
2. Run `sendDailyDigest()` from the Apps Script editor. Confirm the email arrives at both
   addresses and `notified_on` is stamped.
3. **Run it a second time. Confirm no second email is sent** — this is the idempotency check.
4. Sync again from the phone and confirm `notified_on` **survived** the overwrite.
5. Confirm a day with nothing due sends no email at all.

---

## Known risks / things to watch

1. **OEM background-work killing is the most likely real-world failure.** Samsung, Xiaomi, and
   others aggressively kill background work, which can delay or drop the WorkManager digest.
   Mitigation: detect via `isIgnoringBatteryOptimizations` and prompt once to exempt the app.
   The email path is unaffected — Apps Script runs on Google's infrastructure — which is a quiet
   argument for enabling the email to *both* addresses, not just the spouse's.
2. **Doze drift.** The digest may land later than the configured time. Acceptable for bills; if
   it proves annoying, add an opt-in exact-alarm path behind `SCHEDULE_EXACT_ALARM`.
3. **Sheets date coercion.** Addressed by plain-text columns + `getDisplayValues()`, but it will
   silently reappear if anyone reformats a column by hand.
4. **Shared secret in the APK.** Acceptable for a private two-person app; rotating it means
   re-deploying the script and rebuilding the app.
5. **Timezone drift** between `appsscript.json` and the phone. Both must name the same zone.

## Deliberately deferred

Advance warning (N days out), overdue nagging, weekly look-ahead email, mark-paid-from-email
links, two-way sheet editing, weekend/business-day adjustment, and pushing occurrences to a
shared Google Calendar (a cheap future way to give the spouse her own reminders). None require a
schema migration to add later.
