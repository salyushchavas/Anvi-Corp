'use client';

import Link from 'next/link';
import {
  Briefcase,
  CalendarPlus,
  ClipboardEdit,
  FileCheck,
  FileText,
  Lock,
  Send,
} from 'lucide-react';
import { useEvaluatorDashboard } from './EvaluatorDashboardContext';

interface QuickAction {
  key: string;
  label: string;
  phase: number;          // unlocked when phase <= current implemented phase (Phase 2 here)
  href?: string;          // present when enabled
  icon: React.ReactNode;
}

const ACTIONS: QuickAction[] = [
  { key: 'schedule',  label: 'Schedule Session',      phase: 2, href: '/careers/evaluator/schedule-session', icon: <CalendarPlus className="h-3.5 w-3.5" /> },
  { key: 'compose',   label: 'Pending Evaluations',   phase: 2, href: '/careers/evaluator/pending-evaluations', icon: <ClipboardEdit className="h-3.5 w-3.5" /> },
  { key: 'publish',   label: 'Awaiting Acknowledgment', phase: 2, href: '/careers/evaluator/pending-evaluations', icon: <Send className="h-3.5 w-3.5" /> },
  { key: 'ack',       label: 'Evaluation History',    phase: 4, icon: <FileCheck className="h-3.5 w-3.5" /> },
  { key: 'i983',      label: 'Start I-983 Evaluation', phase: 3, icon: <FileText className="h-3.5 w-3.5" /> },
];

/**
 * Evaluator Phase 1 — right-side panel pulls live context from
 * EvaluatorDashboardContext. Home view shows aggregate counts; evaluee
 * detail view shows that specific intern's snapshot.
 */
export default function EvaluatorRightSidePanel() {
  const { rightPanel } = useEvaluatorDashboard();
  const monthLabel = rightPanel?.monthYearLabel
    ?? new Date().toLocaleString(undefined, { month: 'long', year: 'numeric' });

  return (
    <aside className="hidden h-screen w-72 shrink-0 flex-col border-l border-slate-200 bg-slate-50 xl:flex">
      <div className="border-b border-slate-200 bg-white px-4 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          Current cycle
        </p>
        <p className="mt-0.5 text-sm font-semibold text-slate-900">{monthLabel}</p>
        {rightPanel?.homeAggregate ? (
          <ul className="mt-2 space-y-0.5 text-[11px] text-slate-600">
            <li>{rightPanel.homeAggregate.activeEvaluees} active evaluees</li>
            <li>
              {rightPanel.homeAggregate.evaluationsThisMonth} evaluations published this month
            </li>
            <li>
              {rightPanel.homeAggregate.pendingAcknowledgments} pending acknowledgments
            </li>
          </ul>
        ) : !rightPanel?.evalueeContext ? (
          <p className="mt-1 text-[11px] text-slate-500">Loading…</p>
        ) : null}
      </div>

      {rightPanel?.evalueeContext && (
        <div className="border-b border-slate-200 bg-white px-4 py-4">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
            Evaluee
          </p>
          <p className="mt-0.5 text-sm font-semibold text-slate-900">
            {rightPanel.evalueeContext.internName ?? '(unnamed)'}
          </p>
          <p className="text-[11px] text-slate-500">
            {rightPanel.evalueeContext.employeeId ?? '—'}
            {rightPanel.evalueeContext.technology
              ? ` · ${rightPanel.evalueeContext.technology}`
              : ''}
          </p>
          <ul className="mt-2 space-y-0.5 text-[11px] text-slate-600">
            <li>{rightPanel.evalueeContext.monthsInProgram} months in program</li>
            {rightPanel.evalueeContext.workAuthType && (
              <li>
                Work auth:{' '}
                <span
                  className={
                    rightPanel.evalueeContext.isStemOpt
                      ? 'font-semibold text-amber-800'
                      : ''
                  }
                >
                  {rightPanel.evalueeContext.workAuthType.replaceAll('_', ' ')}
                </span>
              </li>
            )}
            <li>
              Last evaluation:{' '}
              {rightPanel.evalueeContext.lastEvaluationAt
                ? new Date(rightPanel.evalueeContext.lastEvaluationAt).toLocaleDateString()
                : 'Never'}
              {rightPanel.evalueeContext.lastEvaluationStatus
                ? ` (${rightPanel.evalueeContext.lastEvaluationStatus})`
                : ''}
            </li>
          </ul>
          {rightPanel.evalueeContext.isStemOpt && (
            <p className="mt-2 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
              <Briefcase className="h-3 w-3" />
              STEM OPT — I-983 required
            </p>
          )}
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-3">
        <p className="px-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          Quick actions
        </p>
        <ul className="mt-2 space-y-1">
          {ACTIONS.map((a) => {
            const enabled = Boolean(a.href);
            const content = (
              <>
                <span className="inline-flex items-center gap-1.5">
                  {a.icon}
                  {a.label}
                </span>
                {!enabled && (
                  <span className="inline-flex items-center gap-0.5 text-[10px] text-slate-400">
                    <Lock className="h-3 w-3" />
                    P{a.phase}
                  </span>
                )}
              </>
            );
            return (
              <li key={a.key}>
                {enabled ? (
                  <Link
                    href={a.href!}
                    className="flex w-full items-center justify-between gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-left text-xs font-medium text-slate-700 hover:border-brand-300 hover:bg-brand-50"
                  >
                    {content}
                  </Link>
                ) : (
                  <button
                    type="button"
                    disabled
                    title={`Coming in Evaluator Phase ${a.phase}`}
                    className="flex w-full cursor-not-allowed items-center justify-between gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-left text-xs font-medium text-slate-500 opacity-70 hover:opacity-90"
                  >
                    {content}
                  </button>
                )}
              </li>
            );
          })}
        </ul>

        {!rightPanel?.evalueeContext && (
          <div className="mt-5 rounded-md border border-slate-200 bg-white p-3">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Tip
            </p>
            <p className="mt-1 text-xs text-slate-600">
              Open an{' '}
              <Link
                href="/careers/evaluator/active-evaluees"
                className="font-medium text-brand-700 hover:underline"
              >
                Active Evaluee
              </Link>{' '}
              to see that intern's evaluation history + Trainer context here.
            </p>
          </div>
        )}
      </div>

      <div className="border-t border-slate-200 bg-white px-4 py-3 text-[11px] text-slate-500">
        Evaluator dashboard · Phase 1
      </div>
    </aside>
  );
}
