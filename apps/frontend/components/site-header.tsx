"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronDown, Menu, X } from "lucide-react";
import { Logo } from "./logo";
import { ButtonLink } from "./button";

const services = [
  { href: "/services/software-development",            label: "Software Development" },
  { href: "/services/cloud-development",               label: "Cloud Development" },
  { href: "/services/mobile-application-development",  label: "Mobile App Development" },
  { href: "/services/it-consulting",                   label: "IT Consulting" },
];

export function SiteHeader() {
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [servicesOpen, setServicesOpen] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 12);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    document.body.style.overflow = mobileOpen ? "hidden" : "";
    return () => { document.body.style.overflow = ""; };
  }, [mobileOpen]);

  return (
    <header
      className={`sticky top-0 z-50 w-full transition-all ${
        scrolled ? "bg-white/95 backdrop-blur-md shadow-sm" : "bg-white"
      }`}
    >
      <div className="container flex h-20 lg:h-24 items-center justify-between">
        <Logo />

        {/* Desktop nav — matches legacy IA: Home / Services / Industries / About Us / Careers / Blogs + Contact button */}
        <nav className="hidden lg:flex items-center gap-1" aria-label="Primary">
          <NavLink href="/">Home</NavLink>
          <div
            className="relative"
            onMouseEnter={() => setServicesOpen(true)}
            onMouseLeave={() => setServicesOpen(false)}
          >
            <button
              className="inline-flex items-center gap-1 px-4 py-2 text-base font-medium text-ink-800 hover:text-brand transition-colors"
              aria-haspopup="true"
              aria-expanded={servicesOpen}
              onClick={() => setServicesOpen(v => !v)}
            >
              Services <ChevronDown className="h-4 w-4" />
            </button>
            {servicesOpen && (
              <div className="absolute left-0 top-full pt-2">
                <div className="w-72 rounded-2xl border border-ink-100 bg-white shadow-card p-2">
                  {services.map(s => (
                    <Link
                      key={s.href}
                      href={s.href}
                      className="block rounded-xl px-4 py-3 text-sm font-medium text-ink-700 hover:bg-brand-50 hover:text-brand"
                      onClick={() => setServicesOpen(false)}
                    >
                      {s.label}
                    </Link>
                  ))}
                </div>
              </div>
            )}
          </div>
          <NavLink href="/#industries">Industries</NavLink>
          <NavLink href="/#about">About Us</NavLink>
          <NavLink href="/careers/openings">Careers</NavLink>
          <NavLink href="/#blog">Blogs</NavLink>
          <ButtonLink href="/careers/login" variant="outline" size="md" className="ml-3">
            Login
          </ButtonLink>
          <ButtonLink href="/contact" size="md" className="ml-2">
            Contact Us
          </ButtonLink>
        </nav>

        {/* Mobile toggle */}
        <button
          className="lg:hidden inline-flex h-10 w-10 items-center justify-center rounded-lg text-ink-800 hover:bg-ink-50"
          aria-label={mobileOpen ? "Close menu" : "Open menu"}
          onClick={() => setMobileOpen(v => !v)}
        >
          {mobileOpen ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
        </button>
      </div>

      {/* Mobile drawer */}
      {mobileOpen && (
        <div className="lg:hidden border-t border-ink-100 bg-white">
          <nav className="container py-4 flex flex-col gap-1" aria-label="Mobile">
            <MobileLink href="/" onClick={() => setMobileOpen(false)}>Home</MobileLink>
            <details className="group">
              <summary className="flex cursor-pointer items-center justify-between rounded-lg px-4 py-3 text-base font-medium text-ink-800 hover:bg-ink-50 list-none">
                Services
                <ChevronDown className="h-4 w-4 transition-transform group-open:rotate-180" />
              </summary>
              <div className="ml-3 mt-1 mb-2 flex flex-col gap-1 border-l-2 border-brand-100 pl-3">
                {services.map(s => (
                  <Link
                    key={s.href}
                    href={s.href}
                    className="rounded-lg px-4 py-2 text-sm text-ink-700 hover:bg-brand-50 hover:text-brand"
                    onClick={() => setMobileOpen(false)}
                  >
                    {s.label}
                  </Link>
                ))}
              </div>
            </details>
            <MobileLink href="/#industries" onClick={() => setMobileOpen(false)}>Industries</MobileLink>
            <MobileLink href="/#about" onClick={() => setMobileOpen(false)}>About Us</MobileLink>
            <MobileLink href="/careers/openings" onClick={() => setMobileOpen(false)}>Careers</MobileLink>
            <MobileLink href="/#blog" onClick={() => setMobileOpen(false)}>Blogs</MobileLink>
            <div className="pt-2 flex flex-col gap-2">
              <ButtonLink
                href="/careers/login"
                variant="outline"
                className="w-full"
                onClick={() => setMobileOpen(false)}
              >
                Login
              </ButtonLink>
              <ButtonLink href="/contact" className="w-full" onClick={() => setMobileOpen(false)}>
                Contact Us
              </ButtonLink>
            </div>
          </nav>
        </div>
      )}
    </header>
  );
}

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} className="px-4 py-2 text-base font-medium text-ink-800 hover:text-brand">
      {children}
    </Link>
  );
}

function MobileLink({ href, onClick, children }: { href: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <Link href={href} onClick={onClick} className="rounded-lg px-4 py-3 text-base font-medium text-ink-800 hover:bg-ink-50">
      {children}
    </Link>
  );
}
