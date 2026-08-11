'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  Download,
  History as HistoryIcon,
  Loader2,
  PencilLine,
  RotateCcw,
  ShieldOff,
  X,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import { useSignatureBlobs } from '@/components/idms/useSignatureBlobs';
import {
  RETURN_REASONS,
  REVOKE_REASONS,
  downloadIdmsFinalPdf,
  humanDate,
  parseFieldSchema,
  stageToneClass,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * ERM detail page — read-only render of the current document (including
 * both signatures once the intern has signed), plus stage-aware actions:
 *   INTERN_SUBMITTED  →  Verify & execute  |  Return with corrections
 *   VERIFIED          →  Finalize (renders PDF)
 *   Any post-DRAFT non-terminal (subject to gate)  →  Revoke
 *   FINALIZED         →  Download executed PDF
 *   RETURNED/REVOKED  →  Clean historical view
 */
export default function ErmDocumentDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout title="Document">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();

  const [detail, setDetail] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [returnOpen, setReturnOpen] = useState(false);
  const [revokeOpen, setRevokeOpen] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InstanceDetail>(`/api/v1/erm/idms/${id}`);
      setDetail(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  const fields = useMemo(
    () => detail ? parseFieldSchema(detail.fieldSchemaJson) : [],
    [detail]);
  const signatureBlobs = useSignatureBlobs(detail);

  async function act(
    path: string, key: string, body?: Record<string, unknown>,
  ): Promise<boolean> {
    if (!detail) return false;
    setBusy(key);
    try {
      // Every transition carries expectedUpdatedAt so a stale second tab
      // (or a double-click that races past the button's own disabled
      // state) surfaces a clean 409 instead of a silent lost update.
      const expectedUpdatedAt = detail.updatedAt
        ? Date.parse(detail.updatedAt) || undefined
        : undefined;
      const payload = { ...(body ?? {}), expectedUpdatedAt };
      const res = await api.post<InstanceDetail>(
        `/api/v1/erm/idms/${detail.id}/${path}`, payload);
      setDetail(res.data);
      toast.success('Done.');
      return true;
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
      };
      // 409 = someone else moved the document out from under us. Refetch
      // so the cockpit shows the terminal state (verified elsewhere,
      // revoked elsewhere) instead of a mystery error.
      if (ax.response?.status === 409) {
        toast.error(ax.response.data?.error
          ?? 'This document changed elsewhere — reloading the latest state.');
        void load();
      } else {
        toast.error(ax.response?.data?.error ?? 'Action failed');
      }
      return false;
    } finally {
      setBusy(null);
    }
  }

  if (loading) {
    return <div className="mx-auto max-w-4xl p-6"><div className="h-64 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err || !detail) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err ?? 'Not found.'}</div>
      </div>
    );
  }

  const stage = stageLabel(detail.status);

  return (
    <div className="mx-auto max-w-6xl space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link
            href="/careers/erm/offers"
            className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Back to cockpit
          </Link>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900">
            {detail.templateTitle}
          </h1>
          <p className="mt-0.5 text-xs text-slate-500">
            For {detail.internName} · {detail.internEmail} · v{detail.version}
          </p>
          <div className="mt-2">
            <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${stageToneClass(stage)}`}>
              {stage}
            </span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {detail.status === 'DRAFT' && (
            <Link
              href={`/careers/erm/offers/idms/${detail.id}/fill`}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
            >
              <PencilLine className="h-4 w-4" />
              Continue editing
            </Link>
          )}
          {detail.status === 'INTERN_SUBMITTED' && (
            <>
              <button
                type="button"
                onClick={() => setReturnOpen(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Return with corrections
              </button>
              <button
                type="button"
                onClick={() => act('verify', 'verify')}
                disabled={busy === 'verify' || !detail.actions.canErmVerify}
                title={!detail.actions.canErmVerify
                  ? 'Open the document to review it first, then Verify becomes available.'
                  : ''}
                className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
              >
                {busy === 'verify'
                  ? <Loader2 className="h-4 w-4 animate-spin" />
                  : <CheckCircle2 className="h-4 w-4" />}
                Verify
              </button>
            </>
          )}
          {detail.status === 'VERIFIED' && (
            <button
              type="button"
              onClick={() => act('finalize', 'finalize')}
              disabled={busy === 'finalize'}
              className="inline-flex items-center gap-1.5 rounded-md bg-emerald-700 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-800 disabled:opacity-60"
            >
              {busy === 'finalize'
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : <CheckCircle2 className="h-4 w-4" />}
              Execute & render PDF
            </button>
          )}
          {/* Always render Revoke — disabled with a tooltip when
              revokeBlockedReason is present. Hiding the button (or
              replacing it with a tiny AlertCircle) is the precedent
              bug the audit flagged: users don't see the action exists,
              can't discover WHY it's unavailable, and try workarounds
              that go nowhere. Same-shape fix as the earlier bug on the
              other surface. */}
          <button
            type="button"
            onClick={() => setRevokeOpen(true)}
            disabled={!detail.actions.canErmRevoke}
            title={
              detail.actions.canErmRevoke
                ? 'Revoke this document'
                : detail.actions.revokeBlockedReason
                  ?? 'Revoke unavailable in this state'
            }
            aria-label={
              detail.actions.canErmRevoke
                ? 'Revoke this document'
                : `Revoke unavailable — ${detail.actions.revokeBlockedReason ?? 'not allowed in this state'}`
            }
            className="inline-flex items-center gap-1.5 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs font-medium text-red-800 hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-red-50"
          >
            <ShieldOff className="h-3.5 w-3.5" />
            Revoke
          </button>
          {detail.finalPdfUrl && (
            <button
              type="button"
              onClick={async () => {
                try {
                  await downloadIdmsFinalPdf(
                    api, detail.finalPdfUrl!,
                    (detail.templateTitle ?? 'Document') + '.pdf');
                } catch (e) {
                  const ax = e as { response?: { data?: { error?: string } }; message?: string };
                  toast.error(ax.response?.data?.error ?? ax.message ?? 'Download failed');
                }
              }}
              className="inline-flex items-center gap-1.5 rounded-md bg-slate-800 px-3 py-2 text-xs font-semibold text-white hover:bg-slate-900"
            >
              <Download className="h-3.5 w-3.5" />
              Executed PDF
            </button>
          )}
        </div>
      </header>

      {detail.returnReasonCode && detail.status === 'RETURNED' && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <p className="font-medium">Waiting on corrections from intern</p>
          <p className="mt-1 text-xs">Reason: {humanReason(detail.returnReasonCode, RETURN_REASONS)}</p>
          {detail.returnComments && <p className="mt-1 text-xs">Comments: {detail.returnComments}</p>}
        </div>
      )}
      {detail.revokeReasonCode && detail.status === 'REVOKED' && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <p className="font-medium">This document was revoked</p>
          <p className="mt-1 text-xs">
            {humanDate(detail.revokedAt)}
            {' · '}
            Reason: {humanReason(detail.revokeReasonCode, REVOKE_REASONS)}
          </p>
          {detail.revokeComments && <p className="mt-1 text-xs">Comments: {detail.revokeComments}</p>}
        </div>
      )}
      {/* The Revoke button above now carries revokeBlockedReason in its
          tooltip + aria-label, so the below inline note is redundant.
          Kept for readers who miss the tooltip (touch devices, screen
          readers with terse aria) — still shows only when the block is
          active AND we have a concrete reason. */}
      {!detail.actions.canErmRevoke && detail.actions.revokeBlockedReason && (
        <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-600">
          <AlertCircle className="mr-1 inline-block h-3 w-3" />
          Revoke unavailable — {detail.actions.revokeBlockedReason}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        <section className="rounded-lg border border-slate-200 bg-white shadow-sm p-2">
          <div className="max-h-[70vh] overflow-y-auto p-2">
            <InstanceRenderer
              detail={detail}
              fields={fields}
              editRole={null}
              textValues={{}}
              signatureBlobs={signatureBlobs}
            />
          </div>
        </section>

        <aside className="space-y-4">
          <HistoryPanel detail={detail} />
        </aside>
      </div>

      {returnOpen && (
        <ReasonDialog
          title="Return with corrections"
          hint="The intern will see this reason and any comments you add."
          reasons={RETURN_REASONS}
          onCancel={() => setReturnOpen(false)}
          onSubmit={async (reasonCode, comments) => {
            // Close only on success — a failed action keeps the dialog +
            // the ERM's typed reason so they don't have to retype.
            const ok = await act('return', 'return', { reasonCode, comments });
            if (ok) setReturnOpen(false);
          }}
          confirmLabel="Return"
          confirmTone="brand"
        />
      )}
      {revokeOpen && (
        <ReasonDialog
          title="Revoke this document"
          hint="Revocation is final and shown in the intern’s history. Choose a reason and add a short note."
          reasons={REVOKE_REASONS}
          onCancel={() => setRevokeOpen(false)}
          onSubmit={async (reasonCode, comments) => {
            const ok = await act('revoke', 'revoke', { reasonCode, comments });
            if (ok) setRevokeOpen(false);
          }}
          confirmLabel="Revoke"
          confirmTone="danger"
        />
      )}
    </div>
  );
}

function HistoryPanel({ detail }: { detail: InstanceDetail }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900 flex items-center gap-1.5">
          <HistoryIcon className="h-3.5 w-3.5" />
          History
        </h2>
        {detail.supersedesId && (
          <Link
            href={`/careers/erm/offers/idms/${detail.supersedesId}`}
            className="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-800"
          >
            Prior version
            <ChevronRight className="h-3 w-3" />
          </Link>
        )}
      </header>
      {detail.history.length === 0 ? (
        <p className="text-xs text-slate-500">No activity yet.</p>
      ) : (
        <ul className="space-y-2 text-xs">
          {detail.history.map((h, i) => (
            <li key={i} className="border-l-2 border-slate-200 pl-3">
              <p className="font-medium text-slate-800">{humanAction(h.action)}</p>
              <p className="text-slate-500">
                {h.actorName ?? '—'}
                {h.actorRole && ` · ${h.actorRole}`}
                {' · '}
                {humanDate(h.createdAt)}
              </p>
              {h.comments && <p className="mt-1 text-slate-600">{h.comments}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ReasonDialog({
  title, hint, reasons, onCancel, onSubmit, confirmLabel, confirmTone,
}: {
  title: string;
  hint: string;
  reasons: { code: string; label: string }[];
  onCancel: () => void;
  onSubmit: (reasonCode: string, comments: string) => Promise<void>;
  confirmLabel: string;
  confirmTone: 'brand' | 'danger';
}) {
  const [code, setCode] = useState(reasons[0].code);
  const [comments, setComments] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const requireText = code.endsWith('_OTHER');
  const canSubmit = Boolean(code) && (!requireText || comments.trim().length > 0);

  async function go() {
    if (!canSubmit) return;
    setSubmitting(true);
    try { await onSubmit(code, comments.trim()); }
    finally { setSubmitting(false); }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <header className="mb-3 flex items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-slate-900 flex items-center gap-2">
              {confirmTone === 'danger' && <AlertTriangle className="h-4 w-4 text-red-600" />}
              {title}
            </h2>
            <p className="mt-0.5 text-xs text-slate-500">{hint}</p>
          </div>
          <button type="button" onClick={onCancel} className="text-slate-400 hover:text-slate-700" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </header>
        <label className="text-xs font-medium text-slate-700">Reason</label>
        <select
          value={code}
          onChange={(e) => setCode(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        >
          {reasons.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
        </select>
        <label className="mt-3 block text-xs font-medium text-slate-700">
          Comments {requireText && <span className="text-red-500">*</span>}
        </label>
        <textarea
          value={comments}
          onChange={(e) => setComments(e.target.value)}
          rows={4}
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          placeholder="Short note for the intern"
        />
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={go}
            disabled={!canSubmit || submitting}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-60 ${
              confirmTone === 'danger'
                ? 'bg-red-700 hover:bg-red-800'
                : 'bg-brand-700 hover:bg-brand-800'
            }`}
          >
            {submitting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function humanAction(a: string): string {
  switch (a) {
    case 'CREATE': return 'Created';
    case 'FILL':   return 'Updated';
    case 'SIGN':   return 'Signature added';
    case 'SEND':   return 'Sent to intern';
    case 'INTERN_SUBMIT': return 'Intern signed and submitted';
    case 'RETURN': return 'Returned for corrections';
    case 'VERIFY': return 'Verified';
    case 'FINALIZE': return 'Executed';
    case 'REVOKE': return 'Revoked';
    case 'SUPERSEDE': return 'Replaced by a newer document';
    default: return a;
  }
}

function humanReason(code: string, list: { code: string; label: string }[]): string {
  return list.find((x) => x.code === code)?.label ?? code;
}

function stageLabel(s: string): string {
  switch (s) {
    case 'DRAFT':            return 'Draft';
    case 'SENT_TO_INTERN':   return 'Sent';
    case 'INTERN_SUBMITTED': return 'Signed — verifying';
    case 'RETURNED':         return 'Returned';
    case 'VERIFIED':         return 'Verified';
    case 'FINALIZED':        return 'Executed';
    case 'REVOKED':          return 'Revoked';
    case 'SUPERSEDED':       return 'Replaced';
    default: return s;
  }
}
