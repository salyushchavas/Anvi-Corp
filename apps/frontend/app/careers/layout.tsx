import type { Metadata } from 'next';
import { Toaster } from 'react-hot-toast';
import { AuthProvider } from '@/lib/careers/auth-context';
import { BRAND } from '@/lib/careers/brand';
import IdleTimeoutProvider from '@/components/auth/IdleTimeoutProvider';

// Careers-specific metadata — overrides the root marketing metadata for
// any URL under /careers/*. Root layout still supplies <html>, <body>,
// and the shared fonts (see apps/frontend/app/layout.tsx).
export const metadata: Metadata = {
  title: {
    default: BRAND.documentTitle,
    template: BRAND.documentTitleTemplate,
  },
  description: BRAND.documentDescription,
  icons: BRAND.faviconUrl ? { icon: BRAND.faviconUrl } : undefined,
};

/**
 * Careers section shell. Wraps the entire /careers/* subtree with:
 *   - AuthProvider          — careers auth session (JWT + storage)
 *   - IdleTimeoutProvider   — auto-logout on idle
 *   - react-hot-toast       — dashboard notifications
 *   - Brand CSS vars        — per-brand primary color injected into :root
 *   - icofont plugin CSS    — icon font used by Site header / footer
 *
 * The root layout at apps/frontend/app/layout.tsx stays minimal (html/body +
 * fonts) so the marketing homepage doesn't pay for any careers-only
 * providers. Nested layouts only wrap their subtree.
 */
export default function CareersLayout({ children }: { children: React.ReactNode }) {
  // Phase-0 batch 5-fix — the brandDsCssVars() `<style>` override that
  // used to live here has moved up to app/layout.tsx (root) so
  // marketing routes also get the per-brand `--ds-brand-ring` on
  // `*:focus-visible`. This layout no longer needs the duplicate;
  // root covers all routes including /careers/*.
  return (
    <>
      {/* icofont is used inside careers SiteHeader/SiteFooter chrome. Local plugin, moved under /careers/. */}
      <link rel="stylesheet" href="/careers/plugins/icofont/icofont.min.css" />
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
    </>
  );
}
