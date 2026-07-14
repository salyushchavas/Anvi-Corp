'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import toast from 'react-hot-toast';
import { Briefcase, Search } from 'lucide-react';
import api from '@/lib/api';
import { formatDateOnly } from '@/lib/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { JobPostingResponse, JobPostingStatus, Page, Uuid } from '@/types';

interface AdminEntityResponse {
  id: Uuid;
  name: string;
}

const PAGE_SIZE = 25;

const STATUS_OPTIONS: JobPostingStatus[] = ['DRAFT', 'OPEN', 'PAUSED', 'CLOSED'];

const STATUS_COLOR: Record<JobPostingStatus, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  OPEN: 'bg-green-100 text-green-800',
  PAUSED: 'bg-amber-100 text-amber-800',
  CLOSED: 'bg-red-100 text-red-800',
};

function StatusBadge({ status }: { status: JobPostingStatus }) {
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        STATUS_COLOR[status]
      }
    >
      {status[0] + status.slice(1).toLowerCase()}
    </span>
  );
}

export default function AdminPostingsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM']}>
      <DashboardLayout title="Job Postings">
        <PostingsTable />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PostingsTable() {
  // Filter form state
  const [searchInput, setSearchInput] = useState('');
  const [committedSearch, setCommittedSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<JobPostingStatus | 'ALL'>('ALL');
  const [entityFilter, setEntityFilter] = useState<string>('ALL');
  const [page, setPage] = useState(0);

  // Data
  const [data, setData] = useState<Page<JobPostingResponse> | null>(null);
  const [entities, setEntities] = useState<AdminEntityResponse[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [updatingId, setUpdatingId] = useState<Uuid | null>(null);

  // Debounce: commit search 300ms after typing stops; reset to page 0.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setCommittedSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(t);
  }, [searchInput]);

  // Load the entity dropdown once.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<AdminEntityResponse[]>('/api/v1/admin/entities');
        if (!cancelled) setEntities(res.data ?? []);
      } catch {
        if (!cancelled) setEntities([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = {
        page,
        size: PAGE_SIZE,
      };
      if (committedSearch) params.search = committedSearch;
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (entityFilter !== 'ALL') params.entityId = entityFilter;
      const res = await api.get<Page<JobPostingResponse>>(
        '/api/v1/job-postings/admin/all',
        { params },
      );
      setData(
        res.data ?? {
          content: [],
          page,
          size: PAGE_SIZE,
          totalElements: 0,
          totalPages: 0,
        },
      );
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load postings.");
      setData(null);
    }
  }, [page, committedSearch, statusFilter, entityFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  // Reset to page 0 when any filter changes (the search debounce handles its own reset).
  useEffect(() => {
    setPage(0);
  }, [statusFilter, entityFilter]);

  const rows = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const currentPage = data?.page ?? page;

  async function toggleStatus(p: JobPostingResponse) {
    // Open <-> Closed is the user-facing toggle. DRAFT/PAUSED postings get
    // an explicit "Publish" or "Pause" hint instead.
    const next: JobPostingStatus =
      p.status === 'OPEN' ? 'CLOSED' : p.status === 'CLOSED' ? 'OPEN' : 'OPEN';
    setUpdatingId(p.id);
    try {
      await api.patch(`/api/v1/job-postings/${p.id}/status`, { status: next });
      toast.success(`Posting "${p.title}" is now ${next.toLowerCase()}.`);
      await load();
    } catch (err: any) {
      toast.error(
        err?.response?.data?.error ?? "Couldn't update posting status.",
      );
    } finally {
      setUpdatingId(null);
    }
  }

  return (
    <section>
      {/* Toolbar */}
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="relative block min-w-0 flex-1 sm:max-w-xs">
          <span className="sr-only">Search postings</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search by title or description"
            className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </label>

        <select
          value={entityFilter}
          onChange={(e) => setEntityFilter(e.target.value)}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="ALL">All entities</option>
          {entities.map((e) => (
            <option key={e.id} value={e.id}>
              {e.name}
            </option>
          ))}
        </select>

        <select
          value={statusFilter}
          onChange={(e) =>
            setStatusFilter(e.target.value as JobPostingStatus | 'ALL')
          }
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="ALL">All statuses</option>
          {STATUS_OPTIONS.map((s) => (
            <option key={s} value={s}>
              {s[0] + s.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          <p className="mb-2">{error}</p>
          <button
            type="button"
            onClick={() => void load()}
            className="rounded border border-red-300 px-3 py-1 font-medium hover:bg-red-100"
          >
            Retry
          </button>
        </div>
      )}

      {/* Table */}
      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
        <table className="min-w-full text-sm">
          <thead className="border-b border-gray-200 bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3 font-medium">
                Title
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                Entity
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                Status
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                Applicants
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                Created
              </th>
              <th scope="col" className="px-4 py-3 font-medium text-right">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {data === null && !error ? (
              <SkeletonRows />
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-sm text-gray-500">
                  <Briefcase
                    className="mx-auto mb-3 h-8 w-8 text-gray-400"
                    strokeWidth={1.5}
                  />
                  {committedSearch || statusFilter !== 'ALL' || entityFilter !== 'ALL'
                    ? 'No postings match those filters.'
                    : 'No postings yet.'}
                </td>
              </tr>
            ) : (
              rows.map((p) => (
                <tr
                  key={p.id}
                  className="border-b border-gray-100 last:border-0 hover:bg-gray-50"
                >
                  <td className="px-4 py-3">
                    <Link
                      href={`/careers/openings/${p.slug ?? p.id}`}
                      className="font-medium text-gray-900 hover:underline"
                    >
                      {p.title}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{p.entityName ?? '—'}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={p.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-700">{p.applicantCount ?? 0}</td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-700">
                    {p.createdAt ? formatDateOnly(p.createdAt) : '—'}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-right">
                    <Link
                      href={`/careers/openings/${p.slug ?? p.id}`}
                      className="mr-2 text-xs font-medium text-accent hover:underline"
                    >
                      View
                    </Link>
                    {(p.status === 'OPEN' || p.status === 'CLOSED') && (
                      <button
                        type="button"
                        onClick={() => void toggleStatus(p)}
                        disabled={updatingId === p.id}
                        className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
                      >
                        {updatingId === p.id
                          ? '…'
                          : p.status === 'OPEN'
                            ? 'Close'
                            : 'Reopen'}
                      </button>
                    )}
                    {p.status === 'DRAFT' && (
                      <button
                        type="button"
                        onClick={() => void toggleStatus(p)}
                        disabled={updatingId === p.id}
                        className="rounded-md bg-accent px-2.5 py-1 text-xs font-medium text-white hover:bg-accent/90 disabled:opacity-60"
                      >
                        {updatingId === p.id ? '…' : 'Publish'}
                      </button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 0 && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-gray-600">
          <span>
            {totalElements} posting{totalElements === 1 ? '' : 's'}
          </span>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={currentPage === 0}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
            >
              ‹ Prev
            </button>
            <span>
              Page {currentPage + 1} of {Math.max(totalPages, 1)}
            </span>
            <button
              type="button"
              onClick={() =>
                setPage((p) => Math.min(Math.max(totalPages - 1, 0), p + 1))
              }
              disabled={currentPage >= totalPages - 1}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
            >
              Next ›
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function SkeletonRows() {
  return (
    <>
      {[0, 1, 2, 3, 4].map((i) => (
        <tr key={i} className="border-b border-gray-100 last:border-0">
          {[0, 1, 2, 3, 4, 5].map((c) => (
            <td key={c} className="px-4 py-3">
              <div className="h-4 w-full animate-pulse rounded bg-gray-100" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
