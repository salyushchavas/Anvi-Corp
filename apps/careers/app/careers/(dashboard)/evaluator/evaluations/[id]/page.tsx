'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ChevronLeft, Pencil, Save, Star, X } from 'lucide-react';
import api from '@/lib/api';
import type { EvaluatorEvaluationDetail, Recommendation } from '@/components/evaluator/types';
import { RECOMMENDATIONS } from '@/components/evaluator/types';

export default function EvaluationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [data, setData] = useState<EvaluatorEvaluationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [amendOpen, setAmendOpen] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<EvaluatorEvaluationDetail>(
        `/api/v1/evaluator/evaluations/${id}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  if (loading && !data) {
    return <div className="mx-auto max-w-5xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err || !data) {
    return <div className="mx-auto max-w-5xl p-6"><p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err ?? 'Not found'}</p></div>;
  }

  const canAmend = ['PUBLISHED', 'ACKNOWLEDGED', 'AMENDED'].includes(data.status);

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href={`/careers/evaluator/evaluees/${data.internLifecycleId}`}
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Back to evaluee
        </Link>
      </p>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">
              {data.internName ?? '(unnamed)'} — {data.evaluationType}
            </h1>
            <p className="text-xs text-slate-500">
              {data.employeeId} {data.technology && `· ${data.technology}`}
              {data.periodStart && ` · Period ${data.periodStart} → ${data.periodEnd}`}
            </p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <StatusPill status={data.status} />
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
                v{data.version}
              </span>
              {data.publishedAt && (
                <span className="text-[11px] text-slate-500">
                  Published {new Date(data.publishedAt).toLocaleString()}
                </span>
              )}
            </div>
          </div>
          {canAmend && (
            <button
              type="button"
              onClick={() => setAmendOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
            >
              <Pencil className="h-3.5 w-3.5" />
              Amend evaluation
            </button>
          )}
        </div>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Rubric</h2>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <RubricBar label="Technical Skills" value={data.technicalSkillsScore} />
          <RubricBar label="Communication" value={data.communicationScore} />
          <RubricBar label="Professionalism" value={data.professionalismScore} />
          <RubricBar label="Learning Application" value={data.learningApplicationScore} />
        </div>
        <p className="mt-3 text-sm text-slate-700">
          <strong>Average:</strong>{' '}
          <span className="tabular-nums">{data.averageScore != null ? data.averageScore.toFixed(2) : '—'}</span> / 5
        </p>
        {data.recommendation && (
          <p className="mt-1 inline-block rounded-md bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-800">
            Recommendation: {data.recommendation.replaceAll('_', ' ')}
          </p>
        )}
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Feedback</h2>
        {data.strengths && <Block label="Strengths" text={data.strengths} />}
        {data.areasForImprovement && <Block label="Areas for improvement" text={data.areasForImprovement} />}
        {data.comments && <Block label="Comments" text={data.comments} />}
      </section>

      {data.internalNotes && (
        <section className="rounded-lg border border-amber-200 bg-amber-50/30 p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-amber-900">Internal notes (Evaluator only)</h2>
          <p className="mt-2 whitespace-pre-wrap text-xs text-slate-700">{data.internalNotes}</p>
        </section>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-900">Intern acknowledgment</h2>
        {data.internAcknowledgedAt ? (
          <div>
            <p className="mt-2 text-xs text-green-700">
              Acknowledged {new Date(data.internAcknowledgedAt).toLocaleString()}
            </p>
            {data.internResponse && (
              <p className="mt-2 whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-xs text-slate-700">
                {data.internResponse}
              </p>
            )}
          </div>
        ) : (
          <p className="mt-2 text-xs text-amber-700">
            Awaiting intern acknowledgment.
          </p>
        )}
      </section>

      {data.amendments.length > 0 && (
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Amendment history</h2>
          <ul className="mt-3 space-y-2">
            {data.amendments.map((a) => (
              <li key={a.amendmentId} className="rounded-md border border-slate-100 bg-slate-50 p-3 text-xs">
                <p className="font-semibold text-slate-800">
                  v{a.previousVersion} → v{a.newVersion}
                  <span className="ml-2 text-slate-500">
                    by {a.amendedByName ?? 'unknown'} on {new Date(a.amendedAt).toLocaleString()}
                  </span>
                </p>
                <p className="mt-1 text-slate-700">{a.amendmentReason}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {amendOpen && (
        <AmendModal
          data={data}
          onClose={() => setAmendOpen(false)}
          onSaved={() => { setAmendOpen(false); void load(); }}
        />
      )}
    </div>
  );
}

function StatusPill({ status }: { status: string }) {
  const tone = status === 'PUBLISHED' ? 'bg-amber-100 text-amber-800'
    : status === 'ACKNOWLEDGED' ? 'bg-green-100 text-green-800'
    : status === 'AMENDED' ? 'bg-amber-100 text-amber-800'
    : 'bg-slate-100 text-slate-700';
  return <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${tone}`}>{status}</span>;
}

function RubricBar({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded-md border border-slate-100 bg-slate-50 p-3">
      <p className="text-[10px] uppercase text-slate-500">{label}</p>
      <div className="mt-1 flex items-center gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <Star
            key={n}
            className={
              'h-3.5 w-3.5 ' +
              (value != null && n <= value
                ? 'fill-amber-400 text-amber-400'
                : 'text-slate-300')
            }
          />
        ))}
        <span className="ml-1 text-xs font-semibold text-slate-900">{value ?? '—'}/5</span>
      </div>
    </div>
  );
}

function Block({ label, text }: { label: string; text: string }) {
  return (
    <div className="mt-3">
      <p className="text-[10px] uppercase text-slate-500">{label}</p>
      <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{text}</p>
    </div>
  );
}

function AmendModal({
  data, onClose, onSaved,
}: {
  data: EvaluatorEvaluationDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [reason, setReason] = useState('');
  const [technical, setTechnical] = useState(data.technicalSkillsScore);
  const [communication, setCommunication] = useState(data.communicationScore);
  const [professionalism, setProfessionalism] = useState(data.professionalismScore);
  const [learning, setLearning] = useState(data.learningApplicationScore);
  const [strengths, setStrengths] = useState(data.strengths ?? '');
  const [areas, setAreas] = useState(data.areasForImprovement ?? '');
  const [comments, setComments] = useState(data.comments ?? '');
  const [recommendation, setRecommendation] = useState<Recommendation | ''>(
    (data.recommendation as Recommendation | null) ?? '',
  );
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const canSubmit = reason.trim().length >= 30;

  async function submit() {
    if (!canSubmit) {
      setErr('Amendment reason must be at least 30 characters.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/evaluator/evaluations/${data.evaluationId}/amend`, {
        amendmentReason: reason.trim(),
        technicalSkillsScore: technical,
        communicationScore: communication,
        professionalismScore: professionalism,
        learningApplicationScore: learning,
        strengths: strengths.trim() || null,
        areasForImprovement: areas.trim() || null,
        comments: comments.trim() || null,
        recommendation: recommendation || null,
      });
      onSaved();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to amend');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Amend evaluation</h3>
            <p className="text-xs text-slate-500">
              Version will increment to v{data.version + 1}. Intern's acknowledgment
              resets and they will receive a re-acknowledge notification.
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3">
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">
              Amendment reason <span className="text-red-600">*</span>{' '}
              <span className="text-[10px] text-slate-500">
                ({reason.trim().length}/30 min)
              </span>
            </span>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              maxLength={1000}
              placeholder="Explain what's changing and why."
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>

          <div className="grid grid-cols-2 gap-3">
            <ScoreField label="Technical" value={technical} onChange={setTechnical} />
            <ScoreField label="Communication" value={communication} onChange={setCommunication} />
            <ScoreField label="Professionalism" value={professionalism} onChange={setProfessionalism} />
            <ScoreField label="Learning" value={learning} onChange={setLearning} />
          </div>

          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Strengths</span>
            <textarea value={strengths} onChange={(e) => setStrengths(e.target.value)}
              rows={3} maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Areas for improvement</span>
            <textarea value={areas} onChange={(e) => setAreas(e.target.value)}
              rows={3} maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Comments</span>
            <textarea value={comments} onChange={(e) => setComments(e.target.value)}
              rows={2} maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>

          <div>
            <p className="text-xs font-semibold text-slate-700">Recommendation</p>
            <div className="mt-1 flex flex-wrap gap-1">
              {RECOMMENDATIONS.map((r) => (
                <button
                  key={r}
                  type="button"
                  onClick={() => setRecommendation(r)}
                  className={
                    'rounded-full border px-2.5 py-0.5 text-[11px] font-medium ' +
                    (recommendation === r
                      ? 'border-brand-700 bg-brand-700 text-white'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50')
                  }
                >
                  {r.replaceAll('_', ' ')}
                </button>
              ))}
            </div>
          </div>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={!canSubmit || submitting}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            <Save className="h-3.5 w-3.5" />
            {submitting ? 'Saving…' : 'Submit amendment'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ScoreField({
  label, value, onChange,
}: {
  label: string;
  value: number | null;
  onChange: (v: number) => void;
}) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      <div className="mt-1 flex gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            onClick={() => onChange(n)}
            className={
              'inline-flex h-7 w-7 items-center justify-center rounded-md border text-xs font-semibold ' +
              (value === n
                ? 'border-brand-700 bg-brand-700 text-white'
                : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-100')
            }
          >
            {n}
          </button>
        ))}
      </div>
    </label>
  );
}
