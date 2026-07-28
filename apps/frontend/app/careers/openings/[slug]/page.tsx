import type { Metadata } from 'next';
import Link from 'next/link';
import { Briefcase, Building2, Hash, MapPin, type LucideIcon } from 'lucide-react';
import { fetchJobPosting } from '@/lib/careers/server-api';
import AdaptiveCareersLayout from '@/components/careers/AdaptiveCareersLayout';
import ApplyCtaCard from '@/components/careers/ApplyCtaCard';
import { PostingSectionGroup, parsePostingSections } from '@/components/careers/PostingSections';

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

function Fact({
  icon: Icon,
  label,
  value,
  mono = false,
}: {
  icon: LucideIcon;
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-start gap-3">
      <span className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-accent/10 text-primary-700">
        <Icon className="h-4 w-4" />
      </span>
      <div className="min-w-0">
        <dt className="text-[11px] font-medium uppercase tracking-wide text-slate-400">{label}</dt>
        <dd className={`text-slate-800 ${mono ? 'break-all font-mono text-[13px]' : 'font-medium'}`}>{value}</dd>
      </div>
    </div>
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

  const aboutSections = parsePostingSections(posting.description);
  const requirementSections = parsePostingSections(posting.requirements);
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

        <header className="mb-6 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <div className="h-1.5 w-full bg-gradient-to-r from-accent to-primary-600" />
          <div className="p-6">
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
            <h1 className="text-3xl font-semibold tracking-tight text-slate-900">{posting.title}</h1>
          </div>
        </header>

        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            <PostingSectionGroup heading="About the role" sections={aboutSections} />
            <PostingSectionGroup heading="Requirements" sections={requirementSections} />
          </div>

          <aside className="space-y-4 lg:col-span-1">
            <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
              <h2 className="mb-4 text-sm font-semibold text-slate-900">At a glance</h2>
              <dl className="space-y-4">
                <Fact icon={Briefcase} label="Employment" value={employment} />
                {posting.location && <Fact icon={MapPin} label="Location" value={posting.location} />}
                {posting.entityName && <Fact icon={Building2} label="Company" value={posting.entityName} />}
                {posting.jobId && <Fact icon={Hash} label="Job ID" value={posting.jobId} mono />}
              </dl>
            </div>

            {/* GAP A4 — auth-aware client CTA. Page itself stays public/SSR. */}
            <ApplyCtaCard slug={posting.slug} />
          </aside>
        </div>
      </article>
    </AdaptiveCareersLayout>
  );
}
