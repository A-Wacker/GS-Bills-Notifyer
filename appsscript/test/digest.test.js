'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppsScript } = require('./harness');

const gs = loadAppsScript().context;

/** Builds an Occurrences row the way readTable() would hand it back (display values). */
function row(overrides) {
  return Object.assign(
    {
      _rowIndex: 2,
      occurrence_id: 'plan-1#1',
      bill_id: 'plan-1',
      bill_name: 'Car loan',
      sequence: '1',
      total_count: '24',
      due_date: '2026-03-15',
      amount: '$250.00',
      paid: 'FALSE',
      paid_on: '',
      notified_on: '',
      updated_at: '2026-03-01T09:00:00',
    },
    overrides
  );
}

test('selectDueRows picks unpaid rows due today', () => {
  const rows = [
    row({ occurrence_id: 'a#1', due_date: '2026-03-15' }),
    row({ occurrence_id: 'b#1', due_date: '2026-03-16' }),
    row({ occurrence_id: 'c#1', due_date: '2026-03-14' }),
  ];
  const due = gs.selectDueRows(rows, '2026-03-15');
  assert.deepEqual(
    due.map((r) => r.occurrence_id),
    ['a#1']
  );
});

test('selectDueRows skips rows already marked paid', () => {
  const rows = [
    row({ occurrence_id: 'a#1', paid: 'TRUE' }),
    row({ occurrence_id: 'b#1', paid: true }),
    row({ occurrence_id: 'c#1', paid: 'Yes' }),
    row({ occurrence_id: 'd#1', paid: 'FALSE' }),
  ];
  const due = gs.selectDueRows(rows, '2026-03-15');
  assert.deepEqual(
    due.map((r) => r.occurrence_id),
    ['d#1']
  );
});

test('selectDueRows is idempotent: a row already notified today is skipped', () => {
  const rows = [row({ notified_on: '2026-03-15' })];
  assert.equal(gs.selectDueRows(rows, '2026-03-15').length, 0);
});

test('selectDueRows re-notifies a row last announced on a previous day', () => {
  const rows = [row({ notified_on: '2026-03-14' })];
  assert.equal(gs.selectDueRows(rows, '2026-03-15').length, 1);
});

test('selectDueRows tolerates stray whitespace in the sheet', () => {
  const rows = [row({ due_date: ' 2026-03-15 ', notified_on: '  ' })];
  assert.equal(gs.selectDueRows(rows, '2026-03-15').length, 1);
});

test('selectDueRows returns nothing on a quiet day, so no email is sent', () => {
  assert.deepEqual(gs.selectDueRows([row(), row()], '2026-06-01'), []);
});

test('sumDisplayAmounts totals currency-formatted display values', () => {
  const rows = [row({ amount: '$250.00' }), row({ amount: '$1,049.99' })];
  assert.equal(gs.sumDisplayAmounts(rows), '$1,299.99');
});

test('sumDisplayAmounts handles plain unformatted numbers', () => {
  assert.equal(gs.sumDisplayAmounts([row({ amount: '250' }), row({ amount: '0.5' })]), '$250.50');
});

test('sumDisplayAmounts groups thousands', () => {
  assert.equal(gs.sumDisplayAmounts([row({ amount: '1234567.89' })]), '$1,234,567.89');
});

test('sumDisplayAmounts degrades to empty rather than a wrong total', () => {
  assert.equal(gs.sumDisplayAmounts([row({ amount: 'n/a' })]), '');
  assert.equal(gs.sumDisplayAmounts([]), '');
});

test('buildDigestSubject names the count and total', () => {
  const subject = gs.buildDigestSubject([row(), row({ amount: '$50.00' })], '2026-03-15');
  assert.match(subject, /^2 payments due today/);
  assert.match(subject, /\$300\.00/);
  assert.match(subject, /2026-03-15/);
});

test('buildDigestSubject uses the singular for one payment', () => {
  assert.match(gs.buildDigestSubject([row()], '2026-03-15'), /^1 payment due today/);
});

test('buildDigestHtml lists each bill with its amount and progress', () => {
  const html = gs.buildDigestHtml([row()], '2026-03-15', 0);
  assert.match(html, /Car loan/);
  assert.match(html, /\$250\.00/);
  assert.match(html, /Payment 1 of 24/);
});

test('buildDigestHtml omits the progress line when the plan length is unknown', () => {
  const html = gs.buildDigestHtml([row({ total_count: '' })], '2026-03-15', 0);
  assert.match(html, /Payment 1/);
  assert.doesNotMatch(html, / of /);
});

test('buildDigestHtml escapes bill names so a stray angle bracket cannot break the layout', () => {
  const html = gs.buildDigestHtml([row({ bill_name: 'Bob <b>& Co</b>' })], '2026-03-15', 0);
  assert.match(html, /Bob &lt;b&gt;&amp; Co&lt;\/b&gt;/);
  assert.doesNotMatch(html, /<b>&/);
});

test('buildDigestHtml warns only once the phone data is genuinely stale', () => {
  assert.doesNotMatch(gs.buildDigestHtml([row()], '2026-03-15', 3), /has not synced/);
  assert.match(gs.buildDigestHtml([row()], '2026-03-15', 9), /has not synced in 9 days/);
});

test('buildDigestHtml handles a null staleness reading', () => {
  assert.doesNotMatch(gs.buildDigestHtml([row()], '2026-03-15', null), /has not synced/);
});

test('buildDigestHtml falls back to the bill id when the name is missing', () => {
  assert.match(gs.buildDigestHtml([row({ bill_name: '' })], '2026-03-15', 0), /plan-1/);
});
