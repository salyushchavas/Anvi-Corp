import type { ApplicationStatus } from '@/types';

interface Props {
  status: ApplicationStatus | string;
}

const COLOR_MAP: Record<string, string> = {
  APPLIED: 'bg-slate-100 text-slate-700',
  // Phase 2.1 screening — visually grouped near Applied/Shortlisted band.
  SCREENING_SENT: 'bg-slate-100 text-slate-700',
  SCREENING_COMPLETED: 'bg-slate-100 text-slate-700',
  SHORTLISTED: 'bg-slate-100 text-slate-700',
  INTERVIEW_SCHEDULED: 'bg-slate-100 text-slate-700',
  INTERVIEWED: 'bg-amber-100 text-amber-800',
  // Phase 2.3 — slot in the Offer band visually so the stepper colors stay coherent.
  SELECTED_CONDITIONAL: 'bg-brand-50 text-brand-700 ring-1 ring-brand-200',
  OFFERED: 'bg-brand-100 text-brand-800',
  ACCEPTED: 'bg-green-100 text-green-800',
  ONBOARDING: 'bg-slate-100 text-slate-700',
  ACTIVE: 'bg-green-100 text-green-800',
  COMPLETED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
  WITHDRAWN: 'bg-gray-100 text-gray-700',
  LAPSED: 'bg-gray-100 text-gray-700',
  NO_SHOW: 'bg-amber-100 text-amber-800',
  // Spec-mentioned aliases the backend may add later; safe to keep in the map.
  OFFER_EXTENDED: 'bg-brand-100 text-brand-800',
  OFFER_ACCEPTED: 'bg-green-100 text-green-800',
  OFFER_DECLINED: 'bg-red-100 text-red-800',
  HIRED: 'bg-green-100 text-green-800',
};

// Override the title-cased default for statuses whose enum name reads awkwardly.
// Phase 2.3 — "Conditionally Selected" matches the candidate-side phrasing from
// the dashboard hero so both surfaces tell the same story.
const LABEL_OVERRIDE: Record<string, string> = {
  SELECTED_CONDITIONAL: 'Conditionally Selected',
};

function titleCase(raw: string): string {
  return raw
    .split('_')
    .map((part) => (part ? part[0] + part.slice(1).toLowerCase() : part))
    .join(' ');
}

export default function ApplicationStatusBadge({ status }: Props) {
  const color = COLOR_MAP[status] ?? 'bg-gray-100 text-gray-700';
  const label = LABEL_OVERRIDE[status] ?? titleCase(status);
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' + color
      }
    >
      {label}
    </span>
  );
}
