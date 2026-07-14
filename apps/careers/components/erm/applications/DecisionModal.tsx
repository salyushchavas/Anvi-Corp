'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import api from '@/lib/api';
import type {
  ApplicationDetail,
  DecisionKind,
  ReasonCodeGroup,
  ReasonCodeOption,
} from './types';

const TITLE: Record<DecisionKind, string> = {
  SHORTLIST: 'Mark as Shortlisted',
  HOLD: 'Place on Hold',
  REQUEST_INFO: 'Request More Information',
  REJECT: 'Reject Application',
};

const SUBMIT_LABEL: Record<DecisionKind, string> = {
  SHORTLIST: 'Shortlist',
  HOLD: 'Send hold notice',
  REQUEST_INFO: 'Send request',
  REJECT: 'Send rejection',
};

const INFO_FIELD_OPTIONS = [
  { key: 'resume', label: 'Updated resume' },
  { key: 'workAuth', label: 'Work authorization details' },
  { key: 'education', label: 'Education verification' },
  { key: 'other', label: 'Other (specify)' },
];

const TEMPLATE_BY_DECISION: Partial<Record<DecisionKind, string>> = {
  HOLD: 'APPLICATION_HOLD',
  REQUEST_INFO: 'APPLICATION_REQUEST_INFO',
  REJECT: 'APPLICATION_REJECT',
};

interface Props {
  detail: ApplicationDetail;
  decision: DecisionKind;
  open: boolean;
  onClose: () => void;
  onApplied: () => void;
}

export default function DecisionModal({
  detail,
  decision,
  open,
  onClose,
  onApplied,
}: Props) {
  const [groups, setGroups] = useState<ReasonCodeGroup[]>([]);
  const [reasonCode, setReasonCode] = useState<string>('');
  const [reasonText, setReasonText] = useState('');
  const [infoFields, setInfoFields] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Live preview shows the rendered email body for HOLD/REQUEST_INFO/REJECT.
  const [previewSubject, setPreviewSubject] = useState<string | null>(null);
  const [previewBody, setPreviewBody] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setReasonCode('');
    setReasonText('');
    setInfoFields([]);
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

  const selectedOption: ReasonCodeOption | null = useMemo(() => {
    for (const g of groups) {
      const o = g.options.find((x) => x.code === reasonCode);
      if (o) return o;
    }
    return null;
  }, [groups, reasonCode]);

  const requiresText = !!selectedOption?.requiresFreeText;

  // Build the local preview from the seeded template copy. The actual render
  // happens server-side at decide time; this client preview is a faithful
  // approximation for ERM confirmation.
  useEffect(() => {
    if (decision === 'SHORTLIST') {
      setPreviewSubject(null);
      setPreviewBody(null);
      return;
    }
    const firstName = detail.applicant?.firstName || 'Applicant';
    const jobTitle = detail.job?.title || 'the role';
    const ermName = detail.application.ermOwnerName || 'Skyzen ERM';
    const fields =
      infoFields.length === 0
        ? '(select fields above)'
        : infoFields
            .map(
              (k) =>
                INFO_FIELD_OPTIONS.find((o) => o.key === k)?.label ?? k,
            )
            .join(', ');
    if (decision === 'HOLD') {
      setPreviewSubject('Your Skyzen application — under review');
      setPreviewBody(
        `Hello ${firstName},\n\nThank you for applying to ${jobTitle}. ` +
          `Your application is currently under extended review. We will ` +
          `reach out when we have an update.\n\n— ${ermName}`,
      );
    } else if (decision === 'REJECT') {
      setPreviewSubject('Update on your Skyzen application');
      setPreviewBody(
        `Hello ${firstName},\n\nThank you for applying to ${jobTitle} at ` +
          `Skyzen. After careful review, we have decided not to proceed ` +
          `with your application at this time. We appreciate your ` +
          `interest and wish you the best.\n\n— ${ermName}`,
      );
    } else if (decision === 'REQUEST_INFO') {
      setPreviewSubject('Skyzen application — additional information needed');
      setPreviewBody(
        `Hello ${firstName},\n\nWe are reviewing your application for ` +
          `${jobTitle} and need the following information: ${fields}. ` +
          `Please update your application in your Skyzen dashboard.\n\n— ${ermName}`,
      );
    }
  }, [decision, detail, infoFields]);

  const toggleField = useCallback((key: string) => {
    setInfoFields((cur) =>
      cur.includes(key) ? cur.filter((x) => x !== key) : [...cur, key],
    );
  }, []);

  async function submit() {
    setErr(null);
    if (decision !== 'SHORTLIST' && !reasonCode) {
      setErr('Select a reason code.');
      return;
    }
    if (requiresText && reasonText.trim().length === 0) {
      setErr('This reason requires free-text context.');
      return;
    }
    if (decision === 'REQUEST_INFO' && infoFields.length === 0) {
      setErr('Pick at least one field to request.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post(
        `/api/v1/erm/applications/${detail.application.id}/decision`,
        {
          decision,
          reasonCode: reasonCode || null,
          reasonText: reasonText.trim() || null,
          infoRequestedFields:
            decision === 'REQUEST_INFO' ? infoFields : null,
        },
      );
      onApplied();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Decision failed'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="grid max-h-[85vh] w-full max-w-3xl overflow-hidden rounded-lg bg-white shadow-xl lg:grid-cols-2">
        <div className="overflow-y-auto p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-slate-900">
              {TITLE[decision]}
            </h2>
            <button
              type="button"
              onClick={onClose}
              className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100"
              aria-label="Close"
            >
              ✕
            </button>
          </div>

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

          {requiresText && (
            <div className="mt-4">
              <label className="text-sm font-medium text-slate-800">
                Free-text reason <span className="text-red-600">*</span>{' '}
                <span className="text-xs text-slate-500">(ERM-only)</span>
              </label>
              <textarea
                value={reasonText}
                onChange={(e) => setReasonText(e.target.value)}
                rows={3}
                className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                placeholder="Describe the reason for the team's records."
              />
            </div>
          )}

          {decision === 'REQUEST_INFO' && (
            <div className="mt-4">
              <p className="text-sm font-medium text-slate-800">
                Fields to request <span className="text-red-600">*</span>
              </p>
              <ul className="mt-2 space-y-2">
                {INFO_FIELD_OPTIONS.map((o) => (
                  <li key={o.key}>
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={infoFields.includes(o.key)}
                        onChange={() => toggleField(o.key)}
                      />
                      {o.label}
                    </label>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {err && (
            <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
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
              {submitting ? 'Sending…' : SUBMIT_LABEL[decision]}
            </button>
          </div>
        </div>

        <aside className="hidden border-l border-slate-200 bg-slate-50 p-6 lg:block">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            Preview to applicant
          </p>
          {decision === 'SHORTLIST' ? (
            <div className="mt-3 rounded-md border border-slate-200 bg-white p-4 text-sm text-slate-600">
              No email at shortlist — applicant learns when their interview is
              scheduled.
            </div>
          ) : (
            <div className="mt-3 rounded-md border border-slate-200 bg-white p-4">
              {previewSubject && (
                <p className="text-sm font-semibold text-slate-900">
                  {previewSubject}
                </p>
              )}
              <p className="mt-3 whitespace-pre-wrap text-sm text-slate-700">
                {previewBody}
              </p>
              {TEMPLATE_BY_DECISION[decision] && (
                <p className="mt-4 text-[11px] text-slate-400">
                  Template: {TEMPLATE_BY_DECISION[decision]}
                </p>
              )}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
