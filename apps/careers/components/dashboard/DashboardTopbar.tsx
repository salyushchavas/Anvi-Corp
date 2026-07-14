'use client';

import { Menu } from 'lucide-react';
import TopBarBell from './TopBarBell';
import TopBarMailbox from './TopBarMailbox';
import UserMenuDropdown from './UserMenuDropdown';

interface Props {
  title?: string;
  onMenuClick?: () => void;
}

export default function DashboardTopbar({ title, onMenuClick }: Props) {
  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4 md:px-6">
      <div className="flex items-center gap-3">
        {onMenuClick && (
          <button
            type="button"
            onClick={onMenuClick}
            aria-label="Open menu"
            className="-ml-2 rounded-md p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 lg:hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
          >
            <Menu className="h-5 w-5" strokeWidth={2} />
          </button>
        )}
        {title && (
          <h1 className="text-sm font-semibold text-slate-800">{title}</h1>
        )}
      </div>

      <div className="flex items-center gap-1">
        <TopBarBell />
        <TopBarMailbox />
        <UserMenuDropdown />
      </div>
    </header>
  );
}
