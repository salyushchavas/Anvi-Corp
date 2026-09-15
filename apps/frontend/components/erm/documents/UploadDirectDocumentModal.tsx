'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { X, ExternalLink, Upload, Search } from 'lucide-react';
import api from '@/lib/careers/api';
import {
  CATEGORY_BADGE,
  SENSITIVITY_BADGE,
  SENSITIVITY_LABEL,
  type OnboardingDocumentCategory,
  type OnboardingDocumentSensitivity,
} from '@/lib/careers/onboarding-documents';
import type { TaskSummary } from './types';

/**
 * ERM "upload a missed document directly" — the direct-onboarded-hire-only
 * companion to {@link ../AssignAdditionalDocumentModal}. Instead of sending
 * an assignment to the intern to fill + upload, the ERM picks a finished
 * file and it's attached to the intern's existing packet as an ACCEPTED
 * task in one shot. No intern step, no email nudge.
 *
 * <h3>Why this modal is single-select (unlike Assign)</h3>
 * The direct-upload endpoint accepts ONE (documentKey, file) pair per
 * call — the ERM has to browse to a specific file per doc, and batching
 * would need one file input per selected doc. Simpler + clearer audit:
 * the ERM picks ONE template row, attaches ONE file, submits, then can
 * do another. The backend audit row is per-task-created which matches
 * that single-shot mental model.
 *
 * <h3>Filtering</h3>
 * The template list filters out doc_keys already on the packet — the
 * backend rejects duplicates with 409 anyway, so we don't offer them.
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

type Props = {
  open: boolean;
  packetId: string;
  internName: string | null;
  existingTasks: TaskSummary[];
  onClose: () => void;
  onUploaded: () => void;
};

export default function UploadDirectDocumentModal({
  open, packetId, internName, existingTasks, onClose, onUploaded,
}: Props) {
  const [rows, setRows] = useState<PickableTemplate[] | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

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

  // Reset transient state when the modal opens fresh (avoid seeing
  // stale selection / error from a previous open).
  useEffect(() => {
    if (open) {
      setSelectedKey(null);
      setFile(null);
      setErr(null);
      setFilter('');
    }
  }, [open]);

  // Doc keys already on the packet — the direct-upload endpoint
  // rejects duplicates with 409, so we exclude them from the picker
  // entirely (no point offering a row that will hard-fail on submit).
  const takenKeys = useMemo(() => {
    const s = new Set<string>();
    for (const t of existingTasks) {
      if (t.documentKey) s.add(t.documentKey);
    }
    return s;
  }, [existingTasks]);

  const available = useMemo(() => {
    return (rows ?? []).filter((r) => !takenKeys.has(r.key));
  }, [rows, takenKeys]);

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return available;
    return available.filter((r) => {
      const t = r.title?.toLowerCase() ?? '';
      const d = r.description?.toLowerCase() ?? '';
      return t.includes(needle) || d.includes(needle);
    });
  }, [available, filter]);

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

  async function submit() {
    if (!selectedKey) {
      setErr('Pick a document type first.');
      return;
    }
    if (!file) {
      setErr('Choose the finished file to upload.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      const fd = new FormData();
      fd.append('documentKey', selectedKey);
      fd.append('file', file);
      await api.post(
        `/api/v1/erm/document-packets/${packetId}/direct-upload-document`,
        fd,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      );
      onUploaded();
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
        message?: string;
      };
      // 403 = tos_version guard (frontend routed a regular hire here
      // somehow — should never happen given the branch on
      // internDirectOnboarded, but defense-in-depth).
      // 409 = duplicate documentKey OR missing packet (backend
      // messages differ — surface the server message either way).
      const msg = ax.response?.data?.error
        ?? ax.message
        ?? 'Upload failed';
      setErr(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  const selectedRow = selectedKey
    ? (available.find((r) => r.key === selectedKey) ?? null)
    : null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Upload document directly
            </h3>
            <p className="text-xs text-slate-500">
              {internName ?? 'Intern'}
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              Direct-onboarded intern — attach the finished file yourself.
              The document lands as <strong>Accepted</strong> on their
              packet immediately. No intern step; no email nudge.
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

          {rows !== null && available.length === 0 && (
            <p className="mb-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
              Every template on file is already attached to this packet —
              nothing left to upload directly.
            </p>
          )}

          {rows !== null && available.length > 0 && (
            <div className="mb-3">
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
            </div>
          )}

          {rows !== null && filtered.length === 0 && available.length > 0 && (
            <p className="mb-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
              No templates match {`"${filter.trim()}"`}. Adjust the filter to see more.
            </p>
          )}

          {rows !== null && categories.map((cat) => {
            const items = grouped.get(cat) ?? [];
            if (items.length === 0) return null;
            const badgeCls = CATEGORY_BADGE[cat as OnboardingDocumentCategory]
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
                    const on = selectedKey === d.key;
                    const sensBadge = SENSITIVITY_BADGE[d.sensitivity as OnboardingDocumentSensitivity]
                      ?? 'bg-slate-100 text-slate-700';
                    const sensLabel = SENSITIVITY_LABEL[d.sensitivity as OnboardingDocumentSensitivity]
                      ?? d.sensitivity;
                    return (
                      <li key={d.key} className="flex items-start gap-3 px-3 py-2">
                        <input
                          id={`direct-doc-${d.key}`}
                          type="radio"
                          name="direct-doc-choice"
                          checked={on}
                          onChange={() => setSelectedKey(d.key)}
                          className="mt-1"
                        />
                        <label htmlFor={`direct-doc-${d.key}`}
                          className="flex-1 cursor-pointer">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-medium text-slate-900">
                              {d.title}
                            </span>
                            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${sensBadge}`}>
                              {sensLabel}
                            </span>
                          </div>
                          {d.description && (
                            <p className="text-[11px] text-slate-500">{d.description}</p>
                          )}
                        </label>
                        <TemplatePreviewLink templateKey={d.key} documentType={d.documentType} />
                      </li>
                    );
                  })}
                </ul>
              </section>
            );
          })}

          {selectedRow && (
            <div className="mt-4 rounded-md border border-slate-200 bg-slate-50 p-3">
              <p className="text-xs font-semibold text-slate-700">
                Finished file for{' '}
                <span className="text-slate-900">{selectedRow.title}</span>
              </p>
              <p className="mt-0.5 text-[11px] text-slate-500">
                PDF preferred. This file will land on the intern&apos;s
                Documents page immediately, marked Accepted, with you as
                the uploader.
              </p>
              <input
                type="file"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                className="mt-2 block w-full text-xs text-slate-700"
              />
              {file && (
                <p className="mt-1 text-[11px] text-slate-500">
                  Selected: <span className="font-medium text-slate-800">{file.name}</span>
                  {' '}({Math.round(file.size / 1024)} KB)
                </p>
              )}
            </div>
          )}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 px-5 py-3">
          <span className="text-xs text-slate-500">
            {selectedKey && file
              ? 'Ready to attach'
              : selectedKey
                ? 'Choose the finished file'
                : 'Pick a document type to continue'}
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
              disabled={submitting || !selectedKey || !file}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
            >
              <Upload className="h-3.5 w-3.5" />
              {submitting ? 'Attaching…' : 'Attach & mark accepted'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Preview button for the blank template — mirrors the AssignAdditional
 *  modal's PreviewLink so the ERM can double-check they're picking the
 *  right template before uploading a finished copy. */
function TemplatePreviewLink({ templateKey, documentType }: {
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
