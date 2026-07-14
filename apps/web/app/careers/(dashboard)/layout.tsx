// Passthrough — each dashboard page wraps its own content in <DashboardLayout>
// from components/dashboard/. Keeping this file so the (dashboard) route group
// has an explicit layout and any future dashboard-wide providers can land here.

export default function DashboardSegmentLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <>{children}</>;
}
