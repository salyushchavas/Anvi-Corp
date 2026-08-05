'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Info,
  Loader2,
  Save,
  Send,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import SignaturePad, { type SignaturePadHandle } from '@/components/idms/SignaturePad';
import {
  humanDate,
  parseFieldSchema,
  type FieldSchemaEntry,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * ERM fill page — the docx-preview canonical HTML is re-rendered with the
 * ERM's fields as inputs (in-place), INTERN placeholders shown as amber
 * "awaits", AUTO fields pre-filled + locked. Signature fields open the
 * SignaturePad in the right rail. Save persists the current field values;
 * Send transitions DRAFT → SENT_TO_INTERN.
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
  const [saving, setSaving] = useState(false);
  const [sending, setSending] = useState(false);
  const [textValues, setTextValues] = useState<Record<string, string>>({});
  const [activeSignature, setActiveSignature] = useState<string | null>(null);
  const [typedName, setTypedName] = useState('');
  const signaturePadRef = useRef<SignaturePadHandle | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<InstanceDetail>(`/api/v1/erm/idms/${id}`);
      setDetail(res.data);
      // Seed textValues from persisted values so the inputs show the last
      // saved state on re-open.
      const seed: Record<string, string> = {};
      for (const [k, v] of Object.entries(res.data.values ?? {})) {
        if (v.valueText != null && v.type !== 'signature') seed[k] = v.valueText;
      }
      setTextValues(seed);
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

  async function save() {
    if (!detail) return;
    const payload: Record<string, string> = {};
    for (const f of ermFields) {
      if (f.type !== 'signature') payload[f.id] = textValues[f.id] ?? '';
    }
    if (Object.keys(payload).length === 0) return;
    setSaving(true);
    try {
      const res = await api.post<InstanceDetail>(
        `/api/v1/erm/idms/${detail.id}/fill`, { values: payload });
      setDetail(res.data);
      toast.success('Saved.');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

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
      toast.success('Signature captured.');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Signature save failed');
    }
  }

  async function send() {
    if (!detail) return;
    if (ermRequiredMissing.length > 0) {
      toast.error('Please complete: ' + ermRequiredMissing.join(', '));
      return;
    }
    if (!confirm('Send this document to the intern? They’ll be notified to fill and sign.')) return;
    setSending(true);
    try {
      // Persist any pending edits before sending.
      const payload: Record<string, string> = {};
      for (const f of ermFields) {
        if (f.type !== 'signature') payload[f.id] = textValues[f.id] ?? '';
      }
      if (Object.keys(payload).length > 0) {
        await api.post(`/api/v1/erm/idms/${detail.id}/fill`, { values: payload });
      }
      await api.post(`/api/v1/erm/idms/${detail.id}/send`);
      toast.success('Sent to intern.');
      router.push(`/careers/erm/offers/idms/${detail.id}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Send failed');
      setSending(false);
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
          <p className="font-medium">This document isn’t in draft anymore.</p>
          <p className="mt-1 text-xs">Open the detail page to verify, revoke, or view its history.</p>
        </div>
      </div>
    );
  }

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
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={save}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            Save
          </button>
          <button
            type="button"
            onClick={send}
            disabled={sending}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            title={ermRequiredMissing.length > 0
              ? 'Complete ' + ermRequiredMissing.join(', ')
              : 'Send to intern'}
          >
            {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            Send to intern
          </button>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        <section className="rounded-lg border border-slate-200 bg-white shadow-sm p-2">
          <div className="border-b border-slate-100 px-3 py-2 text-xs text-slate-500 flex items-center gap-2">
            <Info className="h-3.5 w-3.5" />
            Your fields are highlighted blue. The intern’s fields (amber) stay
            empty until they open the document.
          </div>
          <div className="max-h-[70vh] overflow-y-auto p-2">
            <InstanceRenderer
              detail={detail}
              fields={fields}
              editRole="ERM"
              textValues={textValues}
              onTextChange={(id, v) => setTextValues((p) => ({ ...p, [id]: v }))}
              activeSignatureFieldId={activeSignature}
              onOpenSignature={(fid) => setActiveSignature(fid)}
            />
          </div>
        </section>

        <aside className="space-y-4">
          {activeSignature ? (
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
          ) : (
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-900">Your checklist</h3>
              <ul className="mt-3 space-y-1.5 text-sm">
                {ermFields.map((f) => {
                  const done = f.type === 'signature'
                    ? Boolean(detail.values[f.id]?.signatureUrl)
                    : Boolean((textValues[f.id] ?? '').trim());
                  return (
                    <li key={f.id} className="flex items-start gap-2">
                      <span className={`mt-0.5 inline-block h-3 w-3 rounded-full ${done ? 'bg-emerald-500' : 'bg-slate-300'}`} />
                      <span className={done ? 'text-slate-700 line-through' : 'text-slate-800'}>
                        {f.name}{f.required && <span className="text-red-500"> *</span>}
                      </span>
                    </li>
                  );
                })}
              </ul>
              {ermRequiredMissing.length > 0 && (
                <div className="mt-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span>Complete required items before sending.</span>
                </div>
              )}
              <p className="mt-4 text-xs text-slate-500">
                Created {humanDate(detail.createdAt)}
              </p>
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}
