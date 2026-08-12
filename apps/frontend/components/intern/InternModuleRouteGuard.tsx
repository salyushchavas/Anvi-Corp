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
//
// Wave-2 #10 — includes /careers/messages (shared cross-role route). The
// intern sidebar's Messages link points there; before this row was added,
// the sidebar could still hide the link but the route itself accepted the
// intern (they'd land on a working mail shell even though Messages was
// meant to be locked for their stage). The guard now redirects them home
// consistently with every /careers/intern/* module. The intern-family
// prefixes cover both the {@code /offer} and {@code /offer-letter} pages
// under the same {@code offerLetter} key because the sidebar exposes
// {@code /careers/intern/offer-letter} — prefix-matching on {@code
// /careers/intern/offer} catches both.
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
  { prefix: '/careers/intern/doubts',       key: 'doubts' },
  { prefix: '/careers/messages',            key: 'messages' },
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
    // Wave-2 #10 — reject BOTH hidden AND locked routes. The sidebar
    // renders a locked link as a greyed-out non-Link so a click won't
    // navigate, but a bookmark or a stale tab can still land the intern
    // on the underlying page. Sending them home matches the intent of
    // the lock (module is not yet available at this stage) and stays
    // consistent with the hidden-module redirect.
    if (state && (state.visible === false || state.locked === true)) {
      router.replace('/careers/intern');
    }
  }, [modules, pathname, router]);

  return null;
}
