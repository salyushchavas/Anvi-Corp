'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { CalendarPlus, Info } from 'lucide-react';
import api from '@/lib/api';
import type {
  ActiveEvalueeRow,
  ActiveEvalueesPage,
  EvaluatorEvaluationDetail,
} from '@/components/evaluator/types';

export default function ScheduleSessionPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-4xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <ScheduleSessionInner />
    </Suspense>
  );
}

function defaultScheduledFor(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(14, 0, 0, 0);
  // ISO local for datetime-local input
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes())
  );
}

function ScheduleSessionInner() {
  const router = useRouter();
  const sp = useSearchParams();
  const prefillId = sp?.get('internId') ?? '';

  const [evaluees, setEvaluees] = useState<ActiveEvalueeRow[]>([]);
  const [internLifecycleId, setInternLifecycleId] = useState(prefillId);
  const [scheduledFor, setScheduledFor] = useState(defaultScheduledFor());
  const [durationMinutes, setDurationMinutes] = useState(45);
  const [topic, setTopic] = useState('');
  const [agenda, setAgenda] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const monthLabel = new Date().toLocaleString(undefined, { month: 'long', year: 'numeric' });
  useEffect(() => {
    if (!topic) setTopic(`Monthly Evaluation — ${monthLabel}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadEvaluees = useCallback(async () => {
    try {
      const res = await api.get<ActiveEvalueesPage>(
        '/api/v1/evaluator/active-evaluees?pageSize=100',
      );
      setEvaluees(res.data.items ?? []);
    } catch { setEvaluees([]); }
  }, []);
  useEffect(() => { void loadEvaluees(); }, [loadEvaluees]);

  const selected = evaluees.find((e) => e.lifecycleId === internLifecycleId) ?? null;

  async function submit() {
    setErr(null);
    if (!internLifecycleId) { setErr('Pick an evaluee.'); return; }
    if (!scheduledFor) { setErr('Pick a date/time.'); return; }
    setSubmitting(true);
    try {
      const res = await api.post<EvaluatorEvaluationDetail>(
        '/api/v1/evaluator/evaluations',
        {
          internLifecycleId,
          scheduledFor: new Date(scheduledFor).toISOString(),
          durationMinutes,
          topic: topic.trim() || null,
          agenda: agenda.trim() || null,
        },
      );
      router.push(`/careers/evaluator/evaluations/${res.data.evaluationId}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to schedule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Schedule Session</h1>
        <p className="text-xs text-slate-500">
          Book a monthly evaluation session. Zoom meeting is created on submit
          (degrades to a manual link if Zoom is unconfigured).
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm lg:col-span-2">
          <Field label="Evaluee" required>
            <select
              value={internLifecycleId}
              onChange={(e) => setInternLifecycleId(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">Pick an active evaluee…</option>
              {evaluees.map((e) => (
                <option key={e.lifecycleId} value={e.lifecycleId}>
                  {e.internName ?? '(unnamed)'}
                  {e.employeeId ? ` · ${e.employeeId}` : ''}
                  {e.technology ? ` · ${e.technology}` : ''}
                </option>
              ))}
            </select>
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Date / time" required>
              <input
                type="datetime-local"
                value={scheduledFor}
                onChange={(e) => setScheduledFor(e.target.value)}
                className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </Field>
            <Field label="Duration">
              <select
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(Number(e.target.value))}
                className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                {[30, 45, 60].map((d) => <option key={d} value={d}>{d} min</option>)}
              </select>
            </Field>
          </div>

          <Field label="Topic" required>
            <input
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              maxLength={200}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </Field>

          <Field label="Agenda (optional)">
            <textarea
              value={agenda}
              onChange={(e) => setAgenda(e.target.value)}
              rows={4}
              maxLength={2000}
              placeholder="What you'd like to cover this session — projects, blockers, growth focus, etc."
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </Field>

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
              {submitting ? 'Scheduling…' : 'Schedule Session'}
            </button>
          </div>
        </div>

        <aside className="space-y-3">
          {selected ? (
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                Context for {selected.internName}
              </p>
              <p className="mt-1 text-xs text-slate-600">
                {selected.monthsInProgram} months in program
                {selected.workAuthType && ` · ${selected.workAuthType.replaceAll('_', ' ')}`}
              </p>
              <p className="mt-2 text-xs text-slate-700">
                Last evaluation:{' '}
                {selected.lastEvaluationAt
                  ? new Date(selected.lastEvaluationAt).toLocaleDateString()
                  : 'Never'}
                {selected.lastEvaluationStatus && ` (${selected.lastEvaluationStatus})`}
              </p>
              {selected.pendingAckCount > 0 && (
                <p className="mt-2 text-xs text-red-700">
                  {selected.pendingAckCount} pending acknowledgment{selected.pendingAckCount === 1 ? '' : 's'}
                </p>
              )}
              <Link
                href={`/careers/evaluator/evaluees/${selected.lifecycleId}`}
                className="mt-3 inline-block text-[11px] font-medium text-brand-700 hover:underline"
              >
                Open evaluee detail →
              </Link>
            </div>
          ) : (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-4 text-xs text-slate-500">
              <Info className="mb-1 inline h-3.5 w-3.5" />{' '}
              Pick an evaluee to see their last-evaluation context.
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function Field({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">
        {label}{required && <span className="ml-0.5 text-red-600">*</span>}
      </span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
