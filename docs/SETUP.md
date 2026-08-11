# Setup

Two halves, and only one of them needs a computer.

| | Where | Roughly |
| --- | --- | --- |
| **Google side** — sheet, script, email trigger | Desktop browser | 10 minutes, once |
| **App side** — install and configure | Your phone | 5 minutes |

**The Apps Script editor is desktop-web only.** The Sheets mobile app has no Extensions
menu — Apps Script, macros and add-ons are all absent from it. You *can* force it by opening
`sheets.google.com` in Chrome for Android and switching on **Desktop site**, and it does
work, but the script editor and the deployment dialog are cramped targets on a phone. A
tablet is tolerable. A laptop is much less annoying.

It is a one-time cost either way. Once the trigger is installed everything runs on Google's
servers, and the sheet itself reads perfectly well in the mobile app afterwards.

---

## Part 1 — the Google side (desktop)

### 1. Create the sheet and script

1. Create a new Google Sheet. Name it whatever you like; the script creates the tabs.
2. **Extensions → Apps Script.** This makes a script *bound to that sheet*, which matters:
   it uses `getActiveSpreadsheet()` and the narrow `spreadsheets.currentonly` scope, so it
   can never touch any other file in your Drive.
3. Copy in the four `.gs` files from [`appsscript/`](../appsscript) plus `appsscript.json`.
   Use the `+` next to *Files* to add each one, matching the names.

   With [clasp](https://github.com/google/clasp) instead, from `appsscript/`:

   ```bash
   npm install -g @google/clasp
   clasp login
   cp .clasp.json.example .clasp.json     # paste your script ID into it
   clasp push
   ```

4. **Set the timezone.** In `appsscript.json`, `timeZone` must match your phone's. It ships
   as `America/Chicago`. This is the one setting that silently misbehaves if it disagrees —
   the digest would look for the wrong day's payments.

### 2. Run `setUp`

Open `Setup.gs` and fill in the line near the top:

```js
var SETUP_RECIPIENTS = 'you@example.com, partner@example.com';
```

Put **your own address there too**, not just your partner's. See the note on reliability at
the end — the email is the more dependable of the two notifications, not the backup.

Leave `SETUP_SECRET` blank and the script generates a strong one for you.

Now pick `setUp` from the function dropdown and press **Run**. Google will ask you to
authorise it; the "unverified app" warning is expected for a script you wrote yourself —
choose **Advanced → Go to (project name)**.

That one function creates the three tabs, stores the shared secret, sets the recipients and
installs the daily trigger. The execution log prints the secret:

```
  Setup complete.

  Shared secret (copy into the app, Settings screen):
    3f9a...

  Recipients: you@example.com, partner@example.com
  Time zone:  America/Chicago   (must match the phone)
  Digest:     daily, in the 6:00 hour
```

Keep that log open, or copy the secret somewhere — you need it on the phone shortly.

Re-running `setUp` is safe. It replaces the trigger rather than stacking a second one that
would send a duplicate email.

### 3. Deploy the web app

**Deploy → New deployment → Web app**, then:

- **Execute as:** Me
- **Who has access:** Anyone with the link

Copy the deployment URL. It ends in `/exec`; the `/dev` URL requires you to be logged in and
will not work from the app.

Lost it later? Run `checkConfiguration` and it prints the URL back, along with everything
else it can see — no hunting through the deployment dialog.

> "Anyone with the link" sounds alarming. What actually gates the endpoint is the shared
> secret; the URL alone returns nothing but `{"ok":true,"service":...}`. The alternative,
> proper OAuth, means a Google Cloud project, an OAuth client tied to your app's signing
> fingerprint, and a consent screen — a lot of machinery for a two-person household app.

---

## Part 2 — the app (phone)

### Option A — install the CI build (no computer needed)

Every push builds a debug APK in GitHub Actions.

1. On your phone, open the repo's **Actions** tab and pick the most recent green run on your
   branch.
2. Download the **debug-apk** artifact. It arrives as a zip; extract it with any file
   manager.
3. Tap the `.apk` and allow installing from that source when prompted.

The CI build ships with the URL and secret empty, which is fine — both are enterable on the
Settings screen. That is deliberate, so a CI build is fully usable.

### Option B — build it yourself

Needs Android Studio (or the Android SDK) and JDK 17+. Create `android/local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
WEBAPP_URL=https://script.google.com/macros/s/.../exec
SHARED_SECRET=the-secret-from-the-setUp-log
```

That file is gitignored. Then:

```bash
cd android
./gradlew :app:installDebug
```

Baking the values in just means a fresh install is already configured.

> **On the secret in the APK:** anyone holding the APK can read both values out of it. This
> keeps them out of version control, and nothing more. That is an acceptable trade here —
> the sheet holds bill amounts, not credentials, and the deployment URL is unlisted. If it
> stops being acceptable, run `rotateSharedSecret` and update the Settings screen.

> **On the `:app` module:** `settings.gradle.kts` only includes it when it can find an
> Android SDK, so `./gradlew :domain:test` works on a machine without one. If `:app` seems
> not to exist, that is why — the build prints a note saying so.

### Configure it

Open the app → **Settings**:

1. Paste the **web app URL** and the **shared secret**, and press Save.
2. **Test connection** — it should report the script's server time.
3. Set the **digest time** (default 07:00).
4. **Sync now**, then check the sheet: `Bills` and `Occurrences` should match the app.

---

## Verifying it end to end

**The logic**, if you have a checkout:

```bash
cd android    && ./gradlew :domain:test           # 113 tests
cd android    && ./gradlew :app:testDebugUnitTest  # 25 tests
cd appsscript && npm test                          # 48 tests
```

**The app:**

1. Add a plan — bi-weekly, 24 payments. Check the schedule preview dates *before* saving; a
   wrong start date or cadence is far easier to catch there than six weeks later.
2. The detail screen should show `0 of 24`, the right remaining balance and a payoff date.
3. Tick payments 1–3. Progress, balance and payoff date should all move.
4. Edit the plan's amount. Those three should stay ticked.

**The notification** (device only): set the digest time a couple of minutes ahead,
background the app, and wait. Then use **Settings → Test notification** for the fast path.
Confirm a day with nothing due produces no notification at all.

**The email:** **Settings → Send test email**. This asks the script to run today's digest
and reports back what it did — "Emailed 2 payments to 2 addresses", or why not. The two
paths are independent by design, so the notification working tells you nothing about the
email, and vice versa.

It runs the *real* digest, so it obeys the same once-a-day rule: a second tap says "today's
email already went out" rather than sending a duplicate.

**The email, including the part people skip:**

1. Temporarily set an occurrence's due date to today in the app, and sync.
2. Tap **Send test email** in the app (or run `sendDailyDigest` from the editor). The email
   should arrive at every address, and `notified_on` should be stamped on those rows.
3. **Do it a second time. No second email should be sent** — it should tell you the email
   already went out. This is the idempotency check; Apps Script triggers can fire twice, and
   without it you would get duplicates every morning.
4. Sync again from the phone and confirm `notified_on` survived. The phone sends that column
   empty and the script restores it; if that ever broke, every sync would cause a re-send.
5. Confirm a day with nothing due sends nothing at all.

`previewDailyDigest` reports what would be sent without sending or stamping anything.

---

## Troubleshooting

**The notification arrives late, or not at all.** The most common real problem, and it is
almost always the phone. Samsung, Xiaomi, OnePlus and others aggressively kill background
work — exempt the app from battery optimisation in system settings. The app schedules the
digest inexactly on purpose, because exact alarms on Android 12+ need a permission Google
restricts to alarm and calendar apps, so Doze can push it later into the morning.

None of this touches the email, which runs on Google's servers. That is the argument for
having your own address on the recipient list: it is the more dependable of the two
notifications, not the fallback.

**Sync says "Unexpected response".** The deployment is serving HTML rather than JSON.
Usually the URL ends in `/dev` instead of `/exec`, or access isn't "Anyone with the link".

**Sync says "unauthorized".** The secret in the app doesn't match Script Properties. Run
`rotateSharedSecret` and re-enter the new one on the Settings screen.

**The email reports the wrong day's payments.** `timeZone` in `appsscript.json` disagrees
with the phone's timezone.

**Dates in the sheet turned into something else.** Something reformatted the date columns.
They must stay plain-text — Sheets otherwise parses `2026-01-15` into a Date and hands it
back shifted into the script's timezone. The next sync repairs the formatting.

**A plan's paid ticks vanished after an edit.** Paid state is matched by payment number, so
shortening a plan drops the payments past the new end. Lengthening or re-dating one
preserves them.

**Two identical emails each morning.** Two digest triggers got installed. Run
`removeDigestTrigger`, then `installDigestTrigger`.
