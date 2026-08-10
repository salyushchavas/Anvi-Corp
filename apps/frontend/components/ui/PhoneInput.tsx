'use client';

import { useEffect, useMemo, useState } from 'react';
import { INPUT_CLASS } from '@/components/ui/FormField';

/**
 * Phone number input with a country-code selector.
 *
 * <p>Pairs a dropdown of common country dial codes with a text input
 * for the subscriber number. The parent sees a single {@code value}
 * of the form {@code "+CC number"} (e.g. {@code "+1 555 123 4567"});
 * on load, splits it back into (code, number) for editing.</p>
 *
 * <p>Country list is intentionally curated (~40 top dialing regions)
 * rather than exhaustive so the picker stays scannable. An "Other"
 * entry lets the user type any dial code manually.</p>
 */
export interface PhoneInputProps {
  value: string;
  onChange: (fullNumber: string) => void;
  id?: string;
  placeholder?: string;
  autoComplete?: string;
  disabled?: boolean;
}

interface Country {
  code: string;   // dial code with leading +
  name: string;   // human label
  flag: string;   // emoji flag
}

// Curated list, most-common first. "Other" at the end lets the user type
// an arbitrary dial code without bloating the dropdown to every country.
const COUNTRIES: Country[] = [
  { code: '+1',   name: 'United States / Canada', flag: '🇺🇸' },
  { code: '+91',  name: 'India',                  flag: '🇮🇳' },
  { code: '+44',  name: 'United Kingdom',         flag: '🇬🇧' },
  { code: '+61',  name: 'Australia',              flag: '🇦🇺' },
  { code: '+49',  name: 'Germany',                flag: '🇩🇪' },
  { code: '+33',  name: 'France',                 flag: '🇫🇷' },
  { code: '+81',  name: 'Japan',                  flag: '🇯🇵' },
  { code: '+65',  name: 'Singapore',              flag: '🇸🇬' },
  { code: '+971', name: 'United Arab Emirates',   flag: '🇦🇪' },
  { code: '+86',  name: 'China',                  flag: '🇨🇳' },
  { code: '+82',  name: 'South Korea',            flag: '🇰🇷' },
  { code: '+34',  name: 'Spain',                  flag: '🇪🇸' },
  { code: '+39',  name: 'Italy',                  flag: '🇮🇹' },
  { code: '+55',  name: 'Brazil',                 flag: '🇧🇷' },
  { code: '+52',  name: 'Mexico',                 flag: '🇲🇽' },
  { code: '+27',  name: 'South Africa',           flag: '🇿🇦' },
  { code: '+64',  name: 'New Zealand',            flag: '🇳🇿' },
  { code: '+31',  name: 'Netherlands',            flag: '🇳🇱' },
  { code: '+46',  name: 'Sweden',                 flag: '🇸🇪' },
  { code: '+47',  name: 'Norway',                 flag: '🇳🇴' },
  { code: '+45',  name: 'Denmark',                flag: '🇩🇰' },
  { code: '+41',  name: 'Switzerland',            flag: '🇨🇭' },
  { code: '+43',  name: 'Austria',                flag: '🇦🇹' },
  { code: '+32',  name: 'Belgium',                flag: '🇧🇪' },
  { code: '+48',  name: 'Poland',                 flag: '🇵🇱' },
  { code: '+90',  name: 'Turkey',                 flag: '🇹🇷' },
  { code: '+966', name: 'Saudi Arabia',           flag: '🇸🇦' },
  { code: '+60',  name: 'Malaysia',               flag: '🇲🇾' },
  { code: '+62',  name: 'Indonesia',              flag: '🇮🇩' },
  { code: '+63',  name: 'Philippines',            flag: '🇵🇭' },
  { code: '+66',  name: 'Thailand',               flag: '🇹🇭' },
  { code: '+84',  name: 'Vietnam',                flag: '🇻🇳' },
  { code: '+92',  name: 'Pakistan',               flag: '🇵🇰' },
  { code: '+880', name: 'Bangladesh',             flag: '🇧🇩' },
  { code: '+94',  name: 'Sri Lanka',              flag: '🇱🇰' },
  { code: '+234', name: 'Nigeria',                flag: '🇳🇬' },
  { code: '+254', name: 'Kenya',                  flag: '🇰🇪' },
  { code: '+20',  name: 'Egypt',                  flag: '🇪🇬' },
  { code: '+7',   name: 'Russia',                 flag: '🇷🇺' },
];

const DEFAULT_CODE = '+1';
const OTHER_SENTINEL = '__other__';

/** Split "+CC number" → { code: "+CC", number: "number" }. Handles
 *  a bare number without code (defaults to +1) and strips leading
 *  whitespace so re-editing a saved value round-trips cleanly. */
function splitPhone(full: string): { code: string; number: string } {
  const trimmed = (full ?? '').trim();
  if (!trimmed) return { code: DEFAULT_CODE, number: '' };
  if (!trimmed.startsWith('+')) return { code: DEFAULT_CODE, number: trimmed };
  // Match a leading "+", 1-3 digits, then optional space, then rest.
  const m = /^\+(\d{1,3})\s*(.*)$/.exec(trimmed);
  if (!m) return { code: DEFAULT_CODE, number: trimmed };
  return { code: '+' + m[1], number: m[2].trim() };
}

/** Rejoin "+CC" + number → "+CC number", with a single space between
 *  and never yielding just "+CC " when the user cleared the number. */
function joinPhone(code: string, number: string): string {
  const c = (code ?? '').trim();
  const n = (number ?? '').trim();
  if (!c && !n) return '';
  if (!n) return c;
  if (!c) return n;
  return `${c} ${n}`;
}

export default function PhoneInput({
  value, onChange, id, placeholder = 'e.g. 555 123 4567',
  autoComplete = 'tel-national', disabled,
}: PhoneInputProps) {
  const initial = useMemo(() => splitPhone(value), [value]);
  const [code, setCode] = useState<string>(initial.code);
  const [number, setNumber] = useState<string>(initial.number);
  // When the initial code isn't in the curated list, land on "Other"
  // and pre-fill the custom-code text input.
  const isKnown = COUNTRIES.some((c) => c.code === code);
  const [customCode, setCustomCode] = useState<string>(isKnown ? '' : code);
  const [selectValue, setSelectValue] = useState<string>(isKnown ? code : OTHER_SENTINEL);

  // Rehydrate from parent-supplied value (e.g. profile load) when it
  // changes to a different phone. Skips no-op re-renders where the
  // parent just echoed our own onChange.
  useEffect(() => {
    const next = splitPhone(value);
    if (next.code === code && next.number === number) return;
    setCode(next.code);
    setNumber(next.number);
    if (COUNTRIES.some((c) => c.code === next.code)) {
      setSelectValue(next.code);
      setCustomCode('');
    } else {
      setSelectValue(OTHER_SENTINEL);
      setCustomCode(next.code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  function handleSelect(v: string) {
    setSelectValue(v);
    if (v === OTHER_SENTINEL) {
      // Keep the previously-typed custom code (or empty) but don't
      // emit until the user actually types a real code.
      const effective = customCode || '';
      setCode(effective);
      onChange(joinPhone(effective, number));
    } else {
      setCode(v);
      setCustomCode('');
      onChange(joinPhone(v, number));
    }
  }

  function handleCustomCode(v: string) {
    // Normalise: force a leading "+" and strip anything that isn't a
    // digit so the user can't jam "abc" into a dial-code slot.
    const cleaned = '+' + v.replace(/\D/g, '').slice(0, 4);
    setCustomCode(cleaned);
    setCode(cleaned);
    onChange(joinPhone(cleaned, number));
  }

  function handleNumber(v: string) {
    setNumber(v);
    onChange(joinPhone(code, v));
  }

  return (
    <div className="flex gap-2">
      <select
        aria-label="Country code"
        value={selectValue}
        onChange={(e) => handleSelect(e.target.value)}
        disabled={disabled}
        className={`${INPUT_CLASS} w-auto min-w-[9rem] pr-6`}
      >
        {COUNTRIES.map((c) => (
          <option key={c.code} value={c.code}>
            {c.flag} {c.code} · {c.name}
          </option>
        ))}
        <option value={OTHER_SENTINEL}>Other…</option>
      </select>
      {selectValue === OTHER_SENTINEL && (
        <input
          type="text"
          aria-label="Custom dial code"
          value={customCode}
          onChange={(e) => handleCustomCode(e.target.value)}
          placeholder="+CC"
          disabled={disabled}
          inputMode="numeric"
          className={`${INPUT_CLASS} w-20`}
        />
      )}
      <input
        id={id}
        type="tel"
        value={number}
        onChange={(e) => handleNumber(e.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        disabled={disabled}
        className={`${INPUT_CLASS} flex-1`}
      />
    </div>
  );
}
