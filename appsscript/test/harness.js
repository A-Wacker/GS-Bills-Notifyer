'use strict';

/**
 * Loads the .gs sources into a single shared context, the way Apps Script does, so the
 * pure functions can be exercised under node's built-in test runner.
 *
 * Only functions that take plain data are worth testing here. Anything calling
 * SpreadsheetApp or MailApp is a thin wrapper and is verified by hand against a real
 * spreadsheet (see docs/SETUP.md).
 */

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SOURCE_DIR = path.join(__dirname, '..');
const SOURCES = ['Common.gs', 'Sync.gs', 'Digest.gs', 'Setup.gs'];

/** Minimal stand-ins for the Apps Script globals the sources touch at load time. */
function createGoogleStubs() {
  const sentEmails = [];
  const scriptProperties = new Map();

  return {
    sentEmails,
    scriptProperties,
    globals: {
      Logger: { log: () => {} },
      Session: { getScriptTimeZone: () => 'America/Chicago' },
      Utilities: {
        formatDate: (date, _tz, format) => {
          const iso = date.toISOString();
          return format === 'yyyy-MM-dd' ? iso.slice(0, 10) : iso.slice(0, 19);
        },
        getUuid: () => require('node:crypto').randomUUID(),
      },
      MailApp: {
        sendEmail: (options) => sentEmails.push(options),
        getRemainingDailyQuota: () => 100,
      },
      PropertiesService: {
        getScriptProperties: () => ({
          getProperty: (key) => scriptProperties.get(key) ?? null,
          setProperty: (key, value) => scriptProperties.set(key, value),
        }),
      },
      LockService: {
        getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }),
      },
      ContentService: {
        MimeType: { JSON: 'application/json' },
        createTextOutput: (text) => ({
          _text: text,
          setMimeType() {
            return this;
          },
          getContent() {
            return this._text;
          },
        }),
      },
      SpreadsheetApp: { getActiveSpreadsheet: () => null },
      ScriptApp: { getProjectTriggers: () => [] },
    },
  };
}

/**
 * Evaluates the sources in the *current* realm rather than a fresh vm context.
 *
 * A separate context would give the loaded code its own Object/Array prototypes, and
 * assert.deepStrictEqual rejects cross-realm values even when they match structurally.
 * Running in this realm also mirrors Apps Script more closely: every .gs file shares one
 * global scope, so top-level `function` and `var` declarations see each other.
 */
function loadAppsScript() {
  const stubs = createGoogleStubs();
  Object.assign(globalThis, stubs.globals);

  for (const file of SOURCES) {
    const source = fs.readFileSync(path.join(SOURCE_DIR, file), 'utf8');
    vm.runInThisContext(source, { filename: file });
  }

  return { context: globalThis, ...stubs };
}

module.exports = { loadAppsScript };
