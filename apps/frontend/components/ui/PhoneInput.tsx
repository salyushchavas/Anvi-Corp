'use client';

import { useEffect, useMemo, useState } from 'react';
import { INPUT_CLASS } from '@/components/ui/FormField';

/**
 * Phone number input with a country-code selector.
 *
 * <p>Pairs a fixed-width dropdown of common country dial codes with a
 * text input for the subscriber number. The parent sees a single
 * {@code value} of the form {@code "+CC number"} (e.g.
 * {@code "+1 555 123 4567"}); on load, splits it back into
 * (code, number) for editing.</p>
 *
 * <h3>Why the compact option label</h3>
 * A native {@code <select>} shows the selected option's text as the
 * button label — there is no way to render a different short label in
 * the closed state without dropping to a custom-JS dropdown. We keep
 * the option text SHORT ({@code "+CC · XX"} — dial code + ISO-2
 * country code) so a US selection reads {@code "+1 · US"} instead of
 * {@code "🇺🇸 +1 · United States / Canada"} — the latter grew the
 * dropdown wide enough to squeeze the phone number input off screen
 * on a normal-width form column. Full country name lives in the
 * option's {@code title} attribute for on-hover disambiguation.</p>
 *
 * <h3>Why no flag emoji</h3>
 * Regional-indicator emoji (🇺🇸, 🇮🇳, …) render as their literal
 * letter pairs (e.g. "US", "IN") on Windows because the default
 * system font has no flag glyphs and browsers don't fall back to
 * Segoe UI Emoji inside {@code <select>} options. Rather than ship a
 * cross-platform inconsistency, the picker is text-only. The dial
 * code + ISO-2 country code is a reliable universal identifier.</p>
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
  iso: string;    // ISO-2 country code, e.g. "US" — short label
  name: string;   // human-readable name — used only for the option's title tooltip
}

// Curated list, most-common first. "Other" at the end lets the user type
// an arbitrary dial code without bloating the dropdown to every country.
const COUNTRIES: Country[] = [
  { code: '+1',   iso: 'US/CA', name: 'United States / Canada' },
  { code: '+91',  iso: 'IN',    name: 'India' },
  { code: '+44',  iso: 'GB',    name: 'United Kingdom' },
  { code: '+61',  iso: 'AU',    name: 'Australia' },
  { code: '+49',  iso: 'DE',    name: 'Germany' },
  { code: '+33',  iso: 'FR',    name: 'France' },
  { code: '+81',  iso: 'JP',    name: 'Japan' },
  { code: '+65',  iso: 'SG',    name: 'Singapore' },
  { code: '+971', iso: 'AE',    name: 'United Arab Emirates' },
  { code: '+86',  iso: 'CN',    name: 'China' },
  { code: '+82',  iso: 'KR',    name: 'South Korea' },
  { code: '+34',  iso: 'ES',    name: 'Spain' },
  { code: '+39',  iso: 'IT',    name: 'Italy' },
  { code: '+55',  iso: 'BR',    name: 'Brazil' },
  { code: '+52',  iso: 'MX',    name: 'Mexico' },
  { code: '+27',  iso: 'ZA',    name: 'South Africa' },
  { code: '+64',  iso: 'NZ',    name: 'New Zealand' },
  { code: '+31',  iso: 'NL',    name: 'Netherlands' },
  { code: '+46',  iso: 'SE',    name: 'Sweden' },
  { code: '+47',  iso: 'NO',    name: 'Norway' },
  { code: '+45',  iso: 'DK',    name: 'Denmark' },
  { code: '+41',  iso: 'CH',    name: 'Switzerland' },
  { code: '+43',  iso: 'AT',    name: 'Austria' },
  { code: '+32',  iso: 'BE',    name: 'Belgium' },
  { code: '+48',  iso: 'PL',    name: 'Poland' },
  { code: '+90',  iso: 'TR',    name: 'Turkey' },
  { code: '+966', iso: 'SA',    name: 'Saudi Arabia' },
  { code: '+60',  iso: 'MY',    name: 'Malaysia' },
  { code: '+62',  iso: 'ID',    name: 'Indonesia' },
  { code: '+63',  iso: 'PH',    name: 'Philippines' },
  { code: '+66',  iso: 'TH',    name: 'Thailand' },
  { code: '+84',  iso: 'VN',    name: 'Vietnam' },
  { code: '+92',  iso: 'PK',    name: 'Pakistan' },
  { code: '+880', iso: 'BD',    name: 'Bangladesh' },
  { code: '+94',  iso: 'LK',    name: 'Sri Lanka' },
  { code: '+234', iso: 'NG',    name: 'Nigeria' },
  { code: '+254', iso: 'KE',    name: 'Kenya' },
  { code: '+20',  iso: 'EG',    name: 'Egypt' },
  { code: '+7',   iso: 'RU',    name: 'Russia' },
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
        // Fixed width via inline style — INPUT_CLASS carries `w-full`
        // which, being alphabetically later in Tailwind's generated
        // stylesheet than `w-32`, wins the cascade at equal
        // specificity and expands the <select> to 100% of the flex
        // container, squeezing the phone-number input off screen (the
        // exact symptom the previous fix pass tried to solve with
        // `w-32 flex-shrink-0`). Inline `style` always beats any
        // utility class regardless of CSS order — reliable across
        // Tailwind config changes.
        style={{ width: '8rem', flexShrink: 0 }}
        className={INPUT_CLASS}
      >
        {COUNTRIES.map((c) => (
          <option key={c.code} value={c.code} title={c.name}>
            {c.code} · {c.iso}
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
          className={`${INPUT_CLASS} w-20 flex-shrink-0`}
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
        // min-w-0 so this flex child can shrink below its intrinsic
        // content width if the container is narrow (default flex
        // behavior is min-content, which can push the row wider than
        // its parent and cause horizontal scroll).
        className={`${INPUT_CLASS} min-w-0 flex-1`}
      />
    </div>
  );
}
