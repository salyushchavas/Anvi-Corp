'use client';

/**
 * Belt-and-suspenders route gate for intern pages. The sidebar
 * (DashboardSidebar.tsx) already hides links whose module visibility
 * flag is false, but a stale tab, a bookmarked URL, or a deep link
 * shared earlier can still land an intern on a page that no longer
 * applies to their stage (e.g. an active intern hitting
 * /careers/intern/jobs, or a pre-active intern hitting
 * /careers/intern/projects). This guard checks the current path
 * against the server-authoritative module visibility map and silently
 * redirects to the intern home when the requested page is hidden.
 *
 * Single source of truth = the dashboard endpoint's `modules` map.
 */

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import {
  useInternDashboardOptional,
  type InternModulesMap,
} from './InternDashboardContext';

// Path prefix → module key. Order matters when prefixes nest — longer
// prefixes win because the loop checks them in declared order.
const PATH_TO_MODULE: { prefix: string; key: keyof InternModulesMap }[] = [
  { prefix: '/careers/intern/jobs',         key: 'jobPostings' },
  { prefix: '/careers/intern/applications', key: 'myApplications' },
  { prefix: '/careers/intern/interviews',   key: 'interviewCenter' },
  { prefix: '/careers/intern/offer',        key: 'offerLetter' },
  { prefix: '/careers/intern/onboarding',   key: 'onboarding' },
  { prefix: '/careers/intern/projects',     key: 'myProjects' },
  { prefix: '/careers/intern/timesheets',   key: 'timesheets' },
  { prefix: '/careers/intern/evaluations',  key: 'evaluations' },
  { prefix: '/careers/intern/documents',    key: 'documents' },
  { prefix: '/careers/intern/messages',     key: 'messages' },
  { prefix: '/careers/intern/doubts',       key: 'doubts' },
];

function moduleForPath(pathname: string): keyof InternModulesMap | null {
  for (const { prefix, key } of PATH_TO_MODULE) {
    if (pathname === prefix || pathname.startsWith(prefix + '/')) return key;
  }
  return null;
}

export default function InternModuleRouteGuard() {
  const pathname = usePathname() ?? '';
  const router = useRouter();
  const ctx = useInternDashboardOptional();
  const modules = ctx?.data?.modules ?? null;

  useEffect(() => {
    if (!modules) return; // still loading — let the page render its own skeleton
    const moduleKey = moduleForPath(pathname);
    if (!moduleKey) return; // /careers/intern home + help + unguarded paths
    const state = modules[moduleKey];
    if (state && state.visible === false) {
      router.replace('/careers/intern');
    }
  }, [modules, pathname, router]);

  return null;
}
