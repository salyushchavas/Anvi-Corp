import type { Metadata } from "next";
import { Kumbh_Sans, Inter, Poppins } from "next/font/google";
import "./globals.css";
import { BRAND, brandDsCssVars } from "@/lib/careers/brand";

// Marketing typeface (kept from the original Anvi site).
const kumbh = Kumbh_Sans({
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700"],
  variable: "--font-kumbh",
  display: "swap",
});

// Careers dashboard typeface. Careers surfaces opt in via font-sans (which
// resolves to Inter first in the merged Tailwind config).
const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-inter",
  display: "swap",
});

// Careers marketing / public-facing pages inside /careers/openings use Poppins.
const poppins = Poppins({
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700", "800"],
  variable: "--font-poppins",
  display: "swap",
});

// Phase-0 batch 2 — metadata identity now flows from BRAND.* (env-driven
// at build time). Composed values kept byte-identical for the current
// Anvi deploy: default title = "{metadataSiteName} — {siteTagline}"
// resolves to "Anvi Corp USA — Building Tomorrow's Future, Today", which
// is exactly what the pre-migration literal was. A per-brand clone
// sets NEXT_PUBLIC_BRAND_METADATA_SITE_NAME / _SITE_TAGLINE /
// _SITE_BASE_URL / _NAME / _LEGAL_NAME on Vercel and gets its own
// metadata without editing this file.
const defaultTitle = `${BRAND.metadataSiteName} — ${BRAND.siteTagline}`;

export const metadata: Metadata = {
  metadataBase: new URL(BRAND.siteBaseUrl),
  title: {
    default: defaultTitle,
    template: `%s | ${BRAND.metadataSiteName}`,
  },
  description: `${BRAND.legalName} delivers IT consulting, software development, cloud, and mobile application development services tailored to your business.`,
  keywords: ["IT consulting", "software development", "cloud development", "mobile app development", BRAND.name],
  openGraph: {
    title: defaultTitle,
    description:
      "Advanced IT solutions tailored to your needs: software, cloud, mobile, and consulting.",
    url: BRAND.siteBaseUrl,
    siteName: BRAND.metadataSiteName,
    type: "website",
  },
  twitter: { card: "summary_large_image", title: BRAND.metadataSiteName },
};

// Shell-free root layout: only html/body + fonts + globals. The marketing
// chrome (header/footer) lives in the (marketing) route group; the mail app
// has its own chrome in the (mail) route group; the careers app has its
// providers in app/careers/layout.tsx. Nested layouts wrap only their
// subtree — marketing pages don't pay for careers-only providers, etc.
export default function RootLayout({ children }: { children: React.ReactNode }) {
  // Phase-0 batch 5-fix — brand CSS-var override moved up from
  // app/careers/layout.tsx to here so it applies GLOBALLY, not just
  // under /careers/*. Consequence: the `*:focus-visible { outline: 2px
  // solid var(--ds-brand-ring) }` rule in globals.css (which is
  // global, not scoped to .ds) now gets the per-brand ring color on
  // marketing routes too. Before this fix, marketing pages always
  // showed the globals.css fallback (`#2A8CDB` Anvi blue) even when a
  // per-brand clone set NEXT_PUBLIC_BRAND_PRIMARY.
  //
  // Byte-identical for Anvi: brandDsCssVars() returns null when the
  // primary env var is unset → the guard drops the `<style>` → the
  // globals.css defaults apply exactly as before. When the env IS set
  // to the current Anvi value `#2A8CDB`, the computed override values
  // equal the fallbacks (same mix() formula) — still byte-identical.
  const dsVars = brandDsCssVars();
  return (
    <html
      lang="en"
      className={`${kumbh.variable} ${inter.variable} ${poppins.variable}`}
    >
      <body>
        {dsVars && (
          <style
            // eslint-disable-next-line react/no-danger
            dangerouslySetInnerHTML={{
              __html: `:root{--ds-brand:${dsVars.brand};--ds-brand-hover:${dsVars.brandHover};--ds-brand-ring:${dsVars.brandRing};}`,
            }}
          />
        )}
        {children}
      </body>
    </html>
  );
}
