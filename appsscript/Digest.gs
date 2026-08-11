/**
 * The morning email digest — the reason this project uses a Google Sheet at all.
 *
 * A daily time-driven trigger runs sendDailyDigest(). It emails everyone listed in the
 * Settings tab, so a household member who never installs the Android app still hears about
 * a payment falling due. It is also the more reliable of the two notification paths: this
 * runs on Google's infrastructure, whereas the on-device notification is at the mercy of
 * whatever the phone's manufacturer does to background work.
 */

/** Warn in the email once the phone's data is this stale, since paid marks may be wrong. */
var STALE_SYNC_WARNING_DAYS = 7;

function sendDailyDigest() {
  var lock = LockService.getScriptLock();
  // Triggers can fire twice; the lock plus the notified_on check below make that harmless.
  if (!lock.tryLock(30000)) {
    Logger.log('Digest skipped: a sync or another digest run holds the lock.');
    return { sent: false, reason: 'locked' };
  }

  try {
    var settings = getSettingsMap();
    if (settings.digest_enabled !== undefined && !isTruthyFlag(settings.digest_enabled)) {
      return { sent: false, reason: 'disabled' };
    }

    var today = todayString();
    var sheet = getSheetOrCreate(SHEET_OCCURRENCES, OCCURRENCE_COLUMNS);
    var rows = readTable(sheet);
    var status = digestStatus(rows, today);
    var due = status.pending;
    var counts = {
      date: today,
      dueTodayCount: status.dueTodayCount,
      pendingCount: status.pendingCount,
    };

    if (status.dueTodayCount === 0) {
      // Deliberately silent. A daily "nothing due" email trains people to ignore it.
      Logger.log('Digest: nothing due on ' + today + '.');
      return Object.assign({ sent: false, reason: 'nothing due' }, counts);
    }
    if (due.length === 0) {
      Logger.log('Digest: already emailed ' + status.dueTodayCount + ' item(s) today.');
      return Object.assign({ sent: false, reason: 'already notified today' }, counts);
    }

    var recipients = parseRecipients(settings.email_recipients);
    if (recipients.length === 0) {
      Logger.log('Digest: ' + due.length + ' due but Settings!email_recipients is empty.');
      return Object.assign({ sent: false, reason: 'no recipients' }, counts);
    }

    var staleDays = daysBetweenIsoDates(settings.last_sync_at, today);
    MailApp.sendEmail({
      to: recipients.join(','),
      subject: buildDigestSubject(due, today),
      htmlBody: buildDigestHtml(due, today, staleDays),
      name: 'Bills Notifier',
    });

    markRowsNotified(sheet, due, today);
    Logger.log('Digest: emailed ' + due.length + ' item(s) to ' + recipients.length + ' recipient(s).');
    return Object.assign(
      { sent: true, reason: 'sent', recipients: recipients.length },
      counts
    );
  } finally {
    lock.releaseLock();
  }
}

/**
 * Picks the rows a digest should announce. Pure — unit-tested.
 *
 * The notified_on check is what makes the whole job idempotent: a re-run on the same day,
 * whether from a double-fired trigger or a manual invocation, selects nothing and sends
 * nothing. Note this compares date *strings*; no timezone math happens anywhere here
 * because the Android app already materialized these dates.
 */
function selectDueRows(rows, todayIso) {
  return digestStatus(rows, todayIso).pending;
}

/**
 * Splits today's picture into "owed" and "not yet announced". Pure — unit-tested.
 *
 * Keeping both counts is what lets the caller distinguish a genuinely quiet day from one
 * where the email already went out — from outside, both simply produce no new email.
 */
function digestStatus(rows, todayIso) {
  var dueToday = rows.filter(function (row) {
    return String(row.due_date).trim() === todayIso && !isTruthyFlag(row.paid);
  });
  var pending = dueToday.filter(function (row) {
    return String(row.notified_on).trim() !== todayIso;
  });
  return {
    dueTodayCount: dueToday.length,
    pendingCount: pending.length,
    pending: pending,
  };
}

function buildDigestSubject(due, todayIso) {
  var total = sumDisplayAmounts(due);
  var noun = due.length === 1 ? 'payment' : 'payments';
  return due.length + ' ' + noun + ' due today' + (total ? ' — ' + total : '') + ' (' + todayIso + ')';
}

/** Builds the HTML body. Pure — unit-tested. */
function buildDigestHtml(due, todayIso, staleDays) {
  var rows = due
    .map(function (row) {
      var progress =
        row.sequence && row.total_count
          ? 'Payment ' + escapeHtml(row.sequence) + ' of ' + escapeHtml(row.total_count)
          : row.sequence
            ? 'Payment ' + escapeHtml(row.sequence)
            : '';
      return (
        '<tr>' +
        '<td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;">' +
        escapeHtml(row.bill_name || row.bill_id) +
        '</td>' +
        '<td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;text-align:right;' +
        'font-variant-numeric:tabular-nums;white-space:nowrap;">' +
        escapeHtml(row.amount) +
        '</td>' +
        '<td style="padding:8px 12px;border-bottom:1px solid #e5e7eb;color:#6b7280;' +
        'white-space:nowrap;">' +
        progress +
        '</td>' +
        '</tr>'
      );
    })
    .join('');

  var total = sumDisplayAmounts(due);
  var staleNotice =
    staleDays !== null && staleDays !== undefined && staleDays >= STALE_SYNC_WARNING_DAYS
      ? '<p style="margin:16px 0 0;padding:12px;background:#fef3c7;border-radius:6px;' +
        'color:#92400e;font-size:13px;">The app has not synced in ' +
        escapeHtml(staleDays) +
        ' days, so anything paid since then may still be listed here.</p>'
      : '';

  return (
    '<div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;' +
    'max-width:520px;color:#111827;">' +
    '<h2 style="margin:0 0 4px;font-size:18px;">Due today</h2>' +
    '<p style="margin:0 0 16px;color:#6b7280;font-size:13px;">' +
    escapeHtml(todayIso) +
    '</p>' +
    '<table style="border-collapse:collapse;width:100%;font-size:14px;">' +
    '<tbody>' +
    rows +
    '</tbody>' +
    (total
      ? '<tfoot><tr>' +
        '<td style="padding:8px 12px;font-weight:600;">Total</td>' +
        '<td style="padding:8px 12px;text-align:right;font-weight:600;' +
        'font-variant-numeric:tabular-nums;">' +
        escapeHtml(total) +
        '</td><td></td>' +
        '</tr></tfoot>'
      : '') +
    '</table>' +
    staleNotice +
    '</div>'
  );
}

/**
 * Totals the display strings from the sheet (e.g. "$250.00").
 *
 * Returns '' when any amount can't be parsed, so a malformed cell degrades to "no total
 * line" rather than to a confidently wrong number. Pure — unit-tested.
 */
function sumDisplayAmounts(rows) {
  var totalCents = 0;
  for (var i = 0; i < rows.length; i++) {
    var raw = String(rows[i].amount === undefined ? '' : rows[i].amount)
      .replace(/[$,\s]/g, '')
      .trim();
    if (raw === '' || !/^-?\d+(\.\d+)?$/.test(raw)) return '';
    totalCents += Math.round(parseFloat(raw) * 100);
  }
  if (rows.length === 0) return '';
  var sign = totalCents < 0 ? '-' : '';
  var absolute = Math.abs(totalCents);
  var whole = String(Math.floor(absolute / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  var fraction = String(absolute % 100).padStart(2, '0');
  return sign + '$' + whole + '.' + fraction;
}

/** Stamps notified_on so a later run today is a no-op. */
function markRowsNotified(sheet, rows, todayIso) {
  var column = OCCURRENCE_COLUMNS.indexOf('notified_on') + 1;
  rows.forEach(function (row) {
    sheet.getRange(row._rowIndex, column).setNumberFormat(TEXT_FORMAT).setValue(todayIso);
  });
}

/**
 * Manual smoke test for the editor: reports what today's digest would contain without
 * sending anything or stamping notified_on.
 */
function previewDailyDigest() {
  var today = todayString();
  var rows = readTable(getSheetOrCreate(SHEET_OCCURRENCES, OCCURRENCE_COLUMNS));
  var due = selectDueRows(rows, today);
  var settings = getSettingsMap();
  var preview = {
    date: today,
    timeZone: scriptTimeZone(),
    dueCount: due.length,
    recipients: parseRecipients(settings.email_recipients),
    subject: due.length ? buildDigestSubject(due, today) : '(nothing would be sent)',
  };
  Logger.log(JSON.stringify(preview, null, 2));
  return preview;
}
