'use client';

import { RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { cn } from '@/lib/cn';

export interface DashboardRefreshButtonProps {
  onRefresh: () => Promise<unknown> | unknown;
  className?: string;
  label?: string;
}

export default function DashboardRefreshButton({
  onRefresh,
  className,
  label = 'Refresh',
}: DashboardRefreshButtonProps) {
  const [busy, setBusy] = useState(false);

  async function click() {
    if (busy) return;
    setBusy(true);
    try {
      await onRefresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      onClick={click}
      disabled={busy}
      aria-label="Refresh dashboard"
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2',
        className,
      )}
    >
      <RefreshCw
        className={cn('h-3.5 w-3.5 text-brand-600', busy && 'animate-spin')}
        strokeWidth={2}
      />
      {busy ? 'Refreshing…' : label}
    </button>
  );
}
