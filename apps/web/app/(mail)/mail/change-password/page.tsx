"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, KeyRound } from "lucide-react";
import { useMailAuth } from "@/components/mail/mail-auth-provider";
import { FormField, Input, Spinner } from "@/components/mail/ui";
import { authApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";

export default function ChangePasswordPage() {
  const { status, account, applySession } = useMailAuth();
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Not signed in → login. (A must-change principal IS allowed here.)
  useEffect(() => {
    if (status === "anon") router.replace("/mail/login");
  }, [status, router]);

  if (status !== "authed" || !account) {
    return (
      <div className="flex h-screen items-center justify-center bg-ink-gradient">
        <Spinner className="h-8 w-8 text-white" />
      </div>
    );
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (newPassword.length < 8) {
      setError("New password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirm) {
      setError("New password and confirmation do not match.");
      return;
    }
    setBusy(true);
    try {
      const resp = await authApi.changePassword(currentPassword, newPassword);
      applySession(resp);
      router.replace("/mail");
    } catch (err) {
      setError(err instanceof MailApiError ? err.message : "Could not change password. Please try again.");
      setBusy(false);
    }
  }

  const forced = account.mustChangePassword;

  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-gradient px-4">
      <div className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-cardHover">
        <div className="mb-6 flex flex-col items-center text-center">
          <span className="mb-3 inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-gradient text-white">
            <KeyRound className="h-6 w-6" />
          </span>
          <h1 className="text-xl font-bold text-ink-800">Change your password</h1>
          <p className="mt-1 text-sm text-ink-400">
            {forced ? "You must set a new password before continuing." : `Signed in as ${account.email}`}
          </p>
        </div>

        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <FormField label="Current password" htmlFor="cur" required>
            <Input id="cur" type="password" autoComplete="current-password" value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)} required />
          </FormField>
          <FormField label="New password" htmlFor="np" required hint="At least 8 characters.">
            <Input id="np" type="password" autoComplete="new-password" value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)} required />
          </FormField>
          <FormField label="Confirm new password" htmlFor="cp" required>
            <Input id="cp" type="password" autoComplete="new-password" value={confirm}
              onChange={(e) => setConfirm(e.target.value)} required />
          </FormField>

          {error && (
            <div className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <span>{error}</span>
            </div>
          )}

          <button type="submit" disabled={busy}
            className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 font-semibold text-white shadow-card transition hover:bg-brand-600 hover:shadow-cardHover disabled:opacity-60">
            {busy ? <Spinner className="h-5 w-5 text-white" /> : "Update password"}
          </button>
        </form>
      </div>
    </div>
  );
}
