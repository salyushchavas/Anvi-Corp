"use client";

import { useState } from "react";
import { Check, Copy, ShieldAlert } from "lucide-react";
import { Modal } from "../ui";
import type { MailCredential } from "@/lib/mail-types";

/**
 * Show-ONCE credentials. The one-time password lives only in this modal's props
 * (from the create/reset response) and is cleared when it closes — it is never
 * refetched or stored anywhere else.
 */
export function CredentialsModal({ credential, onClose }: { credential: MailCredential; onClose: () => void }) {
  const [copied, setCopied] = useState(false);

  function copy() {
    navigator.clipboard?.writeText(credential.oneTimePassword).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      },
      () => {
        /* clipboard blocked — user can still select the text */
      },
    );
  }

  return (
    <Modal open onClose={onClose} title="One-time password" width="max-w-md">
      <div className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
        <ShieldAlert className="mt-0.5 h-4 w-4 flex-shrink-0" />
        <span>You won&apos;t be able to see this password again. Copy it now and share it securely.</span>
      </div>

      <dl className="mt-4 space-y-3">
        <div>
          <dt className="text-xs font-medium text-ink-400">Mailbox</dt>
          <dd className="font-medium text-ink-800">{credential.email}</dd>
        </div>
        <div>
          <dt className="text-xs font-medium text-ink-400">One-time password</dt>
          <dd className="mt-1 flex items-center gap-2">
            <code className="flex-1 select-all rounded-lg bg-ink-50 px-3 py-2 font-mono text-sm text-ink-800">
              {credential.oneTimePassword}
            </code>
            <button
              type="button"
              onClick={copy}
              className="inline-flex items-center gap-1 rounded-lg border border-ink-100 px-3 py-2 text-sm font-medium text-ink-700 hover:bg-ink-50"
            >
              {copied ? <Check className="h-4 w-4 text-brand" /> : <Copy className="h-4 w-4" />}
              {copied ? "Copied" : "Copy"}
            </button>
          </dd>
        </div>
      </dl>

      <p className="mt-3 text-xs text-ink-400">
        {credential.mustChangePassword
          ? "The user must change this password on first sign-in."
          : "The user can sign in with this password."}
      </p>

      <div className="mt-6 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          className="rounded-full bg-brand px-6 py-2 text-sm font-semibold text-white hover:bg-brand-600"
        >
          Done
        </button>
      </div>
    </Modal>
  );
}
