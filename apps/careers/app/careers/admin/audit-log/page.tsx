'use client';

import { useCallback, useEffect, useState } from 'react';
import { Download, ScrollText, Search } from 'lucide-react';
import api from '@/lib/api';
import { formatFull } from '@/lib/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { Uuid, UserRole } from '@/types';

/**
 * Staff roles a SUPER_ADMIN can filter the audit log by. APPLICANT / INTERN
 * are excluded — candidate-side audit rows are rare; the action filter
 * narrows them in instead.
 */
const STAFF_ROLES_FOR_FILTER: UserRole[] = [
  'SUPER_ADMIN',
  'MANAGER',
  'ERM',
  'TRAINER',
  'REPORTING_MANAGER',
];

const ROLE_LABEL: Record<UserRole, string> = {
  INTERN: 'Intern',
  TRAINER: 'Trainer',
  EVALUATOR: 'Evaluator',
  REPORTING_MANAGER: 'Reporting Manager',
  MANAGER: 'Manager',
  ERM: 'ERM',
  SUPER_ADMIN: 'Super admin',
};

interface AuditLogEntryResponse {
  id: Uuid;
  timestamp: string;
  actorId: Uuid | null;
  actorName: string | null;
  action: string;
  entityType: string | null;
  entityId: Uuid | null;
  details: string | null;
}

interface PagedAuditLogResponse {
  content: AuditLogEntryResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const PAGE_SIZE = 25;

export default function AuditLogPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN']}>
      <DashboardLayout title="Audit Log">
        <AuditLogViewer />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

interface AuditFilters {
  actorSearch: string;
  action: string;
  role: UserRole | '';
  entityType: string;
  from: string;
  to: string;
}

const EMPTY_FILTERS: AuditFilters = {
  actorSearch: '',
  action: '',
  role: '',
  entityType: '',
  from: '',
  to: '',
};

function AuditLogViewer() {
  const [data, setData] = useState<PagedAuditLogResponse | null>(null);
  const [actions, setActions] = useState<string[]>([]);
  const [entityTypes, setEntityTypes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [downloading, setDownloading] = useState(false);

  // Filter state (committed). The form state below is what's in the inputs;
  // hitting Apply (or pressing Enter) writes form -> committed.
  const [committed, setCommitted] = useState<AuditFilters>(EMPTY_FILTERS);
  const [form, setForm] = useState<AuditFilters>(committed);

  const buildParams = useCallback((f: AuditFilters): Record<string, string> => {
    const p: Record<string, string> = {};
    if (f.actorSearch.trim()) p.actorSearch = f.actorSearch.trim();
    if (f.action) p.action = f.action;
    if (f.role) p.actorRole = f.role;
    if (f.entityType) p.entityType = f.entityType;
    if (f.from) p.from = `${f.from}T00:00:00Z`;
    if (f.to) p.to = `${f.to}T23:59:59Z`;
    return p;
  }, []);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = {
        page,
        size: PAGE_SIZE,
        ...buildParams(committed),
      };
      const res = await api.get<PagedAuditLogResponse>('/api/v1/admin/audit-log', {
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
      setError(err?.response?.data?.error ?? "Couldn't load the audit log.");
      setData(null);
    }
  }, [page, committed, buildParams]);

  const loadActions = useCallback(async () => {
    try {
      const res = await api.get<string[]>('/api/v1/admin/audit-log/actions');
      setActions(res.data ?? []);
    } catch {
      setActions([]);
    }
  }, []);

  const loadEntityTypes = useCallback(async () => {
    try {
      const res = await api.get<string[]>('/api/v1/admin/audit-log/entity-types');
      setEntityTypes(res.data ?? []);
    } catch {
      setEntityTypes([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadActions();
    void loadEntityTypes();
  }, [loadActions, loadEntityTypes]);

  const applyFilters = () => {
    setCommitted(form);
    setPage(0);
  };

  const resetFilters = () => {
    setForm(EMPTY_FILTERS);
    setCommitted(EMPTY_FILTERS);
    setPage(0);
  };

  const downloadCsv = async () => {
    setDownloading(true);
    setError(null);
    try {
      const res = await api.get('/api/v1/admin/audit-log/export', {
        params: buildParams(committed),
        responseType: 'blob',
      });
      const blob = res.data as Blob;
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      // Try to honour the Content-Disposition filename; fall back if absent.
      const disp = res.headers['content-disposition'] as string | undefined;
      const match = disp?.match(/filename=([^;]+)/i);
      a.download = match ? match[1].replace(/"/g, '') : 'audit-log.csv';
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not download the audit log.');
    } finally {
      setDownloading(false);
    }
  };

  const totalPages = data?.totalPages ?? 0;
  const currentPage = data?.page ?? 0;

  return (
    <section>
      <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <label className="relative block lg:col-span-2">
          <span className="sr-only">Search actor</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={form.actorSearch}
            onChange={(e) => setForm((f) => ({ ...f, actorSearch: e.target.value }))}
            onKeyDown={(e) => {
              if (e.key === 'Enter') applyFilters();
            }}
            placeholder="Search actor (name or email)"
            className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </label>
        <select
          value={form.role}
          onChange={(e) => setForm((f) => ({ ...f, role: e.target.value as UserRole | '' }))}
          aria-label="Actor role"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">All actor roles</option>
          {STAFF_ROLES_FOR_FILTER.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABEL[r]}
            </option>
          ))}
        </select>
        <select
          value={form.action}
          onChange={(e) => setForm((f) => ({ ...f, action: e.target.value }))}
          aria-label="Action"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">All actions</option>
          {actions.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
      </div>
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <select
          value={form.entityType}
          onChange={(e) => setForm((f) => ({ ...f, entityType: e.target.value }))}
          aria-label="Target entity"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">All entities</option>
          {entityTypes.map((e) => (
            <option key={e} value={e}>
              {e}
            </option>
          ))}
        </select>
        <input
          type="date"
          value={form.from}
          onChange={(e) => setForm((f) => ({ ...f, from: e.target.value }))}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          aria-label="From"
        />
        <input
          type="date"
          value={form.to}
          onChange={(e) => setForm((f) => ({ ...f, to: e.target.value }))}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          aria-label="To"
        />
        <div className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={resetFilters}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
          >
            Reset
          </button>
          <button
            type="button"
            onClick={applyFilters}
            className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
          >
            Apply
          </button>
        </div>
      </div>

      <div className="mb-4 flex items-center justify-between">
        <p className="text-xs text-gray-500">
          Read-only ·{' '}
          {data?.totalElements != null && (
            <span>{data.totalElements.toLocaleString()} matching {data.totalElements === 1 ? 'event' : 'events'}</span>
          )}
        </p>
        <button
          type="button"
          onClick={() => void downloadCsv()}
          disabled={downloading}
          className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
        >
          <Download className="h-4 w-4" strokeWidth={2} />
          {downloading ? 'Preparing…' : 'Download CSV'}
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {data === null && !error ? (
        <div className="py-10 text-center text-sm text-gray-500">Loading…</div>
      ) : (data?.content?.length ?? 0) === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center">
          <ScrollText className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
          <p className="text-sm text-gray-600">No audit entries match those filters.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
          <table className="min-w-full text-sm">
            <thead className="border-b border-gray-200 bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">Timestamp</th>
                <th className="px-4 py-3 font-medium">Actor</th>
                <th className="px-4 py-3 font-medium">Action</th>
                <th className="px-4 py-3 font-medium">Entity</th>
                <th className="px-4 py-3 font-medium">Details</th>
              </tr>
            </thead>
            <tbody>
              {(data?.content ?? []).map((row) => (
                <tr key={row.id} className="border-b border-gray-100 last:border-0">
                  <td className="whitespace-nowrap px-4 py-3 text-gray-700">
                    {formatFull(row.timestamp)}
                  </td>
                  <td className="px-4 py-3 text-gray-900">{row.actorName ?? '—'}</td>
                  <td className="px-4 py-3">
                    <span className="inline-block whitespace-nowrap rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-700">
                      {row.action}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{row.entityType ?? '—'}</td>
                  <td className="max-w-xs px-4 py-3 text-xs text-gray-500">
                    <span className="block truncate" title={row.details ?? ''}>
                      {row.details ?? '—'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
