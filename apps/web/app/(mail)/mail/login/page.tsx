"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, Mail } from "lucide-react";
import { useMailAuth } from "@/components/mail/mail-auth-provider";
import { FormField, Input, Spinner } from "@/components/mail/ui";
import { MailApiError } from "@/lib/mail-api";

const APP_NAME = process.env.NEXT_PUBLIC_APP_NAME || "Mail";

export default function MailLoginPage() {
  const { login, status, account } = useMailAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Already signed in → skip the form.
  useEffect(() => {
    if (status === "authed" && account) {
      router.replace(account.mustChangePassword ? "/mail/change-password" : "/mail");
    }
  }, [status, account, router]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const acct = await login(email.trim(), password);
      router.replace(acct.mustChangePassword ? "/mail/change-password" : "/mail");
    } catch (err) {
      setError(err instanceof MailApiError ? err.message : "Sign-in failed. Please try again.");
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-gradient px-4">
      <div className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-cardHover">
        <div className="mb-8 flex flex-col items-center text-center">
          <span className="mb-3 inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-gradient text-white">
            <Mail className="h-6 w-6" />
          </span>
          <h1 className="text-2xl font-bold text-ink-800">Anvi {APP_NAME}</h1>
          <p className="mt-1 text-sm text-ink-400">Sign in to your mailbox</p>
        </div>

        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <FormField label="Email" htmlFor="email" required>
            <Input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@anvicorp.com"
              required
            />
          </FormField>
          <FormField label="Password" htmlFor="password" required>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </FormField>

          {error && (
            <div className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <span>{error}</span>
            </div>
          )}

          <button
            type="submit"
            disabled={busy}
            className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-brand px-6 py-3 font-semibold text-white shadow-card transition hover:bg-brand-600 hover:shadow-cardHover disabled:opacity-60"
          >
            {busy ? <Spinner className="h-5 w-5 text-white" /> : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
