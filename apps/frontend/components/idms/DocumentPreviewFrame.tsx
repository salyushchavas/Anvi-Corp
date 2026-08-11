'use client';

import { forwardRef, useCallback, useEffect, useRef, type ReactNode } from 'react';

/**
 * Shared visual frame for the IDMS document preview. Used by the admin
 * studio's template editor AND the ERM/intern fill/sign surface (via
 * {@code InstanceRenderer}), so all three roles see identical document
 * rendering fidelity — same slate canvas, same white page with page-
 * shadow, same base field styling, same fit-to-pane scaling.
 *
 * <p>Role-specific overlays (tint colors for admin ownership, filled/
 * awaiting/signable state for fill) live in each consumer's own
 * {@code <style jsx global>} — this frame owns ONLY the doc-on-canvas
 * presentation so nothing in the framing can drift between studio and
 * fill.</p>
 *
 * <p>Fit-to-pane. docx-preview emits a fixed-width page (Letter/A4 —
 * 8.5in / 8.27in ≈ 800px) which overflows a narrower fill pane on
 * desktop (~1fr column beside a 380px field panel) or a phone screen.
 * The previous version rendered the page at natural width with
 * horizontal overflow, and the doc looked misaligned + off-center. Now
 * a ResizeObserver measures the pane inner width and the page's natural
 * width, then sets a CSS variable driving {@code zoom} on the docx
 * wrapper — the doc scales down uniformly to fit AND its layout height
 * shrinks in proportion (zoom, unlike transform: scale, participates
 * in layout so we don't need margin-trickery to reserve the scaled
 * height). Never scales UP (max 1.0) — small docs on wide panes render
 * at native size. Re-fits on pane resize + on innerHTML change (docx-
 * preview render or canonicalHtml swap).</p>
 */
export const DocumentPreviewFrame = forwardRef<HTMLDivElement, {
  className?: string;
  children?: ReactNode;
}>(function DocumentPreviewFrame({ className, children }, forwardedRef) {
  const localRef = useRef<HTMLDivElement | null>(null);

  const setRefs = useCallback((node: HTMLDivElement | null) => {
    localRef.current = node;
    if (typeof forwardedRef === 'function') forwardedRef(node);
    else if (forwardedRef) {
      (forwardedRef as { current: HTMLDivElement | null }).current = node;
    }
  }, [forwardedRef]);

  useEffect(() => {
    const container = localRef.current;
    if (!container) return;

    // Fit the .docx-wrapper (or fallback .docx article) so the whole
    // page width fits the pane's inner width; scroll vertically as
    // needed. Guarded against zero-width measurements (element not
    // yet laid out) which would produce NaN.
    function fit() {
      if (!container) return;
      const wrapper = container.querySelector<HTMLElement>(
        '.docx-wrapper, .docx',
      );
      if (!wrapper) return;
      // Reset zoom to sample natural width. Reading offsetWidth while
      // zoom is applied returns the ZOOMED size, so we must clear
      // first, measure, then re-apply.
      wrapper.style.zoom = '1';
      const naturalWidth = wrapper.offsetWidth;
      if (naturalWidth <= 0) return;
      const paneStyle = window.getComputedStyle(container);
      const padding =
        parseFloat(paneStyle.paddingLeft || '0')
        + parseFloat(paneStyle.paddingRight || '0');
      const paneInnerWidth = container.clientWidth - padding;
      if (paneInnerWidth <= 0) return;
      const scale = Math.min(1, paneInnerWidth / naturalWidth);
      wrapper.style.zoom = String(scale);
    }

    // Fit on mount + on any resize of the pane. rAF-debounced so a
    // rapid drag of the sidebar boundary coalesces into one write per
    // frame — cheap even on long docs.
    let rafId: number | null = null;
    function scheduleFit() {
      if (rafId != null) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        rafId = null;
        fit();
      });
    }

    scheduleFit();
    const ro = new ResizeObserver(scheduleFit);
    ro.observe(container);

    // The consumer innerHTMLs into this same node AFTER mount (docx-
    // preview renderAsync in admin studio, or canonicalHtml assignment
    // in InstanceRenderer). Watch for child mutations so we re-fit as
    // soon as the .docx-wrapper appears.
    const mo = new MutationObserver(scheduleFit);
    mo.observe(container, { childList: true, subtree: true });

    return () => {
      if (rafId != null) cancelAnimationFrame(rafId);
      ro.disconnect();
      mo.disconnect();
    };
  }, []);

  return (
    <>
      <div
        ref={setRefs}
        className={
          `doc-canvas bg-slate-50 p-6${className ? ' ' + className : ''}`
        }
      />
      {children}
      <style jsx global>{`
        /* The document page itself — sits centered on the slate canvas
           with a subtle shadow so it reads as a real sheet, matching
           the admin studio's original aesthetic. Kept in the shared
           frame so admin, ERM, and intern render the doc identically.
           overflow-x: hidden on the canvas suppresses any residual
           side-scroll bar on browsers that don't apply zoom to layout
           (older Firefox); the JS scaler above always shrinks the doc
           to fit, so no content is clipped. */
        .doc-canvas {
          overflow-x: hidden;
        }
        .doc-canvas .docx-wrapper,
        .doc-canvas .docx {
          background: white;
          box-shadow: 0 1px 3px 0 rgb(0 0 0 / 0.1);
          margin: 0 auto;
        }
        /* Base field anchor — border-radius + inline padding that every
           consumer (admin studio + fill surface) shares. State-specific
           colors + backgrounds are layered on top by each consumer. */
        .doc-field {
          border-radius: 3px;
          padding: 0 2px;
        }
      `}</style>
    </>
  );
});
