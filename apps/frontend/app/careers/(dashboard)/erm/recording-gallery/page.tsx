'use client';

import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import RecordingGallery from '@/components/recordings/RecordingGallery';

/**
 * The parent {@code (dashboard)/erm/layout.tsx} is a provider-only
 * shell — it does NOT render the sidebar/topbar chrome. Each ERM
 * page wraps itself in {@link DashboardLayout} to pick up the
 * sidebar (matches the pattern used by {@code erm/document-gallery},
 * {@code erm/interviews}, etc.). Without this wrapper the page
 * renders full-screen with no sidebar — the bug this fix is for.
 */
export default function ErmRecordingGalleryPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <RecordingGallery
          title="Recording Gallery"
          subtitle="Every intern's interview + monthly evaluation recordings — file-explorer navigation."
        />
      </DashboardLayout>
    </ProtectedRoute>
  );
}
