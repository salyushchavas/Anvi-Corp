import type { Metadata } from "next";
import { ServicePageTemplate } from "@/components/service-page-template";
import { BRAND, BRAND_SHORT } from "@/lib/careers/brand";

export const metadata: Metadata = {
  title: "Mobile Application Development",
  description: `${BRAND_SHORT} mobile application development: iOS, Android, and cross-platform apps engineered for performance, usability, and reach.`,
};

export default function MobileAppDevelopmentPage() {
  return (
    <ServicePageTemplate
      banner="Mobile Application Development"
      eyebrow="Mobile Application Development"
      heading={`Achieve mobile excellence with ${BRAND_SHORT} Mobile Application Development`}
      intro={`In today's mobile-first world, having a powerful and engaging mobile application is crucial. ${BRAND.name} specializes in developing robust mobile applications that deliver exceptional user experiences.`}
      image="/services/mobile-application-development.png"
      items={[
        { letter: "A", lead: "ll-Platform Expertise:",  text: "Specializing in iOS, Android, and cross-platform development." },
        { letter: "N", lead: "ext-Level Performance:",  text: "Using the latest technologies and methodologies for high-performance apps." },
        { letter: "V", lead: "isually Appealing:",      text: "Creating intuitive and visually appealing mobile solutions." },
        { letter: "I", lead: "ncreased Reach:",         text: "Ensuring your app reaches the widest possible audience." },
      ]}
    />
  );
}
