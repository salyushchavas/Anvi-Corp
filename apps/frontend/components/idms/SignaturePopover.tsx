'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, Loader2, X } from 'lucide-react';
import SignatureCapture from '@/components/idms/SignatureCapture';

/**
 * IDMS signature-in-preview popover.
 *
 * <p>Anchored at a signature slot in the document preview so the user
 * signs AT the signature line — matching how one signs a paper
 * document — instead of the prior "draw box buried at the bottom of the
 * right panel" model. The four-mode {@link SignatureCapture}
 * (Draw / Upload / Generate / Clean) renders unchanged inside; only the
 * container / positioning is new.</p>
 *
 * <h3>Positioning</h3>
 * Fixed-position container placed just below the anchor's bounding rect.
 * If the popover would spill off the bottom of the viewport, we flip it
 * above the anchor. Horizontal position is clamped into the viewport
 * with an 8 px gutter so a right-margin anchor doesn't push it off
 * screen. Recomputes on window scroll / resize AND on any scroll event
 * inside the preview container so a scroll of the doc scroller keeps
 * the popover pinned to the moving anchor.
 *
 * <h3>Dismiss</h3>
 * Escape closes. Click-outside (mousedown outside the popover AND
 * outside the anchor — otherwise a re-click on the same anchor would
 * flicker close/open) closes. The Save button is the only affirmative
 * path; the parent's onSave receives the staged data URL + typed name
 * and can await a network POST before we call onClose.
 */
export interface SignaturePopoverProps {
  /** The signature slot in the preview to anchor against. When null the
   *  popover renders nothing (parent gates via `if (state) ...`). */
  anchorEl: HTMLElement | null;
  /** Pre-fills the Generate mode's name input + defaults the typed-name
   *  field. Same seed as the prior in-aside capture used. */
  initialName?: string;
  /** Field name displayed in the popover header ("Employee Signature"
   *  / "ERM Signature") — the user needs to know WHICH slot they're
   *  signing when the doc has multiple. */
  fieldName?: string;
  onCancel(): void;
  /** Save handler — receives the staged PNG data URL + typed name.
   *  Returns a promise so the popover shows a spinner and defers close
   *  until the parent's POST /sign completes. */
  onSave(dataUrl: string, typedName: string): Promise<void>;
  /** Optional container element whose scroll should re-trigger a
   *  reposition. In our fill pages this is the preview column's
   *  overflow-y-auto div. Falls back to window-only if omitted. */
  scrollContainer?: HTMLElement | null;
}

const POPOVER_WIDTH = 380; // Matches the aside width so it never feels narrower than the panel.
const GAP = 8;             // Space between anchor bottom and popover top.
const VIEWPORT_GUTTER = 12; // Minimum distance from viewport edges.

export default function SignaturePopover({
  anchorEl, initialName, fieldName, onCancel, onSave, scrollContainer,
}: SignaturePopoverProps) {
  const popRef = useRef<HTMLDivElement | null>(null);
  const [pos, setPos] = useState<{ top: number; left: number; flipped: boolean } | null>(null);
  const [staged, setStaged] = useState<string | null>(null);
  const [typedName, setTypedName] = useState(initialName ?? '');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Re-seed the typed-name when the popover reopens for a different field
  // — otherwise closing + reopening on a fresh slot would show the last
  // user-edited name.
  useEffect(() => {
    setTypedName(initialName ?? '');
    setStaged(null);
    setErr(null);
    setSaving(false);
  }, [anchorEl, initialName]);

  const reposition = useCallback(() => {
    if (!anchorEl || !popRef.current) return;
    const a = anchorEl.getBoundingClientRect();
    const pop = popRef.current;
    const popH = pop.offsetHeight || 320; // sensible initial guess before first paint
    const spaceBelow = window.innerHeight - a.bottom - GAP - VIEWPORT_GUTTER;
    const spaceAbove = a.top - GAP - VIEWPORT_GUTTER;
    const flipped = spaceBelow < popH && spaceAbove > spaceBelow;
    const top = flipped
      ? Math.max(VIEWPORT_GUTTER, a.top - GAP - popH)
      : Math.min(
          window.innerHeight - VIEWPORT_GUTTER - popH,
          a.bottom + GAP,
        );
    // Prefer aligning the popover's left with the anchor's left,
    // clamped into the viewport.
    const rawLeft = a.left;
    const clampedLeft = Math.max(
      VIEWPORT_GUTTER,
      Math.min(rawLeft, window.innerWidth - POPOVER_WIDTH - VIEWPORT_GUTTER),
    );
    setPos({ top, left: clampedLeft, flipped });
  }, [anchorEl]);

  useLayoutEffect(() => {
    reposition();
  }, [reposition]);

  useEffect(() => {
    if (!anchorEl) return;
    const onScroll = () => reposition();
    const onResize = () => reposition();
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onResize);
    scrollContainer?.addEventListener('scroll', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onResize);
      scrollContainer?.removeEventListener('scroll', onScroll);
    };
  }, [anchorEl, scrollContainer, reposition]);

  // Escape closes.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && !saving) onCancel();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [saving, onCancel]);

  // Click-outside closes — but not while the save POST is in flight
  // (would abandon a signature the user thinks they've saved).
  useEffect(() => {
    function onDown(e: MouseEvent) {
      if (saving) return;
      const t = e.target as Node | null;
      if (!t) return;
      if (popRef.current?.contains(t)) return;
      // Don't close when the user clicks the same anchor — that would
      // race the parent's re-open logic.
      if (anchorEl?.contains(t)) return;
      onCancel();
    }
    document.addEventListener('mousedown', onDown, true);
    return () => document.removeEventListener('mousedown', onDown, true);
  }, [anchorEl, saving, onCancel]);

  const canSave = Boolean(staged) && !saving;

  async function handleSave() {
    if (!staged || saving) return;
    setSaving(true);
    setErr(null);
    try {
      await onSave(staged, typedName);
      // Parent closes us via anchorEl → null; nothing to do here.
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Signature save failed');
      setSaving(false);
    }
  }

  const style = useMemo<React.CSSProperties>(() => ({
    position: 'fixed',
    top: pos?.top ?? -9999,
    left: pos?.left ?? -9999,
    width: POPOVER_WIDTH,
    visibility: pos ? 'visible' : 'hidden',
    zIndex: 60,
  }), [pos]);

  if (typeof window === 'undefined' || !anchorEl) return null;

  return createPortal(
    <div
      ref={popRef}
      style={style}
      role="dialog"
      aria-label={fieldName ? `Sign: ${fieldName}` : 'Sign here'}
      className="rounded-lg border-2 border-brand-500 bg-white p-4 shadow-2xl ring-4 ring-brand-100"
    >
      <header className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-slate-900">
            {fieldName ?? 'Your signature'}
          </h3>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Signing at the signature line in the document.
          </p>
        </div>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          aria-label="Close"
          className="text-slate-400 hover:text-slate-700 disabled:opacity-50"
        >
          <X className="h-4 w-4" />
        </button>
      </header>

      <div className="mt-3">
        <SignatureCapture
          initialName={initialName}
          onChange={setStaged}
          disabled={saving}
        />
      </div>

      <div className="mt-3">
        <label className="text-xs font-medium text-slate-600">
          Typed name (for the record)
        </label>
        <input
          value={typedName}
          onChange={(e) => setTypedName(e.target.value)}
          disabled={saving}
          placeholder="Your name"
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 disabled:opacity-60"
        />
      </div>

      {err && (
        <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}

      <div className="mt-4 flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={handleSave}
          disabled={!canSave}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
        >
          {saving
            ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
            : <CheckCircle2 className="h-3.5 w-3.5" />}
          Save signature
        </button>
      </div>
    </div>,
    document.body,
  );
}
