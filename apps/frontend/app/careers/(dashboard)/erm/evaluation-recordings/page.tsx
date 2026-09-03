'use client';

import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import EvaluationRecordingsGallery from '@/components/dashboard/EvaluationRecordingsGallery';

/**
 * ERM view of the evaluator session-recording gallery. Same shared
 * component as {@code /careers/manager/evaluation-recordings}; the
 * backend gates on ERM / MANAGER / SUPER_ADMIN.
 *
 * <p>Wrapped in {@link DashboardLayout} so the sidebar/topbar chrome
 * renders. The parent {@code (dashboard)/erm/layout.tsx} is a
 * provider-only shell and does not render the sidebar itself —
 * each ERM page opts in via this wrapper (matches
 * {@code erm/document-gallery}, {@code erm/interviews}, etc.).</p>
 */
export default function ErmEvaluationRecordingsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <div className="mx-auto max-w-6xl space-y-4 p-6">
          <header>
            <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-700">
              Evaluations
            </p>
            <h1 className="mt-1 text-2xl font-semibold text-slate-900">
              Recording Sessions
            </h1>
            <p className="mt-1 text-sm text-slate-600">
              Video recordings the evaluator attached to each monthly / final /
              I-983 evaluation session. Grouped by month → intern → project.
            </p>
          </header>
          <EvaluationRecordingsGallery />
        </div>
      </DashboardLayout>
    </ProtectedRoute>
  );
}
