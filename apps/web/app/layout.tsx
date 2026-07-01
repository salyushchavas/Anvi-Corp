import type { Metadata } from "next";
import { Kumbh_Sans } from "next/font/google";
import "./globals.css";

const kumbh = Kumbh_Sans({
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700"],
  variable: "--font-kumbh",
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

// Shell-free root layout: only html/body + fonts + globals. The marketing chrome
// (header/footer) lives in the (marketing) route group; the mail app has its own
// chrome in the (mail) route group. Route groups don't affect URLs.
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={kumbh.variable}>
      <body>{children}</body>
    </html>
  );
}
