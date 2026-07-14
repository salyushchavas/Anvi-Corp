"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { FormField, Input, Modal, Spinner } from "../ui";
import { useToast } from "../toast";
import { SYS_FOLDER_LABEL } from "./rule-format";
import { rulesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import type {
  MailCustomFolder,
  MailFolder,
  MailRule,
  MailRuleAction,
  MailRuleActionType,
  MailRuleCondition,
  MailRuleField,
  MailRuleMatchMode,
  MailRuleOperator,
} from "@/lib/mail-types";

const FIELDS: { value: MailRuleField; label: string }[] = [
  { value: "FROM", label: "From" },
  { value: "TO", label: "To" },
  { value: "CC", label: "Cc" },
  { value: "SUBJECT", label: "Subject" },
  { value: "HAS_ATTACHMENT", label: "Has attachment" },
];

const TEXT_OPERATORS: { value: MailRuleOperator; label: string }[] = [
  { value: "CONTAINS", label: "contains" },
  { value: "EQUALS", label: "equals" },
];

const ACTION_TYPES: { value: MailRuleActionType; label: string }[] = [
  { value: "MOVE_TO_SYSTEM_FOLDER", label: "Move to folder" },
  { value: "MOVE_TO_CUSTOM_FOLDER", label: "Move to custom folder" },
  { value: "STAR", label: "Star" },
  { value: "MARK_READ", label: "Mark as read" },
  { value: "MARK_IMPORTANT", label: "Mark important" },
  { value: "DELETE", label: "Move to Trash" },
];

const SYSTEM_TARGETS: MailFolder[] = ["INBOX", "ARCHIVE", "TRASH"];

const selectClass =
  "block w-full rounded-xl border border-ink-100 bg-white px-3 py-2 text-sm text-ink-800 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20";

export function RuleBuilderModal({
  rule,
  folders,
  onClose,
  onSaved,
}: {
  rule: MailRule | null;
  folders: MailCustomFolder[];
  onClose: () => void;
  onSaved: (saved: MailRule) => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(rule?.name ?? "");
  const [matchMode, setMatchMode] = useState<MailRuleMatchMode>(rule?.matchMode ?? "ALL");
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [stopProcessing, setStopProcessing] = useState(rule?.stopProcessing ?? false);
  const [conditions, setConditions] = useState<MailRuleCondition[]>(
    rule?.conditions.length ? rule.conditions : [{ field: "FROM", operator: "CONTAINS", value: "" }],
  );
  const [actions, setActions] = useState<MailRuleAction[]>(
    rule?.actions.length
      ? rule.actions
      : [{ type: "MOVE_TO_SYSTEM_FOLDER", targetSystemFolder: "ARCHIVE", targetCustomFolderId: null }],
  );
  const [saving, setSaving] = useState(false);

  // ── condition editing ──
  function changeField(i: number, field: MailRuleField) {
    setConditions((cs) =>
      cs.map((c, idx) => {
        if (idx !== i) return c;
        if (field === "HAS_ATTACHMENT") return { field, operator: "IS_TRUE", value: null };
        return { field, operator: c.operator === "IS_TRUE" ? "CONTAINS" : c.operator, value: c.value ?? "" };
      }),
    );
  }
  function patchCondition(i: number, patch: Partial<MailRuleCondition>) {
    setConditions((cs) => cs.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  }
  function addCondition() {
    setConditions((cs) => [...cs, { field: "FROM", operator: "CONTAINS", value: "" }]);
  }
  function removeCondition(i: number) {
    setConditions((cs) => (cs.length <= 1 ? cs : cs.filter((_, idx) => idx !== i)));
  }

  // ── action editing ──
  function changeActionType(i: number, type: MailRuleActionType) {
    setActions((as) =>
      as.map((a, idx) => {
        if (idx !== i) return a;
        if (type === "MOVE_TO_SYSTEM_FOLDER")
          return { type, targetSystemFolder: a.targetSystemFolder ?? "ARCHIVE", targetCustomFolderId: null };
        if (type === "MOVE_TO_CUSTOM_FOLDER")
          return { type, targetSystemFolder: null, targetCustomFolderId: a.targetCustomFolderId ?? folders[0]?.id ?? null };
        return { type, targetSystemFolder: null, targetCustomFolderId: null };
      }),
    );
  }
  function patchAction(i: number, patch: Partial<MailRuleAction>) {
    setActions((as) => as.map((a, idx) => (idx === i ? { ...a, ...patch } : a)));
  }
  function addAction() {
    setActions((as) => [...as, { type: "STAR", targetSystemFolder: null, targetCustomFolderId: null }]);
  }
  function removeAction(i: number) {
    setActions((as) => (as.length <= 1 ? as : as.filter((_, idx) => idx !== i)));
  }

  function validate(): string | null {
    if (!name.trim()) return "Give the rule a name.";
    if (!conditions.length) return "Add at least one condition.";
    for (const c of conditions) {
      if (c.field !== "HAS_ATTACHMENT" && !(c.value ?? "").trim()) return "Every text condition needs a value.";
    }
    if (!actions.length) return "Add at least one action.";
    for (const a of actions) {
      if (a.type === "MOVE_TO_SYSTEM_FOLDER" && !a.targetSystemFolder) return "Pick a target folder.";
      if (a.type === "MOVE_TO_CUSTOM_FOLDER" && !a.targetCustomFolderId) return "Pick a custom folder to move into.";
    }
    return null;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const problem = validate();
    if (problem) {
      toast.error(problem);
      return;
    }
    setSaving(true);
    const input = {
      name: name.trim(),
      matchMode,
      enabled,
      stopProcessing,
      conditions: conditions.map((c) => ({
        field: c.field,
        operator: c.operator,
        value: c.field === "HAS_ATTACHMENT" ? null : (c.value ?? "").trim(),
      })),
      actions,
    };
    try {
      const saved = rule ? await rulesApi.update(rule.id, input) : await rulesApi.create(input);
      toast.success(rule ? "Rule updated." : "Rule created.");
      onSaved(saved);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not save the rule.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={rule ? "Edit rule" : "New rule"} width="max-w-2xl">
      <form onSubmit={submit} className="space-y-5">
        <FormField label="Rule name" htmlFor="rule-name" required>
          <Input id="rule-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus maxLength={150} placeholder="e.g. Invoices to Finance" />
        </FormField>

        {/* Conditions */}
        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-medium text-ink-800">When an incoming message matches</span>
            <select value={matchMode} onChange={(e) => setMatchMode(e.target.value as MailRuleMatchMode)} className="w-auto rounded-lg border border-ink-100 bg-white px-2 py-1 text-xs text-ink-700 focus:border-brand focus:outline-none">
              <option value="ALL">all conditions</option>
              <option value="ANY">any condition</option>
            </select>
          </div>
          <div className="space-y-2">
            {conditions.map((c, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <select value={c.field} onChange={(e) => changeField(i, e.target.value as MailRuleField)} className={`${selectClass} w-auto flex-shrink-0`}>
                  {FIELDS.map((f) => (
                    <option key={f.value} value={f.value}>
                      {f.label}
                    </option>
                  ))}
                </select>
                {c.field === "HAS_ATTACHMENT" ? (
                  <span className="rounded-lg bg-ink-50 px-3 py-2 text-sm text-ink-500">is true</span>
                ) : (
                  <>
                    <select value={c.operator} onChange={(e) => patchCondition(i, { operator: e.target.value as MailRuleOperator })} className={`${selectClass} w-auto flex-shrink-0`}>
                      {TEXT_OPERATORS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                    <input value={c.value ?? ""} onChange={(e) => patchCondition(i, { value: e.target.value })} placeholder="value" className={`${selectClass} min-w-0 flex-1`} />
                  </>
                )}
                <button type="button" onClick={() => removeCondition(i)} disabled={conditions.length <= 1} aria-label="Remove condition" className="rounded-lg p-2 text-ink-400 hover:bg-red-50 hover:text-red-600 disabled:opacity-30">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))}
          </div>
          <button type="button" onClick={addCondition} className="mt-2 inline-flex items-center gap-1 text-sm font-medium text-brand hover:text-brand-600">
            <Plus className="h-4 w-4" /> Add condition
          </button>
        </div>

        {/* Actions */}
        <div>
          <span className="mb-2 block text-sm font-medium text-ink-800">Do this</span>
          <div className="space-y-2">
            {actions.map((a, i) => (
              <div key={i} className="flex flex-wrap items-center gap-2">
                <select value={a.type} onChange={(e) => changeActionType(i, e.target.value as MailRuleActionType)} className={`${selectClass} w-auto flex-shrink-0`}>
                  {ACTION_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
                {a.type === "MOVE_TO_SYSTEM_FOLDER" && (
                  <select value={a.targetSystemFolder ?? "ARCHIVE"} onChange={(e) => patchAction(i, { targetSystemFolder: e.target.value as MailFolder })} className={`${selectClass} w-auto flex-shrink-0`}>
                    {SYSTEM_TARGETS.map((f) => (
                      <option key={f} value={f}>
                        {SYS_FOLDER_LABEL[f]}
                      </option>
                    ))}
                  </select>
                )}
                {a.type === "MOVE_TO_CUSTOM_FOLDER" &&
                  (folders.length ? (
                    <select value={a.targetCustomFolderId ?? ""} onChange={(e) => patchAction(i, { targetCustomFolderId: e.target.value })} className={`${selectClass} w-auto flex-shrink-0`}>
                      <option value="" disabled>
                        Pick a folder…
                      </option>
                      {folders.map((f) => (
                        <option key={f.id} value={f.id}>
                          {f.name}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">Create a custom folder in Mail first.</span>
                  ))}
                <button type="button" onClick={() => removeAction(i)} disabled={actions.length <= 1} aria-label="Remove action" className="rounded-lg p-2 text-ink-400 hover:bg-red-50 hover:text-red-600 disabled:opacity-30">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))}
          </div>
          <button type="button" onClick={addAction} className="mt-2 inline-flex items-center gap-1 text-sm font-medium text-brand hover:text-brand-600">
            <Plus className="h-4 w-4" /> Add action
          </button>
        </div>

        {/* Options */}
        <div className="flex flex-wrap gap-x-6 gap-y-2 border-t border-ink-100 pt-4">
          <label className="inline-flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} className="h-4 w-4 rounded border-ink-200 text-brand focus:ring-brand/30" />
            Enabled
          </label>
          <label className="inline-flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" checked={stopProcessing} onChange={(e) => setStopProcessing(e.target.checked)} className="h-4 w-4 rounded border-ink-200 text-brand focus:ring-brand/30" />
            Stop processing more rules
          </label>
        </div>

        <div className="flex justify-end gap-3">
          <button type="button" onClick={onClose} className="rounded-full px-5 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50">
            Cancel
          </button>
          <button type="submit" disabled={saving} className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2 text-sm font-semibold text-white hover:bg-brand-600 disabled:opacity-60">
            {saving && <Spinner className="h-4 w-4 text-white" />}
            {rule ? "Save changes" : "Create rule"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
