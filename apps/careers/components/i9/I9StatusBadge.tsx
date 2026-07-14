import type { I9Status } from '@/types';

interface Props {
  status: I9Status | string;
  size?: 'sm' | 'md';
}

const COLOR: Record<string, string> = {
  NOT_STARTED: 'bg-gray-200 text-gray-700',
  // Phase 3 step 5 — both the new canonical SECTION_2_PENDING and the legacy
  // SECTION_1_COMPLETE alias render with the same amber "Section 2 due" colour.
  SECTION_2_PENDING: 'bg-amber-100 text-amber-800',
  SECTION_1_COMPLETE: 'bg-amber-100 text-amber-800',
  COMPLETED: 'bg-green-100 text-green-800',
  REOPENED: 'bg-amber-100 text-amber-800',
};

const LABEL: Record<string, string> = {
  NOT_STARTED: 'Not Started',
  SECTION_2_PENDING: 'Section 2 Pending',
  SECTION_1_COMPLETE: 'Section 1 Complete',
  COMPLETED: 'Complete',
  REOPENED: 'Reopened',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export default function I9StatusBadge({ status, size = 'sm' }: Props) {
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
