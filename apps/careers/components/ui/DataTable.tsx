'use client';

import { ChevronDown, ChevronUp } from 'lucide-react';
import { ReactNode, useMemo, useState } from 'react';
import { cn } from '@/lib/cn';
import EmptyState, { type EmptyStateProps } from './EmptyState';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  sortable?: boolean;
  /** Optional accessor for sortable columns; defaults to row[key]. */
  sortValue?: (row: T) => string | number | Date | null | undefined;
  width?: string;
  align?: 'left' | 'right' | 'center';
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  onRowClick?: (row: T) => void;
  /** Stable key for each row — falls back to index. */
  rowKey?: (row: T, index: number) => string;
  empty: EmptyStateProps;
  className?: string;
}

export default function DataTable<T extends Record<string, any>>({
  columns,
  data,
  onRowClick,
  rowKey,
  empty,
  className,
}: DataTableProps<T>) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const sorted = useMemo(() => {
    if (!sortKey) return data;
    const col = columns.find((c) => c.key === sortKey);
    if (!col) return data;
    const accessor = col.sortValue ?? ((row: T) => (row as any)[sortKey]);
    const sign = sortDir === 'asc' ? 1 : -1;
    return [...data].sort((a, b) => {
      const av = accessor(a);
      const bv = accessor(b);
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      if (av < bv) return -1 * sign;
      if (av > bv) return 1 * sign;
      return 0;
    });
  }, [data, sortKey, sortDir, columns]);

  if (data.length === 0) {
    return <EmptyState {...empty} />;
  }

  function handleSort(col: Column<T>) {
    if (!col.sortable) return;
    if (sortKey === col.key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setSortKey(col.key);
      setSortDir('asc');
    }
  }

  return (
    <div className={cn('overflow-hidden rounded-lg border border-slate-200 bg-white', className)}>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-50">
            <tr>
              {columns.map((col) => {
                const active = sortKey === col.key;
                return (
                  <th
                    key={col.key}
                    scope="col"
                    style={col.width ? { width: col.width } : undefined}
                    className={cn(
                      'sticky top-0 z-[1] border-b border-slate-200 bg-slate-50 px-3 py-2.5 text-left text-xs font-semibold uppercase tracking-wider text-slate-600',
                      col.align === 'right' && 'text-right',
                      col.align === 'center' && 'text-center',
                      col.sortable && 'cursor-pointer select-none hover:bg-slate-100',
                    )}
                    onClick={() => handleSort(col)}
                  >
                    <span className="inline-flex items-center gap-1">
                      {col.header}
                      {col.sortable && active && (
                        sortDir === 'asc' ? (
                          <ChevronUp className="h-3 w-3" strokeWidth={2.5} />
                        ) : (
                          <ChevronDown className="h-3 w-3" strokeWidth={2.5} />
                        )
                      )}
                    </span>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {sorted.map((row, idx) => {
              const k = rowKey ? rowKey(row, idx) : String(idx);
              return (
                <tr
                  key={k}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={cn(
                    'border-b border-slate-100 last:border-b-0',
                    onRowClick && 'cursor-pointer hover:bg-slate-50',
                  )}
                >
                  {columns.map((col) => (
                    <td
                      key={col.key}
                      className={cn(
                        'px-3 py-2.5 text-slate-700',
                        col.align === 'right' && 'text-right',
                        col.align === 'center' && 'text-center',
                      )}
                    >
                      {col.render(row)}
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
