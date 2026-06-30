import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";

const services = [
  {
    href: "/services/software-development",
    title: "Software Development",
    img:   "/services/software-developer.png",
    blurb: "Custom software built end-to-end, from concept to production.",
  },
  {
    href: "/services/mobile-application-development",
    title: "Mobile App Development",
    img:   "/services/app-development.png",
    blurb: "iOS, Android, and cross-platform apps with exceptional UX.",
  },
  {
    href: "/services/cloud-development",
    title: "Cloud Development",
    img:   "/services/cloud-computing.png",
    blurb: "Scalable cloud architecture across AWS, Azure, and Google Cloud.",
  },
  {
    href: "/services/it-consulting",
    title: "IT Consulting",
    img:   "/services/it-support.png",
    blurb: "Strategic guidance and tailored solutions for your unique challenges.",
  },
];

export function ServicesGrid() {
  return (
    <section id="services" className="py-20 lg:py-28">
      <div className="container">
        <div className="flex flex-col lg:flex-row lg:items-end lg:justify-between gap-6 mb-14">
          <div className="max-w-2xl">
            <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
              Services we offer
            </span>
            <h2>We deliver <span className="text-brand">advanced IT solutions</span> tailored to your needs.</h2>
          </div>
        </div>

        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {services.map((s, i) => (
            <Link
              key={s.href}
              href={s.href}
              className="group relative flex flex-col rounded-3xl bg-white p-6 lg:p-8 shadow-card hover:shadow-cardHover transition-all hover:-translate-y-1 border border-ink-100"
            >
              <span className="absolute top-6 right-6 text-xs font-semibold text-ink-200">
                {String(i + 1).padStart(2, "0")}
              </span>
              <div className="mb-6 inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-brand-50 group-hover:bg-brand-100 transition">
                <Image src={s.img} alt="" width={48} height={48} className="h-12 w-12 object-contain" />
              </div>
              <h3 className="text-xl mb-3 group-hover:text-brand transition">{s.title}</h3>
              <p className="text-sm text-ink-400 flex-grow">{s.blurb}</p>
              <span className="mt-6 inline-flex items-center gap-1.5 text-sm font-semibold text-brand">
                Read more <ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-1" />
              </span>
            </Link>
          ))}
        </div>
      </div>
    </section>
  );
}
