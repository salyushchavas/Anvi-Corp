'use client';

/**
 * Phase C — shared monthly intern roster table. ONE component renders
 * the Trainer, Manager, and ERM rosters identically: Name / Project /
 * KT / Training Eval / Timesheets, a 6-or-7-tile summary strip,
 * filter chips, sortable headers, attention-highlighted rows, and a
 * per-row "Know more" link to the role's detail page (or any
 * additional action the role provides via {@code extraRowAction}).
 *
 * <p>Single source of truth — extracted from the inline Phase A
 * implementation in {@code trainer/active-interns/page.tsx} so the
 * three role pages can't drift visually or behaviourally. The only
 * per-role variations are passed in as props:</p>
 * <ul>
 *   <li>{@code detailHref} — where "Know more" routes.</li>
 *   <li>{@code showNoManagerControls} — adds a "No manager" filter chip
 *       + summary tile + per-row badge (ERM only).</li>
 *   <li>{@code renderRowExtra} — optional render-prop for a per-row
 *       affordance (e.g. ERM AssignManager button).</li>
 * </ul>
 */

import { useMemo, useState, type ReactNode } from 'react';
import Link from 'next/link';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock,
  FileWarning,
  GraduationCap,
  UserX,
} from 'lucide-react';
import type {
  ActiveInternListPage,
  ActiveInternRow,
  MonthRosterSummary,
  ProjectSlot,
} from '@/components/trainer/types';

export type RowFilter =
  | 'all' | 'attention' | 'no_project' | 'kt_missing'
  | 'ts_incomplete' | 'eval_overdue' | 'no_manager';

export type SortKey = 'name' | 'project' | 'kt' | 'eval' | 'timesheet';

/**
 * Per-cell action providers. When a builder is supplied AND the row's
 * state matches the cell's "action needed" predicate, the cell renders
 * a button-with-redirect instead of the passive status pill. Roles
 * that don't own a given action (e.g. Manager + ERM for KT) just omit
 * the builder and keep the passive rendering.
 *
 * Predicates per cell:
 *   - projectAssignHref: shown when overallState ∈ {NO_PROJECTS, PARTIAL}
 *     (a slot is empty + needs assigning).
 *   - ktDetailHref: shown when any active project slot has
 *     ktStatus !== 'DONE' (KT not yet completed for that project).
 *
 * Other cells (Training eval / Timesheets) are intentionally NOT in
 * this interface today — the Trainer doesn't own those actions per the
 * survey, and the brief's principle is "button ONLY when the trainer
 * has an action". Add more builders here when a role gains a real
 * action for those cells.
 */
export interface RosterCellActions {
  projectAssignHref?: (row: ActiveInternRow) => string;
  /**
   * Called when KT is actionable. The second arg carries the projectId
   * of the FIRST not-done KT slot (or null when KtCell couldn't
   * identify one — e.g. ProjectSlot.id was null because the slot row
   * isn't persisted yet). The returned URL should deep-link to the
   * intern detail page; passing the projectId lets the detail page
   * auto-open the matching KT modal so the trainer completes the
   * action in one click rather than two.
   */
  ktDetailHref?: (
    row: ActiveInternRow,
    options?: { projectId?: string | null },
  ) => string;
}

interface Props {
  data: ActiveInternListPage | null;
  loading: boolean;
  err: string | null;
  periodLabel: string;
  /** Per-row "Know more" link target. */
  detailHref: (lifecycleId: string) => string;
  /** ERM only — show the "No manager" filter chip, summary tile, and
   *  per-row badge. */
  showNoManagerControls?: boolean;
  /** Optional render-prop for an extra per-row action (e.g. ERM
   *  AssignManager button). Receives the row + a refresh callback. */
  renderRowExtra?: (row: ActiveInternRow, onChanged: () => void) => ReactNode;
  /** Called after a row mutation succeeds, so the parent can refetch. */
  onChanged?: () => void;
  /** Optional per-cell action providers — when omitted the cells render
   *  as plain status pills (current Manager + ERM behaviour). The
   *  Trainer surface passes these so Project + KT cells become actionable
   *  buttons that redirect to the relevant section. */
  cellActions?: RosterCellActions;
}

const BASE_FILTERS: Array<{ key: RowFilter; label: string }> = [
  { key: 'all',           label: 'All' },
  { key: 'attention',     label: 'Attention needed' },
  { key: 'no_project',    label: 'No project' },
  { key: 'kt_missing',    label: 'KT not done' },
  { key: 'ts_incomplete', label: 'Timesheets incomplete' },
  { key: 'eval_overdue',  label: 'Evaluation overdue' },
];
const NO_MGR_FILTER = { key: 'no_manager' as RowFilter, label: 'No manager' };

export default function MonthlyRosterTable({
  data, loading, err, periodLabel, detailHref,
  showNoManagerControls, renderRowExtra, onChanged,
  cellActions,
}: Props) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<RowFilter>('all');
  const [sort, setSort] = useState<SortKey>('name');

  const filterChips = showNoManagerControls
    ? [...BASE_FILTERS, NO_MGR_FILTER]
    : BASE_FILTERS;

  const rows = useMemo(() => {
    if (!data) return [];
    const q = search.trim().toLowerCase();
    const items = data.items
      .filter((r) => rowMatchesFilter(r, filter))
      .filter((r) => q === '' || (r.fullName ?? '').toLowerCase().includes(q)
        || (r.email ?? '').toLowerCase().includes(q)
        || (r.employeeId ?? '').toLowerCase().includes(q));
    items.sort(rowComparator(sort));
    return items;
  }, [data, filter, sort, search]);

  return (
    <div className="space-y-4">
      {data?.summary && (
        <SummaryStrip
          summary={data.summary}
          monthYear={data.monthYear}
          showNoManager={Boolean(showNoManagerControls)}
        />
      )}

      <div className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search name, email, employee ID"
          className="w-full rounded-md border border-slate-200 px-3 py-1.5 text-sm"
        />
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[10px] font-semibold uppercase text-slate-500">Show</span>
          {filterChips.map((f) => (
            <button
              key={f.key}
              type="button"
              onClick={() => setFilter(f.key)}
              className={
                'rounded-full border px-2.5 py-0.5 text-[11px] font-medium '
                + (filter === f.key
                    ? 'border-brand-700 bg-brand-700 text-white'
                    : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" aria-hidden />
        ) : rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            {data && data.items.length === 0
              ? `No active interns in ${periodLabel}.`
              : 'No interns match the current filter.'}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <SortableHeader k="name" current={sort} onClick={setSort}>Name</SortableHeader>
                  <SortableHeader k="project" current={sort} onClick={setSort}>Project</SortableHeader>
                  <SortableHeader k="kt" current={sort} onClick={setSort}>KT</SortableHeader>
                  <SortableHeader k="eval" current={sort} onClick={setSort}>Training eval</SortableHeader>
                  <SortableHeader k="timesheet" current={sort} onClick={setSort}>Timesheets</SortableHeader>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((r) => (
                  <Row
                    key={r.internLifecycleId}
                    row={r}
                    detailHref={detailHref(r.internLifecycleId)}
                    showNoManagerBadge={Boolean(showNoManagerControls)}
                    rowExtra={renderRowExtra
                      ? renderRowExtra(r, () => onChanged?.())
                      : null}
                    cellActions={cellActions}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Summary strip ────────────────────────────────────────────────────────

function SummaryStrip({
  summary, monthYear, showNoManager,
}: { summary: MonthRosterSummary; monthYear: string; showNoManager: boolean }) {
  const tiles: Array<{ label: string; value: number; tone: PillTone }> = [
    { label: 'Active', value: summary.totalActive, tone: 'slate' },
    { label: 'Projects unassigned', value: summary.projectsUnassigned, tone: summary.projectsUnassigned > 0 ? 'amber' : 'slate' },
    { label: 'KT not done', value: summary.ktNotDone, tone: summary.ktNotDone > 0 ? 'amber' : 'slate' },
    { label: 'Timesheets incomplete', value: summary.timesheetsIncomplete, tone: summary.timesheetsIncomplete > 0 ? 'amber' : 'slate' },
    { label: 'Evaluations overdue', value: summary.evaluationsOverdue, tone: summary.evaluationsOverdue > 0 ? 'rose' : 'slate' },
    { label: 'Attention', value: summary.attentionNeeded, tone: summary.attentionNeeded > 0 ? 'rose' : 'emerald' },
  ];
  if (showNoManager) {
    // Slot the no-manager tile in second-to-last so the Attention tile
    // still anchors the right edge as the overall health signal.
    tiles.splice(tiles.length - 1, 0, {
      label: 'No manager',
      value: summary.noManager,
      tone: summary.noManager > 0 ? 'rose' : 'slate',
    });
  }
  return (
    <section
      className={
        'grid gap-2 rounded-lg border border-slate-200 bg-white p-3 '
        + (showNoManager
            ? 'grid-cols-2 sm:grid-cols-4 lg:grid-cols-7'
            : 'grid-cols-2 sm:grid-cols-3 lg:grid-cols-6')
      }
      aria-label={`Month summary for ${monthYear}`}
    >
      {tiles.map((t) => (
        <div key={t.label} className={'rounded-md p-2 ' + TONE_BG[t.tone]}>
          <p className="text-[10px] font-semibold uppercase tracking-wide opacity-80">
            {t.label}
          </p>
          <p className={'mt-0.5 text-lg font-semibold ' + TONE_TEXT[t.tone]}>
            {t.value}
          </p>
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
};
const TONE_TEXT: Record<string, string> = {
  slate: 'text-slate-900',
  amber: 'text-amber-900',
  rose: 'text-red-900',
  emerald: 'text-green-900',
};

// ── Row ──────────────────────────────────────────────────────────────────

function Row({
  row, detailHref, showNoManagerBadge, rowExtra, cellActions,
}: {
  row: ActiveInternRow;
  detailHref: string;
  showNoManagerBadge: boolean;
  rowExtra: ReactNode | null;
  cellActions?: RosterCellActions;
}) {
  const attention = rowAttention(row);
  const noManager = row.reportingStructure?.managerId == null;
  return (
    <tr
      className={
        'transition-colors hover:bg-slate-50 '
        + (attention ? 'bg-red-50/40 ring-1 ring-inset ring-red-200/40' : '')
      }
    >
      <td className="px-3 py-2">
        <Link href={detailHref} className="block">
          <span className="block text-sm font-medium text-slate-900 hover:underline">
            {row.fullName ?? '(unknown)'}
          </span>
          <span className="block text-[10px] text-slate-500">
            {row.employeeId ?? '—'}
            {row.technologyTitle ? ' · ' + row.technologyTitle : ''}
          </span>
          {showNoManagerBadge && noManager && (
            <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-medium text-red-800 ring-1 ring-inset ring-red-200">
              <UserX className="h-3 w-3" /> No manager
            </span>
          )}
        </Link>
      </td>
      <td className="px-3 py-2"><ProjectCell row={row} cellActions={cellActions} /></td>
      <td className="px-3 py-2"><KtCell row={row} cellActions={cellActions} /></td>
      <td className="px-3 py-2"><EvalCell row={row} /></td>
      <td className="px-3 py-2"><TimesheetCell row={row} /></td>
      <td className="px-3 py-2 text-right">
        <div className="flex items-center justify-end gap-2">
          {rowExtra}
          <Link
            href={detailHref}
            className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
          >
            Know more <ChevronRight className="h-3 w-3" />
          </Link>
        </div>
      </td>
    </tr>
  );
}

// ── Cells ────────────────────────────────────────────────────────────────

function ProjectCell({
  row, cellActions,
}: { row: ActiveInternRow; cellActions?: RosterCellActions }) {
  const p1 = row.currentMonthProjects.project1;
  const p2 = row.currentMonthProjects.project2;
  // Action predicate: a slot is empty + needs assigning. Mirrors the
  // overallState field — kept inline so we don't depend on a stringly-
  // typed enum getting out of sync with the DTO.
  const needsAssign = !p1 || !p2;
  const nextIndex = !p1 ? 1 : 2;
  const assignHref = cellActions?.projectAssignHref?.(row);

  if (!p1 && !p2) {
    if (assignHref) {
      return (
        <Link
          href={assignHref}
          className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2.5 py-1 text-[11px] font-semibold text-brand-800 hover:bg-brand-100"
          title="Assign the first project for this intern"
        >
          Assign P1
          <ChevronRight className="h-3 w-3" />
        </Link>
      );
    }
    return <Pill tone="slate">Not assigned</Pill>;
  }

  return (
    <div className="flex flex-col gap-1">
      {p1 && <SlotPill slot={p1} index={1} />}
      {p2 && <SlotPill slot={p2} index={2} />}
      {needsAssign && assignHref && (
        <Link
          href={assignHref}
          className="inline-flex w-fit items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2 py-0.5 text-[10px] font-semibold text-brand-800 hover:bg-brand-100"
          title={`Assign project ${nextIndex} for this intern`}
        >
          Assign P{nextIndex}
          <ChevronRight className="h-3 w-3" />
        </Link>
      )}
    </div>
  );
}

function SlotPill({ slot, index }: { slot: ProjectSlot; index: number }) {
  const tone: PillTone =
    slot.state === 'COMPLETED' ? 'emerald'
    : slot.state === 'OVERDUE' ? 'rose'
    : slot.state === 'IN_PROGRESS' ? 'amber'
    : slot.state === 'ASSIGNED' ? 'sky'
    : 'slate';
  const label =
    slot.state === 'COMPLETED' ? 'Completed'
    : slot.state === 'OVERDUE' ? 'Overdue'
    : slot.state === 'IN_PROGRESS' ? humanProjectStatus(slot.status)
    : slot.state === 'ASSIGNED' ? 'Assigned'
    : 'Not started';
  return (
    <div className="flex items-center gap-1.5">
      <span className="text-[10px] font-mono text-slate-400">P{index}</span>
      <Pill tone={tone}>{label}</Pill>
    </div>
  );
}

function humanProjectStatus(status: string | null): string {
  switch (status) {
    case 'SUBMITTED': return 'Submitted';
    case 'RETURNED':  return 'Returned';
    case 'IN_PROGRESS': return 'In progress';
    default: return 'In progress';
  }
}

function KtCell({
  row, cellActions,
}: { row: ActiveInternRow; cellActions?: RosterCellActions }) {
  const slots = [row.currentMonthProjects.project1, row.currentMonthProjects.project2]
    .filter(Boolean) as ProjectSlot[];
  if (slots.length === 0) {
    return <span className="text-xs text-slate-400">—</span>;
  }
  const firstNotDone = slots.find((s) => s.ktStatus !== 'DONE');
  const anyNotDone = !!firstNotDone;
  const ktHref = cellActions?.ktDetailHref?.(row, {
    projectId: firstNotDone?.id ?? null,
  });
  return (
    <div className="flex flex-col gap-1">
      {slots.map((s, i) => {
        const done = s.ktStatus === 'DONE';
        return (
          <div key={s.id ?? i} className="flex items-center gap-1.5">
            {slots.length > 1 && (
              <span className="text-[10px] font-mono text-slate-400">P{i + 1}</span>
            )}
            <Pill tone={done ? 'emerald' : 'slate'}>
              {done
                ? <span className="inline-flex items-center gap-1"><GraduationCap className="h-3 w-3" /> Done</span>
                : 'Not done'}
            </Pill>
            {done && s.ktCompletedAt && (
              <span className="text-[10px] text-slate-500">
                {new Date(s.ktCompletedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
              </span>
            )}
          </div>
        );
      })}
      {anyNotDone && ktHref && (
        <Link
          href={ktHref}
          className="inline-flex w-fit items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2 py-0.5 text-[10px] font-semibold text-brand-800 hover:bg-brand-100"
          title="Open the intern's detail page to mark KT done"
        >
          Mark KT done
          <ChevronRight className="h-3 w-3" />
        </Link>
      )}
    </div>
  );
}

function EvalCell({ row }: { row: ActiveInternRow }) {
  const s = row.evaluation.state;
  if (s === 'NONE') {
    return <span className="text-xs text-slate-400" title="Available after a project completes">—</span>;
  }
  if (s === 'COMPLETED') {
    return (
      <Pill tone="emerald">
        <span className="inline-flex items-center gap-1">
          <CheckCircle2 className="h-3 w-3" /> Done
        </span>
      </Pill>
    );
  }
  if (s === 'OVERDUE') {
    return (
      <Pill tone="rose">
        <span className="inline-flex items-center gap-1">
          <AlertTriangle className="h-3 w-3" /> Not done
        </span>
      </Pill>
    );
  }
  return <Pill tone="amber">{s}</Pill>;
}

function TimesheetCell({ row }: { row: ActiveInternRow }) {
  const ts = row.timesheet;
  const chips: Array<{ label: string; count: number; tone: PillTone }> = ([
    { label: 'Approved',  count: ts.approvedCount,  tone: 'emerald' as PillTone },
    { label: 'Verified',  count: ts.verifiedCount,  tone: 'indigo' as PillTone },
    { label: 'Submitted', count: ts.submittedCount, tone: 'sky' as PillTone },
    { label: 'Rejected',  count: ts.rejectedCount,  tone: 'rose' as PillTone },
    { label: 'Missing',   count: ts.missingCount,   tone: 'amber' as PillTone },
  ]).filter((c) => c.count > 0);

  if (chips.length === 0) {
    return (
      <div className="flex flex-col gap-0.5">
        <Pill tone="slate">
          <FileWarning className="mr-1 inline h-3 w-3" /> Missing
        </Pill>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex flex-wrap gap-1">
        {chips.map((c) => (
          <Pill key={c.label} tone={c.tone}>{c.label} · {c.count}</Pill>
        ))}
      </div>
      {ts.expectedWeeks > 0 && (
        <span className="text-[10px] text-slate-500">
          {ts.approvedCount}/{ts.expectedWeeks} approved
        </span>
      )}
    </div>
  );
}

// ── Primitives ───────────────────────────────────────────────────────────

export type PillTone = 'emerald' | 'amber' | 'rose' | 'sky' | 'slate' | 'indigo';

function Pill({
  tone, children,
}: { tone: PillTone; children: ReactNode }) {
  const tones: Record<PillTone, string> = {
    emerald: 'bg-green-50 text-green-800 ring-green-200',
    amber:   'bg-amber-50  text-amber-800  ring-amber-200',
    rose:    'bg-red-50   text-red-800   ring-red-200',
    sky:     'bg-slate-100    text-slate-700    ring-slate-200',
    slate:   'bg-slate-100 text-slate-700  ring-slate-200',
    indigo:  'bg-slate-100 text-slate-700 ring-slate-200',
  };
  return (
    <span className={
      'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset '
      + tones[tone]
    }>
      {children}
    </span>
  );
}

function SortableHeader({
  k, current, onClick, children,
}: { k: SortKey; current: SortKey; onClick: (k: SortKey) => void; children: ReactNode }) {
  const active = current === k;
  return (
    <th className="px-3 py-2">
      <button
        type="button"
        onClick={() => onClick(k)}
        className={'inline-flex items-center gap-1 ' + (active ? 'text-slate-900' : 'hover:text-slate-700')}
      >
        {children}
        {active && <span aria-hidden>↓</span>}
      </button>
    </th>
  );
}

// ── Row helpers ──────────────────────────────────────────────────────────

export function rowAttention(r: ActiveInternRow): boolean {
  const p = r.currentMonthProjects;
  if (p.overallState === 'NO_PROJECTS') return true;
  const ktMissing =
    (p.project1 && p.project1.ktStatus !== 'DONE')
    || (p.project2 && p.project2.ktStatus !== 'DONE');
  if (ktMissing) return true;
  if (r.timesheet.state === 'REJECTED' || r.timesheet.state === 'MISSING') return true;
  if (r.evaluation.state === 'OVERDUE') return true;
  if (r.reportingStructure?.managerId == null) return true;
  return false;
}

export function rowMatchesFilter(r: ActiveInternRow, f: RowFilter): boolean {
  switch (f) {
    case 'all':         return true;
    case 'attention':   return rowAttention(r);
    case 'no_project':  return r.currentMonthProjects.overallState === 'NO_PROJECTS';
    case 'kt_missing': {
      const p = r.currentMonthProjects;
      return Boolean(
        (p.project1 && p.project1.ktStatus !== 'DONE')
        || (p.project2 && p.project2.ktStatus !== 'DONE'),
      );
    }
    case 'ts_incomplete': return r.timesheet.state !== 'APPROVED';
    case 'eval_overdue':  return r.evaluation.state === 'OVERDUE';
    case 'no_manager':    return r.reportingStructure?.managerId == null;
  }
}

export function rowComparator(s: SortKey): (a: ActiveInternRow, b: ActiveInternRow) => number {
  const byName = (a: ActiveInternRow, b: ActiveInternRow) =>
    (a.fullName ?? '').localeCompare(b.fullName ?? '');
  switch (s) {
    case 'name':     return byName;
    case 'project':  return (a, b) =>
      (a.currentMonthProjects.overallState ?? '').localeCompare(b.currentMonthProjects.overallState ?? '')
      || byName(a, b);
    case 'kt':       return (a, b) => ktOrder(a) - ktOrder(b) || byName(a, b);
    case 'eval':     return (a, b) =>
      (a.evaluation.state ?? '').localeCompare(b.evaluation.state ?? '') || byName(a, b);
    case 'timesheet': return (a, b) =>
      (a.timesheet.state ?? '').localeCompare(b.timesheet.state ?? '') || byName(a, b);
  }
}

function ktOrder(r: ActiveInternRow): number {
  const slots = [r.currentMonthProjects.project1, r.currentMonthProjects.project2]
    .filter(Boolean) as ProjectSlot[];
  if (slots.length === 0) return 3;
  if (slots.every((s) => s.ktStatus === 'DONE')) return 0;
  if (slots.some((s) => s.ktStatus === 'DONE')) return 1;
  return 2;
}

void Clock; // imported for cell helpers; kept in tree for future use
