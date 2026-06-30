import { ButtonLink } from "@/components/button";

export default function Home() {
  return (
    <section className="container py-24 text-center">
      <h1 className="mb-4">Anvi Corp USA</h1>
      <p className="mb-8 text-lg text-ink-400">Design system in place. Real homepage lands in the next commit.</p>
      <ButtonLink href="/contact">Contact Us</ButtonLink>
    </section>
  );
}
