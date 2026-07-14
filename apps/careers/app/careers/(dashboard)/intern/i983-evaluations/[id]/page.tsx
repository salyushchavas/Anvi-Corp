'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { CheckCircle2, ChevronLeft, FileText, Loader2 } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import type { InternI983View } from '@/components/evaluator/types';

export default function InternI983DetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = params?.id;

  const [data, setData] = useState<InternI983View | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [typedName, setTypedName] = useState('');
  const [response, setResponse] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InternI983View>(`/api/v1/intern/i983-evaluations/${id}`);
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
      if (ax.response?.status === 403) {
        router.replace('/careers/intern/i983-evaluations');
        return;
      }
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id, router]);
  useEffect(() => { void load(); }, [load]);

  const needsSign = data && (data.status === 'PUBLISHED' || data.status === 'AMENDED')
    && data.acknowledgedAt == null;
  const canSubmit = typedName.trim().length > 0 && agreed && !submitting;

  async function submit() {
    if (!canSubmit || !data) return;
    setSubmitting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/intern/i983-evaluations/${data.evaluationId}/acknowledge`, {
        studentTypedSignature: typedName.trim(),
        internResponse: response.trim() || null,
      });
      router.replace('/careers/intern/i983-evaluations');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to sign');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading && !data) {
    return <InternPageShell title="I-983"><div className="h-32 animate-pulse rounded-lg bg-slate-100" /></InternPageShell>;
  }
  if (err || !data) {
    return <InternPageShell title="I-983"><p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err ?? 'Not found'}</p></InternPageShell>;
  }

  return (
    <InternPageShell
      title={`I-983 ${data.evaluationType.replaceAll('_', ' ')}`}
      subtitle="STEM OPT federal evaluation"
    >
      <p className="mb-3 text-xs text-slate-500">
        <Link
          href="/careers/intern/i983-evaluations"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          All I-983 evaluations
        </Link>
      </p>

      {data.status === 'AMENDED' && needsSign && (
        <div className="mb-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <strong>Updated I-983.</strong> Your evaluator has amended this form
          since you last signed. Please review the new content and re-sign below.
        </div>
      )}
      {data.acknowledgedAt && (
        <div className="mb-4 rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-900">
          <CheckCircle2 className="mr-1 inline h-4 w-4" />
          <strong>Signed on {new Date(data.acknowledgedAt).toLocaleString()}</strong>
          {data.studentTypedSignature && (
            <p
              className="mt-1 text-2xl text-slate-900"
              style={{ fontFamily: 'var(--font-dancing-script), "Brush Script MT", cursive' }}
            >
              {data.studentTypedSignature}
            </p>
          )}
        </div>
      )}
      {data.dsoSubmittedAt && (
        <div className="mb-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
          <strong>Submitted to your DSO</strong> on {new Date(data.dsoSubmittedAt).toLocaleDateString()}
          {data.dsoSubmissionMethod && ` via ${data.dsoSubmissionMethod.replaceAll('_', ' ').toLowerCase()}`}.
        </div>
      )}

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <header className="border-b border-slate-100 pb-2">
          <p className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-500">
            <FileText className="h-3.5 w-3.5" />
            Evaluation content
          </p>
          <p className="text-xs text-slate-600">
            From <strong>{data.evaluatorName ?? 'your Evaluator'}</strong>
            {data.publishedAt && ` · ${new Date(data.publishedAt).toLocaleDateString()}`}
            {data.periodStartDate && ` · Period ${data.periodStartDate}`}
            {data.periodEndDate && ` → ${data.periodEndDate}`}
          </p>
        </header>
        <Block title="Training Objectives Progress" text={data.trainingObjectivesProgress} />
        <Block title="Training Supervision Provided" text={data.trainingSupervisionProvided} />
        <Block title="Training Evaluation Outcomes" text={data.trainingEvaluationOutcomes} />
        {data.objectivesAchieved && <Block title="Objectives Achieved" text={data.objectivesAchieved} />}
        {data.supervisorAssessment && <Block title="Supervisor Assessment" text={data.supervisorAssessment} />}
      </section>

      {needsSign && (
        <section className="mt-4 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Sign and Acknowledge</h2>
          <p className="mt-1 text-xs text-slate-500">
            Type your full name to e-sign this I-983 evaluation. Your signature
            confirms you've reviewed the content.
          </p>

          <label className="mt-4 block">
            <span className="text-sm font-medium text-slate-800">Type your full name to sign</span>
            <input
              value={typedName}
              onChange={(e) => setTypedName(e.target.value)}
              maxLength={200}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </label>
          <div className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-4">
            <p className="text-[11px] uppercase tracking-wide text-slate-500">Your signature preview</p>
            <p
              className="mt-2 text-3xl text-slate-900"
              style={{
                fontFamily: 'var(--font-dancing-script), "Brush Script MT", cursive',
                minHeight: '2.5rem',
              }}
            >
              {typedName || <span className="text-slate-300">—</span>}
            </p>
          </div>

          <label className="mt-3 block">
            <span className="text-sm font-medium text-slate-800">Optional note to your evaluator</span>
            <textarea
              value={response}
              onChange={(e) => setResponse(e.target.value)}
              rows={3}
              maxLength={2000}
              placeholder="Anything you'd like to share alongside your signature?"
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>

          <label className="mt-3 flex items-start gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={agreed}
              onChange={(e) => setAgreed(e.target.checked)}
              className="mt-0.5 h-4 w-4 cursor-pointer rounded border-slate-300 text-brand-600 focus:ring-brand-500"
            />
            <span>I have read and reviewed the I-983 evaluation above.</span>
          </label>

          {err && (
            <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>
          )}

          <div className="mt-4 flex items-center justify-between">
            <p className="text-[11px] text-slate-500">
              {typedName.trim().length === 0
                ? 'Type your name to enable signing.'
                : !agreed
                  ? 'Tick the agreement to enable signing.'
                  : 'Ready to sign.'}
            </p>
            <button
              type="button"
              onClick={submit}
              disabled={!canSubmit}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              {submitting ? 'Signing…' : 'Sign and Acknowledge'}
            </button>
          </div>
        </section>
      )}
    </InternPageShell>
  );
}

function Block({ title, text }: { title: string; text: string | null }) {
  if (!text) return null;
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">{title}</p>
      <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{text}</p>
    </div>
  );
}
