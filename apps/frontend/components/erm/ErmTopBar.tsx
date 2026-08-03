'use client';

import MonthPicker from '@/components/common/MonthPicker';
import { useErmDashboardOptional } from './ErmDashboardContext';

/**
 * Persistent ERM-area sub-topbar. Rendered by the shared
 * {@code DashboardLayout} directly under the app-wide {@code
 * DashboardTopbar} whenever the current pathname is under
 * {@code /careers/erm}. Reads/writes {@code selectedMonth} through the
 * sticky global context (see {@link ErmDashboardProvider}) so a change
 * on any page is immediately reflected in the selector on the next
 * page. The "past month" chip is a passive warning — live queues
 * (To-do panel, galleries, forms) intentionally do NOT rescope on the
 * month selection.
 */
export default function ErmTopBar() {
  const ctx = useErmDashboardOptional();
  if (!ctx) return null;
  const { selectedMonth, setSelectedMonth, isCurrentMonth } = ctx;

  return (
    <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white px-6 py-3">
      <div className="text-xs text-slate-500">
        Viewing month
        {!isCurrentMonth && (
          <span
            className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-600"
            title="You've picked a past month. Live queues (To-do panel, galleries, upload/form pages) always reflect the current moment — only month-scoped tiles and lists follow this selection."
          >
            past month
          </span>
        )}
      </div>
      <MonthPicker value={selectedMonth} onChange={setSelectedMonth} />
    </header>
  );
}
