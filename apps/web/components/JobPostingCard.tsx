import Link from 'next/link';
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

export default function JobPostingCard({ posting }: Props) {
  return (
    <Link
      href={`/careers/openings/${posting.slug}`}
      className="group block rounded-lg border border-slate-200 bg-white p-6 transition hover:border-accent/40 hover:shadow-md"
    >
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {posting.entityName && (
          <span className="inline-block rounded-md bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">
            {posting.entityName}
          </span>
        )}
        <span className="inline-block rounded-md bg-accent/10 px-2 py-0.5 text-xs font-medium text-primary-700">
          {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
        </span>
      </div>
      <h3 className="mb-2 text-lg font-semibold text-slate-900 group-hover:text-primary-800">
        {posting.title}
      </h3>
      <p className="mb-3 text-sm text-slate-500">{posting.location}</p>
      <p className="mb-4 line-clamp-2 text-sm text-slate-600">
        {descriptionExcerpt(posting.description)}
      </p>
      <span className="inline-flex items-center text-sm font-medium text-primary-700 group-hover:text-primary-800 group-hover:underline">
        View details &rarr;
      </span>
    </Link>
  );
}
