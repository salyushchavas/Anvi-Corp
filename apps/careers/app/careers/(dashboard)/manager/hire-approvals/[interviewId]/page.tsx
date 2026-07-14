'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import api from '@/lib/api';
import ResumePreview from '@/components/erm/applications/ResumePreview';
import type { HireApprovalDetail } from '@/components/manager/hire-approval-types';

export default function HireApprovalDetailPage() {
  const params = useParams<{ interviewId: string }>();
  const router = useRouter();
  const interviewId = params?.interviewId;
  const [d, setD] = useState<HireApprovalDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    if (!interviewId) return;
    setLoading(true);
    try {
      const res = await api.get<HireApprovalDetail>(
        `/api/v1/manager/hire-approvals/${interviewId}`,
      );
      setD(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [interviewId]);

  useEffect(() => { void load(); }, [load]);

  async function decide(action: 'approve' | 'reject' | 'hold') {
    if (!interviewId) return;
    if (action === 'reject') {
      const ok = confirm('Reject this hire? The candidate will be marked REJECTED and the ERM will be notified.');
      if (!ok) return;
    }
    setSubmitting(true);
    setActionErr(null);
    try {
      await api.post(
        `/api/v1/manager/hire-approvals/${interviewId}/${action}`,
        note.trim() ? { note: note.trim() } : {},
      );
      // Hold stays revisitable — reload the same page so the manager sees
      // the HOLD banner with the option to change to Hire/Reject later.
      // Hire/Reject are final — bounce back to the queue.
      if (action === 'hold') {
        await load();
      } else {
        router.push('/careers/manager/hire-approvals');
      }
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setActionErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading && !d) {
    return (
      <div className="mx-auto max-w-5xl space-y-4 p-6">
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }

  if (!d) {
    return (
      <div className="mx-auto max-w-5xl space-y-4 p-6">
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err ?? 'Not found.'}
        </p>
      </div>
    );
  }

  // Final decisions lock the action panel; HOLD is non-final — the
  // panel re-renders with a HOLD banner above the buttons so the
  // manager can revisit and change to Hire/No-Hire.
  const decided = d.managerHireDecision === 'APPROVED' || d.managerHireDecision === 'REJECTED';
  const onHold = d.managerHireDecision === 'HOLD';

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <p className="text-xs">
        <Link href="/careers/manager/hire-approvals" className="text-brand-700 hover:underline">
          ← Back to Hire Approvals
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">
          {d.candidateName ?? '—'}
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          {d.jobTitle ?? '(no job title)'}
          {d.technology && <span className="ml-2 text-slate-500">· {d.technology}</span>}
        </p>
        {d.candidateEmail && (
          <p className="mt-1 text-xs text-slate-500">{d.candidateEmail}</p>
        )}
      </header>

      <div className="grid gap-4 lg:grid-cols-3">
        <main className="space-y-4 lg:col-span-2">
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900">Scorecard</h2>
            <div className="mt-3 grid grid-cols-3 gap-3 text-center">
              <Score label="Technical" value={d.technicalScore} />
              <Score label="Communication" value={d.communicationScore} />
              <Score label="Cultural fit" value={d.culturalFitScore} />
            </div>
            <div className="mt-4 text-xs">
              <p className="font-semibold text-slate-700">ERM recommendation</p>
              <p className="mt-1 text-sm text-slate-900">
                {d.overallRecommendation
                  ? d.overallRecommendation.replace(/_/g, ' ')
                  : 'No recommendation'}
              </p>
            </div>
            {d.scorecardSubmittedAt && (
              <p className="mt-3 text-[11px] text-slate-500">
                Submitted{' '}
                {new Date(d.scorecardSubmittedAt).toLocaleString()}
                {d.scorecardSubmittedByName ? ` by ${d.scorecardSubmittedByName}` : ''}
              </p>
            )}
          </section>

          {d.applicantVisibleNotes && (
            <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
              <h2 className="text-sm font-semibold text-slate-900">
                Applicant-visible notes
              </h2>
              <p className="mt-2 whitespace-pre-line text-sm text-slate-700">
                {d.applicantVisibleNotes}
              </p>
            </section>
          )}

          {d.internalNotes && (
            <section className="rounded-lg border border-amber-200 bg-amber-50 p-5 shadow-sm">
              <h2 className="text-sm font-semibold text-amber-900">
                Internal notes (ERM)
              </h2>
              <p className="mt-2 whitespace-pre-line text-sm text-amber-900">
                {d.internalNotes}
              </p>
            </section>
          )}

          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="mb-3 text-sm font-semibold text-slate-900">Resume</h2>
            <ResumePreview resume={d.resume} />
          </section>

          <section className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-5">
            <h2 className="text-sm font-semibold text-slate-700">
              Interview recording
            </h2>
            <p className="mt-2 text-xs text-slate-500">
              The Zoom interview recording will appear here once the
              recording integration ships (Phase 2). For now, decide from
              the scorecard and resume above.
            </p>
          </section>
        </main>

        <aside className="space-y-3">
          {decided ? (
            <section className={
              d.managerHireDecision === 'APPROVED'
                ? 'rounded-lg border border-green-200 bg-green-50 p-4 shadow-sm'
                : 'rounded-lg border border-red-200 bg-red-50 p-4 shadow-sm'
            }>
              <h3 className="text-xs font-semibold uppercase tracking-wide">
                Decision: {d.managerHireDecision}
              </h3>
              {d.managerHireDecisionAt && (
                <p className="mt-1 text-[11px] text-slate-700">
                  {new Date(d.managerHireDecisionAt).toLocaleString()}
                </p>
              )}
              {d.managerHireDecisionNote && (
                <p className="mt-2 whitespace-pre-line text-xs text-slate-800">
                  {d.managerHireDecisionNote}
                </p>
              )}
            </section>
          ) : (
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Hire decision
              </h3>
              {onHold && (
                <div className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
                  <p className="font-semibold">On hold</p>
                  {d.managerHireDecisionAt && (
                    <p className="mt-0.5 text-[11px]">
                      Paused {new Date(d.managerHireDecisionAt).toLocaleString()}
                    </p>
                  )}
                  {d.managerHireDecisionNote && (
                    <p className="mt-1 whitespace-pre-line">
                      {d.managerHireDecisionNote}
                    </p>
                  )}
                  <p className="mt-1 text-[11px]">
                    No emails sent, lifecycle unchanged. Hire or No-Hire when ready.
                  </p>
                </div>
              )}
              <label className="mt-3 block text-xs font-medium text-slate-700">
                Note (optional)
              </label>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              />
              {actionErr && (
                <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                  {actionErr}
                </p>
              )}
              <div className="mt-3 space-y-2">
                <button
                  type="button"
                  onClick={() => decide('approve')}
                  disabled={submitting}
                  className="w-full rounded-md bg-green-700 px-3 py-2 text-sm font-semibold text-white hover:bg-green-800 disabled:bg-slate-300"
                >
                  {submitting ? 'Saving…' : 'Hire'}
                </button>
                <button
                  type="button"
                  onClick={() => decide('hold')}
                  disabled={submitting}
                  className="w-full rounded-md border border-amber-300 bg-white px-3 py-2 text-sm font-semibold text-amber-700 hover:bg-amber-50 disabled:opacity-60"
                >
                  {onHold ? 'Update hold' : 'Hold'}
                </button>
                <button
                  type="button"
                  onClick={() => decide('reject')}
                  disabled={submitting}
                  className="w-full rounded-md border border-red-300 bg-white px-3 py-2 text-sm font-semibold text-red-700 hover:bg-red-50 disabled:opacity-60"
                >
                  No-Hire
                </button>
              </div>
              <p className="mt-3 text-[11px] text-slate-500">
                Hire = SELECTED + unblocks Send Offer. Hold = pause (no
                emails, no lifecycle change; revisit anytime).
                No-Hire = REJECTED.
              </p>
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}

function Score({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-3">
      <p className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
        {label}
      </p>
      <p className="mt-1 text-2xl font-semibold text-slate-900">
        {value == null ? '—' : value}
      </p>
      <p className="text-[10px] text-slate-500">of 10</p>
    </div>
  );
}
