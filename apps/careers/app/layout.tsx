import type { Metadata } from 'next';
import { Inter, Poppins } from 'next/font/google';
import { Toaster } from 'react-hot-toast';
import { AuthProvider } from '@/lib/auth-context';
import { BRAND, brandDsCssVars } from '@/lib/brand';
import IdleTimeoutProvider from '@/components/auth/IdleTimeoutProvider';
import './globals.css';

const poppins = Poppins({
  subsets: ['latin'],
  weight: ['300', '400', '500', '600', '700', '800'],
  variable: '--font-poppins',
  display: 'swap',
});

// Inter is the dashboard typeface. Marketing pages keep Poppins via the
// font-poppins utility; everything inside DashboardLayout opts into Inter
// through the `ds` class on its root.
const inter = Inter({
  subsets: ['latin'],
  weight: ['400', '500', '600', '700'],
  variable: '--font-inter',
  display: 'swap',
});

// Defaults preserve byte-exact current Skyzen copy (see lib/brand.ts).
// Per-brand deploys override via NEXT_PUBLIC_BRAND_DOCUMENT_*. The public
// marketing page at app/page.tsx has its own metadata that supersedes
// these for the root route — these are the fallback for app/careers/* etc.
export const metadata: Metadata = {
  title: {
    default: BRAND.documentTitle,
    template: BRAND.documentTitleTemplate,
  },
  description: BRAND.documentDescription,
  icons: BRAND.faviconUrl ? { icon: BRAND.faviconUrl } : undefined,
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // Per-brand deploys override the dashboard CSS custom properties from
  // the same env source Tailwind reads. With no env, no <style> block is
  // rendered — the Skyzen defaults in globals.css remain byte-identical.
  const dsVars = brandDsCssVars();
  return (
    <html lang="en" className={`${poppins.variable} ${inter.variable}`}>
      <head>
        {/* icofont — used by SiteHeader / SiteFooter / ported home page. */}
        <link rel="stylesheet" href="/plugins/icofont/icofont.min.css" />
        {dsVars && (
          <style
            // eslint-disable-next-line react/no-danger
            dangerouslySetInnerHTML={{
              __html: `:root{--ds-brand:${dsVars.brand};--ds-brand-hover:${dsVars.brandHover};--ds-brand-ring:${dsVars.brandRing};}`,
            }}
          />
        )}
      </head>
      <body className="min-h-screen font-sans antialiased">
        <AuthProvider>
          <IdleTimeoutProvider>{children}</IdleTimeoutProvider>
        </AuthProvider>
        <Toaster
          position="bottom-right"
          toastOptions={{
            duration: 3000,
            style: {
              fontSize: '14px',
              border: '1px solid rgb(229 231 235)',
              padding: '12px 16px',
              borderRadius: '6px',
            },
            success: { iconTheme: { primary: '#16a34a', secondary: '#fff' } },
            error: { iconTheme: { primary: '#dc2626', secondary: '#fff' } },
          }}
        />
      </body>
    </html>
  );
}
