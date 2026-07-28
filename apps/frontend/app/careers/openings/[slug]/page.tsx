import type { Metadata } from 'next';
import Link from 'next/link';
import { fetchJobPosting } from '@/lib/careers/server-api';
import AdaptiveCareersLayout from '@/components/careers/AdaptiveCareersLayout';
import ApplyCtaCard from '@/components/careers/ApplyCtaCard';

export const dynamic = 'force-dynamic';

const EMPLOYMENT_LABEL: Record<string, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
  FULL_TIME_INTERNSHIP: 'Full-Time / Internship',
};

interface Props {
  params: { slug: string };
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  try {
    const posting = await fetchJobPosting(params.slug);
    if (!posting) {
      return { title: 'Position not found — Anvi Careers' };
    }
    return {
      title: `${posting.title} — Anvi Careers`,
      description: posting.description?.slice(0, 200),
    };
  } catch {
    return { title: 'Anvi Careers' };
  }
}

/**
 * The Jobs Admin form stores several separate boxes inside the two text blocks
 * (`description`, `requirements`) using the box headings as delimiters. We parse
 * those back out here so each renders as its own titled section — instead of the
 * heading label leaking into the body copy and every box being clubbed together.
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

interface PostingSection {
  title: string | null;
  items: string[];
  bulleted: boolean;
}

function parseSections(text?: string): PostingSection[] {
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

function SectionBody({ section }: { section: PostingSection }) {
  if (section.bulleted) {
    return (
      <ul className="list-disc space-y-1 pl-5 text-sm leading-relaxed text-slate-700">
        {section.items.map((it, i) => (
          <li key={i}>{it}</li>
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

function SectionGroup({ heading, sections }: { heading: string; sections: PostingSection[] }) {
  if (sections.length === 0) return null;
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6">
      <h2 className="mb-4 text-lg font-semibold text-slate-900">{heading}</h2>
      <div className="space-y-5">
        {sections.map((s, i) => (
          <div key={i}>
            {s.title && (
              <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-primary-700">
                {s.title}
              </h3>
            )}
            <SectionBody section={s} />
          </div>
        ))}
      </div>
    </section>
  );
}

export default async function JobPostingDetailPage({ params }: Props) {
  let posting;
  let loadError = false;
  try {
    posting = await fetchJobPosting(params.slug);
  } catch {
    loadError = true;
  }

  if (loadError) {
    return (
      <AdaptiveCareersLayout title="Open Internships">
        <section className="rounded-lg border border-red-200 bg-red-50 p-6 text-sm text-red-700">
          <p className="font-medium">Couldn&apos;t load this posting.</p>
          <p className="mt-1">
            The backend may be starting up.{' '}
            <Link href="/careers/openings" className="underline">
              Back to openings
            </Link>
            .
          </p>
        </section>
      </AdaptiveCareersLayout>
    );
  }

  if (!posting) {
    return (
      <AdaptiveCareersLayout title="Open Internships">
        <section className="rounded-lg border border-slate-200 bg-white p-8 text-center">
          <h1 className="mb-2 text-xl font-semibold text-slate-900">
            This position is no longer open
          </h1>
          <p className="mb-6 text-sm text-slate-600">
            It may have been filled or paused. Check the openings list for current roles.
          </p>
          <Link
            href="/careers/openings"
            className="inline-block rounded-full bg-accent hover:bg-accent-dark px-5 py-2.5 text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg"
          >
            Back to all openings
          </Link>
        </section>
      </AdaptiveCareersLayout>
    );
  }

  const aboutSections = parseSections(posting.description);
  const requirementSections = parseSections(posting.requirements);
  const employment = EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType;

  return (
    <AdaptiveCareersLayout title="Open Internships">
      <article>
        <div className="mb-3">
          <Link
            href="/careers/openings"
            className="text-sm font-medium text-primary-700 hover:text-primary-800 hover:underline"
          >
            &larr; All openings
          </Link>
        </div>

      <header className="mb-6 rounded-lg border border-slate-200 bg-white p-6">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          {posting.entityName && (
            <span className="inline-block rounded-md bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700">
              {posting.entityName}
            </span>
          )}
          <span className="inline-block rounded-md bg-accent/10 px-2.5 py-1 text-xs font-medium text-primary-700">
            {employment}
          </span>
          <span className="inline-block rounded-md bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700">
            {posting.location}
          </span>
        </div>
        <h1 className="text-3xl font-semibold text-slate-900">{posting.title}</h1>
      </header>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2 space-y-6">
          <SectionGroup heading="About the role" sections={aboutSections} />
          <SectionGroup heading="Requirements" sections={requirementSections} />
        </div>

        <aside className="lg:col-span-1">
          {/* GAP A4 — auth-aware client CTA. Page itself stays public/SSR. */}
          <ApplyCtaCard slug={posting.slug} />
        </aside>
      </div>
    </article>
    </AdaptiveCareersLayout>
  );
}
