"use client";

import { useCallback, useEffect, useState } from "react";
import useEmblaCarousel from "embla-carousel-react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";

const slides = [
  {
    title: "Building Tomorrow's",
    accent: "Future, Today.",
    body: "Engineering reliable software, cloud, and IT solutions for ambitious teams.",
  },
  {
    title: "Dreaming Big,",
    accent: "Building Bigger.",
    body: "From idea to production — meticulous strategy, flawless execution, measurable outcomes.",
  },
  {
    title: "Transforming Dreams",
    accent: "into Reality.",
    body: "Advanced IT solutions tailored to your needs, powered by a team that ships.",
  },
];

export function Hero() {
  const [emblaRef, emblaApi] = useEmblaCarousel({ loop: true, duration: 30 });
  const [selectedIndex, setSelectedIndex] = useState(0);

  const scrollTo = useCallback((i: number) => emblaApi?.scrollTo(i), [emblaApi]);

  useEffect(() => {
    if (!emblaApi) return;
    const onSelect = () => setSelectedIndex(emblaApi.selectedScrollSnap());
    onSelect();
    emblaApi.on("select", onSelect);
    return () => { emblaApi.off("select", onSelect); };
  }, [emblaApi]);

  useEffect(() => {
    if (!emblaApi) return;
    const id = setInterval(() => emblaApi.scrollNext(), 6500);
    return () => clearInterval(id);
  }, [emblaApi]);

  return (
    <section className="relative isolate overflow-hidden bg-ink-900 text-white">
      {/* video background */}
      <video
        className="absolute inset-0 h-full w-full object-cover opacity-40"
        autoPlay muted loop playsInline preload="metadata"
        poster="/about-1.jpg"
      >
        <source src="/slider/banner.mp4" type="video/mp4" />
      </video>
      {/* gradient veil */}
      <div className="absolute inset-0 bg-gradient-to-br from-ink-900/85 via-ink-900/65 to-brand-900/70" />

      {/* slider */}
      <div className="relative container min-h-[78vh] lg:min-h-[88vh] flex items-center py-24">
        <div className="w-full" ref={emblaRef}>
          <div className="flex">
            {slides.map((s, i) => (
              <div key={i} className="min-w-0 flex-[0_0_100%]">
                <div className="max-w-2xl">
                  <h1 className="text-white">
                    {s.title}
                    <span className="block bg-gradient-to-r from-brand-300 to-brand-500 bg-clip-text text-transparent">
                      {s.accent}
                    </span>
                  </h1>
                  <p className="mt-6 text-lg lg:text-xl text-ink-200 max-w-xl">
                    {s.body}
                  </p>
                  <div className="mt-10 flex flex-wrap gap-4">
                    <Link
                      href="/contact"
                      className="inline-flex items-center gap-2 rounded-full bg-brand px-8 py-4 font-semibold text-white shadow-cardHover hover:bg-brand-600 transition"
                    >
                      Get in touch <ArrowRight className="h-4 w-4" />
                    </Link>
                    <Link
                      href="#services"
                      className="inline-flex items-center gap-2 rounded-full border-2 border-white/30 px-8 py-4 font-semibold text-white hover:bg-white hover:text-ink-900 transition"
                    >
                      Our services
                    </Link>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* pagination */}
      <div className="absolute bottom-8 left-1/2 -translate-x-1/2 flex gap-2 z-10">
        {slides.map((_, i) => (
          <button
            key={i}
            onClick={() => scrollTo(i)}
            aria-label={`Go to slide ${i + 1}`}
            className={`h-1.5 rounded-full transition-all ${
              i === selectedIndex ? "w-10 bg-brand" : "w-4 bg-white/40 hover:bg-white/70"
            }`}
          />
        ))}
      </div>
    </section>
  );
}
