import type { Metadata } from "next";
import { ServicePageTemplate } from "@/components/service-page-template";

export const metadata: Metadata = {
  title: "Cloud Development",
  description:
    "ANVI cloud development services: migration, development, and management across AWS, Azure, and Google Cloud — built for scale, security, and performance.",
};

export default function CloudDevelopmentPage() {
  return (
    <ServicePageTemplate
      banner="Cloud Development"
      eyebrow="Cloud Development"
      heading="Ascend with ANVI Cloud Solutions"
      intro="Anvi Corp offers state-of-the-art cloud development services that empower businesses to leverage the full potential of cloud computing."
      image="/services/cloud-development.png"
      items={[
        { letter: "A", lead: "gile Solutions:",       text: "Comprehensive solutions for cloud migration, development, and management." },
        { letter: "N", lead: "imble Infrastructure:", text: "Prioritising scalability, security, and performance." },
        { letter: "V", lead: "ersatile Platforms:",   text: "Expertise across leading platforms like AWS, Azure, and Google Cloud." },
        { letter: "I", lead: "nnovative Growth:",     text: "Ensuring your cloud environment is optimized for growth and efficiency." },
      ]}
    />
  );
}
