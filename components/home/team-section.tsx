import Image from "next/image";

export function TeamSection() {
  return (
    <section className="relative py-20 lg:py-28 bg-ink-50">
      <div className="container grid gap-12 lg:grid-cols-2 lg:gap-16 items-center">
        <div className="relative rounded-3xl overflow-hidden shadow-card aspect-[4/3] lg:aspect-auto lg:h-[480px]">
          <Image src="/team.jpg" alt="Our team" fill className="object-cover" sizes="(min-width:1024px) 50vw, 100vw" />
          <div className="absolute inset-0 bg-gradient-to-tr from-ink-900/40 to-transparent" />
        </div>
        <div>
          <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
            Our team
          </span>
          <h2 className="mb-5">A team of experts that delivers <span className="text-brand">assurance</span> of quality and reliability.</h2>
          <p className="text-ink-400 mb-8 leading-relaxed">
            Highly skilled professionals dedicated to redefining excellence in IT consulting and software development. A wealth of experience and a passion for innovation.
          </p>
          <div className="grid gap-6 sm:grid-cols-2">
            <Feature
              title="Empowering professionals"
              body="We empower individuals with opportunities in the dynamic IT industry."
            />
            <Feature
              title="Client-centric approach"
              body="We go above and beyond to deliver tailored solutions that drive measurable results."
            />
          </div>
        </div>
      </div>
    </section>
  );
}

function Feature({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-2xl bg-white p-5 shadow-card border border-ink-100">
      <h4 className="text-base mb-2">{title}</h4>
      <p className="text-sm text-ink-400 leading-relaxed">{body}</p>
    </div>
  );
}
