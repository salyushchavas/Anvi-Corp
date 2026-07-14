'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';

interface Props {
  onSubmitted?: () => void;
}

const RATING_FIELDS: Array<{ key: keyof Ratings; label: string }> = [
  { key: 'overallRating', label: 'Overall experience' },
  { key: 'learningRating', label: 'Learning opportunities' },
  { key: 'mentorshipRating', label: 'Mentorship & guidance' },
  { key: 'workEnvironmentRating', label: 'Work environment' },
];

interface Ratings {
  overallRating: number;
  learningRating: number;
  mentorshipRating: number;
  workEnvironmentRating: number;
}

/** Phase 8 — one-time exit feedback survey. POSTs to /api/v1/exit/feedback. */
export default function ExitFeedbackForm({ onSubmitted }: Props) {
  const router = useRouter();
  const [ratings, setRatings] = useState<Ratings>({
    overallRating: 0,
    learningRating: 0,
    mentorshipRating: 0,
    workEnvironmentRating: 0,
  });
  const [whatWentWell, setWhatWentWell] = useState('');
  const [whatCouldImprove, setWhatCouldImprove] = useState('');
  const [wouldRecommend, setWouldRecommend] = useState<boolean | null>(null);
  const [additionalComments, setAdditionalComments] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function setRating(key: keyof Ratings, v: number) {
    setRatings((r) => ({ ...r, [key]: v }));
  }

  async function submit() {
    setErr(null);
    if (Object.values(ratings).some((v) => v < 1 || v > 5)) {
      setErr('Please rate all four categories (1–5).');
      return;
    }
    if (whatWentWell.trim().length < 50) {
      setErr('"What went well" must be at least 50 characters.');
      return;
    }
    if (whatCouldImprove.trim().length < 50) {
      setErr('"What could improve" must be at least 50 characters.');
      return;
    }
    if (wouldRecommend === null) {
      setErr('Please answer the recommendation question.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post('/api/v1/exit/feedback', {
        ...ratings,
        whatWentWell: whatWentWell.trim(),
        whatCouldImprove: whatCouldImprove.trim(),
        wouldRecommend,
        additionalComments: additionalComments.trim() || null,
      });
      onSubmitted?.();
      router.push('/careers/intern/exit/summary');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Submission failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="space-y-6">
      {RATING_FIELDS.map((f) => (
        <div key={f.key} className="rounded-md border border-slate-200 bg-white p-4">
          <div className="flex items-center justify-between">
            <label className="text-sm font-medium text-slate-800">{f.label}</label>
            <span className="text-xs text-slate-500">
              {ratings[f.key] ? `${ratings[f.key]} / 5` : 'Not rated'}
            </span>
          </div>
          <div className="mt-2 flex gap-1">
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                type="button"
                onClick={() => setRating(f.key, n)}
                className={
                  'h-9 w-9 rounded-md border text-sm font-semibold transition-colors ' +
                  (ratings[f.key] >= n
                    ? 'border-brand-600 bg-brand-600 text-white'
                    : 'border-slate-200 text-slate-700 hover:bg-slate-50')
                }
              >
                {n}
              </button>
            ))}
          </div>
        </div>
      ))}

      <div className="rounded-md border border-slate-200 bg-white p-4">
        <label className="text-sm font-medium text-slate-800" htmlFor="went-well">
          What went well? <span className="text-xs text-slate-500">(min 50 characters)</span>
        </label>
        <textarea
          id="went-well"
          value={whatWentWell}
          onChange={(e) => setWhatWentWell(e.target.value)}
          rows={4}
          maxLength={5000}
          className="mt-2 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
        />
        <div className="mt-1 text-right text-[11px] text-slate-500">
          {whatWentWell.trim().length} characters
        </div>
      </div>

      <div className="rounded-md border border-slate-200 bg-white p-4">
        <label className="text-sm font-medium text-slate-800" htmlFor="could-improve">
          What could improve? <span className="text-xs text-slate-500">(min 50 characters)</span>
        </label>
        <textarea
          id="could-improve"
          value={whatCouldImprove}
          onChange={(e) => setWhatCouldImprove(e.target.value)}
          rows={4}
          maxLength={5000}
          className="mt-2 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
        />
        <div className="mt-1 text-right text-[11px] text-slate-500">
          {whatCouldImprove.trim().length} characters
        </div>
      </div>

      <div className="rounded-md border border-slate-200 bg-white p-4">
        <span className="text-sm font-medium text-slate-800">
          Would you recommend this program to a friend?
        </span>
        <div className="mt-2 flex gap-2">
          {[
            { label: 'Yes', value: true },
            { label: 'No', value: false },
          ].map((o) => (
            <button
              key={o.label}
              type="button"
              onClick={() => setWouldRecommend(o.value)}
              className={
                'rounded-md border px-4 py-1.5 text-sm font-medium transition-colors ' +
                (wouldRecommend === o.value
                  ? 'border-brand-600 bg-brand-600 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {o.label}
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-md border border-slate-200 bg-white p-4">
        <label className="text-sm font-medium text-slate-800" htmlFor="comments">
          Additional comments <span className="text-xs text-slate-500">(optional, max 2000)</span>
        </label>
        <textarea
          id="comments"
          value={additionalComments}
          onChange={(e) => setAdditionalComments(e.target.value)}
          rows={3}
          maxLength={2000}
          className="mt-2 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
        />
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={submit}
          disabled={submitting}
          className="rounded-md bg-brand-700 px-5 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
        >
          {submitting ? 'Submitting…' : 'Submit feedback'}
        </button>
      </div>
    </div>
  );
}
