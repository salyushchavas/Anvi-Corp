import {
  Briefcase,
  Building2,
  FileText,
  GraduationCap,
  ListChecks,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react';

/**
 * The Jobs Admin form packs several separate boxes (About Company, Position
 * Summary, Required Skills, Work Authorization, Qualification) into the two stored
 * text columns using the box headings as delimiters. This module parses them back
 * out and renders each as its own titled, iconified section — so headings never
 * leak into the body copy and every box reads as its own block. Shared by the
 * public posting page and the admin form preview so both are pixel-identical.
 */

const SECTION_HEADINGS = [
  'About Company',
  'Position Summary',
  'Required Skills',
  'Work Authorization',
  'Qualification',
  'Qualifications',
  'About the Role',
  'Key Responsibilities',
];

const SECTION_ICON: Record<string, LucideIcon> = {
  'about company': Building2,
  'position summary': Briefcase,
  'required skills': ListChecks,
  'work authorization': ShieldCheck,
  qualification: GraduationCap,
  qualifications: GraduationCap,
  'about the role': FileText,
  'key responsibilities': ListChecks,
};

export interface PostingSection {
  title: string | null;
  items: string[];
  bulleted: boolean;
}

export function parsePostingSections(text?: string | null): PostingSection[] {
  if (!text) return [];
  const byLower = new Map(SECTION_HEADINGS.map((h) => [h.toLowerCase(), h]));
  const sections: PostingSection[] = [];
  let current: PostingSection | null = null;
  for (const raw of text.split(/\r?\n/)) {
    const trimmed = raw.trim();
    if (!trimmed) continue;
    const heading = byLower.get(trimmed.toLowerCase());
    if (heading) {
      current = { title: heading, items: [], bulleted: false };
      sections.push(current);
      continue;
    }
    const bulleted = /^\s*[-•*]\s+/.test(raw);
    const clean = raw.replace(/^\s*[-•*]\s*/, '').trim();
    if (!clean) continue;
    if (!current) {
      current = { title: null, items: [], bulleted: false };
      sections.push(current);
    }
    if (bulleted) current.bulleted = true;
    current.items.push(clean);
  }
  return sections.filter((s) => s.items.length > 0);
}

/**
 * Sections that read as a list of responsibilities/points always render as
 * bullets, even when the admin typed them as plain lines (the form stores
 * Position Summary without bullet markers). About Company / Qualification stay
 * as prose paragraphs.
 */
const BULLET_HEADINGS = new Set([
  'position summary',
  'required skills',
  'work authorization',
  'key responsibilities',
]);

function isBulleted(section: PostingSection): boolean {
  if (section.bulleted) return true;
  return section.title ? BULLET_HEADINGS.has(section.title.toLowerCase()) : false;
}

function SectionBody({ section }: { section: PostingSection }) {
  if (isBulleted(section)) {
    return (
      <ul className="space-y-1.5">
        {section.items.map((it, i) => (
          <li key={i} className="flex gap-2 text-sm leading-relaxed text-slate-700">
            <span aria-hidden className="mt-2 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-accent/70" />
            <span>{it}</span>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <div className="space-y-2 text-sm leading-relaxed text-slate-700">
      {section.items.map((it, i) => (
        <p key={i}>{it}</p>
      ))}
    </div>
  );
}

/**
 * Render a titled group (e.g. "About the role") whose body is a list of parsed
 * sections. `compact` drops the outer card chrome for embedding inside a preview.
 */
export function PostingSectionGroup({
  heading,
  sections,
  compact = false,
}: {
  heading: string;
  sections: PostingSection[];
  compact?: boolean;
}) {
  if (sections.length === 0) return null;
  return (
    <section
      className={
        compact
          ? 'rounded-lg border border-slate-200 bg-white p-4'
          : 'rounded-2xl border border-slate-200 bg-white p-6 shadow-sm'
      }
    >
      <h2 className={`font-semibold text-slate-900 ${compact ? 'mb-3 text-sm' : 'mb-5 text-lg'}`}>{heading}</h2>
      <div className={compact ? 'space-y-4' : 'space-y-6'}>
        {sections.map((s, i) => {
          const Icon = s.title ? SECTION_ICON[s.title.toLowerCase()] ?? FileText : null;
          return (
            <div key={i} className="border-l-2 border-accent/30 pl-4">
              {s.title && (
                <div className="mb-2 flex items-center gap-2">
                  {Icon && (
                    <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-md bg-accent/10 text-primary-700">
                      <Icon className="h-3.5 w-3.5" />
                    </span>
                  )}
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-800">{s.title}</h3>
                </div>
              )}
              <SectionBody section={s} />
            </div>
          );
        })}
      </div>
    </section>
  );
}

/** Convenience: the full posting body — the "About the role" + "Requirements" groups. */
export function PostingBody({
  description,
  requirements,
  compact = false,
}: {
  description?: string | null;
  requirements?: string | null;
  compact?: boolean;
}) {
  const about = parsePostingSections(description);
  const reqs = parsePostingSections(requirements);
  if (about.length === 0 && reqs.length === 0) {
    return <p className="text-sm text-slate-400">No details provided yet.</p>;
  }
  return (
    <div className={compact ? 'space-y-4' : 'space-y-6'}>
      <PostingSectionGroup heading="About the role" sections={about} compact={compact} />
      <PostingSectionGroup heading="Requirements" sections={reqs} compact={compact} />
    </div>
  );
}
