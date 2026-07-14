import Link from 'next/link';

const NAV_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'Home', href: '/' },
  { label: 'Services', href: '/#what-we-do' },
  { label: 'Why We', href: '/#why-we' },
  { label: 'Our Expertise', href: '/#our-expertise' },
  { label: 'Career Support', href: '/#career-support' },
  { label: 'Contact', href: '/#contact' },
];

const SERVICE_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'IT Consulting', href: '/#what-we-do' },
  { label: 'Software Dev', href: '/#what-we-do' },
  { label: 'Training', href: '/#what-we-do' },
  { label: 'Staffing', href: '/#what-we-do' },
  { label: 'Careers', href: '/careers/openings' },
];

const LEGAL_LINKS: ReadonlyArray<{ label: string; href: string }> = [
  { label: 'Privacy Policy', href: '/privacy' },
  { label: 'Terms of Service', href: '/terms' },
];

export default function SiteFooter() {
  const year = new Date().getFullYear();
  return (
    <footer className="bg-[#050a14] text-skyzen-text">
      <div className="mx-auto max-w-7xl px-6 pt-16 pb-6">
        <div className="grid gap-10 md:grid-cols-2 lg:grid-cols-4">
          {/* Brand */}
          <div className="lg:col-span-1">
            <Link href="/" className="mb-4 inline-flex items-center gap-2.5">
              <span className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-md bg-white/10 p-1">
                <img
                  src="/images/skyzen-logo.png"
                  alt="Skyzen"
                  className="h-8 w-8 object-contain"
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
            <p className="max-w-[260px] text-sm leading-relaxed text-skyzen-muted">
              Skyzen Technologies LLC — where technology meets excellence. Premier IT
              consulting, staffing, and training in Plano, TX.
            </p>
            <div className="mt-5 flex items-center gap-2">
              <a
                href="https://www.linkedin.com/company/skyzen-tech-llc/"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="LinkedIn"
                className="flex h-9 w-9 items-center justify-center rounded-md border border-skyzen-border text-skyzen-muted transition hover:border-accent hover:bg-accent/10 hover:text-accent"
              >
                <i className="icofont-linkedin" />
              </a>
            </div>
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
              <div className="flex items-start gap-2.5">
                <i className="icofont-phone mt-0.5 text-accent" />
                <a
                  href="tel:4699453339"
                  className="text-sm text-skyzen-muted transition hover:text-accent"
                >
                  +1 469-945-3339
                </a>
              </div>
              <div className="flex items-start gap-2.5">
                <i className="icofont-email mt-0.5 text-accent" />
                <a
                  href="mailto:info@skyzentech.com"
                  className="text-sm text-skyzen-muted transition hover:text-accent"
                >
                  info@skyzentech.com
                </a>
              </div>
              <div className="flex items-start gap-2.5">
                <i className="icofont-location-pin mt-0.5 text-accent" />
                <span className="text-sm text-skyzen-muted">
                  5465 Legacy Drive, Suite 650,
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
            &copy; {year} Skyzen Technologies LLC. All rights reserved.
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
