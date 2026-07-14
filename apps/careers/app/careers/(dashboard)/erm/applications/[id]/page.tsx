'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import StagePill from '@/components/erm/applications/StagePill';
import DecisionModal from '@/components/erm/applications/DecisionModal';
import ResumePreview from '@/components/erm/applications/ResumePreview';
import type {
  ApplicationDetail,
  DecisionKind,
} from '@/components/erm/applications/types';

type Tab = 'profile' | 'resume' | 'answers' | 'history' | 'notes';

export default function ApplicationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [detail, setDetail] = useState<ApplicationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [modal, setModal] = useState<DecisionKind | null>(null);
  const [tab, setTab] = useState<Tab>('profile');
  const [noteDraft, setNoteDraft] = useState('');
  const [noteErr, setNoteErr] = useState<string | null>(null);
  const [savingNote, setSavingNote] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<ApplicationDetail>(
        `/api/v1/erm/applications/${id}`,
      );
      setDetail(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load application');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function addNote() {
    if (!id || noteDraft.trim().length < 5) {
      setNoteErr('Note must be at least 5 characters.');
      return;
    }
    setSavingNote(true);
    setNoteErr(null);
    try {
      await api.post(`/api/v1/erm/applications/${id}/internal-note`, {
        note: noteDraft.trim(),
      });
      setNoteDraft('');
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setNoteErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to save note'),
      );
    } finally {
      setSavingNote(false);
    }
  }

  async function resumeFromHold() {
    if (!id) return;
    try {
      await api.post(`/api/v1/erm/applications/${id}/resume`, {});
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      alert(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to resume'),
      );
    }
  }

  if (loading && !detail) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Application" />
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
        </DashboardLayout>
      </ProtectedRoute>
    );
  }
  if (err || !detail) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Application" />
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err ?? 'Application not found'}
          </p>
        </DashboardLayout>
      </ProtectedRoute>
    );
  }

  const a = detail.application;
  const ap = detail.applicant;
  const pr = detail.applicantProfile;
  const aa = detail.availableActions;
  const title =
    ap != null ? `${ap.firstName} ${ap.lastName}`.trim() : 'Application';

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Link
          href="/careers/erm/applications"
          className="mb-3 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <ChevronLeft className="h-4 w-4" /> Back to Inbox
        </Link>
        <PageHeader
          title={title}
          subtitle={ap?.applicantId ?? ap?.email ?? undefined}
        />

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <StagePill stage={a.stage} />
          <span className="text-xs text-slate-500">
            Applied {new Date(a.appliedAt).toLocaleDateString()} · Last updated{' '}
            {new Date(a.statusUpdatedAt).toLocaleString()}
          </span>
        </div>

        <div className="grid gap-6 lg:grid-cols-3">
          <main className="lg:col-span-2">
            <div className="mb-4 flex gap-1 border-b border-slate-200 text-sm">
              {(['profile', 'resume', 'answers', 'history', 'notes'] as Tab[]).map(
                (t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => setTab(t)}
                    className={
                      'border-b-2 px-3 py-2 font-medium ' +
                      (tab === t
                        ? 'border-brand-700 text-brand-700'
                        : 'border-transparent text-slate-500 hover:text-slate-800')
                    }
                  >
                    {tabLabel(t)}
                  </button>
                ),
              )}
            </div>

            {tab === 'profile' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <Row label="Email" value={ap?.email} />
                <Row label="Phone" value={ap?.phone} />
                <Row label="School" value={pr?.school} />
                {/* Phase 1.5 — show structured education when present;
                    fall back to the legacy free-text trio for
                    pre-migration rows. */}
                {pr?.degreeLevel || pr?.specialization || pr?.graduationYear ? (
                  <>
                    <Row label="Degree" value={pr.degreeLevel} />
                    <Row label="Specialization" value={pr.specialization} />
                    <Row
                      label="End year"
                      value={pr.graduationYear != null ? String(pr.graduationYear) : null}
                    />
                  </>
                ) : (
                  <>
                    <Row label="Degree" value={pr?.degree} />
                    <Row label="Education" value={pr?.education} />
                  </>
                )}
                <Row label="Skillset" value={pr?.skillset} multiline />
                <Row label="Work authorization" value={pr?.workAuthType} />
                <Row label="Valid from" value={pr?.workAuthValidFrom} />
                <Row label="Valid until" value={pr?.workAuthValidUntil} />
                <Row
                  label="Sponsorship needed"
                  value={pr?.sponsorshipNeeded == null
                    ? null
                    : pr.sponsorshipNeeded ? 'Yes' : 'No'}
                />
              </section>
            )}
            {tab === 'resume' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <ResumePreview resume={detail.resume} />
              </section>
            )}
            {tab === 'answers' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <p className="text-[11px] font-semibold uppercase text-slate-500">
                  Statement of interest
                </p>
                <p className="mt-2 whitespace-pre-wrap text-sm text-slate-800">
                  {a.statementOfInterest ?? '(none provided)'}
                </p>
              </section>
            )}
            {tab === 'history' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                {detail.decisionHistory.length === 0 ? (
                  <p className="text-sm text-slate-500">
                    No decision history yet.
                  </p>
                ) : (
                  <ul className="space-y-3">
                    {detail.decisionHistory.map((d) => (
                      <li
                        key={d.id}
                        className="rounded-md border border-slate-100 bg-slate-50 p-3 text-sm"
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-semibold text-slate-900">
                            {d.decision.replace(/_/g, ' ')}
                          </span>
                          <span className="text-[11px] text-slate-500">
                            {new Date(d.decidedAt).toLocaleString()}
                          </span>
                        </div>
                        <p className="mt-1 text-[12px] text-slate-600">
                          {d.previousStage} → {d.newStage}
                          {d.decidedByName ? ` · by ${d.decidedByName}` : ''}
                        </p>
                        {d.reasonCodeLabel && (
                          <p className="mt-1 text-[12px] text-slate-700">
                            <b>Reason:</b> {d.reasonCodeLabel}
                          </p>
                        )}
                        {d.reasonText && (
                          <p className="mt-1 text-[12px] italic text-slate-600">
                            ERM note: {d.reasonText}
                          </p>
                        )}
                        {d.applicantVisibleMessage && (
                          <details className="mt-2">
                            <summary className="cursor-pointer text-[12px] text-brand-700">
                              View message sent to applicant
                            </summary>
                            <pre className="mt-2 whitespace-pre-wrap rounded-md border border-slate-200 bg-white p-3 text-[12px] text-slate-700">
                              {d.applicantVisibleMessage}
                            </pre>
                          </details>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
            {tab === 'notes' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <p className="text-xs text-slate-500">
                  Internal notes — ERM/Manager visible only.
                </p>
                <pre className="mt-3 max-h-96 overflow-auto whitespace-pre-wrap rounded-md border border-slate-200 bg-slate-50 p-3 text-[12px] text-slate-800">
                  {a.internalNotes ?? '(no notes yet)'}
                </pre>
                <div className="mt-3">
                  <textarea
                    value={noteDraft}
                    onChange={(e) => setNoteDraft(e.target.value)}
                    rows={3}
                    placeholder="Add a note (min 5 chars)…"
                    className="w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                  />
                  {noteErr && (
                    <p className="mt-2 text-xs text-red-700">{noteErr}</p>
                  )}
                  <div className="mt-2 flex justify-end">
                    <button
                      type="button"
                      onClick={addNote}
                      disabled={savingNote}
                      className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
                    >
                      {savingNote ? 'Saving…' : 'Add note'}
                    </button>
                  </div>
                </div>
              </section>
            )}
          </main>

          <aside className="space-y-4">
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Actions
              </h3>
              <div className="mt-3 space-y-2">
                <ActionBtn
                  label="Shortlist"
                  enabled={aa.canShortlist}
                  onClick={() => setModal('SHORTLIST')}
                  primary
                />
                <ActionBtn
                  label="Hold"
                  enabled={aa.canHold}
                  onClick={() => setModal('HOLD')}
                />
                <ActionBtn
                  label="Request info"
                  enabled={aa.canRequestInfo}
                  onClick={() => setModal('REQUEST_INFO')}
                />
                <ActionBtn
                  label="Reject"
                  enabled={aa.canReject}
                  onClick={() => setModal('REJECT')}
                />
                {aa.canResumeFromHold && (
                  <button
                    type="button"
                    onClick={resumeFromHold}
                    className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                  >
                    Resume from Hold
                  </button>
                )}
                {/* ERM Phase 3 — Schedule interview CTA only when SHORTLISTED. */}
                {a.stage === 'SHORTLISTED' && (
                  <Link
                    href={`/careers/erm/interviews/new?applicationId=${a.id}`}
                    className="block w-full rounded-md bg-amber-600 px-3 py-2 text-center text-sm font-semibold text-white hover:bg-amber-700"
                  >
                    Schedule interview
                  </Link>
                )}
                {/* ERM Phase 4 — Send offer CTA only when INTERVIEWED. The
                    server-side gate also enforces SELECTED interview decision;
                    if last interview wasn't SELECTED, the POST will 409. */}
                {a.stage === 'INTERVIEWED' && (
                  <Link
                    href={`/careers/erm/offers/new?applicationId=${a.id}`}
                    className="block w-full rounded-md bg-brand-700 px-3 py-2 text-center text-sm font-semibold text-white hover:bg-brand-800"
                  >
                    Send offer
                  </Link>
                )}
              </div>
            </section>

            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Job
              </h3>
              <p className="mt-2 text-sm font-medium text-slate-900">
                {detail.job?.title}
              </p>
              <p className="text-[11px] text-slate-500">
                {detail.job?.jobType} · {detail.job?.location}
              </p>
              {detail.job?.descriptionExcerpt && (
                <p className="mt-2 text-xs text-slate-600">
                  {detail.job.descriptionExcerpt}
                </p>
              )}
            </section>

            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Owner
              </h3>
              <p className="mt-2 text-sm text-slate-800">
                {a.ermOwnerName ?? 'Unassigned'}
              </p>
            </section>
          </aside>
        </div>

        {modal && (
          <DecisionModal
            open
            detail={detail}
            decision={modal}
            onClose={() => setModal(null)}
            onApplied={() => void load()}
          />
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function tabLabel(t: Tab): string {
  return (
    {
      profile: 'Profile',
      resume: 'Resume',
      answers: 'Application answers',
      history: 'Decision history',
      notes: 'Internal notes',
    } as const
  )[t];
}

function Row({
  label,
  value,
  multiline,
}: {
  label: string;
  value: string | null | undefined;
  multiline?: boolean;
}) {
  return (
    <div className="mb-2 grid grid-cols-3 gap-2 text-sm">
      <p className="text-[11px] uppercase text-slate-500">{label}</p>
      <p
        className={
          'col-span-2 text-slate-800 ' + (multiline ? 'whitespace-pre-wrap' : '')
        }
      >
        {value && value.trim().length > 0 ? value : '—'}
      </p>
    </div>
  );
}

function ActionBtn({
  label,
  enabled,
  onClick,
  primary,
}: {
  label: string;
  enabled: boolean;
  onClick: () => void;
  primary?: boolean;
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
          : 'border border-slate-200 text-slate-700 hover:bg-slate-50 disabled:opacity-50')
      }
    >
      {label}
    </button>
  );
}

