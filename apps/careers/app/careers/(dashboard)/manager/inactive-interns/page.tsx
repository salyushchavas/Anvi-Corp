'use client';

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  Award,
  Briefcase,
  CheckCircle2,
  ChevronRight,
  Clock,
  ShieldCheck,
  ShieldOff,
  Star,
  XCircle,
} from 'lucide-react';
import api from '@/lib/api';
import PeriodPicker, {
  formatPeriod,
  thisMonth,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';

/**
 * Phase 4B-1 — Manager's read-only Inactive Interns surface. Scope is
 * enforced server-side (managerId == caller). Defaults to "all time"
 * because exits are infrequent; the period picker is a narrowing tool
 * for spotting a specific month's departures.
 */

type ExitType = 'COMPLETED' | 'RESIGNED' | 'TERMINATED' | 'EXTENDED';

interface InactiveRow {
  internLifecycleId: string;
  internUserId: string;
  fullName: string | null;
  email: string | null;
  employeeId: string | null;
  technologyTitle: string | null;
  activeStatus: string | null;
  endedAt: string | null;
  startDate: string | null;
  durationDays: number;
  trainerName: string | null;
  evaluatorName: string | null;
  managerName: string | null;
  ermName: string | null;

  exitRecordId: string | null;
  exitType: ExitType | null;
  exitDate: string | null;
  lastWorkingDay: string | null;
  exitReason: string | null;
  reasonCode: string | null;
  rehireEligible: boolean | null;
  finalTimesheetStatus: string | null;
  accessRevocationDone: boolean | null;
  finalDocumentsArchived: boolean | null;
  internVisibleSummary: string | null;
  finalEvaluationId: string | null;

  projectsCompleted: number;
  evaluationsCount: number;
  averageEvaluationScore: number | null;
  totalApprovedHours: number;
  lastProjectTitle: string | null;
  lastProjectMonthYearAsDate: string | null;
}

interface ListResponse {
  items: InactiveRow[];
  totalElements: number;
  monthYear: string | null;
}

export default function ManagerInactiveInternsPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <ManagerInactiveInternsInner />
    </Suspense>
  );
}

function ManagerInactiveInternsInner() {
  const [urlPeriod, setUrlPeriod] = usePeriodFromUrl();
  // "All time" is the default. We read ?period=this-or-all from the URL
  // alongside ?y/?m so refresh keeps the choice. usePeriodFromUrl
  // hydrates y/m even when we don't intend to use them — that's fine,
  // we just gate via this flag.
  const [allTime, setAllTime] = useState(true);
  const [data, setData] = useState<ListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (!allTime) {
        params.set('y', String(urlPeriod.year));
        params.set('m', String(urlPeriod.month));
      }
      const qs = params.toString();
      const res = await api.get<ListResponse>(
        `/api/v1/manager/inactive-interns${qs ? '?' + qs : ''}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Could not load list');
    } finally {
      setLoading(false);
    }
  }, [allTime, urlPeriod.year, urlPeriod.month]);

  useEffect(() => { void load(); }, [load]);

  const periodLabel = allTime ? 'All time' : formatPeriod(urlPeriod);
  const sortedRows = useMemo(() => {
    if (!data) return [];
    return [...data.items].sort((a, b) => {
      const ad = a.exitDate ?? (a.endedAt ?? '');
      const bd = b.exitDate ?? (b.endedAt ?? '');
      if (ad === bd) return (a.fullName ?? '').localeCompare(b.fullName ?? '');
      return bd.localeCompare(ad); // desc — most recent exits first
    });
  }, [data]);

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Inactive interns</h1>
          <p className="text-xs text-slate-500">
            {periodLabel} · your direct reports who have exited
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="inline-flex items-center gap-2 text-xs text-slate-700">
            <input
              type="checkbox"
              checked={allTime}
              onChange={(e) => {
                setAllTime(e.target.checked);
                if (e.target.checked) {
                  // Reset URL period to "this month" when toggling back on
                  // would feel wrong — just leave the URL state as is so
                  // toggling off again returns to the last viewed month.
                } else if (urlPeriod.year === thisMonth().year
                  && urlPeriod.month === thisMonth().month) {
                  // Make sure URL has values when the picker becomes live.
                  setUrlPeriod(thisMonth());
                }
              }}
              className="h-4 w-4 rounded border-slate-300"
            />
            All time
          </label>
          <div className={allTime ? 'pointer-events-none opacity-50' : ''}>
            <PeriodPicker value={urlPeriod} onChange={setUrlPeriod} />
          </div>
        </div>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {loading && !data ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : sortedRows.length === 0 ? (
        <p className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          {allTime
            ? 'No inactive interns under you yet.'
            : `No interns under you went inactive in ${periodLabel}.`}
        </p>
      ) : (
        <SummaryStrip rows={sortedRows} />
      )}

      {sortedRows.length > 0 && (
        <ul className="space-y-3">
          {sortedRows.map((r) => (
            <li key={r.internLifecycleId}>
              <RowCard
                row={r}
                expanded={expanded === r.internLifecycleId}
                onToggle={() => setExpanded(
                  expanded === r.internLifecycleId ? null : r.internLifecycleId,
                )}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ── Summary strip ────────────────────────────────────────────────────────

function SummaryStrip({ rows }: { rows: InactiveRow[] }) {
  const counts = useMemo(() => {
    const c = { COMPLETED: 0, RESIGNED: 0, TERMINATED: 0, EXTENDED: 0, UNKNOWN: 0 };
    for (const r of rows) {
      const key = (r.exitType ?? 'UNKNOWN') as keyof typeof c;
      c[key] = (c[key] ?? 0) + 1;
    }
    return c;
  }, [rows]);
  const tiles: Array<{ label: string; value: number; tone: string }> = [
    { label: 'Total inactive', value: rows.length, tone: 'slate' },
    { label: 'Completed',  value: counts.COMPLETED,  tone: counts.COMPLETED > 0  ? 'emerald' : 'slate' },
    { label: 'Resigned',   value: counts.RESIGNED,   tone: counts.RESIGNED > 0   ? 'amber'   : 'slate' },
    { label: 'Terminated', value: counts.TERMINATED, tone: counts.TERMINATED > 0 ? 'rose'    : 'slate' },
    { label: 'Extended',   value: counts.EXTENDED,   tone: counts.EXTENDED > 0   ? 'indigo'  : 'slate' },
  ];
  return (
    <section className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-3 sm:grid-cols-5">
      {tiles.map((t) => (
        <div key={t.label} className={'rounded-md p-2 ' + TONE_BG[t.tone]}>
          <p className="text-[10px] font-semibold uppercase tracking-wide opacity-80">{t.label}</p>
          <p className={'mt-0.5 text-lg font-semibold ' + TONE_TEXT[t.tone]}>{t.value}</p>
        </div>
      ))}
    </section>
  );
}

const TONE_BG: Record<string, string> = {
  slate: 'bg-slate-50',
  amber: 'bg-amber-50',
  rose: 'bg-red-50',
  emerald: 'bg-green-50',
  indigo: 'bg-slate-100',
};
const TONE_TEXT: Record<string, string> = {
  slate: 'text-slate-900',
  amber: 'text-amber-900',
  rose: 'text-red-900',
  emerald: 'text-green-900',
  indigo: 'text-slate-700',
};

// ── Row ──────────────────────────────────────────────────────────────────

function RowCard({
  row, expanded, onToggle,
}: { row: InactiveRow; expanded: boolean; onToggle: () => void }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-start justify-between gap-3 px-4 py-3 text-left hover:bg-slate-50"
        aria-expanded={expanded}
      >
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-2">
            <h3 className="text-sm font-semibold text-slate-900">
              {row.fullName ?? '(unknown)'}
            </h3>
            <span className="text-[11px] text-slate-500">
              {row.employeeId ?? '—'}
              {row.technologyTitle ? ' · ' + row.technologyTitle : ''}
            </span>
            <ExitTypePill type={row.exitType} />
            {row.rehireEligible === false && (
              <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-medium text-red-800 ring-1 ring-inset ring-red-200">
                <ShieldOff className="h-3 w-3" /> No rehire
              </span>
            )}
            {row.rehireEligible === true && row.exitType !== 'COMPLETED' && (
              <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-medium text-green-800 ring-1 ring-inset ring-green-200">
                <ShieldCheck className="h-3 w-3" /> Rehirable
              </span>
            )}
          </div>
          <p className="mt-1 text-[11px] text-slate-600">
            {row.exitDate
              ? <>Exit date: <span className="font-medium text-slate-800">{fmtDate(row.exitDate)}</span></>
              : row.endedAt
                ? <>Marked inactive: <span className="font-medium text-slate-800">{fmtInstantDate(row.endedAt)}</span></>
                : 'Exit date not recorded'}
            {row.lastWorkingDay && row.lastWorkingDay !== row.exitDate && (
              <> · Last day: <span className="font-medium text-slate-800">{fmtDate(row.lastWorkingDay)}</span></>
            )}
            {' · '}{row.durationDays} day{row.durationDays === 1 ? '' : 's'} in role
          </p>
        </div>
        <ChevronRight className={
          'mt-1 h-4 w-4 text-slate-400 transition-transform '
          + (expanded ? 'rotate-90' : '')
        } />
      </button>

      {expanded && (
        <div className="border-t border-slate-100 px-4 py-3">
          <Snapshot row={row} />
          {row.exitReason && (
            <DetailBlock label="Exit reason">
              <p className="whitespace-pre-wrap text-sm text-slate-700">{row.exitReason}</p>
              {row.reasonCode && (
                <p className="mt-1 text-[11px] text-slate-500">Code: {row.reasonCode}</p>
              )}
            </DetailBlock>
          )}
          {row.internVisibleSummary && (
            <DetailBlock label="Summary shared with intern">
              <p className="whitespace-pre-wrap text-sm text-slate-700">{row.internVisibleSummary}</p>
            </DetailBlock>
          )}
          <DetailBlock label="Closure checklist">
            <ul className="space-y-1 text-xs">
              <ChecklistRow ok={row.accessRevocationDone === true}
                label="Access revocation" />
              <ChecklistRow ok={row.finalDocumentsArchived === true}
                label="Final documents archived" />
              <ChecklistRow ok={row.finalTimesheetStatus === 'ALL_APPROVED'
                  || row.finalTimesheetStatus === 'WAIVED'}
                label={'Final timesheet — ' + (row.finalTimesheetStatus ?? 'unknown')} />
              <ChecklistRow ok={row.finalEvaluationId != null}
                label="Final evaluation linked" />
            </ul>
          </DetailBlock>
          <DetailBlock label="Reporting structure at exit">
            <p className="text-xs text-slate-700">
              {[
                row.trainerName && `Trainer: ${row.trainerName}`,
                row.evaluatorName && `Evaluator: ${row.evaluatorName}`,
                row.managerName && `Manager: ${row.managerName}`,
                row.ermName && `ERM: ${row.ermName}`,
              ].filter(Boolean).join(' · ') || '—'}
            </p>
          </DetailBlock>
          <div className="mt-3">
            <Link
              href={`/careers/trainer/active-interns/${row.internLifecycleId}`}
              className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
            >
              Open full intern detail <ChevronRight className="h-3 w-3" />
            </Link>
          </div>
        </div>
      )}
    </section>
  );
}

function Snapshot({ row }: { row: InactiveRow }) {
  const tiles = [
    {
      icon: <Briefcase className="h-3.5 w-3.5" />,
      label: 'Projects completed',
      value: String(row.projectsCompleted),
    },
    {
      icon: <Award className="h-3.5 w-3.5" />,
      label: 'Evaluations',
      value: String(row.evaluationsCount),
    },
    {
      icon: <Star className="h-3.5 w-3.5" />,
      label: 'Avg score',
      value: row.averageEvaluationScore != null
        ? row.averageEvaluationScore.toFixed(1)
        : '—',
    },
    {
      icon: <Clock className="h-3.5 w-3.5" />,
      label: 'Approved hours',
      value: typeof row.totalApprovedHours === 'number'
        ? row.totalApprovedHours.toFixed(2)
        : String(row.totalApprovedHours),
    },
  ];
  return (
    <>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {tiles.map((t) => (
          <div key={t.label} className="rounded-md border border-slate-100 bg-slate-50 p-2">
            <p className="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              {t.icon} {t.label}
            </p>
            <p className="mt-0.5 text-sm font-semibold text-slate-900">{t.value}</p>
          </div>
        ))}
      </div>
      {row.lastProjectTitle && (
        <p className="mt-2 text-[11px] text-slate-600">
          Last project: <span className="font-medium text-slate-800">{row.lastProjectTitle}</span>
          {row.lastProjectMonthYearAsDate && (
            <> · {fmtMonth(row.lastProjectMonthYearAsDate)}</>
          )}
        </p>
      )}
    </>
  );
}

function DetailBlock({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mt-3">
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </p>
      <div className="mt-1">{children}</div>
    </div>
  );
}

function ChecklistRow({ ok, label }: { ok: boolean; label: string }) {
  return (
    <li className="flex items-center gap-1.5 text-slate-700">
      {ok
        ? <CheckCircle2 className="h-3.5 w-3.5 text-green-600" />
        : <XCircle className="h-3.5 w-3.5 text-slate-400" />}
      <span>{label}</span>
    </li>
  );
}

function ExitTypePill({ type }: { type: ExitType | null }) {
  if (!type) {
    return (
      <span className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-700 ring-1 ring-inset ring-slate-200">
        Inactive
      </span>
    );
  }
  const cfg: Record<ExitType, { tone: string; label: string }> = {
    COMPLETED:  { tone: 'bg-green-100 text-green-800 ring-green-200', label: 'Completed' },
    RESIGNED:   { tone: 'bg-amber-100 text-amber-800 ring-amber-200',       label: 'Resigned' },
    TERMINATED: { tone: 'bg-red-100 text-red-800 ring-red-200',          label: 'Terminated' },
    EXTENDED:   { tone: 'bg-slate-100 text-slate-700 ring-slate-200',    label: 'Extended' },
  };
  const c = cfg[type];
  return (
    <span className={
      'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset '
      + c.tone
    }>
      {c.label}
    </span>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────────

function fmtDate(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch { return iso; }
}
function fmtInstantDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch { return iso; }
}
function fmtMonth(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short', year: 'numeric',
    });
  } catch { return iso; }
}
