'use client';

import { useState, useMemo } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Briefcase, CheckCircle2, Lock, MapPin } from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import { useInternDashboardOptional } from '@/components/intern/InternDashboardContext';
import { formatRelative } from '@/lib/format-date';
import type { JobPostingResponse } from '@/types';
import Modal from '@/components/ui/Modal';
import ApplyNowModal from './ApplyNowModal';

interface Props {
  postings: JobPostingResponse[];
  /** Notified after a successful apply so the parent can flip the local
   *  `applied` flag without a network round-trip. */
  onApplied?: (jobPostingId: string, applicationId: string) => void;
  /** Empty-state label override (e.g. "No internships are currently open"). */
  emptyLabel?: string;
}

const EMPLOYMENT_LABEL: Record<string, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
};

/**
 * Read-only table renderer for the candidate-facing job-postings surface.
 * Replaces the previous card grid. Long-form fields (description,
 * full requirements/qualifications) are NEVER rendered inline — only
 * via the {@link KnowMoreDrawer} that opens from the title cell.
 *
 * <p>The Apply column reuses the existing {@link ApplyNowModal} flow:
 *   <ul>
 *     <li>Unauthenticated → bounces to {@code /careers/login?redirect=…}.</li>
 *     <li>Authenticated INTERN, not applied → opens the apply modal.</li>
 *     <li>Already applied → shows a non-interactive "Applied" pill.</li>
 *     <li>Authenticated non-intern (ERM / Trainer / etc.) → disabled
 *         with a tooltip; preserves the JobCard's behaviour.</li>
 *   </ul>
 */
export default function JobPostingsTable({
  postings, onApplied, emptyLabel = 'No openings are currently posted.',
}: Props) {
  const [knowMore, setKnowMore] = useState<JobPostingResponse | null>(null);
  const [applyTarget, setApplyTarget] = useState<JobPostingResponse | null>(null);
  // Track local "applied" overrides so the row flips immediately on success
  // without waiting for a parent re-fetch.
  const [appliedOverrides, setAppliedOverrides] = useState<Record<string, boolean>>({});

  const rows = useMemo(() => postings ?? [], [postings]);

  function handleApplied(jobPostingId: string, applicationId: string) {
    setAppliedOverrides((m) => ({ ...m, [jobPostingId]: true }));
    onApplied?.(jobPostingId, applicationId);
  }

  if (rows.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
        <p className="text-sm text-slate-600">{emptyLabel}</p>
      </div>
    );
  }

  return (
    <>
      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-600">
            <tr>
              <th className="px-3 py-2 text-left">Job ID</th>
              <th className="px-3 py-2 text-left">Role</th>
              <th className="px-3 py-2 text-left">Type</th>
              <th className="px-3 py-2 text-left">Qualification</th>
              <th className="px-3 py-2 text-left">Location</th>
              <th className="px-3 py-2 text-left">Posted</th>
              <th className="px-3 py-2 text-right">Apply</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((p) => (
              <Row
                key={p.id}
                posting={p}
                applied={appliedOverrides[p.id] ?? p.applied ?? false}
                onKnowMore={() => setKnowMore(p)}
                onApply={() => setApplyTarget(p)}
              />
            ))}
          </tbody>
        </table>
      </div>

      {knowMore && (
        <KnowMoreDrawer
          posting={knowMore}
          applied={appliedOverrides[knowMore.id] ?? knowMore.applied ?? false}
          onClose={() => setKnowMore(null)}
          onApply={() => {
            const target = knowMore;
            setKnowMore(null);
            setApplyTarget(target);
          }}
        />
      )}

      {applyTarget && (
        <ApplyNowModal
          posting={applyTarget}
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

function Row({
  posting, applied, onKnowMore, onApply,
}: {
  posting: JobPostingResponse;
  applied: boolean;
  onKnowMore: () => void;
  onApply: () => void;
}) {
  // Pull a short qualification preview off `requirements` so the column
  // is scannable. The full text lives in the Know More drawer.
  const qualPreview = qualificationPreview(posting.requirements);

  return (
    <tr className="align-top hover:bg-slate-50">
      <td className="px-3 py-2 font-mono text-[11px] text-slate-700">
        {posting.jobId ?? '—'}
      </td>
      <td className="px-3 py-2">
        <p className="font-medium text-slate-900">{posting.title}</p>
        <button
          type="button"
          onClick={onKnowMore}
          className="text-[11px] font-medium text-brand-700 hover:underline"
        >
          Know more →
        </button>
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 font-semibold text-slate-700">
          <Briefcase className="h-3 w-3" />
          {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
        </span>
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        <p className="line-clamp-2 max-w-[16rem]" title={qualPreview ?? ''}>
          {qualPreview ?? <span className="text-slate-400">—</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        {posting.location ? (
          <span className="inline-flex items-center gap-1">
            <MapPin className="h-3 w-3 text-slate-400" />
            {posting.location}
          </span>
        ) : (
          <span className="text-slate-400">—</span>
        )}
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-500">
        {posting.publishedAt
          ? formatRelative(posting.publishedAt)
          : posting.createdAt
            ? formatRelative(posting.createdAt)
            : '—'}
      </td>
      <td className="px-3 py-2 text-right">
        <ApplyCell posting={posting} applied={applied} onApply={onApply} />
      </td>
    </tr>
  );
}

function ApplyCell({
  posting, applied, onApply,
}: {
  posting: JobPostingResponse;
  applied: boolean;
  onApply: () => void;
}) {
  const router = useRouter();
  const { user } = useAuth();
  const dashboard = useInternDashboardOptional();
  const isAuthed = !!user;
  const isApplicant = !!user?.roles?.includes('INTERN');
  const isPostApplicant =
    isAuthed && !isApplicant && (user?.roles?.length ?? 0) > 0;

  // Approach 1 — only locks for an INTERN inside the dashboard layout
  // (where the provider is present). On the public openings page the
  // dashboard hook returns null and the server-side gate remains the sole
  // authority.
  const readiness = dashboard?.data?.applyReadiness;
  const profileLocked = isApplicant && readiness != null && !readiness.complete;

  if (applied) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold text-green-700">
        <CheckCircle2 className="h-3 w-3" />
        Applied
      </span>
    );
  }

  if (isPostApplicant) {
    return (
      <span className="inline-flex items-center text-[11px] text-slate-400"
        title="Only applicants (INTERN role) can apply">
        Staff view
      </span>
    );
  }

  if (profileLocked) {
    const focus = readiness!.missing[0];
    const href = focus
      ? `/careers/intern/profile/complete?focus=${encodeURIComponent(focus)}`
      : '/careers/intern/profile/complete';
    return (
      <Link
        href={href}
        title="Complete your profile to apply"
        className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-3 py-1 text-xs font-semibold text-brand-700 hover:bg-brand-100"
      >
        <Lock className="h-3 w-3" strokeWidth={2.5} />
        Apply
      </Link>
    );
  }

  function go() {
    if (!isAuthed) {
      const redirect = `/careers/openings/${posting.slug}/apply`;
      router.push(`/careers/login?redirect=${encodeURIComponent(redirect)}`);
      return;
    }
    onApply();
  }

  return (
    <button
      type="button"
      onClick={go}
      className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-xs font-semibold text-white hover:bg-brand-800"
    >
      Apply
    </button>
  );
}

// ── Know More drawer ─────────────────────────────────────────────────────

function KnowMoreDrawer({
  posting, applied, onClose, onApply,
}: {
  posting: JobPostingResponse;
  applied: boolean;
  onClose: () => void;
  onApply: () => void;
}) {
  const { user } = useAuth();
  const router = useRouter();
  const isAuthed = !!user;
  const isApplicant = !!user?.roles?.includes('INTERN');
  const isPostApplicant =
    isAuthed && !isApplicant && (user?.roles?.length ?? 0) > 0;

  function handleApplyClick() {
    if (!isAuthed) {
      const redirect = `/careers/openings/${posting.slug}/apply`;
      router.push(`/careers/login?redirect=${encodeURIComponent(redirect)}`);
      return;
    }
    if (!isApplicant) return;
    onApply();
  }

  const footer = (
    <div className="flex w-full items-center justify-between">
      <Link
        href={`/careers/openings/${posting.slug}`}
        className="text-xs font-medium text-slate-600 hover:text-slate-800 hover:underline"
        onClick={onClose}
      >
        Open full posting page
      </Link>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-slate-200 bg-white px-4 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          Close
        </button>
        {applied ? (
          <span className="inline-flex items-center gap-1 rounded-md bg-green-100 px-3 py-1.5 text-xs font-semibold text-green-700">
            <CheckCircle2 className="h-3 w-3" />
            Already applied
          </span>
        ) : isPostApplicant ? (
          <span className="inline-flex items-center rounded-md bg-slate-100 px-3 py-1.5 text-xs font-medium text-slate-500"
            title="Only applicants (INTERN role) can apply">
            Staff view
          </span>
        ) : (
          <button
            type="button"
            onClick={handleApplyClick}
            className="rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            Apply now
          </button>
        )}
      </div>
    </div>
  );

  const description = (
    <>
      <span className="font-mono text-[11px] text-slate-500">
        {posting.jobId ?? '—'}
      </span>
      {posting.entityName && (
        <span className="ml-2 text-[11px] text-slate-500">
          · {posting.entityName}
        </span>
      )}
    </>
  );

  return (
    <Modal
      open
      onOpenChange={(o) => { if (!o) onClose(); }}
      title={posting.title}
      description={description}
      docked="right"
      footer={footer}
    >
      <div className="space-y-4 text-sm">
        <Section label="Type">
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
            <Briefcase className="h-3 w-3" />
            {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
          </span>
        </Section>
        {posting.location && (
          <Section label="Location">
            <span className="inline-flex items-center gap-1 text-slate-700">
              <MapPin className="h-3.5 w-3.5 text-slate-400" />
              {posting.location}
            </span>
          </Section>
        )}
        {posting.publishedAt && (
          <Section label="Posted">
            <span className="text-slate-700">{formatRelative(posting.publishedAt)}</span>
          </Section>
        )}
        {posting.description && (
          <Section label="About the role">
            <p className="whitespace-pre-wrap leading-relaxed text-slate-700">
              {posting.description}
            </p>
          </Section>
        )}
        {posting.requirements && (
          <Section label="Qualifications & requirements">
            <p className="whitespace-pre-wrap leading-relaxed text-slate-700">
              {posting.requirements}
            </p>
          </Section>
        )}
      </div>
    </Modal>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </p>
      <div className="mt-1">{children}</div>
    </div>
  );
}

/** Pull the first qualification-shaped line from the free-text
 *  `requirements` field so the column has something scannable. The full
 *  text remains in the drawer. Returns null when nothing matches. */
function qualificationPreview(text?: string | null): string | null {
  if (!text) return null;
  const lines = text.split(/\r?\n|;|•/g)
    .map((s) => s.replace(/^[-*•\s]+/, '').trim())
    .filter(Boolean);
  for (const line of lines) {
    if (/^qualif/i.test(line) || /^education/i.test(line) || /^degree/i.test(line)) {
      return line;
    }
  }
  // Fallback: just show the first bullet so the column isn't always empty.
  return lines[0] ?? null;
}
