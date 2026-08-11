'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * Themed replacement for {@code window.prompt(...)} on destructive
 * actions that need a reason (cancel packet, reject hire, revoke
 * offer, waive tasks, etc.). Enforces a minimum-length note client-
 * side so the destructive act carries a real audit-trail rationale
 * — the old {@code prompt() → alert('too short')} loop was easy to
 * bypass and looked untrustworthy.
 *
 * <p>Same visual shell as {@link ConfirmDialog} (backdrop, panel,
 * ESC-to-close, focus management) plus a required textarea with a
 * live "N/M chars" counter and a disabled Confirm until minLength is
 * met. Danger variant tints the Confirm button red — every current
 * caller is destructive, so danger is the default.</p>
 */
interface Props {
  open: boolean;
  onClose: () => void;
  /** Called with the trimmed reason string once the user confirms.
   *  May return a Promise; the dialog shows "Working…" until it
   *  settles (success or throw). */
  onConfirm: (reason: string) => Promise<void> | void;
  title: string;
  description?: string;
  /** Textarea label. Defaults to "Reason". */
  reasonLabel?: string;
  /** Placeholder inside the textarea (guidance on what to write). */
  placeholder?: string;
  /** Minimum trimmed length required before Confirm activates.
   *  Default 10 — enough to force a real note, short enough not
   *  to feel oppressive on legitimate quick reasons. */
  minLength?: number;
  /** Max textarea length. Default 1000. */
  maxLength?: number;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'primary' | 'danger';
}

export default function ReasonDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  reasonLabel = 'Reason',
  placeholder,
  minLength = 10,
  maxLength = 1000,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
}: Props) {
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!open) {
      setReason('');
      setPending(false);
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

  const trimmedLen = reason.trim().length;
  const canConfirm = !pending && trimmedLen >= minLength;

  async function handleConfirm() {
    if (!canConfirm) return;
    setPending(true);
    try {
      await onConfirm(reason.trim());
    } finally {
      setPending(false);
    }
  }

  const confirmClass = variant === 'danger'
    ? 'bg-red-600 hover:bg-red-700 text-white'
    : 'bg-accent hover:bg-accent-dark text-white';

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
        aria-labelledby="reason-dialog-title"
        className="relative w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h2 id="reason-dialog-title" className="text-lg font-semibold text-gray-900">
          {title}
        </h2>
        {description && (
          <p className="mt-2 text-sm text-gray-600">{description}</p>
        )}

        <label className="mt-4 block">
          <span className="flex items-baseline justify-between text-xs font-medium text-gray-700">
            <span>{reasonLabel} <span className="text-red-600">*</span></span>
            <span
              className={
                'tabular-nums ' +
                (trimmedLen < minLength ? 'text-gray-400' : 'text-gray-500')
              }
            >
              {trimmedLen}/{minLength} min
            </span>
          </span>
          <textarea
            ref={textareaRef}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={4}
            maxLength={maxLength}
            placeholder={placeholder}
            disabled={pending}
            className="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:bg-gray-50"
          />
        </label>

        <div className="mt-6 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={pending}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={() => void handleConfirm()}
            disabled={!canConfirm}
            title={
              !canConfirm && !pending
                ? `Reason must be at least ${minLength} characters`
                : undefined
            }
            className={
              'inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ' +
              confirmClass
            }
          >
            {pending && (
              <span
                aria-hidden="true"
                className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"
              />
            )}
            {pending ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
