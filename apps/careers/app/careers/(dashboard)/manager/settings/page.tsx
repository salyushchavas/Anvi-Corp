'use client';

import ManagerStubPage from '@/components/manager/ManagerStubPage';

export default function ManagerSettingsPage() {
  return (
    <ManagerStubPage
      title="Settings"
      description="Per-Manager preferences — default span-of-control scope (mine / all), notification cadence for escalations, default view on each section, and email digest frequency."
      phase={5}
    />
  );
}
