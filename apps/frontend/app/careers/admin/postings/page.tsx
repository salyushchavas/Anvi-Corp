'use client';

import { PostingsManagementPage } from '@/components/careers/PostingsManagementPage';

export default function AdminPostingsPage() {
  return (
    <PostingsManagementPage requiredRoles={['ERM', 'JOBS_ADMIN', 'SUPER_ADMIN']} />
  );
}
