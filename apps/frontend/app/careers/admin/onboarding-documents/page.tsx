'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CheckCircle2,
  Cloud,
  Eye,
  FileText,
  FileX,
  Filter,
  Layers,
  Plus,
  RefreshCw,
  Search,
  Trash2,
  Upload,
  UploadCloud,
  X,
} from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import RecordingUploader from '@/components/dashboard/RecordingUploader';

/**
 * Admin ⟶ Onboarding Documents. Master list of blank templates that
 * interns download during onboarding. Wrapped in DashboardLayout so
 * the admin sidebar + topbar render alongside — the page owns only
 * the content column.
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

const CATEGORIES = ['TAX', 'IMMIGRATION', 'EMPLOYMENT', 'LEGAL', 'INFORMATIONAL', 'OTHER'] as const;
const SENSITIVITIES = ['GENERAL', 'FINANCIAL', 'GOVERNMENT_ID', 'PII'] as const;

/** Per-category tone so the table reads at a glance without hunting
 *  for the label — colors chosen to be distinct without collision. */
const CATEGORY_TONE: Record<string, string> = {
  TAX:           'bg-emerald-50 text-emerald-800 border-emerald-200',
  IMMIGRATION:   'bg-violet-50 text-violet-800 border-violet-200',
  EMPLOYMENT:    'bg-sky-50 text-sky-800 border-sky-200',
  LEGAL:         'bg-amber-50 text-amber-900 border-amber-200',
  INFORMATIONAL: 'bg-slate-50 text-slate-700 border-slate-200',
  OTHER:         'bg-slate-50 text-slate-700 border-slate-200',
};
const SENSITIVITY_TONE: Record<string, string> = {
  GENERAL:       'bg-slate-100 text-slate-700',
  FINANCIAL:     'bg-red-50 text-red-700',
  GOVERNMENT_ID: 'bg-red-100 text-red-800',
  PII:           'bg-red-50 text-red-700',
};

export default function AdminOnboardingDocumentsPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN', 'JOBS_ADMIN']}>
      <DashboardLayout title="Onboarding Documents">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const [data, setData] = useState<ListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [replaceFor, setReplaceFor] = useState<TemplateRow | null>(null);
  const [removeFor, setRemoveFor] = useState<TemplateRow | null>(null);
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  const [showRemoved, setShowRemoved] = useState(false);

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

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return items.filter((t) => {
      if (!showRemoved && !t.active) return false;
      if (categoryFilter && t.category !== categoryFilter) return false;
      if (q && !`${t.title} ${t.key} ${t.description ?? ''}`.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [items, search, categoryFilter, showRemoved]);

  const stats = useMemo(() => {
    const active = items.filter((t) => t.active).length;
    const removed = items.filter((t) => !t.active).length;
    const withUpload = items.filter((t) => !!t.currentDocumentId).length;
    return { active, removed, withUpload, total: items.length };
  }, [items]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-700">
            Super Admin · Templates
          </p>
          <h1 className="mt-1 text-2xl font-semibold text-slate-900">Onboarding Documents</h1>
          <p className="mt-1 max-w-2xl text-sm text-slate-600">
            Master set of blank templates interns download during onboarding.
            Uploading a new file replaces what interns receive on the next
            download; removing a template deactivates it for new packets but
            keeps in-progress ones intact.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-medium text-slate-700 shadow-sm hover:bg-slate-50">
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
          <button type="button" onClick={() => setAddOpen(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-2 text-xs font-semibold text-white shadow-sm hover:bg-brand-800">
            <Plus className="h-3.5 w-3.5" /> Add template
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard icon={<Layers className="h-4 w-4 text-slate-500" />}
          label="Total" value={stats.total} />
        <StatCard icon={<CheckCircle2 className="h-4 w-4 text-emerald-600" />}
          label="Active" value={stats.active} tone="emerald" />
        <StatCard icon={<Cloud className="h-4 w-4 text-brand-600" />}
          label="Custom file uploaded" value={stats.withUpload} tone="brand" />
        <StatCard icon={<FileX className="h-4 w-4 text-red-500" />}
          label="Removed" value={stats.removed} tone="red" />
      </div>

      {/* Filters */}
      <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-[240px] flex-1">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by name, key, or description"
              className="w-full rounded-md border border-slate-200 pl-8 pr-3 py-1.5 text-sm"
            />
          </div>
          <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <input type="checkbox" checked={showRemoved}
              onChange={(e) => setShowRemoved(e.target.checked)}
              className="h-3.5 w-3.5" />
            Show removed
          </label>
          <span className="ml-auto inline-flex items-center gap-1 text-xs text-slate-500">
            <Filter className="h-3 w-3" />
            {filtered.length} of {items.length}
          </span>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <span className="text-[10px] uppercase tracking-wide text-slate-400">Category</span>
          <FilterChip active={!categoryFilter} onClick={() => setCategoryFilter('')} label="All" />
          {CATEGORIES.map((c) => (
            <FilterChip key={c} active={categoryFilter === c}
              onClick={() => setCategoryFilter(categoryFilter === c ? '' : c)}
              label={c} tone={CATEGORY_TONE[c]} />
          ))}
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
        {loading && !data ? (
          <SkeletonRows />
        ) : filtered.length === 0 ? (
          <EmptyState hasAny={items.length > 0}
            onAdd={() => setAddOpen(true)} onClear={() => {
              setSearch(''); setCategoryFilter(''); setShowRemoved(false);
            }} />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                  <th className="px-4 py-3">Template</th>
                  <th className="px-4 py-3">Category</th>
                  <th className="px-4 py-3">Current file</th>
                  <th className="px-4 py-3">Updated</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map((t) => (
                  <TemplateRow key={t.id} t={t}
                    onReplace={() => setReplaceFor(t)}
                    onRemove={() => setRemoveFor(t)} />
                ))}
              </tbody>
            </table>
          </div>
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

function TemplateRow({ t, onReplace, onRemove }: {
  t: TemplateRow;
  onReplace: () => void;
  onRemove: () => void;
}) {
  const hasS3 = !!t.currentDocumentId;
  const hasLegacy = !!t.legacyStaticUrl;
  const canPreview = hasS3 || hasLegacy;
  const [previewing, setPreviewing] = useState(false);
  const [previewErr, setPreviewErr] = useState<string | null>(null);

  async function handlePreview() {
    if (previewing) return;
    setPreviewErr(null);
    // Legacy-only rows point at a public static asset — no auth or presign
    // needed, so open it directly and skip the round-trip.
    if (!hasS3 && hasLegacy && t.legacyStaticUrl) {
      window.open(t.legacyStaticUrl, '_blank', 'noopener,noreferrer');
      return;
    }
    // For uploaded files we must hit the authenticated resolve endpoint to
    // get a presigned S3 URL back, then open THAT — the endpoint returns
    // JSON, not the file, and can't be linked to directly from a new tab
    // (no Bearer token would be attached).
    setPreviewing(true);
    try {
      const res = await api.get<{ url: string }>(
        `/api/v1/onboarding-templates/${encodeURIComponent(t.key)}/download-url`
      );
      const url = res.data?.url;
      if (!url) throw new Error('No URL returned by server');
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setPreviewErr(ax.response?.data?.error ?? ax.message ?? 'Preview failed');
    } finally {
      setPreviewing(false);
    }
  }

  return (
    <tr className={t.active ? 'group hover:bg-slate-50/50' : 'bg-slate-50/40 opacity-70'}>
      <td className="max-w-md px-4 py-3">
        <div className="flex items-start gap-2.5">
          <div className={
            'mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md '
            + (hasS3 ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-500')
          }>
            <FileText className="h-4 w-4" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <p className="truncate text-sm font-semibold text-slate-900" title={t.title}>
                {t.title}
              </p>
              {!t.active && (
                <span className="rounded bg-red-100 px-1.5 py-0.5 text-[10px] font-semibold text-red-700">
                  removed
                </span>
              )}
            </div>
            <p className="mt-0.5 truncate font-mono text-[10px] text-slate-500" title={t.key}>
              {t.key}
            </p>
            {t.description && (
              <p className="mt-1 line-clamp-2 text-[11px] text-slate-500" title={t.description}>
                {t.description}
              </p>
            )}
          </div>
        </div>
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col gap-1">
          <span className={
            'inline-flex w-fit items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold '
            + (CATEGORY_TONE[t.category] ?? CATEGORY_TONE.OTHER)
          }>
            {t.category}
          </span>
          <span className={
            'inline-flex w-fit rounded px-1.5 py-0.5 text-[10px] font-medium '
            + (SENSITIVITY_TONE[t.sensitivity] ?? SENSITIVITY_TONE.GENERAL)
          }>
            {t.sensitivity.replaceAll('_', ' ')}
          </span>
        </div>
      </td>
      <td className="max-w-xs px-4 py-3">
        {hasS3 ? (
          <div>
            <p className="truncate text-xs font-medium text-slate-800" title={t.currentFileName ?? ''}>
              {t.currentFileName ?? '—'}
            </p>
            <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[10px] text-slate-500">
              <span>{fmtBytes(t.currentFileBytes)}</span>
              {t.currentMimeType && <span>· {t.currentMimeType}</span>}
              <span className="inline-flex items-center gap-0.5 rounded bg-emerald-100 px-1.5 py-0.5 font-semibold text-emerald-800">
                <UploadCloud className="h-2.5 w-2.5" /> uploaded
              </span>
            </div>
          </div>
        ) : hasLegacy ? (
          <div>
            <p className="text-xs text-slate-700">Legacy static asset</p>
            <p className="mt-0.5 truncate font-mono text-[10px] text-slate-500"
               title={t.legacyStaticUrl ?? ''}>
              {t.legacyStaticUrl}
            </p>
          </div>
        ) : (
          <p className="text-[11px] italic text-slate-400">
            Upload-only — intern uploads their own scan
          </p>
        )}
      </td>
      <td className="px-4 py-3 text-xs text-slate-600">
        {formatDate(t.updatedAt)}
      </td>
      <td className="whitespace-nowrap px-4 py-3 text-right">
        <div className="inline-flex flex-col items-end gap-1">
          <div className="inline-flex items-center gap-1">
            {canPreview && (
              <button
                type="button"
                onClick={() => void handlePreview()}
                disabled={previewing}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                title="Preview file"
              >
                <Eye className="h-3 w-3" /> {previewing ? 'Opening…' : 'Preview'}
              </button>
            )}
            <button type="button" onClick={onReplace}
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
              title="Upload a new file for this template">
              <Upload className="h-3 w-3" /> Replace
            </button>
            <button type="button" onClick={onRemove}
              disabled={!t.active}
              className="inline-flex items-center gap-1 rounded-md border border-red-200 bg-white px-2 py-1 text-[11px] font-semibold text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
              title={t.active ? 'Soft-remove this template' : 'Already removed'}>
              <Trash2 className="h-3 w-3" /> Remove
            </button>
          </div>
          {previewErr && (
            <p className="max-w-[260px] truncate text-[10px] text-red-600" title={previewErr}>
              {previewErr}
            </p>
          )}
        </div>
      </td>
    </tr>
  );
}

function StatCard({ icon, label, value, tone = 'slate' }: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone?: 'slate' | 'emerald' | 'brand' | 'red';
}) {
  const toneCls =
    tone === 'emerald' ? 'text-emerald-700' :
    tone === 'brand'   ? 'text-brand-700' :
    tone === 'red'     ? 'text-red-700' :
    'text-slate-800';
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <div className="flex items-center justify-between">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {label}
        </p>
        {icon}
      </div>
      <p className={`mt-1 text-2xl font-semibold ${toneCls}`}>{value}</p>
    </div>
  );
}

function FilterChip({ active, onClick, label, tone }: {
  active: boolean;
  onClick: () => void;
  label: string;
  tone?: string;
}) {
  return (
    <button type="button" onClick={onClick}
      className={
        'inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium transition-colors '
        + (active
          ? 'border-brand-700 bg-brand-700 text-white'
          : (tone ?? 'border-slate-200 bg-white text-slate-700') + ' hover:bg-slate-50')
      }>
      {label}
    </button>
  );
}

function SkeletonRows() {
  return (
    <div className="divide-y divide-slate-100">
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="flex items-center gap-4 p-4">
          <div className="h-8 w-8 shrink-0 animate-pulse rounded bg-slate-100" />
          <div className="h-3 w-64 animate-pulse rounded bg-slate-100" />
          <div className="h-3 w-20 animate-pulse rounded bg-slate-100" />
          <div className="ml-auto h-6 w-24 animate-pulse rounded bg-slate-100" />
        </div>
      ))}
    </div>
  );
}

function EmptyState({ hasAny, onAdd, onClear }: {
  hasAny: boolean; onAdd: () => void; onClear: () => void;
}) {
  return (
    <div className="flex flex-col items-center gap-3 p-12 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-100">
        <FileText className="h-6 w-6 text-slate-500" />
      </div>
      {hasAny ? (
        <>
          <p className="text-sm font-semibold text-slate-800">No templates match your filters.</p>
          <button type="button" onClick={onClear}
            className="text-xs font-medium text-brand-700 hover:underline">
            Clear filters
          </button>
        </>
      ) : (
        <>
          <p className="text-sm font-semibold text-slate-800">No templates yet.</p>
          <p className="max-w-sm text-xs text-slate-500">
            Add your first onboarding template — interns will see it in their
            packet download list.
          </p>
          <button type="button" onClick={onAdd}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800">
            <Plus className="h-3.5 w-3.5" /> Add template
          </button>
        </>
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
    <ModalShell title="Add template" icon={<Plus className="h-4 w-4 text-brand-700" />} onClose={onClose}>
      <div className="space-y-4 p-5 text-sm">
        <Field label="Template name *">
          <input type="text" value={title} onChange={(e) => setTitle(e.target.value)}
            maxLength={255}
            placeholder="e.g. Custom Onboarding Addendum 2026"
            className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Category *">
            <select value={category} onChange={(e) => setCategory(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm">
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="Sensitivity *">
            <select value={sensitivity} onChange={(e) => setSensitivity(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm">
              {SENSITIVITIES.map((s) => <option key={s} value={s}>{s.replaceAll('_', ' ')}</option>)}
            </select>
          </Field>
        </div>
        <Field label="Key (auto-derived from name if blank)">
          <input type="text" value={key} onChange={(e) => setKey(e.target.value)}
            placeholder="CUSTOM_ADDENDUM_2026"
            className="w-full rounded-md border border-slate-200 px-3 py-2 font-mono text-xs" />
        </Field>
        <Field label="Description (optional)">
          <textarea value={description} onChange={(e) => setDescription(e.target.value)}
            rows={3} maxLength={2000}
            placeholder="One-line description shown to admins and to interns in their packet."
            className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
        </Field>
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
        <p className="rounded-md border border-slate-200 bg-slate-50 p-2 text-[11px] text-slate-600">
          Next step: use <strong>Replace</strong> on the new row to upload the blank PDF file.
        </p>
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm hover:bg-slate-50">
          Cancel
        </button>
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
  const [attached, setAttached] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function attach(docId: string) {
    setSaving(true); setErr(null);
    try {
      await api.post(
        `/api/v1/admin/onboarding-templates/${template.id}/file`,
        { documentId: docId });
      setAttached(true);
      // Give the user a beat to see the success state before we reload.
      setTimeout(() => onReplaced(), 900);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save file');
    } finally { setSaving(false); }
  }

  return (
    <ModalShell
      title={`Replace file · ${template.title}`}
      icon={<Upload className="h-4 w-4 text-brand-700" />}
      onClose={onClose}
    >
      <div className="space-y-4 p-5 text-sm">
        {template.currentFileName && (
          <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Currently serving
            </p>
            <p className="mt-1 truncate font-mono text-slate-700" title={template.currentFileName}>
              {template.currentFileName}
            </p>
          </div>
        )}
        <p className="text-xs text-slate-600">
          Upload a new blank file. Interns downloading this template next
          will receive the <strong>new</strong> version; the previous
          version is soft-deleted (bytes retained for audit).
        </p>
        <RecordingUploader
          key={template.id}
          accept="application/pdf,.pdf,.doc,.docx"
          chooseButtonLabel="Choose document…"
          presignEndpoint={`/api/v1/admin/onboarding-templates/${template.id}/file/presign-upload`}
          onReady={(docId) => { void attach(docId); }}
          helperText="Direct-to-S3 upload · 50 MB max · PDF / DOC / DOCX recommended."
        />
        {saving && (
          <p className="text-xs text-slate-500">Saving reference…</p>
        )}
        {attached && (
          <p className="inline-flex items-center gap-1.5 rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
            <CheckCircle2 className="h-3.5 w-3.5" />
            New file attached — the template now serves the uploaded version.
          </p>
        )}
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm hover:bg-slate-50">
          Close
        </button>
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
    <ModalShell
      title={`Remove template`}
      icon={<Trash2 className="h-4 w-4 text-red-600" />}
      onClose={onClose}
    >
      <div className="space-y-3 p-5 text-sm">
        <p className="text-slate-700">
          Remove <strong>{template.title}</strong>?
        </p>
        <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-600">
          <p className="font-semibold text-slate-700">Soft-remove behavior:</p>
          <ul className="mt-1 list-disc space-y-1 pl-5">
            <li>New packets won't include this template.</li>
            <li>Interns already assigned it keep their download intact.</li>
            <li>The row stays in the list flagged "removed" for audit.</li>
            <li>Uploaded files stay in S3 (not deleted).</li>
          </ul>
        </div>
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      </div>
      <div className="flex justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
        <button type="button" onClick={onClose}
          className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm hover:bg-slate-50">
          Cancel
        </button>
        <button type="button" onClick={submit} disabled={busy}
          className="rounded-md bg-red-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-red-800 disabled:bg-slate-300">
          {busy ? 'Removing…' : 'Remove template'}
        </button>
      </div>
    </ModalShell>
  );
}

function ModalShell({ title, icon, onClose, children }: {
  title: string; icon: React.ReactNode; onClose: () => void; children: React.ReactNode;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
      <div className="flex max-h-[92vh] w-full max-w-lg flex-col overflow-hidden rounded-lg bg-white shadow-2xl">
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-5 py-3">
          <h3 className="inline-flex items-center gap-2 text-base font-semibold text-slate-900">
            {icon}
            {title}
          </h3>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-700">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="overflow-y-auto">{children}</div>
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

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '—';
    return d.toLocaleDateString(undefined, {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch { return '—'; }
}
