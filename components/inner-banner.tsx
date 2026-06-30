type Props = {
  title: string;
  subtitle?: string;
};

export function InnerBanner({ title, subtitle }: Props) {
  return (
    <section className="relative overflow-hidden bg-ink-gradient text-white">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(42,140,219,0.25),transparent_60%),radial-gradient(circle_at_70%_80%,rgba(60,114,252,0.2),transparent_60%)]" />
      <div className="absolute inset-0 opacity-30">
        {[...Array(8)].map((_, i) => (
          <span
            key={i}
            className="absolute block rounded-full bg-brand/30 animate-float-slow"
            style={{
              width:  `${20 + i * 12}px`,
              height: `${20 + i * 12}px`,
              left:  `${(i * 13) % 100}%`,
              top:   `${(i * 23) % 80}%`,
              animationDelay:    `${i * 0.7}s`,
              animationDuration: `${6 + i}s`,
            }}
          />
        ))}
      </div>
      <div className="container relative py-20 lg:py-28 text-center">
        <h1 className="text-white">{title}</h1>
        {subtitle && <p className="mt-4 text-lg text-ink-200 max-w-2xl mx-auto">{subtitle}</p>}
      </div>
    </section>
  );
}
