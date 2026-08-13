import type { Metadata } from "next";
import { InnerBanner } from "@/components/inner-banner";
import { BRAND } from "@/lib/careers/brand";

// Privacy Policy — canonical route. Phase-0 batch 1 collapsed the
// pre-existing /privacy vs /privacy-policy duplication: this file is
// the one canonical page, and /privacy-policy now 301-redirects here
// via next.config.mjs. Company-identity variables (entity name,
// contact email) come from BRAND.* so a per-brand deploy swaps them
// via NEXT_PUBLIC_BRAND_* env vars. Legal SUBSTANCE of the clauses
// is unchanged — only the identity slots become config-driven. Legal
// review still required before this page is treated as binding.

export const metadata: Metadata = {
  title: "Privacy Policy (Draft — under review)",
  description: `How ${BRAND.legalName} collects, uses, and protects your personal information. This is the draft version under legal review.`,
};

export default function PrivacyDraftPage() {
  return (
    <>
      <InnerBanner title="Privacy Policy" />
      <section className="py-16 lg:py-24">
        <div className="container max-w-3xl prose-content">
          <DraftNotice />

          <p className="mb-8 text-sm text-ink-500">
            <strong className="text-ink-800">Last updated:</strong> 2026-08-11
          </p>

          <Block title="Our commitment to privacy">
            {BRAND.legalName} prioritizes protecting your privacy. We are committed to protecting your personal information and remaining transparent about our data practices. This privacy statement describes our online information practices and the options available to you about the collection and use of your data. We make this notice prominently displayed on our site and at all points where personally identifiable information may be sought.
          </Block>

          <Block title="The way we use information">
            We use the information you provide about yourself to fulfill your requests for information. Your information is not shared with outside parties except as necessary to complete these processes. We use return email addresses solely to respond to the inquiries we receive and do not use them for any other purpose or share them with outside parties.
          </Block>

          <Block title="Data collection and security">
            We use non-identifying and aggregate information to enhance the design of our website, ensuring a better user experience. Rest assured, we do not disclose anything that could be used to identify individuals. To maintain the security and integrity of the information we collect online, we have implemented appropriate physical, electronic, and managerial procedures to prevent unauthorized access, maintain data accuracy, and ensure proper information use.
          </Block>

          <Block title="Changes to this privacy policy">
            As part of our commitment to transparency, we may update this privacy policy periodically to reflect changes in our practices or legal requirements. Any updates will be posted on our website, and we encourage you to review this policy periodically to stay informed about how we handle personal information.
          </Block>

          <Block title="Contact us">
            Your questions, concerns, and requests regarding this privacy policy or our data practices are important to us. Please feel free to contact us at <a href={`mailto:${BRAND.contactEmail}`} className="text-brand hover:text-brand-600">{BRAND.contactEmail}</a>. We are dedicated to addressing any inquiries promptly and ensuring your privacy rights are protected.
          </Block>

          <p className="mt-10 rounded-2xl bg-ink-50 p-5 text-sm text-ink-500 border border-ink-100">
            <strong className="text-ink-800">Note:</strong> No mobile information will be shared with third parties or affiliates for marketing or promotional purposes. All the above categories exclude text messaging originator opt-in data and consent; this information will not be shared with any third parties.
          </p>
        </div>
      </section>
    </>
  );
}

function DraftNotice() {
  return (
    <div
      role="note"
      className="mb-8 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
    >
      <strong className="text-amber-900">DRAFT — pending legal review.</strong>{" "}
      This is a working version of our Privacy Policy under review. Individual
      clauses may change before this page is considered binding.
    </div>
  );
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-8">
      <h3 className="text-lg font-bold text-ink-800 mb-3">{title}</h3>
      <p className="text-ink-400 leading-relaxed">{children}</p>
    </div>
  );
}
