import type { OfferStatus } from '@/types';

interface Props {
  status: OfferStatus | string;
  size?: 'sm' | 'md';
}

const COLOR_MAP: Record<string, string> = {
  DRAFT: 'bg-gray-200 text-gray-700',
  SENT: 'bg-slate-100 text-slate-700',
  ACCEPTED: 'bg-green-100 text-green-800',
  DECLINED: 'bg-red-100 text-red-800',
  EXPIRED: 'bg-amber-100 text-amber-800',
  REVOKED: 'bg-red-100 text-red-800',
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

export default function OfferStatusBadge({ status, size = 'sm' }: Props) {
  const color = COLOR_MAP[status] ?? 'bg-gray-100 text-gray-700';
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full font-medium ' +
        SIZE_MAP[size] +
        ' ' +
        color
      }
    >
      {titleCase(status)}
    </span>
  );
}
