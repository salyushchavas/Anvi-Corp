'use client';

import { PostingsManagementPage } from '@/components/careers/PostingsManagementPage';

export default function AdminJobsPage() {
  return <PostingsManagementPage requiredRoles={['JOBS_ADMIN', 'SUPER_ADMIN']} />;
}
