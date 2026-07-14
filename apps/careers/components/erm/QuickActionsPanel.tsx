'use client';

import Link from 'next/link';
import {
  AlertTriangle,
  ClipboardCheck,
  ClipboardList,
  Download,
  FileSignature,
  LogOut,
  Search,
  UserPlus,
  Video,
  type LucideIcon,
} from 'lucide-react';
import type { QuickAction } from './ErmDashboardContext';

const RED_THRESHOLD = 5;

const ICON_BY_KEY: Record<string, LucideIcon> = {
  shortlist: Search,
  interview: Video,
  offer: FileSignature,
  'onboarding-assign': UserPlus,
  'doc-review': ClipboardCheck,
  escalate: AlertTriangle,
  exit: LogOut,
  reports: Download,
};

interface Props {
  actions: QuickAction[] | null;
  loading: boolean;
}

export default function QuickActionsPanel({ actions, loading }: Props) {
  if (loading && !actions) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="h-10 animate-pulse rounded-md bg-slate-50" />
        ))}
      </div>
    );
  }
  if (!actions || actions.length === 0) return null;
  return (
    <nav className="space-y-1">
      {actions.map((a) => {
        const Icon = ICON_BY_KEY[a.key] ?? ClipboardList;
        const red = a.badge > RED_THRESHOLD;
        return (
          <Link
            key={a.key}
            href={a.href}
            className="flex items-center gap-3 rounded-md border border-transparent px-3 py-2 text-sm text-slate-700 hover:border-slate-200 hover:bg-slate-50"
          >
            <Icon className="h-4 w-4 text-slate-500" strokeWidth={2} />
            <span className="flex-1 truncate">{a.label}</span>
            <span
              className={
                'inline-flex min-w-[24px] items-center justify-center rounded-full px-1.5 py-0.5 text-[11px] font-semibold tabular-nums ' +
                (a.badge === 0
                  ? 'bg-slate-100 text-slate-500'
                  : red
                    ? 'bg-red-100 text-red-700'
                    : 'bg-brand-100 text-brand-800')
              }
            >
              {a.badge}
            </span>
          </Link>
        );
      })}
    </nav>
  );
}
