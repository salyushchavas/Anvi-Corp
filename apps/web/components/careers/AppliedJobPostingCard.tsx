'use client';

import Link from 'next/link';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type { JobPostingResponse } from '@/types';

interface Props {
  posting: JobPostingResponse;
}

const EMPLOYMENT_LABEL: Record<string, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
};

function descriptionExcerpt(text: string | undefined, maxChars = 180): string {
  if (!text) return '';
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length <= maxChars ? flat : flat.slice(0, maxChars).trimEnd() + '…';
}

/**
 * "Applied" variant of {@link JobPostingCard}: the candidate has already
 * applied to this posting, so the card surfaces the application status badge
 * and links to /careers/intern/applications instead of dangling an
 * Apply CTA (which would create a duplicate application).
 */
export default function AppliedJobPostingCard({ posting }: Props) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {posting.entityName && (
          <span className="inline-block rounded-md bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">
            {posting.entityName}
          </span>
        )}
        <span className="inline-block rounded-md bg-accent/10 px-2 py-0.5 text-xs font-medium text-primary-700">
          {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
        </span>
        {posting.applicationStatus && (
          <ApplicationStatusBadge status={posting.applicationStatus} />
        )}
      </div>
      <h3 className="mb-2 text-lg font-semibold text-slate-900">{posting.title}</h3>
      <p className="mb-3 text-sm text-slate-500">{posting.location}</p>
      <p className="mb-4 line-clamp-2 text-sm text-slate-600">
        {descriptionExcerpt(posting.description)}
      </p>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <Link
          href="/careers/intern/applications"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          View application &rarr;
        </Link>
        <Link
          href={`/careers/openings/${posting.slug}`}
          className="text-slate-500 hover:text-slate-700 hover:underline"
        >
          Posting details
        </Link>
      </div>
    </div>
  );
}
