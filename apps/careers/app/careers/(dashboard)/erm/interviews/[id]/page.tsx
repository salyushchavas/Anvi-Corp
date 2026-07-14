'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import { CheckCircle2, ChevronLeft, ExternalLink, Send } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import InterviewStatusPill from '@/components/erm/interviews/InterviewStatusPill';
import DecisionPill from '@/components/erm/interviews/DecisionPill';
import CompleteInterviewModal from '@/components/erm/interviews/CompleteInterviewModal';
import RescheduleModal from '@/components/erm/interviews/RescheduleModal';
import CancelModal from '@/components/erm/interviews/CancelModal';
import ChangeInterviewerModal from '@/components/erm/interviews/ChangeInterviewerModal';
import ZoomStatusBanner from '@/components/erm/interviews/ZoomStatusBanner';
import WebexHostStartCard from '@/components/meeting/WebexHostStartCard';
import type { InterviewDetail } from '@/components/erm/interviews/types';
import type { OfferListPage, OfferRow } from '@/components/erm/offers/types';
import {
  formatInZone,
  formatLocalIfDifferent,
} from '@/lib/format-interview-time';

type Tab = 'overview' | 'decision' | 'notes' | 'history';

export default function InterviewDetailPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = params?.id;
  const [data, setData] = useState<InterviewDetail | null>(null);
  const [activeOffer, setActiveOffer] = useState<OfferRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('overview');
  const [modal, setModal] = useState<'complete' | 'reschedule' | 'cancel' | 'change' | null>(null);
  const [notesDraft, setNotesDraft] = useState({
    applicantVisibleNotes: '',
    internalNotes: '',
  });
  const [savingNotes, setSavingNotes] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InterviewDetail>(`/api/v1/erm/interviews/${id}`);
      setData(res.data);
      setNotesDraft({
        applicantVisibleNotes: res.data.applicantVisibleNotes ?? '',
        internalNotes: res.data.internalNotes ?? '',
      });
      setErr(null);

      // Phase 8.6 — surface offer state in the action sidebar. Only fetch
      // when we know the application id; failure is non-fatal.
      const appId = res.data.applicant?.applicationId;
      if (appId) {
        try {
          const offersRes = await api.get<OfferListPage>(
            `/api/v1/erm/offers?applicationId=${appId}&pageSize=10`,
          );
          const active = (offersRes.data.items ?? []).find(
            (o) => o.status === 'SENT' || o.status === 'SIGNED',
          ) ?? null;
          setActiveOffer(active);
        } catch {
          setActiveOffer(null);
        }
      } else {
        setActiveOffer(null);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load interview');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function saveNotes() {
    if (!id) return;
    setSavingNotes(true);
    try {
      await api.post(`/api/v1/erm/interviews/${id}/notes`, notesDraft);
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to save notes');
    } finally {
      setSavingNotes(false);
    }
  }

  if (loading && !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN', 'MANAGER', 'TRAINER']}>
        <DashboardLayout>
          <PageHeader title="Interview" />
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
        </DashboardLayout>
      </ProtectedRoute>
    );
  }
  if (err || !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN', 'MANAGER', 'TRAINER']}>
        <DashboardLayout>
          <PageHeader title="Interview" />
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err ?? 'Interview not found'}
          </p>
        </DashboardLayout>
      </ProtectedRoute>
    );
  }

  const ap = data.applicant;
  const title = ap ? `${ap.firstName} ${ap.lastName}`.trim() : 'Interview';
  const localView = formatLocalIfDifferent(data.scheduledAt, data.timezone);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN', 'MANAGER', 'TRAINER']}>
      <DashboardLayout>
        <Link
          href="/careers/erm/interviews"
          className="mb-3 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <ChevronLeft className="h-4 w-4" /> Back to scheduler
        </Link>
        <PageHeader title={title} subtitle={data.job?.title ?? undefined} />

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <InterviewStatusPill status={data.status} />
          <DecisionPill decision={data.decision} />
          <span className="text-xs text-slate-500">
            {formatInZone(data.scheduledAt, data.timezone)} · {data.durationMinutes ?? 60} min
          </span>
          {data.rescheduleCount > 0 && (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600">
              rescheduled {data.rescheduleCount}×
            </span>
          )}
        </div>
        {localView && (
          <p className="mb-4 text-xs text-slate-500">({localView})</p>
        )}

        <div className="grid gap-6 lg:grid-cols-3">
          <main className="lg:col-span-2">
            <div className="mb-4 flex gap-1 border-b border-slate-200 text-sm">
              {(['overview', 'decision', 'notes', 'history'] as Tab[]).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setTab(t)}
                  className={
                    'border-b-2 px-3 py-2 font-medium capitalize ' +
                    (tab === t
                      ? 'border-brand-700 text-brand-700'
                      : 'border-transparent text-slate-500 hover:text-slate-800')
                  }
                >
                  {t}
                </button>
              ))}
            </div>

            {tab === 'overview' && (
              <section className="space-y-4">
                <div className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                  <h3 className="text-sm font-semibold text-slate-900">Applicant</h3>
                  <p className="mt-2 text-sm text-slate-800">
                    {ap?.firstName} {ap?.lastName} · {ap?.email}
                  </p>
                  <p className="text-[11px] text-slate-500">
                    {ap?.applicantId} · Application: {ap?.applicationStatus}
                  </p>
                  {ap?.applicationId && (
                    <Link
                      href={`/careers/erm/applications/${ap.applicationId}`}
                      className="mt-3 inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
                    >
                      Open application <ExternalLink className="h-3 w-3" />
                    </Link>
                  )}
                </div>
                <div className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                  <h3 className="text-sm font-semibold text-slate-900">Meeting</h3>
                  <p className="mt-2 text-sm text-slate-700">
                    Interviewer: <b>{data.interviewer?.fullName ?? '—'}</b>
                  </p>
                  {/* ERM is the host — show ONLY the host start link.
                      The participant join URL is intentionally NOT
                      surfaced here; the applicant sees it on their own
                      /careers/intern/interviews page. */}
                  {data.zoomPassword && (
                    <p className="mt-1 text-xs text-slate-600">
                      Passcode: <span className="font-mono">{data.zoomPassword}</span>
                    </p>
                  )}
                  {data.zoomMeetingId && (
                    <div className="mt-3">
                      <WebexHostStartCard
                        providerMeetingId={data.zoomMeetingId}
                        startUrl={data.zoomStartUrl}
                      />
                    </div>
                  )}
                  <ZoomStatusBanner interview={data} onUpdated={setData} />
                  {data.prepInstructions && (
                    <div className="mt-3 rounded-md border border-slate-100 bg-slate-50 p-3 text-xs text-slate-700">
                      <p className="font-semibold">Prep:</p>
                      <p className="mt-1 whitespace-pre-wrap">{data.prepInstructions}</p>
                    </div>
                  )}
                </div>
              </section>
            )}

            {tab === 'decision' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                {data.status !== 'COMPLETED' ? (
                  <p className="text-sm text-slate-500">No decision recorded yet.</p>
                ) : (
                  <>
                    <div className="flex items-center gap-2">
                      <DecisionPill decision={data.decision} />
                      {data.overallRecommendation && (
                        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-700">
                          {data.overallRecommendation.replaceAll('_', ' ')}
                        </span>
                      )}
                    </div>
                    <div className="mt-3 grid grid-cols-3 gap-3 text-sm">
                      <Score label="Technical" value={data.technicalScore} />
                      <Score label="Communication" value={data.communicationScore} />
                      <Score label="Cultural fit" value={data.culturalFitScore} />
                    </div>
                    {data.applicantVisibleNotes && (
                      <div className="mt-4">
                        <p className="text-[11px] uppercase text-slate-500">Applicant-visible notes</p>
                        <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                          {data.applicantVisibleNotes}
                        </p>
                      </div>
                    )}
                    {data.internalNotes && (
                      <div className="mt-4">
                        <p className="text-[11px] uppercase text-slate-500">Internal notes (ERM-only)</p>
                        <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                          {data.internalNotes}
                        </p>
                      </div>
                    )}
                  </>
                )}
              </section>
            )}

            {tab === 'notes' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <p className="text-xs text-slate-500">
                  Internal notes are ERM-only. Applicant-visible notes show in the
                  intern's My Applications detail.
                </p>
                <div className="mt-3 space-y-3">
                  <div>
                    <label className="text-sm font-medium text-slate-800">
                      Applicant-visible notes
                    </label>
                    <textarea
                      value={notesDraft.applicantVisibleNotes}
                      onChange={(e) =>
                        setNotesDraft((s) => ({ ...s, applicantVisibleNotes: e.target.value }))
                      }
                      rows={4}
                      className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                    />
                  </div>
                  <div>
                    <label className="text-sm font-medium text-slate-800">
                      Internal notes (ERM-only)
                    </label>
                    <textarea
                      value={notesDraft.internalNotes}
                      onChange={(e) =>
                        setNotesDraft((s) => ({ ...s, internalNotes: e.target.value }))
                      }
                      rows={4}
                      maxLength={5000}
                      className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                    />
                  </div>
                  <div className="flex justify-end">
                    <button
                      type="button"
                      onClick={saveNotes}
                      disabled={savingNotes}
                      className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
                    >
                      {savingNotes ? 'Saving…' : 'Save notes'}
                    </button>
                  </div>
                </div>
              </section>
            )}

            {tab === 'history' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                {data.history.length === 0 ? (
                  <p className="text-sm text-slate-500">No history yet.</p>
                ) : (
                  <ul className="space-y-3">
                    {data.history.map((h) => (
                      <li
                        key={h.id}
                        className="rounded-md border border-slate-100 bg-slate-50 p-3 text-sm"
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-semibold text-slate-900">
                            {h.eventType.replaceAll('_', ' ')}
                          </span>
                          <span className="text-[11px] text-slate-500">
                            {new Date(h.createdAt).toLocaleString()}
                          </span>
                        </div>
                        <p className="mt-1 text-[12px] text-slate-600">
                          by {h.actorName ?? 'system'}
                          {h.reasonCode ? ` · ${h.reasonCode}` : ''}
                        </p>
                        {h.reasonText && (
                          <p className="mt-1 text-[12px] italic text-slate-600">
                            {h.reasonText}
                          </p>
                        )}
                        {h.payloadJson && (
                          <details className="mt-2">
                            <summary className="cursor-pointer text-[11px] text-brand-700">
                              payload
                            </summary>
                            <pre className="mt-1 whitespace-pre-wrap rounded-md border border-slate-200 bg-white p-2 text-[11px] text-slate-700">
                              {h.payloadJson}
                            </pre>
                          </details>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
          </main>

          <aside className="space-y-4">
            {/* Manager hire-approval gate. The ERM submits the scorecard
                from this page; the Manager then approves / rejects from
                the Hire Approvals queue. The "Send Offer" CTA only
                appears once the manager has APPROVED — until then this
                slot shows the pending-approval state. */}
            {data.status === 'COMPLETED'
              && (data.managerHireDecision == null
                  || data.managerHireDecision === 'PENDING') && (
              <section className="rounded-lg border border-amber-200 bg-amber-50 p-4 shadow-sm">
                <h3 className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Pending manager hire approval
                </h3>
                <p className="mt-2 text-xs text-amber-900">
                  Scorecard submitted. A Manager will review and decide
                  Hire / No-Hire from the Hire Approvals queue. The offer
                  cannot be sent until the manager approves.
                </p>
              </section>
            )}

            {data.status === 'COMPLETED'
              && data.managerHireDecision === 'REJECTED' && (
              <section className="rounded-lg border border-red-200 bg-red-50 p-4 shadow-sm">
                <h3 className="text-xs font-semibold uppercase tracking-wide text-red-800">
                  Manager declined hire
                </h3>
                <p className="mt-2 text-xs text-red-900">
                  A Manager declined this hire. The application has been
                  moved to REJECTED.
                </p>
                {data.managerHireDecisionNote && (
                  <p className="mt-2 whitespace-pre-line text-xs text-red-900">
                    {data.managerHireDecisionNote}
                  </p>
                )}
              </section>
            )}

            {data.status === 'COMPLETED' && data.managerHireDecision === 'APPROVED' && (
              <section className="rounded-lg border border-green-200 bg-green-50 p-4 shadow-sm">
                {activeOffer ? (
                  <div>
                    <div className="flex items-center gap-2">
                      <CheckCircle2 className="h-4 w-4 text-green-700" />
                      <h3 className="text-xs font-semibold uppercase tracking-wide text-green-800">
                        Offer {activeOffer.status === 'SIGNED' ? 'Signed' : 'Sent'}
                      </h3>
                    </div>
                    <p className="mt-2 text-xs text-green-900">
                      {activeOffer.status === 'SIGNED'
                        ? `Signed ${activeOffer.signedAt ? new Date(activeOffer.signedAt).toLocaleDateString() : '—'}`
                        : `Sent ${activeOffer.sentAt ? new Date(activeOffer.sentAt).toLocaleDateString() : '—'}`}
                    </p>
                    <Link
                      href={`/careers/erm/offers/${activeOffer.offerId}`}
                      className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-green-800 hover:underline"
                    >
                      Open offer
                      <ExternalLink className="h-3 w-3" />
                    </Link>
                  </div>
                ) : (
                  <div>
                    <h3 className="text-xs font-semibold uppercase tracking-wide text-green-800">
                      Next step
                    </h3>
                    <p className="mt-1 text-xs text-green-900">
                      Applicant selected. Send the offer letter to advance the application.
                    </p>
                    <button
                      type="button"
                      onClick={() => router.push(
                        `/careers/erm/offers/new?applicationId=${data.applicant?.applicationId ?? ''}`,
                      )}
                      disabled={!data.applicant?.applicationId}
                      className="mt-3 inline-flex w-full items-center justify-center gap-1 rounded-md bg-brand-700 px-3 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
                    >
                      <Send className="h-3.5 w-3.5" />
                      Send Offer
                    </button>
                  </div>
                )}
              </section>
            )}

            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Actions</h3>
              <div className="mt-3 space-y-2">
                <Btn label="Complete" enabled={data.availableActions.canComplete}
                  onClick={() => setModal('complete')} primary />
                <Btn label="Reschedule" enabled={data.availableActions.canReschedule}
                  onClick={() => setModal('reschedule')} />
                <Btn label="Change interviewer" enabled={data.availableActions.canChangeInterviewer}
                  onClick={() => setModal('change')} />
                <Btn label="Cancel" enabled={data.availableActions.canCancel}
                  onClick={() => setModal('cancel')} danger />
              </div>
            </section>

            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Compliance</h3>
              <p className="mt-2 text-xs text-slate-600">
                Internal notes are ERM-only. Applicant-visible notes go in the
                dedicated field on the Decision tab.
              </p>
            </section>
          </aside>
        </div>

        {modal === 'complete' && (
          <CompleteInterviewModal open interview={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
        {modal === 'reschedule' && (
          <RescheduleModal open interview={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
        {modal === 'cancel' && (
          <CancelModal open interview={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
        {modal === 'change' && (
          <ChangeInterviewerModal open interview={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Score({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded-md border border-slate-100 bg-slate-50 p-3 text-center">
      <p className="text-[11px] uppercase text-slate-500">{label}</p>
      <p className="mt-1 text-lg font-semibold text-slate-900">
        {value ?? '—'}{value != null ? <span className="text-xs text-slate-400">/10</span> : ''}
      </p>
    </div>
  );
}

function Btn({
  label,
  enabled,
  onClick,
  primary,
  danger,
}: {
  label: string;
  enabled: boolean;
  onClick: () => void;
  primary?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={!enabled}
      onClick={onClick}
      className={
        'w-full rounded-md px-3 py-2 text-sm font-semibold transition-colors ' +
        (primary
          ? 'bg-brand-700 text-white hover:bg-brand-800 disabled:bg-slate-300'
          : danger
            ? 'border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-50'
            : 'border border-slate-200 text-slate-700 hover:bg-slate-50 disabled:opacity-50')
      }
    >
      {label}
    </button>
  );
}
