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

  // ── Phase-0 config-layer extension ─────────────────────────────────
  // Fields the identity-centralization survey flagged as missing. Each
  // reads NEXT_PUBLIC_BRAND_* with the current Anvi-Corp default so a
  // fresh clone renders identically until the operator overrides in
  // Vercel. NOTE: no reference migration is done in this commit — the
  // batches that consume these come next.

  /** Company phone (E.164 / US-formatted, marketing chrome + contact page). */
  phone: process.env.NEXT_PUBLIC_BRAND_PHONE || '+1 469-945-4554',

  /** Physical address — separate lines so callers can format per-locale. */
  addressLine1:
    process.env.NEXT_PUBLIC_BRAND_ADDRESS_LINE1 || '7950 Legacy Dr',
  addressLine2:
    process.env.NEXT_PUBLIC_BRAND_ADDRESS_LINE2 || 'Suite 400',
  city: process.env.NEXT_PUBLIC_BRAND_CITY || 'Plano',
  state: process.env.NEXT_PUBLIC_BRAND_STATE || 'TX',
  postalCode: process.env.NEXT_PUBLIC_BRAND_POSTAL_CODE || '75024',
  country: process.env.NEXT_PUBLIC_BRAND_COUNTRY || 'USA',

  /**
   * Corporate mailbox suffix (bare domain, no @). Consumed by the
   * company-mailbox composer UIs that build `<local>@<emailDomain>`.
   * Distinct from `websiteUrl` in case a deploy wants to host the
   * marketing site on `.io` but keep `.com` mailboxes.
   */
  emailDomain: process.env.NEXT_PUBLIC_BRAND_EMAIL_DOMAIN || 'anvicorp.com',

  /**
   * Generic contact email surfaced on marketing chrome / legal pages.
   * DISTINCT from {@link supportEmail} — support is careers-team-facing
   * (`careers@`) while contact is the general company mailbox
   * (`info@`). Survey found 8+ places using `info@` separately from
   * `careers@`; keeping them as two fields lets a clone route each to
   * a different inbox.
   */
  contactEmail:
    process.env.NEXT_PUBLIC_BRAND_CONTACT_EMAIL || 'info@anvicorp.com',

  /**
   * Absolute canonical URL for the marketing site — the value that
   * belongs in `<link rel="canonical">`, sitemap.xml `<loc>`,
   * `metadataBase`, and OpenGraph `url`. `websiteUrl` above is kept
   * for backwards compatibility but callers targeting metadata should
   * prefer this field (the Next.js metadata layer needs a URL object,
   * not a bare hostname).
   *
   * <p>Default is BARE `anvicorp.com` (no `www.` prefix) to match the
   * pre-Phase-0-Batch-2 hardcoded value in `app/layout.tsx`,
   * `app/sitemap.ts`, and `app/robots.ts` — so wiring these three
   * metadata surfaces through the config is byte-identical for the
   * current Anvi SEO footprint. A per-brand deploy that prefers the
   * `www.` canonical can override via env var.</p>
   */
  siteBaseUrl:
    process.env.NEXT_PUBLIC_BRAND_SITE_BASE_URL || 'https://anvicorp.com',

  /**
   * Marketing tagline that hangs off the metadata `<title>` and hero
   * headings. Matches the current hardcoded `app/layout.tsx` copy so
   * migrating the metadata later is a mechanical swap.
   */
  siteTagline:
    process.env.NEXT_PUBLIC_BRAND_SITE_TAGLINE
      || "Building Tomorrow's Future, Today",

  /**
   * OpenGraph `siteName` — often the legal or full brand name. Split
   * from `productName` because product = "Anvi Careers" but siteName =
   * "Anvi Corp USA" (the parent entity on marketing surfaces).
   */
  metadataSiteName:
    process.env.NEXT_PUBLIC_BRAND_METADATA_SITE_NAME || 'Anvi Corp USA',

  /**
   * Governing-law jurisdiction for the /terms clause. Default matches
   * the physical-address state so the boilerplate resolves to a real
   * courts venue. A future legal-review pass can override per-brand.
   */
  jurisdiction:
    process.env.NEXT_PUBLIC_BRAND_JURISDICTION || 'Texas, USA',

  /**
   * Bare S3 host for user-content asset URLs (used by Next.js
   * `next.config.mjs` `images.remotePatterns` + CSP `connect-src`).
   * Bare host, no scheme, no bucket-suffix — callers append the
   * region variant. Current bucket name has a legacy typo
   * ("carrers"); a clone can fix it via the env var without touching
   * code. Kept blank-default-safe so an unset env falls back to the
   * literal current host.
   */
  s3AssetHost:
    process.env.NEXT_PUBLIC_BRAND_S3_ASSET_HOST
      || 'anvi-corp-carrers.s3.amazonaws.com',
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
