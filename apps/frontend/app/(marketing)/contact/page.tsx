import type { Metadata } from "next";
import { Mail, MapPin, Phone } from "lucide-react";
import { InnerBanner } from "@/components/inner-banner";
import { ContactForm } from "@/components/contact-form";
import { BRAND } from "@/lib/careers/brand";

// Phase-0 batch 3 — contact info flows from BRAND.*. Composed values
// byte-identical to pre-migration for Anvi.
const streetLine = `${BRAND.addressLine1}, ${BRAND.addressLine2}`;
const cityLine = `${BRAND.city}, ${BRAND.state} ${BRAND.postalCode}`;
const telHref = `tel:${BRAND.phone.replace(/[^\d+]/g, "")}`;

export const metadata: Metadata = {
  title: "Contact",
  description: `Get in touch with ${BRAND.legalName} — ${BRAND.city}, ${BRAND.state}. We typically reply within one business day.`,
};

export default function ContactPage() {
  return (
    <>
      <InnerBanner title="Contact Us" subtitle="We'd love to hear about your project — drop us a line." />

      <section className="py-16 lg:py-24">
        <div className="container grid gap-10 lg:grid-cols-5">
          <div className="lg:col-span-2 space-y-5">
            <h2 className="mb-2 text-2xl lg:text-3xl">Let&apos;s talk.</h2>
            <p className="text-ink-400 mb-6 leading-relaxed">
              Tell us about your goals and we&apos;ll get back to you within one business day.
            </p>

            <ContactItem
              icon={<MapPin className="h-5 w-5" />}
              title="Office"
              body={<>{streetLine}<br/>{cityLine}</>}
            />
            <ContactItem
              icon={<Phone className="h-5 w-5" />}
              title="Phone"
              body={<a href={telHref} className="hover:text-brand">{BRAND.phone}</a>}
            />
            <ContactItem
              icon={<Mail className="h-5 w-5" />}
              title="Email"
              body={<a href={`mailto:${BRAND.contactEmail}`} className="hover:text-brand">{BRAND.contactEmail}</a>}
            />
          </div>

          <div className="lg:col-span-3">
            <div className="rounded-3xl bg-white shadow-card border border-ink-100 p-6 lg:p-8">
              <ContactForm />
            </div>
          </div>
        </div>
      </section>
    </>
  );
}

function ContactItem({ icon, title, body }: { icon: React.ReactNode; title: string; body: React.ReactNode }) {
  return (
    <div className="flex gap-4 rounded-2xl border border-ink-100 bg-white p-5 shadow-card">
      <span className="inline-flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand">
        {icon}
      </span>
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-ink-400">{title}</p>
        <p className="text-ink-800 text-base">{body}</p>
      </div>
    </div>
  );
}
