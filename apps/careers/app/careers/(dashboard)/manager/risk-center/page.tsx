'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  AlertTriangle, Check, ChevronLeft, Clock, Filter, MessageSquare,
  Search, ShieldAlert, UserPlus, X,
} from 'lucide-react';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type {
  RiskFilterOptions,
  RiskListResponse,
  RiskRow,
  RiskSummary,
} from '@/components/manager/types';

export default function RiskCenterPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <RiskCenterInner />
    </Suspense>
  );
}

function RiskCenterInner() {
  const { user } = useAuth();

  const [severity, setSeverity] = useState<string[]>([]);
  const [exceptionType, setExceptionType] = useState<string>('');
  const [status, setStatus] = useState<string>('');           // empty = open default
  const [assignedToId, setAssignedToId] = useState<string>('');
  const [myInternsOnly, setMyInternsOnly] = useState(false);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<RiskListResponse | null>(null);
  const [filters, setFilters] = useState<RiskFilterOptions | null>(null);
  const [summary, setSummary] = useState<RiskSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [modal, setModal] = useState<
    | { kind: 'assign' | 'note' | 'resolve'; row: RiskRow }
    | null
  >(null);
  const [actionPending, setActionPending] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        const [f, s] = await Promise.all([
          api.get<RiskFilterOptions>('/api/v1/manager/risk-center/filters'),
          api.get<RiskSummary>('/api/v1/manager/risk-center/summary'),
        ]);
        setFilters(f.data);
        setSummary(s.data);
      } catch {
        // non-fatal
      }
    })();
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams();
      for (const s of severity) params.append('severity', s);
      if (exceptionType) params.set('exceptionType', exceptionType);
      if (status) params.set('status', status);
      if (assignedToId) params.set('assignedToId', assignedToId);
      if (myInternsOnly && user?.userId) params.set('managerId', user.userId);
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<RiskListResponse>(
        `/api/v1/manager/risk-center?${params.toString()}`);
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load Risk Center');
    } finally {
      setLoading(false);
    }
  }, [severity, exceptionType, status, assignedToId, myInternsOnly,
      search, page, user?.userId]);
  useEffect(() => { void load(); }, [load]);

  async function reloadAll() {
    await load();
    try {
      const s = await api.get<RiskSummary>('/api/v1/manager/risk-center/summary');
      setSummary(s.data);
    } catch { /* ignore */ }
  }

  function toggleSeverity(s: string) {
    setPage(0);
    setSeverity((cur) => cur.includes(s)
      ? cur.filter((x) => x !== s)
      : [...cur, s]);
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/manager"
          className="inline-flex items-center gap-1 hover:text-slate-700">
          <ChevronLeft className="h-3.5 w-3.5" />
          Manager home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Risk Center</h1>
        <p className="text-xs text-slate-500">
          Aggregated open exceptions across onboarding, project, meeting,
          evaluation, timesheet, offer, and exit. Reads from the same
          persisted store ERM uses (15-min scan cadence). Escalation,
          assignment, and resolution actions are portfolio-wide for
          MANAGER + SUPER_ADMIN.
        </p>
      </header>

      {summary && (
        <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <SummaryCard icon={<ShieldAlert className="h-4 w-4" />}
            label="Open exceptions" value={summary.totalOpen} />
          <SummaryCard icon={<AlertTriangle className="h-4 w-4" />}
            label="Urgent" value={summary.urgent}
            tone={summary.urgent > 0 ? 'rose' : 'slate'} />
          <SummaryCard icon={<AlertTriangle className="h-4 w-4" />}
            label="Warn" value={summary.warn}
            tone={summary.warn > 0 ? 'amber' : 'slate'} />
          <SummaryCard icon={<AlertTriangle className="h-4 w-4" />}
            label="Info" value={summary.info} />
          <SummaryCard icon={<UserPlus className="h-4 w-4" />}
            label="Assigned" value={summary.assigned} />
          <SummaryCard icon={<Check className="h-4 w-4" />}
            label="Resolved (30d)" value={summary.resolvedLast30Days}
            tone="emerald" />
        </section>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-5">
          <label className="md:col-span-2 block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Search className="h-3 w-3" />
              Search intern (name / email / employee ID)
            </span>
            <input
              type="text" value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              placeholder="Search…"
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>
          <FilterSelect label="Type" value={exceptionType}
            onChange={(v) => { setExceptionType(v); setPage(0); }}>
            <option value="">All types</option>
            {filters?.exceptionTypes.map((t) =>
              <option key={t} value={t}>{t.replaceAll('_', ' ')}</option>)}
          </FilterSelect>
          <FilterSelect label="Status" value={status}
            onChange={(v) => { setStatus(v); setPage(0); }}>
            <option value="">Open + Assigned + In-progress</option>
            {filters?.statuses.map((s) =>
              <option key={s} value={s}>{s.replaceAll('_', ' ')}</option>)}
          </FilterSelect>
          <FilterSelect label="Assigned to" value={assignedToId}
            onChange={(v) => { setAssignedToId(v); setPage(0); }}>
            <option value="">Anyone</option>
            {filters?.assignees.map((u) =>
              <option key={u.userId} value={u.userId}>{u.fullName}</option>)}
          </FilterSelect>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <label className={
            'inline-flex cursor-pointer items-center gap-2 rounded-full px-3 py-1 text-sm ' +
            (myInternsOnly
              ? 'bg-brand-100 font-semibold text-brand-800 ring-1 ring-brand-300'
              : 'text-slate-700 hover:bg-slate-100')
          }>
            <input type="checkbox" checked={myInternsOnly}
              onChange={(e) => { setMyInternsOnly(e.target.checked); setPage(0); }}
              className="h-4 w-4 accent-teal-700" />
            My interns only
            {myInternsOnly && <span className="text-[10px] uppercase">(filter active)</span>}
          </label>

          <div className="inline-flex items-center gap-1 text-sm text-slate-700">
            <Filter className="h-3 w-3" />
            Severity:
            <PillButton active={severity.includes('URGENT')}
              onClick={() => toggleSeverity('URGENT')} tone="rose">Urgent</PillButton>
            <PillButton active={severity.includes('WARN')}
              onClick={() => toggleSeverity('WARN')} tone="amber">Warn</PillButton>
            <PillButton active={severity.includes('INFO')}
              onClick={() => toggleSeverity('INFO')} tone="slate">Info</PillButton>
          </div>
        </div>
      </section>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-600">
            <tr>
              <th className="px-3 py-2 text-left">Severity</th>
              <th className="px-3 py-2 text-left">Type</th>
              <th className="px-3 py-2 text-left">Intern</th>
              <th className="px-3 py-2 text-left">Manager / ERM</th>
              <th className="px-3 py-2 text-left">Assigned</th>
              <th className="px-3 py-2 text-left">Status</th>
              <th className="px-3 py-2 text-right">Age</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && !data && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {data && data.items.length === 0 && !loading && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">
                No exceptions match these filters.
              </td></tr>
            )}
            {data?.items.map((r) => (
              <Row key={r.id} row={r}
                onAssign={() => setModal({ kind: 'assign', row: r })}
                onNote={() => setModal({ kind: 'note', row: r })}
                onResolve={() => setModal({ kind: 'resolve', row: r })} />
            ))}
          </tbody>
        </table>
      </section>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">
          <p>Page {data.page + 1} of {data.totalPages} · {data.totalElements} total</p>
          <div className="flex gap-2">
            <button type="button" disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50">
              Prev
            </button>
            <button type="button" disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50">
              Next
            </button>
          </div>
        </div>
      )}

      {modal && (
        <ActionModal
          kind={modal.kind}
          row={modal.row}
          assignees={filters?.assignees ?? []}
          pending={actionPending}
          onCancel={() => setModal(null)}
          onSubmit={async (payload) => {
            setActionPending(true);
            setErr(null);
            try {
              const id = modal.row.id;
              if (modal.kind === 'assign') {
                await api.post(`/api/v1/manager/risk-center/${id}/assign`,
                  { assigneeUserId: payload.assigneeUserId });
              } else if (modal.kind === 'note') {
                await api.post(`/api/v1/manager/risk-center/${id}/note`,
                  { note: payload.note });
              } else {
                await api.post(`/api/v1/manager/risk-center/${id}/resolve`, {
                  reasonCode: payload.reasonCode,
                  reasonText: payload.reasonText,
                  resolutionNote: payload.resolutionNote,
                });
              }
              setModal(null);
              await reloadAll();
            } catch (e) {
              const ax = e as { response?: { data?: { error?: string; message?: string } }; message?: string };
              setErr(ax.response?.data?.error
                ?? ax.response?.data?.message
                ?? ax.message ?? 'Action failed');
            } finally {
              setActionPending(false);
            }
          }}
        />
      )}
    </div>
  );
}

function Row({
  row, onAssign, onNote, onResolve,
}: {
  row: RiskRow;
  onAssign: () => void;
  onNote: () => void;
  onResolve: () => void;
}) {
  const isOpen = row.status === 'OPEN'
    || row.status === 'ASSIGNED'
    || row.status === 'IN_PROGRESS';
  return (
    <tr className="align-top hover:bg-slate-50">
      <td className="px-3 py-2"><SeverityBadge sev={row.severity} /></td>
      <td className="px-3 py-2">
        <p className="font-medium text-slate-900">
          {row.exceptionType.replaceAll('_', ' ')}
        </p>
        <p className="text-[10px] text-slate-500">
          {row.subjectResourceType ?? ''}
        </p>
      </td>
      <td className="px-3 py-2 text-[11px]">
        <p className="text-slate-800">{row.subjectName ?? '—'}</p>
        <p className="text-[10px] text-slate-500">{row.subjectEmployeeId ?? ''}</p>
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        <p>
          <span className="text-[10px] font-semibold uppercase text-slate-400">Mgr</span>{' '}
          {row.managerName ?? <span className="text-amber-700">Unassigned</span>}
        </p>
        <p>
          <span className="text-[10px] font-semibold uppercase text-slate-400">ERM</span>{' '}
          {row.ermOwnerName ?? <span className="text-amber-700">Unassigned</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        {row.assignedToName ?? <span className="text-slate-400">—</span>}
        {row.assignedAt && (
          <p className="text-[10px] text-slate-500">
            {new Date(row.assignedAt).toLocaleDateString()}
          </p>
        )}
      </td>
      <td className="px-3 py-2"><StatusPill status={row.status} /></td>
      <td className="px-3 py-2 text-right text-[11px] tabular-nums text-slate-700">
        {row.ageDays}d
      </td>
      <td className="px-3 py-2 text-right">
        {isOpen ? (
          <div className="flex justify-end gap-1">
            <button type="button" onClick={onAssign}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
              title="Assign owner">
              <UserPlus className="h-3 w-3" />
              Assign
            </button>
            <button type="button" onClick={onNote}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50"
              title="Add note / escalate">
              <MessageSquare className="h-3 w-3" />
              Note
            </button>
            <button type="button" onClick={onResolve}
              className="inline-flex items-center gap-1 rounded-md bg-green-600 px-2 py-1 text-[11px] font-semibold text-white hover:bg-green-700"
              title="Resolve">
              <Check className="h-3 w-3" />
              Resolve
            </button>
          </div>
        ) : (
          <span className="text-[11px] text-slate-400">closed</span>
        )}
      </td>
    </tr>
  );
}

function SeverityBadge({ sev }: { sev: RiskRow['severity'] }) {
  const map = {
    URGENT: 'bg-red-600 text-white',
    WARN: 'bg-amber-500 text-white',
    INFO: 'bg-slate-500 text-white',
  };
  return (
    <span className={
      'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ' + map[sev]
    }>
      <AlertTriangle className="h-3 w-3" />
      {sev}
    </span>
  );
}

function StatusPill({ status }: { status: RiskRow['status'] }) {
  const map: Record<RiskRow['status'], string> = {
    OPEN: 'bg-slate-100 text-slate-700',
    ASSIGNED: 'bg-amber-100 text-amber-800',
    IN_PROGRESS: 'bg-amber-100 text-amber-800',
    RESOLVED: 'bg-green-100 text-green-700',
    DISMISSED: 'bg-slate-100 text-slate-500',
    AUTO_RESOLVED: 'bg-green-100 text-green-700',
  };
  return (
    <span className={
      'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ' + map[status]
    }>
      <Clock className="h-3 w-3" />
      {status.replaceAll('_', ' ')}
    </span>
  );
}

function FilterSelect({
  label, value, onChange, children,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        <Filter className="h-3 w-3" />
        {label}
      </span>
      <select value={value} onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm">
        {children}
      </select>
    </label>
  );
}

function PillButton({
  active, onClick, tone = 'slate', children,
}: {
  active: boolean;
  onClick: () => void;
  tone?: 'rose' | 'amber' | 'slate';
  children: React.ReactNode;
}) {
  const activeMap = {
    rose: 'bg-red-600 text-white',
    amber: 'bg-amber-500 text-white',
    slate: 'bg-slate-600 text-white',
  };
  return (
    <button type="button" onClick={onClick}
      className={
        'rounded-full px-2 py-0.5 text-[11px] font-semibold ' +
        (active ? activeMap[tone] : 'bg-slate-100 text-slate-700 hover:bg-slate-200')
      }>
      {children}
    </button>
  );
}

function SummaryCard({
  icon, label, value, tone = 'slate',
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone?: 'slate' | 'emerald' | 'amber' | 'rose';
}) {
  const cls = tone === 'emerald' ? 'text-green-700'
    : tone === 'amber' ? 'text-amber-700'
    : tone === 'rose' ? 'text-red-700'
    : 'text-slate-900';
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <p className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {icon}
        {label}
      </p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{value}</p>
    </div>
  );
}

// ── Action modal (assign | note | resolve) ───────────────────────────────

interface ActionPayload {
  assigneeUserId?: string;
  note?: string;
  reasonCode?: string;
  reasonText?: string;
  resolutionNote?: string;
}

function ActionModal({
  kind, row, assignees, pending, onCancel, onSubmit,
}: {
  kind: 'assign' | 'note' | 'resolve';
  row: RiskRow;
  assignees: { userId: string; fullName: string }[];
  pending: boolean;
  onCancel: () => void;
  onSubmit: (p: ActionPayload) => void;
}) {
  const [assigneeUserId, setAssigneeUserId] = useState(row.assignedToId ?? '');
  const [note, setNote] = useState('');
  const [reasonCode, setReasonCode] = useState('RESOLVED');
  const [reasonText, setReasonText] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');

  let title = '';
  let body: React.ReactNode;
  let canSubmit = false;

  if (kind === 'assign') {
    title = 'Assign owner';
    canSubmit = !!assigneeUserId;
    body = (
      <label className="block">
        <span className="text-xs font-semibold text-slate-700">Owner</span>
        <select value={assigneeUserId}
          onChange={(e) => setAssigneeUserId(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm">
          <option value="">— Pick an assignee —</option>
          {assignees.map((u) => (
            <option key={u.userId} value={u.userId}>{u.fullName}</option>
          ))}
        </select>
        <p className="mt-1 text-[10px] text-slate-500">
          Assignee is recorded in the exception event log + audit.
        </p>
      </label>
    );
  } else if (kind === 'note') {
    title = 'Add note / escalate';
    canSubmit = note.trim().length >= 5;
    body = (
      <label className="block">
        <span className="text-xs font-semibold text-slate-700">Note (≥ 5 chars)</span>
        <textarea value={note} onChange={(e) => setNote(e.target.value)}
          rows={4}
          placeholder="Context, escalation path, or follow-up needed…"
          className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
        <p className="mt-1 text-[10px] text-slate-500">
          Recorded in the exception event log. Never surfaced to the intern.
        </p>
      </label>
    );
  } else {
    title = 'Resolve exception';
    canSubmit = !!reasonCode && resolutionNote.trim().length >= 10;
    body = (
      <div className="space-y-3">
        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Reason code</span>
          <input type="text" value={reasonCode}
            onChange={(e) => setReasonCode(e.target.value)}
            placeholder="RESOLVED"
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          <p className="mt-1 text-[10px] text-slate-500">
            Use one of ERM&apos;s structured reason codes (e.g. RESOLVED,
            CONDITION_NO_LONGER_APPLICABLE).
          </p>
        </label>
        <label className="block">
          <span className="text-xs font-semibold text-slate-700">Reason text (optional)</span>
          <input type="text" value={reasonText}
            onChange={(e) => setReasonText(e.target.value)}
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
        </label>
        <label className="block">
          <span className="text-xs font-semibold text-slate-700">
            Resolution note (≥ 10 chars)
          </span>
          <textarea value={resolutionNote}
            onChange={(e) => setResolutionNote(e.target.value)}
            rows={3}
            placeholder="What was done, who confirmed…"
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
        </label>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-5 shadow-lg">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-900">{title}</h2>
            <p className="text-xs text-slate-500">
              {row.exceptionType.replaceAll('_', ' ')} · {row.subjectName ?? 'unknown intern'}
            </p>
          </div>
          <button type="button" onClick={onCancel}
            className="rounded-md p-1 text-slate-500 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-3">{body}</div>

        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onCancel}
            className="rounded-md border border-slate-200 bg-white px-4 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
            Cancel
          </button>
          <button type="button"
            onClick={() => onSubmit({
              assigneeUserId, note: note.trim(),
              reasonCode, reasonText: reasonText.trim() || undefined,
              resolutionNote: resolutionNote.trim(),
            })}
            disabled={pending || !canSubmit}
            className={
              'rounded-md px-4 py-1.5 text-xs font-semibold text-white disabled:opacity-50 ' +
              (kind === 'resolve' ? 'bg-green-600 hover:bg-green-700'
                : 'bg-brand-700 hover:bg-brand-800')
            }>
            {pending ? 'Working…' : kind === 'resolve' ? 'Resolve' : 'Submit'}
          </button>
        </div>
      </div>
    </div>
  );
}
