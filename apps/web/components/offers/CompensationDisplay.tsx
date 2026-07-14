import type { CompensationFrequency } from '@/types';

interface Props {
  amount: number | string;
  frequency: CompensationFrequency;
  currency?: string;
  variant?: 'default' | 'large';
}

const SUFFIX: Record<CompensationFrequency, string> = {
  HOURLY: '/ hour',
  MONTHLY: '/ month',
  YEARLY: '/ year',
};

function formatAmount(amount: number | string, currency: string): string {
  const n = typeof amount === 'string' ? Number(amount) : amount;
  if (!Number.isFinite(n)) return String(amount);
  try {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(n);
  } catch {
    return n.toFixed(2) + ' ' + currency;
  }
}

export default function CompensationDisplay({
  amount,
  frequency,
  currency = 'USD',
  variant = 'default',
}: Props) {
  const className =
    variant === 'large'
      ? 'text-base font-semibold text-gray-900'
      : 'text-sm font-medium text-gray-900';
  return (
    <span className={className}>
      {formatAmount(amount, currency)}{' '}
      <span className="font-normal text-gray-500">{SUFFIX[frequency]}</span>
    </span>
  );
}
