/**
 * Shared constants and helpers for the GS-Bills-Notifyer Apps Script.
 *
 * Design note: this script contains NO date arithmetic. The Android app materializes the
 * complete payment schedule and writes every dated installment into the Occurrences tab,
 * so all this side ever does is compare a `yyyy-MM-dd` string against today. A second
 * implementation of recurrence math would drift from the Kotlin one on end-of-month and
 * leap-year cases, so there deliberately isn't one.
 *
 * Functions whose names start with a lowercase letter and take plain data are pure and are
 * covered by the node tests in test/ — keep them free of SpreadsheetApp/MailApp calls.
 */

var SHEET_BILLS = 'Bills';
var SHEET_OCCURRENCES = 'Occurrences';
var SHEET_SETTINGS = 'Settings';

/** The Bills tab doubles as a human-readable dashboard, so it carries derived columns too. */
var BILL_COLUMNS = [
  'bill_id',
  'name',
  'payee',
  'schedule',
  'installment_amount',
  'first_due',
  'end_date',
  'installment_count',
  'paid_count',
  'remaining_amount',
  'next_due',
  'autopay',
  'notes',
  'active',
  'updated_at',
  // Machine-readable plan definition. Everything above is derived or decorative; these are
  // what let a plan be rebuilt rather than merely displayed — needed by a read-only mirror
  // device and by anyone restoring from this sheet as a backup.
  'recurrence_type',
  'recurrence_interval',
  'semi_monthly_days',
  'end_mode',
  'end_on',
  'final_amount',
  'notifications_enabled',
];

var OCCURRENCE_COLUMNS = [
  'occurrence_id',
  'bill_id',
  'bill_name',
  'sequence',
  // Denormalized from the plan so the email can say "payment 7 of 24" without joining
  // back to the Bills tab. The sheet is a mirror and a dashboard, not a normalized store.
  'total_count',
  'due_date',
  'amount',
  'paid',
  'paid_on',
  'notified_on',
  'updated_at',
];

/**
 * Columns holding `yyyy-MM-dd` strings.
 *
 * These are forced to plain-text number format on write. Left as-is, Sheets silently
 * coerces "2026-01-15" into a Date, and getValues() then re-materializes it in the
 * script's timezone — which is how you get an off-by-one-day bug that only shows up for
 * people west of UTC. Everything here stays a string and is compared as a string.
 */
var BILL_DATE_COLUMNS = ['first_due', 'end_date', 'next_due', 'end_on'];
var OCCURRENCE_DATE_COLUMNS = ['due_date', 'paid_on', 'notified_on'];

/** Columns written as real numbers so the sheet can sum them and format as currency. */
var BILL_MONEY_COLUMNS = ['installment_amount', 'remaining_amount', 'final_amount'];
var OCCURRENCE_MONEY_COLUMNS = ['amount'];

var CURRENCY_FORMAT = '$#,##0.00';
var TEXT_FORMAT = '@';

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested in test/)
// ---------------------------------------------------------------------------

/**
 * Interprets the sheet's various spellings of a boolean. Sheets may hand back a real
 * boolean, the string "TRUE" from a checkbox, or "true"/"yes" if a human typed it.
 */
function isTruthyFlag(value) {
  if (value === true) return true;
  if (value === false || value === null || value === undefined) return false;
  var text = String(value).trim().toLowerCase();
  return text === 'true' || text === 'yes' || text === 'y' || text === '1';
}

/** Turns a header row plus data rows into objects keyed by column name. */
function rowsToObjects(header, values) {
  return values.map(function (row, index) {
    var object = { _rowIndex: index + 2 }; // +2: 1-based sheet rows, and row 1 is the header
    header.forEach(function (name, column) {
      object[name] = row[column] === undefined ? '' : row[column];
    });
    return object;
  });
}

/** Projects objects back onto a fixed column order, filling gaps with ''. */
function objectsToRows(columns, objects) {
  return objects.map(function (object) {
    return columns.map(function (name) {
      var value = object[name];
      return value === undefined || value === null ? '' : value;
    });
  });
}

/**
 * Length-independent string comparison for the shared secret.
 *
 * The secret is not high-value — it gates a household bill sheet, and it necessarily ships
 * inside the APK — but comparing without an early return costs nothing.
 */
function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (a.length !== b.length) return false;
  var difference = 0;
  for (var i = 0; i < a.length; i++) {
    difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return difference === 0;
}

/** Whole days between two `yyyy-MM-dd` strings, or null if either is unusable. */
function daysBetweenIsoDates(fromIso, toIso) {
  var from = Date.parse(String(fromIso).slice(0, 10) + 'T00:00:00Z');
  var to = Date.parse(String(toIso).slice(0, 10) + 'T00:00:00Z');
  if (isNaN(from) || isNaN(to)) return null;
  return Math.round((to - from) / 86400000);
}

/** Splits a recipient list that may be comma- or semicolon-separated. */
function parseRecipients(raw) {
  if (!raw) return [];
  return String(raw)
    .split(/[,;]/)
    .map(function (address) {
      return address.trim();
    })
    .filter(function (address) {
      return address.length > 0 && address.indexOf('@') > 0;
    });
}

/** Escapes text for inclusion in the HTML email body. */
function escapeHtml(value) {
  return String(value === null || value === undefined ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// ---------------------------------------------------------------------------
// Spreadsheet access (not unit-tested — thin wrappers over the Sheets API)
// ---------------------------------------------------------------------------

function getSpreadsheet() {
  return SpreadsheetApp.getActiveSpreadsheet();
}

function scriptTimeZone() {
  return Session.getScriptTimeZone();
}

/** Today in the script's timezone as `yyyy-MM-dd`. Must match the phone's timezone. */
function todayString() {
  return Utilities.formatDate(new Date(), scriptTimeZone(), 'yyyy-MM-dd');
}

function nowIsoString() {
  return Utilities.formatDate(new Date(), scriptTimeZone(), "yyyy-MM-dd'T'HH:mm:ss");
}

function getSheetOrCreate(name, columns) {
  var spreadsheet = getSpreadsheet();
  var sheet = spreadsheet.getSheetByName(name);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(name);
  }
  if (columns && sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, columns.length).setValues([columns]).setFontWeight('bold');
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Reads a tab as objects using display values, so dates come back as the literal strings
 * that were written rather than as timezone-shifted Date objects.
 */
function readTable(sheet) {
  var lastRow = sheet.getLastRow();
  var lastColumn = sheet.getLastColumn();
  if (lastRow < 2 || lastColumn < 1) return [];
  var all = sheet.getRange(1, 1, lastRow, lastColumn).getDisplayValues();
  var header = all[0];
  return rowsToObjects(header, all.slice(1));
}

/**
 * Replaces a tab's data rows wholesale and applies the number formats that keep dates as
 * text. Full replacement rather than diffing: the dataset is a few hundred rows and the
 * phone is the only writer, so there is nothing to merge except the columns this script
 * owns (see restoreScriptOwnedColumns in Sync.gs).
 */
function writeTable(sheet, columns, objects, dateColumns, moneyColumns) {
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, columns.length).setValues([columns]).setFontWeight('bold');
    sheet.setFrozenRows(1);
  }

  var existingRows = sheet.getLastRow() - 1;
  if (existingRows > 0) {
    sheet.getRange(2, 1, existingRows, sheet.getLastColumn()).clearContent();
  }
  if (objects.length === 0) return 0;

  // Set text format BEFORE writing, otherwise Sheets parses the date strings on the way in.
  (dateColumns || []).forEach(function (name) {
    var index = columns.indexOf(name);
    if (index >= 0) {
      sheet.getRange(2, index + 1, objects.length, 1).setNumberFormat(TEXT_FORMAT);
    }
  });
  (moneyColumns || []).forEach(function (name) {
    var index = columns.indexOf(name);
    if (index >= 0) {
      sheet.getRange(2, index + 1, objects.length, 1).setNumberFormat(CURRENCY_FORMAT);
    }
  });

  sheet.getRange(2, 1, objects.length, columns.length).setValues(objectsToRows(columns, objects));
  return objects.length;
}

function getSettingsMap() {
  var sheet = getSheetOrCreate(SHEET_SETTINGS, ['key', 'value']);
  var settings = {};
  readTable(sheet).forEach(function (row) {
    if (row.key) settings[String(row.key).trim()] = row.value;
  });
  return settings;
}

function setSetting(key, value) {
  var sheet = getSheetOrCreate(SHEET_SETTINGS, ['key', 'value']);
  var rows = readTable(sheet);
  for (var i = 0; i < rows.length; i++) {
    if (String(rows[i].key).trim() === key) {
      sheet.getRange(rows[i]._rowIndex, 2).setValue(value);
      return;
    }
  }
  sheet.appendRow([key, value]);
}
