'use client';

import MonthPicker from '@/components/common/MonthPicker';
import { useTrainerDashboard } from './TrainerDashboardContext';

/**
 * Persistent trainer-area top bar. Renders in the trainer layout above
 * every child page so the month selector is always visible and a change
 * on any page is immediately reflected in the selector on the next
 * page. Reads/writes {@code selectedMonth} through the sticky global
 * context — see {@link TrainerDashboardProvider} for the persistence
 * model (URL → localStorage → current month, with localStorage as the
 * between-session store).
 */
export default function TrainerTopBar() {
  const { selectedMonth, setSelectedMonth, isCurrentMonth } = useTrainerDashboard();

  return (
    <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white px-6 py-3">
      <div className="text-xs text-slate-500">
        Viewing month
        {!isCurrentMonth && (
          <span
            className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-600"
            title="You've picked a past month. Live queues (To-do panel, Files & templates library) always reflect the current moment — only month-scoped tiles and lists follow this selection."
          >
            past month
          </span>
        )}
      </div>
      <MonthPicker value={selectedMonth} onChange={setSelectedMonth} />
    </header>
  );
}
