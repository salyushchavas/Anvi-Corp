'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import SignOutButton from '@/components/auth/SignOutButton';
import { BRAND } from '@/lib/brand';

/**
 * Manager Phase 0 — 10-item sidebar per the Manager Oversight spec (§10
 * of the master spec). The finer-grained spec sections (Interviews, Offers
 * &amp; New Hire, Training Delivery, Evaluation Delivery, Team Workload)
 * fold into these as sub-views in later phases — Interviews into Applicant
 * Pipeline, Offers/New Hire into Onboarding Health, Training/Evaluation
 * Delivery into Active Interns + Risk Center, Team Workload into Reports.
 *
 * <p>Phase 0 lights up Home + a placeholder for every item; Phase 1+ will
 * incrementally swap each placeholder for the real surface.</p>
 */
const ITEMS: { href: string; label: string; phase: number }[] = [
  { href: '/careers/manager',                       label: 'Home',                phase: 1 },
  { href: '/careers/manager/applicant-pipeline',    label: 'Applicant Pipeline',  phase: 1 },
  { href: '/careers/manager/onboarding-health',     label: 'Onboarding Health',   phase: 2 },
  { href: '/careers/manager/active-interns',        label: 'Active Interns',      phase: 3 },
  { href: '/careers/manager/timesheet-approvals',   label: 'Timesheet Approvals', phase: 3 },
  { href: '/careers/manager/risk-center',           label: 'Risk Center',         phase: 4 },
  { href: '/careers/manager/reports',               label: 'Reports',             phase: 4 },
  { href: '/careers/manager/inactive-interns',      label: 'Inactive Interns',    phase: 4 },
  { href: '/careers/manager/settings',              label: 'Settings',            phase: 5 },
  { href: '/careers/manager/help',                  label: 'Help',                phase: 5 },
];

export default function ManagerSidebar() {
  const pathname = usePathname();
  return (
    <aside className="hidden h-screen w-60 shrink-0 flex-col border-r border-slate-200 bg-white lg:flex">
      <div className="border-b border-slate-200 px-4 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {BRAND.name}
        </p>
        <p className="mt-0.5 text-sm font-semibold text-slate-900">Manager</p>
      </div>
      <nav className="flex-1 overflow-y-auto p-2">
        <ul className="space-y-0.5">
          {ITEMS.map((it) => {
            const active =
              pathname === it.href ||
              (it.href !== '/careers/manager' && pathname?.startsWith(it.href));
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
          Role: Manager · Audit log
        </p>
      </div>
    </aside>
  );
}
