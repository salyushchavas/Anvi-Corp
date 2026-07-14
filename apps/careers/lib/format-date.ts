// Lightweight date formatters — no date-fns dependency.

const MS = {
  minute: 60_000,
  hour: 3_600_000,
  day: 86_400_000,
};

function safeDate(iso: string | Date): Date | null {
  if (iso instanceof Date) return isNaN(iso.getTime()) ? null : iso;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d;
}

function timePart(d: Date): string {
  return d.toLocaleTimeString('en-US', {
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
}

function shortDate(d: Date): string {
  return d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
  });
}

function shortWeekday(d: Date): string {
  return d.toLocaleDateString('en-US', { weekday: 'short' });
}

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

/** "Tomorrow 10:00 AM", "Today 2:30 PM", "Fri May 23, 2:00 PM", "May 18, 10:00 AM (3 days ago)". */
export function formatRelative(iso?: string | Date | null): string {
  if (!iso) return '—';
  const d = safeDate(iso);
  if (!d) return '—';

  const now = new Date();
  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const startOfTarget = new Date(d);
  startOfTarget.setHours(0, 0, 0, 0);

  const dayDelta = Math.round((startOfTarget.getTime() - startOfToday.getTime()) / MS.day);

  if (dayDelta === 0) return `Today ${timePart(d)}`;
  if (dayDelta === 1) return `Tomorrow ${timePart(d)}`;
  if (dayDelta === -1) return `Yesterday ${timePart(d)}`;

  if (dayDelta > 1 && dayDelta < 7) {
    return `${shortWeekday(d)} ${shortDate(d)}, ${timePart(d)}`;
  }

  if (dayDelta < 0) {
    return `${shortDate(d)}, ${timePart(d)} (${Math.abs(dayDelta)} days ago)`;
  }

  return `${shortDate(d)}, ${timePart(d)}`;
}

/** "Fri, May 23, 2026 at 2:00 PM". */
export function formatFull(iso?: string | Date | null): string {
  if (!iso) return '—';
  const d = safeDate(iso);
  if (!d) return '—';
  const date = d.toLocaleDateString('en-US', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  return `${date} at ${timePart(d)}`;
}

/** "May 18, 2026". */
export function formatDateOnly(iso?: string | Date | null): string {
  if (!iso) return '—';
  const d = safeDate(iso);
  if (!d) return '—';
  return d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

/** Returns true when scheduledAt is in the past (relative to now). */
export function isPast(iso?: string | Date | null): boolean {
  if (!iso) return false;
  const d = safeDate(iso);
  if (!d) return false;
  return d.getTime() < Date.now();
}

/** Builds a local datetime ISO string from <input type="date"> + <input type="time">. */
export function combineDateAndTime(dateStr: string, timeStr: string): string | null {
  if (!dateStr || !timeStr) return null;
  const combined = `${dateStr}T${timeStr}`;
  const d = new Date(combined);
  if (isNaN(d.getTime())) return null;
  return d.toISOString();
}

/**
 * Date-only relative formatter for things like onboarding due dates.
 * Takes a LocalDate ISO string ("2026-05-24") or Date and returns
 * "today", "tomorrow", "in 5 days", "3 days ago", or "May 24".
 */
export function formatDueDate(iso?: string | Date | null): string {
  if (!iso) return '—';
  const d = safeDate(iso);
  if (!d) return '—';

  const today = new Date();
  const a = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const b = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const days = Math.round((b.getTime() - a.getTime()) / MS.day);

  if (days === 0) return 'today';
  if (days === 1) return 'tomorrow';
  if (days === -1) return 'yesterday';
  if (days > 1 && days < 7) return `in ${days} days`;
  if (days < -1 && days > -7) return `${Math.abs(days)} days ago`;
  return shortDate(d);
}

/** Returns today's date as a YYYY-MM-DD string suitable for `<input type="date">`. */
export function todayDateInput(): string {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

/** Splits an ISO timestamp back into `{ date, time }` strings for the form. */
export function splitDateAndTime(iso?: string): { date: string; time: string } {
  if (!iso) return { date: '', time: '' };
  const d = safeDate(iso);
  if (!d) return { date: '', time: '' };
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mn = String(d.getMinutes()).padStart(2, '0');
  return { date: `${yyyy}-${mm}-${dd}`, time: `${hh}:${mn}` };
}
