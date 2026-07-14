import type { EVerifyStatus } from '@/types';

interface Props {
  status: EVerifyStatus | string;
  size?: 'sm' | 'md';
}

const COLOR: Record<string, string> = {
  PENDING_SUBMISSION: 'bg-gray-200 text-gray-700',
  OPEN: 'bg-slate-100 text-slate-700',
  EMPLOYMENT_AUTHORIZED: 'bg-green-100 text-green-800',
  TENTATIVE_NONCONFIRMATION: 'bg-amber-100 text-amber-800',
  FINAL_NONCONFIRMATION: 'bg-red-100 text-red-800',
  CLOSED: 'bg-gray-100 text-gray-600',
};

const LABEL: Record<string, string> = {
  PENDING_SUBMISSION: 'Pending Submission',
  OPEN: 'Open',
  EMPLOYMENT_AUTHORIZED: 'Employment Authorized',
  TENTATIVE_NONCONFIRMATION: 'Tentative Non-confirmation',
  FINAL_NONCONFIRMATION: 'Final Non-confirmation',
  CLOSED: 'Closed',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export default function EVerifyStatusBadge({ status, size = 'sm' }: Props) {
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full font-medium ' +
        SIZE_MAP[size] +
        ' ' +
        (COLOR[status] ?? 'bg-gray-100 text-gray-700')
      }
    >
      {LABEL[status] ?? status}
    </span>
  );
}
