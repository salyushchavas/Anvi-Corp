'use client';

import { useState, ReactNode } from 'react';
import DashboardSidebar from './DashboardSidebar';
import DashboardTopbar from './DashboardTopbar';

interface Props {
  children: ReactNode;
  title?: string;
}

/**
 * Dashboard shell. The `ds` class on the root scopes design-system tokens
 * (Inter font, slate-50 app surface, teal focus ring) to /careers/* without
 * leaking into marketing pages.
 *
 * Layout: fixed sidebar (lg+) / off-canvas drawer (sm/md), top bar, scrollable
 * main column. Mobile-first: at 375px the sidebar collapses behind a hamburger
 * and the topbar exposes a menu button.
 */
export default function DashboardLayout({ children, title }: Props) {
  const [open, setOpen] = useState(false);

  return (
    <div className="ds flex h-screen overflow-hidden">
      {/* Desktop sidebar */}
      <aside className="hidden lg:flex">
        <DashboardSidebar />
      </aside>

      {/* Mobile sidebar — off-canvas */}
      <aside
        className={
          'fixed inset-y-0 left-0 z-40 transform transition-transform duration-200 ease-out lg:hidden ' +
          (open ? 'translate-x-0' : '-translate-x-full')
        }
      >
        <DashboardSidebar onNavigate={() => setOpen(false)} />
      </aside>

      {open && (
        <button
          type="button"
          aria-label="Close menu"
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-30 bg-slate-900/40 lg:hidden"
        />
      )}

      <div className="flex flex-1 flex-col overflow-hidden">
        <DashboardTopbar title={title} onMenuClick={() => setOpen(true)} />
        <main className="flex-1 overflow-y-auto px-4 py-6 md:px-8 md:py-8 lg:px-10">
          <div className="mx-auto w-full max-w-7xl">{children}</div>
        </main>
      </div>
    </div>
  );
}
