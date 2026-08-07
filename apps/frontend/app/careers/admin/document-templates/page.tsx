'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { CheckCircle2, Edit3, FileText, MinusCircle, Plus, RefreshCw, Search } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { TemplateRow } from '@/lib/careers/editable-templates';

/**
 * Admin ⟶ Document Templates. Phase 1 of the editable-documents work:
 * list templates, jump into the studio, deactivate. Phase 2 will add
 * the "Instantiate for intern" action; keeping the list narrow keeps
 * the phase boundary honest.
 */
interface ListResponse {
  items: TemplateRow[];
}

export default function AdminDocumentTemplatesPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN', 'JOBS_ADMIN']}>
      <DashboardLayout title="Document Templates">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const [data, setData] = useState<ListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showRemoved, setShowRemoved] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<ListResponse>('/api/v1/admin/editable-templates');
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
      if (q && !`${t.title} ${t.key} ${t.description ?? ''}`.toLowerCase().includes(q)) {
        return false;
      }
      return true;
    });
  }, [items, search, showRemoved]);

  async function deactivate(row: TemplateRow) {
    if (!confirm(`Deactivate "${row.title}"? It stays in the list under "Show inactive".`)) return;
    try {
      await api.delete(`/api/v1/admin/editable-templates/${row.id}`);
      toast.success(`Deactivated "${row.title}"`);
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Deactivate failed');
    }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
            Document Templates
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Upload a Word document, mark the fields each party fills in, and
            save a reusable template.
          </p>
        </div>
        <Link
          href="/careers/admin/document-templates/new"
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
        >
          <Plus className="h-4 w-4" />
          New template
        </Link>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[240px]">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search title, key, description…"
            className="w-full rounded-md border border-slate-200 bg-white pl-9 pr-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>
        <label className="inline-flex items-center gap-2 text-xs text-slate-600">
          <input
            type="checkbox"
            checked={showRemoved}
            onChange={(e) => setShowRemoved(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300"
          />
          Show inactive
        </label>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {loading ? (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      ) : filtered.length === 0 ? (
        <EmptyState hasAny={items.length > 0} />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 text-left font-medium">Template</th>
                <th className="px-4 py-3 text-left font-medium">Source</th>
                <th className="px-4 py-3 text-left font-medium">Fields</th>
                <th className="px-4 py-3 text-left font-medium">Status</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {filtered.map((row) => (
                <tr key={row.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <div className="font-medium text-slate-900">{row.title}</div>
                    <div className="text-xs text-slate-500">
                      <code className="font-mono">{row.key}</code>
                      {row.description && <span className="ml-2">· {row.description}</span>}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    {row.hasSource ? (
                      <span className="inline-flex items-center gap-1 rounded bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700">
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        {row.sourceFileName ?? 'attached'}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded bg-amber-50 px-2 py-0.5 text-xs text-amber-800">
                        <FileText className="h-3.5 w-3.5" />
                        No file yet
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <span className="tabular-nums text-slate-700">{row.fieldCount}</span>
                  </td>
                  <td className="px-4 py-3">
                    {row.active ? (
                      <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                        Active
                      </span>
                    ) : (
                      <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
                        Inactive
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="inline-flex items-center gap-2">
                      <Link
                        href={`/careers/admin/document-templates/${row.key}/edit`}
                        className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
                      >
                        <Edit3 className="h-3.5 w-3.5" />
                        Edit
                      </Link>
                      {row.active && (
                        <button
                          type="button"
                          onClick={() => void deactivate(row)}
                          className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-red-700 hover:bg-red-50"
                        >
                          <MinusCircle className="h-3.5 w-3.5" />
                          Deactivate
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function EmptyState({ hasAny }: { hasAny: boolean }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-10 text-center">
      <FileText className="mx-auto h-8 w-8 text-slate-400" />
      <h2 className="mt-3 text-base font-semibold text-slate-800">
        {hasAny ? 'No templates match your filters' : 'No templates yet'}
      </h2>
      <p className="mt-1 text-sm text-slate-500">
        {hasAny
          ? 'Try clearing the search or toggling "Show inactive".'
          : 'Upload a Word document and mark its fields to build your first template.'}
      </p>
      {!hasAny && (
        <Link
          href="/careers/admin/document-templates/new"
          className="mt-4 inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
        >
          <Plus className="h-4 w-4" />
          Create your first template
        </Link>
      )}
    </div>
  );
}
