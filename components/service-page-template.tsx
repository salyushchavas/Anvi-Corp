import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { InnerBanner } from "./inner-banner";
import { CheckList, type CheckItem } from "./check-list";

type Props = {
  /** Page title shown in the banner */
  banner: string;
  /** Small uppercase eyebrow above the main heading */
  eyebrow: string;
  /** Main section heading (the "Ascend with ANVI…" line) */
  heading: string;
  /** Intro paragraph */
  intro: string;
  /** A-N-V-I letter-accented bullets */
  items: CheckItem[];
  /** Optional closing paragraph (used by IT Consulting) */
  outro?: string;
  /** Hero image for the left column */
  image: string;
};

export function ServicePageTemplate({ banner, eyebrow, heading, intro, items, outro, image }: Props) {
  return (
    <>
      <InnerBanner title={banner} />

      <section className="py-20 lg:py-28">
        <div className="container grid gap-12 lg:grid-cols-2 lg:gap-16 items-center">
          <div className="relative">
            <div className="rounded-3xl overflow-hidden bg-ink-50 p-8 lg:p-12 shadow-card">
              <Image
                src={image}
                alt=""
                width={600}
                height={500}
                className="h-auto w-full object-contain"
                priority
              />
            </div>
            <div className="hidden lg:block absolute -bottom-6 -left-6 h-24 w-24 rounded-2xl bg-brand-gradient" />
          </div>

          <div>
            <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
              {eyebrow}
            </span>
            <h2 className="mb-5">{heading}</h2>
            <p className="text-ink-400 mb-8 leading-relaxed">{intro}</p>
            <CheckList items={items} />
            {outro && <p className="mt-8 text-ink-400 leading-relaxed">{outro}</p>}
            <div className="mt-10">
              <Link
                href="/contact"
                className="inline-flex items-center gap-2 rounded-full bg-brand px-7 py-3.5 font-semibold text-white shadow-card hover:bg-brand-600 hover:shadow-cardHover transition"
              >
                Start a project <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}
