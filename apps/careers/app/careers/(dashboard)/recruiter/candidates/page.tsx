'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Search, Users } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type { Page, Uuid } from '@/types';

interface CandidateListItemResponse {
  candidateId: Uuid;
  name: string | null;
  email: string | null;
  phone: string | null;
  applicationCount: number;
  latestStatus: string | null;
  latestPosition: string | null;
  hasResume: boolean;
  createdAt: string;
}

const PAGE_SIZE = 25;

function initialsOf(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((p) => p[0]?.toUpperCase() ?? '').join('') || '?';
}

export default function RecruiterCandidatesPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM']}>
      <DashboardLayout title="Candidates">
        <CandidatesList />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function CandidatesList() {
  const router = useRouter();
  const [searchInput, setSearchInput] = useState('');
  const [committedSearch, setCommittedSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<CandidateListItemResponse> | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Debounce: commit search 300ms after typing stops; reset to page 0.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setCommittedSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(t);
  }, [searchInput]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = {
        page,
        size: PAGE_SIZE,
      };
      if (committedSearch) params.search = committedSearch;
      const res = await api.get<Page<CandidateListItemResponse>>('/api/v1/candidates', {
        params,
      });
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
      setError(err?.response?.data?.error ?? "Couldn't load candidates.");
      setData(null);
    }
  }, [page, committedSearch]);

  useEffect(() => {
    void load();
  }, [load]);

  const totalPages = data?.totalPages ?? 0;
  const currentPage = data?.page ?? page;

  return (
    <section>
      <div className="mb-4">
        <label className="relative block w-full sm:max-w-xs">
          <span className="sr-only">Search candidates</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search by name or email"
            className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </label>
      </div>

      {/* error / loading / empty / list are mutually exclusive — the error
          branch returns first so the empty state never renders alongside it. */}
      {error ? (
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
      ) : data === null ? (
        <div className="py-10 text-center text-sm text-gray-500">Loading…</div>
      ) : (data.content?.length ?? 0) === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center">
          <Users className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
          <p className="text-sm text-gray-600">
            {committedSearch ? 'No candidates match that search.' : 'No candidates yet.'}
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {(data?.content ?? []).map((c) => (
            <li key={c.candidateId}>
              <button
                type="button"
                onClick={() => router.push(`/careers/erm/candidates/${c.candidateId}`)}
                className="flex w-full items-center gap-4 rounded-lg border border-gray-200 bg-white p-4 text-left hover:border-gray-300 hover:bg-gray-50"
              >
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-accent text-sm font-semibold text-white">
                  {initialsOf(c.name)}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="truncate font-semibold text-gray-900">
                    {c.name ?? '—'}
                  </div>
                  <div className="truncate text-sm text-gray-600">
                    {c.email ?? '—'}
                    {c.latestPosition ? <> · {c.latestPosition}</> : null}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-3">
                  <span className="whitespace-nowrap text-xs text-gray-500">
                    {c.applicationCount} app{c.applicationCount === 1 ? '' : 's'}
                  </span>
                  {c.latestStatus ? <ApplicationStatusBadge status={c.latestStatus} /> : null}
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}

      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-center gap-4 text-sm text-gray-600">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={currentPage === 0}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
          >
            ‹ Prev
          </button>
          <span>
            Page {currentPage + 1} of {totalPages}
          </span>
          <button
            type="button"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={currentPage >= totalPages - 1}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
          >
            Next ›
          </button>
        </div>
      )}
    </section>
  );
}
