"use client";

import { useState, type FormEvent } from "react";
import { FormField, Input, Modal, Spinner } from "../ui";
import { adminApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { useToast } from "../toast";
import type { MailCredential, MailDomain } from "@/lib/mail-types";

/**
 * Create-mailbox form. Domain is a picker for SUPER_ADMIN, fixed for ADMIN.
 * Password is optional — "Auto-generate" (default) sends none so the server
 * generates a strong one. On success the parent shows the one-time credentials.
 */
export function CreateMailboxModal({
  domains,
  fixedDomainId,
  onClose,
  onCreated,
}: {
  domains: MailDomain[];
  fixedDomainId?: string;
  onClose: () => void;
  onCreated: (c: MailCredential) => void;
}) {
  const toast = useToast();
  const [localPart, setLocalPart] = useState("");
  const [domainId, setDomainId] = useState(fixedDomainId ?? domains[0]?.id ?? "");
  const [displayName, setDisplayName] = useState("");
  const [autoGenerate, setAutoGenerate] = useState(true);
  const [password, setPassword] = useState("");
  const [requireChange, setRequireChange] = useState(true);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!domainId) {
      toast.error("Choose a domain.");
      return;
    }
    setBusy(true);
    try {
      const cred = await adminApi.createMailbox({
        domainId,
        localPart: localPart.trim(),
        displayName: displayName.trim() || undefined,
        password: autoGenerate ? undefined : password,
        requireChangeOnFirstLogin: requireChange,
      });
      onCreated(cred);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not create the mailbox.");
      setBusy(false);
    }
  }

  const domainName = domains.find((d) => d.id === domainId)?.name;

  return (
    <Modal open onClose={onClose} title="Create mailbox" width="max-w-lg">
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Local part" htmlFor="local" required>
            <Input id="local" value={localPart} onChange={(e) => setLocalPart(e.target.value)} placeholder="jane.doe" required />
          </FormField>
          <FormField label="Domain" htmlFor="domain" required>
            {fixedDomainId ? (
              <Input id="domain" value={domainName ?? ""} disabled />
            ) : (
              <select
                id="domain"
                value={domainId}
                onChange={(e) => setDomainId(e.target.value)}
                className="block w-full rounded-xl border border-ink-100 bg-white px-4 py-2.5 text-sm text-ink-800 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
              >
                {domains.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
            )}
          </FormField>
        </div>

        <FormField label="Display name" htmlFor="dn">
          <Input id="dn" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Jane Doe" />
        </FormField>

        <div className="rounded-xl border border-ink-100 p-3">
          <label className="flex items-center gap-2 text-sm font-medium text-ink-800">
            <input type="checkbox" checked={autoGenerate} onChange={(e) => setAutoGenerate(e.target.checked)} className="accent-brand" />
            Auto-generate a strong password
          </label>
          {!autoGenerate && (
            <div className="mt-3">
              <FormField label="Password" htmlFor="pw" hint="At least 8 characters.">
                <Input id="pw" type="text" value={password} onChange={(e) => setPassword(e.target.value)} />
              </FormField>
            </div>
          )}
        </div>

        <label className="flex items-center gap-2 text-sm text-ink-700">
          <input type="checkbox" checked={requireChange} onChange={(e) => setRequireChange(e.target.checked)} className="accent-brand" />
          Require password change on first sign-in
        </label>

        <div className="mt-2 flex justify-end gap-3">
          <button type="button" onClick={onClose} disabled={busy} className="rounded-full px-5 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50 disabled:opacity-60">
            Cancel
          </button>
          <button type="submit" disabled={busy} className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2 text-sm font-semibold text-white hover:bg-brand-600 disabled:opacity-60">
            {busy && <Spinner className="h-4 w-4 text-white" />}
            Create mailbox
          </button>
        </div>
      </form>
    </Modal>
  );
}
