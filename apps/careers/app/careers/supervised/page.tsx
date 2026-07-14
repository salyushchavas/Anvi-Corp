'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Search, Users } from 'lucide-react';
import api from '@/lib/api';
import { formatDateOnly } from '@/lib/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { Uuid } from '@/types';

interface InternSummaryResponse {
  candidateId: Uuid;
  name: string | null;
  email: string | null;
  position: string | null;
  entityName: string | null;
  hiredDate: string | null;
  assignedEvaluatorName: string | null;
}

const ALL_ENTITIES = '__all__';

function initialsOf(name: string | null): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((p) => p[0]?.toUpperCase() ?? '').join('') || '?';
}

export default function SupervisedInternsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'TRAINER']}>
      <DashboardLayout title="Supervised Interns">
        <SupervisedInternsRoster />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Spinner() {
  return (
    <div className="flex min-h-[40vh] items-center justify-center">
      <div
        aria-label="Loading"
        className="h-10 w-10 animate-spin rounded-full border-4 border-accent border-t-transparent"
      />
    </div>
  );
}

function SupervisedInternsRoster() {
  const [interns, setInterns] = useState<InternSummaryResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [entityFilter, setEntityFilter] = useState<string>(ALL_ENTITIES);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<InternSummaryResponse[]>('/api/v1/supervised/interns');
      setInterns(res.data ?? []);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't load supervised interns.";
      setError(msg);
      setInterns(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Build the entity filter options from the fetched roster so the dropdown
  // only ever lists entities that actually have hired interns.
  const entityOptions = useMemo(() => {
    if (!interns) return [];
    const names = new Set<string>();
    for (const i of interns) {
      if (i.entityName) names.add(i.entityName);
    }
    return Array.from(names).sort((a, b) => a.localeCompare(b));
  }, [interns]);

  const filtered = useMemo(() => {
    if (!interns) return [];
    const q = search.trim().toLowerCase();
    return interns.filter((i) => {
      if (entityFilter !== ALL_ENTITIES && i.entityName !== entityFilter) return false;
      if (!q) return true;
      return (
        (i.name ?? '').toLowerCase().includes(q) ||
        (i.email ?? '').toLowerCase().includes(q)
      );
    });
  }, [interns, search, entityFilter]);

  if (interns === null && !error) return <Spinner />;

  return (
    <section>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-900">Supervised Interns</h1>
        <p className="mt-1 text-sm text-slate-600">
          Active interns under supervision.
        </p>
      </header>

      {error && (
        <div className="mb-6 rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
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

      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <label className="relative block w-full sm:max-w-xs">
          <span className="sr-only">Search intern</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search intern"
            className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </label>

        <label className="flex items-center gap-2 text-sm text-gray-600">
          Filter:
          <select
            value={entityFilter}
            onChange={(e) => setEntityFilter(e.target.value)}
            className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value={ALL_ENTITIES}>All entities</option>
            {entityOptions.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {interns && filtered.length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center">
          <Users className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
          <p className="text-sm text-gray-600">
            {interns.length === 0
              ? 'No interns under supervision yet.'
              : 'No interns match those filters.'}
          </p>
        </div>
      ) : (
        <ul className="space-y-3">
          {filtered.map((i) => (
            <li
              key={i.candidateId}
              className="flex items-start justify-between gap-4 rounded-lg border border-gray-200 bg-white p-5 hover:border-gray-300"
            >
              <div className="flex min-w-0 items-start gap-4">
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-accent text-sm font-semibold text-white">
                  {initialsOf(i.name)}
                </div>
                <div className="min-w-0">
                  <div className="truncate font-semibold text-gray-900">
                    {i.name ?? 'Unknown intern'}
                  </div>
                  <div className="truncate text-sm text-gray-600">
                    {i.position ?? 'Position unknown'}
                    {i.entityName ? <> · {i.entityName}</> : null}
                  </div>
                  <div className="mt-1 text-xs text-gray-500">
                    Hired {formatDateOnly(i.hiredDate)} · Evaluator:{' '}
                    {i.assignedEvaluatorName ?? '— (unassigned)'}
                  </div>
                </div>
              </div>

              <Link
                href={`/careers/supervised/${i.candidateId}`}
                className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                View
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
