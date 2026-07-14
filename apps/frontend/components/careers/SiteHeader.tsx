'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { useAuth } from '@/lib/careers/auth-context';
import { getDashboardForUser } from '@/lib/careers/role-routing';
import { BRAND } from '@/lib/careers/brand';

const NAV_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'HOME',       href: '/' },
  { label: 'SERVICES',   href: '/#services' },
  { label: 'INDUSTRIES', href: '/#industries' },
  { label: 'ABOUT',      href: '/#about' },
  { label: 'CONTACT',    href: '/contact' },
];

export default function SiteHeader() {
  const router = useRouter();
  const { user, isLoading, logout } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  function handleLogout() {
    logout();
    router.push('/careers/login');
  }

  function closeMobile() {
    setMobileOpen(false);
  }

  const authenticatedDashboard = user ? getDashboardForUser(user) : '/careers/login';
  const primaryRole = user?.roles?.[0] ?? '';

  return (
    <header className="bg-skyzen-dark text-skyzen-text">
      {/* Top bar — email + phone from _legacy/contact-us.php (real Anvi values). */}
      <div className="border-b border-skyzen-border bg-skyzen-dark/95 text-[13px]">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-2">
          <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
            <a
              href={`mailto:${BRAND.supportEmail}`}
              className="inline-flex items-center text-skyzen-muted transition hover:text-accent"
            >
              <i className="icofont-email mr-1.5 text-accent" />
              {BRAND.supportEmail}
            </a>
            <a
              href="tel:+14699454554"
              className="inline-flex items-center text-skyzen-muted transition hover:text-accent"
            >
              <i className="icofont-phone mr-1.5 text-accent" />
              +1 469-945-4554
            </a>
          </div>
          {/* Social icons intentionally omitted — no real Anvi social URLs in _legacy/
              (the homepage had placeholder <a href="#"> icons; apply.php's real URLs
              belonged to Blueera, a different company). Add back when accounts exist. */}
        </div>
      </div>

      {/* Main nav — wrapped in a pill container (matches legacy index.html .nav-inner) */}
      <nav className="px-4 py-4 lg:py-5">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 rounded-full border border-white/10 bg-[#2a2d35] px-4 py-2.5 shadow-[0_8px_32px_rgba(0,0,0,0.4)] lg:px-7">
        {/* Brand — reuses the marketing wordmark logo (public/logo.png).
            The logo IS the wordmark, so we drop the previous SKY-ZEN text
            span (Skyzen had an icon-only logo + text wordmark; Anvi's is
            a single wordmark image). A small vertical separator + product
            label indicates you're in the /careers section vs the marketing site. */}
        <Link href="/" className="flex items-center gap-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={BRAND.logoUrl}
            alt={BRAND.name}
            className="h-8 lg:h-9 w-auto rounded bg-white/95 px-1.5 py-1"
          />
          <span className="hidden border-l border-white/20 pl-3 text-[10px] font-medium uppercase tracking-[0.18em] text-white/70 sm:inline">
            {BRAND.productName}
          </span>
        </Link>

        {/* Desktop nav */}
        <ul className="hidden items-center gap-1 lg:flex">
          {NAV_LINKS.map((link) => (
            <li key={link.href}>
              <Link
                href={link.href}
                className="block whitespace-nowrap rounded-md px-3 py-2 text-xs font-medium tracking-wide text-white/75 transition hover:bg-white/10 hover:text-white"
              >
                {link.label}
              </Link>
            </li>
          ))}
          <li>
            <Link
              href="/careers/openings"
              className="inline-flex items-center gap-1.5 rounded-md border border-accent/40 px-3 py-1.5 text-xs font-medium tracking-wide text-accent transition hover:border-accent hover:bg-accent/10"
            >
              <i className="icofont-briefcase" />
              CAREERS
            </Link>
          </li>
          <li className="ml-2">
            {isLoading ? (
              <span className="inline-block h-9 w-24 animate-pulse rounded-full bg-white/10" />
            ) : user ? (
              <div className="flex items-center gap-2">
                <Link
                  href={authenticatedDashboard}
                  className="rounded-full bg-gradient-to-br from-accent to-accent-dark px-5 py-2 text-xs font-semibold uppercase tracking-wide text-white shadow-glow-accent transition hover:shadow-glow-accent-lg"
                >
                  Dashboard
                </Link>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="rounded-md border border-skyzen-border px-3 py-1.5 text-xs font-medium text-white/80 transition hover:border-white/30 hover:text-white"
                  aria-label={`Logout ${primaryRole.toLowerCase()}`}
                >
                  Logout
                </button>
              </div>
            ) : (
              <Link
                href="/careers/login"
                className="rounded-full bg-gradient-to-br from-accent to-accent-dark px-5 py-2 text-xs font-semibold uppercase tracking-wide text-white shadow-glow-accent transition hover:shadow-glow-accent-lg"
              >
                Sign in
              </Link>
            )}
          </li>
        </ul>

        {/* Mobile toggle */}
        <button
          type="button"
          onClick={() => setMobileOpen((v) => !v)}
          className="inline-flex h-10 w-10 items-center justify-center rounded-md text-white/85 transition hover:bg-white/10 lg:hidden"
          aria-label="Toggle menu"
          aria-expanded={mobileOpen}
        >
          <i className={mobileOpen ? 'icofont-close text-lg' : 'icofont-navigation-menu text-lg'} />
        </button>
        </div>
      </nav>

      {/* Mobile menu */}
      {mobileOpen && (
        <div className="border-t border-skyzen-border bg-skyzen-dark/98 lg:hidden">
          <ul className="mx-auto max-w-7xl space-y-1 px-4 py-4">
            {NAV_LINKS.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  onClick={closeMobile}
                  className="block rounded-md px-3 py-2.5 text-sm font-medium text-white/85 transition hover:bg-white/10 hover:text-white"
                >
                  {link.label}
                </Link>
              </li>
            ))}
            <li className="border-t border-skyzen-border pt-2">
              <Link
                href="/careers/openings"
                onClick={closeMobile}
                className="block rounded-md px-3 py-2.5 text-sm font-medium text-accent transition hover:bg-accent/10"
              >
                Careers
              </Link>
            </li>
            {!isLoading && !user && (
              <li>
                <Link
                  href="/careers/login"
                  onClick={closeMobile}
                  className="block rounded-md bg-gradient-to-br from-accent to-accent-dark px-3 py-2.5 text-center text-sm font-semibold text-white"
                >
                  Sign in
                </Link>
              </li>
            )}
            {!isLoading && user && (
              <li>
                <Link
                  href={authenticatedDashboard}
                  onClick={closeMobile}
                  className="block rounded-md bg-gradient-to-br from-accent to-accent-dark px-3 py-2.5 text-center text-sm font-semibold text-white"
                >
                  Dashboard
                </Link>
              </li>
            )}
          </ul>
        </div>
      )}
    </header>
  );
}
