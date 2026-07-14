'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Plus } from 'lucide-react';
import api from '@/lib/api';

type TemplateRow = {
  id: string;
  title: string;
  technologyArea: string;
  description: string | null;
  published: boolean;
  publishedAt: string | null;
  usageCount: number;
  archived: boolean;
  archivedAt: string | null;
  createdById: string | null;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  attachmentCount: number;
};

type TemplateListPage = {
  items: TemplateRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

const TABS: { key: string; label: string }[] = [
  { key: 'ALL', label: 'All (active)' },
  { key: 'PUBLISHED', label: 'Published' },
  { key: 'DRAFT', label: 'Drafts' },
  { key: 'ARCHIVED', label: 'Archived' },
];

export default function FilesTemplatesPage() {
  const [tab, setTab] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<TemplateListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('published', tab);
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<TemplateListPage>(
        `/api/v1/trainer/project-templates?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [tab, search, page]);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/trainer" className="hover:text-slate-700">
              ← Trainer dashboard
            </Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Project Templates</h1>
          <p className="text-xs text-slate-500">
            Reusable assignment blueprints. Shared across all trainers per doc §3.
          </p>
        </div>
        <Link
          href="/careers/trainer/files-templates/new"
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-2 text-sm font-semibold text-white hover:bg-brand-800"
        >
          <Plus className="h-4 w-4" /> New template
        </Link>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-slate-200">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => { setTab(t.key); setPage(0); }}
            className={
              '-mb-px border-b-2 px-3 py-2 text-sm font-medium ' +
              (tab === t.key
                ? 'border-brand-700 text-brand-800'
                : 'border-transparent text-slate-600 hover:text-slate-900')
            }
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="flex items-center gap-2">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); void load(); } }}
          placeholder="Search title or description"
          className="w-full rounded-md border border-slate-200 px-3 py-1.5 text-sm sm:max-w-sm"
        />
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : !data || data.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">No templates yet.</p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Title</th>
                <th className="px-3 py-2">Technology</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Usage</th>
                <th className="px-3 py-2">Files</th>
                <th className="px-3 py-2">Created by</th>
                <th className="px-3 py-2">Updated</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((t) => <Row key={t.id} t={t} />)}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-500">
          <span>Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)</span>
          <div className="flex gap-1">
            <button type="button" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50">Prev</button>
            <button type="button" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50">Next</button>
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ t }: { t: TemplateRow }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <Link href={`/careers/trainer/files-templates/${t.id}`}
          className="text-sm font-medium text-slate-900 hover:underline">{t.title}</Link>
        {t.description && (
          <p className="text-[11px] text-slate-500 line-clamp-1">{t.description}</p>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        <span className="rounded-full bg-slate-100 px-2 py-0.5">{t.technologyArea}</span>
      </td>
      <td className="px-3 py-2">
        {t.archived ? (
          <span className="rounded-full bg-slate-200 px-2 py-0.5 text-[11px] font-semibold text-slate-700">Archived</span>
        ) : t.published ? (
          <span className="rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-semibold text-green-800">Published</span>
        ) : (
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">Draft</span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{t.usageCount}</td>
      <td className="px-3 py-2 text-xs text-slate-700">{t.attachmentCount}</td>
      <td className="px-3 py-2 text-xs text-slate-700">{t.createdByName ?? '—'}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {new Date(t.updatedAt).toLocaleDateString()}
      </td>
    </tr>
  );
}
