'use client';

import { useEffect, useMemo, useState } from 'react';
import api from '@/lib/api';
import type { InterviewDetail } from './types';

interface Props {
  interview: InterviewDetail;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

/**
 * ERM Phase: Manager hire-approval gate. The ERM no longer sets the
 * hire decision (SELECTED/HOLD/REJECTED) here — that moved to the
 * Manager's Hire Approvals queue. This modal now submits the
 * scorecard + an optional recommendation only; the candidate then
 * shows "pending manager hire approval" until a manager decides.
 */
export default function CompleteInterviewModal({
  interview,
  open,
  onClose,
  onApplied,
}: Props) {
  const [technicalScore, setTechnicalScore] = useState<number | ''>('');
  const [communicationScore, setCommunicationScore] = useState<number | ''>('');
  const [culturalFitScore, setCulturalFitScore] = useState<number | ''>('');
  const [recommendation, setRecommendation] = useState('');
  const [applicantVisibleNotes, setApplicantVisibleNotes] = useState('');
  const [internalNotes, setInternalNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setTechnicalScore('');
    setCommunicationScore('');
    setCulturalFitScore('');
    setRecommendation('');
    setApplicantVisibleNotes('');
    setInternalNotes('');
    setErr(null);
  }, [open]);

  const missingFields = useMemo(() => {
    const missing: string[] = [];
    if (applicantVisibleNotes.trim().length < 20) {
      missing.push('Applicant-visible notes (≥ 20 chars)');
    }
    return missing;
  }, [applicantVisibleNotes]);
  const canSubmit = missingFields.length === 0 && !submitting;

  if (!open) return null;

  async function submit() {
    setErr(null);
    if (applicantVisibleNotes.trim().length < 20) {
      setErr('Applicant-visible notes must be at least 20 characters.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/api/v1/erm/interviews/${interview.id}/complete`, {
        technicalScore: technicalScore === '' ? null : technicalScore,
        communicationScore: communicationScore === '' ? null : communicationScore,
        culturalFitScore: culturalFitScore === '' ? null : culturalFitScore,
        overallRecommendation: recommendation || null,
        applicantVisibleNotes: applicantVisibleNotes.trim(),
        internalNotes: internalNotes.trim() || null,
      });
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Submission failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-lg bg-white shadow-xl">
        <div className="min-h-0 flex-1 overflow-y-auto p-6">
          <h2 className="text-lg font-semibold text-slate-900">
            Submit interview scorecard
          </h2>
          <p className="mt-1 text-xs text-slate-600">
            Submit your scores, recommendation, and applicant-visible notes.
            A Manager will review and decide Hire / No-Hire from the Hire
            Approvals queue.
          </p>

          <div className="mt-4 space-y-4">
            <div className="grid grid-cols-3 gap-2">
              <ScoreInput label="Technical" value={technicalScore} onChange={setTechnicalScore} />
              <ScoreInput label="Communication" value={communicationScore} onChange={setCommunicationScore} />
              <ScoreInput label="Cultural fit" value={culturalFitScore} onChange={setCulturalFitScore} />
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Overall recommendation (advisory)
              </label>
              <select
                value={recommendation}
                onChange={(e) => setRecommendation(e.target.value)}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                <option value="">No recommendation</option>
                <option value="HIRE">Hire</option>
                <option value="REJECT">Reject</option>
                <option value="HOLD">Hold</option>
              </select>
              <p className="mt-1 text-[11px] text-slate-500">
                Shown to the Manager when they decide. Advisory only —
                the binding Hire/Reject/Hold decision is the Manager&rsquo;s.
              </p>
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Applicant-visible notes <span className="text-red-600">*</span>{' '}
                <span className="text-xs text-slate-500">(min 20 chars)</span>
              </label>
              <textarea
                value={applicantVisibleNotes}
                onChange={(e) => setApplicantVisibleNotes(e.target.value)}
                rows={4}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
              <div className="mt-1 text-right text-[11px] text-slate-500">
                {applicantVisibleNotes.trim().length} characters
              </div>
              <p className="mt-1 text-[11px] text-slate-500">
                Sent to the applicant when the Manager finalizes the hire decision.
              </p>
            </div>

            <div>
              <label className="text-sm font-medium text-slate-800">
                Internal notes <span className="text-xs text-slate-500">(ERM + Manager only)</span>
              </label>
              <textarea
                value={internalNotes}
                onChange={(e) => setInternalNotes(e.target.value)}
                rows={3}
                maxLength={5000}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </div>

            {err && (
              <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                {err}
              </p>
            )}
          </div>
        </div>

        <div className="flex flex-shrink-0 items-center justify-between gap-3 border-t border-slate-200 bg-white px-6 py-3">
          <p className="text-[11px] text-slate-500">
            {missingFields.length > 0
              ? `Required: ${missingFields.join(' · ')}`
              : 'Ready to submit. Manager will action the hire decision.'}
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={!canSubmit}
              title={
                missingFields.length > 0
                  ? `Fill required fields: ${missingFields.join(', ')}`
                  : undefined
              }
              className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? 'Saving…' : 'Submit scorecard'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function ScoreInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number | '';
  onChange: (v: number | '') => void;
}) {
  return (
    <div>
      <label className="text-xs font-medium text-slate-700">{label} (1-10)</label>
      <input
        type="number"
        min={1}
        max={10}
        value={value}
        onChange={(e) =>
          onChange(e.target.value === '' ? '' : Number(e.target.value))
        }
        className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
      />
    </div>
  );
}
