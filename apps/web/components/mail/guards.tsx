"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useMailAuth } from "./mail-auth-provider";
import { FullScreenSpinner } from "./ui";

/**
 * Gate for the mail client: no token → /mail/login; must-change → /mail/change-password;
 * otherwise render. The backend is still the real authority (401 on the api instance
 * also redirects); this is the fast client-side gate.
 */
export function MailGuard({ children }: { children: ReactNode }) {
  const { status, account } = useMailAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "anon") {
      router.replace("/mail/login");
    } else if (status === "authed" && account?.mustChangePassword) {
      router.replace("/mail/change-password");
    }
  }, [status, account, router]);

  if (status !== "authed" || account?.mustChangePassword) {
    return <FullScreenSpinner />;
  }
  return <>{children}</>;
}

/** Admin gate: USER → /mail; ADMIN/SUPER_ADMIN allowed. Backend 403/404 is the real guard. */
export function MailAdminGuard({ children }: { children: ReactNode }) {
  return (
    <MailGuard>
      <AdminGate>{children}</AdminGate>
    </MailGuard>
  );
}

function AdminGate({ children }: { children: ReactNode }) {
  const { account } = useMailAuth();
  const router = useRouter();

  useEffect(() => {
    if (account && account.role === "USER") {
      router.replace("/mail");
    }
  }, [account, router]);

  if (!account || account.role === "USER") {
    return <FullScreenSpinner />;
  }
  return <>{children}</>;
}
