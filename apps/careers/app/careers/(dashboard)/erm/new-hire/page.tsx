'use client';

/**
 * ERM New Hire List — unified status table.
 *
 * Replaces the prior 3-tab layout (Pending Document Assignment /
 * Documents In Progress / All Hires) with a single status table where
 * each in-flight intern is one row showing stage + next action +
 * progress. Stage and Next-action cells deep-link to the right section
 * per intern; row chevron opens the full detail+tracker page.
 *
 * Design provenance: shaped by the ui-ux-pro-max skill — table density
 * + zebra rows, status pill conventions, cursor-pointer on every
 * clickable element, focus rings preserved, 150-300ms transitions,
 * skeleton loaders for >300ms loads, no emoji icons. Palette stays on
 * the Skyzen brand-700 + accent-orange + slate-200 theme — only
 * structural patterns came from the skill.
 *
 * The row data is server-authoritative: the unified-table fetches
 * `tab=all` once and filters client-side by computed stage. Stage +
 * Next-action mapping is derived from the row's `currentStepId` /
 * `canActivate` / `stepsCompleted` so client + server can't drift.
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  ChevronRight,
  ExternalLink,
  FileCheck,
  FilePlus2,
  Mail,
  Sparkles,
  Users,
  Zap,
} from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import type {
  NewHireListPage,
  NewHireRow,
} from '@/components/erm/offers/types';

// ── Stage derivation ─────────────────────────────────────────────────────

type Stage = 'DOCUMENTS' | 'SETUP' | 'READY' | 'ACTIVE';

function stageOf(row: NewHireRow): Stage {
  if (row.stepsCompleted != null && row.stepsTotal != null
      && row.stepsCompleted === row.stepsTotal) return 'ACTIVE';
  if (row.canActivate) return 'READY';
  switch (row.currentStepId) {
    case 'DOCS_ASSIGNED':
    case 'DOCS_VERIFIED':
      return 'DOCUMENTS';
    case 'TEAM_NOTIFIED':
    case 'MAIL_AND_JOINING':
    case 'ACTIVATE':
      return 'SETUP';
    default:
      // No tracker data yet (older client / brand new lifecycle) — fall
      // back to DOCUMENTS so the row stays visible under the default sort.
      return 'DOCUMENTS';
  }
}

const STAGE_LABEL: Record<Stage, string> = {
  DOCUMENTS: 'Documents',
  SETUP: 'Setup',
  READY: 'Ready to activate',
  ACTIVE: 'Active',
};

function stagePillClasses(stage: Stage): string {
  switch (stage) {
    case 'DOCUMENTS': return 'bg-brand-50 text-brand-800 ring-brand-200';
    case 'SETUP':     return 'bg-brand-50 text-brand-800 ring-brand-200';
    case 'READY':     return 'bg-accent/15 text-accent-dark ring-accent/30';
    case 'ACTIVE':    return 'bg-green-100 text-green-800 ring-green-200';
  }
}

// Action-needed first (Setup → Documents → Ready), then Active.
// Within a stage, most recently signed first.
const STAGE_PRIORITY: Record<Stage, number> = {
  SETUP: 0, DOCUMENTS: 1, READY: 2, ACTIVE: 3,
};

// ── Deep-link mapping ────────────────────────────────────────────────────

interface NextActionTarget {
  href: string;
  label: string;
  icon: typeof Sparkles;
  waiting?: boolean;
}

function nextActionFor(row: NewHireRow): NextActionTarget {
  const detailHref = `/careers/erm/new-hire/${row.internLifecycleId}`;
  // 5/5 — fully active.
  if (row.stepsCompleted != null && row.stepsTotal != null
      && row.stepsCompleted === row.stepsTotal) {
    return { href: detailHref, label: 'View intern', icon: CheckCircle2 };
  }
  switch (row.currentStepId) {
    case 'DOCS_ASSIGNED':
      // The packet assign is a modal on the detail page; the tracker
      // there auto-opens it via its onOpenAssignPacketModal callback.
      return { href: detailHref, label: 'Assign documents', icon: FilePlus2 };
    case 'DOCS_VERIFIED':
      // Direct redirect to the existing review screen — the one
      // separate surface (everything else lives on the detail tracker).
      return {
        href: `/careers/erm/document-review/${row.internLifecycleId}`,
        label: 'Review documents',
        icon: FileCheck,
      };
    case 'TEAM_NOTIFIED':
      return { href: detailHref, label: 'Notify trainer + manager', icon: Users };
    case 'MAIL_AND_JOINING':
      return { href: detailHref, label: 'Assign mail + joining date', icon: Mail };
    case 'ACTIVATE':
      return { href: detailHref, label: 'Activate intern', icon: Zap };
    default:
      return { href: detailHref, label: row.nextStepLabel ?? 'Open intern', icon: ArrowRight };
  }
}

// ── Filter pills ─────────────────────────────────────────────────────────

type FilterKey = 'ALL' | Stage;

const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL',       label: 'All' },
  { key: 'SETUP',     label: 'Setup' },
  { key: 'DOCUMENTS', label: 'Documents' },
  { key: 'READY',     label: 'Ready' },
  { key: 'ACTIVE',    label: 'Active' },
];

// ── Page shell ───────────────────────────────────────────────────────────

export default function NewHireListPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="New Hire List"
          subtitle="Every signed-offer intern, with their onboarding stage and next ERM action."
        />
        <Suspense fallback={<div className="h-40 animate-pulse rounded-lg bg-slate-100" />}>
          <NewHireListInner />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function NewHireListInner() {
  const [filter, setFilter] = useState<FilterKey>('ALL');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<NewHireListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // Always fetch tab=all so the filter pills can switch instantly
      // client-side. Backend's tab=all now includes both PROSPECTIVE and
      // ACTIVE so the Active filter has rows to show.
      const res = await api.get<NewHireListPage>(
        `/api/v1/erm/new-hire?tab=all&page=${page}&pageSize=50`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load new hires');
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => { void load(); }, [load]);

  const sorted = useMemo(() => {
    const rows = data?.items ?? [];
    return [...rows].sort((a, b) => {
      const pa = STAGE_PRIORITY[stageOf(a)];
      const pb = STAGE_PRIORITY[stageOf(b)];
      if (pa !== pb) return pa - pb;
      const ta = a.signedAt ? new Date(a.signedAt).getTime() : 0;
      const tb = b.signedAt ? new Date(b.signedAt).getTime() : 0;
      return tb - ta;
    });
  }, [data]);

  const counts = useMemo(() => {
    const c: Record<Stage, number> = {
      DOCUMENTS: 0, SETUP: 0, READY: 0, ACTIVE: 0,
    };
    for (const r of sorted) c[stageOf(r)] += 1;
    return c;
  }, [sorted]);

  const filtered = useMemo(() => {
    if (filter === 'ALL') return sorted;
    return sorted.filter((r) => stageOf(r) === filter);
  }, [sorted, filter]);

  return (
    <>
      <FilterBar filter={filter} setFilter={setFilter} counts={counts}
        total={sorted.length} />

      {err && (
        <p className="mt-3 inline-flex items-center gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
          <AlertCircle className="h-4 w-4" />{err}
        </p>
      )}

      <UnifiedTable rows={filtered} loading={loading && !data}
        emptyFor={filter} />

      {data && data.totalPages > 1 && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          onPrev={() => setPage((p) => Math.max(0, p - 1))}
          onNext={() => setPage((p) => p + 1)}
        />
      )}
    </>
  );
}

// ── Filter bar ───────────────────────────────────────────────────────────

function FilterBar({
  filter, setFilter, counts, total,
}: {
  filter: FilterKey;
  setFilter: (f: FilterKey) => void;
  counts: Record<Stage, number>;
  total: number;
}) {
  function countFor(k: FilterKey): number {
    return k === 'ALL' ? total : counts[k as Stage];
  }
  return (
    <div className="mb-4 flex flex-wrap items-center gap-1.5 rounded-lg border border-slate-200 bg-white p-2">
      <span className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500">
        Stage
      </span>
      {FILTERS.map((f) => {
        const active = filter === f.key;
        const n = countFor(f.key);
        return (
          <button
            key={f.key}
            type="button"
            onClick={() => setFilter(f.key)}
            className={
              'inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold transition-colors duration-150 cursor-pointer '
              + (active
                ? 'bg-brand-700 text-white shadow-sm'
                : 'bg-slate-100 text-slate-700 hover:bg-slate-200')
            }
            aria-pressed={active}
          >
            {f.label}
            <span
              className={
                'inline-flex min-w-[1.25rem] items-center justify-center rounded-full px-1.5 text-[10px] font-semibold '
                + (active ? 'bg-white/20 text-white' : 'bg-white text-slate-600')
              }
            >
              {n}
            </span>
          </button>
        );
      })}
    </div>
  );
}

// ── Table ────────────────────────────────────────────────────────────────

function UnifiedTable({
  rows, loading, emptyFor,
}: { rows: NewHireRow[]; loading: boolean; emptyFor: FilterKey }) {
  if (loading) {
    return (
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <div className="space-y-2 p-4">
          <div className="h-12 animate-pulse rounded bg-slate-100" />
          <div className="h-12 animate-pulse rounded bg-slate-100" />
          <div className="h-12 animate-pulse rounded bg-slate-100" />
        </div>
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-300 bg-white p-12 text-center">
        <Sparkles className="mx-auto h-6 w-6 text-slate-300" />
        <p className="mt-2 text-sm font-medium text-slate-700">
          {emptyFor === 'ALL'
            ? 'No new hires on file.'
            : `No interns in the ${STAGE_LABEL[emptyFor as Stage]} stage right now.`}
        </p>
        <p className="mt-1 text-xs text-slate-500">
          New rows appear here as soon as an offer is signed.
        </p>
      </div>
    );
  }
  return (
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50">
          <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            <th className="px-3 py-2">Intern</th>
            <th className="px-3 py-2">Stage</th>
            <th className="px-3 py-2">Next action</th>
            <th className="px-3 py-2 text-center">Progress</th>
            <th className="px-3 py-2"></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((r) => <Row key={r.internLifecycleId} row={r} />)}
        </tbody>
      </table>
    </div>
  );
}

// ── Row ──────────────────────────────────────────────────────────────────

function Row({ row }: { row: NewHireRow }) {
  const router = useRouter();
  const stage = stageOf(row);
  const next = nextActionFor(row);
  const detailHref = `/careers/erm/new-hire/${row.internLifecycleId}`;
  function rowKeyDown(e: React.KeyboardEvent) {
    if (e.target !== e.currentTarget) return;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      router.push(detailHref);
    }
  }
  return (
    <tr
      tabIndex={0}
      onKeyDown={rowKeyDown}
      className="group transition-colors duration-150 hover:bg-slate-50 focus:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-300"
    >
      {/* Intern */}
      <td className="px-3 py-3 align-middle">
        <Link
          href={detailHref}
          className="flex items-center gap-3 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-300 rounded-md"
        >
          <Avatar name={row.internName} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-semibold text-slate-900">
              {row.internName ?? '(unknown)'}
            </span>
            <span className="block truncate text-[11px] text-slate-500">
              {row.employeeId ?? '—'}
              {row.internEmail && (
                <span className="text-slate-400"> · {row.internEmail}</span>
              )}
            </span>
          </span>
        </Link>
      </td>

      {/* Stage badge */}
      <td className="px-3 py-3 align-middle">
        <Link
          href={detailHref}
          className={
            'inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 transition-colors duration-150 hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-300 '
            + stagePillClasses(stage)
          }
          title={`Stage: ${STAGE_LABEL[stage]} — open intern detail`}
        >
          {STAGE_LABEL[stage]}
        </Link>
      </td>

      {/* Next action (deep-link) */}
      <td className="px-3 py-3 align-middle">
        <NextActionLink target={next} />
      </td>

      {/* Progress */}
      <td className="px-3 py-3 text-center align-middle">
        <ProgressDot row={row} />
      </td>

      {/* Row chevron → detail */}
      <td className="px-3 py-3 text-right align-middle">
        <Link
          href={detailHref}
          aria-label="Open intern detail"
          className="inline-flex h-9 w-9 items-center justify-center rounded-full text-slate-400 transition-colors duration-150 hover:bg-slate-100 hover:text-slate-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-300"
        >
          <ChevronRight className="h-5 w-5" />
        </Link>
      </td>
    </tr>
  );
}

function NextActionLink({ target }: { target: NextActionTarget }) {
  const Icon = target.icon;
  const isExternal = target.href.startsWith('http');
  if (target.waiting) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md bg-slate-50 px-2 py-1 text-xs text-slate-600 ring-1 ring-slate-200">
        <Icon className="h-3.5 w-3.5" />
        {target.label}
      </span>
    );
  }
  return (
    <Link
      href={target.href}
      className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-2.5 py-1 text-xs font-semibold text-brand-800 transition-colors duration-150 hover:bg-brand-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-300"
      title={target.label}
    >
      <Icon className="h-3.5 w-3.5" />
      {target.label}
      {isExternal ? <ExternalLink className="h-3 w-3" /> : null}
    </Link>
  );
}

function ProgressDot({ row }: { row: NewHireRow }) {
  const total = row.stepsTotal ?? 6;
  const completed = row.stepsCompleted ?? 0;
  if (row.stepsCompleted == null) {
    return <span className="text-[11px] text-slate-400">—</span>;
  }
  if (completed === total) {
    return (
      <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-green-700">
        <CheckCircle2 className="h-4 w-4" />
        {completed}/{total}
      </span>
    );
  }
  const pct = Math.round((completed / total) * 100);
  return (
    <span className="inline-flex flex-col items-center gap-0.5">
      <span className="text-[11px] font-semibold tabular-nums text-slate-700">
        {completed}/{total}
      </span>
      <span
        className="h-1.5 w-16 overflow-hidden rounded-full bg-slate-100"
        aria-label={`${pct}% complete`}
      >
        <span
          className="block h-full bg-brand-700 transition-all duration-300"
          style={{ width: `${pct}%` }}
        />
      </span>
    </span>
  );
}

// ── Avatar (initials) ────────────────────────────────────────────────────

function Avatar({ name }: { name: string | null }) {
  const initials = (() => {
    if (!name) return '?';
    const parts = name.trim().split(/\s+/);
    if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
    return (parts[0]![0]! + parts[parts.length - 1]![0]!).toUpperCase();
  })();
  return (
    <span
      aria-hidden
      className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-brand-100 to-brand-200 text-xs font-semibold text-brand-800 ring-1 ring-brand-200/60"
    >
      {initials}
    </span>
  );
}

// ── Pagination ───────────────────────────────────────────────────────────

function Pagination({
  page, totalPages, totalElements, onPrev, onNext,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPrev: () => void;
  onNext: () => void;
}) {
  return (
    <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
      <span>
        Page {page + 1} of {totalPages} ({totalElements} total)
      </span>
      <div className="flex gap-1">
        <button
          type="button"
          disabled={page === 0}
          onClick={onPrev}
          className="rounded-md border border-slate-200 px-2 py-1 cursor-pointer transition-colors duration-150 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Prev
        </button>
        <button
          type="button"
          disabled={page + 1 >= totalPages}
          onClick={onNext}
          className="rounded-md border border-slate-200 px-2 py-1 cursor-pointer transition-colors duration-150 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </div>
  );
}

