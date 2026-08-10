'use client';

import { forwardRef, type ReactNode } from 'react';

/**
 * Shared visual frame for the IDMS document preview. Used by the admin
 * studio's template editor AND the ERM/intern fill/sign surface (via
 * {@code InstanceRenderer}), so all three roles see identical document
 * rendering fidelity — same slate canvas, same white page with page-
 * shadow, same base field styling.
 *
 * <p>Role-specific overlays (tint colors for admin ownership, filled/
 * awaiting/signable state for fill) live in each consumer's own
 * {@code <style jsx global>} — this frame owns ONLY the doc-on-canvas
 * presentation so nothing in the framing can drift between studio and
 * fill.</p>
 *
 * <p>The ref is forwarded to the inner {@code .doc-canvas} div that
 * consumers {@code innerHTML} into (docx-preview output at author time,
 * or the stored {@code canonicalHtml} at fill time). Children render as
 * overlays on top of the canvas — position them absolutely against a
 * {@code relative}-positioned parent supplied by the consumer.</p>
 */
export const DocumentPreviewFrame = forwardRef<HTMLDivElement, {
  className?: string;
  children?: ReactNode;
}>(function DocumentPreviewFrame({ className, children }, ref) {
  return (
    <>
      <div
        ref={ref}
        className={
          `doc-canvas bg-slate-50 p-6${className ? ' ' + className : ''}`
        }
      />
      {children}
      <style jsx global>{`
        /* The document page itself — sits centered on the slate canvas
           with a subtle shadow so it reads as a real sheet, matching
           the admin studio's original aesthetic. Kept in the shared
           frame so admin, ERM, and intern render the doc identically. */
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
