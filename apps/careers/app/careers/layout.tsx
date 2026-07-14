// AuthProvider was hoisted to app/layout.tsx so the homepage and /jobs (which
// use SiteHeader -> useAuth) get the context too. This layout is now a thin
// passthrough; keep it so the careers route segment still has an explicit
// layout file (and any future careers-only providers can land here).

export default function CareersLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <>{children}</>;
}
