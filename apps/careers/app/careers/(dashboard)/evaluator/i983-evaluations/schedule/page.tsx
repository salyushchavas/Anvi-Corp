'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { CalendarPlus, ChevronLeft, Info } from 'lucide-react';
import api from '@/lib/api';
import type {
  EvaluatorI983Detail,
  I983EvaluationType,
  I983ListResponse,
  I983ListRow,
} from '@/components/evaluator/types';
import { I983_TYPES } from '@/components/evaluator/types';

export default function ScheduleI983Page() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-4xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <ScheduleI983Inner />
    </Suspense>
  );
}

function defaultScheduledFor(): string {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  d.setHours(15, 0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes())
  );
}

function ScheduleI983Inner() {
  const router = useRouter();
  const sp = useSearchParams();
  const prefillId = sp?.get('internId') ?? '';

  const [eligible, setEligible] = useState<I983ListRow[]>([]);
  const [internLifecycleId, setInternLifecycleId] = useState(prefillId);
  const [evaluationType, setEvaluationType] = useState<I983EvaluationType>('ANNUAL_REVIEW');
  const [scheduledFor, setScheduledFor] = useState(defaultScheduledFor());
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [agenda, setAgenda] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const loadEligible = useCallback(async () => {
    try {
      const res = await api.get<I983ListResponse>('/api/v1/evaluator/i983-evaluations');
      // Pool of selectable interns = dueSoon rows that have a plan.
      const pool = res.data.dueSoon.filter((r) => r.planExists);
      setEligible(pool);
    } catch { setEligible([]); }
  }, []);
  useEffect(() => { void loadEligible(); }, [loadEligible]);

  const selected = eligible.find((e) => e.internLifecycleId === internLifecycleId) ?? null;

  // Auto-fill default type from the selected row.
  useEffect(() => {
    if (selected) {
      const next = selected.evaluationType as I983EvaluationType | undefined;
      if (next && I983_TYPES.includes(next)) setEvaluationType(next);
    }
  }, [selected]);

  async function submit() {
    setErr(null);
    if (!internLifecycleId) { setErr('Pick a STEM OPT intern.'); return; }
    if (!scheduledFor) { setErr('Pick a date/time.'); return; }
    setSubmitting(true);
    try {
      const periodStart = selected?.trainingStartDate ?? null;
      const periodEnd = selected?.nextDueDate ?? selected?.trainingEndDate ?? null;
      const res = await api.post<EvaluatorI983Detail>(
        '/api/v1/evaluator/i983-evaluations',
        {
          internLifecycleId,
          evaluationType,
          scheduledFor: new Date(scheduledFor).toISOString(),
          durationMinutes,
          periodStartDate: periodStart,
          periodEndDate: periodEnd,
          agenda: agenda.trim() || null,
        },
      );
      router.push(`/careers/evaluator/i983-evaluations/${res.data.evaluationId}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to schedule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator/i983-evaluations"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          I-983 Evaluations
        </Link>
      </p>
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Schedule I-983 Evaluation</h1>
        <p className="text-xs text-slate-500">
          Federal STEM OPT requirement. Only interns with an active I-983 plan
          can be scheduled.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm lg:col-span-2">
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">STEM OPT intern *</span>
            <select
              value={internLifecycleId}
              onChange={(e) => setInternLifecycleId(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">Pick an eligible intern…</option>
              {eligible.map((e) => (
                <option key={e.internLifecycleId} value={e.internLifecycleId}>
                  {e.internName ?? '(unnamed)'}
                  {e.employeeId ? ` · ${e.employeeId}` : ''}
                  {e.nextDueDate ? ` · next due ${e.nextDueDate}` : ''}
                </option>
              ))}
            </select>
            {eligible.length === 0 && (
              <p className="mt-1 text-[11px] text-amber-700">
                No eligible STEM OPT interns with active I-983 plans found in
                the next 60 days.
              </p>
            )}
          </label>

          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Evaluation type *</span>
            <div className="mt-1 grid grid-cols-1 gap-2 sm:grid-cols-3">
              {I983_TYPES.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setEvaluationType(t)}
                  className={
                    'rounded-md border px-3 py-1.5 text-xs font-medium ' +
                    (evaluationType === t
                      ? 'border-brand-700 bg-brand-700 text-white'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50')
                  }
                >
                  {t.replaceAll('_', ' ')}
                </button>
              ))}
            </div>
          </label>

          <div className="grid grid-cols-2 gap-3">
            <label className="block">
              <span className="text-xs font-semibold text-slate-700">Date / time *</span>
              <input
                type="datetime-local"
                value={scheduledFor}
                onChange={(e) => setScheduledFor(e.target.value)}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </label>
            <label className="block">
              <span className="text-xs font-semibold text-slate-700">Duration</span>
              <select
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(Number(e.target.value))}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                {[30, 45, 60, 90].map((d) => <option key={d} value={d}>{d} min</option>)}
              </select>
            </label>
          </div>

          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Agenda (optional)</span>
            <textarea
              value={agenda}
              onChange={(e) => setAgenda(e.target.value)}
              rows={4}
              maxLength={2000}
              placeholder="What you'd like to discuss — training objectives progress, supervisor feedback, signature confirmation, etc."
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={submitting}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
            >
              <CalendarPlus className="h-4 w-4" />
              {submitting ? 'Scheduling…' : 'Schedule I-983'}
            </button>
          </div>
        </div>

        <aside className="space-y-3">
          {selected ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50/30 p-4">
              <p className="text-[10px] font-semibold uppercase tracking-wide text-amber-800">
                Plan context
              </p>
              <p className="mt-1 text-sm font-semibold text-slate-900">
                {selected.internName}
              </p>
              <p className="text-[11px] text-slate-500">{selected.employeeId ?? '—'}</p>
              <ul className="mt-2 space-y-0.5 text-[11px] text-slate-700">
                {selected.trainingStartDate && (
                  <li>Training start: {selected.trainingStartDate}</li>
                )}
                {selected.trainingEndDate && (
                  <li>Training end: {selected.trainingEndDate}</li>
                )}
                {selected.nextDueDate && (
                  <li className="text-amber-800 font-semibold">
                    Next due: {selected.nextDueDate}
                  </li>
                )}
              </ul>
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-4 text-xs text-slate-500">
              <Info className="mb-1 inline h-3.5 w-3.5" />{' '}
              Pick an intern to see their I-983 plan context.
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
