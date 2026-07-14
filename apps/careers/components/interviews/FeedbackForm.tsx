'use client';

import { useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import RatingSelector from './RatingSelector';
import type {
  InterviewRecommendation,
  InterviewResponse,
  SubmitScorecardRequest,
} from '@/types';

interface Props {
  interviewId: string;
  initial?: Partial<{
    technicalRating: number;
    communicationRating: number;
    problemSolvingRating: number;
    comments: string;
    recommendation: InterviewRecommendation;
  }>;
  onSubmitted: (updated: InterviewResponse) => void;
  onCancel?: () => void;
}

const RECOMMENDATIONS: { value: InterviewRecommendation; label: string }[] = [
  { value: 'STRONG_HIRE', label: 'Strong Hire' },
  { value: 'HIRE', label: 'Hire' },
  { value: 'NO_HIRE', label: 'No Hire' },
  { value: 'STRONG_NO_HIRE', label: 'Strong No Hire' },
];

/**
 * Phase 2.2 — structured interview scorecard. Replaces the freeform overall-
 * rating + strengths/concerns flow with three dimension ratings (technical /
 * communication / problemSolving), a required recommendation, and a single
 * comments box. The backend computes overallRating as the rounded average so
 * existing surfaces (recruiter table, dashboard) keep showing a single score.
 */
export default function FeedbackForm({
  interviewId,
  initial,
  onSubmitted,
  onCancel,
}: Props) {
  const [technical, setTechnical] = useState<number | null>(
    initial?.technicalRating ?? null,
  );
  const [communication, setCommunication] = useState<number | null>(
    initial?.communicationRating ?? null,
  );
  const [problemSolving, setProblemSolving] = useState<number | null>(
    initial?.problemSolvingRating ?? null,
  );
  const [comments, setComments] = useState(initial?.comments ?? '');
  const [recommendation, setRecommendation] = useState<InterviewRecommendation | null>(
    initial?.recommendation ?? null,
  );

  const [submitting, setSubmitting] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFieldError(null);

    if (technical === null) {
      setFieldError('Technical rating is required.');
      return;
    }
    if (communication === null) {
      setFieldError('Communication rating is required.');
      return;
    }
    if (problemSolving === null) {
      setFieldError('Problem-solving rating is required.');
      return;
    }
    if (!recommendation) {
      setFieldError('Pick a recommendation.');
      return;
    }

    const body: SubmitScorecardRequest = {
      technicalRating: technical,
      communicationRating: communication,
      problemSolvingRating: problemSolving,
      recommendation,
      comments: comments.trim() || undefined,
    };

    setSubmitting(true);
    try {
      const res = await api.post<InterviewResponse>(
        `/api/v1/interviews/${interviewId}/scorecard`,
        body,
      );
      toast.success('Scorecard submitted');
      onSubmitted(res.data);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Could not submit scorecard';
      toast.error(msg);
      setFieldError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      <RatingSelector
        value={technical}
        onChange={setTechnical}
        label="Technical rating *"
      />

      <RatingSelector
        value={communication}
        onChange={setCommunication}
        label="Communication rating *"
      />

      <RatingSelector
        value={problemSolving}
        onChange={setProblemSolving}
        label="Problem-solving rating *"
      />

      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-700">
          Comments
        </label>
        <textarea
          value={comments}
          onChange={(e) => setComments(e.target.value)}
          rows={5}
          placeholder="Strengths, concerns, follow-ups, anything the recruiter should know…"
          className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
        />
      </div>

      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">
          Recommendation *
        </label>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {RECOMMENDATIONS.map((opt) => {
            const active = recommendation === opt.value;
            return (
              <button
                key={opt.value}
                type="button"
                onClick={() => setRecommendation(opt.value)}
                className={
                  'rounded-md border px-3 py-2 text-sm font-medium transition-colors ' +
                  (active
                    ? 'border-primary-700 bg-primary-700 text-white'
                    : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50')
                }
                aria-pressed={active}
              >
                {opt.label}
              </button>
            );
          })}
        </div>
      </div>

      {fieldError && (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {fieldError}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 pt-2">
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Cancel
          </button>
        )}
        <button
          type="submit"
          disabled={submitting}
          className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
        >
          {submitting ? 'Submitting…' : 'Submit scorecard'}
        </button>
      </div>
    </form>
  );
}
