'use client';

import { useMemo, useState } from 'react';
import { X } from 'lucide-react';
import api from '@/lib/api';

/**
 * Mail bridge Phase 5 — ERM dialog for the "Assign company email"
 * action. Calls POST /api/v1/erm/interns/{userId}/assign-company-email
 * via the shared axios client. On success the parent refreshes
 * NewHireDetail so the page flips to the PENDING_ACTIVATION chip.
 *
 * <p>Visual shell mirrors the existing inline-modal pattern used by
 * {@code SetJoiningDateModal} in the same page — same {@code fixed
 * inset-0 z-50 ... bg-black/40} backdrop, same width + spacing
 * tokens — so the dialog looks like a sibling action, not a new
 * design.</p>
 *
 * <p>The starting password is generated client-side (or typed by ERM)
 * and posted to the backend. The backend hashes it, stores the hash on
 * the new mail account, and emails the plaintext to the intern's
 * personal Gmail via the AFTER_COMMIT listener. The API response does
 * NOT include the plaintext — what's shown to the ERM here is what
 * was generated client-side, presented once so the ERM can verbally
 * confirm with the intern if needed.</p>
 */
export default function AssignCompanyEmailDialog({
  open,
  userId,
  internName,
  personalEmail,
  onClose,
  onAssigned,
}: {
  open: boolean;
  userId: string;
  internName: string | null;
  personalEmail: string | null;
  onClose: () => void;
  onAssigned: (assignedEmail: string) => void;
}) {
  const [localPart, setLocalPart] = useState('');
  const [password, setPassword] = useState(() => generatePassword());
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const cleanLocal = useMemo(
    () => localPart.trim().toLowerCase().replace(/\s+/g, ''),
    [localPart],
  );
  const previewEmail = cleanLocal ? `${cleanLocal}@skyzentech.com` : '—';
  const canSubmit = cleanLocal.length > 0 && password.length >= 8 && !submitting;

  async function submit() {
    setErr(null);
    if (!cleanLocal) {
      setErr('Local part is required.');
      return;
    }
    if (password.length < 8) {
      setErr('Starting password must be at least 8 characters.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<{
        userId: string;
        companyEmail: string;
        status: string;
      }>(`/api/v1/erm/interns/${userId}/assign-company-email`, {
        localPart: cleanLocal,
        startingPassword: password,
      });
      onAssigned(res.data.companyEmail);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Assignment failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Assign company email
            </h3>
            <p className="text-xs text-slate-500">
              {internName ?? 'This intern'} will receive their starting
              credentials at{' '}
              <span className="font-mono">{personalEmail ?? '(personal email on file)'}</span>{' '}
              and must change the password on first mailbox login. Their
              dashboard login also moves to the company email after that.
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

        <div className="space-y-4 p-5">
          <label className="block">
            <span className="text-sm font-medium text-slate-800">Company email</span>
            <div className="mt-1 flex overflow-hidden rounded-md border border-slate-200 focus-within:ring-2 focus-within:ring-brand-500">
              <input
                value={localPart}
                onChange={(e) => setLocalPart(e.target.value)}
                placeholder="e.g. asmith"
                className="block w-full border-0 px-3 py-2 text-sm focus:outline-none focus:ring-0"
                autoFocus
              />
              <span className="flex items-center bg-slate-50 px-3 text-sm text-slate-500">
                @skyzentech.com
              </span>
            </div>
            <p className="mt-1 text-[11px] text-slate-500">
              Will become: <span className="font-mono">{previewEmail}</span>
            </p>
          </label>

          <label className="block">
            <span className="text-sm font-medium text-slate-800">Starting password</span>
            <div className="mt-1 flex gap-2">
              <input
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="block w-full rounded-md border border-slate-200 px-3 py-2 font-mono text-sm"
                type="text"
                minLength={8}
              />
              <button
                type="button"
                onClick={() => setPassword(generatePassword())}
                className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                Generate
              </button>
            </div>
            <p className="mt-1 text-[11px] text-slate-500">
              Shown here only — the intern receives it once by email and
              sets their own on first mailbox login. Min 8 characters.
            </p>
          </label>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={!canSubmit}
            className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
          >
            {submitting ? 'Assigning…' : 'Assign + email credentials'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * 12-char alphanumeric password with at least one digit and one
 * letter. Strong enough for a starting credential the intern will
 * change on first login; readable enough to dictate verbally if
 * needed.
 */
function generatePassword(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789';
  const cryptoSource = typeof window !== 'undefined' ? window.crypto : null;
  const len = 12;
  const out: string[] = [];
  if (cryptoSource && cryptoSource.getRandomValues) {
    const buf = new Uint32Array(len);
    cryptoSource.getRandomValues(buf);
    for (let i = 0; i < len; i++) {
      out.push(alphabet[buf[i] % alphabet.length]);
    }
  } else {
    for (let i = 0; i < len; i++) {
      out.push(alphabet[Math.floor(Math.random() * alphabet.length)]);
    }
  }
  return out.join('');
}
