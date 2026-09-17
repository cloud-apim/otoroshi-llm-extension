// Files the studio hands to the browser: downloads and CSV exports.

export function download(fileName, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// A text a spreadsheet would run as a formula (`=`, `+`, `-`, `@`, tab or carriage return first) is prefixed with a
// quote: values like the end user or the session come from the requests of the applications.
function cell(value) {
  if (value === null || value === undefined) return '';
  let text = typeof value === 'object' ? JSON.stringify(value) : String(value);
  if (typeof value !== 'number' && /^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

// `columns`: [{ label, value: (row) => any }]. RFC 4180, with a byte order mark so spreadsheets read UTF-8.
export function toCsv(columns, rows) {
  const lines = [columns.map((c) => cell(c.label)).join(','), ...rows.map((row) => columns.map((c) => cell(c.value(row))).join(','))];
  return `﻿${lines.join('\r\n')}\r\n`;
}

export function downloadCsv(fileName, columns, rows) {
  download(fileName, toCsv(columns, rows), 'text/csv;charset=utf-8');
}

// `logs-demo-24h-2026-09-17.csv`
export function exportName(...parts) {
  const slug = parts
    .filter(Boolean)
    .map((p) =>
      String(p)
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
    )
    .filter(Boolean)
    .join('-');
  return `${slug}-${new Date().toISOString().substring(0, 10)}.csv`;
}

export const isoDate = (ts) => (ts === null || ts === undefined || ts === '' ? '' : new Date(Number(ts)).toISOString());
