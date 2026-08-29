"use client";

import { useCallback, useEffect, useState } from "react";
import useEmblaCarousel from "embla-carousel-react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { FloatingCircles } from "@/components/floating-circles";
import { HeroSocialRail } from "@/components/hero-social-rail";
// Hero copy lives in one obvious per-brand file (lib/careers/heroContent.ts)
// so a clone edits it there without touching component logic. Anvi's current
// three slides are preserved as the file's defaults.
import { HERO_SLIDES } from "@/lib/careers/heroContent";

const slides = HERO_SLIDES;

export function Hero() {
  const [emblaRef, emblaApi] = useEmblaCarousel({ loop: true, duration: 35 });
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
        className="absolute inset-0 h-full w-full object-cover"
        autoPlay muted loop playsInline preload="metadata"
        poster="/about-1.jpg"
      >
        <source src="/slider/banner.mp4" type="video/mp4" />
      </video>
      {/* dark gradient veil for legibility */}
      <div className="absolute inset-0 bg-gradient-to-br from-ink-900/75 via-ink-900/50 to-ink-900/65" />

      {/* legacy floating circles */}
      <FloatingCircles />

      {/* vertical socials + scroll-down (right edge) */}
      <HeroSocialRail />

      {/* carousel viewport — MUST be overflow-hidden so slides don't bleed */}
      <div className="relative min-h-[78vh] lg:min-h-[88vh] flex items-center">
        <div className="w-full overflow-hidden" ref={emblaRef}>
          <div className="flex">
            {slides.map((s, i) => (
              <div key={i} className="min-w-0 flex-[0_0_100%]">
                <div className="container py-20 lg:py-28 pr-16 md:pr-24">
                  <div className="max-w-3xl">
                    <h1 className="text-white text-5xl md:text-6xl lg:text-7xl xl:text-[80px] leading-[1.05] font-bold">
                      {s.line1} <br />
                      {s.line2} <br />
                      {s.line3}
                    </h1>
                    <div className="mt-10">
                      <Link
                        href="/contact"
                        className="inline-flex items-center gap-2 rounded-full bg-brand px-10 py-4 text-base font-bold text-white shadow-cardHover hover:bg-brand-600 transition"
                      >
                        Read More <ArrowRight className="h-4 w-4" />
                      </Link>
                    </div>
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
