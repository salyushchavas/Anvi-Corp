import Link from "next/link";
import Image from "next/image";
import { ArrowRight } from "lucide-react";

export function CareersCtaSection() {
  return (
    <section className="py-20 lg:py-28">
      <div className="container">
        <div className="relative overflow-hidden rounded-3xl bg-brand-gradient p-10 lg:p-16">
          <div className="absolute top-0 right-0 w-1/2 h-full bg-[radial-gradient(circle_at_top_right,rgba(255,255,255,0.18),transparent_60%)]" />
          <div className="relative grid gap-10 lg:grid-cols-2 lg:items-center">
            <div className="flex items-start gap-5">
              <div className="hidden sm:flex h-16 w-16 flex-shrink-0 items-center justify-center rounded-2xl bg-white/15 backdrop-blur-sm">
                <Image src="/jobs.png" alt="" width={40} height={40} className="h-10 w-10 brightness-0 invert" />
              </div>
              <div>
                <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-white/80 mb-2">
                  Careers
                </span>
                <h3 className="text-white text-2xl lg:text-3xl">
                  Explore paths where ambition and achievement are celebrated.
                </h3>
              </div>
            </div>
            <div>
              <p className="text-white/90 text-base lg:text-lg leading-relaxed mb-6">
                Elevate your career with our current openings. Whether you&apos;re an industry veteran or rising talent, explore roles crafted for your skills and aspirations.
              </p>
              <Link
                href="/careers"
                className="inline-flex items-center gap-2 rounded-full bg-white px-7 py-3.5 font-semibold text-brand hover:bg-ink-50 transition"
              >
                Explore current positions <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
