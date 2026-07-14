'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ChevronLeft, FileText, Save, Send } from 'lucide-react';
import api from '@/lib/api';
import type { EvaluatorI983Detail } from '@/components/evaluator/types';

export default function ComposeI983Page() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = params?.id;

  const [data, setData] = useState<EvaluatorI983Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [progress, setProgress] = useState('');
  const [supervision, setSupervision] = useState('');
  const [outcomes, setOutcomes] = useState('');
  const [objectives, setObjectives] = useState('');
  const [supervisorAssessment, setSupervisorAssessment] = useState('');

  const [savingDraft, setSavingDraft] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<EvaluatorI983Detail>(`/api/v1/evaluator/i983-evaluations/${id}`);
      setData(res.data);
      setProgress(res.data.trainingObjectivesProgress ?? '');
      setSupervision(res.data.trainingSupervisionProvided ?? '');
      setOutcomes(res.data.trainingEvaluationOutcomes ?? '');
      setObjectives(res.data.objectivesAchieved ?? '');
      setSupervisorAssessment(res.data.supervisorAssessment ?? '');
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  function body() {
    return {
      trainingObjectivesProgress: progress.trim() || null,
      trainingSupervisionProvided: supervision.trim() || null,
      trainingEvaluationOutcomes: outcomes.trim() || null,
      objectivesAchieved: objectives.trim() || null,
      supervisorAssessment: supervisorAssessment.trim() || null,
    };
  }

  const canPublish = progress.trim().length >= 100
    && supervision.trim().length >= 100
    && outcomes.trim().length >= 100;

  async function saveDraft() {
    setSavingDraft(true);
    setErr(null);
    try {
      await api.patch(`/api/v1/evaluator/i983-evaluations/${id}/draft`, body());
      setSavedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally {
      setSavingDraft(false);
    }
  }

  async function publish() {
    if (!canPublish) return;
    setPublishing(true);
    setErr(null);
    try {
      await api.patch(`/api/v1/evaluator/i983-evaluations/${id}/draft`, body());
      await api.post(`/api/v1/evaluator/i983-evaluations/${id}/publish`);
      router.push(`/careers/evaluator/i983-evaluations/${id}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to publish');
      setConfirmOpen(false);
    } finally {
      setPublishing(false);
    }
  }

  if (loading && !data) return <div className="mx-auto max-w-5xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>;
  if (err && !data) return <div className="mx-auto max-w-5xl p-6"><p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p></div>;
  if (!data) return null;

  const readOnly = ['PUBLISHED', 'ACKNOWLEDGED', 'AMENDED'].includes(data.status);
  const plan = data.planContext;

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

      {/* Section 1: Intern + Plan Context */}
      <section className="rounded-lg border border-amber-200 bg-amber-50/20 p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">
              I-983 {data.evaluationType.replaceAll('_', ' ')} — {data.internName ?? '(unnamed)'}
            </h1>
            <p className="text-xs text-slate-500">
              {data.employeeId}{plan?.universityName && ` · ${plan.universityName}`}
            </p>
            <p className="mt-1 text-xs text-slate-700">
              Period: {data.periodStartDate ?? '—'}
              {data.periodEndDate && ` → ${data.periodEndDate}`}
            </p>
          </div>
        </div>
        {plan?.trainingGoalsAndObjectives && (
          <div className="mt-3 rounded-md bg-white p-3">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Plan training goals + objectives
            </p>
            <p className="mt-1 whitespace-pre-wrap text-xs text-slate-700">
              {plan.trainingGoalsAndObjectives}
            </p>
          </div>
        )}
        {readOnly && (
          <p className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
            This I-983 is {data.status}. Use the detail page to amend or mark DSO submission.
          </p>
        )}
      </section>

      {/* Section 2: Training Objectives Progress */}
      <Section title="2. Training Objectives Progress"
        hint={`${progress.trim().length}/100 chars min — score the intern against the plan's training_goals_and_objectives`}
        ok={progress.trim().length >= 100}>
        <textarea
          value={progress}
          onChange={(e) => setProgress(e.target.value)}
          rows={6}
          maxLength={10000}
          disabled={readOnly}
          className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
      </Section>

      {/* Section 3: Training Supervision Provided */}
      <Section title="3. Training Supervision Provided"
        hint={`${supervision.trim().length}/100 chars min`}
        ok={supervision.trim().length >= 100}>
        <textarea
          value={supervision}
          onChange={(e) => setSupervision(e.target.value)}
          rows={5}
          maxLength={10000}
          disabled={readOnly}
          placeholder="How supervision was provided this period — frequency of check-ins, mentorship structure, formal training sessions, etc."
          className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
      </Section>

      {/* Section 4: Training Evaluation Outcomes */}
      <Section title="4. Training Evaluation Outcomes"
        hint={`${outcomes.trim().length}/100 chars min`}
        ok={outcomes.trim().length >= 100}>
        <textarea
          value={outcomes}
          onChange={(e) => setOutcomes(e.target.value)}
          rows={5}
          maxLength={10000}
          disabled={readOnly}
          placeholder="What the trainee has learned, skills gained, overall performance against the training plan."
          className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
      </Section>

      {/* Section 5: Objectives Achieved (per-plan-objective notes) */}
      <Section title="5. Objectives Achieved (per-objective notes)" hint="Optional" ok>
        <textarea
          value={objectives}
          onChange={(e) => setObjectives(e.target.value)}
          rows={4}
          maxLength={10000}
          disabled={readOnly}
          placeholder="Per-objective progress notes if useful. Free text mirroring the plan structure."
          className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
      </Section>

      {/* Section 6: Supervisor Assessment */}
      <Section title="6. Supervisor Assessment" hint="Optional" ok>
        <textarea
          value={supervisorAssessment}
          onChange={(e) => setSupervisorAssessment(e.target.value)}
          rows={4}
          maxLength={5000}
          disabled={readOnly}
          placeholder="Direct supervisor's perspective on progress and recommendations."
          className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
      </Section>

      {/* Signature block */}
      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="inline-flex items-center gap-1.5 text-sm font-semibold text-slate-900">
          <FileText className="h-3.5 w-3.5" />
          Signature Block
        </h2>
        <p className="mt-2 text-xs text-slate-700">
          By clicking <strong>Publish</strong> below, you certify the above
          I-983 evaluation is accurate. Your name and timestamp will be captured
          as the employer representative signature. The form will then be sent
          to the intern for signature and routed for your DSO submission.
        </p>
      </section>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {!readOnly && (
        <div className="sticky bottom-4 flex items-center justify-between rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm">
          <p className="text-[11px] text-slate-500">
            {savedAt
              ? <span className="text-green-700">Saved at {savedAt.toLocaleTimeString()}</span>
              : <span>Required: 3 sections × 100+ chars before Publish</span>}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={saveDraft}
              disabled={savingDraft}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              <Save className="h-3.5 w-3.5" />
              {savingDraft ? 'Saving…' : 'Save draft'}
            </button>
            <button
              type="button"
              onClick={() => setConfirmOpen(true)}
              disabled={!canPublish || publishing}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
            >
              <Send className="h-3.5 w-3.5" />
              {publishing ? 'Publishing…' : 'Publish'}
            </button>
          </div>
        </div>
      )}

      {confirmOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
            <h3 className="text-base font-semibold text-slate-900">Publish I-983?</h3>
            <p className="mt-2 text-sm text-slate-600">
              This sends the I-983 to the intern for review and signature. After
              they sign, you'll need to submit the form to their DSO within
              <strong> 10 days</strong>. You can amend it later if needed.
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmOpen(false)}
                className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={publish}
                disabled={publishing}
                className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
              >
                {publishing ? 'Publishing…' : 'Publish I-983'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Section({ title, hint, ok, children }: {
  title: string;
  hint: string;
  ok: boolean;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
        <span className={
          'text-[11px] ' + (ok ? 'text-green-700' : 'text-red-700')
        }>{hint}</span>
      </div>
      <div className="mt-2">{children}</div>
    </section>
  );
}
