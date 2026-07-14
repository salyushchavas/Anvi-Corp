'use client';

import { useMemo, useState } from 'react';
import { X, ExternalLink } from 'lucide-react';
import api from '@/lib/api';
import {
  SKYZEN_DOCUMENTS,
  SKYZEN_DOCUMENT_CATEGORIES,
  CATEGORY_BADGE,
  SENSITIVITY_BADGE,
  SENSITIVITY_LABEL,
  type SkyzenDocumentKey,
  type SkyzenDocumentCategory,
  type SkyzenDocumentSpec,
} from '@/lib/skyzen-documents';

type Props = {
  open: boolean;
  lifecycleId: string;
  internName: string | null;
  employeeId?: string | null;
  tentativeStartDate?: string | null;
  onClose: () => void;
  onAssigned: () => void;
};

/**
 * ERM Phase 8.2 — assignment modal sourced entirely from the static
 * SKYZEN_DOCUMENTS constant (no API fetch). ERM ticks the docs they
 * want to send, optionally adds an instructions note, and submits.
 * Phase 8.6.4 dropped the client-side reporting-structure gate;
 * Manager is no longer required and Trainer/Evaluator auto-link from
 * system config at offer sign. Server still validates T+E and returns
 * REPORTING_STRUCTURE_INCOMPLETE if either is missing.
 */
export default function AssignPacketModal({
  open, lifecycleId, internName, employeeId, tentativeStartDate,
  onClose, onAssigned,
}: Props) {
  const [selected, setSelected] = useState<Set<SkyzenDocumentKey>>(new Set());
  const [customInstructions, setCustomInstructions] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const grouped = useMemo(() => {
    const out = new Map<SkyzenDocumentCategory, SkyzenDocumentSpec[]>();
    for (const c of SKYZEN_DOCUMENT_CATEGORIES) out.set(c, []);
    // Deprecated docs are kept in SKYZEN_DOCUMENTS so historical packets
    // still resolve their key → title/category, but they're hidden from
    // new packet assignment so ERM stops handing them out.
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
    // Phase 8.6.4 (revised) — document onboarding is ERM↔intern only;
    // Trainer/Evaluator have no role in this loop, so there is no
    // reporting-structure gate on assignment.
    if (selected.size === 0) {
      setErr('Pick at least one document.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post('/api/v1/erm/document-packets/assign', {
        internLifecycleId: lifecycleId,
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
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to assign');
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Assign documents
            </h3>
            <p className="text-xs text-slate-500">
              {internName ?? 'Intern'}
              {employeeId && <span> · {employeeId}</span>}
              {tentativeStartDate && <span> · starts {tentativeStartDate}</span>}
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              Fill-and-sign docs: the intern downloads the PDF, fills it by
              hand, scans all pages into a single PDF, and uploads. Upload-only
              docs (passport, license, education) skip the template — they
              scan/photo the existing document and upload directly.
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
                    return (
                      <li key={d.key} className="flex items-start gap-3 px-3 py-2">
                        <input
                          id={`doc-${d.key}`}
                          type="checkbox"
                          checked={on}
                          onChange={() => toggle(d.key)}
                          className="mt-1"
                        />
                        <label htmlFor={`doc-${d.key}`} className="flex-1 cursor-pointer">
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-medium text-slate-900">
                              {d.title}
                            </span>
                            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${SENSITIVITY_BADGE[d.sensitivity]}`}>
                              {SENSITIVITY_LABEL[d.sensitivity]}
                            </span>
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
                          // Upload-only doc — no fill-in template to preview.
                          <span className="text-[11px] text-slate-400">
                            Upload only
                          </span>
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
              Custom instructions to intern (optional)
            </span>
            <textarea
              value={customInstructions}
              onChange={(e) => setCustomInstructions(e.target.value)}
              rows={3}
              maxLength={5000}
              className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
              placeholder="Anything extra for this specific intern — e.g. extended deadline, special instructions."
            />
          </label>
        </div>

        <div className="flex items-center justify-between border-t border-slate-200 px-5 py-3">
          <span className="text-xs text-slate-500">
            {selected.size} document{selected.size === 1 ? '' : 's'} selected
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
              {submitting ? 'Sending…' : `Send to intern`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
