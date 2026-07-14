import type { EVerifyPhase } from '@/types';

/**
 * Phase 3 step 7 — coarse phase chip used on the HR list + detail header.
 * The rich {@code EVerifyStatusBadge} stays for the "what step exactly are we
 * in" reading; this badge is the at-a-glance signal.
 */
interface Props {
  phase: EVerifyPhase | string | null | undefined;
  size?: 'sm' | 'md';
}

const COLOR: Record<string, string> = {
  CREATED: 'bg-slate-100 text-slate-700',
  AUTHORIZED: 'bg-green-100 text-green-800',
  IN_REVIEW: 'bg-amber-100 text-amber-800',
  NOT_AUTHORIZED: 'bg-red-100 text-red-800',
  CLOSED: 'bg-gray-100 text-gray-600',
};

const LABEL: Record<string, string> = {
  CREATED: 'Created',
  AUTHORIZED: 'Authorized',
  IN_REVIEW: 'In Review',
  NOT_AUTHORIZED: 'Not Authorized',
  CLOSED: 'Closed',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

export default function EVerifyPhaseBadge({ phase, size = 'sm' }: Props) {
  if (!phase) return null;
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full font-medium ' +
        SIZE_MAP[size] +
        ' ' +
        (COLOR[phase] ?? 'bg-gray-100 text-gray-700')
      }
    >
      {LABEL[phase] ?? phase}
    </span>
  );
}
