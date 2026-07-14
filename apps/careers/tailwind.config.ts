import type { Config } from 'tailwindcss';

// ── Brand ramp generator (build-time) ─────────────────────────────────
// Each per-brand deploy sets NEXT_PUBLIC_BRAND_PRIMARY (and optionally
// NEXT_PUBLIC_BRAND_ACCENT). Tailwind colors are baked at build time;
// Vercel builds per-brand with these env vars and produces a different
// CSS bundle. With no env vars set, the existing Skyzen orange ramp
// renders byte-identically (see SKYZEN_BRAND_RAMP defaults below).
const SKYZEN_BRAND_RAMP: Record<number, string> = {
  50:  '#fff7ed',
  100: '#ffedd5',
  200: '#fed7aa',
  300: '#fdba74',
  400: '#fb923c',
  500: '#f97316',
  600: '#ea580c',
  700: '#c2410c',
  800: '#9a3412',
  900: '#7c2d12',
};

function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#?([a-f\d]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function rgbToHex(r: number, g: number, b: number): string {
  const clamp = (v: number) => Math.max(0, Math.min(255, Math.round(v)));
  return '#' + [r, g, b].map((v) => clamp(v).toString(16).padStart(2, '0')).join('');
}
function mix(from: [number, number, number], to: [number, number, number], t: number): string {
  return rgbToHex(
    from[0] + (to[0] - from[0]) * t,
    from[1] + (to[1] - from[1]) * t,
    from[2] + (to[2] - from[2]) * t,
  );
}
/**
 * Derive a tailwind-style 50..900 ramp from a brand-500 anchor: lighter
 * shades mix toward white, darker shades mix toward black. Ratios picked
 * to approximate Tailwind's default-ramp spacing. For pixel-perfect
 * brand work, override individual shades via direct CSS instead.
 */
function deriveRamp(primaryHex: string): Record<number, string> {
  const rgb = hexToRgb(primaryHex);
  if (!rgb) return { ...SKYZEN_BRAND_RAMP };
  const W: [number, number, number] = [255, 255, 255];
  const K: [number, number, number] = [0, 0, 0];
  return {
    50:  mix(rgb, W, 0.92),
    100: mix(rgb, W, 0.82),
    200: mix(rgb, W, 0.65),
    300: mix(rgb, W, 0.45),
    400: mix(rgb, W, 0.20),
    500: primaryHex,
    600: mix(rgb, K, 0.15),
    700: mix(rgb, K, 0.30),
    800: mix(rgb, K, 0.45),
    900: mix(rgb, K, 0.60),
  };
}

const BRAND_PRIMARY_ENV = process.env.NEXT_PUBLIC_BRAND_PRIMARY;
const BRAND_ACCENT_ENV = process.env.NEXT_PUBLIC_BRAND_ACCENT;

const BRAND_RAMP = BRAND_PRIMARY_ENV ? deriveRamp(BRAND_PRIMARY_ENV) : SKYZEN_BRAND_RAMP;
const RING_DEFAULT = BRAND_PRIMARY_ENV ?? '#f97316';
const ACCENT_DEFAULT = BRAND_ACCENT_ENV ?? '#fb9b47';
const ACCENT_DARK = BRAND_ACCENT_ENV
  ? mix(hexToRgb(BRAND_ACCENT_ENV) ?? [251, 155, 71], [0, 0, 0], 0.15)
  : '#ff7c20';
const ACCENT_LIGHT = BRAND_ACCENT_ENV
  ? mix(hexToRgb(BRAND_ACCENT_ENV) ?? [251, 155, 71], [255, 255, 255], 0.20)
  : '#ffb347';

// Legacy `primary` palette. Originally a hand-picked Skyzen orange ramp;
// now derived from the brand primary when env is set, so a per-brand
// deploy re-themes pages that still reference primary-* utilities.
const SKYZEN_PRIMARY_RAMP: Record<number, string> = {
  50:  '#fff5ec',
  100: '#ffe6d1',
  200: '#ffcc9e',
  300: '#ffb066',
  400: '#fb9b47',
  500: '#ff7c20',
  600: '#e0862f',
  700: '#b86723',
  800: '#8f4f1c',
  900: '#5e3411',
};
const PRIMARY_RAMP = BRAND_PRIMARY_ENV
  ? deriveRamp(BRAND_PRIMARY_ENV)
  : SKYZEN_PRIMARY_RAMP;

// Glow shadows tint from the accent, not brand primary — same env source
// though so a full brand swap re-tints them.
function toRgbString(hex: string): string {
  const rgb = hexToRgb(hex) ?? [251, 155, 71];
  return `${rgb[0]},${rgb[1]},${rgb[2]}`;
}
const GLOW_RGB = toRgbString(ACCENT_DEFAULT);

const config: Config = {
  content: [
    './app/**/*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
    './lib/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        // ── Legacy Skyzen marketing palette (kept for /home + chrome) ────────
        skyzen: {
          dark:   '#080d1a',
          dark2:  '#0c1221',
          dark3:  '#111827',
          navy:   '#0f2238',
          light:  '#f8fafc',
          text:   '#e2e8f0',
          muted:  '#8a9ab5',
          border: 'rgba(255,255,255,0.08)',
          card:   'rgba(255,255,255,0.04)',
        },
        // Legacy orange accent — marketing site only. The dashboard chrome
        // moved to teal as its brand (see `brand` below).
        // Defaults to Skyzen orange; overridden when NEXT_PUBLIC_BRAND_ACCENT
        // is set at build time (see top of this file).
        accent: {
          DEFAULT: ACCENT_DEFAULT,
          dark:    ACCENT_DARK,
          light:   ACCENT_LIGHT,
        },
        // `primary` — legacy palette. Derived from the brand primary so a
        // per-brand deploy re-themes any lingering primary-* utilities.
        primary: PRIMARY_RAMP,
        // ── Design system (dashboard) — brand ramp from env at build ────
        // Defaults to the Skyzen orange ramp; per-brand deploys set
        // NEXT_PUBLIC_BRAND_PRIMARY and the ramp is derived (see top).
        brand: BRAND_RAMP,
      },
      fontFamily: {
        // Poppins kept for marketing chrome; Inter is the dashboard typeface.
        // `font-sans` resolves to Inter first, falls back to Poppins, then
        // system sans — dashboard surfaces opt-in by class, marketing pages
        // keep working because Poppins is still in the cascade.
        sans: [
          'var(--font-inter)',
          'var(--font-poppins)',
          'ui-sans-serif',
          'system-ui',
          'sans-serif',
        ],
        poppins: ['var(--font-poppins)', 'sans-serif'],
        inter: ['var(--font-inter)', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      boxShadow: {
        'glow-accent': `0 6px 24px rgba(${GLOW_RGB},0.4)`,
        'glow-accent-lg': `0 10px 30px rgba(${GLOW_RGB},0.55)`,
        // Dashboard surface elevation — quiet, not glowy.
        'ds-sm': '0 1px 2px rgba(15,23,42,0.06)',
        'ds-md': '0 4px 10px rgba(15,23,42,0.08)',
        'ds-lg': '0 12px 24px rgba(15,23,42,0.10)',
      },
      ringColor: {
        DEFAULT: RING_DEFAULT,
      },
      keyframes: {
        'modal-in': {
          '0%': { opacity: '0', transform: 'scale(0.98)' },
          '100%': { opacity: '1', transform: 'scale(1)' },
        },
        'modal-out': {
          '0%': { opacity: '1', transform: 'scale(1)' },
          '100%': { opacity: '0', transform: 'scale(0.98)' },
        },
        'fade-in': {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
      },
      animation: {
        'modal-in': 'modal-in 200ms ease-out',
        'modal-out': 'modal-out 150ms ease-out',
        'fade-in': 'fade-in 150ms ease-out',
      },
    },
  },
  plugins: [],
};

export default config;
