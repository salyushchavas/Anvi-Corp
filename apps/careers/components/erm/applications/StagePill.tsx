'use client';

const STYLES: Record<string, string> = {
  APPLIED: 'bg-slate-100 text-slate-700',
  HOLD: 'bg-slate-200 text-slate-700',
  INFO_REQUESTED: 'bg-slate-100 text-slate-700',
  SCREENING_SENT: 'bg-slate-100 text-slate-700',
  SCREENING_COMPLETED: 'bg-slate-100 text-slate-700',
  SHORTLISTED: 'bg-amber-100 text-amber-800',
  INTERVIEW_SCHEDULED: 'bg-amber-100 text-amber-800',
  INTERVIEWED: 'bg-amber-100 text-amber-800',
  SELECTED_CONDITIONAL: 'bg-brand-100 text-brand-800',
  OFFERED: 'bg-brand-100 text-brand-800',
  ACCEPTED: 'bg-green-100 text-green-800',
  HIRED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
  WITHDRAWN: 'bg-slate-100 text-slate-600',
  LAPSED: 'bg-slate-100 text-slate-600',
  NO_SHOW: 'bg-red-100 text-red-800',
};

const LABEL: Record<string, string> = {
  APPLIED: 'Applied',
  HOLD: 'Hold',
  INFO_REQUESTED: 'Info requested',
  SCREENING_SENT: 'Screening sent',
  SCREENING_COMPLETED: 'Screening done',
  SHORTLISTED: 'Shortlisted',
  INTERVIEW_SCHEDULED: 'Interview scheduled',
  INTERVIEWED: 'Interviewed',
  SELECTED_CONDITIONAL: 'Selected',
  OFFERED: 'Offered',
  ACCEPTED: 'Accepted',
  HIRED: 'Hired',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
  LAPSED: 'Lapsed',
  NO_SHOW: 'No-show',
};

export default function StagePill({ stage }: { stage: string }) {
  const cls = STYLES[stage] ?? 'bg-slate-100 text-slate-700';
  return (
    <span
      className={
        'inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ' + cls
      }
    >
      {LABEL[stage] ?? stage}
    </span>
  );
}
