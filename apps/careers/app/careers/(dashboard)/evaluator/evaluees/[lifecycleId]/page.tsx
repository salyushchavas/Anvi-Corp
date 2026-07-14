'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertTriangle,
  Award,
  BadgeCheck,
  Briefcase,
  CalendarCheck,
  CalendarPlus,
  ChevronLeft,
  ClipboardEdit,
  FileText,
  TrendingDown,
  TrendingUp,
  Users,
  X,
} from 'lucide-react';
import api from '@/lib/api';
import type {
  EvalueeDetail,
  EvaluationTimelineEntry,
} from '@/components/evaluator/types';

export default function EvalueeDetailPage() {
  const params = useParams<{ lifecycleId: string }>();
  const router = useRouter();
  const lifecycleId = params?.lifecycleId;
  const [data, setData] = useState<EvalueeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [finalModalOpen, setFinalModalOpen] = useState(false);

  const load = useCallback(async () => {
    if (!lifecycleId) return;
    setLoading(true);
    try {
      const res = await api.get<EvalueeDetail>(
        `/api/v1/evaluator/evaluees/${lifecycleId}`,
      );
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
  }, [lifecycleId, router]);

  useEffect(() => { void load(); }, [load]);

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
            <Link
              href={`/careers/evaluator/schedule-session?internId=${data.profile.lifecycleId}`}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
            >
              <CalendarPlus className="h-4 w-4" />
              Schedule next evaluation
            </Link>
            <button
              type="button"
              onClick={() => setFinalModalOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm font-semibold text-amber-900 hover:bg-amber-100"
              title="Schedule the Final evaluation. Requires an ExitRecord on this lifecycle (ERM initiates exit first)."
            >
              <Award className="h-4 w-4" />
              Schedule Final
            </button>
          </div>
        </div>
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

      {/* Monitor cards */}
      <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <MonitorCurrentMonth detail={data} />
        <MonitorHistorySummary detail={data} />
        {stemOpt && <MonitorI983 detail={data} />}
        <MonitorTrainerContext detail={data} />
      </section>

      {finalModalOpen && (
        <ScheduleFinalModal
          lifecycleId={data.profile.lifecycleId}
          internName={data.profile.internName}
          onClose={() => setFinalModalOpen(false)}
          onScheduled={(evalId) => {
            setFinalModalOpen(false);
            router.push(`/careers/evaluator/evaluations/${evalId}/compose`);
          }}
        />
      )}

      {/* Timeline */}
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

function MonitorHistorySummary({ detail }: { detail: EvalueeDetail }) {
  const h = detail.historySummary;
  const trendIcon = h.trend === 'IMPROVING'
    ? <TrendingUp className="h-3.5 w-3.5 text-green-700" />
    : h.trend === 'DECLINING'
      ? <TrendingDown className="h-3.5 w-3.5 text-red-700" />
      : null;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600">
        <BadgeCheck className="h-3.5 w-3.5" />
        Evaluation History Summary
      </h3>
      {h.totalEvaluations === 0 ? (
        <p className="mt-3 text-sm text-slate-500">First evaluation pending.</p>
      ) : (
        <dl className="mt-3 grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-[10px] uppercase text-slate-500">Total to date</dt>
            <dd className="text-lg font-semibold text-slate-900">{h.totalEvaluations}</dd>
          </div>
          <div>
            <dt className="text-[10px] uppercase text-slate-500">Avg overall</dt>
            <dd className="text-lg font-semibold text-slate-900">
              {h.averageOverallScore != null ? h.averageOverallScore.toFixed(2) : '—'}
            </dd>
          </div>
          <div className="col-span-2 inline-flex items-center gap-1 text-xs text-slate-700">
            {trendIcon}
            <span>Trend: {h.trend.replaceAll('_', ' ').toLowerCase()}</span>
          </div>
        </dl>
      )}
      <Link
        href={`/careers/evaluator/evaluation-history?intern=${detail.profile.lifecycleId}`}
        className="mt-3 inline-flex items-center gap-0.5 text-xs font-medium text-brand-700 hover:underline"
      >
        Open full history →
      </Link>
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

function ScheduleFinalModal({
  lifecycleId,
  internName,
  onClose,
  onScheduled,
}: {
  lifecycleId: string;
  internName: string | null;
  onClose: () => void;
  onScheduled: (evaluationId: string) => void;
}) {
  const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000);
  const toLocalInput = (d: Date) =>
    new Date(d.getTime() - d.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);

  const [when, setWhen] = useState(toLocalInput(tomorrow));
  const [duration, setDuration] = useState(60);
  const [topic, setTopic] = useState('Final Evaluation — End of Internship');
  const [agenda, setAgenda] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [modalErr, setModalErr] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setModalErr(null);
    try {
      const scheduledFor = new Date(when).toISOString();
      const res = await api.post<{ evaluationId: string }>(
        '/api/v1/evaluator/final-evaluations',
        {
          internLifecycleId: lifecycleId,
          scheduledFor,
          durationMinutes: duration,
          topic,
          agenda,
          timezone:
            Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
        },
      );
      onScheduled(res.data.evaluationId);
    } catch (e) {
      const ax = e as {
        response?: { data?: { error?: string } };
        message?: string;
      };
      setModalErr(
        ax.response?.data?.error ??
          ax.message ??
          'Failed to schedule Final evaluation',
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-lg rounded-lg border border-slate-200 bg-white p-5 shadow-lg">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-900">
              Schedule Final evaluation
            </h2>
            <p className="text-xs text-slate-500">
              {internName ?? '(unnamed intern)'} · End-of-internship review
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-slate-500 hover:bg-slate-100"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <p className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
          Final evaluations require an open ExitRecord on this intern&apos;s
          lifecycle. Ask ERM to initiate the exit flow first if you hit a
          409 here.
        </p>

        <div className="mt-3 space-y-3 text-sm">
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">
              Scheduled for
            </span>
            <input
              type="datetime-local"
              value={when}
              onChange={(e) => setWhen(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2"
            />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">
              Duration (minutes)
            </span>
            <input
              type="number"
              min={15}
              max={180}
              value={duration}
              onChange={(e) => setDuration(parseInt(e.target.value, 10) || 60)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2"
            />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Topic</span>
            <input
              type="text"
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2"
            />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">
              Agenda (optional)
            </span>
            <textarea
              value={agenda}
              onChange={(e) => setAgenda(e.target.value)}
              rows={3}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2"
            />
          </label>
        </div>

        {modalErr && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {modalErr}
          </p>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 bg-white px-4 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-amber-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-amber-800 disabled:opacity-60"
          >
            {submitting ? 'Scheduling…' : 'Schedule Final'}
          </button>
        </div>
      </div>
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
