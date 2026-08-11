'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Client-side draft autosave for long-form composers. Debounced writes
 * to {@link sessionStorage} under a caller-provided key, so drafts
 * survive an accidental refresh / crash / same-session nav without
 * needing a server-side draft table.
 *
 * <p>Persistence choice: {@link sessionStorage} not {@link localStorage}
 * because a) drafts are scoped to the current tab (two composers in
 * two tabs don't fight each other), and b) drafts don't leak across
 * sessions on shared machines. Cleared explicitly on successful
 * submit via {@link DraftAutosave#clear} so the next open shows a
 * fresh form.</p>
 *
 * <p>Regression source: the UX audit flagged 5 composers where a
 * refresh / crash / accidental navigation would lose 5-30 minutes of
 * typed work. Every one of those composers is now expected to route
 * its input state through this hook.</p>
 *
 * @param key   unique per-record key. MUST include the record id (or
 *              a composite that identifies "which draft this is") so
 *              two open composers for different records don't stomp
 *              on each other. Recommended shape: {@code
 *              "draft:<surface>:<recordId>"} — see per-composer usage.
 * @param value the current in-memory form state. Serialised to JSON
 *              on every debounced tick; MUST be JSON-safe (no
 *              functions, Dates, class instances).
 * @param opts.debounceMs how long to wait after the last change
 *              before persisting. Default 800ms (feels immediate to
 *              the user, coalesces bursts of keystrokes).
 * @param opts.enabled when false, the hook is inert (no reads, no
 *              writes). Use for gated composers where a draft only
 *              makes sense in specific states.
 */
export interface DraftAutosave {
  /** True iff a previously-persisted draft was loaded during
   *  {@link readDraft} on this key. Consumers surface a subtle
   *  "Draft restored" badge on first render. */
  restored: boolean;
  /** ms timestamp of the last successful save. null before the first
   *  save fires. Drives the "Saved 12s ago" indicator. */
  savedAt: number | null;
  /** Compact status string for the indicator component. */
  status: 'idle' | 'saving' | 'saved' | 'restored';
  /** Remove the draft. Call on successful submit + on explicit
   *  "Discard draft" affordances. Idempotent. */
  clear: () => void;
}

/**
 * Read a persisted draft on mount. Call INSIDE a state initializer
 * (e.g. {@code useState(() => readDraft<Shape>(key) ?? initial)}) so
 * the form starts with the saved value on the first render — no
 * flicker to "empty then restored".
 *
 * <p>Returns {@code null} when nothing is stored under the key OR
 * when the stored blob fails to parse (corrupted / schema drift).
 * Corrupted blobs are silently dropped so a legacy shape can't
 * permanently jam the composer.</p>
 */
export function readDraft<T>(key: string): T | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(key);
    if (raw == null) return null;
    return JSON.parse(raw) as T;
  } catch {
    // Corrupted draft (parser threw, schema mismatch, etc.) — drop
    // silently so the composer boots fresh instead of jamming.
    try { window.sessionStorage.removeItem(key); } catch { /* ignore */ }
    return null;
  }
}

export function useDraftAutosave<T>(
  key: string,
  value: T,
  opts: { debounceMs?: number; enabled?: boolean } = {},
): DraftAutosave {
  const debounceMs = opts.debounceMs ?? 800;
  const enabled = opts.enabled ?? true;

  const [status, setStatus] = useState<DraftAutosave['status']>('idle');
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [restored, setRestored] = useState(false);

  // First mount only — check whether a draft exists under this key so
  // the caller (who already restored the state via readDraft in its
  // initializer) can surface a "Draft restored" indicator. Runs once
  // per key so a key-change (e.g. switching records in an editor)
  // re-checks.
  useEffect(() => {
    if (!enabled || typeof window === 'undefined') return;
    try {
      const raw = window.sessionStorage.getItem(key);
      if (raw != null) {
        setRestored(true);
        setStatus('restored');
      } else {
        setRestored(false);
        setStatus('idle');
      }
    } catch {
      setRestored(false);
      setStatus('idle');
    }
  }, [key, enabled]);

  // Debounced persistence. A timer that resets on every value change;
  // fires the write once the user stops typing for `debounceMs`. Also
  // clears on unmount so a mid-flight timer doesn't fire against a
  // stale key.
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!enabled || typeof window === 'undefined') return;
    if (timerRef.current) clearTimeout(timerRef.current);
    setStatus('saving');
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      try {
        window.sessionStorage.setItem(key, JSON.stringify(value));
        setSavedAt(Date.now());
        setStatus('saved');
      } catch {
        // sessionStorage failed (quota, disabled, private mode) — go
        // back to idle rather than sit at "saving" forever. Silent
        // fail: the composer still works; only the safety-net drops.
        setStatus('idle');
      }
    }, debounceMs);
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = null;
    };
  }, [key, value, debounceMs, enabled]);

  const clear = useCallback(() => {
    if (typeof window === 'undefined') return;
    try { window.sessionStorage.removeItem(key); } catch { /* ignore */ }
    setRestored(false);
    setSavedAt(null);
    setStatus('idle');
  }, [key]);

  return { restored, savedAt, status, clear };
}
