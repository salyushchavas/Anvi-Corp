'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Cloud,
  CloudOff,
  Loader2,
  RefreshCw,
  Send,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import FieldForm, { type FieldFormHandle } from '@/components/idms/FieldForm';
import SignatureCapture from '@/components/idms/SignatureCapture';
import ConfirmDialog from '@/components/ConfirmDialog';
import { useSignatureBlobs } from '@/components/idms/useSignatureBlobs';
import {
  useCoalescedAutoSave,
  type SaveState,
} from '@/components/idms/useCoalescedAutoSave';
import { useFillCompleteness } from '@/components/idms/useFillCompleteness';
import { useAuth } from '@/lib/careers/auth-context';
import {
  humanDate,
  parseFieldSchema,
  type FieldSchemaEntry,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * ERM fill page — LEFT: live document preview (read-only, click any anchor
 * to focus its field in the panel). RIGHT: field panel with guided checklist,
 * completion count, auto-save. Send transitions DRAFT → SENT_TO_INTERN.
 *
 * <h3>Structural corrective — reactive derivation</h3>
 * <p>The completeness counter, Send-button gate, and blocking-reason banner
 * are ALL derived from {@link useFillCompleteness}, a single reactive
 * selector over (fields, textValues, detail.values, optimisticSignatures).
 * The three surfaces cannot disagree on what "complete" means — the F1
 * exemplar bug (Send stuck disabled because a derived-from-snapshot flag
 * went stale) has exactly one place to be audited.</p>
 *
 * <h3>Save/Send race</h3>
 * <p>Auto-save runs through {@link useCoalescedAutoSave} — a single-slot
 * queue that never drops the latest keystroke and never fires two
 * concurrent /fill calls. Send explicitly flushes pending saves via
 * {@code controller.flush(textValues)} and then posts the SAME values
 * on the {@code /send} body as the server-side authority — a final
 * keystroke can never be dropped between save and transition.</p>
 */
export default function ErmFillPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout title="Fill document">
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
  const [sending, setSending] = useState(false);
  // Send-confirmation card — opens when the ERM clicks "Send to intern"
  // and blocks the actual /send POST until the ERM confirms in the
  // dialog. Cancel closes with no side effects; confirm calls send().
  const [confirmSendOpen, setConfirmSendOpen] = useState(false);

  const [textValues, setTextValues] = useState<Record<string, string>>({});
  const [activeSignature, setActiveSignature] = useState<string | null>(null);
  const [typedName, setTypedName] = useState('');
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const [stagedSignature, setStagedSignature] = useState<string | null>(null);
  // Optimistic signature tracking — after the drawer's Save signature
  // returns, the fieldId sits here until the next detail refetch fills
  // detail.values[id].signatureUrl. Without this the completeness selector
  // would flash "incomplete" for ~200 ms after signing (F15).
  const [optimisticSigned, setOptimisticSigned] = useState<Set<string>>(new Set());

  const { user } = useAuth();
  const fieldFormRef = useRef<FieldFormHandle | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InstanceDetail>(`/api/v1/erm/idms/${id}`);
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
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load document');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  const fields: FieldSchemaEntry[] = useMemo(
    () => detail ? parseFieldSchema(detail.fieldSchemaJson) : [],
    [detail],
  );
  const signatureBlobs = useSignatureBlobs(detail);
  // Refs so persist() reads the FRESH detail (expectedUpdatedAt) and
  // field list without re-arming the auto-save on every server response.
  const detailRef = useRef<InstanceDetail | null>(null);
  const fieldsRef = useRef<FieldSchemaEntry[]>([]);
  useEffect(() => { detailRef.current = detail; }, [detail]);
  useEffect(() => { fieldsRef.current = fields; }, [fields]);

  const completeness = useFillCompleteness({
    detail,
    fields,
    role: 'ERM',
    textValues,
    optimisticallySignedFieldIds: optimisticSigned,
  });

  // ── Auto-save: coalesced single-slot with content-equal no-op ──────
  const persist = useCallback(async (
    values: Record<string, string>,
  ): Promise<boolean> => {
    const currentDetail = detailRef.current;
    const currentFields = fieldsRef.current;
    if (!currentDetail) return false;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== 'ERM') continue;
      if (f.type === 'signature') continue;
      payload[f.id] = values[f.id] ?? '';
    }
    if (Object.keys(payload).length === 0) return true;
    const res = await api.post<InstanceDetail>(
      `/api/v1/erm/idms/${currentDetail.id}/fill`,
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
    enabled: true,
    onConflict: () => { void load(); },
  });
  const { saveState, scheduleSave, flush, retry, seedLastSavedKey } = saveController;

  // Seed the last-saved key on load so an initial no-op scheduleSave
  // (from setTextValues in load()) is short-circuited.
  useEffect(() => {
    if (!detail) return;
    const currentFields = fieldsRef.current;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== 'ERM' || f.type === 'signature') continue;
      payload[f.id] = textValues[f.id] ?? '';
    }
    seedLastSavedKey(payload);
    // Intentionally seeds only on detail identity (fresh load / server
    // response) — not on every textValues keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail, seedLastSavedKey]);

  function onTextChange(id: string, v: string) {
    setTextValues((p) => {
      const next = { ...p, [id]: v };
      scheduleSave(next);
      return next;
    });
  }

  // Prompt if the user tries to close/navigate mid-debounce.
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

  async function submitSignature() {
    if (!detail || !activeSignature) return;
    if (!stagedSignature) {
      toast.error('Prepare a signature first.');
      return;
    }
    const fieldId = activeSignature;
    try {
      const res = await api.post<InstanceDetail>(
        `/api/v1/erm/idms/${detail.id}/sign`,
        { fieldId, signatureImageDataUrl: stagedSignature, typedName },
      );
      setDetail(res.data);
      // Optimistically mark done — the returned detail already has the
      // signatureUrl but this handles any renderer race.
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

  // Gatekeeper — validates completeness THEN opens the confirmation
  // card. The actual POST lives in send() and only runs when the ERM
  // clicks Confirm in the dialog.
  function openSendConfirm() {
    if (!detail) return;
    if (!completeness.canTransition) {
      toast.error(completeness.blockingReason ?? 'Complete required fields first.');
      return;
    }
    setConfirmSendOpen(true);
  }

  async function send() {
    if (!detail) return;
    setConfirmSendOpen(false);
    setSending(true);
    try {
      // Flush any pending debounced save with the LATEST values — the
      // controller awaits in-flight + coalesced pending. If the flush
      // fails, refuse to proceed so the intern can't receive stale bytes.
      const saved = await flush(textValues);
      if (!saved) {
        toast.error("Couldn't save your latest changes — please try again in a moment.");
        setSending(false);
        return;
      }
      // Post the SAME values as the authority on /send so a final
      // keystroke can't be dropped between save and transition.
      const latestUpdatedAt = detailRef.current?.updatedAt;
      const payload: Record<string, string> = {};
      for (const f of fields) {
        if (f.assignee !== 'ERM' || f.type === 'signature') continue;
        payload[f.id] = textValues[f.id] ?? '';
      }
      await api.post(`/api/v1/erm/idms/${detail.id}/send`, {
        values: payload,
        expectedUpdatedAt: latestUpdatedAt
          ? Date.parse(latestUpdatedAt) || undefined
          : undefined,
      });
      toast.success('Sent to intern.');
      router.push(`/careers/erm/offers/idms/${detail.id}`);
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
      };
      if (ax.response?.status === 409) {
        toast.error('This document changed elsewhere — reloading the latest state.');
        void load();
      } else {
        toast.error(ax.response?.data?.error ?? 'Send failed');
      }
      setSending(false);
    }
  }

  function onFieldClickInPreview(fieldId: string) {
    const f = fields.find((x) => x.id === fieldId);
    if (!f) return;
    if (f.assignee === 'ERM' && f.type === 'signature') {
      setActiveSignature(fieldId);
      return;
    }
    if (f.assignee === 'ERM') {
      fieldFormRef.current?.focusField(fieldId);
      setFocusedField(fieldId);
    }
  }

  if (loading) {
    return <div className="mx-auto max-w-4xl p-6"><div className="h-64 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err || !detail) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err ?? 'Document not found.'}
        </div>
      </div>
    );
  }

  if (detail.status !== 'DRAFT') {
    return (
      <div className="mx-auto max-w-3xl p-6 space-y-4">
        <Link
          href={`/careers/erm/offers/idms/${detail.id}`}
          className="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-800"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          Back to document
        </Link>
        <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="font-medium">This document isn't in draft anymore.</p>
          <p className="mt-1 text-xs">Open the detail page to verify, revoke, or view its history.</p>
        </div>
      </div>
    );
  }

  // Send stays disabled while a save is actively in flight (dirty is
  // fine — flush() will settle it, so we don't block the button on the
  // debounce window). Completeness comes from the shared selector.
  const sendDisabled = sending
    || !completeness.canTransition
    || saveState.kind === 'saving';

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
            For {detail.internName} · {detail.internEmail}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500">
            {completeness.ownDone} of {completeness.ownTotal} complete
          </span>
          <SaveIndicator state={saveState} onRetry={retry} />
          <button
            type="button"
            onClick={openSendConfirm}
            disabled={sendDisabled}
            title={sendReasonWhenDisabled(sendDisabled, completeness.blockingReason, saveState)}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            Send to intern
          </button>
        </div>
      </header>

      {completeness.blockingReason && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{completeness.blockingReason}</p>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="max-h-[calc(100vh-220px)] overflow-y-auto p-2">
            <InstanceRenderer
              detail={detail}
              fields={fields}
              editRole="ERM"
              textValues={textValues}
              signatureBlobs={signatureBlobs}
              focusedFieldId={focusedField}
              onFieldClick={onFieldClickInPreview}
              activeSignatureFieldId={activeSignature}
            />
          </div>
        </section>

        <aside className="space-y-4">
          <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <FieldForm
              ref={fieldFormRef}
              detail={detail}
              fields={fields}
              role="ERM"
              textValues={textValues}
              onTextChange={onTextChange}
              onOpenSignature={(fid) => setActiveSignature(fid)}
              activeSignatureFieldId={activeSignature}
              focusedFieldId={focusedField}
              onFocusField={setFocusedField}
              signatureBlobs={signatureBlobs}
            />
            <p className="mt-4 text-[11px] text-slate-400">
              Draft started {humanDate(detail.createdAt)} · autosaves as you type
            </p>
          </section>

          {activeSignature && (
            <section className="rounded-lg border border-brand-300 bg-white p-4 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-900">Signature</h3>
              <p className="mt-1 text-xs text-slate-500">
                Pick a way to sign. Whichever method you use, the same signature
                lands in the document.
              </p>
              <div className="mt-3">
                <SignatureCapture
                  initialName={user?.fullName ?? ''}
                  onChange={setStagedSignature}
                />
              </div>
              <div className="mt-3">
                <label className="text-xs font-medium text-slate-600">Typed name (for the record)</label>
                <input
                  value={typedName}
                  onChange={(e) => setTypedName(e.target.value)}
                  placeholder="Your name"
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
                  onClick={submitSignature}
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

      <ConfirmDialog
        open={confirmSendOpen}
        onClose={() => setConfirmSendOpen(false)}
        onConfirm={send}
        title="Send this document to the intern?"
        description="They'll be notified to fill and sign."
        confirmLabel="Send"
        cancelLabel="Cancel"
        variant="primary"
      />
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

function sendReasonWhenDisabled(
  disabled: boolean,
  blockingReason: string | null,
  state: SaveState,
): string {
  if (!disabled) return 'Send to intern';
  if (state.kind === 'saving') return 'Saving your changes — the Send button unlocks in a moment.';
  if (blockingReason) return blockingReason;
  return 'Send to intern';
}
