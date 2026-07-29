'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  FileText,
  Plus,
  RefreshCw,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import api from '@/lib/careers/api';
import RecordingUploader from '@/components/dashboard/RecordingUploader';

/**
 * Admin ⟶ Onboarding Documents. Master list of blank templates that
 * interns download during onboarding. Actions per row: Replace (upload
 * new file — S3-backed Document, gets served the moment the intern
 * fetches the download URL) and Remove (soft — active=false, keeps
 * in-progress packets resolving). Top action: Add template (name +
 * category + sensitivity; upload the file in a second step via
 * Replace).
 */
interface TemplateRow {
  id: string;
  key: string;
  title: string;
  category: string;
  sensitivity: string;
  description: string | null;
  currentDocumentId: string | null;
  currentFileName: string | null;
  currentFileBytes: number | null;
  currentMimeType: string | null;
  legacyStaticUrl: string | null;
  active: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}
interface ListResponse { items: TemplateRow[] }

const CATEGORIES = ['TAX', 'IMMIGRATION', 'EMPLOYMENT', 'LEGAL', 'INFORMATIONAL', 'OTHER'];
const SENSITIVITIES = ['GENERAL', 'FINANCIAL', 'GOVERNMENT_ID', 'PII'];

export default function AdminOnboardingDocumentsPage() {
  const [data, setData] = useState<ListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [replaceFor, setReplaceFor] = useState<TemplateRow | null>(null);
  const [removeFor, setRemoveFor] = useState<TemplateRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<ListResponse>('/api/v1/admin/onboarding-templates');
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load templates');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const items = data?.items ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/admin" className="hover:text-slate-700">← Admin home</Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Onboarding Documents</h1>
          <p className="text-xs text-slate-500">
            Master set of blank templates interns download during onboarding.
            Uploading a new file replaces what interns receive on the next
            download; removing a template deactivates it for new packets
            but keeps in-progress ones intact.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => void load()}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
          <button type="button" onClick={() => setAddOpen(true)}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800">
            <Plus className="h-3.5 w-3.5" /> Add template
          </button>
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            No templates yet. Add one with the button above.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Name</th>
                <th className="px-3 py-2">Category / Sensitivity</th>
                <th className="px-3 py-2">Current file</th>
                <th className="px-3 py-2">Updated</th>
                <th className="px-3 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((t) => {
                const hasS3 = !!t.currentDocumentId;
                const hasLegacy = !!t.legacyStaticUrl;
                const previewUrl = hasS3
                  ? `/api/v1/onboarding-templates/${encodeURIComponent(t.key)}/download-url`
                  : t.legacyStaticUrl;
                return (
                  <tr key={t.id} className={t.active ? 'hover:bg-slate-50' : 'bg-slate-50/60'}>
                    <td className="px-3 py-2">
                      <p className="text-sm font-medium text-slate-900">
                        {t.title}
                        {!t.active && (
                          <span className="ml-2 rounded bg-red-100 px-1.5 py-0.5 text-[10px] font-semibold text-red-700">
                            removed
                          </span>
                        )}
                      </p>
                      <p className="font-mono text-[10px] text-slate-500">{t.key}</p>
                    </td>
                    <td className="px-3 py-2 text-xs">
                      <span className="inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
                        {t.category}
                      </span>
                      <span className="ml-1 inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
                        {t.sensitivity}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {hasS3 ? (
                        <div>
                          <p className="truncate font-mono text-slate-700" title={t.currentFileName ?? ''}>
                            {t.currentFileName ?? '—'}
                          </p>
                          <p className="text-[10px] text-slate-500">
                            {fmtBytes(t.currentFileBytes)}
                            {t.currentMimeType && ` · ${t.currentMimeType}`}
                            <span className="ml-1 rounded bg-emerald-100 px-1 py-0.5 text-[10px] font-semibold text-emerald-800">
                              uploaded
                            </span>
                          </p>
                        </div>
                      ) : hasLegacy ? (
                        <p className="text-[11px] text-slate-500">
                          <span className="rounded bg-slate-100 px-1 py-0.5 text-[10px] font-semibold text-slate-700">
                            static
                          </span>
                          <span className="ml-1 truncate font-mono">
                            {t.legacyStaticUrl}
                          </span>
                        </p>
                      ) : (
                        <p className="text-[11px] text-slate-400 italic">
                          upload-only (intern uploads their own)
                        </p>
                      )}
                    </td>
                    <td className="px-3 py-2 text-xs text-slate-700">
                      {new Date(t.updatedAt).toLocaleDateString()}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <div className="inline-flex items-center gap-1">
                        {previewUrl && (
                          <a
                            href={previewUrl}
                            target="_blank"
                            rel="noreferrer noopener"
                            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
                          >
                            Preview
                          </a>
                        )}
                        <button type="button" onClick={() => setReplaceFor(t)}
                          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-1 text-[11px] font-semibold text-white hover:bg-brand-800">
                          <Upload className="h-3 w-3" /> Replace
                        </button>
                        <button type="button" onClick={() => setRemoveFor(t)}
                          disabled={!t.active}
                          className="inline-flex items-center gap-1 rounded-md border border-red-200 bg-white px-2 py-1 text-[11px] font-semibold text-red-700 hover:bg-red-50 disabled:opacity-40">
                          <Trash2 className="h-3 w-3" /> Remove
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {addOpen && (
        <AddTemplateModal
          onClose={() => setAddOpen(false)}
          onCreated={() => { setAddOpen(false); void load(); }}
        />
      )}
      {replaceFor && (
        <ReplaceFileModal
          template={replaceFor}
          onClose={() => setReplaceFor(null)}
          onReplaced={() => { setReplaceFor(null); void load(); }}
        />
      )}
      {removeFor && (
        <RemoveConfirmModal
          template={removeFor}
          onClose={() => setRemoveFor(null)}
          onRemoved={() => { setRemoveFor(null); void load(); }}
        />
      )}
    </div>
  );
}

function AddTemplateModal({ onClose, onCreated }: {
  onClose: () => void; onCreated: () => void;
}) {
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('EMPLOYMENT');
  const [sensitivity, setSensitivity] = useState('GENERAL');
  const [description, setDescription] = useState('');
  const [key, setKey] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!title.trim()) { setErr('Title required.'); return; }
    setSubmitting(true); setErr(null);
    try {
      await api.post('/api/v1/admin/onboarding-templates', {
        key: key.trim() || null,
        title: title.trim(),
        category,
        sensitivity,
        description: description.trim() || null,
      });
      onCreated();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to add template');
    } finally { setSubmitting(false); }
  }

  return (
    <ModalShell title="Add template" onClose={onClose}>
      <div className="space-y-3 p-5 text-sm">
        <Field label="Name *">
          <input type="text" value={title} onChange={(e) => setTitle(e.target.value)}
            maxLength={255}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Category *">
            <select value={category} onChange={(e) => setCategory(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="Sensitivity *">
            <select value={sensitivity} onChange={(e) => setSensitivity(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
              {SENSITIVITIES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </Field>
        </div>
        <Field label="Key (auto-derived from name if left blank)">
          <input type="text" value={key} onChange={(e) => setKey(e.target.value)}
            placeholder="e.g. CUSTOM_ADDENDUM_2026"
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
        <Field label="Description (optional)">
          <textarea value={description} onChange={(e) => setDescription(e.target.value)}
            rows={3} maxLength={2000}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
        <p className="rounded-md border border-slate-200 bg-slate-50 p-2 text-[11px] text-slate-600">
          After adding the template, use Replace on its row to upload the blank PDF.
        </p>
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
        <button type="button" onClick={submit} disabled={submitting}
          className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
          {submitting ? 'Adding…' : 'Add template'}
        </button>
      </div>
    </ModalShell>
  );
}

function ReplaceFileModal({ template, onClose, onReplaced }: {
  template: TemplateRow; onClose: () => void; onReplaced: () => void;
}) {
  const [attached, setAttached] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function attach(docId: string) {
    setSaving(true); setErr(null);
    try {
      await api.post(
        `/api/v1/admin/onboarding-templates/${template.id}/file`,
        { documentId: docId });
      setAttached(docId);
      onReplaced();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save file');
    } finally { setSaving(false); }
  }

  return (
    <ModalShell title={`Replace file — ${template.title}`} onClose={onClose}>
      <div className="space-y-3 p-5 text-sm">
        <p className="text-xs text-slate-500">
          Upload a new blank PDF (or other file). Interns downloading this
          template on the next fetch will receive the NEW file; the previous
          version is soft-deleted (bytes retained in S3 for audit).
        </p>
        <RecordingUploader
          key={template.id}
          accept="*/*"
          presignEndpoint={`/api/v1/admin/onboarding-templates/${template.id}/file/presign-upload`}
          onReady={(docId) => { void attach(docId); }}
          helperText="Direct-to-S3 upload. 50 MB max."
        />
        {saving && <p className="text-xs text-slate-500">Saving…</p>}
        {attached && (
          <p className="rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
            New file attached — this template will now serve the uploaded version.
          </p>
        )}
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Close</button>
      </div>
    </ModalShell>
  );
}

function RemoveConfirmModal({ template, onClose, onRemoved }: {
  template: TemplateRow; onClose: () => void; onRemoved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    setBusy(true); setErr(null);
    try {
      await api.delete(`/api/v1/admin/onboarding-templates/${template.id}`);
      onRemoved();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to remove');
    } finally { setBusy(false); }
  }
  return (
    <ModalShell title={`Remove template — ${template.title}`} onClose={onClose}>
      <div className="space-y-3 p-5 text-sm">
        <p className="text-xs text-slate-700">
          Deactivates this template (active=false). New packets won't include
          it, but any intern who was already assigned it can still download
          the current file — nothing breaks mid-onboarding. The row stays in
          the list marked "removed" for audit.
        </p>
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
        <button type="button" onClick={submit} disabled={busy}
          className="rounded-md bg-red-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-red-800 disabled:bg-slate-300">
          {busy ? 'Removing…' : 'Remove template'}
        </button>
      </div>
    </ModalShell>
  );
}

function ModalShell({ title, onClose, children }: {
  title: string; onClose: () => void; children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900 inline-flex items-center gap-2">
            <FileText className="h-4 w-4 text-brand-700" />
            {title}
          </h3>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function fmtBytes(n: number | null): string {
  if (n == null) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
