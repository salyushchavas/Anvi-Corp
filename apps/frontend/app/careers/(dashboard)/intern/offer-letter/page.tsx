'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  CheckCircle2,
  ChevronRight,
  Download,
  FileSignature,
  Loader2,
  MessageSquareWarning,
  ShieldOff,
} from 'lucide-react';
import api from '@/lib/careers/api';
import {
  RETURN_REASONS,
  downloadIdmsFinalPdf,
  humanDate,
  stageToneClass,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * Pipeline-restore — the intern's home for their offer letter.
 *
 * <p>Reads {@code /api/v1/intern/offer-letter} for the current offer-
 * family IDMS instance (newest non-VOIDED row whose {@code templateKey}
 * matches {@code LOWER(templateKey) LIKE '%offer\_%'} — case-insensitive
 * so both legacy uppercase and new lowercase template keys resolve).
 * Renders the current lifecycle stage, an inline
 * offer-signing stepper, and a state-appropriate CTA that deep-links
 * to the F1-F19 fill/sign surface at {@code /careers/intern/offer-
 * letter/{id}}. Executed offers surface the PDF download.</p>
 *
 * <p>Renders content-only — the ProtectedRoute (INTERN gate) and
 * DashboardLayout (sidebar + top bar) are supplied by
 * {@code intern/layout.tsx}. Wrapping them here again would double
 * the shell.</p>
 */
export default function InternOfferLetterPage() {
  const [offer, setOffer] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<{ offer: InstanceDetail | null }>(
        '/api/v1/intern/offer-letter');
      setOffer(res.data.offer ?? null);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load your offer');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  // Refetch on tab-focus so an offer the ERM sends while this tab is
  // open lights up without a manual reload.
  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') void load();
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [load]);

  if (loading) {
    return <div className="mx-auto max-w-3xl p-6"><div className="h-64 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{err}</p>
        </div>
      </div>
    );
  }
  if (!offer) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-10 text-center">
          <FileSignature className="mx-auto h-8 w-8 text-slate-400" />
          <h2 className="mt-3 text-base font-semibold text-slate-800">No offer letter yet</h2>
          <p className="mt-1 text-sm text-slate-500">
            Once your ERM sends your offer, it will appear here for signing.
            You'll get a notification the moment it's ready.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
            {offer.templateTitle}
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Your offer letter — everything you need to review, sign, and keep
            for your records lives here.
          </p>
        </div>
        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${stageToneClass(externalStage(offer.status))}`}>
          {externalStage(offer.status)}
        </span>
      </header>

      <OfferStepper status={offer.status} />

      <StateCard offer={offer} />

      {offer.history.length > 0 && (
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
            History
          </h2>
          <ul className="space-y-1.5">
            {offer.history.map((h, i) => (
              <li key={i} className="rounded-md border border-slate-200 bg-white p-3 text-xs">
                <p className="font-medium text-slate-800">{humanAction(h.action)}</p>
                <p className="mt-0.5 text-slate-500">
                  {h.actorName ?? '—'}
                  {h.actorRole ? ` · ${h.actorRole}` : ''}
                  {' · '}
                  {humanDate(h.createdAt)}
                </p>
                {h.comments && <p className="mt-1 text-slate-600">{h.comments}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

// ── State-conditioned action card ────────────────────────────────────────

function StateCard({ offer }: { offer: InstanceDetail }) {
  const canEdit = offer.status === 'SENT_TO_INTERN' || offer.status === 'RETURNED';
  if (canEdit) {
    return (
      <section className={`rounded-lg border p-6 shadow-sm ${
        offer.status === 'RETURNED'
          ? 'border-red-200 bg-red-50'
          : 'border-amber-200 bg-amber-50'
      }`}>
        {offer.status === 'RETURNED' && offer.returnReasonCode && (
          <div className="mb-3 rounded-md border border-red-200 bg-white p-3 text-sm text-red-800">
            <p className="flex items-center gap-1.5 font-medium">
              <MessageSquareWarning className="h-4 w-4" />
              Your ERM asked for changes
            </p>
            <p className="mt-1 text-xs">
              Reason: {RETURN_REASONS.find((r) => r.code === offer.returnReasonCode)?.label
                ?? offer.returnReasonCode}
            </p>
            {offer.returnComments && (
              <p className="mt-1 text-xs">Notes: {offer.returnComments}</p>
            )}
          </div>
        )}
        <h2 className="text-base font-semibold text-slate-900">
          {offer.status === 'RETURNED'
            ? 'Your offer letter came back with corrections'
            : 'Your offer letter is ready — review and sign'}
        </h2>
        <p className="mt-1 text-sm text-slate-700">
          {offer.status === 'RETURNED'
            ? 'Address the changes your ERM requested, then re-submit.'
            : 'Open your offer, complete any fields, and sign — takes a few minutes.'}
        </p>
        <Link
          href={`/careers/intern/offer-letter/${offer.id}`}
          className="mt-4 inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
        >
          <FileSignature className="h-4 w-4" />
          Review and sign
          <ChevronRight className="h-4 w-4" />
        </Link>
      </section>
    );
  }
  if (offer.status === 'INTERN_SUBMITTED' || offer.status === 'VERIFIED') {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-700">
          <Loader2 className="h-4 w-4 animate-spin" />
          Under review
        </div>
        <p className="mt-2 text-sm text-slate-600">
          Your ERM is finalizing your offer. You'll get the executed copy the
          moment it's ready — no action needed from you right now.
        </p>
      </section>
    );
  }
  if (offer.status === 'FINALIZED') {
    return (
      <section className="rounded-lg border border-emerald-200 bg-emerald-50 p-6 shadow-sm">
        <div className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-800">
          <CheckCircle2 className="h-4 w-4" />
          Executed{offer.finalizedAt ? ` on ${humanDate(offer.finalizedAt)}` : ''}
        </div>
        <p className="mt-2 text-sm text-slate-700">
          Welcome aboard! Your onboarding documents will appear on the
          Documents page as soon as ERM assigns them.
        </p>
        {offer.finalPdfUrl && (
          <button
            type="button"
            onClick={async () => {
              try {
                await downloadIdmsFinalPdf(
                  api, offer.finalPdfUrl!,
                  (offer.templateTitle ?? 'Offer Letter') + '.pdf');
              } catch (e) {
                const ax = e as { response?: { data?: { error?: string } }; message?: string };
                alert(ax.response?.data?.error ?? ax.message ?? 'Download failed');
              }
            }}
            className="mt-4 inline-flex items-center gap-1.5 rounded-md bg-slate-800 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-900"
          >
            <Download className="h-4 w-4" />
            Download executed offer
          </button>
        )}
      </section>
    );
  }
  if (offer.status === 'REVOKED') {
    return (
      <section className="rounded-lg border border-slate-200 bg-slate-50 p-6 shadow-sm">
        <div className="inline-flex items-center gap-1.5 text-sm font-semibold text-red-800">
          <ShieldOff className="h-4 w-4" />
          Revoked{offer.revokedAt ? ` on ${humanDate(offer.revokedAt)}` : ''}
        </div>
        <p className="mt-2 text-sm text-slate-700">
          This offer was revoked. Please reach out to your ERM if you have
          questions.
        </p>
        {offer.revokeComments && (
          <p className="mt-3 rounded-md border border-slate-200 bg-white p-3 text-xs text-slate-700">
            {offer.revokeComments}
          </p>
        )}
      </section>
    );
  }
  if (offer.status === 'SUPERSEDED') {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <p className="text-sm text-slate-700">
          This offer was replaced with a newer version. Your latest offer
          shows above.
        </p>
      </section>
    );
  }
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <p className="text-sm text-slate-700">
        Your offer is being prepared. You'll see it here when your ERM sends
        it.
      </p>
    </section>
  );
}

// ── Offer stepper (sent → signed → executed) ─────────────────────────────

/**
 * Three explicit offer stages so the intern can see where they are in
 * the pipeline: SENT → SIGNED → EXECUTED. Onboarding is gated behind
 * EXECUTED (enforced by DocumentPacketService's offer-family gate).
 */
function OfferStepper({ status }: { status: string }) {
  const stages = [
    { key: 'sent', label: 'Sent' },
    { key: 'signed', label: 'Signed' },
    { key: 'executed', label: 'Executed' },
  ] as const;
  const activeIndex = offerStageIndex(status);
  return (
    <ol className="flex items-center gap-1 rounded-lg border border-slate-200 bg-white p-3 text-xs">
      {stages.map((s, idx) => {
        const done = idx < activeIndex;
        const current = idx === activeIndex;
        return (
          <li key={s.key} className="flex flex-1 items-center gap-2">
            <span className={`inline-flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-semibold ${
              done ? 'bg-emerald-600 text-white'
                : current ? 'bg-brand-700 text-white'
                : 'bg-slate-200 text-slate-500'
            }`}>
              {done ? <CheckCircle2 className="h-3.5 w-3.5" /> : idx + 1}
            </span>
            <span className={
              done || current
                ? 'font-medium text-slate-800'
                : 'text-slate-500'
            }>
              {s.label}
            </span>
            {idx < stages.length - 1 && (
              <span className={`ml-auto h-px flex-1 ${done ? 'bg-emerald-400' : 'bg-slate-200'}`} />
            )}
          </li>
        );
      })}
    </ol>
  );
}

function offerStageIndex(status: string): number {
  switch (status) {
    case 'DRAFT':
    case 'SENT_TO_INTERN':
    case 'RETURNED':
      return 0;
    case 'INTERN_SUBMITTED':
    case 'VERIFIED':
      return 1;
    case 'FINALIZED':
      return 2;
    case 'REVOKED':
    case 'SUPERSEDED':
    case 'VOIDED':
      return -1;
    default:
      return 0;
  }
}

function externalStage(status: string): string {
  switch (status) {
    case 'DRAFT':            return 'Being prepared';
    case 'SENT_TO_INTERN':   return 'Awaiting your signature';
    case 'INTERN_SUBMITTED': return 'Under review';
    case 'RETURNED':         return 'Returned';
    case 'VERIFIED':         return 'Under review';
    case 'FINALIZED':        return 'Executed';
    case 'REVOKED':          return 'Revoked';
    case 'SUPERSEDED':       return 'Replaced';
    default: return status;
  }
}

function humanAction(a: string): string {
  switch (a) {
    case 'CREATE': return 'Created';
    case 'FILL':   return 'Updated';
    case 'SIGN':   return 'Signature added';
    case 'SEND':   return 'Sent to you';
    case 'INTERN_SUBMIT': return 'You signed and submitted';
    case 'RETURN': return 'Returned for corrections';
    case 'VERIFY': return 'Verified by ERM';
    case 'FINALIZE': return 'Executed';
    case 'REVOKE': return 'Revoked';
    case 'SUPERSEDE': return 'Replaced by a newer offer';
    default: return a;
  }
}
