'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import SignOutButton from '@/components/auth/SignOutButton';
import { BRAND } from '@/lib/brand';

/**
 * Evaluator Phase 0 — 9-item sidebar per the locked Evaluator dashboard
 * spec. I-983 Evaluations stays visible at all times in Phase 0 (Phase 3
 * will conditionally hide it for cohorts with no F1_STEM_OPT interns).
 *
 * <p>Phase 1 lights up Home + Active Evaluees, Phase 2 lights up Schedule
 * Session + Pending Evaluations, Phase 3 lights up I-983 Evaluations,
 * Phase 4 lights up Evaluation History + Reports + Settings + Help.</p>
 */
const ITEMS: { href: string; label: string; phase: number }[] = [
  { href: '/careers/evaluator',                       label: 'Home',                 phase: 1 },
  { href: '/careers/evaluator/active-evaluees',       label: 'Active Evaluees',      phase: 1 },
  { href: '/careers/evaluator/schedule-session',      label: 'Schedule Session',     phase: 2 },
  { href: '/careers/evaluator/pending-evaluations',   label: 'Pending Evaluations',  phase: 2 },
  { href: '/careers/evaluator/pending-vivas',         label: 'Pending Q&A',          phase: 2 },
  { href: '/careers/evaluator/i983-evaluations',      label: 'I-983 Evaluations',    phase: 3 },
  { href: '/careers/evaluator/evaluation-history',    label: 'Evaluation History',   phase: 4 },
  { href: '/careers/evaluator/reports',               label: 'Reports',              phase: 4 },
  { href: '/careers/evaluator/settings',              label: 'Settings',             phase: 4 },
  { href: '/careers/evaluator/help',                  label: 'Help',                 phase: 4 },
];

export default function EvaluatorSidebar() {
  const pathname = usePathname();
  return (
    <aside className="hidden h-screen w-60 shrink-0 flex-col border-r border-slate-200 bg-white lg:flex">
      <div className="border-b border-slate-200 px-4 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {BRAND.name}
        </p>
        <p className="mt-0.5 text-sm font-semibold text-slate-900">Evaluator</p>
      </div>
      <nav className="flex-1 overflow-y-auto p-2">
        <ul className="space-y-0.5">
          {ITEMS.map((it) => {
            const active =
              pathname === it.href ||
              (it.href !== '/careers/evaluator' && pathname?.startsWith(it.href));
            return (
              <li key={it.href}>
                <Link
                  href={it.href}
                  className={
                    'flex items-center justify-between rounded-md px-3 py-2 text-sm ' +
                    (active
                      ? 'bg-brand-50 font-semibold text-brand-800'
                      : 'text-slate-700 hover:bg-slate-50')
                  }
                >
                  <span>{it.label}</span>
                  <span className="text-[10px] text-slate-400">P{it.phase}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="space-y-2 border-t border-slate-200 px-3 py-3">
        <SignOutButton variant="sidebar" />
        <p className="px-3 text-[11px] text-slate-500">
          Role: Evaluator · Audit log
        </p>
      </div>
    </aside>
  );
}
