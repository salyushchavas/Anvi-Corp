"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { KeyRound, Plus, Search, UserCheck, UserX } from "lucide-react";
import { ConfirmDialog, RoleBadge, Spinner, StatusBadge } from "../ui";
import { useToast } from "../toast";
import { adminApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { CreateMailboxModal } from "./create-mailbox-modal";
import { CredentialsModal } from "./credentials-modal";
import type { MailCredential, MailDomain, MailMailbox, MailRole } from "@/lib/mail-types";
import type { StoredAccount } from "@/lib/mail-api";

type Pending =
  | { kind: "suspend" | "reactivate"; mb: MailMailbox }
  | { kind: "reset"; mb: MailMailbox }
  | null;

export function MailboxesPanel({ account, domains }: { account: StoredAccount; domains: MailDomain[] }) {
  const toast = useToast();
  const isSuper = account.role === "SUPER_ADMIN";

  const [rows, setRows] = useState<MailMailbox[]>([]);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [domainId, setDomainId] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");

  const [createOpen, setCreateOpen] = useState(false);
  const [credential, setCredential] = useState<MailCredential | null>(null);
  const [pending, setPending] = useState<Pending>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await adminApi.listMailboxes({ status: status || undefined, domainId: domainId || undefined, search: search || undefined }));
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not load mailboxes.");
    } finally {
      setLoading(false);
    }
  }, [status, domainId, search, toast]);

  useEffect(() => {
    load();
  }, [load]);

  function submitSearch(e: FormEvent) {
    e.preventDefault();
    setSearch(searchInput.trim());
  }

  async function changeRole(mb: MailMailbox, role: MailRole) {
    try {
      await adminApi.setRole(mb.accountId, role);
      toast.success(`Role updated for ${mb.email}.`);
      load();
    } catch (err) {
      // Backend is the real guard (e.g. MAIL_LAST_SUPER_ADMIN, admin-can't-grant-super).
      toast.error(err instanceof MailApiError ? err.message : "Could not change the role.");
      load();
    }
  }

  async function runPending() {
    if (!pending) return;
    setBusy(true);
    try {
      if (pending.kind === "reset") {
        const cred = await adminApi.resetPassword(pending.mb.accountId);
        setPending(null);
        setCredential(cred);
      } else if (pending.kind === "suspend") {
        await adminApi.suspend(pending.mb.accountId);
        toast.success(`Suspended ${pending.mb.email}.`);
        setPending(null);
        load();
      } else {
        await adminApi.reactivate(pending.mb.accountId);
        toast.success(`Reactivated ${pending.mb.email}.`);
        setPending(null);
        load();
      }
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Action failed.");
      setPending(null);
    } finally {
      setBusy(false);
    }
  }

  const roleOptions: MailRole[] = isSuper ? ["USER", "ADMIN", "SUPER_ADMIN"] : ["USER", "ADMIN"];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <form onSubmit={submitSearch} className="relative flex-1 min-w-[12rem]">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300" />
          <input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search name or address…"
            className="w-full rounded-full border border-ink-100 bg-white py-2 pl-9 pr-3 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
          />
        </form>

        {isSuper && (
          <select value={domainId} onChange={(e) => setDomainId(e.target.value)} className="rounded-full border border-ink-100 bg-white px-4 py-2 text-sm">
            <option value="">All domains</option>
            {domains.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        )}

        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-full border border-ink-100 bg-white px-4 py-2 text-sm">
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
        </select>

        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-full bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-600"
        >
          <Plus className="h-4 w-4" /> New mailbox
        </button>
      </div>

      <div className="overflow-x-auto rounded-2xl border border-ink-100">
        <table className="w-full text-sm">
          <thead className="border-b border-ink-100 bg-ink-50 text-left text-xs uppercase tracking-wide text-ink-400">
            <tr>
              <th className="px-4 py-3 font-semibold">Mailbox</th>
              <th className="px-4 py-3 font-semibold">Role</th>
              <th className="px-4 py-3 font-semibold">Status</th>
              <th className="px-4 py-3 text-right font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={4} className="px-4 py-10 text-center">
                  <Spinner className="mx-auto h-6 w-6" />
                </td>
              </tr>
            )}
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-10 text-center text-ink-300">
                  No mailboxes found.
                </td>
              </tr>
            )}
            {!loading &&
              rows.map((mb) => {
                // An ADMIN cannot manage a SUPER_ADMIN target — hide those controls
                // (the backend also 403s; this just avoids offering a dead action).
                const manageable = isSuper || mb.role !== "SUPER_ADMIN";
                const active = mb.status === "ACTIVE";
                return (
                  <tr key={mb.accountId} className="border-b border-ink-50 last:border-0">
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-800">{mb.email}</div>
                      {mb.displayName && <div className="text-xs text-ink-400">{mb.displayName}</div>}
                    </td>
                    <td className="px-4 py-3">
                      {manageable ? (
                        <select
                          value={mb.role}
                          onChange={(e) => changeRole(mb, e.target.value as MailRole)}
                          className="rounded-lg border border-ink-100 bg-white px-2 py-1 text-xs"
                        >
                          {roleOptions.map((r) => (
                            <option key={r} value={r}>
                              {r.replace("_", " ")}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <RoleBadge role={mb.role} />
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={mb.status} />
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        {manageable && (
                          <>
                            <button
                              type="button"
                              onClick={() => setPending({ kind: "reset", mb })}
                              title="Reset password"
                              className="rounded-lg p-2 text-ink-500 hover:bg-ink-50 hover:text-brand"
                            >
                              <KeyRound className="h-4 w-4" />
                            </button>
                            <button
                              type="button"
                              onClick={() => setPending({ kind: active ? "suspend" : "reactivate", mb })}
                              title={active ? "Suspend" : "Reactivate"}
                              className={`rounded-lg p-2 hover:bg-ink-50 ${active ? "text-ink-500 hover:text-red-600" : "text-ink-500 hover:text-green-600"}`}
                            >
                              {active ? <UserX className="h-4 w-4" /> : <UserCheck className="h-4 w-4" />}
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
          </tbody>
        </table>
      </div>

      {createOpen && (
        <CreateMailboxModal
          domains={domains}
          fixedDomainId={isSuper ? undefined : domains[0]?.id}
          onClose={() => setCreateOpen(false)}
          onCreated={(cred) => {
            setCreateOpen(false);
            setCredential(cred);
            load();
          }}
        />
      )}

      {credential && <CredentialsModal credential={credential} onClose={() => setCredential(null)} />}

      <ConfirmDialog
        open={pending !== null}
        title={
          pending?.kind === "reset"
            ? "Reset password"
            : pending?.kind === "suspend"
              ? "Suspend mailbox"
              : "Reactivate mailbox"
        }
        message={
          pending?.kind === "reset"
            ? `Generate a new one-time password for ${pending?.mb.email}? Their current sessions will be revoked.`
            : pending?.kind === "suspend"
              ? `Suspend ${pending?.mb.email}? They will be signed out and unable to log in.`
              : `Reactivate ${pending?.mb.email}? They will be able to log in again.`
        }
        confirmLabel={pending?.kind === "reset" ? "Reset password" : pending?.kind === "suspend" ? "Suspend" : "Reactivate"}
        danger={pending?.kind === "suspend"}
        busy={busy}
        onConfirm={runPending}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}
