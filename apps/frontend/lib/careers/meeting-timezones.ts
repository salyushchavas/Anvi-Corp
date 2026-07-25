/**
 * Fixed 5-zone list used by every meeting-scheduling form in the app.
 *
 * <p>Storage is always the IANA id ({@code Asia/Kolkata} etc.) so
 * downstream code (Java + PostgreSQL) doesn't have to translate short
 * labels. UI only ever renders the short label (IST / ET / CT / MT /
 * PT) — kept out of storage because labels drift and IANA ids don't.</p>
 *
 * <p>Order matters — the picker renders the zones in this order,
 * India first (main team location per the Ops default set at
 * {@code ADMIN_EMAIL=admin@anvicorp.com}), then east-to-west US zones
 * for the rest of the intern population.</p>
 */
export interface MeetingZone {
  /** Short display label. Uppercase, terse. */
  label: 'IST' | 'ET' | 'CT' | 'MT' | 'PT';
  /** IANA id — what actually gets stored + used for conversion. */
  iana: string;
}

export const MEETING_ZONES: readonly MeetingZone[] = [
  { label: 'IST', iana: 'Asia/Kolkata' },
  { label: 'ET',  iana: 'America/New_York' },
  { label: 'CT',  iana: 'America/Chicago' },
  { label: 'MT',  iana: 'America/Denver' },
  { label: 'PT',  iana: 'America/Los_Angeles' },
];

/**
 * Default zone every scheduling form seeds its picker with. India-based
 * ops team is the primary scheduler; interns in the US see the meeting
 * displayed in whatever zone the scheduler picked (or their own local
 * fallback via the display formatter).
 */
export const DEFAULT_MEETING_ZONE = 'Asia/Kolkata';

/**
 * Reverse lookup: IANA id → short label. Returns the raw IANA id
 * (unchanged) when the id isn't in the 5-zone allow-list — that keeps
 * legacy rows that stored something like {@code America/Chicago} rendering
 * legibly instead of hiding.
 */
export function zoneLabelFor(iana: string | null | undefined): string {
  if (!iana) return '';
  const match = MEETING_ZONES.find((z) => z.iana === iana);
  return match ? match.label : iana;
}

/**
 * Wall-clock-in-zone → UTC ISO string. The datetime-local input returns
 * a string like {@code "2026-08-05T15:00"} with no zone information;
 * {@code new Date(str)} interprets it as browser-local time, which is
 * wrong when the scheduler picks a zone different from their own. This
 * helper composes the correct {@code Instant} such that when rendered in
 * {@code iana} the wall clock reads exactly {@code local}.
 *
 * <p>Uses {@link Intl.DateTimeFormat} to derive the zone's offset at
 * that specific date — respects DST transitions correctly. No external
 * dep needed (no moment / date-fns-tz).</p>
 *
 * <p>Returns the string unchanged when the input is empty (defensive
 * for form-not-yet-filled cases). Throws {@link RangeError} for
 * malformed input (letters where digits belong etc.).</p>
 */
export function localInZoneToUtcIso(
  local: string,
  iana: string,
): string {
  if (!local) return local;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(local);
  if (!match) {
    throw new RangeError(`Invalid datetime-local string: ${local}`);
  }
  const y = Number(match[1]);
  const mo = Number(match[2]);
  const d = Number(match[3]);
  const h = Number(match[4]);
  const mi = Number(match[5]);
  const s = match[6] ? Number(match[6]) : 0;

  // Step 1: interpret the wall-clock as UTC first (a naive guess).
  const naiveUtcMs = Date.UTC(y, mo - 1, d, h, mi, s);
  // Step 2: format that naive instant IN the target zone, and see what
  // wall clock comes back. The delta is the zone's offset from UTC at
  // that instant (with DST already resolved).
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: iana,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(naiveUtcMs));
  const partMap: Record<string, string> = {};
  for (const p of parts) partMap[p.type] = p.value;
  const zonedAsUtcMs = Date.UTC(
    Number(partMap.year),
    Number(partMap.month) - 1,
    Number(partMap.day),
    // Intl in 24-hour mode emits '24' for midnight — normalize to 0.
    Number(partMap.hour) === 24 ? 0 : Number(partMap.hour),
    Number(partMap.minute),
    Number(partMap.second ?? 0),
  );
  const offsetMs = zonedAsUtcMs - naiveUtcMs;
  // Step 3: subtract the offset from the naive UTC to get the real UTC
  // instant whose zoned rendering equals the input.
  return new Date(naiveUtcMs - offsetMs).toISOString();
}

/**
 * Format the current moment plus 30 minutes as a {@code datetime-local}
 * input string (YYYY-MM-DDTHH:mm) in the given zone. Rounds up to the
 * next {@code roundToMinutes} boundary (default 5) so the picker
 * doesn't seed a "3:07 PM" oddity when the user just wants "3:10".
 *
 * <p>Used as the initial value of every scheduling form's date input so
 * the ERM / trainer / evaluator gets a sane starting point instead of a
 * blank field.</p>
 */
export function nowPlus30InZone(
  iana: string,
  roundToMinutes = 5,
): string {
  const base = new Date(Date.now() + 30 * 60 * 1000);
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: iana,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(base);
  const partMap: Record<string, string> = {};
  for (const p of parts) partMap[p.type] = p.value;
  const y = partMap.year;
  const mo = partMap.month;
  const d = partMap.day;
  const rawHour = Number(partMap.hour) === 24 ? 0 : Number(partMap.hour);
  const rawMinute = Number(partMap.minute);
  const rounded = Math.ceil(rawMinute / roundToMinutes) * roundToMinutes;
  const h = rounded === 60 ? rawHour + 1 : rawHour;
  const mi = rounded === 60 ? 0 : rounded;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${y}-${mo}-${d}T${pad(h)}:${pad(mi)}`;
}
