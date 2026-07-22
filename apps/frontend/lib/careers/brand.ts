/**
 * Brand identity — single source of truth for all chrome that varies
 * per deployment. Reads NEXT_PUBLIC_BRAND_* env vars at build time;
 * defaults to Anvi so a plain `next build` renders the correct
 * <title>, sidebar subtitle, support email, etc. without any Vercel
 * env config.
 *
 * Per-brand deployments (Vercel) can still override each string
 * individually; the defaults are just what a fresh clone shows on
 * localhost.
 */
export const BRAND = {
  /** Short brand name used in sidebar subtitles, body copy. */
  name: process.env.NEXT_PUBLIC_BRAND_NAME || 'Anvi Corp',
  /** "{name} Careers" product noun. */
  productName: process.env.NEXT_PUBLIC_BRAND_PRODUCT_NAME || 'Anvi Careers',
  /** Legal entity (for footers, contracts). */
  legalName:
    process.env.NEXT_PUBLIC_BRAND_LEGAL_NAME || 'Anvi Corp USA',
  /** Logo asset path or absolute URL. */
  logoUrl: process.env.NEXT_PUBLIC_BRAND_LOGO_URL || '/logo.png',
  /** Favicon URL. Empty default = no <link rel="icon"> emitted (current behavior). */
  faviconUrl: process.env.NEXT_PUBLIC_BRAND_FAVICON_URL || '',
  /** Support / contact email (UI + email templates may reference). */
  supportEmail:
    process.env.NEXT_PUBLIC_BRAND_SUPPORT_EMAIL || 'careers@anvicorp.com',
  /** Public marketing site URL. */
  websiteUrl:
    process.env.NEXT_PUBLIC_BRAND_WEBSITE_URL || 'https://anvicorp.com',
  /**
   * Brand primary color (hex) — informational; the visual rendering uses
   * Tailwind's brand-* ramp baked at build (see tailwind.config.ts which
   * reads the same env var). Exposed here so JS-driven code paths
   * (charts, SVG icons) can use the value directly.
   */
  primary: process.env.NEXT_PUBLIC_BRAND_PRIMARY || '#1D6299',
  /** Brand accent color (hex) — deeper accent for hovers / highlights. */
  accent: process.env.NEXT_PUBLIC_BRAND_ACCENT || '#174D78',
  /**
   * <title> default for routes that don't override metadata.
   */
  documentTitle:
    process.env.NEXT_PUBLIC_BRAND_DOCUMENT_TITLE
      || 'Anvi Careers | Anvi Corp USA',
  /** Document title template (Next.js metadata format). %s = page title. */
  documentTitleTemplate:
    process.env.NEXT_PUBLIC_BRAND_DOCUMENT_TITLE_TEMPLATE
      || '%s — Anvi Careers',
  /** Meta description default. */
  documentDescription:
    process.env.NEXT_PUBLIC_BRAND_DOCUMENT_DESCRIPTION
      || 'Anvi Corp USA Careers — IT consulting, software development, and STEM internships.',
};

/**
 * Derive the three dashboard CSS custom-property shades (—ds-brand,
 * —ds-brand-hover, —ds-brand-ring) from the brand primary hex. Same
 * mixing scheme as tailwind.config.ts so the CSS-var-driven surfaces
 * (globals.css focus-visible ring, .ds scope) stay in lock-step with
 * the tailwind brand-* utilities.
 *
 * <p>Returns null when the primary env is unset so callers can skip
 * injection entirely — the defaults in globals.css already render
 * byte-identically.</p>
 */
export function brandDsCssVars(): {
  brand: string;
  brandHover: string;
  brandRing: string;
} | null {
  const primary = process.env.NEXT_PUBLIC_BRAND_PRIMARY;
  if (!primary) return null;
  const rgb = hexToRgb(primary);
  if (!rgb) return null;
  const K: [number, number, number] = [0, 0, 0];
  return {
    brand:      mix(rgb, K, 0.30), // matches tailwind brand-700
    brandHover: mix(rgb, K, 0.45), // matches tailwind brand-800
    brandRing:  primary,           // brand-500
  };
}

function hexToRgb(hex: string): [number, number, number] | null {
  const m = /^#?([a-f\d]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function mix(from: [number, number, number], to: [number, number, number], t: number): string {
  const clamp = (v: number) => Math.max(0, Math.min(255, Math.round(v)));
  const c = [
    clamp(from[0] + (to[0] - from[0]) * t),
    clamp(from[1] + (to[1] - from[1]) * t),
    clamp(from[2] + (to[2] - from[2]) * t),
  ];
  return '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');
}
