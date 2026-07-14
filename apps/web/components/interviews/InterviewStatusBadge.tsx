import type { InterviewStatus } from '@/types';

interface Props {
  status: InterviewStatus | string;
  size?: 'sm' | 'md';
}

const COLOR_MAP: Record<string, string> = {
  SCHEDULED: 'bg-slate-100 text-slate-700',
  COMPLETED: 'bg-green-100 text-green-800',
  CANCELLED: 'bg-gray-200 text-gray-600',
  NO_SHOW: 'bg-amber-100 text-amber-800',
};

const SIZE_MAP: Record<'sm' | 'md', string> = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
};

function titleCase(raw: string): string {
  return raw
    .split('_')
    .map((part) => (part ? part[0] + part.slice(1).toLowerCase() : part))
    .join(' ');
}

export default function InterviewStatusBadge({ status, size = 'sm' }: Props) {
  const color = COLOR_MAP[status] ?? 'bg-gray-100 text-gray-700';
  const sizeClass = SIZE_MAP[size];
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full font-medium ' +
        sizeClass +
        ' ' +
        color
      }
    >
      {titleCase(status)}
    </span>
  );
}
