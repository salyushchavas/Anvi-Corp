import Link from "next/link";
import { Mail, MapPin, Phone } from "lucide-react";
import { Logo } from "./logo";
import { FacebookIcon, InstagramIcon, LinkedInIcon, TwitterIcon } from "./social-icons";
import { BRAND } from "@/lib/careers/brand";

// Phase-0 batch 3 — contact info flows from BRAND.*. Composed values
// stay byte-identical for the current Anvi deploy: BRAND defaults for
// phone / address / contactEmail / legalName are exact matches to the
// prior hardcoded literals. Per-brand clone swaps via env vars.
const streetLine = `${BRAND.addressLine1}, ${BRAND.addressLine2}`;
const cityLine = `${BRAND.city}, ${BRAND.state} ${BRAND.postalCode}`;
// Digits-only tel: href — mail clients + phone dialers want the E.164
// form without the display spacing.
const telHref = `tel:${BRAND.phone.replace(/[^\d+]/g, "")}`;

const services = [
  { href: "/services/software-development", label: "Software Development" },
  { href: "/services/cloud-development", label: "Cloud Development" },
  { href: "/services/mobile-application-development", label: "Mobile App Development" },
  { href: "/services/it-consulting", label: "IT Consulting" },
];

const company = [
  { href: "/#about", label: "About" },
  { href: "/#industries", label: "Industries" },
  { href: "/careers", label: "Careers" },
  { href: "/contact", label: "Contact" },
  { href: "/privacy", label: "Privacy Policy" },
];

const social = [
  { href: "#", label: "Instagram", Icon: InstagramIcon },
  { href: "#", label: "LinkedIn",  Icon: LinkedInIcon  },
  { href: "#", label: "Facebook",  Icon: FacebookIcon  },
  { href: "#", label: "Twitter",   Icon: TwitterIcon   },
];

export function SiteFooter() {
  return (
    <footer className="bg-ink-900 text-ink-200">
      <div className="container py-16 lg:py-20">
        <div className="grid gap-10 md:grid-cols-2 lg:grid-cols-4">
          <div>
            <div className="mb-5 inline-flex bg-white rounded-xl p-2">
              <Logo />
            </div>
            <p className="text-sm leading-relaxed text-ink-300 max-w-xs">
              Building tomorrow&apos;s future today. Advanced IT solutions tailored to your business.
            </p>
            <div className="mt-5 flex gap-3">
              {social.map(({ href, label, Icon }) => (
                <Link
                  key={label}
                  href={href}
                  aria-label={label}
                  className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-ink-700 text-ink-300 hover:border-brand hover:text-brand transition"
                >
                  <Icon className="h-4 w-4" />
                </Link>
              ))}
            </div>
          </div>

          <div>
            <h4 className="text-sm font-semibold uppercase tracking-wider text-white mb-4">Services</h4>
            <ul className="space-y-2.5 text-sm">
              {services.map(s => (
                <li key={s.href}>
                  <Link href={s.href} className="text-ink-300 hover:text-brand">{s.label}</Link>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h4 className="text-sm font-semibold uppercase tracking-wider text-white mb-4">Company</h4>
            <ul className="space-y-2.5 text-sm">
              {company.map(c => (
                <li key={c.href}>
                  <Link href={c.href} className="text-ink-300 hover:text-brand">{c.label}</Link>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h4 className="text-sm font-semibold uppercase tracking-wider text-white mb-4">Get in touch</h4>
            <ul className="space-y-3 text-sm">
              <li className="flex gap-3">
                <MapPin className="h-5 w-5 flex-shrink-0 text-brand mt-0.5" />
                <span className="text-ink-300">{streetLine}<br/>{cityLine}</span>
              </li>
              <li className="flex gap-3">
                <Phone className="h-5 w-5 flex-shrink-0 text-brand mt-0.5" />
                <a href={telHref} className="text-ink-300 hover:text-brand">{BRAND.phone}</a>
              </li>
              <li className="flex gap-3">
                <Mail className="h-5 w-5 flex-shrink-0 text-brand mt-0.5" />
                <a href={`mailto:${BRAND.contactEmail}`} className="text-ink-300 hover:text-brand">{BRAND.contactEmail}</a>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <div className="border-t border-ink-700">
        <div className="container py-5 flex flex-col md:flex-row items-center justify-between gap-3 text-xs text-ink-300">
          <p>© {new Date().getFullYear()} {BRAND.legalName}. All rights reserved.</p>
          <div className="flex gap-5">
            <Link href="/privacy" className="hover:text-brand">Privacy Policy</Link>
            <Link href="/contact" className="hover:text-brand">Contact</Link>
          </div>
        </div>
      </div>
    </footer>
  );
}
