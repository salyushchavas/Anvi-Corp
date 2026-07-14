'use client';

/**
 * IANA timezone selector — used by the ERM interview scheduling flow so
 * every interview is stored with an unambiguous zone (avoids the previous
 * mixed-locale bug where a US-zoned slot was being labelled in the
 * intern's local time).
 *
 * <p>Curated business list at the top covers ~99% of cases; "Other…"
 * reveals a free-text input that accepts any IANA id and validates it
 * via {@link isValidIanaTimezone}.</p>
 */

import { useEffect, useState } from 'react';
import { isValidIanaTimezone } from '@/lib/format-interview-time';

const OTHER = '__other__';

const CURATED: { id: string; label: string }[] = [
  { id: 'Asia/Kolkata', label: 'Asia/Kolkata — IST (India)' },
  { id: 'America/New_York', label: 'America/New_York — ET (US Eastern)' },
  { id: 'America/Chicago', label: 'America/Chicago — CT (US Central)' },
  { id: 'America/Denver', label: 'America/Denver — MT (US Mountain)' },
  { id: 'America/Los_Angeles', label: 'America/Los_Angeles — PT (US Pacific)' },
  { id: 'America/Anchorage', label: 'America/Anchorage — AKT (Alaska)' },
  { id: 'Pacific/Honolulu', label: 'Pacific/Honolulu — HST (Hawaii)' },
  { id: 'UTC', label: 'UTC' },
];

interface Props {
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
}

export default function TimezoneSelect({ value, onChange, disabled }: Props) {
  const curatedIds = new Set(CURATED.map((c) => c.id));
  const initiallyOther = value !== '' && !curatedIds.has(value);

  const [mode, setMode] = useState<string>(initiallyOther ? OTHER : value);
  const [otherInput, setOtherInput] = useState<string>(initiallyOther ? value : '');

  // If parent flips the value externally (e.g. reset), keep the selector in sync.
  useEffect(() => {
    if (curatedIds.has(value)) {
      setMode(value);
    } else if (value) {
      setMode(OTHER);
      setOtherInput(value);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  function handleSelect(next: string) {
    setMode(next);
    if (next === OTHER) {
      // Don't clobber the parent value yet — wait for a valid IANA id.
      if (otherInput && isValidIanaTimezone(otherInput)) onChange(otherInput.trim());
    } else {
      onChange(next);
    }
  }

  function handleOther(next: string) {
    setOtherInput(next);
    const trimmed = next.trim();
    if (trimmed && isValidIanaTimezone(trimmed)) {
      onChange(trimmed);
    }
  }

  const otherInvalid =
    mode === OTHER && otherInput.trim() !== '' && !isValidIanaTimezone(otherInput.trim());

  return (
    <div>
      <select
        value={mode}
        onChange={(e) => handleSelect(e.target.value)}
        disabled={disabled}
        className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
      >
        {CURATED.map((c) => (
          <option key={c.id} value={c.id}>{c.label}</option>
        ))}
        <option value={OTHER}>Other (type an IANA id)…</option>
      </select>

      {mode === OTHER && (
        <div className="mt-2">
          <input
            value={otherInput}
            onChange={(e) => handleOther(e.target.value)}
            placeholder="e.g. Europe/London, Australia/Sydney"
            disabled={disabled}
            className={
              'w-full rounded-md border px-3 py-2 text-sm ' +
              (otherInvalid ? 'border-red-400' : 'border-slate-200')
            }
          />
          {otherInvalid && (
            <p className="mt-1 text-xs text-red-700">
              Not a recognized IANA timezone id.
            </p>
          )}
          {!otherInvalid && otherInput.trim() === '' && (
            <p className="mt-1 text-xs text-slate-500">
              Enter the full IANA id (Region/City).
            </p>
          )}
        </div>
      )}
    </div>
  );
}
