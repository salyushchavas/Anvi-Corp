import { FloatingCircles } from "./floating-circles";

type Props = {
  title: string;
  subtitle?: string;
};

export function InnerBanner({ title, subtitle }: Props) {
  return (
    <section className="relative overflow-hidden bg-ink-gradient text-white">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(42,140,219,0.30),transparent_60%),radial-gradient(circle_at_70%_80%,rgba(60,114,252,0.25),transparent_60%)]" />
      <FloatingCircles />
      <div className="container relative py-20 lg:py-28 text-center">
        <h1 className="text-white">{title}</h1>
        {subtitle && <p className="mt-4 text-lg text-ink-200 max-w-2xl mx-auto">{subtitle}</p>}
      </div>
    </section>
  );
}
