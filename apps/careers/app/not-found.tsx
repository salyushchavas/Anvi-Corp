import Link from 'next/link';
import SiteFooter from '@/components/SiteFooter';
import { BRAND } from '@/lib/brand';

/**
 * App Router 404. Branded with the Skyzen dark navy header, accent gradient
 * strip, and the same SiteFooter every public page uses. Renders for any
 * route that doesn't match a defined page.
 */
export default function NotFound() {
  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <header className="bg-skyzen-dark py-4 text-white">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-6">
          <Link href="/" className="flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center overflow-hidden rounded-md bg-white/10 p-1">
              <img
                src={BRAND.logoUrl}
                alt={BRAND.name}
                className="h-7 w-7 object-contain"
              />
            </span>
            <span className="flex flex-col leading-tight">
              <span className="text-[17px] font-extrabold uppercase tracking-wide">
                <span className="text-white">SKY</span>
                <span className="text-accent">ZEN</span>
              </span>
              <span className="text-[10px] uppercase tracking-[0.15em] text-white/50">
                Technologies LLC
              </span>
            </span>
          </Link>
        </div>
      </header>
      <div className="bg-gradient-to-r from-accent to-accent-dark px-6 py-1" />

      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center px-6 py-16 text-center">
        <p className="text-7xl font-extrabold tracking-tight text-accent">404</p>
        <h1 className="mt-4 text-2xl font-bold text-gray-900">
          We can&apos;t find that page.
        </h1>
        <p className="mt-2 max-w-md text-sm text-gray-600">
          The link might be old, or it may have moved. Try going back to the
          home page, or to your dashboard if you&apos;re signed in.
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/"
            className="rounded-full bg-gradient-to-r from-accent to-accent-dark px-5 py-2 text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg"
          >
            Go home
          </Link>
          <Link
            href="/careers/intern"
            className="rounded-full border border-gray-300 bg-white px-5 py-2 text-sm font-medium text-gray-700 transition hover:bg-gray-50"
          >
            Go to dashboard
          </Link>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
