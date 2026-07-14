'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  Briefcase,
  Building2,
  CheckCircle2,
  Clock,
  MapPin,
} from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import { formatRelative } from '@/lib/format-date';
import type { JobPostingResponse } from '@/types';
import { Button, Card, StatusPill } from '@/components/ui';
import ApplyNowModal from './ApplyNowModal';

interface Props {
  posting: JobPostingResponse;
  onApplied?: (jobPostingId: string, applicationId: string) => void;
}

const EMPLOYMENT_LABEL: Record<string, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
};

const MAX_BULLET_LINES = 4;

function splitToBullets(text?: string | null): string[] {
  if (!text) return [];
  return text
    .split(/\r?\n|;|•/g)
    .map((s) => s.replace(/^[-*•\s]+/, '').trim())
    .filter(Boolean);
}

function partitionRequirements(req?: string | null) {
  const all = splitToBullets(req);
  const qualifications: string[] = [];
  const requirements: string[] = [];
  for (const line of all) {
    if (/^qualif/i.test(line) || /^education/i.test(line) || /^degree/i.test(line)) {
      qualifications.push(line);
    } else {
      requirements.push(line);
    }
  }
  return { requirements, qualifications };
}

export default function JobCard({ posting, onApplied }: Props) {
  const router = useRouter();
  const { user } = useAuth();
  const [showApply, setShowApply] = useState(false);
  const [showAllReq, setShowAllReq] = useState(false);
  const [showAllQual, setShowAllQual] = useState(false);
  const [applied, setApplied] = useState<boolean>(posting.applied ?? false);

  const { requirements, qualifications } = useMemo(
    () => partitionRequirements(posting.requirements),
    [posting.requirements],
  );

  const isAuthed = !!user;
  const isApplicant = !!user?.roles?.includes('INTERN');
  const isPostApplicant =
    isAuthed && !isApplicant && (user?.roles?.length ?? 0) > 0;

  const reqShown = showAllReq ? requirements : requirements.slice(0, MAX_BULLET_LINES);
  const qualShown = showAllQual ? qualifications : qualifications.slice(0, MAX_BULLET_LINES);

  function handleApplyClick() {
    if (!isAuthed) {
      const redirect = `/careers/openings/${posting.slug}/apply`;
      router.push(`/careers/login?redirect=${encodeURIComponent(redirect)}`);
      return;
    }
    if (!isApplicant || applied) return;
    setShowApply(true);
  }

  return (
    <>
      <Card variant="interactive" className="flex h-full flex-col gap-4">
        <header>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            {posting.entityName && (
              <span className="inline-flex items-center gap-1 rounded-md bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-700">
                <Building2 className="h-3 w-3" strokeWidth={2} />
                {posting.entityName}
              </span>
            )}
            <span className="inline-flex items-center gap-1 rounded-md bg-brand-50 px-2 py-0.5 text-[11px] font-medium text-brand-700 ring-1 ring-brand-200">
              <Briefcase className="h-3 w-3" strokeWidth={2} />
              {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
            </span>
            {posting.publishedAt && (
              <span className="inline-flex items-center gap-1 text-[11px] text-slate-500">
                <Clock className="h-3 w-3" strokeWidth={2} />
                Posted {formatRelative(posting.publishedAt)}
              </span>
            )}
          </div>
          <h3 className="text-base font-semibold text-slate-900">{posting.title}</h3>
          {posting.location && (
            <p className="mt-1 flex items-center gap-1 text-xs text-slate-500">
              <MapPin className="h-3 w-3" strokeWidth={2} />
              {posting.location}
            </p>
          )}
        </header>

        {posting.description && (
          <p className="line-clamp-3 text-sm text-slate-600">{posting.description}</p>
        )}

        {requirements.length > 0 && (
          <section>
            <p className="mb-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              Requirements
            </p>
            <ul className="space-y-1 text-sm text-slate-700">
              {reqShown.map((r, i) => (
                <li key={i} className="flex gap-1.5">
                  <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-brand-500" />
                  <span>{r}</span>
                </li>
              ))}
            </ul>
            {requirements.length > MAX_BULLET_LINES && (
              <button
                type="button"
                onClick={() => setShowAllReq((v) => !v)}
                className="mt-1 text-xs font-medium text-brand-700 hover:underline"
              >
                {showAllReq ? 'Show less' : `Show ${requirements.length - MAX_BULLET_LINES} more`}
              </button>
            )}
          </section>
        )}

        {qualifications.length > 0 && (
          <section>
            <p className="mb-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              Qualifications
            </p>
            <ul className="space-y-1 text-sm text-slate-700">
              {qualShown.map((q, i) => (
                <li key={i} className="flex gap-1.5">
                  <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-brand-500" />
                  <span>{q}</span>
                </li>
              ))}
            </ul>
            {qualifications.length > MAX_BULLET_LINES && (
              <button
                type="button"
                onClick={() => setShowAllQual((v) => !v)}
                className="mt-1 text-xs font-medium text-brand-700 hover:underline"
              >
                {showAllQual ? 'Show less' : `Show ${qualifications.length - MAX_BULLET_LINES} more`}
              </button>
            )}
          </section>
        )}

        <footer className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-4">
          <Link
            href={`/careers/openings/${posting.slug}`}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            View details →
          </Link>
          {isPostApplicant ? (
            <Button variant="secondary" size="sm" disabled>
              Not eligible
            </Button>
          ) : applied ? (
            <StatusPill
              status="APPROVED"
              label="Applied"
              icon={CheckCircle2}
              size="md"
            />
          ) : (
            <Button size="sm" onClick={handleApplyClick}>
              Apply now
            </Button>
          )}
        </footer>
      </Card>

      {showApply && (
        <ApplyNowModal
          posting={posting}
          defaultName={user?.fullName}
          defaultEmail={user?.email}
          onClose={() => setShowApply(false)}
          onApplied={(applicationId) => {
            setApplied(true);
            setShowApply(false);
            if (onApplied) onApplied(posting.id, applicationId);
          }}
        />
      )}
    </>
  );
}
