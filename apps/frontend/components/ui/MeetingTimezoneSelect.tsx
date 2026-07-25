'use client';

import { MEETING_ZONES } from '@/lib/careers/meeting-timezones';

/**
 * Locked 5-zone picker (IST / ET / CT / MT / PT) used by every meeting
 * scheduling form. Emits the IANA id ({@code Asia/Kolkata},
 * {@code America/New_York}, …) as the value; renders the short label.
 *
 * <p>Deliberately narrower than the legacy {@link
 * TimezoneSelect} — Ops wants a single consistent zone list on every
 * scheduler so trainers, ERM, and evaluators all pick from the same
 * dropdown. No "Other…" free-text — if a new zone is needed, add it to
 * {@link MEETING_ZONES}, not by hand-typing an IANA id on one form.</p>
 */
interface Props {
  value: string;
  onChange: (iana: string) => void;
  disabled?: boolean;
  className?: string;
}

export default function MeetingTimezoneSelect({
  value,
  onChange,
  disabled,
  className,
}: Props) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className={
        (className ?? '') +
        ' w-full rounded-md border border-slate-200 px-3 py-2 text-sm disabled:cursor-not-allowed disabled:bg-slate-100'
      }
    >
      {MEETING_ZONES.map((z) => (
        <option key={z.iana} value={z.iana}>
          {z.label} ({z.iana})
        </option>
      ))}
    </select>
  );
}
