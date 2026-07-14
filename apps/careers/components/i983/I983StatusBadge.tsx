import type { I983Status } from '@/types';

interface Props {
  status: I983Status | string;
  size?: 'sm' | 'md';
}

const COLOR: Record<string, string> = {
  DRAFT: 'bg-gray-200 text-gray-700',
  COMPLETE: 'bg-slate-100 text-slate-700',
  SUBMITTED_TO_DSO: 'bg-slate-100 text-slate-700',
  DSO_APPROVED: 'bg-green-100 text-green-800',
  DSO_REJECTED: 'bg-red-100 text-red-800',
  AMENDMENT_REQUESTED: 'bg-amber-100 text-amber-800',
};

const LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  COMPLETE: 'Complete',
  SUBMITTED_TO_DSO: 'Submitted to DSO',
  DSO_APPROVED: 'DSO Approved',
  DSO_REJECTED: 'DSO Rejected',
  AMENDMENT_REQUESTED: 'Amendment Requested',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export default function I983StatusBadge({ status, size = 'sm' }: Props) {
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
