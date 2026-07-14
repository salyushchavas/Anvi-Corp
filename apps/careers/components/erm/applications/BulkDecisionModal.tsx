'use client';

import { useEffect, useState } from 'react';
import api from '@/lib/api';
import type { DecisionKind, ReasonCodeGroup, ReasonCodeOption } from './types';

interface Props {
  selectedIds: string[];
  decision: DecisionKind;
  open: boolean;
  onClose: () => void;
  onApplied: (
    result: { succeeded: string[]; failed: { applicationId: string; reason: string }[] },
  ) => void;
}

export default function BulkDecisionModal({
  selectedIds,
  decision,
  open,
  onClose,
  onApplied,
}: Props) {
  const [groups, setGroups] = useState<ReasonCodeGroup[]>([]);
  const [reasonCode, setReasonCode] = useState('');
  const [reasonText, setReasonText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setReasonCode('');
    setReasonText('');
    setErr(null);
    if (decision === 'SHORTLIST') {
      setGroups([]);
      return;
    }
    void (async () => {
      try {
        const res = await api.get<ReasonCodeGroup[]>(
          `/api/v1/erm/applications/reason-codes?decision=${decision}`,
        );
        setGroups(res.data ?? []);
      } catch {
        setGroups([]);
      }
    })();
  }, [decision, open]);

  if (!open) return null;
  const selectedOpt: ReasonCodeOption | null =
    groups.flatMap((g) => g.options).find((o) => o.code === reasonCode) ?? null;

  async function submit() {
    setErr(null);
    if (decision !== 'SHORTLIST' && !reasonCode) {
      setErr('Select a reason code.');
      return;
    }
    if (selectedOpt?.requiresFreeText && reasonText.trim().length === 0) {
      setErr('This reason requires free-text context.');
      return;
    }
    if (selectedIds.length === 0) {
      setErr('No applications selected.');
      return;
    }
    if (selectedIds.length > 50) {
      setErr('Bulk cap is 50 per request.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<{
        succeeded: string[];
        failed: { applicationId: string; reason: string }[];
      }>(`/api/v1/erm/applications/bulk-decision`, {
        applicationIds: selectedIds,
        decision: {
          decision,
          reasonCode: reasonCode || null,
          reasonText: reasonText.trim() || null,
          infoRequestedFields: decision === 'REQUEST_INFO' ? ['other'] : null,
        },
      });
      onApplied(res.data);
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Bulk decision failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">
          Bulk {decision.toLowerCase().replace(/_/g, ' ')}
        </h2>
        <p className="mt-2 text-sm text-slate-600">
          Sending to <b>{selectedIds.length}</b> applicant
          {selectedIds.length === 1 ? '' : 's'}. The same reason will apply
          to each.
        </p>

        {decision !== 'SHORTLIST' && (
          <div className="mt-4">
            <label className="text-sm font-medium text-slate-800">
              Reason code <span className="text-red-600">*</span>
            </label>
            <select
              value={reasonCode}
              onChange={(e) => setReasonCode(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">Select a reason…</option>
              {groups.map((g) => (
                <optgroup key={g.category} label={g.category.replace(/_/g, ' ')}>
                  {g.options.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>
        )}

        {selectedOpt?.requiresFreeText && (
          <div className="mt-4">
            <label className="text-sm font-medium text-slate-800">
              Free-text reason <span className="text-red-600">*</span>
            </label>
            <textarea
              value={reasonText}
              onChange={(e) => setReasonText(e.target.value)}
              rows={3}
              className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
        )}

        {err && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Applying…' : `Apply to ${selectedIds.length}`}
          </button>
        </div>
      </div>
    </div>
  );
}
