import type { Metadata } from "next";
import { ServicePageTemplate } from "@/components/service-page-template";

export const metadata: Metadata = {
  title: "IT Consulting",
  description:
    "ANVI IT consulting: accurate insights, tailored strategies, vast cross-industry experience, and process improvements to drive your business forward.",
};

export default function ItConsultingPage() {
  return (
    <ServicePageTemplate
      banner="IT Consulting"
      eyebrow="IT Consulting"
      heading="Achieve IT excellence with ANVI Consulting Services"
      intro="Navigating the complexities of IT can be challenging, but Anvi Corp is here to guide you with expert IT consulting services."
      outro="Drive your business forward with ANVI IT consulting."
      image="/services/it-consulting.png"
      items={[
        { letter: "A", lead: "ccurate Insights:",     text: "Expertise and strategic insight to overcome technological challenges." },
        { letter: "N", lead: "eed-Based Strategies:", text: "Tailored solutions that address your specific business needs." },
        { letter: "V", lead: "ast Experience:",       text: "Consultants with a wealth of experience across various industries." },
        { letter: "I", lead: "mproved Processes:",    text: "Optimizing IT infrastructure, implementing new technologies, and enhancing processes." },
      ]}
    />
  );
}
