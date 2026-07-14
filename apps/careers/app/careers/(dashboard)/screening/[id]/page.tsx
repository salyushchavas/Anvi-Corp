'use client';

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import toast from 'react-hot-toast';
import { CheckCircle2 } from 'lucide-react';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import type {
  ScreeningCandidateResponse,
  ScreeningSubmitRequest,
  ScreeningSummaryResponse,
  Uuid,
} from '@/types';

export default function CandidateScreeningPage() {
  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <DashboardLayout title="Complete screening">
        <ScreeningForm />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function ScreeningForm() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const id = typeof params?.id === 'string' ? params.id : null;

  const [data, setData] = useState<ScreeningCandidateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [answers, setAnswers] = useState<Record<Uuid, { choiceIndex?: number; freeText?: string }>>({});
  const [submitting, setSubmitting] = useState(false);
  // Confirmation state — populated once submit succeeds. We deliberately
  // keep showing the confirmation panel (instead of redirecting immediately)
  // so the candidate can see their score and any next-step messaging.
  const [done, setDone] = useState<ScreeningSummaryResponse | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setError(null);
    try {
      const res = await api.get<ScreeningCandidateResponse>(`/api/v1/screening/${id}`);
      setData(res.data);
      // If the screening was already submitted in another tab, the candidate
      // page should reflect that — render the done panel directly.
      if (res.data?.status === 'COMPLETED') {
        setDone({
          id: res.data.id,
          applicationId: '' as Uuid,
          status: 'COMPLETED',
          sentAt: res.data.sentAt,
          completedAt: res.data.completedAt,
        });
      }
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the screening.");
      setData(null);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const everyScorableAnswered = useMemo(() => {
    if (!data) return false;
    return data.questions
      .filter((q) => q.type === 'SINGLE_CHOICE')
      .every((q) => answers[q.id]?.choiceIndex != null);
  }, [data, answers]);

  function setChoice(qid: Uuid, choiceIndex: number) {
    setAnswers((curr) => ({ ...curr, [qid]: { ...curr[qid], choiceIndex } }));
  }

  function setFreeText(qid: Uuid, freeText: string) {
    setAnswers((curr) => ({ ...curr, [qid]: { ...curr[qid], freeText } }));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!data || submitting) return;
    setSubmitting(true);
    try {
      const body: ScreeningSubmitRequest = {
        answers: data.questions.map((q) => ({
          questionId: q.id,
          choiceIndex: answers[q.id]?.choiceIndex,
          freeText: answers[q.id]?.freeText,
        })),
      };
      const res = await api.post<ScreeningSummaryResponse>(
        `/api/v1/screening/${data.id}/submit`,
        body,
      );
      setDone(res.data);
      toast.success('Screening submitted.');
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Submission failed. Try again.';
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (error && !data) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        <p className="mb-2">{error}</p>
        <Link
          href="/careers/intern"
          className="rounded border border-red-300 px-3 py-1 text-xs font-medium hover:bg-red-100"
        >
          Back to dashboard
        </Link>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="space-y-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-24 animate-pulse rounded-lg border border-gray-200 bg-gray-50" />
        ))}
      </div>
    );
  }

  if (done) {
    return (
      <div className="mx-auto max-w-2xl rounded-lg border border-green-200 bg-green-50 p-8 text-center">
        <CheckCircle2 className="mx-auto mb-3 h-10 w-10 text-green-600" strokeWidth={2} />
        <h2 className="mb-2 text-lg font-semibold text-green-900">
          Thanks — your screening is in.
        </h2>
        <p className="mb-1 text-sm text-green-800">
          {data.jobPostingTitle ? `For ${data.jobPostingTitle}.` : ''} A recruiter will review and reach
          out with next steps.
        </p>
        {typeof done.score === 'number' && typeof done.maxScore === 'number' && done.maxScore > 0 && (
          <p className="mt-3 text-sm font-medium text-green-900">
            Score: {done.score} / {done.maxScore}
          </p>
        )}
        <Link
          href="/careers/intern"
          className="mt-5 inline-flex items-center justify-center rounded-full bg-gradient-to-r from-accent to-accent-dark px-5 py-2 text-sm font-semibold text-white shadow-glow-accent hover:shadow-glow-accent-lg"
        >
          Back to dashboard
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="mx-auto max-w-2xl space-y-6">
      <header>
        <h1 className="text-xl font-semibold text-gray-900">
          {data.jobPostingTitle
            ? `Screening for ${data.jobPostingTitle}`
            : 'Screening questionnaire'}
        </h1>
        {data.entityName && (
          <p className="mt-1 text-sm text-gray-600">{data.entityName}</p>
        )}
        <p className="mt-2 text-xs text-gray-500">
          Pick the best answer for the multiple-choice questions. Short-answer
          prompts are for the recruiter — no right or wrong answer.
        </p>
      </header>

      {data.questions.map((q, idx) => (
        <fieldset
          key={q.id}
          className="space-y-3 rounded-lg border border-gray-200 bg-white p-5"
        >
          <legend className="text-sm font-medium text-gray-900">
            {idx + 1}. {q.prompt}
          </legend>
          {q.type === 'SINGLE_CHOICE' && (q.choices ?? []).length > 0 && (
            <div className="space-y-2">
              {(q.choices ?? []).map((c, ci) => {
                const selected = answers[q.id]?.choiceIndex === ci;
                return (
                  <label
                    key={ci}
                    className={
                      'flex cursor-pointer items-center gap-3 rounded-md border px-3 py-2 text-sm transition-colors ' +
                      (selected
                        ? 'border-accent bg-accent/5 text-accent-dark'
                        : 'border-gray-300 bg-white hover:bg-gray-50')
                    }
                  >
                    <input
                      type="radio"
                      name={`q-${q.id}`}
                      checked={selected}
                      onChange={() => setChoice(q.id, ci)}
                      className="h-4 w-4 text-accent focus:ring-accent"
                    />
                    <span>{c}</span>
                  </label>
                );
              })}
            </div>
          )}
          {q.type === 'FREE_TEXT' && (
            <textarea
              value={answers[q.id]?.freeText ?? ''}
              onChange={(e) => setFreeText(q.id, e.target.value)}
              rows={3}
              className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              placeholder="Your answer (optional)"
            />
          )}
        </fieldset>
      ))}

      <button
        type="submit"
        disabled={submitting || !everyScorableAnswered}
        className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-5 py-2.5 text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
      >
        {submitting ? 'Submitting…' : 'Submit screening'}
      </button>
      {!everyScorableAnswered && (
        <p className="text-center text-xs text-gray-500">
          Pick an answer for every multiple-choice question to submit.
        </p>
      )}
    </form>
  );
}
