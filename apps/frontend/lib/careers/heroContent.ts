// ─────────────────────────────────────────────────────────────────────
// PER-BRAND CONTENT — Hero carousel copy
// ─────────────────────────────────────────────────────────────────────
// This is the one file a per-brand clone edits to change the marketing
// hero. Slides are rendered by components/home/hero.tsx as a three-line
// stack; the shape { line1, line2, line3 } lets each brand pick its own
// hero-specific line breaks and punctuation (which BRAND.siteTagline —
// used in metadata + footer — does NOT capture).
//
// FOR ANVI: the three slides below preserve the exact copy the hero
// component shipped before Phase-0 extracted them. Slide 1 is a stylized
// three-line rendition of BRAND.siteTagline; slides 2 and 3 are Anvi
// hero taglines carried over from the pre-Phase-0 hero component.
//
// FOR A CLONE: replace the slide copy below with the clone's own hero
// voice. Keeping three slides preserves the existing carousel cadence;
// changing the count is safe (the component just renders whatever
// HERO_SLIDES contains).

export interface HeroSlide {
  line1: string;
  line2: string;
  line3: string;
}

export const HERO_SLIDES: ReadonlyArray<HeroSlide> = [
  // Slide 1 — the stylized 3-line rendition of the brand tagline. For Anvi
  // this reads as "Building / Tomorrow's, / Future Today!" — same shape a
  // clone should follow for the primary hero panel. Kept verbatim (not
  // wired to BRAND.siteTagline) because the hero uses per-brand
  // punctuation + line breaks that BRAND.siteTagline's short marketing
  // line does not carry.
  {
    line1: "Building",
    line2: "Tomorrow's,",
    line3: "Future Today!",
  },
  // Slide 2 — supporting tagline. Per-brand voice.
  {
    line1: "Dreaming",
    line2: "Big, Building",
    line3: "Bigger",
  },
  // Slide 3 — supporting tagline. Per-brand voice.
  {
    line1: "Transforming",
    line2: "Dreams into",
    line3: "Reality",
  },
];
