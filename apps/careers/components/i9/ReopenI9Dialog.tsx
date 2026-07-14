'use client';

import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import type { I9FormResponse } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  onReopened: (form: I9FormResponse) => void;
  formId: string;
}

export default function ReopenI9Dialog({
  open,
  onClose,
  onReopened,
  formId,
}: Props) {
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!open) {
      setReason('');
      setPending(false);
      setError(null);
      return;
    }
    const t = setTimeout(() => textareaRef.current?.focus(), 30);
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

  async function handleReopen() {
    if (pending) return;
    const trimmed = reason.trim();
    if (!trimmed) {
      setError('A reason is required.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const res = await api.post<I9FormResponse>(
        `/api/v1/i9/${formId}/reopen`,
        { reason: trimmed }
      );
      toast.success('Form reopened');
      onReopened(res.data);
      onClose();
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't reopen form. Try again.";
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
        aria-labelledby="reopen-dialog-title"
        className="relative w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h2
          id="reopen-dialog-title"
          className="text-lg font-semibold text-gray-900"
        >
          Reopen this I-9?
        </h2>
        <p className="mt-2 text-sm text-gray-600">
          Reopening allows corrections to Section 2. The action will be
          permanently logged in the audit history.
        </p>

        <div className="mt-4">
          <label
            htmlFor="reopen-reason"
            className="mb-1.5 block text-sm font-medium text-gray-900"
          >
            Reason for reopening
            <span className="ml-0.5 text-red-500">*</span>
          </label>
          <textarea
            ref={textareaRef}
            id="reopen-reason"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              if (error && e.target.value.trim()) setError(null);
            }}
            rows={3}
            placeholder="e.g. Wrong document number recorded for List A item"
            className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
          {error && (
            <p className="mt-1 text-xs text-red-600">{error}</p>
          )}
        </div>

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
            type="button"
            onClick={() => void handleReopen()}
            disabled={pending}
            className="inline-flex items-center gap-2 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800 disabled:opacity-50"
          >
            {pending && (
              <span
                aria-hidden="true"
                className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"
              />
            )}
            {pending ? 'Reopening…' : 'Reopen Form'}
          </button>
        </div>
      </div>
    </div>
  );
}
