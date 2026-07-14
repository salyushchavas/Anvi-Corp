import Image from "next/image";
import { CheckList } from "@/components/check-list";

export function AboutSection() {
  return (
    <section id="about" className="py-20 lg:py-28">
      <div className="container grid gap-12 lg:grid-cols-2 lg:gap-16 items-center">
        <div className="relative">
          <div className="rounded-3xl overflow-hidden shadow-card">
            <Image
              src="/about-1.jpg"
              alt="Anvi Corp team at work"
              width={800}
              height={600}
              className="h-full w-full object-cover"
            />
          </div>
          <div className="hidden md:block absolute -bottom-10 -right-6 w-2/3 rounded-3xl overflow-hidden shadow-cardHover border-8 border-white">
            <Image
              src="/about-2.jpg"
              alt=""
              width={600}
              height={400}
              className="h-full w-full object-cover"
            />
          </div>
          <div className="hidden lg:block absolute -top-6 -left-6 h-24 w-24 rounded-2xl bg-brand-gradient" />
        </div>

        <div>
          <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
            Our approach
          </span>
          <h2 className="mb-5">We prioritize <span className="text-brand">attention to detail</span> in every project we undertake.</h2>
          <p className="text-ink-400 mb-8 leading-relaxed">
            At Anvi Corp, we&apos;re laser-focused on delivering meticulous, detail-oriented solutions. We dive deep, leaving no pixel unscrutinized, to make sure we deliver solutions that are polished to perfection.
          </p>
          <CheckList
            items={[
              { lead: "Analysis:",   text: "Precision-driven scrutiny." },
              { lead: "Planning:",   text: "Meticulous strategy crafting." },
              { lead: "Execution:",  text: "Flawless implementation mastery." },
              { lead: "Evaluation:", text: "Thorough quality assurance." },
            ]}
          />
        </div>
      </div>
    </section>
  );
}
