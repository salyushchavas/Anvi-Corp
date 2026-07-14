import type { Metadata } from "next";
import { Kumbh_Sans, Inter, Poppins } from "next/font/google";
import "./globals.css";

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

export const metadata: Metadata = {
  metadataBase: new URL("https://anvicorp.com"),
  title: {
    default: "Anvi Corp USA — Building Tomorrow's Future, Today",
    template: "%s | Anvi Corp USA",
  },
  description:
    "Anvi Corp USA delivers IT consulting, software development, cloud, and mobile application development services tailored to your business.",
  keywords: ["IT consulting", "software development", "cloud development", "mobile app development", "Anvi Corp"],
  openGraph: {
    title: "Anvi Corp USA — Building Tomorrow's Future, Today",
    description:
      "Advanced IT solutions tailored to your needs: software, cloud, mobile, and consulting.",
    url: "https://anvicorp.com",
    siteName: "Anvi Corp USA",
    type: "website",
  },
  twitter: { card: "summary_large_image", title: "Anvi Corp USA" },
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
