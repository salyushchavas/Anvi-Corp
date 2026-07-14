"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ChevronDown, ChevronUp, ListFilter, Pencil, Plus, Trash2 } from "lucide-react";
import { useMailAuth } from "../mail-auth-provider";
import { ConfirmDialog, FullScreenSpinner } from "../ui";
import { useToast } from "../toast";
import { RuleBuilderModal } from "./rule-builder-modal";
import { describeConditions, describeAction } from "./rule-format";
import { foldersApi, rulesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import type { MailCustomFolder, MailRule } from "@/lib/mail-types";

export function RulesScreen() {
  const { account } = useMailAuth();
  const toast = useToast();

  const [rules, setRules] = useState<MailRule[] | null>(null);
  const [folders, setFolders] = useState<MailCustomFolder[]>([]);
  const [editing, setEditing] = useState<MailRule | "new" | null>(null);
  const [toDelete, setToDelete] = useState<MailRule | null>(null);
  const [busy, setBusy] = useState(false);
  const [reordering, setReordering] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [r, f] = await Promise.all([rulesApi.list(), foldersApi.list()]);
        if (!cancelled) {
          setRules(r);
          setFolders(f);
        }
      } catch (err) {
        if (!cancelled) {
          toast.error(err instanceof MailApiError ? err.message : "Could not load your rules.");
          setRules([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // Load once on mount. `toast` is used only for error display; keeping it out of the deps
    // avoids a retry storm if the load persistently fails (a shown toast re-renders the
    // provider → new toast identity → effect re-run → reload → …).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function toggle(rule: MailRule) {
    try {
      const updated = await rulesApi.setEnabled(rule.id, !rule.enabled);
      setRules((prev) => prev?.map((r) => (r.id === updated.id ? updated : r)) ?? prev);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not update the rule.");
    }
  }

  async function move(index: number, dir: -1 | 1) {
    if (!rules) return;
    const target = index + dir;
    if (target < 0 || target >= rules.length) return;
    const next = [...rules];
    [next[index], next[target]] = [next[target], next[index]];
    setRules(next); // optimistic
    setReordering(true);
    try {
      const saved = await rulesApi.reorder(next.map((r) => r.id));
      setRules(saved);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not reorder.");
      // Reload authoritative order on failure.
      try {
        setRules(await rulesApi.list());
      } catch {
        /* keep optimistic */
      }
    } finally {
      setReordering(false);
    }
  }

  async function confirmDelete() {
    if (!toDelete) return;
    setBusy(true);
    try {
      await rulesApi.remove(toDelete.id);
      toast.success("Rule deleted.");
      setRules((prev) => prev?.filter((r) => r.id !== toDelete.id) ?? prev);
      setToDelete(null);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not delete the rule.");
      setToDelete(null);
    } finally {
      setBusy(false);
    }
  }

  function onSaved(saved: MailRule) {
    setRules((prev) => {
      if (!prev) return [saved];
      const exists = prev.some((r) => r.id === saved.id);
      return exists ? prev.map((r) => (r.id === saved.id ? saved : r)) : [...prev, saved];
    });
    setEditing(null);
  }

  if (!account || rules === null) return <FullScreenSpinner />;

  return (
    <div className="min-h-screen bg-ink-50">
      <header className="flex h-14 items-center gap-3 border-b border-ink-100 bg-white px-4">
        <Link href="/mail" className="inline-flex flex-shrink-0 items-center gap-1.5 text-sm font-medium text-ink-600 hover:text-brand">
          <ArrowLeft className="h-4 w-4" /> Mail
        </Link>
        <span className="mx-1 h-5 w-px flex-shrink-0 bg-ink-100" />
        <span className="flex-shrink-0 font-bold text-ink-800">Settings</span>
        <span className="ml-auto min-w-0 truncate text-sm text-ink-400">{account.email}</span>
      </header>

      <div className="mx-auto max-w-3xl px-4 py-6">
        <div className="mb-5 flex items-start justify-between gap-3">
          <div>
            <h1 className="text-lg font-bold text-ink-800">Inbox rules</h1>
            <p className="mt-1 text-sm text-ink-500">
              Rules run on incoming mail, top to bottom. The first matching rule that stops processing wins.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setEditing("new")}
            className="inline-flex flex-shrink-0 items-center gap-2 rounded-full bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-600"
          >
            <Plus className="h-4 w-4" /> New rule
          </button>
        </div>

        {rules.length === 0 ? (
          <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-ink-200 bg-white py-14 text-center">
            <ListFilter className="h-10 w-10 text-ink-300" />
            <p className="text-sm text-ink-500">No rules yet. Create one to auto-file or flag incoming mail.</p>
          </div>
        ) : (
          <ul className="space-y-3">
            {rules.map((rule, i) => (
              <li key={rule.id} className="rounded-2xl border border-ink-100 bg-white p-4 shadow-card">
                <div className="flex items-start gap-3">
                  <div className="flex flex-shrink-0 flex-col">
                    <button
                      type="button"
                      onClick={() => move(i, -1)}
                      disabled={i === 0 || reordering}
                      aria-label="Move up"
                      className="rounded p-0.5 text-ink-400 hover:bg-ink-50 hover:text-brand disabled:opacity-30"
                    >
                      <ChevronUp className="h-4 w-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => move(i, 1)}
                      disabled={i === rules.length - 1 || reordering}
                      aria-label="Move down"
                      className="rounded p-0.5 text-ink-400 hover:bg-ink-50 hover:text-brand disabled:opacity-30"
                    >
                      <ChevronDown className="h-4 w-4" />
                    </button>
                  </div>

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-semibold text-ink-800">{rule.name}</span>
                      {!rule.enabled && (
                        <span className="flex-shrink-0 rounded-full bg-ink-100 px-2 py-0.5 text-xs font-medium text-ink-500">Off</span>
                      )}
                    </div>
                    <p className="mt-1 text-sm text-ink-500">
                      <span className="text-ink-400">If {rule.matchMode === "ALL" ? "all" : "any"} of: </span>
                      {describeConditions(rule.conditions)}
                    </p>
                    <p className="mt-0.5 text-sm text-ink-500">
                      <span className="text-ink-400">Then: </span>
                      {rule.actions.map((a) => describeAction(a, folders)).join(", ") || "—"}
                      {rule.stopProcessing && <span className="text-ink-400"> · stop</span>}
                    </p>
                  </div>

                  <div className="flex flex-shrink-0 items-center gap-1">
                    <Switch on={rule.enabled} onToggle={() => toggle(rule)} label={`Toggle ${rule.name}`} />
                    <button
                      type="button"
                      onClick={() => setEditing(rule)}
                      aria-label={`Edit ${rule.name}`}
                      className="rounded-lg p-2 text-ink-400 hover:bg-ink-50 hover:text-brand"
                    >
                      <Pencil className="h-4 w-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setToDelete(rule)}
                      aria-label={`Delete ${rule.name}`}
                      className="rounded-lg p-2 text-ink-400 hover:bg-red-50 hover:text-red-600"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {editing && (
        <RuleBuilderModal
          rule={editing === "new" ? null : editing}
          folders={folders}
          onClose={() => setEditing(null)}
          onSaved={onSaved}
        />
      )}

      <ConfirmDialog
        open={toDelete !== null}
        title="Delete rule"
        message={`Delete "${toDelete?.name}"? Incoming mail will no longer be processed by this rule.`}
        confirmLabel="Delete rule"
        danger
        busy={busy}
        onConfirm={confirmDelete}
        onCancel={() => setToDelete(null)}
      />
    </div>
  );
}

function Switch({ on, onToggle, label }: { on: boolean; onToggle: () => void; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      onClick={onToggle}
      className={`relative inline-flex h-5 w-9 flex-shrink-0 items-center rounded-full transition ${on ? "bg-brand" : "bg-ink-200"}`}
    >
      <span className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition ${on ? "translate-x-4" : "translate-x-0.5"}`} />
    </button>
  );
}
