import { Hero } from "@/components/home/hero";
import { ServicesGrid } from "@/components/home/services-grid";
import { IndustriesSection } from "@/components/home/industries-section";
import { AboutSection } from "@/components/home/about-section";
import { TeamSection } from "@/components/home/team-section";
import { CareersCtaSection } from "@/components/home/careers-cta-section";
import { BlogSection } from "@/components/home/blog-section";
import { FinalCtaSection } from "@/components/home/final-cta-section";

export default function HomePage() {
  return (
    <>
      <Hero />
      <ServicesGrid />
      <IndustriesSection />
      <AboutSection />
      <TeamSection />
      <CareersCtaSection />
      <BlogSection />
      <FinalCtaSection />
    </>
  );
}
