'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft, ExternalLink } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import OfferStatusPill from '@/components/erm/offers/OfferStatusPill';
import VoidOfferModal from '@/components/erm/offers/VoidOfferModal';
import ResendOfferModal from '@/components/erm/offers/ResendOfferModal';
import UpdateStartDateModal from '@/components/erm/offers/UpdateStartDateModal';
import type { OfferDetail } from '@/components/erm/offers/types';

type Tab = 'overview' | 'signing' | 'history' | 'notes';

export default function OfferDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [data, setData] = useState<OfferDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('overview');
  const [modal, setModal] = useState<'void' | 'resend' | 'startdate' | null>(null);
  const [noteDraft, setNoteDraft] = useState('');
  const [savingNote, setSavingNote] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<OfferDetail>(`/api/v1/erm/offers/${id}`);
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load offer');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function sendReminder() {
    if (!id) return;
    try {
      await api.post(`/api/v1/erm/offers/${id}/reminder`, {});
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Reminder failed');
    }
  }

  async function clearForReoffer() {
    if (!id) return;
    if (!confirm('Archive this voided offer and unlock a fresh offer for the application?')) return;
    try {
      await api.post(`/api/v1/erm/offers/${id}/clear-for-reoffer`, {});
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      alert(ax.response?.data?.error ?? (e instanceof Error ? e.message : 'Failed'));
    }
  }

  async function addNote() {
    if (!id || noteDraft.trim().length < 5) return;
    setSavingNote(true);
    try {
      await api.post(`/api/v1/erm/offers/${id}/internal-note`, { note: noteDraft.trim() });
      setNoteDraft('');
      await load();
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Note save failed');
    } finally {
      setSavingNote(false);
    }
  }

  if (loading && !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Offer" />
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
        </DashboardLayout>
      </ProtectedRoute>
    );
  }
  if (err || !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Offer" />
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err ?? 'Offer not found'}
          </p>
        </DashboardLayout>
      </ProtectedRoute>
    );
  }

  const expiringSoon =
    data.status === 'SENT' &&
    data.expiresAt &&
    new Date(data.expiresAt).getTime() - Date.now() < 24 * 3600 * 1000;

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Link
          href="/careers/erm/offers"
          className="mb-3 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <ChevronLeft className="h-4 w-4" /> Back to offers
        </Link>
        <PageHeader
          title={data.applicantName ?? 'Offer'}
          subtitle={data.roleTitle ?? data.jobTitle ?? undefined}
        />

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <OfferStatusPill status={data.status} />
          {data.expiresAt && (
            <span className={expiringSoon
              ? 'rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-700'
              : 'text-xs text-slate-500'}>
              Expires {new Date(data.expiresAt).toLocaleString()}
            </span>
          )}
          {data.reminderCount > 0 && (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600">
              {data.reminderCount} reminder{data.reminderCount === 1 ? '' : 's'}
            </span>
          )}
          {data.archivedAt && (
            <span className="rounded-full bg-slate-200 px-2 py-0.5 text-[11px] text-slate-700">
              archived
            </span>
          )}
        </div>

        <div className="grid gap-6 lg:grid-cols-3">
          <main className="lg:col-span-2">
            <div className="mb-4 flex gap-1 border-b border-slate-200 text-sm">
              {(['overview', 'signing', 'history', 'notes'] as Tab[]).map((t) => (
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
                  <Row label="Applicant" value={`${data.applicantName} · ${data.applicantEmail}`} />
                  <Row label="Applicant ID" value={data.applicantId} />
                  <Row label="Job" value={`${data.jobTitle} (${data.jobType})`} />
                  <Row label="Role title" value={data.roleTitle} />
                  <Row label="Compensation" value={data.compensationSummary} />
                  <Row label="Worksite" value={data.worksite} />
                  <Row label="Hours / week" value={data.expectedHoursPerWeek?.toString()} />
                  <Row label="Tentative start" value={data.tentativeStartDate} />
                </div>
              </section>
            )}

            {tab === 'signing' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <p className="mb-3 text-xs text-slate-500">
                  Signing runs through IDMS (in-house). The applicant signs
                  at <span className="font-mono">/careers/intern/offer/sign/{data.id}</span>
                  ; signature image + typed name are stored on the offer row.
                </p>
                <Row label="Sent" value={data.sentAt ?? '—'} />
                <Row label="Signed" value={data.signedAt ?? '—'} />
                <Row label="Voided" value={data.voidedAt ?? '—'} />
                {data.legacyEnvelopeId && (
                  <Row label="Legacy envelope ID" value={data.legacyEnvelopeId} />
                )}
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
                          <p className="mt-1 text-[12px] italic text-slate-600">{h.reasonText}</p>
                        )}
                        {h.payloadJson && (
                          <details className="mt-2">
                            <summary className="cursor-pointer text-[11px] text-brand-700">payload</summary>
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

            {tab === 'notes' && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <p className="text-xs text-slate-500">Internal notes — ERM only.</p>
                <pre className="mt-3 max-h-96 overflow-auto whitespace-pre-wrap rounded-md border border-slate-200 bg-slate-50 p-3 text-[12px] text-slate-800">
                  {data.internalNotes ?? '(no notes yet)'}
                </pre>
                <div className="mt-3">
                  <textarea
                    value={noteDraft}
                    onChange={(e) => setNoteDraft(e.target.value)}
                    rows={3}
                    placeholder="Add a note (min 5 chars)…"
                    className="w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                  />
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
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Actions</h3>
              <div className="mt-3 space-y-2">
                {data.status === 'SENT' && (
                  <>
                    <button
                      type="button"
                      onClick={sendReminder}
                      className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Send reminder
                    </button>
                    <button
                      type="button"
                      onClick={() => setModal('resend')}
                      className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Resend (extend expiry)
                    </button>
                    <button
                      type="button"
                      onClick={() => setModal('startdate')}
                      className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Update start date
                    </button>
                    <button
                      type="button"
                      onClick={() => setModal('void')}
                      className="w-full rounded-md border border-red-200 px-3 py-2 text-sm font-semibold text-red-700 hover:bg-red-50"
                    >
                      Void offer
                    </button>
                  </>
                )}
                {data.status === 'VOIDED' && !data.archivedAt && (
                  <button
                    type="button"
                    onClick={clearForReoffer}
                    className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                  >
                    Clear for re-offer (24h cooldown)
                  </button>
                )}
                {data.status === 'SIGNED' && data.applicationId && (
                  <Link
                    href={`/careers/erm/applications/${data.applicationId}`}
                    className="inline-flex w-full items-center justify-center gap-1 rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                  >
                    View application <ExternalLink className="h-3 w-3" />
                  </Link>
                )}
              </div>
            </section>
          </aside>
        </div>

        {modal === 'void' && (
          <VoidOfferModal open offer={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
        {modal === 'resend' && (
          <ResendOfferModal open offer={data} onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
        {modal === 'startdate' && (
          <UpdateStartDateModal open offerId={data.id} currentDate={data.tentativeStartDate}
            onClose={() => setModal(null)} onApplied={() => void load()} />
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="mb-2 grid grid-cols-3 gap-2 text-sm">
      <p className="text-[11px] uppercase text-slate-500">{label}</p>
      <p className="col-span-2 text-slate-800">{value && value !== 'null' ? value : '—'}</p>
    </div>
  );
}
