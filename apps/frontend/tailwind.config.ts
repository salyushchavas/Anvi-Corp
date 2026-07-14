import type { Config } from "tailwindcss";

// ── Brand ramp generator (build-time, env-driven) ─────────────────────
// Ported from the careers tailwind config. Each deploy sets
// NEXT_PUBLIC_BRAND_PRIMARY (and optionally NEXT_PUBLIC_BRAND_ACCENT);
// the resulting Tailwind colors are baked at build time. With no env
// vars set, the SKYZEN_BRAND_RAMP defaults render — but Anvi's
// apps/frontend/.env.local sets these to the Anvi blue #2A8CDB so the marketing
// site produces the exact same brand-* palette it always has (no visual
// regression), while the careers dashboard chrome now derives from the
// same source of truth.
const SKYZEN_BRAND_RAMP: Record<number, string> = {
  50:  "#fff7ed",
  100: "#ffedd5",
  200: "#fed7aa",
  300: "#fdba74",
  400: "#fb923c",
  500: "#f97316",
  600: "#ea580c",
  700: "#c2410c",
  800: "#9a3412",
  900: "#7c2d12",
};

function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#?([a-f\d]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function rgbToHex(r: number, g: number, b: number): string {
  const clamp = (v: number) => Math.max(0, Math.min(255, Math.round(v)));
  return "#" + [r, g, b].map((v) => clamp(v).toString(16).padStart(2, "0")).join("");
}
function mix(from: [number, number, number], to: [number, number, number], t: number): string {
  return rgbToHex(
    from[0] + (to[0] - from[0]) * t,
    from[1] + (to[1] - from[1]) * t,
    from[2] + (to[2] - from[2]) * t,
  );
}
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
const BRAND_ACCENT_ENV  = process.env.NEXT_PUBLIC_BRAND_ACCENT;

const BRAND_RAMP    = BRAND_PRIMARY_ENV ? deriveRamp(BRAND_PRIMARY_ENV) : SKYZEN_BRAND_RAMP;
const RING_DEFAULT  = BRAND_PRIMARY_ENV ?? "#f97316";

// Accent — from careers (env-derivable). Anvi's .env.local sets NEXT_PUBLIC_BRAND_ACCENT=#3C72FC.
const ACCENT_DEFAULT = BRAND_ACCENT_ENV ?? "#fb9b47";
const ACCENT_DARK    = BRAND_ACCENT_ENV
  ? mix(hexToRgb(BRAND_ACCENT_ENV) ?? [251, 155, 71], [0, 0, 0], 0.15)
  : "#ff7c20";
const ACCENT_LIGHT   = BRAND_ACCENT_ENV
  ? mix(hexToRgb(BRAND_ACCENT_ENV) ?? [251, 155, 71], [255, 255, 255], 0.20)
  : "#ffb347";

// Legacy `primary` ramp used by some careers surfaces. Derived from the
// brand primary when env is set, so a per-brand deploy re-themes any
// lingering primary-* utilities.
const SKYZEN_PRIMARY_RAMP: Record<number, string> = {
  50:  "#fff5ec",
  100: "#ffe6d1",
  200: "#ffcc9e",
  300: "#ffb066",
  400: "#fb9b47",
  500: "#ff7c20",
  600: "#e0862f",
  700: "#b86723",
  800: "#8f4f1c",
  900: "#5e3411",
};
const PRIMARY_RAMP = BRAND_PRIMARY_ENV ? deriveRamp(BRAND_PRIMARY_ENV) : SKYZEN_PRIMARY_RAMP;

function toRgbString(hex: string): string {
  const rgb = hexToRgb(hex) ?? [251, 155, 71];
  return `${rgb[0]},${rgb[1]},${rgb[2]}`;
}
const GLOW_RGB = toRgbString(ACCENT_DEFAULT);

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    // ── Container config (marketing site) ────────────────────────────
    container: {
      center: true,
      padding: { DEFAULT: "1rem", sm: "1.5rem", lg: "2rem" },
      screens: { sm: "640px", md: "768px", lg: "1024px", xl: "1200px" },
    },
    extend: {
      colors: {
        // ── Brand (env-derived) — replaces marketing's hardcoded ramp.
        // With NEXT_PUBLIC_BRAND_PRIMARY=#2A8CDB set in apps/frontend/.env.local
        // the derived ramp is Anvi blue; unset would fall back to Skyzen orange.
        // DEFAULT alias to brand-500 so `bg-brand`/`text-brand` (marketing utilities) resolve.
        brand: {
          DEFAULT: BRAND_RAMP[500],
          ...BRAND_RAMP,
        },

        // ── Accent — marketing was `{DEFAULT: "#3C72FC", 600: "#264FCB"}`.
        // Careers uses `{DEFAULT, dark, light}`. The union keeps marketing's
        // 600 key for existing utility usage and adds careers' semantic slots.
        accent: {
          DEFAULT: ACCENT_DEFAULT,
          600: "#264FCB",
          dark:    ACCENT_DARK,
          light:   ACCENT_LIGHT,
        },

        // ── Legacy primary ramp (careers-only utility classes still ref primary-*)
        primary: PRIMARY_RAMP,

        // ── ink.* — Anvi neutral ramp (marketing chrome + body copy).
        ink: {
          DEFAULT: "#0F0D1D",
          50:  "#F6F7F9",
          100: "#EAEBED",
          200: "#D2D4D8",
          300: "#A7AAB1",
          400: "#777181",
          500: "#55585B",
          600: "#33363A",
          700: "#1B1D22",
          800: "#0F0D1D",
          900: "#020B18",
        },

        // ── skyzen.* — dark navy palette used by careers marketing chrome
        // (SiteHeader/SiteFooter). Kept for those surfaces; safe to rename
        // to `chrome:` in a later cleanup pass.
        skyzen: {
          dark:   "#080d1a",
          dark2:  "#0c1221",
          dark3:  "#111827",
          navy:   "#0f2238",
          light:  "#f8fafc",
          text:   "#e2e8f0",
          muted:  "#8a9ab5",
          border: "rgba(255,255,255,0.08)",
          card:   "rgba(255,255,255,0.04)",
        },
      },
      fontFamily: {
        // font-sans resolves Inter → Poppins → Kumbh → system. Marketing
        // pages opt in explicitly via font-kumbh; careers dashboards get
        // Inter by default; careers marketing (openings) uses Poppins.
        sans: [
          "var(--font-inter)",
          "var(--font-poppins)",
          "var(--font-kumbh)",
          "system-ui",
          "sans-serif",
        ],
        kumbh:   ["var(--font-kumbh)", "system-ui", "sans-serif"],
        inter:   ["var(--font-inter)", "sans-serif"],
        poppins: ["var(--font-poppins)", "sans-serif"],
        mono:    ["ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      boxShadow: {
        // Marketing shadows (Anvi)
        card:      "0 8px 32px -12px rgba(15, 13, 29, 0.10)",
        cardHover: "0 16px 48px -16px rgba(42, 140, 219, 0.22)",
        // Careers marketing glow (accent-tinted)
        "glow-accent":    `0 6px 24px rgba(${GLOW_RGB},0.4)`,
        "glow-accent-lg": `0 10px 30px rgba(${GLOW_RGB},0.55)`,
        // Careers dashboard surface elevation (quiet, not glowy)
        "ds-sm": "0 1px 2px rgba(15,23,42,0.06)",
        "ds-md": "0 4px 10px rgba(15,23,42,0.08)",
        "ds-lg": "0 12px 24px rgba(15,23,42,0.10)",
      },
      backgroundImage: {
        "brand-gradient": "linear-gradient(135deg, #2A8CDB 0%, #3C72FC 100%)",
        "ink-gradient":   "linear-gradient(180deg, #0F0D1D 0%, #020B18 100%)",
      },
      ringColor: {
        DEFAULT: RING_DEFAULT,
      },
      animation: {
        // Marketing
        "float-slow": "float 8s ease-in-out infinite",
        "anvi-rise":  "anvi-rise 25s linear infinite",
        // Careers
        "modal-in":  "modal-in 200ms ease-out",
        "modal-out": "modal-out 150ms ease-out",
        "fade-in":   "fade-in 150ms ease-out",
      },
      keyframes: {
        float: {
          "0%, 100%": { transform: "translateY(0) scale(1)",   opacity: "0.6" },
          "50%":      { transform: "translateY(-30px) scale(1.1)", opacity: "1" },
        },
        // ported from _legacy/assets/css/main.css @keyframes animate
        "anvi-rise": {
          "0%":   { transform: "translateY(0) rotate(0deg)",       opacity: "1", borderRadius: "0"    },
          "100%": { transform: "translateY(-1000px) rotate(720deg)", opacity: "0", borderRadius: "50%" },
        },
        "modal-in": {
          "0%":   { opacity: "0", transform: "scale(0.98)" },
          "100%": { opacity: "1", transform: "scale(1)"    },
        },
        "modal-out": {
          "0%":   { opacity: "1", transform: "scale(1)"    },
          "100%": { opacity: "0", transform: "scale(0.98)" },
        },
        "fade-in": {
          "0%":   { opacity: "0" },
          "100%": { opacity: "1" },
        },
      },
    },
  },
  plugins: [],
};

export default config;
