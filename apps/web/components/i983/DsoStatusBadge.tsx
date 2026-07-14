import type { DsoApprovalStatus } from '@/types';

interface Props {
  status: DsoApprovalStatus | string;
  size?: 'sm' | 'md';
}

const COLOR: Record<string, string> = {
  NOT_SUBMITTED: 'bg-gray-200 text-gray-700',
  SUBMITTED: 'bg-slate-100 text-slate-700',
  APPROVED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
  AMENDMENT_REQUESTED: 'bg-amber-100 text-amber-800',
};

const LABEL: Record<string, string> = {
  NOT_SUBMITTED: 'Not Submitted',
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  AMENDMENT_REQUESTED: 'Amendment Requested',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export default function DsoStatusBadge({ status, size = 'sm' }: Props) {
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
