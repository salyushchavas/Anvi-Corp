// Route-group wrapper for every dashboard route. Mounts the login
// summary popup here so it fires exactly once per browser session on
// the first dashboard mount — surviving refresh, honouring the auth
// redirect's returnTo, and covering every per-role layout below (each
// of which wraps its own content in <DashboardLayout> / a custom shell).
//
// The popup component itself gates on hasNewSinceLastSeen from the
// server + a sessionStorage flag, so this layout doesn't need to
// per-route-check anything.
//
// Wave-2 #10 — InternDashboardBoundary lives here so every dashboard
// route (including the shared /careers/messages route) sees the intern
// module map when the caller is an intern. Previously the provider was
// scoped to /careers/intern/* only, which meant the shared sidebar
// silently rendered ungated links on Messages.

import LoginTodoPopup from '@/components/todos/LoginTodoPopup';
import InternDashboardBoundary from '@/components/intern/InternDashboardBoundary';

export default function DashboardSegmentLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <InternDashboardBoundary>
      {children}
      <LoginTodoPopup />
    </InternDashboardBoundary>
  );
}
