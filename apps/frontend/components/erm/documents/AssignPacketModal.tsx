'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { X, ExternalLink, AlertCircle } from 'lucide-react';
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
 * ERM Phase 8.2 → 8.7 — assignment modal now sources its list from
 * {@code /api/v1/erm/onboarding-templates/pickable} which returns every
 * active row from {@code onboarding_document_templates}: the enum-seeded
 * rows AND any admin-added templates. Previously enumerated the frontend
 * SKYZEN_DOCUMENTS constant, so admin-added templates never appeared.
 *
 * <p>Row shape mirrors the enum spec closely so the render is unchanged.
 * Admin-added rows whose key isn't in the enum are surfaced with an
 * "admin-added" badge and shown disabled — the downstream assignPacket
 * endpoint still deserializes into the SkyzenDocument enum, so custom
 * keys can't be submitted yet. Full end-to-end assignability of custom
 * keys is a follow-up (requires changing DocumentTask.documentKey from
 * enum → String).</p>
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

export default function AssignPacketModal({
  open, lifecycleId, internName, employeeId, tentativeStartDate,
  onClose, onAssigned,
}: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [customInstructions, setCustomInstructions] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [rows, setRows] = useState<PickableTemplate[] | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);

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

  const grouped = useMemo(() => {
    const out = new Map<string, PickableTemplate[]>();
    for (const r of rows ?? []) {
      const list = out.get(r.category) ?? [];
      list.push(r);
      out.set(r.category, list);
    }
    return out;
  }, [rows]);

  const categories = useMemo(() => Array.from(grouped.keys()).sort(), [grouped]);

  function toggle(k: string, disabled: boolean) {
    if (disabled) return;
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });
  }

  async function submit() {
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
          {loadErr && (
            <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {loadErr}
            </p>
          )}
          {rows === null && !loadErr && (
            <div className="h-32 animate-pulse rounded-md bg-slate-100" />
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
                    // Enum spec — undefined for admin-added rows that
                    // aren't in the SkyzenDocument enum. Those rows show
                    // but are non-assignable until the entity refactor.
                    const enumSpec = SKYZEN_DOCUMENT_BY_KEY[d.key as SkyzenDocumentKey];
                    const enumAssignable = !!enumSpec;
                    const on = selected.has(d.key);
                    const sensBadge = SENSITIVITY_BADGE[d.sensitivity as SkyzenDocumentSensitivity]
                      ?? 'bg-slate-100 text-slate-700';
                    const sensLabel = SENSITIVITY_LABEL[d.sensitivity as SkyzenDocumentSensitivity]
                      ?? d.sensitivity;
                    return (
                      <li key={d.key} className="flex items-start gap-3 px-3 py-2">
                        <input
                          id={`doc-${d.key}`}
                          type="checkbox"
                          checked={on}
                          onChange={() => toggle(d.key, !enumAssignable)}
                          disabled={!enumAssignable}
                          className="mt-1 disabled:opacity-40"
                        />
                        <label htmlFor={`doc-${d.key}`}
                          className={
                            'flex-1 '
                            + (enumAssignable ? 'cursor-pointer' : 'cursor-not-allowed opacity-70')
                          }>
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-medium text-slate-900">
                              {d.title}
                            </span>
                            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${sensBadge}`}>
                              {sensLabel}
                            </span>
                            {d.hasCustomFile && (
                              <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-800">
                                admin file
                              </span>
                            )}
                            {!enumAssignable && (
                              <span className="inline-flex items-center gap-0.5 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-900"
                                title="Admin-added templates aren't assignable yet — entity refactor pending. Enum-seeded templates work as before.">
                                <AlertCircle className="h-2.5 w-2.5" /> admin-added — assignment pending
                              </span>
                            )}
                          </div>
                          {d.description && (
                            <p className="text-[11px] text-slate-500">{d.description}</p>
                          )}
                        </label>
                        <PreviewLink templateKey={d.key} enumSpec={enumSpec} />
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
              className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {submitting ? 'Assigning…' : 'Assign'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function PreviewLink({ templateKey, enumSpec }: {
  templateKey: string;
  enumSpec: { publicUrl: string | null } | undefined;
}) {
  const [busy, setBusy] = useState(false);
  const staticFallback = enumSpec?.publicUrl ?? null;

  async function open() {
    if (busy) return;
    setBusy(true);
    try {
      const res = await api.get<{ downloadUrl: string | null }>(
        `/api/v1/onboarding-templates/${encodeURIComponent(templateKey)}/download-url`);
      const url = res.data?.downloadUrl ?? staticFallback;
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
    } catch {
      if (staticFallback) window.open(staticFallback, '_blank', 'noopener,noreferrer');
    } finally {
      setBusy(false);
    }
  }

  if (!enumSpec && !staticFallback) {
    return <span className="text-[11px] text-slate-400">Upload only</span>;
  }
  return (
    <button
      type="button"
      onClick={open}
      disabled={busy}
      className="inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 hover:underline disabled:opacity-60"
      title="Open the current blank PDF in a new tab (admin file if uploaded, else the seeded default)"
    >
      {busy ? 'Opening…' : 'Preview'} <ExternalLink className="h-3 w-3" />
    </button>
  );
}
