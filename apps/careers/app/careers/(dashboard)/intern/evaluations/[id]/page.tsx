'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { AlertTriangle, CheckCircle2, ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';

interface InternEvaluation {
  id: string;
  evaluationType: string;
  status: 'PUBLISHED' | 'ACKNOWLEDGED' | 'AMENDED';
  overallScore?: number | null;
  technicalSkillsScore?: number | null;
  communicationScore?: number | null;
  professionalismScore?: number | null;
  learningApplicationScore?: number | null;
  strengthsNarrative?: string | null;
  areasForImprovementNarrative?: string | null;
  improvementPlan?: string | null;
  internAcknowledgedAt?: string | null;
  internResponse?: string | null;
  publishedAt?: string | null;
  amendedAt?: string | null;
  amendmentReason?: string | null;
  version: number;
  linkedProjectId?: string | null;
}

const TYPE_LABEL: Record<string, string> = {
  MONTHLY: 'Monthly',
  POST_PROJECT: 'Post-Project',
  STEM_OPT_12_MONTH: 'STEM OPT — 12 Month',
  STEM_OPT_24_MONTH: 'STEM OPT — 24 Month',
  FINAL: 'Final',
};

const MAX_RESPONSE = 2000;

export default function InternEvaluationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [eval_, setEval] = useState<InternEvaluation | null>(null);
  const [response, setResponse] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InternEvaluation>(`/api/v1/evaluation-cycle/${id}`);
      setEval(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load evaluation');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function acknowledge() {
    if (!eval_) return;
    setSubmitting(true);
    try {
      await api.post(`/api/v1/evaluation-cycle/${eval_.id}/acknowledge`, {
        response: response.trim() || null,
      });
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string; message?: string } } };
      setErr(ax.response?.data?.error
        ?? ax.response?.data?.message
        ?? (e instanceof Error ? e.message : 'Acknowledge failed'));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <InternPageShell title="Evaluation">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !eval_) {
    return (
      <InternPageShell title="Evaluation">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Evaluation not found'}
        </p>
      </InternPageShell>
    );
  }

  const title = `${TYPE_LABEL[eval_.evaluationType] ?? eval_.evaluationType} — Published ${
    eval_.publishedAt ? new Date(eval_.publishedAt).toLocaleDateString() : 'recently'
  }`;
  const needsAck = !eval_.internAcknowledgedAt
    && (eval_.status === 'PUBLISHED' || eval_.status === 'AMENDED');

  return (
    <InternPageShell title={title}>
      <Link
        href="/careers/intern/evaluations"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" />
        All evaluations
      </Link>

      {eval_.amendedAt && eval_.amendmentReason && (
        <div className="mb-5 rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          <div className="mb-1 inline-flex items-center gap-1 font-semibold">
            <AlertTriangle className="h-4 w-4" />
            Amended {new Date(eval_.amendedAt).toLocaleDateString()} (v{eval_.version})
          </div>
          <p className="whitespace-pre-wrap">{eval_.amendmentReason}</p>
        </div>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
          Rubric scores
        </h2>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <ScoreBar label="Overall" value={eval_.overallScore} />
          <ScoreBar label="Technical Skills" value={eval_.technicalSkillsScore} />
          <ScoreBar label="Communication" value={eval_.communicationScore} />
          <ScoreBar label="Professionalism" value={eval_.professionalismScore} />
          <ScoreBar label="Learning Application" value={eval_.learningApplicationScore} />
        </div>
      </section>

      <Section title="Strengths" body={eval_.strengthsNarrative} />
      <Section title="Areas for improvement" body={eval_.areasForImprovementNarrative} />
      <Section title="Improvement plan" body={eval_.improvementPlan} />

      {needsAck ? (
        <section className="mt-6 rounded-lg border border-brand-200 bg-brand-50 p-5">
          <h3 className="text-sm font-semibold text-brand-900">Acknowledge this evaluation</h3>
          <p className="mt-1 text-xs text-brand-800">
            Add an optional response, then acknowledge to confirm you've read the feedback.
          </p>
          <div className="mt-3">
            <textarea
              value={response}
              onChange={(e) => setResponse(e.target.value)}
              maxLength={MAX_RESPONSE}
              rows={4}
              placeholder="Optional response to your evaluator…"
              className="w-full resize-y rounded-md border border-slate-200 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
            <div className="mt-1 text-right text-[11px] text-slate-400">
              {response.length}/{MAX_RESPONSE}
            </div>
          </div>
          <button
            type="button"
            onClick={acknowledge}
            disabled={submitting}
            className="mt-3 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Submitting…' : 'Acknowledge'}
          </button>
        </section>
      ) : eval_.internAcknowledgedAt && (
        <section className="mt-6 rounded-lg border border-green-200 bg-green-50 p-5 text-sm text-green-900">
          <div className="inline-flex items-center gap-1 font-semibold">
            <CheckCircle2 className="h-4 w-4" />
            Acknowledged on {new Date(eval_.internAcknowledgedAt).toLocaleString()}
          </div>
          {eval_.internResponse && (
            <p className="mt-2 whitespace-pre-wrap">{eval_.internResponse}</p>
          )}
        </section>
      )}
    </InternPageShell>
  );
}

function Section({ title, body }: { title: string; body?: string | null }) {
  if (!body) return null;
  return (
    <section className="mt-6 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
      <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">{body}</p>
    </section>
  );
}

function ScoreBar({ label, value }: { label: string; value?: number | null }) {
  const v = typeof value === 'number' ? Math.max(0, Math.min(10, value)) : null;
  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between">
        <span className="text-xs text-slate-500">{label}</span>
        <span className="text-sm font-semibold text-slate-900">
          {v === null ? '—' : `${v}/10`}
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
        {v !== null && (
          <div
            className="h-full rounded-full bg-brand-600 transition-all"
            style={{ width: `${(v / 10) * 100}%` }}
            aria-hidden
          />
        )}
      </div>
    </div>
  );
}
