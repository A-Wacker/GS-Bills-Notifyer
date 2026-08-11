/**
 * One-time setup, run by hand from the Apps Script editor.
 *
 * Everything needed is in `setUp()` — see docs/SETUP.md. The individual functions below
 * remain callable if you ever want to redo one step on its own.
 *
 * Note that the Apps Script editor is desktop-web only; there is no Extensions menu in the
 * Sheets mobile app. Hence the single entry point: the whole desktop session is fill in two
 * lines, press Run, then deploy.
 */

/** Paste a secret here, or leave blank and setUp() will generate a strong one for you. */
var SETUP_SECRET = '';

/** Comma-separated addresses that should receive the morning email. */
var SETUP_RECIPIENTS = '';

/** Daily digest trigger window. Apps Script fires somewhere inside the hour, not on the dot. */
var DIGEST_HOUR = 9;

/**
 * Does the whole setup: creates the tabs, stores the shared secret, sets the recipients and
 * installs the daily trigger.
 *
 * Fill in SETUP_RECIPIENTS above (and SETUP_SECRET if you want to choose it yourself), then
 * press Run. Safe to re-run — it replaces the trigger rather than stacking a second one.
 *
 * The generated secret is written to the execution log. Copy it into the app's Settings
 * screen, then blank SETUP_SECRET out again if you pasted your own.
 */
function setUp(secret, recipients) {
  var sharedSecret = secret || SETUP_SECRET || generateSecret();
  var emailRecipients = recipients || SETUP_RECIPIENTS;

  initializeSpreadsheet();
  PropertiesService.getScriptProperties().setProperty('SHARED_SECRET', sharedSecret);

  var parsed = parseRecipients(emailRecipients);
  if (parsed.length > 0) {
    setSetting('email_recipients', parsed.join(','));
  }
  setSetting('digest_enabled', 'TRUE');

  installDigestTrigger();

  var summary = {
    sharedSecret: sharedSecret,
    recipients: parsed,
    timeZone: scriptTimeZone(),
    digestHour: DIGEST_HOUR,
    webAppUrl: deployedWebAppUrl(),
  };

  Logger.log(
    [
      '',
      '  Setup complete.',
      '',
      '  Shared secret (copy into the app, Settings screen):',
      '    ' + sharedSecret,
      '',
      '  Recipients: ' + (parsed.length ? parsed.join(', ') : '(none set — the digest cannot email anyone)'),
      '  Time zone:  ' + summary.timeZone + '   (must match the phone)',
      '  Digest:     daily, in the ' + DIGEST_HOUR + ':00 hour',
      '  Web app:    ' + (summary.webAppUrl || '(not deployed yet — Deploy > New deployment, then re-run checkConfiguration)'),
      '',
    ].join('\n')
  );
  return summary;
}

/**
 * A 256-bit secret from two UUIDs.
 *
 * Generating it here rather than telling you to run `openssl rand` means the whole setup is
 * possible without a terminal — which matters, because the editor already forces you onto a
 * desktop browser.
 */
function generateSecret() {
  return (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
}

/** The deployed web app URL, or '' when the script has not been deployed yet. */
function deployedWebAppUrl() {
  try {
    return ScriptApp.getService().getUrl() || '';
  } catch (error) {
    return '';
  }
}

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

  return { ok: true, timeZone: scriptTimeZone() };
}

/**
 * Installs the daily digest trigger, replacing any existing one so re-running doesn't
 * stack up duplicate triggers that would each send the same email.
 */
function installDigestTrigger() {
  removeDigestTrigger();
  ScriptApp.newTrigger('sendDailyDigest').timeBased().atHour(DIGEST_HOUR).everyDays(1).create();
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
 * Reports the current state without revealing the secret. Run this after deploying to read
 * the web app URL back, which saves hunting for it in the deployment dialog.
 */
function checkConfiguration() {
  var settings = getSettingsMap();
  var status = {
    hasSharedSecret: !!PropertiesService.getScriptProperties().getProperty('SHARED_SECRET'),
    webAppUrl: deployedWebAppUrl() || '(not deployed yet)',
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

/** Replaces just the shared secret, leaving everything else alone. */
function rotateSharedSecret() {
  var secret = SETUP_SECRET || generateSecret();
  PropertiesService.getScriptProperties().setProperty('SHARED_SECRET', secret);
  Logger.log('New shared secret (update the app Settings screen too):\n  ' + secret);
  return secret;
}
