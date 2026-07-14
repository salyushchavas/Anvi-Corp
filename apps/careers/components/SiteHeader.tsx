'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { useAuth } from '@/lib/auth-context';
import { getDashboardForUser } from '@/lib/role-routing';

const NAV_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'HOME', href: '/' },
  { label: 'SERVICES', href: '/#what-we-do' },
  { label: 'WHY WE?', href: '/#why-we' },
  { label: 'OUR EXPERTISE', href: '/#our-expertise' },
  { label: 'CAREER SUPPORT', href: '/#career-support' },
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
      {/* Top bar */}
      <div className="border-b border-skyzen-border bg-skyzen-dark/95 text-[13px]">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-2">
          <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
            <a
              href="mailto:info@skyzentech.com"
              className="inline-flex items-center text-skyzen-muted transition hover:text-accent"
            >
              <i className="icofont-email mr-1.5 text-accent" />
              info@skyzentech.com
            </a>
            <a
              href="tel:4699453339"
              className="inline-flex items-center text-skyzen-muted transition hover:text-accent"
            >
              <i className="icofont-phone mr-1.5 text-accent" />
              +1 469-945-3339
            </a>
          </div>
          <div className="flex items-center gap-2">
            <a
              href="https://www.linkedin.com/company/skyzen-tech-llc/"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="LinkedIn"
              className="inline-flex h-7 w-7 items-center justify-center rounded-full border border-skyzen-border text-skyzen-muted transition hover:border-accent hover:text-accent"
            >
              <i className="icofont-linkedin" />
            </a>
          </div>
        </div>
      </div>

      {/* Main nav — wrapped in a pill container (matches legacy index.html .nav-inner) */}
      <nav className="px-4 py-4 lg:py-5">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 rounded-full border border-white/10 bg-[#2a2d35] px-4 py-2.5 shadow-[0_8px_32px_rgba(0,0,0,0.4)] lg:px-7">
        {/* Brand */}
        <Link href="/" className="flex items-center gap-2.5">
          <span className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-md">
            <img
              src="/images/skyzen-logo.png"
              alt="Skyzen"
              className="h-9 w-9 object-contain"
            />
          </span>
          <span className="flex flex-col leading-tight">
            <span className="text-[18px] font-extrabold uppercase tracking-wide">
              <span className="text-white">SKY</span>
              <span className="text-accent">ZEN</span>
            </span>
            <span className="text-[10px] uppercase tracking-[0.15em] text-white/50">
              Technologies LLC
            </span>
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
                Login
              </Link>
            )}
          </li>
        </ul>

        {/* Mobile toggle */}
        <button
          type="button"
          onClick={() => setMobileOpen((v) => !v)}
          aria-label="Toggle menu"
          aria-expanded={mobileOpen}
          className="rounded-md border border-white/10 p-2 text-white/80 lg:hidden"
        >
          <i className={mobileOpen ? 'icofont-close-line' : 'icofont-navigation-menu'} />
        </button>
        </div>
      </nav>

      {/* Mobile menu */}
      {mobileOpen && (
        <div className="border-t border-skyzen-border bg-skyzen-dark/98 lg:hidden">
          <ul className="mx-auto flex max-w-7xl flex-col gap-1 px-4 py-3">
            {NAV_LINKS.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  onClick={closeMobile}
                  className="block rounded px-3 py-2 text-sm font-medium text-white/80 hover:bg-white/10 hover:text-white"
                >
                  {link.label}
                </Link>
              </li>
            ))}
            <li>
              <Link
                href="/careers/openings"
                onClick={closeMobile}
                className="block rounded px-3 py-2 text-sm font-medium text-accent hover:bg-accent/10"
              >
                CAREERS
              </Link>
            </li>
            <li className="border-t border-skyzen-border pt-2">
              {user ? (
                <>
                  <Link
                    href={authenticatedDashboard}
                    onClick={closeMobile}
                    className="block rounded px-3 py-2 text-sm font-semibold text-white hover:bg-white/10"
                  >
                    Dashboard
                  </Link>
                  <button
                    type="button"
                    onClick={() => {
                      closeMobile();
                      handleLogout();
                    }}
                    className="block w-full rounded px-3 py-2 text-left text-sm text-white/70 hover:bg-white/5"
                  >
                    Logout
                  </button>
                </>
              ) : (
                <Link
                  href="/careers/login"
                  onClick={closeMobile}
                  className="block rounded px-3 py-2 text-sm font-semibold text-accent hover:bg-accent/10"
                >
                  Login
                </Link>
              )}
            </li>
          </ul>
        </div>
      )}
    </header>
  );
}
