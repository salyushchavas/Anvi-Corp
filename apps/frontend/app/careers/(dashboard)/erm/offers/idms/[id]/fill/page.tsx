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
  Send,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import FieldForm, { type FieldFormHandle } from '@/components/idms/FieldForm';
import SignaturePad, { type SignaturePadHandle } from '@/components/idms/SignaturePad';
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

const AUTO_SAVE_DEBOUNCE_MS = 800;

type SaveState =
  | { kind: 'idle' }
  | { kind: 'dirty' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: Date }
  | { kind: 'error'; message: string };

function PageContent() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();

  const [detail, setDetail] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const [textValues, setTextValues] = useState<Record<string, string>>({});
  const [activeSignature, setActiveSignature] = useState<string | null>(null);
  const [typedName, setTypedName] = useState('');
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });

  const signaturePadRef = useRef<SignaturePadHandle | null>(null);
  const fieldFormRef = useRef<FieldFormHandle | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const skipNextAutoSaveRef = useRef(false); // seeds don't count as dirty

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
      skipNextAutoSaveRef.current = true;
      setTextValues(seed);
      setSaveState({ kind: 'idle' });
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
  const ermFields = useMemo(() => fields.filter((f) => f.assignee === 'ERM'), [fields]);
  const ermRequiredMissing = useMemo(() => {
    if (!detail) return [] as string[];
    const miss: string[] = [];
    for (const f of ermFields) {
      if (!f.required) continue;
      if (f.type === 'signature') {
        if (!detail.values[f.id]?.signatureUrl) miss.push(f.name);
      } else if (!(textValues[f.id] ?? '').trim()) {
        miss.push(f.name);
      }
    }
    return miss;
  }, [detail, ermFields, textValues]);

  // ── Auto-save on textValues change (debounced) ─────────────────────
  const persistText = useCallback(async (values: Record<string, string>) => {
    if (!detail) return;
    const payload: Record<string, string> = {};
    for (const f of ermFields) {
      if (f.type !== 'signature') payload[f.id] = values[f.id] ?? '';
    }
    if (Object.keys(payload).length === 0) {
      setSaveState({ kind: 'idle' });
      return;
    }
    setSaveState({ kind: 'saving' });
    try {
      const res = await api.post<InstanceDetail>(
        `/api/v1/erm/idms/${detail.id}/fill`, { values: payload });
      // Refresh persisted values from server without stomping on the
      // user's in-flight typing (we do NOT reseed textValues).
      setDetail(res.data);
      setSaveState({ kind: 'saved', at: new Date() });
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setSaveState({
        kind: 'error',
        message: ax.response?.data?.error ?? 'Save failed',
      });
    }
  }, [detail, ermFields]);

  useEffect(() => {
    if (!detail) return;
    if (skipNextAutoSaveRef.current) {
      skipNextAutoSaveRef.current = false;
      return;
    }
    setSaveState({ kind: 'dirty' });
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    debounceTimerRef.current = setTimeout(() => {
      void persistText(textValues);
    }, AUTO_SAVE_DEBOUNCE_MS);
    return () => {
      if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    };
  }, [textValues, detail, persistText]);

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
    const dataUrl = signaturePadRef.current?.toDataURL();
    if (!dataUrl) {
      toast.error('Please draw your signature first.');
      return;
    }
    try {
      const res = await api.post<InstanceDetail>(
        `/api/v1/erm/idms/${detail.id}/sign`,
        { fieldId: activeSignature, signatureImageDataUrl: dataUrl, typedName },
      );
      setDetail(res.data);
      signaturePadRef.current?.clear();
      setActiveSignature(null);
      setTypedName('');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Signature save failed');
    }
  }

  async function send() {
    if (!detail) return;
    if (ermRequiredMissing.length > 0) {
      toast.error('Complete required fields first: ' + ermRequiredMissing.join(', '));
      return;
    }
    if (!confirm('Send this document to the intern? They’ll be notified to fill and sign.')) return;
    setSending(true);
    try {
      // Cancel any pending debounced save, flush the final state
      // synchronously so the server has everything before SEND.
      if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
      await persistText(textValues);
      await api.post(`/api/v1/erm/idms/${detail.id}/send`);
      toast.success('Sent to intern.');
      router.push(`/careers/erm/offers/idms/${detail.id}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Send failed');
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

  const sendDisabled = sending || ermRequiredMissing.length > 0
    || saveState.kind === 'saving' || saveState.kind === 'dirty';

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
          <SaveIndicator state={saveState} />
          <button
            type="button"
            onClick={send}
            disabled={sendDisabled}
            title={sendReasonWhenDisabled(sendDisabled, ermRequiredMissing, saveState)}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            Send to intern
          </button>
        </div>
      </header>

      {ermRequiredMissing.length > 0 && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>
            Send is disabled until you complete:{' '}
            <span className="font-medium">{ermRequiredMissing.join(', ')}</span>
          </p>
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
              onTextChange={(id, v) => setTextValues((p) => ({ ...p, [id]: v }))}
              onOpenSignature={(fid) => setActiveSignature(fid)}
              activeSignatureFieldId={activeSignature}
              focusedFieldId={focusedField}
              onFocusField={setFocusedField}
            />
            <p className="mt-4 text-[11px] text-slate-400">
              Draft started {humanDate(detail.createdAt)} · autosaves as you type
            </p>
          </section>

          {activeSignature && (
            <section className="rounded-lg border border-brand-300 bg-white p-4 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-900">Signature</h3>
              <p className="mt-1 text-xs text-slate-500">
                Draw your signature below, then save.
              </p>
              <div className="mt-3">
                <SignaturePad ref={signaturePadRef} />
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
                  onClick={() => setActiveSignature(null)}
                  className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={submitSignature}
                  className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
                >
                  <CheckCircle2 className="h-3.5 w-3.5" />
                  Save signature
                </button>
              </div>
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}

function SaveIndicator({ state }: { state: SaveState }) {
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
      <span className="inline-flex items-center gap-1.5 text-xs text-red-700" title={state.message}>
        <CloudOff className="h-3.5 w-3.5" />
        Not saved
      </span>
    );
  }
  return null;
}

function sendReasonWhenDisabled(
  disabled: boolean,
  missing: string[],
  state: SaveState,
): string {
  if (!disabled) return 'Send to intern';
  if (state.kind === 'saving') return 'Saving your changes — the Send button unlocks in a moment.';
  if (state.kind === 'dirty') return 'Saving your changes — the Send button unlocks in a moment.';
  if (missing.length > 0) return 'Complete ' + missing.join(', ') + ' before sending.';
  return 'Send to intern';
}
