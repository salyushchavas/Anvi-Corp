'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  FolderArchive,
  RefreshCw,
  Search,
} from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';

type StatusFilter = 'ALL' | 'ACTIVE' | 'INACTIVE' | 'PROSPECTIVE';

interface InternRow {
  lifecycleId: string;
  userId: string | null;
  employeeId: string | null;
  fullName: string | null;
  email: string | null;
  activeStatus: string | null;
  hiredAt: string | null;
  endedAt: string | null;
  packetCount: number;
  totalTasks: number;
  uploadedCount: number;
  pendingTasks: number;
  revisionRequestedTasks: number;
  acceptedTasks: number;
  lastUploadAt: string | null;
}

interface InternListResponse {
  items: InternRow[];
  total: number;
}

const STATUS_OPTIONS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL',         label: 'All' },
  { value: 'ACTIVE',      label: 'Active' },
  { value: 'PROSPECTIVE', label: 'Prospective / Onboarding' },
  { value: 'INACTIVE',    label: 'Inactive / Past' },
];

export default function ErmDocumentGalleryPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <GalleryInner />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function GalleryInner() {
  const [data, setData] = useState<InternListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [search, setSearch] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('status', status);
      if (search.trim()) params.set('search', search.trim());
      const res = await api.get<InternListResponse>(
        `/api/v1/erm/document-gallery/interns?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [status, search]);

  useEffect(() => { void load(); }, [load]);

  const rows = data?.items ?? [];

  const summary = useMemo(() => {
    let withUploads = 0;
    let revisions = 0;
    for (const r of rows) {
      if (r.uploadedCount > 0) withUploads++;
      revisions += r.revisionRequestedTasks;
    }
    return { withUploads, revisions };
  }, [rows]);

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/erm" className="hover:text-slate-700">← ERM home</Link>
          </p>
          <h1 className="mt-1 inline-flex items-center gap-2 text-xl font-semibold text-slate-900">
            <FolderArchive className="h-5 w-5 text-brand-700" />
            Document Gallery
          </h1>
          <p className="text-xs text-slate-500">
            Every intern × their uploaded onboarding documents. Re-uploads overwrite — only the latest version is kept.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-white p-3">
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value as StatusFilter)}
          className="rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        >
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
        <div className="relative flex-1 min-w-[16rem]">
          <Search className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name, employee id, or email"
            className="w-full rounded-md border border-slate-200 pl-7 pr-3 py-1.5 text-sm"
          />
        </div>
        <span className="ml-auto text-xs text-slate-600">
          <strong className="text-slate-900">{rows.length}</strong> intern{rows.length === 1 ? '' : 's'}
          {' · '}
          <strong className="text-slate-900">{summary.withUploads}</strong> with uploads
          {summary.revisions > 0 && (
            <>
              {' · '}
              <strong className="text-red-700">{summary.revisions}</strong> pending revision
            </>
          )}
        </span>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            No interns match this filter.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Documents</th>
                <th className="px-3 py-2">Last upload</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => <Row key={r.lifecycleId} r={r} />)}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Row({ r }: { r: InternRow }) {
  const summary = buildSummary(r);
  const activeTone = statusTone(r.activeStatus);
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{r.fullName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">
          {r.employeeId ?? 'no employee id'}
          {r.email && <span> · {r.email}</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-xs">
        <span className={
          'inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-semibold ' + activeTone
        }>
          {prettyStatus(r.activeStatus)}
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.totalTasks === 0 ? (
          <span className="text-slate-400">No packet assigned</span>
        ) : (
          <div className="space-y-0.5">
            <p>
              <strong className="text-slate-900">{r.uploadedCount}</strong> of {r.totalTasks} uploaded
            </p>
            <div className="flex flex-wrap gap-1">
              {summary.map((b) => (
                <span key={b.label}
                  className={'inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[10px] font-medium ' + b.tone}
                >
                  {b.icon}{b.label}
                </span>
              ))}
            </div>
          </div>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.lastUploadAt ? new Date(r.lastUploadAt).toLocaleString() : '—'}
      </td>
      <td className="px-3 py-2 text-right">
        <Link
          href={`/careers/erm/document-gallery/${r.lifecycleId}`}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          Open documents
        </Link>
      </td>
    </tr>
  );
}

interface SummaryBadge { label: string; tone: string; icon: React.ReactNode }

function buildSummary(r: InternRow): SummaryBadge[] {
  const out: SummaryBadge[] = [];
  if (r.acceptedTasks > 0) {
    out.push({
      label: `${r.acceptedTasks} accepted`,
      tone: 'bg-green-100 text-green-800',
      icon: <CheckCircle2 className="h-3 w-3" />,
    });
  }
  if (r.revisionRequestedTasks > 0) {
    out.push({
      label: `${r.revisionRequestedTasks} pending revision`,
      tone: 'bg-red-100 text-red-800',
      icon: <AlertTriangle className="h-3 w-3" />,
    });
  }
  if (r.pendingTasks > 0) {
    out.push({
      label: `${r.pendingTasks} not uploaded`,
      tone: 'bg-amber-100 text-amber-800',
      icon: <Clock className="h-3 w-3" />,
    });
  }
  return out;
}

function statusTone(s: string | null): string {
  switch (s) {
    case 'ACTIVE':
    case 'ACTIVE_INTERN':       return 'bg-green-100 text-green-800';
    case 'PROSPECTIVE':         return 'bg-amber-100 text-amber-800';
    case 'ONBOARDING':
    case 'ONBOARDING_ASSIGNED':
    case 'ONBOARDING_ACCEPTED': return 'bg-amber-100 text-amber-800';
    case 'INACTIVE':
    case 'INACTIVE_INTERN':
    case 'EXITED':
    case 'TERMINATED':          return 'bg-slate-200 text-slate-700';
    default:                    return 'bg-slate-100 text-slate-600';
  }
}

function prettyStatus(s: string | null): string {
  if (!s) return 'Unknown';
  return s.replace(/_/g, ' ').toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
