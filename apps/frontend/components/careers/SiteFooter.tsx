import Link from 'next/link';
import { BRAND } from '@/lib/careers/brand';

const NAV_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'Home',       href: '/' },
  { label: 'Services',   href: '/#services' },
  { label: 'Industries', href: '/#industries' },
  { label: 'About',      href: '/#about' },
  { label: 'Contact',    href: '/contact' },
];

const SERVICE_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'Software Development',      href: '/services/software-development' },
  { label: 'Cloud Development',         href: '/services/cloud-development' },
  { label: 'Mobile App Development',    href: '/services/mobile-application-development' },
  { label: 'IT Consulting',             href: '/services/it-consulting' },
  { label: 'Careers',                   href: '/careers/openings' },
];

const LEGAL_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'Privacy Policy', href: '/privacy-policy' },
  // /terms doesn't exist on the Anvi site yet — dropped rather than linking to a 404.
];

export default function SiteFooter() {
  const year = new Date().getFullYear();
  return (
    <footer className="bg-[#050a14] text-skyzen-text">
      <div className="mx-auto max-w-7xl px-6 pt-16 pb-6">
        <div className="grid gap-10 md:grid-cols-2 lg:grid-cols-4">
          {/* Brand — same wordmark as marketing (public/logo.png).
              White background pill so the dark-navy footer doesn't hide
              a dark-colored logo. */}
          <div className="lg:col-span-1">
            <Link href="/" className="mb-4 inline-block">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={BRAND.logoUrl}
                alt={BRAND.name}
                className="h-10 w-auto rounded-md bg-white/95 px-2 py-1.5"
              />
            </Link>
            <p className="max-w-[260px] text-sm leading-relaxed text-skyzen-muted">
              {/* TODO: replace with an official Anvi tagline. Neutral placeholder for now. */}
              Advanced IT consulting, software development, and cloud solutions
              tailored to your needs.
            </p>
            {/* Social block removed — no Anvi LinkedIn / social URLs provided.
                Add back when the accounts exist. */}
          </div>

          {/* Navigation */}
          <div>
            <h6 className="mb-5 text-xs font-bold uppercase tracking-[0.15em] text-white">
              Navigation
            </h6>
            <ul className="space-y-2.5">
              {NAV_LINKS.map((link) => (
                <li key={link.href + link.label}>
                  <Link
                    href={link.href}
                    className="text-sm text-skyzen-muted transition hover:text-accent"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Services */}
          <div>
            <h6 className="mb-5 text-xs font-bold uppercase tracking-[0.15em] text-white">
              Services
            </h6>
            <ul className="space-y-2.5">
              {SERVICE_LINKS.map((link) => (
                <li key={link.href + link.label}>
                  <Link
                    href={link.href}
                    className="text-sm text-skyzen-muted transition hover:text-accent"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Contact */}
          <div>
            <h6 className="mb-5 text-xs font-bold uppercase tracking-[0.15em] text-white">
              Contact Info
            </h6>
            <div className="space-y-3">
              {/* Phone omitted — no Anvi phone number provided. */}
              <div className="flex items-start gap-2.5">
                <i className="icofont-email mt-0.5 text-accent" />
                <a
                  href={`mailto:${BRAND.supportEmail}`}
                  className="text-sm text-skyzen-muted transition hover:text-accent"
                >
                  {BRAND.supportEmail}
                </a>
              </div>
              <div className="flex items-start gap-2.5">
                <i className="icofont-location-pin mt-0.5 text-accent" />
                <span className="text-sm text-skyzen-muted">
                  7950 Legacy Dr, Suite 400,
                  <br />
                  Plano, TX 75024
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Bottom */}
        <div className="mt-12 flex flex-wrap items-center justify-between gap-4 border-t border-skyzen-border pt-5">
          <span className="text-xs text-skyzen-muted">
            &copy; {year} {BRAND.legalName}. All rights reserved.
          </span>
          <div className="flex flex-wrap items-center gap-4">
            {LEGAL_LINKS.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                className="text-xs text-skyzen-muted transition hover:text-accent"
              >
                {link.label}
              </Link>
            ))}
          </div>
        </div>
      </div>
    </footer>
  );
}
