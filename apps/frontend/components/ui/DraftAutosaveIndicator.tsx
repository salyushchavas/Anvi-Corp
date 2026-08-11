'use client';

import { useEffect, useState } from 'react';
import type { DraftAutosave } from '@/lib/careers/useDraftAutosave';

/**
 * Tiny visual companion for {@link useDraftAutosave}. Shows one of:
 * <ul>
 *   <li><b>Draft restored</b> — a persisted draft was loaded on mount.
 *     Overrides the "saved" text on first render so the user knows
 *     their old work is back.</li>
 *   <li><b>Saving…</b> — the debounce window is open, a save is
 *     scheduled.</li>
 *   <li><b>Saved just now / Saved Xs ago / Saved Nm ago</b> — the
 *     last successful save, updated once a second so the elapsed
 *     time keeps counting up without the parent re-rendering.</li>
 * </ul>
 *
 * <p>Kept intentionally small (11px muted text, no icons, no
 * animation) so it can slot next to a Save button without competing
 * for attention.</p>
 */
export default function DraftAutosaveIndicator({
  state, className,
}: {
  state: DraftAutosave;
  className?: string;
}) {
  // Re-render every second while a saved timestamp is displayed so
  // "Saved 5s ago" ticks up to "Saved 6s ago" without the parent
  // being involved. Cleaned up once savedAt is null or the component
  // unmounts.
  const [, forceTick] = useState(0);
  useEffect(() => {
    if (state.savedAt == null) return;
    const id = setInterval(() => forceTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [state.savedAt]);

  const text = renderText(state);
  if (!text) return null;
  return (
    <span
      className={
        `inline-flex items-center gap-1 text-[11px] font-medium text-slate-500 ${className ?? ''}`.trim()
      }
      aria-live="polite"
      aria-atomic="true"
    >
      {text}
    </span>
  );
}

function renderText(state: DraftAutosave): string | null {
  if (state.status === 'saving') return 'Saving draft…';
  if (state.status === 'restored' && state.savedAt == null) return 'Draft restored';
  if (state.savedAt == null) return null;
  const secs = Math.max(0, Math.round((Date.now() - state.savedAt) / 1000));
  if (secs < 3) return 'Saved just now';
  if (secs < 60) return `Saved ${secs}s ago`;
  const mins = Math.round(secs / 60);
  if (mins < 60) return `Saved ${mins}m ago`;
  return 'Saved';
}
