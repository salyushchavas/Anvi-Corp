'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * IDMS shared debounced + coalescing auto-save hook.
 *
 * <h3>Structural guarantees</h3>
 * <ul>
 *   <li><b>Single-slot pending</b>: while a save is in flight, subsequent
 *       calls just update {@code nextValues}. When the running save
 *       completes, if new values landed we fire ANOTHER save with the
 *       LATEST values — never dropping a keystroke, never firing two
 *       concurrent /fill requests that could arrive out of order and
 *       cause React setDetail to apply stale server responses last.</li>
 *   <li><b>Content-equal no-op</b>: {@code toKey(values)} equality against
 *       the last successfully saved key short-circuits — the second
 *       layer of protection against the F1 auto-save loop even if the
 *       effect deps ever regress.</li>
 *   <li><b>Explicit flush</b>: {@link SaveController#flush} awaits any
 *       in-flight save + any coalesced pending call + returns whether
 *       the LATEST values reached the server. send()/submit() calls
 *       this before firing the transition — no silent stale-value send.</li>
 * </ul>
 *
 * <h3>Save state</h3>
 * Cycles {@code idle → dirty → saving → saved | error}. The parent's
 * Send/Submit button gates on saveState during dirty/saving to prevent
 * concurrent transitions while values are still in flight.
 *
 * <h3>Retry on error</h3>
 * {@link SaveController#retry} explicitly re-fires with the last-
 * attempted values. Auto-retry happens after {@link RETRY_AFTER_MS} on
 * transient errors so a network blip self-heals instead of hanging in
 * "Not saved" until the user types again (F16).
 */

const AUTO_SAVE_DEBOUNCE_MS = 800;
const RETRY_AFTER_MS = 3_000;

export type SaveState =
  | { kind: 'idle' }
  | { kind: 'dirty' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: Date }
  | { kind: 'error'; message: string; retriable: boolean };

export interface SaveController<T> {
  saveState: SaveState;
  /** Schedule an auto-save with the current values (debounced). */
  scheduleSave(values: T): void;
  /** Await any pending + in-flight save with the latest values, return
   *  true iff the LATEST values are known-good on the server. */
  flush(latestValues: T): Promise<boolean>;
  /** Explicitly retry after an error. */
  retry(): void;
  /** Cancel any pending debounce timer without saving. */
  cancel(): void;
  /** Seed the last-saved key so an initial render doesn't re-POST. */
  seedLastSavedKey(values: T): void;
}

export interface UseCoalescedAutoSaveOptions<T> {
  /**
   * Perform the actual persistence call. Return true iff the payload
   * committed on the server. Throwing counts as failure.
   */
  persist: (values: T) => Promise<boolean>;
  /**
   * Content-key generator — two calls with equal keys are treated as
   * the same save; the second is a no-op. Default: JSON.stringify.
   */
  toKey?: (values: T) => string;
  /**
   * True when auto-save is allowed to fire (e.g. canEdit gate on the
   * intern side). When false, scheduleSave still tracks state but no
   * timer is armed and no POST fires.
   */
  enabled: boolean;
  /**
   * Called on a 409 from the persist. The parent typically refetches
   * detail so the UI reflects the terminal state instead of showing
   * a mystery error (F10 for the intern fill page).
   */
  onConflict?: () => void;
}

export function useCoalescedAutoSave<T>(
  opts: UseCoalescedAutoSaveOptions<T>,
): SaveController<T> {
  const { persist, enabled, onConflict } = opts;
  const toKey = opts.toKey ?? ((v: T) => JSON.stringify(v));

  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });

  const savingLockRef = useRef(false);
  const nextValuesRef = useRef<T | null>(null);
  const lastSavedKeyRef = useRef<string>('');
  const lastAttemptedRef = useRef<T | null>(null);
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const enabledRef = useRef(enabled);
  useEffect(() => { enabledRef.current = enabled; }, [enabled]);

  const runFlushLoop = useCallback(async (): Promise<boolean> => {
    if (savingLockRef.current) {
      // Another loop is already draining nextValuesRef; wait for it.
      // A tiny wait-poll is acceptable here (typical /fill is ~200 ms
      // so the loop exits quickly).
      while (savingLockRef.current) {
        await new Promise((r) => setTimeout(r, 25));
      }
    }
    savingLockRef.current = true;
    let finalResult = true;
    try {
      while (nextValuesRef.current !== null) {
        const toSave = nextValuesRef.current;
        nextValuesRef.current = null;
        const key = toKey(toSave);
        if (key === lastSavedKeyRef.current) {
          // Content-equal to last save — nothing to do.
          setSaveState({ kind: 'saved', at: new Date() });
          continue;
        }
        setSaveState({ kind: 'saving' });
        lastAttemptedRef.current = toSave;
        try {
          const ok = await persist(toSave);
          if (ok) {
            lastSavedKeyRef.current = key;
            setSaveState({ kind: 'saved', at: new Date() });
            finalResult = true;
          } else {
            setSaveState({
              kind: 'error',
              message: 'Save failed',
              retriable: true,
            });
            finalResult = false;
            scheduleAutoRetry();
            break;
          }
        } catch (e) {
          const ax = e as { response?: { status?: number; data?: { error?: string } } };
          if (ax.response?.status === 409) {
            setSaveState({ kind: 'idle' });
            finalResult = false;
            onConflict?.();
            break;
          }
          setSaveState({
            kind: 'error',
            message: ax.response?.data?.error ?? 'Save failed',
            retriable: true,
          });
          finalResult = false;
          scheduleAutoRetry();
          break;
        }
      }
    } finally {
      savingLockRef.current = false;
    }
    return finalResult;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [persist, onConflict]);

  const scheduleAutoRetry = useCallback(() => {
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    retryTimerRef.current = setTimeout(() => {
      const last = lastAttemptedRef.current;
      if (!last) return;
      // Re-schedule via the normal loop so the coalescing rules apply.
      nextValuesRef.current = last;
      void runFlushLoop();
    }, RETRY_AFTER_MS);
  }, [runFlushLoop]);

  const scheduleSave = useCallback((values: T) => {
    if (!enabledRef.current) return;
    const key = toKey(values);
    if (key === lastSavedKeyRef.current) return; // no-op
    setSaveState({ kind: 'dirty' });
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    debounceTimerRef.current = setTimeout(() => {
      nextValuesRef.current = values;
      void runFlushLoop();
    }, AUTO_SAVE_DEBOUNCE_MS);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runFlushLoop]);

  const flush = useCallback(async (latestValues: T): Promise<boolean> => {
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    // Enqueue the latest values so the loop's post-check picks them up
    // even if the current in-flight save was for older values.
    nextValuesRef.current = latestValues;
    return runFlushLoop();
  }, [runFlushLoop]);

  const retry = useCallback(() => {
    const last = lastAttemptedRef.current;
    if (!last) return;
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    nextValuesRef.current = last;
    void runFlushLoop();
  }, [runFlushLoop]);

  const cancel = useCallback(() => {
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
  }, []);

  const seedLastSavedKey = useCallback((values: T) => {
    lastSavedKeyRef.current = toKey(values);
    setSaveState({ kind: 'idle' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => () => {
    if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
    if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
  }, []);

  return { saveState, scheduleSave, flush, retry, cancel, seedLastSavedKey };
}
