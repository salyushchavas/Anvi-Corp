import Link from "next/link";
import { ArrowRight } from "lucide-react";

export function FinalCtaSection() {
  return (
    <section className="relative overflow-hidden bg-ink-900 text-white">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_20%_50%,rgba(42,140,219,0.35),transparent_50%),radial-gradient(circle_at_80%_50%,rgba(60,114,252,0.3),transparent_50%)]" />
      <div className="container relative py-20 lg:py-24">
        <div className="grid gap-8 lg:grid-cols-3 lg:items-center">
          <div className="lg:col-span-2 max-w-3xl">
            <h2 className="text-white">
              Experience the <span className="text-brand-300">advantage</span> of partnering with Anvi Corp USA for all your IT needs.
            </h2>
          </div>
          <div className="lg:text-right">
            <Link
              href="/contact"
              className="inline-flex items-center gap-2 rounded-full bg-brand px-8 py-4 font-semibold text-white shadow-cardHover hover:bg-brand-600 transition"
            >
              Start a conversation <ArrowRight className="h-4 w-4" />
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
