import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, Briefcase, GraduationCap, Sparkles } from "lucide-react";
import { InnerBanner } from "@/components/inner-banner";

export const metadata: Metadata = {
  title: "Careers",
  description: "Careers at Anvi Corp USA. A dedicated platform for job openings, internships, and applications is launching soon.",
};

export default function CareersPage() {
  return (
    <>
      <InnerBanner
        title="Careers at Anvi Corp"
        subtitle="Where ambition and achievement are celebrated at every turn."
      />

      <section className="py-20 lg:py-28">
        <div className="container max-w-4xl">
          <div className="rounded-3xl bg-white shadow-card border border-ink-100 p-8 lg:p-14 text-center">
            <span className="inline-flex items-center gap-2 rounded-full bg-brand-50 px-4 py-1.5 text-sm font-semibold text-brand mb-6">
              <Sparkles className="h-4 w-4" /> Coming soon
            </span>
            <h2 className="mb-4">A dedicated careers platform is on the way.</h2>
            <p className="text-lg text-ink-400 leading-relaxed mb-10 max-w-2xl mx-auto">
              We&apos;re building a full job board with openings, internships, and a streamlined application experience. In the meantime, if you&apos;d like to introduce yourself, drop us a line.
            </p>

            <div className="grid gap-5 sm:grid-cols-3 mb-12 text-left">
              <Feature
                icon={<Briefcase className="h-5 w-5" />}
                title="Full-time roles"
                body="Engineering, consulting, and operations across our service lines."
              />
              <Feature
                icon={<GraduationCap className="h-5 w-5" />}
                title="Internships"
                body="Hands-on programs designed for students and rising talent."
              />
              <Feature
                icon={<Sparkles className="h-5 w-5" />}
                title="Apply directly"
                body="Send your resume and we&apos;ll be in touch when a fit opens up."
              />
            </div>

            <Link
              href="/contact"
              className="inline-flex items-center gap-2 rounded-full bg-brand px-8 py-4 font-semibold text-white shadow-card hover:bg-brand-600 hover:shadow-cardHover transition"
            >
              Get in touch <ArrowRight className="h-4 w-4" />
            </Link>
          </div>
        </div>
      </section>
    </>
  );
}

function Feature({ icon, title, body }: { icon: React.ReactNode; title: string; body: string }) {
  return (
    <div className="rounded-2xl border border-ink-100 bg-ink-50 p-5">
      <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-white text-brand mb-3">
        {icon}
      </span>
      <h3 className="text-base font-bold mb-1.5">{title}</h3>
      <p className="text-sm text-ink-400 leading-relaxed">{body}</p>
    </div>
  );
}
