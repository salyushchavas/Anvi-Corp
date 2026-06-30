type Props = {
  eyebrow?: string;
  title: React.ReactNode;
  description?: string;
  align?: "left" | "center";
  className?: string;
};

export function SectionHeading({ eyebrow, title, description, align = "left", className = "" }: Props) {
  const alignment = align === "center" ? "text-center mx-auto" : "";
  return (
    <div className={`${alignment} max-w-3xl ${className}`}>
      {eyebrow && (
        <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
          {eyebrow}
        </span>
      )}
      <h2 className="mb-4">{title}</h2>
      {description && <p className="text-ink-400 text-lg leading-relaxed">{description}</p>}
    </div>
  );
}
