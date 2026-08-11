import type { Metadata } from "next";
import { InnerBanner } from "@/components/inner-banner";

// Standard-template Terms of Service — DRAFT copy that unblocks the
// register / signup flow (previously linked to a 404) while legal
// reviews. Every clause below is intentionally generic and MUST be
// reviewed by counsel before this page is considered binding — the
// DraftNotice at the top makes that explicit to any reader.

export const metadata: Metadata = {
  title: "Terms of Service (Draft — under review)",
  description:
    "Terms governing your use of Anvi Corp USA services. This is the draft version under legal review.",
};

export default function TermsDraftPage() {
  return (
    <>
      <InnerBanner title="Terms of Service" />
      <section className="py-16 lg:py-24">
        <div className="container max-w-3xl prose-content">
          <DraftNotice />

          <p className="mb-8 text-sm text-ink-500">
            <strong className="text-ink-800">Last updated:</strong> 2026-08-11
          </p>

          <Block title="1. Acceptance of terms">
            By accessing or using the Anvi Corp USA website, careers portal, or any
            related service (collectively, the &ldquo;Service&rdquo;), you agree to be
            bound by these Terms of Service and by our{" "}
            <a href="/privacy" className="text-brand hover:text-brand-600">
              Privacy Policy
            </a>
            . If you do not agree with any part of these terms, please do not use
            the Service.
          </Block>

          <Block title="2. Use of the Service">
            You agree to use the Service only for lawful purposes and in a manner
            that does not infringe the rights of, restrict, or inhibit any other
            person&apos;s use and enjoyment of the Service. Prohibited behavior
            includes harassing or causing distress to other users, transmitting
            obscene or offensive content, disrupting the normal flow of dialog, or
            attempting unauthorized access to the Service or its related systems.
          </Block>

          <Block title="3. Accounts and eligibility">
            To access certain features (including the careers portal, applications,
            and onboarding surfaces) you must register for an account with accurate,
            current, and complete information, and keep that information up to date.
            You are responsible for safeguarding your credentials and for all
            activity that occurs under your account. Notify us promptly of any
            unauthorized use.
          </Block>

          <Block title="4. User content">
            You retain ownership of content you submit through the Service
            (including resumes, application answers, uploaded documents, and
            profile information). By submitting content you grant Anvi Corp USA a
            non-exclusive, worldwide, royalty-free license to use, store, display,
            and process that content solely for the purpose of operating and
            improving the Service and evaluating your candidacy where applicable.
          </Block>

          <Block title="5. Intellectual property">
            All software, design, text, graphics, and other materials that make up
            the Service are the property of Anvi Corp USA or its licensors and are
            protected by intellectual-property laws. You may not copy, modify,
            distribute, sell, or lease any part of the Service without our prior
            written permission.
          </Block>

          <Block title="6. Termination">
            We may suspend or terminate your access to the Service at our
            discretion, with or without notice, for any reason including a breach
            of these terms. You may stop using the Service at any time; account
            deletion requests can be made by contacting us at{" "}
            <a
              href="mailto:info@anvicorp.com"
              className="text-brand hover:text-brand-600"
            >
              info@anvicorp.com
            </a>
            .
          </Block>

          <Block title="7. Disclaimer of warranties">
            The Service is provided on an &ldquo;as is&rdquo; and &ldquo;as
            available&rdquo; basis. To the fullest extent permitted by law, Anvi
            Corp USA disclaims all warranties, express or implied, including
            warranties of merchantability, fitness for a particular purpose, and
            non-infringement. We do not warrant that the Service will be
            uninterrupted, error-free, or free of harmful components.
          </Block>

          <Block title="8. Limitation of liability">
            To the fullest extent permitted by law, Anvi Corp USA and its
            affiliates, officers, and employees will not be liable for any
            indirect, incidental, special, consequential, or punitive damages, or
            any loss of profits or revenues, arising out of or in connection with
            your use of the Service.
          </Block>

          <Block title="9. Changes to these terms">
            We may update these Terms of Service from time to time to reflect
            changes to the Service, our business, or applicable law. Updates are
            posted on this page with a new &ldquo;Last updated&rdquo; date. Your
            continued use of the Service after an update constitutes acceptance of
            the revised terms.
          </Block>

          <Block title="10. Governing law">
            These terms are governed by the laws of the jurisdiction in which Anvi
            Corp USA is established, without regard to conflict-of-law rules. Any
            dispute arising out of or relating to these terms or the Service will
            be resolved in the courts of that jurisdiction.
          </Block>

          <Block title="11. Contact">
            Questions about these Terms of Service can be sent to{" "}
            <a
              href="mailto:info@anvicorp.com"
              className="text-brand hover:text-brand-600"
            >
              info@anvicorp.com
            </a>
            .
          </Block>
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
      This is a working template of our Terms of Service and has not yet been
      reviewed by counsel. Individual clauses may change before this page is
      considered binding.
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
