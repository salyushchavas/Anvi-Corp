/**
 * Interview-time formatting helpers. Centralised so every surface (intern,
 * ERM list, ERM detail, application detail) renders the scheduled time
 * unambiguously: in the interview's stored IANA timezone with the zone
 * abbreviation appended (e.g. "Tue, Jun 16, 2026 · 2:30 PM EDT") instead
 * of the browser's local time with the zone pasted next to it.
 *
 * <p>Backed by {@code Intl.DateTimeFormat} with {@code timeZone} +
 * {@code timeZoneName: 'short'} — no extra dependency. Falls back to
 * {@code UTC} when the stored value is null/blank/invalid (e.g. legacy
 * pre-Phase 2 interviews; the DB default makes this rare).</p>
 */

const FALLBACK_ZONE = 'UTC';

function safeZone(zone: string | null | undefined): string {
  const z = (zone ?? '').trim();
  if (!z) return FALLBACK_ZONE;
  // Intl.DateTimeFormat throws on unknown zones; verify cheaply.
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: z });
    return z;
  } catch {
    return FALLBACK_ZONE;
  }
}

/**
 * Format an ISO datetime in the interview's scheduled zone.
 * Example output: "Tue, Jun 16, 2026 · 2:30 PM EDT".
 */
export function formatInZone(
  iso: string | null | undefined,
  zone: string | null | undefined,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const tz = safeZone(zone);
  const dateFmt = new Intl.DateTimeFormat('en-US', {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
    timeZone: tz,
  });
  const timeFmt = new Intl.DateTimeFormat('en-US', {
    hour: 'numeric', minute: '2-digit', hour12: true,
    timeZone: tz, timeZoneName: 'short',
  });
  return `${dateFmt.format(date)} · ${timeFmt.format(date)}`;
}

/**
 * If the viewer's browser timezone differs from the scheduled zone,
 * return a parenthetical "(your local: …)" string. Otherwise return null.
 * Surfaces use this as a secondary line so a US-zoned interview shown to
 * an IST-resident intern reads as e.g.
 *   Tue, Jun 16, 2026 · 2:30 PM EDT
 *   (your local: Wed, Jun 17, 12:00 AM IST)
 */
export function formatLocalIfDifferent(
  iso: string | null | undefined,
  zone: string | null | undefined,
): string | null {
  if (!iso) return null;
  const scheduledZone = safeZone(zone);
  let localZone: string;
  try {
    localZone = Intl.DateTimeFormat().resolvedOptions().timeZone || FALLBACK_ZONE;
  } catch {
    return null;
  }
  if (localZone === scheduledZone) return null;
  return `your local: ${formatInZone(iso, localZone)}`;
}

/**
 * The browser's IANA timezone, used as the default value in the
 * scheduling selector. Falls back to {@code UTC} on the very rare
 * browsers where the API is unavailable.
 */
export function detectBrowserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || FALLBACK_ZONE;
  } catch {
    return FALLBACK_ZONE;
  }
}

/** True when the IANA id parses cleanly. Used by TimezoneSelect's
 *  "Other…" input to gate submit. */
export function isValidIanaTimezone(zone: string): boolean {
  const z = zone.trim();
  if (!z) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: z });
    return true;
  } catch {
    return false;
  }
}
