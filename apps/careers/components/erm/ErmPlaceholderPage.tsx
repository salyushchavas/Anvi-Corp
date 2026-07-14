'use client';

import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';

interface Props {
  title: string;
  subtitle?: string;
  phase: number;
}

/**
 * ERM Phase 0 — shared scaffolding for the 14 left-nav pages until their
 * implementing phase lands. Renders the dashboard chrome + PageHeader and
 * a "Coming in ERM Phase {N}" empty state so the route + sidebar pairing
 * is verifiable today.
 */
export default function ErmPlaceholderPage({ title, subtitle, phase }: Props) {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader title={title} subtitle={subtitle} />
        <section className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
          <h2 className="text-base font-semibold text-slate-900">
            Coming in ERM Phase {phase}
          </h2>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-500">
            This page is scaffolded by ERM Phase 0. The implementing phase
            will fill it in.
          </p>
        </section>
      </DashboardLayout>
    </ProtectedRoute>
  );
}
