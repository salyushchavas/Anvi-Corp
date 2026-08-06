'use client';

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';

/** IDMS Phase 2 — shared signature pad. HiDPI-aware canvas; exposes
 *  {@code toDataURL()}, {@code clear()}, {@code isEmpty()} via ref. */
export interface SignaturePadHandle {
  toDataURL(): string | null;
  clear(): void;
  isEmpty(): boolean;
}

interface Props {
  onChange?: (empty: boolean) => void;
  /** Fires on the trailing edge of each stroke (pointer-up) with the
   *  current PNG data URL, or {@code null} if the pad is empty. Used by
   *  {@code SignatureCapture}'s Draw tab to keep the parent's staged
   *  signature in sync without polling the imperative handle. */
  onStrokeEnd?: (dataUrl: string | null) => void;
  disabled?: boolean;
}

const SignaturePad = forwardRef<SignaturePadHandle, Props>(function SignaturePad(
  { onChange, onStrokeEnd, disabled },
  ref,
) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const drawingRef = useRef(false);
  const emptyRef = useRef(true);
  const [tick, setTick] = useState(0);

  useImperativeHandle(ref, () => ({
    toDataURL(): string | null {
      const c = canvasRef.current;
      if (!c || emptyRef.current) return null;
      return c.toDataURL('image/png');
    },
    clear() {
      const c = canvasRef.current;
      if (!c) return;
      const ctx = c.getContext('2d');
      if (ctx) ctx.clearRect(0, 0, c.width, c.height);
      emptyRef.current = true;
      setTick((n) => n + 1);
      onChange?.(true);
      onStrokeEnd?.(null);
    },
    isEmpty() {
      return emptyRef.current;
    },
  }));

  useEffect(() => {
    const c = canvasRef.current;
    if (!c) return;
    const ratio = Math.max(1, window.devicePixelRatio || 1);
    const rect = c.getBoundingClientRect();
    c.width = rect.width * ratio;
    c.height = rect.height * ratio;
    const ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.scale(ratio, ratio);
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.strokeStyle = '#0f172a';
  }, []);

  function pointerDown(e: React.PointerEvent<HTMLCanvasElement>) {
    if (disabled) return;
    const c = canvasRef.current;
    if (!c) return;
    drawingRef.current = true;
    c.setPointerCapture(e.pointerId);
    const rect = c.getBoundingClientRect();
    const ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.beginPath();
    ctx.moveTo(e.clientX - rect.left, e.clientY - rect.top);
  }
  function pointerMove(e: React.PointerEvent<HTMLCanvasElement>) {
    if (!drawingRef.current || disabled) return;
    const c = canvasRef.current;
    if (!c) return;
    const rect = c.getBoundingClientRect();
    const ctx = c.getContext('2d');
    if (!ctx) return;
    ctx.lineTo(e.clientX - rect.left, e.clientY - rect.top);
    ctx.stroke();
    if (emptyRef.current) {
      emptyRef.current = false;
      onChange?.(false);
    }
  }
  function pointerUp() {
    if (!drawingRef.current) return;
    drawingRef.current = false;
    if (onStrokeEnd) {
      const c = canvasRef.current;
      if (!c || emptyRef.current) onStrokeEnd(null);
      else onStrokeEnd(c.toDataURL('image/png'));
    }
  }

  return (
    <div className="rounded-md border border-slate-300 bg-white p-1">
      <canvas
        ref={canvasRef}
        style={{ width: '100%', height: 120, touchAction: 'none' }}
        onPointerDown={pointerDown}
        onPointerMove={pointerMove}
        onPointerUp={pointerUp}
        onPointerLeave={pointerUp}
        aria-label="Draw your signature"
        className={disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-crosshair'}
      />
      <div className="flex items-center justify-between px-2 py-1 text-[11px] text-slate-500">
        <span>Draw your signature above{tick > 0 ? '' : ''}</span>
        <button
          type="button"
          onClick={() => {
            const c = canvasRef.current;
            if (!c) return;
            const ctx = c.getContext('2d');
            if (ctx) ctx.clearRect(0, 0, c.width, c.height);
            emptyRef.current = true;
            setTick((n) => n + 1);
            onChange?.(true);
          }}
          className="text-slate-500 hover:text-slate-800"
        >
          Clear
        </button>
      </div>
    </div>
  );
});

export default SignaturePad;
