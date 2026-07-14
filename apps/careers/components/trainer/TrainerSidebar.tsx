'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import SignOutButton from '@/components/auth/SignOutButton';
import { BRAND } from '@/lib/brand';

/**
 * Trainer Phase 0 — 9-item sidebar per Trainer doc §4.
 *
 * <p>Phase 1 will wire badge counts (active interns, projects due,
 * submissions pending, etc.) once the underlying Trainer service
 * exists. For Phase 0 every link renders unstyled badges.</p>
 */
const ITEMS: { href: string; label: string; phase: number }[] = [
  { href: '/careers/trainer',                  label: 'Home',             phase: 1 },
  { href: '/careers/trainer/active-interns',   label: 'Active Interns',   phase: 1 },
  { href: '/careers/trainer/assign-project',   label: 'Assign Project',   phase: 2 },
  { href: '/careers/trainer/weekly-meetings',  label: 'Weekly Meetings',  phase: 3 },
  { href: '/careers/trainer/weekly-tracker',   label: 'Weekly Tracker',   phase: 3 },
  { href: '/careers/trainer/doubts',           label: 'Doubt Requests',   phase: 3 },
  { href: '/careers/trainer/pending-reviews',  label: 'Pending Reviews',  phase: 3 },
  { href: '/careers/trainer/feedback-history', label: 'Feedback History', phase: 4 },
  { href: '/careers/trainer/files-templates',  label: 'Files / Templates', phase: 2 },
  { href: '/careers/trainer/reports',          label: 'Reports',          phase: 4 },
  { href: '/careers/trainer/settings',         label: 'Settings',         phase: 4 },
  { href: '/careers/trainer/help',             label: 'Help',             phase: 4 },
];

export default function TrainerSidebar() {
  const pathname = usePathname();
  return (
    <aside className="hidden h-screen w-60 shrink-0 flex-col border-r border-slate-200 bg-white lg:flex">
      <div className="border-b border-slate-200 px-4 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {BRAND.name}
        </p>
        <p className="mt-0.5 text-sm font-semibold text-slate-900">Trainer</p>
      </div>
      <nav className="flex-1 overflow-y-auto p-2">
        <ul className="space-y-0.5">
          {ITEMS.map((it) => {
            const active =
              pathname === it.href ||
              (it.href !== '/careers/trainer' && pathname?.startsWith(it.href));
            return (
              <li key={it.href}>
                <Link
                  href={it.href}
                  className={
                    'flex items-center justify-between rounded-md px-3 py-2 text-sm ' +
                    (active
                      ? 'bg-brand-50 font-semibold text-brand-800'
                      : 'text-slate-700 hover:bg-slate-50')
                  }
                >
                  <span>{it.label}</span>
                  <span className="text-[10px] text-slate-400">P{it.phase}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="space-y-2 border-t border-slate-200 px-3 py-3">
        <SignOutButton variant="sidebar" />
        <p className="px-3 text-[11px] text-slate-500">
          Role: Trainer · Audit log
        </p>
      </div>
    </aside>
  );
}
