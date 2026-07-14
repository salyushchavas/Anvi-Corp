'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  FileText,
  Pencil,
  Save,
  Send,
  X,
} from 'lucide-react';
import api from '@/lib/api';
import type { EvaluatorI983Detail } from '@/components/evaluator/types';
import { DSO_SUBMISSION_METHODS } from '@/components/evaluator/types';

export default function I983DetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [data, setData] = useState<EvaluatorI983Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [amendOpen, setAmendOpen] = useState(false);
  const [dsoOpen, setDsoOpen] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<EvaluatorI983Detail>(`/api/v1/evaluator/i983-evaluations/${id}`);
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

  if (loading && !data) return <div className="mx-auto max-w-5xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>;
  if (err || !data) return <div className="mx-auto max-w-5xl p-6"><p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err ?? 'Not found'}</p></div>;

  const canAmend = ['PUBLISHED', 'ACKNOWLEDGED', 'AMENDED'].includes(data.status);
  const canMarkDso = ['ACKNOWLEDGED', 'AMENDED'].includes(data.status) && !data.dsoSubmittedAt;
  const ackAt = data.acknowledgedAt ? new Date(data.acknowledgedAt) : null;
  const daysSinceAck = ackAt ? Math.floor((Date.now() - ackAt.getTime()) / 86_400_000) : null;
  const dsoUrgent = canMarkDso && daysSinceAck != null && daysSinceAck > 7;

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

      <section className="rounded-lg border border-amber-200 bg-amber-50/30 p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">
              {data.internName ?? '(unnamed)'} — I-983 {data.evaluationType.replaceAll('_', ' ')}
            </h1>
            <p className="text-xs text-slate-500">
              {data.employeeId}
              {data.planContext?.universityName && ` · ${data.planContext.universityName}`}
              {data.periodStartDate && ` · ${data.periodStartDate}`}
              {data.periodEndDate && ` → ${data.periodEndDate}`}
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
          <div className="flex flex-wrap gap-2">
            {canMarkDso && (
              <button
                type="button"
                onClick={() => setDsoOpen(true)}
                className={
                  'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold ' +
                  (dsoUrgent
                    ? 'bg-red-700 text-white hover:bg-red-800'
                    : 'bg-brand-700 text-white hover:bg-brand-800')
                }
              >
                <Send className="h-3.5 w-3.5" />
                Mark Submitted to DSO
                {dsoUrgent && <span className="ml-1">· {daysSinceAck}d</span>}
              </button>
            )}
            {canAmend && (
              <button
                type="button"
                onClick={() => setAmendOpen(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                <Pencil className="h-3.5 w-3.5" />
                Amend
              </button>
            )}
          </div>
        </div>
      </section>

      <Block title="Training Objectives Progress" text={data.trainingObjectivesProgress} />
      <Block title="Training Supervision Provided" text={data.trainingSupervisionProvided} />
      <Block title="Training Evaluation Outcomes" text={data.trainingEvaluationOutcomes} />
      {data.objectivesAchieved && <Block title="Objectives Achieved (notes)" text={data.objectivesAchieved} />}
      {data.supervisorAssessment && <Block title="Supervisor Assessment" text={data.supervisorAssessment} />}

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="inline-flex items-center gap-1.5 text-sm font-semibold text-slate-900">
          <FileText className="h-3.5 w-3.5" />
          Signatures & DSO Submission
        </h2>
        <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
          <Row label="Evaluator (employer rep)"
            value={data.evaluatorName ?? '—'}
            sub={data.publishedAt ? new Date(data.publishedAt).toLocaleString() : 'unpublished'} />
          <Row label="Intern signature"
            value={data.studentTypedSignature ?? 'Not signed yet'}
            sub={data.acknowledgedAt ? new Date(data.acknowledgedAt).toLocaleString() : '—'} />
          <Row label="DSO submission"
            value={data.dsoSubmittedAt
              ? `${(data.dsoSubmissionMethod ?? '').replaceAll('_', ' ')}`
              : ackAt
                ? `Pending (${daysSinceAck}d since ack)`
                : 'Awaiting ack'}
            sub={data.dsoSubmittedAt
              ? new Date(data.dsoSubmittedAt).toLocaleString()
              : ackAt ? 'Federal 10-day window' : '—'} />
          <Row label="Plan supervisor"
            value={data.planContext?.supervisorName ?? '—'}
            sub={data.planContext?.supervisorEmail ?? ''} />
        </dl>
        {data.dsoSubmissionNotes && (
          <p className="mt-3 rounded-md bg-slate-50 p-3 text-xs text-slate-700">
            <strong>DSO submission notes:</strong> {data.dsoSubmissionNotes}
          </p>
        )}
        {data.internResponse && (
          <p className="mt-3 rounded-md bg-slate-50 p-3 text-xs text-slate-700">
            <strong>Intern response:</strong> {data.internResponse}
          </p>
        )}
      </section>

      {data.amendedAt && (
        <section className="rounded-lg border border-amber-200 bg-amber-50/30 p-4">
          <p className="text-xs font-semibold text-amber-900">
            Amended on {new Date(data.amendedAt).toLocaleString()}
          </p>
          {data.amendmentReason && (
            <p className="mt-1 text-xs text-amber-900">{data.amendmentReason}</p>
          )}
        </section>
      )}

      {amendOpen && (
        <AmendI983Modal
          data={data}
          onClose={() => setAmendOpen(false)}
          onSaved={() => { setAmendOpen(false); void load(); }}
        />
      )}
      {dsoOpen && (
        <MarkDsoSubmittedModal
          evaluationId={data.evaluationId}
          onClose={() => setDsoOpen(false)}
          onSaved={() => { setDsoOpen(false); void load(); }}
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

function Block({ title, text }: { title: string; text: string | null }) {
  if (!text) return null;
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">{text}</p>
    </section>
  );
}

function Row({ label, value, sub }: {
  label: string; value: string; sub?: string;
}) {
  return (
    <div className="rounded-md border border-slate-100 bg-slate-50 p-2">
      <p className="text-[10px] uppercase text-slate-500">{label}</p>
      <p className="text-sm font-medium text-slate-900">{value}</p>
      {sub && <p className="text-[10px] text-slate-500">{sub}</p>}
    </div>
  );
}

function AmendI983Modal({
  data, onClose, onSaved,
}: {
  data: EvaluatorI983Detail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [reason, setReason] = useState('');
  const [progress, setProgress] = useState(data.trainingObjectivesProgress ?? '');
  const [supervision, setSupervision] = useState(data.trainingSupervisionProvided ?? '');
  const [outcomes, setOutcomes] = useState(data.trainingEvaluationOutcomes ?? '');
  const [objectives, setObjectives] = useState(data.objectivesAchieved ?? '');
  const [supervisorAssessment, setSupervisorAssessment] = useState(data.supervisorAssessment ?? '');
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
      await api.post(`/api/v1/evaluator/i983-evaluations/${data.evaluationId}/amend`, {
        amendmentReason: reason.trim(),
        trainingObjectivesProgress: progress.trim() || null,
        trainingSupervisionProvided: supervision.trim() || null,
        trainingEvaluationOutcomes: outcomes.trim() || null,
        objectivesAchieved: objectives.trim() || null,
        supervisorAssessment: supervisorAssessment.trim() || null,
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
            <h3 className="text-base font-semibold text-slate-900">Amend I-983 evaluation</h3>
            <p className="text-xs text-slate-500">
              Version will increment to v{data.version + 1}. The intern's signature
              and any DSO submission status will reset — they'll need to re-sign,
              and you'll need to re-submit to DSO.
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto px-5 py-4">
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
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Training Objectives Progress</span>
            <textarea value={progress} onChange={(e) => setProgress(e.target.value)}
              rows={4} maxLength={10000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Training Supervision Provided</span>
            <textarea value={supervision} onChange={(e) => setSupervision(e.target.value)}
              rows={4} maxLength={10000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Training Evaluation Outcomes</span>
            <textarea value={outcomes} onChange={(e) => setOutcomes(e.target.value)}
              rows={4} maxLength={10000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Objectives Achieved</span>
            <textarea value={objectives} onChange={(e) => setObjectives(e.target.value)}
              rows={3} maxLength={10000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Supervisor Assessment</span>
            <textarea value={supervisorAssessment} onChange={(e) => setSupervisorAssessment(e.target.value)}
              rows={3} maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </label>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">Cancel</button>
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

function MarkDsoSubmittedModal({
  evaluationId, onClose, onSaved,
}: {
  evaluationId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [method, setMethod] = useState<string>('EMAIL_TO_DSO');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/evaluator/i983-evaluations/${evaluationId}/mark-dso-submitted`, {
        submissionMethod: method,
        submissionNotes: notes.trim() || null,
      });
      onSaved();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to record');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Mark Submitted to DSO</h3>
            <p className="text-xs text-slate-500">
              Record how and when you submitted the I-983 to the student's DSO.
              This satisfies the federal 10-day window.
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="space-y-3 p-5">
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Submission method *</span>
            <select
              value={method}
              onChange={(e) => setMethod(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              {DSO_SUBMISSION_METHODS.map((m) => (
                <option key={m} value={m}>{m.replaceAll('_', ' ')}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-xs font-semibold text-slate-700">Notes</span>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={4}
              maxLength={2000}
              placeholder="e.g. emailed to dso@university.edu with PDF attachment"
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">Cancel</button>
          <button type="button" onClick={submit} disabled={submitting}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            <CheckCircle2 className="h-3.5 w-3.5" />
            {submitting ? 'Saving…' : 'Mark submitted'}
          </button>
        </div>
      </div>
    </div>
  );
}
