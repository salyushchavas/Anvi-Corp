'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import StateDot from '@/components/erm/active/StateDot';
import {
  EXCEPTION_TYPE_LABEL,
} from '@/components/erm/escalations/types';
import type {
  ComplianceCard,
  EscalationsCard,
  EvaluationCard,
  InternMonitorView,
  ProjectCard,
  TimesheetCard,
  TrainerMeetingCard,
} from '@/components/erm/active/types';

const POLL_MS = 60_000;

export default function InternMonitorPage() {
  // useParams (next/navigation) is the standard client-component way to read
  // dynamic-route params on Next 14. The previous `use(props.params)` form
  // expects `params` to be a thenable (Next 15 behavior); on Next 14.2 it's a
  // plain object and React's `use()` throws minified error #438.
  const params = useParams<{ lifecycleId: string }>();
  const lifecycleId = params?.lifecycleId ?? '';
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Body lifecycleId={lifecycleId} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ lifecycleId }: { lifecycleId: string }) {
  const [v, setV] = useState<InternMonitorView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<InternMonitorView>(
        `/api/v1/erm/active-interns/${lifecycleId}`,
      );
      setV(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load monitor');
    }
  }, [lifecycleId]);

  useEffect(() => {
    void load();
    const id = setInterval(() => {
      void load();
    }, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  if (err && !v) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!v) return <div className="h-64 animate-pulse rounded-lg bg-slate-100" />;

  return (
    <>
      <PageHeader
        title={v.intern.fullName ?? 'Intern'}
        subtitle={`${v.intern.employeeId ?? ''} · ${v.intern.email ?? ''} · ${v.summary.daysActive}d active`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3 text-xs text-slate-600">
        <Link
          href="/careers/erm/active-interns"
          className="text-slate-500 hover:text-slate-700"
        >
          ← Active interns
        </Link>
        <span>·</span>
        <span>Trainer: <strong>{v.summary.trainerName ?? '—'}</strong></span>
        <span>Evaluator: <strong>{v.summary.evaluatorName ?? '—'}</strong></span>
        <span>Manager: <strong>{v.summary.managerName ?? '—'}</strong></span>
        <span>ERM: <strong>{v.summary.ermName ?? '—'}</strong></span>
        {v.intern.signedRoleTitle && (
          <span className="ml-auto rounded-full bg-slate-100 px-2 py-0.5 text-[11px]">
            {v.intern.signedRoleTitle} · {v.intern.compensationSummary ?? ''}
          </span>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2 grid grid-cols-1 gap-4 md:grid-cols-2">
          <ProjectCardView card={v.project} />
          <MeetingCardView card={v.trainerMeeting} />
          <EvaluationCardView card={v.evaluation} />
          <TimesheetCardView card={v.timesheet} />
          <ComplianceCardView card={v.compliance} userId={v.intern.userId} />
          <EscalationsCardView card={v.escalations} lifecycleId={lifecycleId} />
        </div>

        <aside className="space-y-3">
          <QuickActions intern={v.intern} lifecycleId={lifecycleId} />
          <RecentActivityCard entries={v.recentActivity} />
        </aside>
      </div>
    </>
  );
}

function QuickActions({
  intern,
  lifecycleId,
}: {
  intern: InternMonitorView['intern'];
  lifecycleId: string;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="mb-3 text-sm font-semibold text-slate-900">
        Quick actions
      </h3>
      <div className="space-y-2 text-sm">
        <Link
          href={`/careers/erm/compliance/${intern.userId}`}
          className="block rounded border border-slate-200 px-3 py-1.5 text-slate-700 hover:bg-slate-50"
        >
          Compliance tracker →
        </Link>
        <Link
          href={`/careers/erm/document-packets`}
          className="block rounded border border-slate-200 px-3 py-1.5 text-slate-700 hover:bg-slate-50"
        >
          Document packets →
        </Link>
        <Link
          href={`/careers/erm/escalations?internLifecycleId=${lifecycleId}`}
          className="block rounded border border-slate-200 px-3 py-1.5 text-slate-700 hover:bg-slate-50"
        >
          Filter escalations to this intern →
        </Link>
        <Link
          href={`/careers/erm/exits/initiate?lifecycleId=${lifecycleId}`}
          className="block rounded border border-red-200 bg-red-50 px-3 py-1.5 text-red-700 hover:bg-red-100"
        >
          Initiate exit →
        </Link>
      </div>
    </section>
  );
}

function RecentActivityCard({
  entries,
}: {
  entries: InternMonitorView['recentActivity'];
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h3 className="mb-3 text-sm font-semibold text-slate-900">
        Recent activity
      </h3>
      {entries.length === 0 ? (
        <p className="text-xs text-slate-500">No recent activity.</p>
      ) : (
        <ul className="space-y-2 text-xs text-slate-700">
          {entries.slice(0, 20).map((e, i) => (
            <li key={i} className="border-l-2 border-slate-200 pl-2">
              <div className="font-medium text-slate-900">
                {e.action ?? '—'}
                {e.entityType && (
                  <span className="ml-1 text-[10px] font-normal text-slate-500">
                    on {e.entityType}
                  </span>
                )}
              </div>
              <div className="text-[11px] text-slate-500">
                {e.actorName ?? 'system'} ·{' '}
                {e.at ? new Date(e.at).toLocaleString() : '—'}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function CardShell({
  title,
  state,
  children,
}: {
  title: string;
  state: { state: 'OK' | 'WARN' | 'URGENT'; label: string; detail: string | null };
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
        <StateDot state={state} detail />
      </div>
      {children}
    </section>
  );
}

function ProjectCardView({ card }: { card: ProjectCard }) {
  return (
    <CardShell title="Project" state={card.state}>
      {card.projects.length === 0 ? (
        <p className="text-xs text-slate-500">No projects assigned yet.</p>
      ) : (
        <ul className="space-y-2 text-xs text-slate-700">
          {card.projects.slice(0, 3).map((p) => (
            <li key={p.id}>
              <div className="text-sm font-medium text-slate-900">
                {p.title ?? '(untitled)'}
              </div>
              <div className="text-[11px] text-slate-500">
                {p.status} · due {p.dueDate ?? '—'}
              </div>
            </li>
          ))}
        </ul>
      )}
      {card.assignNewCta && (
        <p className="mt-2 text-[11px] text-slate-500">
          Trainer can assign a new project from their dashboard.
        </p>
      )}
    </CardShell>
  );
}

function MeetingCardView({ card }: { card: TrainerMeetingCard }) {
  return (
    <CardShell title="Trainer meeting" state={card.state}>
      {card.upcoming.length === 0 && card.past.length === 0 ? (
        <p className="text-xs text-slate-500">No meetings on record.</p>
      ) : (
        <>
          {card.upcoming.length > 0 && (
            <div className="mb-2">
              <p className="text-[10px] font-semibold uppercase text-slate-500">
                Upcoming
              </p>
              <ul className="space-y-1 text-xs text-slate-700">
                {card.upcoming.slice(0, 2).map((m) => (
                  <li key={m.id}>
                    {m.topic ?? '(no topic)'} ·{' '}
                    {m.scheduledFor
                      ? new Date(m.scheduledFor).toLocaleString()
                      : '—'}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {card.past.length > 0 && (
            <div>
              <p className="text-[10px] font-semibold uppercase text-slate-500">
                Past
              </p>
              <ul className="space-y-1 text-xs text-slate-700">
                {card.past.slice(0, 3).map((m) => (
                  <li key={m.id}>
                    {m.topic ?? '(no topic)'} ·{' '}
                    {m.status}{' '}
                    {m.scheduledFor && (
                      <span className="text-[10px] text-slate-500">
                        ({new Date(m.scheduledFor).toLocaleDateString()})
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
      {card.scheduleNewCta && (
        <p className="mt-2 text-[11px] text-slate-500">
          Trainer schedules the next meeting from their dashboard.
        </p>
      )}
    </CardShell>
  );
}

function EvaluationCardView({ card }: { card: EvaluationCard }) {
  return (
    <CardShell title="Evaluations" state={card.state}>
      {card.evaluations.length === 0 ? (
        <p className="text-xs text-slate-500">No evaluations yet.</p>
      ) : (
        <ul className="space-y-2 text-xs text-slate-700">
          {card.evaluations.slice(0, 3).map((e) => (
            <li key={e.id}>
              <div className="text-sm font-medium text-slate-900">
                {e.evaluationType} · {e.status}
              </div>
              <div className="text-[11px] text-slate-500">
                {e.publishedAt
                  ? 'Published ' + new Date(e.publishedAt).toLocaleDateString()
                  : e.scheduledFor
                    ? 'Scheduled ' + new Date(e.scheduledFor).toLocaleDateString()
                    : '—'}
              </div>
            </li>
          ))}
        </ul>
      )}
    </CardShell>
  );
}

function TimesheetCardView({ card }: { card: TimesheetCard }) {
  return (
    <CardShell title="Timesheet" state={card.state}>
      <div className="mb-2 text-xs text-slate-700">
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px]">
          Current week: {card.currentWeekStatus}
        </span>
        <span className="ml-2 text-[11px] text-slate-500">
          Total approved: {card.totalApprovedHours}h
        </span>
      </div>
      {card.lastFourWeeks.length === 0 ? (
        <p className="text-xs text-slate-500">No timesheets in last 4 weeks.</p>
      ) : (
        <ul className="space-y-1 text-xs text-slate-700">
          {card.lastFourWeeks.map((t) => (
            <li key={t.id} className="flex justify-between">
              <span>{t.weekStart}</span>
              <span>
                {t.hours}h ·{' '}
                <span
                  className={
                    t.status === 'REJECTED'
                      ? 'text-red-700'
                      : t.status === 'APPROVED'
                        ? 'text-green-700'
                        : ''
                  }
                >
                  {t.status}
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </CardShell>
  );
}

function ComplianceCardView({
  card,
  userId,
}: {
  card: ComplianceCard;
  userId: string;
}) {
  return (
    <CardShell title="Compliance" state={card.state}>
      <dl className="space-y-1 text-xs text-slate-700">
        <Row k="Work auth" v={card.workAuthType ?? '—'} />
        <Row k="Expires" v={card.workAuthExpiresOn ?? '—'} />
        <Row k="I-9" v={card.i9Status ?? '—'} />
        <Row k="E-Verify" v={card.everifyStatus ?? '—'} />
        <Row k="I-983" v={card.i983Status ?? 'NOT_REQUIRED'} />
      </dl>
      {card.alerts.length > 0 && (
        <ul className="mt-2 space-y-1 text-[11px]">
          {card.alerts.map((a, i) => (
            <li key={i} className="text-slate-700">
              <span
                className={
                  'inline-block h-1.5 w-1.5 rounded-full mr-1 ' +
                  (a.severity === 'URGENT'
                    ? 'bg-red-500'
                    : a.severity === 'WARN'
                      ? 'bg-amber-500'
                      : 'bg-slate-500')
                }
              />
              {EXCEPTION_TYPE_LABEL[a.label] ?? a.label}
            </li>
          ))}
        </ul>
      )}
      <Link
        href={`/careers/erm/compliance/${userId}`}
        className="mt-3 inline-block text-[11px] text-brand-700 hover:underline"
      >
        Open compliance tracker →
      </Link>
    </CardShell>
  );
}

function EscalationsCardView({
  card,
  lifecycleId,
}: {
  card: EscalationsCard;
  lifecycleId: string;
}) {
  return (
    <CardShell title="Escalations" state={card.state}>
      {card.openExceptions.length === 0 ? (
        <p className="text-xs text-slate-500">No open exceptions.</p>
      ) : (
        <ul className="space-y-1 text-xs text-slate-700">
          {card.openExceptions.slice(0, 5).map((e) => (
            <li key={e.id} className="flex items-center gap-2">
              <span
                className={
                  'inline-block h-1.5 w-1.5 rounded-full ' +
                  (e.severity === 'URGENT'
                    ? 'bg-red-500'
                    : e.severity === 'WARN'
                      ? 'bg-amber-500'
                      : 'bg-slate-500')
                }
              />
              <Link
                href={`/careers/erm/escalations/${e.id}`}
                className="hover:underline"
              >
                {EXCEPTION_TYPE_LABEL[e.exceptionType] ?? e.exceptionType}
              </Link>
              <span className="ml-auto text-[10px] text-slate-500">
                {e.ageDays}d
              </span>
            </li>
          ))}
        </ul>
      )}
      <Link
        href={`/careers/erm/escalations?internLifecycleId=${lifecycleId}`}
        className="mt-3 inline-block text-[11px] text-brand-700 hover:underline"
      >
        All escalations for this intern →
      </Link>
    </CardShell>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between border-b border-slate-100 pb-0.5">
      <span className="text-slate-500">{k}</span>
      <span className="text-slate-800">{v}</span>
    </div>
  );
}
