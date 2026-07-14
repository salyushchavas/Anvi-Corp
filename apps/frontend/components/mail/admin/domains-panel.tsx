"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Plus, Power, Trash2 } from "lucide-react";
import { ConfirmDialog, FormField, Input, Modal, Spinner, StatusBadge } from "../ui";
import { useToast } from "../toast";
import { adminApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import type { MailDomain } from "@/lib/mail-types";

export function DomainsPanel() {
  const toast = useToast();
  const [rows, setRows] = useState<MailDomain[]>([]);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [creating, setCreating] = useState(false);
  const [toDelete, setToDelete] = useState<MailDomain | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await adminApi.listDomains());
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not load domains.");
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    load();
  }, [load]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setCreating(true);
    try {
      await adminApi.createDomain(name.trim(), displayName.trim() || undefined);
      toast.success("Domain created.");
      setCreateOpen(false);
      setName("");
      setDisplayName("");
      load();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not create the domain.");
    } finally {
      setCreating(false);
    }
  }

  async function toggleActive(d: MailDomain) {
    try {
      await adminApi.updateDomain(d.id, { active: !d.active });
      toast.success(`${d.name} ${d.active ? "deactivated" : "activated"}.`);
      load();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not update the domain.");
    }
  }

  async function confirmDelete() {
    if (!toDelete) return;
    setBusy(true);
    try {
      await adminApi.deleteDomain(toDelete.id);
      toast.success(`Deleted ${toDelete.name}.`);
      setToDelete(null);
      load();
    } catch (err) {
      // e.g. MAIL_DOMAIN_NOT_EMPTY (409) — surfaced, never swallowed.
      toast.error(err instanceof MailApiError ? err.message : "Could not delete the domain.");
      setToDelete(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="mb-4 flex justify-end">
        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-full bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-600"
        >
          <Plus className="h-4 w-4" /> New domain
        </button>
      </div>

      <div className="overflow-x-auto rounded-2xl border border-ink-100">
        <table className="w-full text-sm">
          <thead className="border-b border-ink-100 bg-ink-50 text-left text-xs uppercase tracking-wide text-ink-400">
            <tr>
              <th className="px-4 py-3 font-semibold">Domain</th>
              <th className="px-4 py-3 font-semibold">Mailboxes</th>
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
                  No domains yet.
                </td>
              </tr>
            )}
            {!loading &&
              rows.map((d) => (
                <tr key={d.id} className="border-b border-ink-50 last:border-0">
                  <td className="px-4 py-3">
                    <div className="font-medium text-ink-800">{d.name}</div>
                    {d.displayName && <div className="text-xs text-ink-400">{d.displayName}</div>}
                  </td>
                  <td className="px-4 py-3 text-ink-600">{d.accountCount}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={d.active ? "ACTIVE" : "SUSPENDED"} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        type="button"
                        onClick={() => toggleActive(d)}
                        title={d.active ? "Deactivate" : "Activate"}
                        className="rounded-lg p-2 text-ink-500 hover:bg-ink-50 hover:text-brand"
                      >
                        <Power className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setToDelete(d)}
                        title="Delete (empty domains only)"
                        className="rounded-lg p-2 text-ink-500 hover:bg-red-50 hover:text-red-600"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      {createOpen && (
        <Modal open onClose={() => setCreateOpen(false)} title="Create domain" width="max-w-md">
          <form onSubmit={create} className="space-y-4">
            <FormField label="Domain name" htmlFor="dn" required hint="e.g. anvicorp.com">
              <Input id="dn" value={name} onChange={(e) => setName(e.target.value)} placeholder="anvicorp.com" required />
            </FormField>
            <FormField label="Display name" htmlFor="ddn">
              <Input id="ddn" value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="Anvi Corp" />
            </FormField>
            <div className="flex justify-end gap-3">
              <button type="button" onClick={() => setCreateOpen(false)} className="rounded-full px-5 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50">
                Cancel
              </button>
              <button type="submit" disabled={creating} className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2 text-sm font-semibold text-white hover:bg-brand-600 disabled:opacity-60">
                {creating && <Spinner className="h-4 w-4 text-white" />}
                Create
              </button>
            </div>
          </form>
        </Modal>
      )}

      <ConfirmDialog
        open={toDelete !== null}
        title="Delete domain"
        message={`Delete ${toDelete?.name}? This only works if the domain has no mailboxes — otherwise deactivate it instead.`}
        confirmLabel="Delete"
        danger
        busy={busy}
        onConfirm={confirmDelete}
        onCancel={() => setToDelete(null)}
      />
    </div>
  );
}
