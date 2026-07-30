'use client';

import { useMemo, useState } from 'react';
import { X, ExternalLink, RotateCcw } from 'lucide-react';
import api from '@/lib/careers/api';
import {
  SKYZEN_DOCUMENTS,
  SKYZEN_DOCUMENT_CATEGORIES,
  CATEGORY_BADGE,
  SENSITIVITY_BADGE,
  SENSITIVITY_LABEL,
  type SkyzenDocumentKey,
  type SkyzenDocumentCategory,
  type SkyzenDocumentSpec,
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
 * ERM "assign additional / forgotten document" modal. Mirrors
 * AssignPacketModal but posts to {@code /document-packets/{id}/add-documents}
 * so ERM can add or re-send one/many docs without rebuilding the packet.
 *
 * <p>Each doc is annotated with its current state on the packet so the
 * ERM knows whether they're adding fresh work or re-poking an in-flight
 * task or re-opening one that was already accepted / waived.</p>
 */
export default function AssignAdditionalDocumentModal({
  open, packetId, internName, existingTasks, onClose, onAssigned,
}: Props) {
  const [selected, setSelected] = useState<Set<SkyzenDocumentKey>>(new Set());
  const [customInstructions, setCustomInstructions] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const statusByKey = useMemo(() => {
    const m = new Map<SkyzenDocumentKey, TaskStatus>();
    for (const t of existingTasks) {
      if (t.documentKey) m.set(t.documentKey, t.status);
    }
    return m;
  }, [existingTasks]);

  const grouped = useMemo(() => {
    const out = new Map<SkyzenDocumentCategory, SkyzenDocumentSpec[]>();
    for (const c of SKYZEN_DOCUMENT_CATEGORIES) out.set(c, []);
    for (const d of SKYZEN_DOCUMENTS) {
      if (d.deprecated) continue;
      out.get(d.category)!.push(d);
    }
    return out;
  }, []);

  function toggle(k: SkyzenDocumentKey) {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(k)) next.delete(k); else next.add(k);
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
              {internName ?? 'Intern'} · adds to the existing packet without rebuilding it.
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              A doc that&rsquo;s already on the packet is re-notified in place; a
              closed doc (accepted / waived) is reopened for the intern to
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

          {SKYZEN_DOCUMENT_CATEGORIES.map((cat) => {
            const items = grouped.get(cat) ?? [];
            if (items.length === 0) return null;
            return (
              <section key={cat} className="mb-4">
                <h4 className="mb-2 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                  <span className={`rounded-full px-2 py-0.5 ${CATEGORY_BADGE[cat]}`}>
                    {cat}
                  </span>
                </h4>
                <ul className="divide-y divide-slate-100 rounded-md border border-slate-200">
                  {items.map((d) => {
                    const on = selected.has(d.key);
                    const currentStatus = statusByKey.get(d.key);
                    return (
                      <li key={d.key} className="flex items-start gap-3 px-3 py-2">
                        <input
                          id={`add-doc-${d.key}`}
                          type="checkbox"
                          checked={on}
                          onChange={() => toggle(d.key)}
                          className="mt-1"
                        />
                        <label htmlFor={`add-doc-${d.key}`} className="flex-1 cursor-pointer">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-medium text-slate-900">
                              {d.title}
                            </span>
                            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${SENSITIVITY_BADGE[d.sensitivity]}`}>
                              {SENSITIVITY_LABEL[d.sensitivity]}
                            </span>
                            {currentStatus && (
                              <StateBadge status={currentStatus} />
                            )}
                          </div>
                          <p className="text-[11px] text-slate-500">{d.description}</p>
                        </label>
                        {d.publicUrl ? (
                          <a
                            href={d.publicUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 hover:underline"
                            title="Open the blank PDF in a new tab"
                          >
                            Preview <ExternalLink className="h-3 w-3" />
                          </a>
                        ) : (
                          <span className="text-[11px] text-slate-400">Upload only</span>
                        )}
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
