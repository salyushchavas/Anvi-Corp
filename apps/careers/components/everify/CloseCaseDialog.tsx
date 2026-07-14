'use client';

import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import type { EVerifyCaseResponse, EVerifyClosureReason } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  onClosed: (updated: EVerifyCaseResponse) => void;
  caseId: string;
}

const REASON_OPTIONS: { value: EVerifyClosureReason; label: string }[] = [
  { value: 'SUCCESSFUL', label: 'Successful — Employment authorized' },
  { value: 'EMPLOYEE_TERMINATED', label: 'Employee terminated before resolution' },
  { value: 'INVALID_QUERY', label: 'Invalid query — opened in error' },
  { value: 'OTHER', label: 'Other (document in notes)' },
];

export default function CloseCaseDialog({
  open,
  onClose,
  onClosed,
  caseId,
}: Props) {
  const [reason, setReason] = useState<EVerifyClosureReason | ''>('');
  const [notes, setNotes] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      setReason('');
      setNotes('');
      setPending(false);
      setError(null);
      return;
    }
    const t = setTimeout(() => closeBtnRef.current?.focus(), 30);
    return () => clearTimeout(t);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function handler(e: KeyboardEvent) {
      if (e.key === 'Escape' && !pending) onClose();
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, pending, onClose]);

  if (!open) return null;

  async function handleClose() {
    if (pending) return;
    if (!reason) {
      setError('Please pick a closure reason.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const res = await api.post<EVerifyCaseResponse>(
        `/api/v1/everify/${caseId}/close`,
        {
          closureReason: reason,
          notes: notes.trim() || undefined,
        }
      );
      toast.success('Case closed');
      onClosed(res.data);
      onClose();
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't close case.";
      setError(msg);
      toast.error(msg);
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <button
        type="button"
        aria-label="Close dialog"
        onClick={() => !pending && onClose()}
        className="absolute inset-0 bg-black/40"
      />

      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="close-case-title"
        className="relative w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h2
          id="close-case-title"
          className="text-lg font-semibold text-gray-900"
        >
          Close E-Verify Case
        </h2>
        <p className="mt-2 text-sm text-gray-600">
          Final action — this closes the case in our records. The closure will
          be permanently logged in the audit history.
        </p>

        <div className="mt-4">
          <label
            htmlFor="close-reason"
            className="mb-1.5 block text-sm font-medium text-gray-900"
          >
            Closure reason
            <span className="ml-0.5 text-red-500">*</span>
          </label>
          <select
            id="close-reason"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value as EVerifyClosureReason);
              if (error && e.target.value) setError(null);
            }}
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500"
          >
            <option value="">Select reason…</option>
            {REASON_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>

        <div className="mt-4">
          <label
            htmlFor="close-notes"
            className="mb-1.5 block text-sm font-medium text-gray-900"
          >
            Notes (optional)
          </label>
          <textarea
            id="close-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={3}
            placeholder="Additional context for the closure (will be appended to existing notes)"
            className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500"
          />
        </div>

        {error && (
          <p className="mt-2 text-xs text-red-600">{error}</p>
        )}

        <div className="mt-6 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={pending}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={() => void handleClose()}
            disabled={pending}
            className="inline-flex items-center gap-2 rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-50"
          >
            {pending && (
              <span
                aria-hidden="true"
                className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"
              />
            )}
            {pending ? 'Closing…' : 'Close Case'}
          </button>
        </div>
      </div>
    </div>
  );
}
