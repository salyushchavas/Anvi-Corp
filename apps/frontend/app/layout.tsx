import type { Metadata } from "next";
import { Kumbh_Sans, Inter, Poppins } from "next/font/google";
import "./globals.css";
import { BRAND } from "@/lib/careers/brand";

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
  return (
    <html
      lang="en"
      className={`${kumbh.variable} ${inter.variable} ${poppins.variable}`}
    >
      <body>{children}</body>
    </html>
  );
}
