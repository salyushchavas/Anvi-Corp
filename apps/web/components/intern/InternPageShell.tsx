'use client';

import type { ReactNode } from 'react';
import PageHeader from '@/components/ui/PageHeader';
import InternJourneyStepper from './InternJourneyStepper';
import RightSidePanel from './RightSidePanel';
import InactiveBanner from '@/components/exit/InactiveBanner';
import { useInternDashboard } from './InternDashboardContext';

interface Props {
  title: ReactNode;
  subtitle?: ReactNode;
  children?: ReactNode;
  /** Suppress the InternJourneyStepper for pages where the lifecycle
   *  is irrelevant (e.g. an active intern's project detail page).
   *  Default false — every other intern page keeps the tracker. */
  hideTracker?: boolean;
  /** Suppress the global RightSidePanel and collapse the layout to a
   *  single-column main. Use when the page provides its own
   *  consolidated right rail. Default false. */
  hideRightSidePanel?: boolean;
}

/**
 * Standard top-of-page wrapper for every intern surface. Renders:
 *
 *   <PageHeader title subtitle />
 *   <InternJourneyStepper status />    ← shared with the home journey-view (opt-out)
 *   <main> {children} </main>
 *   <RightSidePanel />                 ← Phase 7 — contacts + reminders + bell + help (opt-out)
 *
 * On lg+ screens the right side panel sits in a 1/4 column next to the
 * main content; on smaller viewports it tucks underneath.
 */
export default function InternPageShell({
  title, subtitle, children,
  hideTracker = false,
  hideRightSidePanel = false,
}: Props) {
  const { data, loading } = useInternDashboard();

  const inactive = data?.mode === 'INACTIVE';
  return (
    <>
      {inactive && <InactiveBanner exitSummary={data?.exitSummary ?? null} />}
      <PageHeader title={title} subtitle={subtitle} />
      {!hideTracker && data && <InternJourneyStepper status={data.lifecycleStatus} />}
      {!hideTracker && !data && loading && (
        <div className="mb-6 h-12 animate-pulse rounded-md bg-slate-100" aria-hidden />
      )}
      {hideRightSidePanel ? (
        <main className="min-w-0">{children}</main>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
          <main className="min-w-0">{children}</main>
          <RightSidePanel />
        </div>
      )}
    </>
  );
}
