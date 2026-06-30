import { Check } from "lucide-react";

export type CheckItem = {
  /** The single capital letter highlighted in brand color (the "A" in "Agile") */
  letter?: string;
  /** Bold lead-in word/phrase before the colon */
  lead?: string;
  /** The descriptive copy */
  text: string;
};

export function CheckList({ items }: { items: CheckItem[] }) {
  return (
    <ul className="space-y-4">
      {items.map((item, i) => (
        <li key={i} className="flex gap-3">
          <span className="mt-1 inline-flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-brand-50 text-brand">
            <Check className="h-3.5 w-3.5" strokeWidth={3} />
          </span>
          <p className="text-ink-500 leading-relaxed">
            {item.letter && <span className="anvi-letter">{item.letter}</span>}
            {item.lead && <span className="font-semibold text-ink-800">{item.lead}</span>}
            {(item.letter || item.lead) ? " " : ""}
            {item.text}
          </p>
        </li>
      ))}
    </ul>
  );
}
