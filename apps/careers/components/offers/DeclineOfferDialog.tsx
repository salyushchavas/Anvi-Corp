'use client';

import { useEffect, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';

interface Props {
  open: boolean;
  onClose: () => void;
  onConfirmed: () => void;
  offerId: string;
}

export default function DeclineOfferDialog({
  open,
  onClose,
  onConfirmed,
  offerId,
}: Props) {
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const declineRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      setReason('');
      setPending(false);
      return;
    }
    const t = setTimeout(() => declineRef.current?.focus(), 30);
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

  async function handleDecline() {
    if (pending) return;
    setPending(true);
    try {
      await api.post(`/api/v1/offers/${offerId}/decline`, {
        reason: reason.trim() || undefined,
      });
      toast('Offer declined. We’ve notified the hiring team.', { icon: '📩' });
      onConfirmed();
      onClose();
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? 'Could not decline offer');
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
        aria-labelledby="decline-dialog-title"
        className="relative w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h2
          id="decline-dialog-title"
          className="text-lg font-semibold text-gray-900"
        >
          Decline this offer?
        </h2>
        <p className="mt-2 text-sm text-gray-600">
          We understand things don&apos;t always work out. Optionally let the hiring
          team know why.
        </p>

        <div className="mt-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            Reason (optional)
          </label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder="e.g. Accepted another opportunity, timing didn't work, etc."
            className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
          />
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
            ref={declineRef}
            type="button"
            onClick={() => void handleDecline()}
            disabled={pending}
            className="inline-flex items-center gap-2 rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-50"
          >
            {pending && (
              <span
                aria-hidden="true"
                className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"
              />
            )}
            {pending ? 'Declining…' : 'Decline'}
          </button>
        </div>
      </div>
    </div>
  );
}
