/**
 * One-time setup helpers, run by hand from the Apps Script editor.
 * See docs/SETUP.md for the full walkthrough.
 */

/** Daily digest trigger window. Apps Script fires somewhere inside the hour, not on the dot. */
var DIGEST_HOUR = 6;

/**
 * Creates the tabs, headers and default settings rows.
 * Safe to re-run: existing tabs and values are left alone.
 */
function initializeSpreadsheet() {
  getSheetOrCreate(SHEET_BILLS, BILL_COLUMNS);
  getSheetOrCreate(SHEET_OCCURRENCES, OCCURRENCE_COLUMNS);
  getSheetOrCreate(SHEET_SETTINGS, ['key', 'value']);

  var settings = getSettingsMap();
  var defaults = {
    email_recipients: '',
    digest_enabled: 'TRUE',
    timezone: scriptTimeZone(),
    last_sync_at: '',
  };
  Object.keys(defaults).forEach(function (key) {
    if (settings[key] === undefined) setSetting(key, defaults[key]);
  });

  Logger.log('Spreadsheet initialized. Set Settings!email_recipients before enabling the trigger.');
  return { ok: true, timeZone: scriptTimeZone() };
}

/**
 * Installs the daily digest trigger, replacing any existing one so re-running doesn't
 * stack up duplicate triggers that would each try to send the same email.
 */
function installDigestTrigger() {
  removeDigestTrigger();
  ScriptApp.newTrigger('sendDailyDigest').timeBased().atHour(DIGEST_HOUR).everyDays(1).create();
  Logger.log(
    'Digest trigger installed for the ' + DIGEST_HOUR + ':00 hour, ' + scriptTimeZone() + '.'
  );
  return { ok: true, hour: DIGEST_HOUR, timeZone: scriptTimeZone() };
}

function removeDigestTrigger() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'sendDailyDigest') {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  return { ok: true, removed: removed };
}

/**
 * Stores the shared secret the Android app must present. Paste a long random string here,
 * run once, then clear it from the editor so it isn't left sitting in the source.
 *
 * Generate one with: openssl rand -base64 32
 */
function setSharedSecret() {
  var secret = ''; // <-- paste, run, then blank this out again
  if (!secret) {
    throw new Error('Set the secret variable in setSharedSecret() before running it.');
  }
  PropertiesService.getScriptProperties().setProperty('SHARED_SECRET', secret);
  Logger.log('Shared secret stored. Clear the literal from this function now.');
}

/** Confirms setup without revealing the secret. */
function checkConfiguration() {
  var settings = getSettingsMap();
  var status = {
    hasSharedSecret: !!PropertiesService.getScriptProperties().getProperty('SHARED_SECRET'),
    timeZone: scriptTimeZone(),
    recipients: parseRecipients(settings.email_recipients),
    digestEnabled: settings.digest_enabled === undefined || isTruthyFlag(settings.digest_enabled),
    lastSyncAt: settings.last_sync_at || '(never)',
    digestTriggers: ScriptApp.getProjectTriggers().filter(function (trigger) {
      return trigger.getHandlerFunction() === 'sendDailyDigest';
    }).length,
    remainingDailyEmailQuota: MailApp.getRemainingDailyQuota(),
  };
  Logger.log(JSON.stringify(status, null, 2));
  return status;
}
