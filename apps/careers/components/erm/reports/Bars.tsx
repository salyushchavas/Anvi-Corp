'use client';

import { useMemo } from 'react';

/**
 * Lightweight HTML/CSS bar chart components used across the Reports
 * tabs. No external chart library — width is computed as a % of the
 * max value in the dataset.
 */

export function HorizontalBars({
  rows,
  formatValue,
  tone = 'teal',
}: {
  rows: { label: string; value: number; sub?: string }[];
  formatValue?: (n: number) => string;
  tone?: 'teal' | 'rose' | 'amber' | 'slate';
}) {
  const max = useMemo(
    () => Math.max(1, ...rows.map((r) => r.value)),
    [rows],
  );
  const barTone =
    tone === 'rose'
      ? 'bg-red-500'
      : tone === 'amber'
        ? 'bg-amber-500'
        : tone === 'slate'
          ? 'bg-slate-500'
          : 'bg-brand-600';
  if (rows.length === 0) {
    return (
      <p className="text-xs text-slate-500">No data in this range.</p>
    );
  }
  return (
    <ul className="space-y-1.5">
      {rows.map((r, i) => (
        <li key={i}>
          <div className="flex items-baseline justify-between text-xs text-slate-700">
            <span className="truncate">
              {r.label}
              {r.sub && (
                <span className="ml-2 text-[10px] text-slate-400">
                  {r.sub}
                </span>
              )}
            </span>
            <span className="ml-2 tabular-nums text-slate-900">
              {formatValue ? formatValue(r.value) : r.value.toLocaleString()}
            </span>
          </div>
          <div className="mt-0.5 h-2 w-full rounded-full bg-slate-100">
            <div
              className={'h-2 rounded-full ' + barTone}
              style={{ width: (r.value / max) * 100 + '%' }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

export function VerticalBars({
  rows,
  height = 160,
  tone = 'teal',
}: {
  rows: { label: string; value: number }[];
  height?: number;
  tone?: 'teal' | 'rose' | 'amber';
}) {
  const max = useMemo(
    () => Math.max(1, ...rows.map((r) => r.value)),
    [rows],
  );
  const barTone =
    tone === 'rose' ? 'bg-red-500'
      : tone === 'amber' ? 'bg-amber-500' : 'bg-brand-600';
  if (rows.length === 0) {
    return <p className="text-xs text-slate-500">No data.</p>;
  }
  return (
    <div className="flex items-end gap-1" style={{ height }}>
      {rows.map((r, i) => (
        <div key={i} className="flex flex-1 flex-col items-center gap-1">
          <div className="w-full" style={{ height: height - 24 }}>
            <div
              className={'w-full rounded-t ' + barTone}
              style={{
                height: (r.value / max) * (height - 24) + 'px',
                marginTop:
                  height - 24 - (r.value / max) * (height - 24) + 'px',
              }}
              title={r.label + ': ' + r.value}
            />
          </div>
          <span className="text-[10px] text-slate-500">{r.label}</span>
        </div>
      ))}
    </div>
  );
}

export function Donut({
  slices,
  size = 120,
}: {
  slices: { label: string; value: number; color: string }[];
  size?: number;
}) {
  const total = slices.reduce((sum, s) => sum + s.value, 0);
  if (total === 0) {
    return <p className="text-xs text-slate-500">No data.</p>;
  }
  const radius = size / 2;
  const inner = radius * 0.6;
  let cursor = 0;
  const arcs = slices.map((s) => {
    const start = cursor / total;
    cursor += s.value;
    const end = cursor / total;
    return { ...s, start, end };
  });
  return (
    <div className="flex flex-wrap items-center gap-4">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        {arcs.map((a, i) => (
          <path
            key={i}
            d={arcPath(radius, inner, a.start, a.end)}
            fill={a.color}
          />
        ))}
        <circle cx={radius} cy={radius} r={inner * 0.7} fill="white" />
      </svg>
      <ul className="space-y-1 text-xs">
        {arcs.map((a, i) => (
          <li key={i} className="flex items-center gap-2">
            <span
              className="inline-block h-2 w-2 rounded-full"
              style={{ backgroundColor: a.color }}
            />
            <span className="text-slate-800">{a.label}</span>
            <span className="ml-1 tabular-nums text-slate-500">
              {a.value} ({((a.value / total) * 100).toFixed(0)}%)
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function arcPath(r: number, inner: number, start: number, end: number) {
  const a1 = start * 2 * Math.PI - Math.PI / 2;
  const a2 = end * 2 * Math.PI - Math.PI / 2;
  const x1 = r + r * Math.cos(a1);
  const y1 = r + r * Math.sin(a1);
  const x2 = r + r * Math.cos(a2);
  const y2 = r + r * Math.sin(a2);
  const x3 = r + inner * Math.cos(a2);
  const y3 = r + inner * Math.sin(a2);
  const x4 = r + inner * Math.cos(a1);
  const y4 = r + inner * Math.sin(a1);
  const large = end - start > 0.5 ? 1 : 0;
  return [
    `M ${x1} ${y1}`,
    `A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`,
    `L ${x3} ${y3}`,
    `A ${inner} ${inner} 0 ${large} 0 ${x4} ${y4}`,
    'Z',
  ].join(' ');
}
