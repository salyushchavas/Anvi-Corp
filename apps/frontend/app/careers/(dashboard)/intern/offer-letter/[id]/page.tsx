'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Cloud,
  CloudOff,
  Download,
  Loader2,
  MessageSquareWarning,
  RefreshCw,
  Send,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import FieldForm, { type FieldFormHandle } from '@/components/idms/FieldForm';
import SignatureCapture from '@/components/idms/SignatureCapture';
import { useSignatureBlobs } from '@/components/idms/useSignatureBlobs';
import {
  useCoalescedAutoSave,
  type SaveState,
} from '@/components/idms/useCoalescedAutoSave';
import { useFillCompleteness } from '@/components/idms/useFillCompleteness';
import { useAuth } from '@/lib/careers/auth-context';
import {
  RETURN_REASONS,
  humanDate,
  parseFieldSchema,
  stageToneClass,
  type FieldSchemaEntry,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * Intern-side fill + sign page for their offer letter (Pipeline-restore
 * canonical URL). Operates on any {@link InstanceDetail} by id — the
 * API contract at {@code /api/v1/intern/agreements/*} is doc-shape-
 * agnostic — but the UI copy + back-link land on the offer-letter
 * pipeline. Same split-view + save-race contract as the ERM fill page
 * with roles inverted; see the ERM page's header comment for the
 * reactive-completeness + save/send-race guarantees.
 *
 * <p>Renders content-only — the ProtectedRoute (INTERN gate) and
 * DashboardLayout (sidebar + top bar) are supplied by
 * {@code intern/layout.tsx}. Wrapping them here again would double
 * the shell.</p>
 */
export default function InternOfferLetterFillPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;

  const [detail, setDetail] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [textValues, setTextValues] = useState<Record<string, string>>({});
  const [activeSignature, setActiveSignature] = useState<string | null>(null);
  const [typedName, setTypedName] = useState('');
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [stagedSignature, setStagedSignature] = useState<string | null>(null);
  const [optimisticSigned, setOptimisticSigned] = useState<Set<string>>(new Set());

  const { user } = useAuth();
  const fieldFormRef = useRef<FieldFormHandle | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InstanceDetail>(`/api/v1/intern/agreements/${id}`);
      setDetail(res.data);
      const seed: Record<string, string> = {};
      for (const [k, v] of Object.entries(res.data.values ?? {})) {
        if (v.valueText != null && v.type !== 'signature') seed[k] = v.valueText;
      }
      setTextValues(seed);
      setOptimisticSigned(new Set());
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  const fields: FieldSchemaEntry[] = useMemo(
    () => detail ? parseFieldSchema(detail.fieldSchemaJson) : [],
    [detail]);
  const signatureBlobs = useSignatureBlobs(detail);

  const canEdit = Boolean(detail && (detail.status === 'SENT_TO_INTERN' || detail.status === 'RETURNED'));
  const isRevoked = detail?.status === 'REVOKED';
  const isFinalized = detail?.status === 'FINALIZED';

  const detailRef = useRef<InstanceDetail | null>(null);
  const fieldsRef = useRef<FieldSchemaEntry[]>([]);
  const canEditRef = useRef<boolean>(false);
  useEffect(() => { detailRef.current = detail; }, [detail]);
  useEffect(() => { fieldsRef.current = fields; }, [fields]);
  useEffect(() => { canEditRef.current = canEdit; }, [canEdit]);

  const completeness = useFillCompleteness({
    detail,
    fields,
    role: 'INTERN',
    textValues,
    optimisticallySignedFieldIds: optimisticSigned,
  });

  const persist = useCallback(async (
    values: Record<string, string>,
  ): Promise<boolean> => {
    const currentDetail = detailRef.current;
    const currentFields = fieldsRef.current;
    if (!currentDetail || !canEditRef.current) return false;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== 'INTERN') continue;
      if (f.type === 'signature') continue;
      payload[f.id] = values[f.id] ?? '';
    }
    if (Object.keys(payload).length === 0) return true;
    const res = await api.post<InstanceDetail>(
      `/api/v1/intern/agreements/${currentDetail.id}/fill`,
      {
        values: payload,
        expectedUpdatedAt: currentDetail.updatedAt
          ? Date.parse(currentDetail.updatedAt) || undefined
          : undefined,
      },
    );
    setDetail(res.data);
    return true;
  }, []);

  const saveController = useCoalescedAutoSave<Record<string, string>>({
    persist,
    enabled: canEdit,
    onConflict: () => { void load(); },
  });
  const { saveState, scheduleSave, flush, retry, seedLastSavedKey } = saveController;

  useEffect(() => {
    if (!detail) return;
    const currentFields = fieldsRef.current;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== 'INTERN' || f.type === 'signature') continue;
      payload[f.id] = textValues[f.id] ?? '';
    }
    seedLastSavedKey(payload);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail, seedLastSavedKey]);

  function onTextChange(fid: string, v: string) {
    setTextValues((p) => {
      const next = { ...p, [fid]: v };
      scheduleSave(next);
      return next;
    });
  }

  useEffect(() => {
    function beforeUnload(e: BeforeUnloadEvent) {
      if (saveState.kind === 'dirty' || saveState.kind === 'saving') {
        e.preventDefault();
        e.returnValue = '';
      }
    }
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [saveState]);

  async function saveSignature() {
    if (!detail || !activeSignature) return;
    if (!stagedSignature) {
      toast.error('Prepare a signature first.');
      return;
    }
    const fieldId = activeSignature;
    try {
      const res = await api.post<InstanceDetail>(
        `/api/v1/intern/agreements/${detail.id}/sign`,
        { fieldId, signatureImageDataUrl: stagedSignature, typedName },
      );
      setDetail(res.data);
      setOptimisticSigned((s) => {
        const n = new Set(s);
        n.add(fieldId);
        return n;
      });
      setStagedSignature(null);
      setActiveSignature(null);
      setTypedName('');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Signature save failed');
    }
  }

  async function submit() {
    if (!detail) return;
    if (!completeness.canTransition) {
      toast.error(completeness.blockingReason ?? 'Complete required fields first.');
      return;
    }
    setSubmitting(true);
    try {
      const saved = await flush(textValues);
      if (!saved) {
        toast.error("Couldn't save your latest changes — please try again in a moment.");
        setSubmitting(false);
        return;
      }
      const latestUpdatedAt = detailRef.current?.updatedAt;
      const payload: Record<string, string> = {};
      for (const f of fields) {
        if (f.assignee !== 'INTERN' || f.type === 'signature') continue;
        payload[f.id] = textValues[f.id] ?? '';
      }
      const res = await api.post<InstanceDetail>(
        `/api/v1/intern/agreements/${detail.id}/submit`,
        {
          values: payload,
          expectedUpdatedAt: latestUpdatedAt
            ? Date.parse(latestUpdatedAt) || undefined
            : undefined,
        },
      );
      setDetail(res.data);
      setConfirmOpen(false);
      toast.success('Submitted to your ERM.');
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
      };
      if (ax.response?.status === 409) {
        toast.error("This document isn't awaiting your response anymore.");
        void load();
        setConfirmOpen(false);
      } else {
        toast.error(ax.response?.data?.error ?? 'Submit failed');
      }
    } finally {
      setSubmitting(false);
    }
  }

  function onFieldClickInPreview(fieldId: string) {
    const f = fields.find((x) => x.id === fieldId);
    if (!f) return;
    if (!canEdit) return;
    if (f.assignee === 'INTERN' && f.type === 'signature') {
      setActiveSignature(fieldId);
      return;
    }
    if (f.assignee === 'INTERN') {
      fieldFormRef.current?.focusField(fieldId);
      setFocusedField(fieldId);
    }
  }

  if (loading) {
    return <div className="mx-auto max-w-4xl p-6"><div className="h-64 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err || !detail) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err ?? 'Not found.'}</div>
      </div>
    );
  }

  const submitDisabled = submitting
    || !completeness.canTransition
    || saveState.kind === 'saving';

  return (
    <div className="mx-auto max-w-5xl space-y-4">
      <Link
        href="/careers/intern/offer-letter"
        className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-3.5 w-3.5" />
        Back to offer letter
      </Link>
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
            {detail.templateTitle}
          </h1>
          <p className="mt-0.5 text-xs text-slate-500">
            <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${stageToneClass(externalStage(detail.status))}`}>
              {externalStage(detail.status)}
            </span>
          </p>
        </div>
        <div className="flex items-center gap-3">
          {canEdit && (
            <span className="text-xs text-slate-500">
              {completeness.ownDone} of {completeness.ownTotal} complete
            </span>
          )}
          {canEdit && <SaveIndicator state={saveState} onRetry={retry} />}
          {isFinalized && detail.finalPdfUrl && (
            <a
              href={detail.finalPdfUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-md bg-slate-800 px-3 py-2 text-xs font-semibold text-white hover:bg-slate-900"
            >
              <Download className="h-3.5 w-3.5" />
              Download executed copy
            </a>
          )}
          {canEdit && (
            <button
              type="button"
              onClick={() => setConfirmOpen(true)}
              disabled={submitDisabled}
              title={submitReasonWhenDisabled(submitDisabled, completeness.blockingReason, saveState)}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              <Send className="h-4 w-4" />
              Submit
            </button>
          )}
        </div>
      </header>

      {detail.status === 'RETURNED' && detail.returnReasonCode && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <p className="flex items-center gap-1.5 font-medium">
            <MessageSquareWarning className="h-4 w-4" />
            Your ERM asked for changes
          </p>
          <p className="mt-1 text-xs">
            Reason: {RETURN_REASONS.find((r) => r.code === detail.returnReasonCode)?.label ?? detail.returnReasonCode}
          </p>
          {detail.returnComments && (
            <p className="mt-1 text-xs">Notes: {detail.returnComments}</p>
          )}
        </div>
      )}

      {isRevoked && (
        <div className="rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
          <p className="font-medium">This document has been revoked.</p>
          <p className="mt-1 text-xs text-slate-500">
            {humanDate(detail.revokedAt)}. Please reach out to your ERM if you have questions.
          </p>
          {detail.revokeComments && (
            <p className="mt-2 rounded-md border border-slate-200 bg-white p-2 text-xs text-slate-700">
              {detail.revokeComments}
            </p>
          )}
        </div>
      )}

      {canEdit && completeness.blockingReason && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{completeness.blockingReason}</p>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="max-h-[calc(100vh-260px)] overflow-y-auto p-2">
            <InstanceRenderer
              detail={detail}
              fields={fields}
              editRole={canEdit ? 'INTERN' : null}
              textValues={textValues}
              signatureBlobs={signatureBlobs}
              focusedFieldId={focusedField}
              onFieldClick={canEdit ? onFieldClickInPreview : undefined}
              activeSignatureFieldId={activeSignature}
            />
          </div>
        </section>

        <aside className="space-y-4">
          {canEdit && (
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <FieldForm
                ref={fieldFormRef}
                detail={detail}
                fields={fields}
                role="INTERN"
                textValues={textValues}
                onTextChange={onTextChange}
                onOpenSignature={(fid) => setActiveSignature(fid)}
                activeSignatureFieldId={activeSignature}
                focusedFieldId={focusedField}
                onFocusField={setFocusedField}
                signatureBlobs={signatureBlobs}
              />
            </section>
          )}
          {canEdit && activeSignature && (
            <section className="rounded-lg border border-brand-300 bg-white p-4 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-900">Your signature</h3>
              <p className="mt-1 text-xs text-slate-500">
                Pick a way to sign — draw, upload an image, generate one from
                your name, or clean up a photo of your signature.
              </p>
              <div className="mt-3">
                <SignatureCapture
                  initialName={user?.fullName ?? ''}
                  onChange={setStagedSignature}
                />
              </div>
              <div className="mt-3">
                <label className="text-xs font-medium text-slate-600">Typed name</label>
                <input
                  value={typedName}
                  onChange={(e) => setTypedName(e.target.value)}
                  placeholder="Your full name"
                  className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
                />
              </div>
              <div className="mt-4 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setActiveSignature(null);
                    setStagedSignature(null);
                  }}
                  className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={saveSignature}
                  disabled={!stagedSignature}
                  className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
                >
                  <CheckCircle2 className="h-3.5 w-3.5" />
                  Save signature
                </button>
              </div>
            </section>
          )}
        </aside>
      </div>

      {confirmOpen && (
        <ConfirmSubmitDialog
          onCancel={() => setConfirmOpen(false)}
          onConfirm={submit}
          submitting={submitting}
        />
      )}
    </div>
  );
}

function ConfirmSubmitDialog({
  onCancel, onConfirm, submitting,
}: { onCancel: () => void; onConfirm: () => void; submitting: boolean }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-base font-semibold text-slate-900">Submit for verification?</h2>
        <p className="mt-2 text-sm text-slate-600">
          Your ERM will get a notification to verify what you've signed.
          You can't edit the document after submitting unless it comes back
          with corrections.
        </p>
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Not yet
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={submitting}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
            Submit
          </button>
        </div>
      </div>
    </div>
  );
}

function SaveIndicator({
  state,
  onRetry,
}: {
  state: SaveState;
  onRetry: () => void;
}) {
  if (state.kind === 'saving') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        Saving…
      </span>
    );
  }
  if (state.kind === 'dirty') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500">
        <Cloud className="h-3.5 w-3.5" />
        Unsaved changes
      </span>
    );
  }
  if (state.kind === 'saved') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-emerald-700">
        <Cloud className="h-3.5 w-3.5" />
        Saved
      </span>
    );
  }
  if (state.kind === 'error') {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-red-700" title={state.message}>
        <span className="inline-flex items-center gap-1.5">
          <CloudOff className="h-3.5 w-3.5" />
          {state.message}
        </span>
        {state.retriable && (
          <button
            type="button"
            onClick={onRetry}
            className="inline-flex items-center gap-1 rounded border border-red-200 bg-white px-1.5 py-0.5 text-[11px] font-medium text-red-700 hover:bg-red-50"
          >
            <RefreshCw className="h-3 w-3" />
            Retry
          </button>
        )}
      </span>
    );
  }
  return null;
}

function submitReasonWhenDisabled(
  disabled: boolean,
  blockingReason: string | null,
  state: SaveState,
): string {
  if (!disabled) return 'Submit';
  if (state.kind === 'saving') return 'Saving your changes — Submit unlocks in a moment.';
  if (blockingReason) return blockingReason;
  return 'Submit';
}

function externalStage(status: string): string {
  switch (status) {
    case 'DRAFT':            return 'Being prepared';
    case 'SENT_TO_INTERN':   return 'Awaiting your signature';
    case 'INTERN_SUBMITTED': return 'Signed — verifying';
    case 'RETURNED':         return 'Returned';
    case 'VERIFIED':         return 'Signed — verifying';
    case 'FINALIZED':        return 'Executed';
    case 'REVOKED':          return 'Revoked';
    case 'SUPERSEDED':       return 'Replaced';
    default: return status;
  }
}
