'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertTriangle,
  Briefcase,
  CalendarCheck,
  CalendarCheck2,
  ChevronLeft,
  ClipboardEdit,
  FileText,
  Users,
} from 'lucide-react';
import api from '@/lib/careers/api';
import { useEvaluatorDashboard } from '@/components/evaluator/EvaluatorDashboardContext';
import type {
  EvalueeDetail,
  EvaluationTimelineEntry,
} from '@/components/evaluator/types';
import type {
  ProjectTimelineEntry,
  ProjectTimelineResponse,
} from '@/components/evaluator/perproject-types';
import ProjectCardsSection from '@/components/evaluator/ProjectCardsSection';
import AllWeeklyReportsPanel from '@/components/evaluator/AllWeeklyReportsPanel';
import ScheduleFinalSessionDialog from '@/components/evaluator/ScheduleFinalSessionDialog';

export default function EvalueeDetailPage() {
  const params = useParams<{ lifecycleId: string }>();
  const router = useRouter();
  const lifecycleId = params?.lifecycleId;
  const [data, setData] = useState<EvalueeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  // "Schedule Final" opens the bulk-schedule dialog (one Zoom covering
  // every project's post-project evaluation). We fetch the project
  // timeline lazily on button click so the hub avoids a duplicate
  // request while ProjectCardsSection is also pulling it.
  const [finalDialogOpen, setFinalDialogOpen] = useState(false);
  const [finalDialogEligible, setFinalDialogEligible] = useState<ProjectTimelineEntry[]>([]);
  const [finalDialogLoading, setFinalDialogLoading] = useState(false);
  const [finalDialogErr, setFinalDialogErr] = useState<string | null>(null);

  const { selectedMonth, isCurrentMonth } = useEvaluatorDashboard();

  const load = useCallback(async () => {
    if (!lifecycleId) return;
    setLoading(true);
    try {
      const url = isCurrentMonth
        ? `/api/v1/evaluator/evaluees/${lifecycleId}`
        : `/api/v1/evaluator/evaluees/${lifecycleId}?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<EvalueeDetail>(url);
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
      if (ax.response?.status === 403) {
        router.replace('/careers/evaluator/active-evaluees');
        return;
      }
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load evaluee');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId, router, selectedMonth, isCurrentMonth]);

  useEffect(() => { void load(); }, [load]);

  const openFinalDialog = useCallback(async () => {
    if (!lifecycleId) return;
    setFinalDialogLoading(true);
    setFinalDialogErr(null);
    try {
      const res = await api.get<ProjectTimelineResponse>(
        `/api/v1/evaluator/evaluees/${lifecycleId}/project-timeline`,
      );
      // Server rejects rows outside DRAFT/SCHEDULED; mirror the filter
      // client-side so the dialog only shows what's actionable.
      const eligible = res.data.entries.filter((e) =>
        e.evaluationId != null
        && (e.evaluationStatus === 'DRAFT' || e.evaluationStatus === 'SCHEDULED'));
      setFinalDialogEligible(eligible);
      setFinalDialogOpen(true);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setFinalDialogErr(ax.response?.data?.error ?? ax.message
        ?? 'Failed to load projects');
    } finally {
      setFinalDialogLoading(false);
    }
  }, [lifecycleId]);

  if (loading && !data) {
    return (
      <div className="mx-auto max-w-6xl p-6">
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }
  if (err || !data) {
    return (
      <div className="mx-auto max-w-6xl p-6">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Evaluee not found'}
        </p>
      </div>
    );
  }

  const stemOpt = data.profile.workAuthType === 'F1_STEM_OPT';

  return (
    <div className="mx-auto max-w-6xl space-y-5 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator/active-evaluees"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Back to Active Evaluees
        </Link>
      </p>

      {/* Header card */}
      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">
              {data.profile.internName ?? '(unnamed)'}
            </h1>
            <p className="text-xs text-slate-500">
              {data.profile.employeeId ?? '—'}
              {data.profile.applicantId && (
                <span className="ml-2">· {data.profile.applicantId}</span>
              )}
              {data.profile.internEmail && (
                <span className="ml-2">· {data.profile.internEmail}</span>
              )}
            </p>
            <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
              {data.profile.technology && (
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-700">
                  {data.profile.technology}
                </span>
              )}
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-700">
                {data.profile.monthsInProgram} months in program
              </span>
              {data.profile.workAuthType && (
                <span
                  className={
                    'rounded-full px-2 py-0.5 font-semibold ' +
                    (stemOpt
                      ? 'bg-amber-100 text-amber-800'
                      : 'bg-green-100 text-green-700')
                  }
                >
                  {data.profile.workAuthType.replaceAll('_', ' ')}
                </span>
              )}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void openFinalDialog()}
              disabled={finalDialogLoading}
              className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-60"
              title="Schedule one final session covering every project's post-project evaluation."
            >
              <CalendarCheck2 className="h-4 w-4" />
              {finalDialogLoading ? 'Loading…' : 'Schedule Final'}
            </button>
          </div>
        </div>
        {finalDialogErr && (
          <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {finalDialogErr}
          </p>
        )}
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label="Total evaluations" value={data.profile.totalEvaluationsToDate} />
          <Stat
            label="Last evaluation"
            value={
              data.profile.lastEvaluationAt
                ? new Date(data.profile.lastEvaluationAt).toLocaleDateString()
                : '—'
            }
          />
          <Stat
            label="Current month"
            value={data.currentMonth.monthStatus.replaceAll('_', ' ')}
            tone={data.currentMonth.actionNeeded ? 'amber' : 'emerald'}
          />
          {data.historySummary.averageOverallScore != null && (
            <Stat
              label="Avg overall"
              value={data.historySummary.averageOverallScore.toFixed(2)}
            />
          )}
        </div>
      </section>

      {/* Per-project hub — moved to the top so the evaluator lands on
          the actionable surface (Project 1 / Project 2 cards) before
          the monthly / trainer context cards. Trainer Q&A, recording,
          and per-project actions all live inside each card. */}
      <ProjectCardsSection lifecycleId={data.profile.lifecycleId} />

      {/* Monitor cards */}
      <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <MonitorCurrentMonth detail={data} />
        {stemOpt && <MonitorI983 detail={data} />}
        <MonitorTrainerContext detail={data} />
      </section>

      {finalDialogOpen && (
        <ScheduleFinalSessionDialog
          eligibleProjects={finalDialogEligible}
          onClose={() => setFinalDialogOpen(false)}
          onScheduled={() => setFinalDialogOpen(false)}
        />
      )}

      {/* Every weekly report the intern has submitted — their own
          account of the work leading into each evaluation. */}
      <AllWeeklyReportsPanel lifecycleId={data.profile.lifecycleId} />

      {/* Legacy monthly-evaluation timeline. Left in place so amended /
          published monthly evaluations remain visible during the
          MONTHLY→POST_PROJECT parallel-mode transition. */}
      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Evaluation timeline</h2>
        {data.timeline.length === 0 ? (
          <p className="mt-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-500">
            No evaluations yet. The first monthly evaluation surfaces here once
            it's published (Phase 2 ships the composition flow).
          </p>
        ) : (
          <ol className="mt-3 space-y-3">
            {data.timeline.map((e) => <TimelineEntry key={e.evaluationId} entry={e} />)}
          </ol>
        )}
      </section>
    </div>
  );
}

function Stat({
  label, value, tone = 'slate',
}: {
  label: string;
  value: number | string;
  tone?: 'slate' | 'emerald' | 'amber' | 'rose';
}) {
  const cls = tone === 'emerald' ? 'text-green-700'
    : tone === 'amber' ? 'text-amber-700'
    : tone === 'rose' ? 'text-red-700'
    : 'text-slate-900';
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-2">
      <p className="text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-0.5 text-sm font-semibold tabular-nums ${cls}`}>{value}</p>
    </div>
  );
}

function MonitorCurrentMonth({ detail }: { detail: EvalueeDetail }) {
  const cm = detail.currentMonth;
  const status = cm.monthStatus;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
          <CalendarCheck className="h-3.5 w-3.5" />
          Current Month Evaluation
        </h3>
        {cm.actionNeeded && (
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            Action needed
          </span>
        )}
      </div>
      <p className="mt-3 text-sm text-slate-700">
        <strong>{cm.monthYearLabel}:</strong> {status.replaceAll('_', ' ')}
      </p>
      {cm.publishedAt && (
        <p className="mt-1 text-xs text-slate-500">
          Published {new Date(cm.publishedAt).toLocaleDateString()}
          {cm.daysSincePublish != null && (
            <span className="ml-1">
              · {cm.daysSincePublish}d ago{cm.daysSincePublish > 7 && ' (overdue ack)'}
            </span>
          )}
        </p>
      )}
      {status === 'NOT_YET_SCHEDULED' ? (
        <Link
          href={`/careers/evaluator/schedule-session?internId=${detail.profile.lifecycleId}`}
          className="mt-3 inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-800"
        >
          <ClipboardEdit className="h-3 w-3" />
          Schedule
        </Link>
      ) : status === 'SCHEDULED' || status === 'IN_PROGRESS' ? (
        <Link
          href={`/careers/evaluator/evaluations/${cm.currentEvaluationId}/compose`}
          className="mt-3 inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-brand-800"
        >
          <ClipboardEdit className="h-3 w-3" />
          {status === 'SCHEDULED' ? 'Start session' : 'Continue compose'}
        </Link>
      ) : (
        <Link
          href={`/careers/evaluator/evaluations/${cm.currentEvaluationId}`}
          className="mt-3 inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <ClipboardEdit className="h-3 w-3" />
          View / amend
        </Link>
      )}
    </div>
  );
}

function MonitorI983({ detail }: { detail: EvalueeDetail }) {
  const i983 = detail.i983Status;
  if (!i983) return null;
  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50/30 p-4 shadow-sm">
      <h3 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-amber-800">
        <FileText className="h-3.5 w-3.5" />
        I-983 Status (STEM OPT)
      </h3>
      <p className="mt-3 text-sm text-slate-700">
        <strong>Plan status:</strong> {i983.planStatus.replaceAll('_', ' ')}
      </p>
      <p className="mt-1 text-xs text-slate-600">
        Last evaluation:{' '}
        {i983.lastI983EvaluationAt
          ? new Date(i983.lastI983EvaluationAt).toLocaleDateString()
          : 'Not yet conducted'}
        {i983.lastI983Status && ` (${i983.lastI983Status})`}
      </p>
      <p className="mt-1 text-xs text-slate-600">
        Next due:{' '}
        {i983.nextDueDate
          ? new Date(i983.nextDueDate).toLocaleDateString()
          : 'See I-983 Evaluations for cadence'}
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <Link
          href={`/careers/evaluator/i983-evaluations/schedule?internId=${detail.profile.lifecycleId}`}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
        >
          <Briefcase className="h-3 w-3" />
          Conduct I-983
        </Link>
        <Link
          href="/careers/evaluator/i983-evaluations"
          className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-white px-3 py-1.5 text-xs font-medium text-brand-700 hover:bg-brand-50"
        >
          View all I-983
        </Link>
      </div>
    </div>
  );
}

function MonitorTrainerContext({ detail }: { detail: EvalueeDetail }) {
  const t = detail.trainerContext;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
        <Users className="h-3.5 w-3.5" />
        Trainer Context
      </h3>
      <p className="mt-2 text-xs text-slate-500">
        Read-only cross-role awareness.
      </p>
      {!t || (!t.currentProjectTitle && !t.lastMeetingScheduledFor) ? (
        <p className="mt-3 text-sm text-slate-500">
          No active project or recent meeting from Trainer.
        </p>
      ) : (
        <dl className="mt-3 space-y-2 text-xs text-slate-700">
          {t.currentProjectTitle && (
            <div>
              <dt className="text-[10px] uppercase text-slate-500">Current project</dt>
              <dd>
                {t.currentProjectTitle}
                {t.currentProjectStatus && (
                  <span className="ml-1 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                    {t.currentProjectStatus}
                  </span>
                )}
                {t.currentProjectDueDate && (
                  <span className="ml-1 text-slate-500">
                    due {new Date(t.currentProjectDueDate).toLocaleDateString()}
                  </span>
                )}
              </dd>
            </div>
          )}
          {t.lastMeetingScheduledFor && (
            <div>
              <dt className="text-[10px] uppercase text-slate-500">Last weekly meeting</dt>
              <dd
                className={
                  t.daysSinceLastMeeting != null && t.daysSinceLastMeeting > 14
                    ? 'text-red-700'
                    : ''
                }
              >
                {new Date(t.lastMeetingScheduledFor).toLocaleDateString()}
                {t.daysSinceLastMeeting != null && (
                  <span className="ml-1">· {t.daysSinceLastMeeting}d ago</span>
                )}
                {t.lastMeetingStatus && (
                  <span className="ml-1 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                    {t.lastMeetingStatus}
                  </span>
                )}
              </dd>
            </div>
          )}
          {t.trainerName && (
            <div>
              <dt className="text-[10px] uppercase text-slate-500">Trainer</dt>
              <dd>{t.trainerName}</dd>
            </div>
          )}
        </dl>
      )}
    </div>
  );
}

function TimelineEntry({ entry }: { entry: EvaluationTimelineEntry }) {
  const isI983 = entry.entryKind === 'I983_EVALUATION';
  // I-983 entries land on Phase 3 detail (still a stub); intern_evaluations
  // entries route to the read-only detail page that supports amendment.
  const href = isI983
    ? '/careers/evaluator/i983-evaluations'
    : `/careers/evaluator/evaluations/${entry.evaluationId}`;
  return (
    <li>
      <Link
        href={href}
        className="block rounded-md border border-slate-200 bg-white p-3 hover:border-brand-300 hover:shadow-sm"
      >
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={
            'rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
            (isI983
              ? 'bg-amber-100 text-amber-800'
              : 'bg-slate-100 text-slate-700')
          }
        >
          {isI983 ? 'I-983' : (entry.evaluationType ?? 'EVALUATION')}
        </span>
        <span className="text-[10px] text-slate-500">
          {entry.publishedAt ? new Date(entry.publishedAt).toLocaleDateString() : '—'}
        </span>
        {entry.status && (
          <span className="rounded-full bg-green-50 px-2 py-0.5 text-[10px] font-semibold text-green-700">
            {entry.status}
          </span>
        )}
        {entry.overallScore != null && (
          <span className="text-[10px] text-slate-700">
            score {entry.overallScore.toFixed(1)}
          </span>
        )}
        {!entry.acknowledgedAt && entry.status === 'PUBLISHED' && (
          <span className="inline-flex items-center gap-0.5 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-800">
            <AlertTriangle className="h-3 w-3" />
            unacknowledged
          </span>
        )}
      </div>
      {entry.summary && (
        <p className="mt-2 text-xs text-slate-700">{entry.summary}…</p>
      )}
      </Link>
    </li>
  );
}
