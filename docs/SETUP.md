# Setup

End to end this takes about 20 minutes. Do the Google side first — the app needs the web
app URL from step 2.

---

## 1. Create the spreadsheet and script

1. Create a new Google Sheet. Name it whatever you like; the script creates the tabs.
2. **Extensions → Apps Script.** This creates a script *bound to that sheet*, which matters:
   the script uses `getActiveSpreadsheet()` and the narrow `spreadsheets.currentonly` scope,
   so it can never touch any other file in your Drive.
3. Copy in the four `.gs` files from `appsscript/` plus `appsscript.json`.

   With [clasp](https://github.com/google/clasp), from the `appsscript/` directory:

   ```bash
   npm install -g @google/clasp
   clasp login
   cp .clasp.json.example .clasp.json     # then paste your script ID into it
   clasp push
   ```

   The script ID is under **Project Settings** in the Apps Script editor. Otherwise just
   paste each file in by hand.

4. **Set the timezone.** In `appsscript.json`, `timeZone` must match the phone's. It ships as
   `America/Chicago`. This is the one setting that silently produces wrong behaviour if it
   disagrees — the digest would look for the wrong day's payments.

5. Run `initializeSpreadsheet` once from the editor (pick it from the function dropdown and
   press Run). Google will ask you to authorise the script; the warning screen about an
   unverified app is expected for a script you wrote yourself — choose **Advanced → Go to
   (project name)**.

   This creates the `Bills`, `Occurrences` and `Settings` tabs.

6. On the **Settings** tab, set `email_recipients` to the addresses that should get the
   morning email, comma separated. Put your own address there too, not just your partner's —
   see the note on reliability at the bottom.

---

## 2. Deploy the web app

1. **Deploy → New deployment → Web app.**
2. Set:
   - **Execute as:** Me
   - **Who has access:** Anyone with the link
3. Copy the deployment URL. It ends in `/exec` — the `/dev` URL requires you to be logged in
   and will not work from the app.

"Anyone with the link" sounds alarming. What actually gates the endpoint is the shared
secret below; the URL alone gets a caller nothing but `{"ok":true,"service":...}`. The
alternative — proper OAuth — means a Google Cloud project, an OAuth client tied to your
app's signing fingerprint, and a consent screen, which is a lot of machinery for a
two-person household app.

### Set the shared secret

Generate one:

```bash
openssl rand -base64 32
```

In the Apps Script editor, open `Setup.gs`, paste it into the `secret` variable in
`setSharedSecret()`, run that function once, then **blank the variable out again** so the
secret isn't sitting in the source.

Run `checkConfiguration()` to confirm. It reports whether the secret is set, the timezone,
the recipient list and the remaining daily email quota, without printing the secret.

---

## 3. Install the daily email trigger

Run `installDigestTrigger()` from the editor. It fires `sendDailyDigest` once a day in the
6–7am window of the script's timezone. Apps Script time triggers are hour-windowed rather
than exact — fine here, and it matches the app's own inexact scheduling.

Re-running it replaces the existing trigger rather than adding a second one.

Apps Script emails you automatically if a trigger throws, so failures are not silent.

---

## 4. Build the app

Requires Android Studio (or the Android SDK) and JDK 17+.

Create `android/local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
WEBAPP_URL=https://script.google.com/macros/s/.../exec
SHARED_SECRET=the-secret-you-generated
```

That file is gitignored. Then:

```bash
cd android
./gradlew :app:installDebug
```

Both values can also be set later on the app's Settings screen; putting them here just means
a fresh install is already configured.

**On the secret in the APK:** anyone holding the APK can read both values out of it. This
keeps them out of version control, and nothing more. That is an acceptable trade here — the
sheet holds bill amounts, not credentials, and the deployment URL is unlisted. If it ever
stops being acceptable, rotate the secret via `setSharedSecret()` and rebuild.

> **Note on the `:app` module.** `settings.gradle.kts` only includes it when it can find an
> Android SDK, so `./gradlew :domain:test` works on a machine without one. If `:app` seems to
> not exist, that is why — the build prints a note saying so.

---

## 5. Verify it works

**Schedule maths** — the part most worth trusting:

```bash
cd android && ./gradlew :domain:test     # 105 tests
cd appsscript && npm test                # 45 tests
```

The node suite includes a contract test that runs a Kotlin-generated fixture through the
real script functions, so the two halves are checked against each other.

**The app:**

1. Add a plan — bi-weekly, 24 payments. Check the schedule preview dates *before* saving;
   a wrong start date or cadence is far easier to catch there than six weeks later.
2. The detail screen should show `0 of 24`, the right remaining balance and a projected
   payoff date.
3. Tick payments 1–3. Progress, balance and payoff date should all move.
4. Edit the plan's amount. Those three should stay ticked.

**The notification:**

Set the digest time a couple of minutes ahead in Settings, background the app, and wait.
Then use **Settings → Run digest now** for the fast path. Confirm a day with nothing due
produces no notification at all.

**Sync:**

**Settings → Test connection** should report the script's server time. Then **Sync now**, and
check the sheet: `Bills` and `Occurrences` should match the app, and `Settings!last_sync_at`
should have updated.

**The email, including the part people forget:**

1. Temporarily set an occurrence's due date to today in the app, and sync.
2. Run `sendDailyDigest()` from the Apps Script editor. The email should arrive at every
   address, and `notified_on` should be stamped on those rows.
3. **Run it a second time.** No second email should be sent. This is the idempotency check —
   Apps Script triggers can fire twice, and without it you would get duplicate emails.
4. Sync again from the phone and confirm `notified_on` survived. The phone sends that column
   empty and the script restores it; if that ever breaks, every sync would cause a re-send.
5. Confirm a day with nothing due sends nothing at all.

`previewDailyDigest()` reports what would be sent without sending or stamping anything.

---

## Troubleshooting

**The notification arrives late, or not at all.** This is the most common real problem, and
it is almost always the phone. Samsung, Xiaomi, OnePlus and others aggressively kill
background work. Exempt the app from battery optimisation in system settings. The app
schedules the digest inexactly on purpose — exact alarms on Android 12+ need a permission
Google restricts to alarm and calendar apps — so Doze can push it later into the morning.

The email path is unaffected by any of this, because it runs on Google's servers. That is
the argument for putting your own address on the recipient list: it is the more dependable
of the two notifications, not the fallback.

**Sync says "Unexpected response".** The deployment is serving an HTML page rather than
JSON. Usually the URL ends in `/dev` instead of `/exec`, or access isn't set to "Anyone with
the link".

**Sync says "unauthorized".** The secret in the app doesn't match Script Properties. Re-run
`setSharedSecret()` and re-enter it on the Settings screen.

**The email reports the wrong day's payments.** `timeZone` in `appsscript.json` disagrees
with the phone's timezone.

**Dates in the sheet turned into something else.** Something reformatted the date columns.
They must stay plain-text format — Sheets otherwise parses `2026-01-15` into a Date and
hands it back shifted into the script's timezone. The next sync repairs the formatting.

**A plan's paid ticks vanished after an edit.** Paid state is matched by payment number, so
shortening a plan drops the payments past the new end. Lengthening or re-dating a plan
preserves them.
