'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { X, ExternalLink, RotateCcw, Search } from 'lucide-react';
import api from '@/lib/careers/api';
import {
  SKYZEN_DOCUMENT_BY_KEY,
  CATEGORY_BADGE,
  SENSITIVITY_BADGE,
  SENSITIVITY_LABEL,
  type SkyzenDocumentKey,
  type SkyzenDocumentCategory,
  type SkyzenDocumentSensitivity,
} from '@/lib/careers/skyzen-documents';
import type { TaskStatus, TaskSummary } from './types';

type Props = {
  open: boolean;
  packetId: string;
  internName: string | null;
  existingTasks: TaskSummary[];
  onClose: () => void;
  onAssigned: () => void;
};

/**
 * ERM "assign additional / forgotten document" modal. Same list source
 * as AssignPacketModal: {@code /api/v1/erm/onboarding-templates/pickable}
 * so admin-added templates surface here too. Post-widening of
 * DocumentTask.documentKey (enum → String) both enum-seeded AND
 * admin-added custom rows are selectable + assignable end-to-end.
 */
interface PickableTemplate {
  key: string;
  title: string;
  category: string;
  sensitivity: string;
  description: string | null;
  documentType: 'TEMPLATE' | 'NORMAL' | string;
  hasCustomFile: boolean;
}

export default function AssignAdditionalDocumentModal({
  open, packetId, internName, existingTasks, onClose, onAssigned,
}: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [customInstructions, setCustomInstructions] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [rows, setRows] = useState<PickableTemplate[] | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  // Case-insensitive substring filter across title + description. Select-
  // all scopes to exactly the filtered visible rows so ERM can narrow to
  // e.g. "passport" and hit Select all without silently grabbing every
  // hidden row.
  const [filter, setFilter] = useState('');

  const load = useCallback(async () => {
    setLoadErr(null);
    try {
      const res = await api.get<{ items: PickableTemplate[] }>(
        '/api/v1/erm/onboarding-templates/pickable');
      setRows(res.data.items ?? []);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setLoadErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load templates');
      setRows([]);
    }
  }, []);

  useEffect(() => {
    if (open && rows === null) void load();
  }, [open, rows, load]);

  const statusByKey = useMemo(() => {
    const m = new Map<string, TaskStatus>();
    for (const t of existingTasks) {
      if (t.documentKey) m.set(t.documentKey, t.status);
    }
    return m;
  }, [existingTasks]);

  // Apply the substring filter BEFORE grouping so both the categories
  // list and the select-all helpers see only what the user sees.
  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return rows ?? [];
    return (rows ?? []).filter((r) => {
      const t = r.title?.toLowerCase() ?? '';
      const d = r.description?.toLowerCase() ?? '';
      return t.includes(needle) || d.includes(needle);
    });
  }, [rows, filter]);

  const grouped = useMemo(() => {
    const out = new Map<string, PickableTemplate[]>();
    for (const r of filtered) {
      const list = out.get(r.category) ?? [];
      list.push(r);
      out.set(r.category, list);
    }
    return out;
  }, [filtered]);

  const categories = useMemo(() => Array.from(grouped.keys()).sort(), [grouped]);

  function toggle(k: string) {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });
  }

  // Select-all / deselect-all scoped to filtered rows.
  const visibleKeys = useMemo(() => filtered.map((r) => r.key), [filtered]);
  const allVisibleSelected =
    visibleKeys.length > 0 && visibleKeys.every((k) => selected.has(k));
  function selectAllVisible() {
    setSelected((cur) => {
      const next = new Set(cur);
      for (const k of visibleKeys) next.add(k);
      return next;
    });
  }
  function deselectAllVisible() {
    setSelected((cur) => {
      const next = new Set(cur);
      for (const k of visibleKeys) next.delete(k);
      return next;
    });
  }

  async function submit() {
    if (selected.size === 0) {
      setErr('Pick at least one document to send.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/api/v1/erm/document-packets/${packetId}/add-documents`, {
        selectedDocumentKeys: Array.from(selected),
        customInstructions: customInstructions.trim() || null,
        perDocumentInstructions: null,
      });
      onAssigned();
    } catch (e) {
      const ax = e as {
        response?: { data?: { error?: string } };
        message?: string;
      };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to send');
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  const newCount = Array.from(selected).filter((k) => !statusByKey.has(k)).length;
  const reopenCount = Array.from(selected).filter((k) => {
    const s = statusByKey.get(k);
    return s === 'ACCEPTED' || s === 'WAIVED';
  }).length;
  const renotifyCount = selected.size - newCount - reopenCount;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Send additional document
            </h3>
            <p className="text-xs text-slate-500">
              {internName ?? 'Intern'}
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              Adds to the existing packet. Docs already on the packet are
              re-notified; closed docs (accepted / waived) are reopened for
              re-upload.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-full p-1 text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {err && (
            <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
          {loadErr && (
            <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {loadErr}
            </p>
          )}
          {rows === null && !loadErr && (
            <div className="h-32 animate-pulse rounded-md bg-slate-100" />
          )}

          {rows !== null && rows.length > 0 && (
            <div className="mb-3 space-y-2">
              <div className="relative">
                <Search className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
                <input
                  type="text"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  placeholder="Filter by title or description"
                  className="w-full rounded-md border border-slate-200 pl-7 pr-3 py-1.5 text-sm"
                />
              </div>
              <div className="flex items-center justify-between rounded-md border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs">
                <span className="text-slate-600">
                  {filter.trim() ? (
                    <>
                      <strong className="text-slate-900">{filtered.length}</strong> of{' '}
                      <strong className="text-slate-900">{rows.length}</strong> shown
                      {' · '}
                      <strong className="text-slate-900">{selected.size}</strong> selected
                    </>
                  ) : (
                    <>
                      <strong className="text-slate-900">{selected.size}</strong> of{' '}
                      <strong className="text-slate-900">{rows.length}</strong> selected
                    </>
                  )}
                </span>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={selectAllVisible}
                    disabled={visibleKeys.length === 0 || allVisibleSelected}
                    className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-50"
                    title={filter.trim() ? 'Selects only the filtered rows' : 'Selects every row'}
                  >
                    Select all{filter.trim() ? ' shown' : ''}
                  </button>
                  <button
                    type="button"
                    onClick={deselectAllVisible}
                    disabled={selected.size === 0}
                    className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-50"
                  >
                    Deselect all{filter.trim() ? ' shown' : ''}
                  </button>
                </div>
              </div>
            </div>
          )}

          {rows !== null && filtered.length === 0 && rows.length > 0 && (
            <p className="mb-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
              No templates match {`"${filter.trim()}"`}. Adjust the filter to see more.
            </p>
          )}

          {rows !== null && categories.map((cat) => {
            const items = grouped.get(cat) ?? [];
            if (items.length === 0) return null;
            const badgeCls = CATEGORY_BADGE[cat as SkyzenDocumentCategory]
              ?? 'bg-slate-100 text-slate-700';
            return (
              <section key={cat} className="mb-4">
                <h4 className="mb-2 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                  <span className={`rounded-full px-2 py-0.5 ${badgeCls}`}>
                    {cat}
                  </span>
                </h4>
                <ul className="divide-y divide-slate-100 rounded-md border border-slate-200">
                  {items.map((d) => {
                    // Post-widening — every DB template row (enum-seeded
                    // AND admin-added custom) is selectable + assignable
                    // end-to-end.
                    const isCustom = !SKYZEN_DOCUMENT_BY_KEY[d.key as SkyzenDocumentKey];
                    const on = selected.has(d.key);
                    const currentStatus = statusByKey.get(d.key);
                    const sensBadge = SENSITIVITY_BADGE[d.sensitivity as SkyzenDocumentSensitivity]
                      ?? 'bg-slate-100 text-slate-700';
                    const sensLabel = SENSITIVITY_LABEL[d.sensitivity as SkyzenDocumentSensitivity]
                      ?? d.sensitivity;
                    return (
                      <li key={d.key} className="flex items-start gap-3 px-3 py-2">
                        <input
                          id={`add-doc-${d.key}`}
                          type="checkbox"
                          checked={on}
                          onChange={() => toggle(d.key)}
                          className="mt-1"
                        />
                        <label htmlFor={`add-doc-${d.key}`}
                          className="flex-1 cursor-pointer">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-medium text-slate-900">
                              {d.title}
                            </span>
                            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${sensBadge}`}>
                              {sensLabel}
                            </span>
                            {currentStatus && <StateBadge status={currentStatus} />}
                            {isCustom && (
                              <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700"
                                title="Admin-added template — key is stored as-is and DB-validated on assign.">
                                admin-added
                              </span>
                            )}
                          </div>
                          {d.description && (
                            <p className="text-[11px] text-slate-500">{d.description}</p>
                          )}
                        </label>
                        <PreviewLink templateKey={d.key} documentType={d.documentType} />
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}

          <label className="mt-2 block">
            <span className="text-xs font-semibold text-slate-700">
              Note appended to the packet instructions (optional)
            </span>
            <textarea
              value={customInstructions}
              onChange={(e) => setCustomInstructions(e.target.value)}
              rows={3}
              maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              placeholder="Why this doc is being sent now — e.g. missed at onboarding, needed for visa filing."
            />
          </label>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 px-5 py-3">
          <span className="text-xs text-slate-500">
            {selected.size} selected
            {selected.size > 0 && (
              <>
                {' '}·{' '}
                {newCount > 0 && <span>{newCount} new</span>}
                {newCount > 0 && (reopenCount > 0 || renotifyCount > 0) && ', '}
                {reopenCount > 0 && (
                  <span className="inline-flex items-center gap-1 text-amber-700">
                    <RotateCcw className="h-3 w-3" /> {reopenCount} reopen
                  </span>
                )}
                {reopenCount > 0 && renotifyCount > 0 && ', '}
                {renotifyCount > 0 && <span>{renotifyCount} re-notify</span>}
              </>
            )}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={submitting || selected.size === 0}
              className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
            >
              {submitting ? 'Sending…' : 'Send to intern'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Preview button — always hits the resolver on click (no baked-in
 * static fallback), so ERM sees whatever the template's
 * currentDocumentId points at RIGHT NOW. Mirrors the admin preview +
 * intern download button — same round-trip, same freshness guarantee.
 */
function PreviewLink({ templateKey, documentType }: {
  templateKey: string;
  documentType: string;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function open() {
    if (busy) return;
    setBusy(true);
    setErr(null);
    try {
      const res = await api.get<{ downloadUrl: string | null }>(
        `/api/v1/onboarding-templates/${encodeURIComponent(templateKey)}/download-url`);
      const url = res.data?.downloadUrl;
      if (!url) throw new Error('No template file available');
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Preview failed');
    } finally {
      setBusy(false);
    }
  }

  if (documentType !== 'TEMPLATE') {
    return <span className="text-[11px] text-slate-400">Upload only</span>;
  }
  return (
    <div className="flex flex-col items-end">
      <button
        type="button"
        onClick={open}
        disabled={busy}
        className="inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 hover:underline disabled:opacity-60"
        title="Open the current blank PDF in a new tab — resolves fresh so admin replacements appear immediately"
      >
        {busy ? 'Opening…' : 'Preview'} <ExternalLink className="h-3 w-3" />
      </button>
      {err && (
        <p className="mt-1 max-w-[180px] truncate text-[10px] text-red-600" title={err}>
          {err}
        </p>
      )}
    </div>
  );
}

function StateBadge({ status }: { status: TaskStatus }) {
  const styles: Record<TaskStatus, string> = {
    PENDING: 'bg-slate-100 text-slate-700',
    SUBMITTED: 'bg-slate-100 text-slate-700',
    UNDER_REVIEW: 'bg-amber-100 text-amber-800',
    ACCEPTED: 'bg-green-100 text-green-800',
    REJECTED: 'bg-red-100 text-red-800',
    RESEND_REQUESTED: 'bg-amber-100 text-amber-800',
    WAIVED: 'bg-slate-200 text-slate-700',
  };
  const label: Record<TaskStatus, string> = {
    PENDING: 'already assigned',
    SUBMITTED: 'awaiting review',
    UNDER_REVIEW: 'under review',
    ACCEPTED: 'accepted — will reopen',
    REJECTED: 'rejected',
    RESEND_REQUESTED: 'resend requested',
    WAIVED: 'waived — will reopen',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${styles[status]}`}>
      {label[status]}
    </span>
  );
}
