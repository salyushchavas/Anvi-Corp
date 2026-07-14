'use client';

import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import PageHeader from '@/components/ui/PageHeader';

export default function ReportingManagerDashboardPage() {
  return (
    <ProtectedRoute requiredRoles={['REPORTING_MANAGER', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader title="Reporting Manager Dashboard" />
        <section className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
          <h2 className="text-base font-semibold text-slate-900">
            Dashboard under construction
          </h2>
          <p className="mt-2 text-sm text-slate-600">
            Functionality coming in next iteration.
          </p>
        </section>
      </DashboardLayout>
    </ProtectedRoute>
  );
}
