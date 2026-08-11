'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadAppsScript } = require('./harness');

const gs = loadAppsScript().context;

test('collectScriptOwnedColumns indexes notified_on by occurrence id', () => {
  const preserved = gs.collectScriptOwnedColumns([
    { occurrence_id: 'a#1', notified_on: '2026-03-15' },
    { occurrence_id: 'a#2', notified_on: '' },
    { occurrence_id: 'b#1', notified_on: '2026-02-01' },
  ]);
  assert.deepEqual(Object.keys(preserved).sort(), ['a#1', 'b#1']);
  assert.equal(preserved['a#1'].notified_on, '2026-03-15');
});

test('collectScriptOwnedColumns ignores rows without an id', () => {
  const preserved = gs.collectScriptOwnedColumns([{ occurrence_id: '', notified_on: '2026-03-15' }]);
  assert.deepEqual(preserved, {});
});

/**
 * The regression this merge exists to prevent: without it, every sync wipes notified_on
 * and the next digest re-emails bills that were already announced.
 */
test('restoreScriptOwnedColumns carries notified_on across a full snapshot overwrite', () => {
  const preserved = gs.collectScriptOwnedColumns([
    { occurrence_id: 'a#1', notified_on: '2026-03-15' },
  ]);
  const incoming = [
    { occurrence_id: 'a#1', bill_name: 'Car loan', notified_on: '' },
    { occurrence_id: 'a#2', bill_name: 'Car loan', notified_on: '' },
  ];

  const merged = gs.restoreScriptOwnedColumns(incoming, preserved);

  assert.equal(merged[0].notified_on, '2026-03-15');
  assert.equal(merged[1].notified_on, '');
  assert.equal(merged[0].bill_name, 'Car loan', 'phone-owned columns must still come from the app');
});

test('restoreScriptOwnedColumns leaves unknown ids untouched', () => {
  const merged = gs.restoreScriptOwnedColumns([{ occurrence_id: 'new#1', notified_on: '' }], {
    'old#1': { notified_on: '2026-01-01' },
  });
  assert.equal(merged[0].notified_on, '');
});

test('restoreScriptOwnedColumns does not mutate its input', () => {
  const incoming = [{ occurrence_id: 'a#1', notified_on: '' }];
  gs.restoreScriptOwnedColumns(incoming, { 'a#1': { notified_on: '2026-03-15' } });
  assert.equal(incoming[0].notified_on, '', 'the caller’s array should be left alone');
});

test('a sync followed by a merge keeps the digest idempotent across the round trip', () => {
  // Digest ran this morning and stamped the row...
  const inSheet = [{ occurrence_id: 'a#1', due_date: '2026-03-15', paid: 'FALSE', notified_on: '2026-03-15' }];
  // ...then the phone syncs, which knows nothing about notified_on.
  const fromPhone = [{ occurrence_id: 'a#1', due_date: '2026-03-15', paid: 'FALSE', notified_on: '' }];

  const merged = gs.restoreScriptOwnedColumns(fromPhone, gs.collectScriptOwnedColumns(inSheet));

  assert.equal(gs.selectDueRows(merged, '2026-03-15').length, 0, 'must not re-notify after a sync');
});

test('constantTimeEquals matches identical strings and rejects everything else', () => {
  assert.equal(gs.constantTimeEquals('s3cret', 's3cret'), true);
  assert.equal(gs.constantTimeEquals('s3cret', 's3creT'), false);
  assert.equal(gs.constantTimeEquals('s3cret', 's3cret '), false);
  assert.equal(gs.constantTimeEquals('', ''), true);
  assert.equal(gs.constantTimeEquals(null, 'x'), false);
  assert.equal(gs.constantTimeEquals(undefined, undefined), false);
});

test('isTruthyFlag understands the sheet’s several spellings of true', () => {
  [true, 'TRUE', 'true', ' True ', 'yes', 'Y', '1'].forEach((value) => {
    assert.equal(gs.isTruthyFlag(value), true, `${JSON.stringify(value)} should be truthy`);
  });
  [false, 'FALSE', 'false', '', '0', 'no', null, undefined].forEach((value) => {
    assert.equal(gs.isTruthyFlag(value), false, `${JSON.stringify(value)} should be falsy`);
  });
});

test('rowsToObjects keys by header and records the 1-based sheet row', () => {
  const objects = gs.rowsToObjects(
    ['occurrence_id', 'amount'],
    [
      ['a#1', '$10.00'],
      ['a#2', '$20.00'],
    ]
  );
  assert.equal(objects[0].occurrence_id, 'a#1');
  assert.equal(objects[0]._rowIndex, 2, 'first data row is sheet row 2, under the header');
  assert.equal(objects[1]._rowIndex, 3);
});

test('objectsToRows projects onto the column order and fills gaps', () => {
  const rows = gs.objectsToRows(
    ['a', 'b', 'c'],
    [{ b: 2, a: 1 }, { a: 9, c: null }]
  );
  assert.deepEqual(rows, [
    [1, 2, ''],
    [9, '', ''],
  ]);
});

test('parseRecipients splits on commas and semicolons and drops junk', () => {
  assert.deepEqual(gs.parseRecipients('a@x.com, b@y.com; c@z.com'), [
    'a@x.com',
    'b@y.com',
    'c@z.com',
  ]);
  assert.deepEqual(gs.parseRecipients('  a@x.com ,, not-an-address '), ['a@x.com']);
  assert.deepEqual(gs.parseRecipients(''), []);
  assert.deepEqual(gs.parseRecipients(null), []);
});

test('daysBetweenIsoDates counts whole days and ignores a time suffix', () => {
  assert.equal(gs.daysBetweenIsoDates('2026-03-01', '2026-03-15'), 14);
  assert.equal(gs.daysBetweenIsoDates('2026-03-01T09:30:00', '2026-03-08'), 7);
  assert.equal(gs.daysBetweenIsoDates('2026-03-15', '2026-03-15'), 0);
  assert.equal(gs.daysBetweenIsoDates('', '2026-03-15'), null);
  assert.equal(gs.daysBetweenIsoDates(null, '2026-03-15'), null);
});

test('daysBetweenIsoDates spans a leap day correctly', () => {
  assert.equal(gs.daysBetweenIsoDates('2028-02-28', '2028-03-01'), 2);
  assert.equal(gs.daysBetweenIsoDates('2026-02-28', '2026-03-01'), 1);
});

test('escapeHtml neutralises markup', () => {
  assert.equal(gs.escapeHtml('<script>&"\''), '&lt;script&gt;&amp;&quot;&#39;');
  assert.equal(gs.escapeHtml(null), '');
});

test('generateSecret produces a long, unpredictable hex string', () => {
  const secret = gs.generateSecret();
  // Two UUIDs with the dashes removed: 256 bits of entropy, safe in a URL or a text field.
  assert.match(secret, /^[0-9a-f]{64}$/);
  assert.notEqual(secret, gs.generateSecret());
});

/**
 * The regression that stranded the mirror: adding the seven machine-readable plan columns
 * changed BILL_COLUMNS, but a sheet that already had rows kept its old 15-column header
 * forever, so the new values landed in unnamed columns and readTable dropped them.
 */
test('headerRepairPlan rewrites a header left behind by an older schema', () => {
  const old = gs.BILL_COLUMNS.slice(0, 15);
  const plan = gs.headerRepairPlan(old, gs.BILL_COLUMNS);
  assert.equal(plan.needsWrite, true);
  assert.equal(plan.staleColumnCount, 0);
});

test('headerRepairPlan leaves an already-current header alone', () => {
  const plan = gs.headerRepairPlan(gs.BILL_COLUMNS.slice(), gs.BILL_COLUMNS);
  assert.deepEqual(plan, { needsWrite: false, staleColumnCount: 0 });
});

test('headerRepairPlan treats an empty tab as needing a header', () => {
  assert.equal(gs.headerRepairPlan([], gs.OCCURRENCE_COLUMNS).needsWrite, true);
  assert.equal(gs.headerRepairPlan(null, gs.OCCURRENCE_COLUMNS).needsWrite, true);
});

test('headerRepairPlan clears columns dropped from the schema', () => {
  const plan = gs.headerRepairPlan(gs.BILL_COLUMNS.concat(['retired_a', 'retired_b']), gs.BILL_COLUMNS);
  assert.equal(plan.needsWrite, false, 'the surviving columns already match, so no rewrite');
  assert.equal(plan.staleColumnCount, 2);
});

test('headerRepairPlan repairs a header whose columns were reordered by hand', () => {
  const shuffled = gs.BILL_COLUMNS.slice();
  const first = shuffled[0];
  shuffled[0] = shuffled[1];
  shuffled[1] = first;
  assert.equal(gs.headerRepairPlan(shuffled, gs.BILL_COLUMNS).needsWrite, true);
});

test('headerRepairPlan tolerates stray whitespace in a hand-edited header', () => {
  const padded = gs.BILL_COLUMNS.map((name) => ' ' + name + ' ');
  assert.equal(gs.headerRepairPlan(padded, gs.BILL_COLUMNS).needsWrite, false);
});
