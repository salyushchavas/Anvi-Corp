import { ArrowDown, ArrowRight, ArrowUp, type LucideIcon } from 'lucide-react';

interface Trend {
  direction: 'up' | 'down' | 'flat';
  value: string;
}

interface Props {
  label: string;
  value: string | number;
  icon?: LucideIcon;
  trend?: Trend;
}

const TREND_COLOR: Record<Trend['direction'], string> = {
  up: 'bg-green-50 text-green-700 border-green-200',
  down: 'bg-red-50 text-red-700 border-red-200',
  flat: 'bg-gray-50 text-gray-600 border-gray-200',
};

const TREND_ICON: Record<Trend['direction'], LucideIcon> = {
  up: ArrowUp,
  down: ArrowDown,
  flat: ArrowRight,
};

export default function StatCard({ label, value, icon: Icon, trend }: Props) {
  const TrendIcon = trend ? TREND_ICON[trend.direction] : null;
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-5">
      <div className="flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
          {label}
        </div>
        {Icon && <Icon className="h-4 w-4 text-gray-400" strokeWidth={2} />}
      </div>
      <div className="mt-2 text-2xl font-bold text-gray-900">{value}</div>
      {trend && TrendIcon && (
        <div
          className={
            'mt-2 inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium ' +
            TREND_COLOR[trend.direction]
          }
        >
          <TrendIcon className="h-3 w-3" strokeWidth={2.5} />
          {trend.value}
        </div>
      )}
    </div>
  );
}
