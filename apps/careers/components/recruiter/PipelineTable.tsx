'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import toast from 'react-hot-toast';
import { Search, Star, X } from 'lucide-react';
import api from '@/lib/api';
import { formatDateOnly } from '@/lib/format-date';
import { useAuth } from '@/lib/auth-context';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type {
  ApplicationResponse,
  ApplicationStatus,
  JobPostingResponse,
  Page,
  UserRole,
  Uuid,
} from '@/types';

const PAGE_SIZE = 25;

// Whitelist of statuses we surface in the multi-select; mirrors the Kanban
// columns + a couple of exit states. Order is the natural funnel order.
const STATUS_FILTER_OPTIONS: ApplicationStatus[] = [
  'APPLIED',
  'SHORTLISTED',
  'INTERVIEW_SCHEDULED',
  'INTERVIEWED',
  'OFFERED',
  'ACCEPTED',
  'HIRED',
  'REJECTED',
  'WITHDRAWN',
];

type SortField = 'appliedAt' | 'status' | 'candidateName';
type SortDir = 'asc' | 'desc';

interface SortState {
  field: SortField;
  dir: SortDir;
}

const SORTABLE_HEADERS: { key: SortField; label: string }[] = [
  { key: 'candidateName', label: 'Candidate' },
  { key: 'status', label: 'Status' },
  { key: 'appliedAt', label: 'Applied' },
];

// Bulk action endpoint is RECRUITER/ERM/ADMIN per Part-1 @PreAuthorize; hide
// the buttons for HR even though they can view the table.
const BULK_ROLES: ReadonlyArray<UserRole> = ['ERM'];

function hasBulkAccess(roles: UserRole[] | undefined): boolean {
  if (!roles) return false;
  return roles.some((r) => BULK_ROLES.includes(r));
}

interface Props {
  postings: JobPostingResponse[];
}

export default function PipelineTable({ postings }: Props) {
  const router = useRouter();
  const { user } = useAuth();
  const canBulk = hasBulkAccess(user?.roles);

  // Filter form state (controlled inputs)
  const [searchInput, setSearchInput] = useState('');
  const [committedSearch, setCommittedSearch] = useState('');
  const [statuses, setStatuses] = useState<ApplicationStatus[]>([]);
  const [jobPostingId, setJobPostingId] = useState<string>('');
  const [sort, setSort] = useState<SortState>({ field: 'appliedAt', dir: 'desc' });
  const [page, setPage] = useState(0);

  // Data + selection
  const [data, setData] = useState<Page<ApplicationResponse> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<Uuid>>(new Set());
  const [acting, setActing] = useState<'SHORTLIST' | 'REJECT' | null>(null);

  // Debounce search — 300ms after typing stops, reset to page 0.
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
      // Build URLSearchParams manually so multi-value `status` repeats the key
      // (Spring binds ?status=A&status=B into List<ApplicationStatus>).
      const params = new URLSearchParams();
      params.set('page', String(page));
      params.set('size', String(PAGE_SIZE));
      params.set('sort', `${sort.field},${sort.dir}`);
      if (committedSearch) params.set('search', committedSearch);
      if (jobPostingId) params.set('jobPostingId', jobPostingId);
      for (const s of statuses) params.append('status', s);

      const res = await api.get<Page<ApplicationResponse>>(
        `/api/v1/applications?${params.toString()}`,
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
      setError(err?.response?.data?.error ?? "Couldn't load applications.");
      setData(null);
    }
  }, [page, committedSearch, statuses, jobPostingId, sort]);

  useEffect(() => {
    void load();
  }, [load]);

  // Selection is scoped to ids visible on the current page only; switching
  // pages clears it to avoid acting on hidden rows the user can't see.
  useEffect(() => {
    setSelectedIds(new Set());
  }, [page, committedSearch, statuses, jobPostingId, sort]);

  const rows = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const currentPage = data?.page ?? page;

  const allOnPageSelected =
    rows.length > 0 && rows.every((r) => selectedIds.has(r.id));
  const someOnPageSelected =
    !allOnPageSelected && rows.some((r) => selectedIds.has(r.id));

  function toggleAllOnPage() {
    setSelectedIds((curr) => {
      const next = new Set(curr);
      if (allOnPageSelected) {
        for (const r of rows) next.delete(r.id);
      } else {
        for (const r of rows) next.add(r.id);
      }
      return next;
    });
  }

  function toggleRow(id: Uuid) {
    setSelectedIds((curr) => {
      const next = new Set(curr);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function clearFilters() {
    setSearchInput('');
    setCommittedSearch('');
    setStatuses([]);
    setJobPostingId('');
    setSort({ field: 'appliedAt', dir: 'desc' });
    setPage(0);
  }

  function toggleSort(field: SortField) {
    setSort((curr) => {
      if (curr.field === field) {
        return { field, dir: curr.dir === 'asc' ? 'desc' : 'asc' };
      }
      // Default direction for a freshly-chosen column: appliedAt desc, the
      // others asc (alphabetical / lifecycle order reads more naturally).
      return { field, dir: field === 'appliedAt' ? 'desc' : 'asc' };
    });
    setPage(0);
  }

  async function runBulk(action: 'SHORTLIST' | 'REJECT') {
    if (selectedIds.size === 0) return;
    setActing(action);
    try {
      const res = await api.post<{ updated: number; skipped: number }>(
        '/api/v1/applications/bulk',
        { ids: Array.from(selectedIds), action },
      );
      const updated = res.data?.updated ?? 0;
      const skipped = res.data?.skipped ?? 0;
      toast.success(`${updated} updated, ${skipped} skipped`);
      setSelectedIds(new Set());
      await load();
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Bulk action failed.';
      toast.error(msg);
    } finally {
      setActing(null);
    }
  }

  const hasActiveFilters =
    committedSearch.length > 0 ||
    statuses.length > 0 ||
    jobPostingId !== '' ||
    sort.field !== 'appliedAt' ||
    sort.dir !== 'desc';

  return (
    <div className="space-y-4">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-gray-200 bg-white p-3">
        <label className="relative block min-w-0 flex-1 sm:max-w-xs">
          <span className="sr-only">Search candidates</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search candidate name or email"
            className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </label>

        <StatusMultiSelect value={statuses} onChange={(v) => { setStatuses(v); setPage(0); }} />

        <select
          value={jobPostingId}
          onChange={(e) => { setJobPostingId(e.target.value); setPage(0); }}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">All postings</option>
          {postings.map((p) => (
            <option key={p.id} value={p.id}>
              {p.title}
            </option>
          ))}
        </select>

        {hasActiveFilters && (
          <button
            type="button"
            onClick={clearFilters}
            className="inline-flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50"
          >
            <X className="h-3 w-3" strokeWidth={2} />
            Clear filters
          </button>
        )}
      </div>

      {/* Bulk action bar */}
      {selectedIds.size > 0 && canBulk && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-accent/30 bg-accent/5 px-4 py-3">
          <span className="text-sm font-medium text-gray-900">
            {selectedIds.size} selected
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void runBulk('SHORTLIST')}
              disabled={acting !== null}
              className="rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-white hover:bg-accent/90 disabled:opacity-60"
            >
              {acting === 'SHORTLIST' ? 'Working…' : 'Shortlist'}
            </button>
            <button
              type="button"
              onClick={() => void runBulk('REJECT')}
              disabled={acting !== null}
              className="rounded-md border border-red-300 bg-white px-3 py-1.5 text-sm font-semibold text-red-700 hover:bg-red-50 disabled:opacity-60"
            >
              {acting === 'REJECT' ? 'Working…' : 'Reject'}
            </button>
            <button
              type="button"
              onClick={() => setSelectedIds(new Set())}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            >
              Clear
            </button>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
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
              <th scope="col" className="w-10 px-3 py-3">
                <input
                  type="checkbox"
                  aria-label="Select all on page"
                  checked={allOnPageSelected}
                  ref={(el) => {
                    if (el) el.indeterminate = someOnPageSelected;
                  }}
                  onChange={toggleAllOnPage}
                  className="h-4 w-4 rounded border-gray-300 text-accent focus:ring-accent"
                />
              </th>
              {SORTABLE_HEADERS.map((h) => (
                <SortableHeader
                  key={h.key}
                  field={h.key}
                  label={h.label}
                  active={sort.field === h.key}
                  dir={sort.dir}
                  onToggle={toggleSort}
                />
              ))}
              <th scope="col" className="px-4 py-3 font-medium">
                Position
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                Rating
              </th>
            </tr>
          </thead>
          <tbody>
            {data === null && !error ? (
              <SkeletonRows />
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-10 text-center text-sm text-gray-500">
                  No applications match your filters.
                </td>
              </tr>
            ) : (
              rows.map((a) => (
                <TableRow
                  key={a.id}
                  app={a}
                  selected={selectedIds.has(a.id)}
                  onToggle={() => toggleRow(a.id)}
                  onOpen={() =>
                    router.push(`/careers/erm/applications/${a.id}`)
                  }
                />
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-gray-600">
          <span>
            {totalElements} application{totalElements === 1 ? '' : 's'}
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
    </div>
  );
}

function SortableHeader({
  field,
  label,
  active,
  dir,
  onToggle,
}: {
  field: SortField;
  label: string;
  active: boolean;
  dir: SortDir;
  onToggle: (f: SortField) => void;
}) {
  return (
    <th scope="col" className="px-4 py-3 font-medium">
      <button
        type="button"
        onClick={() => onToggle(field)}
        className={
          'inline-flex items-center gap-1 transition ' +
          (active ? 'text-gray-900' : 'text-gray-500 hover:text-gray-700')
        }
        aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      >
        {label}
        {active && <span aria-hidden="true">{dir === 'asc' ? '▲' : '▼'}</span>}
      </button>
    </th>
  );
}

function TableRow({
  app,
  selected,
  onToggle,
  onOpen,
}: {
  app: ApplicationResponse;
  selected: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  return (
    <tr
      onClick={onOpen}
      className={
        'cursor-pointer border-b border-gray-100 last:border-0 ' +
        (selected ? 'bg-accent/5' : 'hover:bg-gray-50')
      }
    >
      <td
        className="w-10 px-3 py-3"
        // Prevent the row's onClick from firing when the user is interacting
        // with the checkbox cell.
        onClick={(e) => e.stopPropagation()}
      >
        <input
          type="checkbox"
          aria-label={`Select ${app.candidateName ?? app.id}`}
          checked={selected}
          onChange={onToggle}
          className="h-4 w-4 rounded border-gray-300 text-accent focus:ring-accent"
        />
      </td>
      <td className="px-4 py-3">
        <div className="font-medium text-gray-900">{app.candidateName ?? '—'}</div>
        <div className="text-xs text-gray-500">{app.candidateEmail ?? '—'}</div>
      </td>
      <td className="px-4 py-3">
        <ApplicationStatusBadge status={app.status} />
      </td>
      <td className="whitespace-nowrap px-4 py-3 text-gray-700">
        {app.appliedAt ? formatDateOnly(app.appliedAt) : '—'}
      </td>
      <td className="px-4 py-3 text-gray-700">{app.jobPostingTitle ?? '—'}</td>
      <td className="px-4 py-3 text-gray-700">
        {app.recruiterRating != null ? (
          <span className="inline-flex items-center gap-1">
            <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" strokeWidth={1.5} />
            {app.recruiterRating}
          </span>
        ) : (
          '—'
        )}
      </td>
    </tr>
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

function StatusMultiSelect({
  value,
  onChange,
}: {
  value: ApplicationStatus[];
  onChange: (next: ApplicationStatus[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const summary = useMemo(() => {
    if (value.length === 0) return 'All statuses';
    if (value.length === 1) return value[0].replace('_', ' ').toLowerCase();
    return `${value.length} statuses`;
  }, [value]);

  function toggle(s: ApplicationStatus) {
    if (value.includes(s)) {
      onChange(value.filter((x) => x !== s));
    } else {
      onChange([...value, s]);
    }
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="inline-flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        Status: <span className="font-medium text-gray-900 capitalize">{summary}</span>
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <>
          {/* Click-outside backdrop */}
          <button
            type="button"
            aria-hidden="true"
            tabIndex={-1}
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-10 cursor-default"
          />
          <div
            role="listbox"
            aria-label="Filter by status"
            className="absolute left-0 top-full z-20 mt-1 min-w-[12rem] overflow-hidden rounded-md border border-gray-200 bg-white shadow-lg"
          >
            {STATUS_FILTER_OPTIONS.map((s) => {
              const checked = value.includes(s);
              return (
                <label
                  key={s}
                  className="flex cursor-pointer items-center gap-2 px-3 py-2 text-sm hover:bg-gray-50"
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggle(s)}
                    className="h-4 w-4 rounded border-gray-300 text-accent focus:ring-accent"
                  />
                  <span className="capitalize">
                    {s.replace(/_/g, ' ').toLowerCase()}
                  </span>
                </label>
              );
            })}
            {value.length > 0 && (
              <button
                type="button"
                onClick={() => onChange([])}
                className="block w-full border-t border-gray-100 px-3 py-2 text-left text-xs font-medium text-gray-500 hover:bg-gray-50"
              >
                Clear status filter
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
