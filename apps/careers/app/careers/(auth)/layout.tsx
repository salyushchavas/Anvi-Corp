// Passthrough — each auth page wraps its own content in <AuthLayout>
// from components/dashboard/.

export default function AuthSegmentLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <>{children}</>;
}
