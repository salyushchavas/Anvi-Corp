import type { Metadata } from "next";
import { ServicePageTemplate } from "@/components/service-page-template";

export const metadata: Metadata = {
  title: "Software Development",
  description:
    "ANVI software development services: custom, end-to-end software built on advanced technologies for startups and large enterprises.",
};

export default function SoftwareDevelopmentPage() {
  return (
    <ServicePageTemplate
      banner="Software Development"
      eyebrow="Software Development"
      heading="Advancing your digital transformation with ANVI Software Development"
      intro="At Anvi Corp, we recognize the critical role software plays in driving business success. Our comprehensive ANVI software development services are meticulously designed to meet the unique needs of your organisation — whether you're a startup or a large enterprise."
      image="/services/software-development.png"
      items={[
        { letter: "A", lead: "dvanced Technologies:",   text: "Leveraging cutting-edge technologies and industry best practices." },
        { letter: "N", lead: "eed-Based Solutions:",    text: "Custom software solutions tailored to enhance operational efficiency and drive innovation." },
        { letter: "V", lead: "ision to Reality:",       text: "End-to-end development services from concept to deployment." },
        { letter: "I", lead: "nnovative Outcomes:",     text: "Ensuring a seamless experience and exceptional results." },
      ]}
    />
  );
}
