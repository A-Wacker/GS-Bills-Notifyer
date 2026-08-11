/**
 * The sync endpoint the Android app posts to.
 *
 * The app never writes cells itself. It POSTs a full snapshot and this script owns every
 * sheet write, which keeps the one piece of merge logic — preserving the columns this
 * script owns — in a single place.
 *
 * Deployed as: Execute as "Me", Who has access "Anyone with the link". The URL is
 * unlisted and the shared secret in Script Properties is the actual gate.
 */

/** Columns written by this script, not by the phone, and therefore preserved across a sync. */
var SCRIPT_OWNED_OCCURRENCE_COLUMNS = ['notified_on'];

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: 'empty request body' });
    }

    var body = JSON.parse(e.postData.contents);
    var expected = PropertiesService.getScriptProperties().getProperty('SHARED_SECRET');
    if (!expected) {
      return jsonResponse({ ok: false, error: 'server not configured: SHARED_SECRET unset' });
    }
    if (!constantTimeEquals(String(body.secret || ''), expected)) {
      return jsonResponse({ ok: false, error: 'unauthorized' });
    }

    switch (body.action) {
      case 'ping':
        // Backs the "Test connection" button in the app's settings screen.
        return jsonResponse({ ok: true, action: 'ping', serverTime: nowIsoString() });
      case 'sync':
        return jsonResponse(handleSync(body.payload || {}));
      // Backs the app's "Send test email" button. Runs the real digest, including its
      // idempotency check, and reports what happened rather than just succeeding.
      case 'digest':
        return jsonResponse(Object.assign({ ok: true }, sendDailyDigest()));
      // Read-only mirror devices download the sheet instead of uploading to it, so the
      // single-writer rule that makes sync safe stays intact.
      case 'pull':
        return jsonResponse(handlePull());
      default:
        return jsonResponse({ ok: false, error: 'unknown action: ' + body.action });
    }
  } catch (error) {
    return jsonResponse({ ok: false, error: String(error && error.message ? error.message : error) });
  }
}

/**
 * A GET on the web app URL is only ever a human poking the deployment in a browser.
 * Answering with a terse status makes "did I deploy this correctly?" easy to check without
 * exposing any bill data.
 */
function doGet() {
  return jsonResponse({ ok: true, service: 'GS-Bills-Notifyer', hint: 'POST to sync' });
}

function handleSync(payload) {
  var bills = payload.bills || [];
  var occurrences = payload.occurrences || [];

  var lock = LockService.getScriptLock();
  // A daily digest trigger could otherwise be stamping notified_on mid-rewrite.
  if (!lock.tryLock(30000)) {
    return { ok: false, error: 'busy: another sync or digest is running' };
  }

  try {
    var occurrencesSheet = getSheetOrCreate(SHEET_OCCURRENCES, OCCURRENCE_COLUMNS);
    var billsSheet = getSheetOrCreate(SHEET_BILLS, BILL_COLUMNS);

    // Capture what this script owns before the snapshot overwrites it, then put it back.
    var preserved = collectScriptOwnedColumns(readTable(occurrencesSheet));
    var merged = restoreScriptOwnedColumns(occurrences, preserved);

    var billCount = writeTable(
      billsSheet,
      BILL_COLUMNS,
      bills,
      BILL_DATE_COLUMNS,
      BILL_MONEY_COLUMNS
    );
    var occurrenceCount = writeTable(
      occurrencesSheet,
      OCCURRENCE_COLUMNS,
      merged,
      OCCURRENCE_DATE_COLUMNS,
      OCCURRENCE_MONEY_COLUMNS
    );

    setSetting('last_sync_at', nowIsoString());
    if (payload.settings) {
      Object.keys(payload.settings).forEach(function (key) {
        setSetting(key, payload.settings[key]);
      });
    }

    return {
      ok: true,
      billCount: billCount,
      occurrenceCount: occurrenceCount,
      preservedNotifications: Object.keys(preserved).length,
      serverTime: nowIsoString(),
    };
  } finally {
    lock.releaseLock();
  }
}

/**
 * Indexes the script-owned columns of the current sheet by occurrence_id.
 * Pure given plain rows — unit-tested.
 */
function collectScriptOwnedColumns(existingRows) {
  var preserved = {};
  existingRows.forEach(function (row) {
    var id = row.occurrence_id;
    if (!id) return;
    var kept = {};
    var any = false;
    SCRIPT_OWNED_OCCURRENCE_COLUMNS.forEach(function (name) {
      if (row[name]) {
        kept[name] = row[name];
        any = true;
      }
    });
    if (any) preserved[id] = kept;
  });
  return preserved;
}

/**
 * Re-applies preserved script-owned values onto the incoming snapshot.
 *
 * Without this, every sync would wipe notified_on and the next digest run would re-send
 * emails for bills that had already been announced. Pure — unit-tested.
 */
function restoreScriptOwnedColumns(incomingRows, preserved) {
  return incomingRows.map(function (row) {
    var kept = preserved[row.occurrence_id];
    if (!kept) return row;
    var merged = {};
    Object.keys(row).forEach(function (key) {
      merged[key] = row[key];
    });
    SCRIPT_OWNED_OCCURRENCE_COLUMNS.forEach(function (name) {
      if (kept[name]) merged[name] = kept[name];
    });
    return merged;
  });
}

/**
 * Returns the sheet's contents for a mirror device.
 *
 * Read-only on purpose: it touches nothing, so a mirror can never overwrite the phone that
 * owns the data. Display values are used throughout, so dates arrive as the literal strings
 * that were written rather than as timezone-shifted Date objects.
 */
function handlePull() {
  var bills = readTable(getSheetOrCreate(SHEET_BILLS, BILL_COLUMNS)).map(stripRowIndex);
  var occurrences = readTable(getSheetOrCreate(SHEET_OCCURRENCES, OCCURRENCE_COLUMNS))
    .map(stripRowIndex);

  return {
    ok: true,
    bills: bills,
    occurrences: occurrences,
    serverTime: nowIsoString(),
  };
}

/** Drops the sheet bookkeeping field so it doesn't travel to the device. */
function stripRowIndex(row) {
  var copy = {};
  Object.keys(row).forEach(function (key) {
    if (key !== '_rowIndex') copy[key] = row[key];
  });
  return copy;
}

function jsonResponse(object) {
  return ContentService.createTextOutput(JSON.stringify(object)).setMimeType(
    ContentService.MimeType.JSON
  );
}
