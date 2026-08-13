'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/careers/auth-context';
import { useInternDashboardOptional } from '@/components/intern/InternDashboardContext';
import type { JobPostingResponse } from '@/types';
import ApplyNowModal from './ApplyNowModal';

interface Props {
  postings: JobPostingResponse[];
  onApplied?: (jobPostingId: string, applicationId: string) => void;
  emptyLabel?: string;
}

const EMPLOYMENT_LABEL: Record<string, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
  FULL_TIME_INTERNSHIP: 'Full-Time / Internship',
};

export default function JobPostingsTable({
  postings,
  onApplied,
  emptyLabel = 'No openings are currently posted.',
}: Props) {
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [applyTarget, setApplyTarget] = useState<JobPostingResponse | null>(null);
  const [appliedOverrides, setAppliedOverrides] = useState<Record<string, boolean>>({});
  // W5 #9 — the Review step of ApplyNowModal shows "Applicant" +
  // "Email" fields that were blank because this call site (unlike the
  // JobCard call site) forgot to pass defaultName + defaultEmail. Wire
  // them from the same auth context JobCard uses so the intern's own
  // name + email surface before they submit.
  const { user } = useAuth();

  const rows = useMemo(() => postings ?? [], [postings]);

  function handleApplied(jobPostingId: string, applicationId: string) {
    setAppliedOverrides((current) => ({ ...current, [jobPostingId]: true }));
    onApplied?.(jobPostingId, applicationId);
  }

  if (rows.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center shadow-ds-sm">
        <p className="text-sm font-medium text-slate-600">{emptyLabel}</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-3">
        {rows.map((posting) => {
          const isExpanded = expandedId === posting.id || hoveredId === posting.id;
          return (
            <JobCard
              key={posting.id}
              posting={posting}
              applied={appliedOverrides[posting.id] ?? posting.applied ?? false}
              expanded={isExpanded}
              onHoverStart={() => setHoveredId(posting.id)}
              onHoverEnd={() => setHoveredId((current) => (current === posting.id ? null : current))}
              onToggle={() =>
                setExpandedId((current) => (current === posting.id ? null : posting.id))
              }
              onApply={() => setApplyTarget(posting)}
            />
          );
        })}
      </div>

      {applyTarget && (
        <ApplyNowModal
          posting={applyTarget}
          defaultName={user?.fullName}
          defaultEmail={user?.email}
          onClose={() => setApplyTarget(null)}
          onApplied={(applicationId) => {
            handleApplied(applyTarget.id, applicationId);
            setApplyTarget(null);
          }}
        />
      )}
    </>
  );
}

function JobCard({
  posting,
  applied,
  expanded,
  onHoverStart,
  onHoverEnd,
  onToggle,
  onApply,
}: {
  posting: JobPostingResponse;
  applied: boolean;
  expanded: boolean;
  onHoverStart: () => void;
  onHoverEnd: () => void;
  onToggle: () => void;
  onApply: () => void;
}) {
  const tags = jobTags(posting);
  const revealItems = expandedLines(posting);

  return (
    <motion.article
      layout
      onMouseEnter={onHoverStart}
      onMouseLeave={onHoverEnd}
      whileHover={{ y: -3 }}
      transition={{ duration: 0.3, ease: 'easeInOut' }}
      className="group overflow-hidden rounded-xl border border-slate-200 bg-white shadow-ds-sm transition-colors duration-300 ease-in-out hover:border-brand-200 hover:shadow-cardHover"
    >
      <div className="grid gap-0 md:grid-cols-[5.5rem_minmax(0,1fr)]">
        <DecorativeRail jobId={posting.jobId} />
        <div className="min-w-0 p-4 sm:p-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="min-w-0 flex-1">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <JobBadge>{EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}</JobBadge>
                {posting.location && <JobBadge>{posting.location}</JobBadge>}
                {applied && <JobBadge tone="success">Applied</JobBadge>}
              </div>

              <h3 className="text-lg font-semibold tracking-tight text-slate-950 sm:text-xl">
                {posting.title}
              </h3>

              {posting.jobId && (
                <p className="mt-0.5 font-mono text-[11px] font-medium uppercase tracking-wide text-slate-400">
                  {posting.jobId}
                </p>
              )}

              {posting.description && (
                <p className="mt-3 line-clamp-2 max-w-3xl text-sm leading-5 text-slate-600">
                  {plainPreview(posting.description)}
                </p>
              )}

              {tags.length > 0 && <JobTags tags={tags} />}
            </div>

            <JobActions
              posting={posting}
              applied={applied}
              onApply={onApply}
              onToggle={onToggle}
              expanded={expanded}
            />
          </div>

          <AnimatePresence initial={false}>
            {expanded && revealItems.length > 0 && (
              <JobCardExpanded items={revealItems} />
            )}
          </AnimatePresence>
        </div>
      </div>
    </motion.article>
  );
}

function DecorativeRail({ jobId }: { jobId?: string }) {
  return (
    <div className="relative hidden overflow-hidden border-r border-brand-100 bg-brand-50 md:block">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_1px_1px,theme(colors.brand.200)_1px,transparent_0)] bg-[length:14px_14px] opacity-70" />
      <div className="absolute inset-y-0 right-0 w-px bg-brand-200" />
      <div className="absolute left-4 top-5 h-12 w-px bg-brand-300" />
      <div className="absolute bottom-5 left-4 right-4 h-px bg-brand-200" />
      <div className="relative flex h-full min-h-[8.75rem] items-end p-4">
        <span className="break-all font-mono text-[9px] font-semibold uppercase tracking-widest text-brand-700">
          {jobId ?? 'ANVI'}
        </span>
      </div>
    </div>
  );
}

function JobBadge({
  children,
  tone = 'default',
}: {
  children: React.ReactNode;
  tone?: 'default' | 'success';
}) {
  const cls = tone === 'success'
    ? 'border-green-200 bg-green-50 text-green-700'
    : 'border-slate-200 bg-slate-50 text-slate-600';
  return (
    <span className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${cls}`}>
      {children}
    </span>
  );
}

function JobTags({ tags }: { tags: string[] }) {
  return (
    <div className="mt-3 flex flex-wrap gap-1.5">
      {tags.slice(0, 8).map((tag) => (
        <span
          key={tag}
          className="rounded-full border border-brand-100 bg-brand-50 px-2.5 py-0.5 text-xs font-medium text-brand-700"
        >
          {tag}
        </span>
      ))}
    </div>
  );
}

function JobActions({
  posting,
  applied,
  expanded,
  onApply,
  onToggle,
}: {
  posting: JobPostingResponse;
  applied: boolean;
  expanded: boolean;
  onApply: () => void;
  onToggle: () => void;
}) {
  const router = useRouter();
  const { user } = useAuth();
  const dashboard = useInternDashboardOptional();
  const isAuthed = !!user;
  const isApplicant = !!user?.roles?.includes('INTERN');
  const isPostApplicant = isAuthed && !isApplicant && (user?.roles?.length ?? 0) > 0;
  const readiness = dashboard?.data?.applyReadiness;
  const profileLocked = isApplicant && readiness != null && !readiness.complete;

  function apply() {
    if (applied || isPostApplicant) return;
    if (!isAuthed) {
      const redirect = `/careers/openings/${posting.slug}/apply`;
      router.push(`/careers/login?redirect=${encodeURIComponent(redirect)}`);
      return;
    }
    if (profileLocked) {
      const focus = readiness!.missing[0];
      const href = focus
        ? `/careers/intern/profile/complete?focus=${encodeURIComponent(focus)}`
        : '/careers/intern/profile/complete';
      router.push(href);
      return;
    }
    onApply();
  }

  // Wave-2 #5 — visible apply-lock. When the intern's profile is
  // incomplete, the button relabels + disables and a click-through hint
  // renders alongside; the click still routes to /profile/complete so the
  // intern can act without hunting for the fix.
  const applyLabel = applied
    ? 'Applied'
    : isPostApplicant
      ? 'Staff view'
      : profileLocked
        ? 'Complete profile to apply'
        : 'Apply Now';

  return (
    <div className="flex shrink-0 flex-wrap items-center gap-2 lg:justify-end">
      <button
        type="button"
        onClick={apply}
        disabled={applied || isPostApplicant}
        title={profileLocked ? 'Finish your profile to enable Apply.' : undefined}
        aria-describedby={profileLocked ? `apply-lock-${posting.id}` : undefined}
        className={
          'rounded-full px-4 py-1.5 text-sm font-semibold shadow-ds-sm transition-colors disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-500 '
          + (profileLocked && !applied && !isPostApplicant
            ? 'bg-amber-500 text-white hover:bg-amber-600'
            : 'bg-brand-700 text-white hover:bg-brand-800')
        }
      >
        {applyLabel}
      </button>
      {profileLocked && !applied && !isPostApplicant && (
        <span
          id={`apply-lock-${posting.id}`}
          className="text-xs text-amber-800"
        >
          Complete your profile to apply
        </span>
      )}
      <Link
        href={`/careers/openings/${posting.slug}`}
        className="rounded-full border border-slate-200 bg-white px-4 py-1.5 text-sm font-semibold text-slate-700 transition-colors hover:border-brand-200 hover:bg-brand-50 hover:text-brand-700"
      >
        View Details
      </Link>
      <button
        type="button"
        onClick={onToggle}
        className="rounded-full border border-transparent px-3 py-2 text-xs font-semibold text-slate-500 transition-colors hover:bg-slate-50 hover:text-slate-700 md:hidden"
        aria-expanded={expanded}
      >
        {expanded ? 'Less' : 'More'}
      </button>
    </div>
  );
}

function JobCardExpanded({ items }: { items: Array<{ label: string; lines: string[] }> }) {
  return (
    <motion.div
      key="expanded"
      initial={{ height: 0, opacity: 0, y: 8 }}
      animate={{ height: 'auto', opacity: 1, y: 0 }}
      exit={{ height: 0, opacity: 0, y: 8 }}
      transition={{ duration: 0.3, ease: 'easeInOut' }}
      className="overflow-hidden"
    >
      <div className="mt-4 grid gap-3 border-t border-slate-100 pt-4 lg:grid-cols-2">
        {items.map((item) => (
          <section key={item.label} className="rounded-lg border border-slate-100 bg-slate-50/70 p-3">
            <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
              {item.label}
            </p>
            <div className="mt-2 space-y-1.5 text-sm leading-5 text-slate-700">
              {item.lines.slice(0, 6).map((line) => (
                <p key={line}>{line}</p>
              ))}
            </div>
          </section>
        ))}
      </div>
    </motion.div>
  );
}

function expandedLines(posting: JobPostingResponse): Array<{ label: string; lines: string[] }> {
  return [
    { label: 'Description', lines: sectionLines(posting.description) },
    { label: 'Requirements', lines: sectionLines(posting.requirements) },
  ].filter((section) => section.lines.length > 0);
}

function jobTags(posting: JobPostingResponse): string[] {
  const lines = sectionLines(posting.requirements);
  const tags = lines
    .flatMap((line) => line.split(/,|\/|\band\b/gi))
    .map((part) => part.replace(/[().]/g, '').trim())
    .filter((part) => part.length >= 3 && part.length <= 28);
  return Array.from(new Set(tags)).slice(0, 8);
}

function plainPreview(text: string): string {
  return sectionLines(text).join(' ');
}

function sectionLines(text?: string | null): string[] {
  if (!text) return [];
  return text
    .split(/\r?\n|;|•/g)
    .map((line) => line.replace(/^[-*\s]+/, '').trim())
    .filter(Boolean)
    .filter((line) => !/^(about company|position summary|required skills|work authorization|qualification|qualifications|about the role|key responsibilities)$/i.test(line));
}
