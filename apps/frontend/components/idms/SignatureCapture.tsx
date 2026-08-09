'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { PenLine, Upload, Type, Wand2, AlertCircle } from 'lucide-react';
import {
  Dancing_Script,
  Great_Vibes,
  Homemade_Apple,
  Sacramento,
  Alex_Brush,
} from 'next/font/google';
import { cn } from '@/lib/careers/cn';
import SignaturePad, { type SignaturePadHandle } from '@/components/idms/SignaturePad';

/**
 * Unified signature capture component — four modes behind a tab strip,
 * one output contract. Replaces every bare {@link SignaturePad} call site
 * in IDMS. The output is a PNG data URL bounded by the 500 KB cap the
 * backend {@code DocumentInstanceService.signField} enforces (we check on
 * the client too so the user gets a friendly message instead of a 400).
 *
 * <h3>Modes</h3>
 * <ol>
 *   <li><b>Draw</b> — the existing {@link SignaturePad} lifted verbatim.</li>
 *   <li><b>Upload</b> — file picker, PNG/JPG, preview, size + type checks.</li>
 *   <li><b>Generate</b> — name input + a row of script-font renderings,
 *       rasterised to PNG at signature resolution. Bundles 5 open-licensed
 *       Google fonts via {@code next/font/google}: Dancing Script (OFL),
 *       Great Vibes (OFL), Sacramento (OFL), Homemade Apple (Apache 2.0),
 *       Alex Brush (OFL).</li>
 *   <li><b>Upload & clean</b> — file picker + client-side canvas pipeline
 *       (grayscale → threshold → transparent background → auto-trim →
 *       contrast), live before/after preview, sensitivity slider.</li>
 * </ol>
 *
 * <p>Every mode calls {@link SignatureCaptureProps#onChange} with a PNG
 * data URL when the user has a signature ready, or {@code null} when
 * they clear it. The parent's Save button flips enabled off {@code null}.</p>
 */

const dancing = Dancing_Script({ subsets: ['latin'], weight: '600', display: 'swap' });
const vibes    = Great_Vibes({    subsets: ['latin'], weight: '400', display: 'swap' });
const sacra    = Sacramento({     subsets: ['latin'], weight: '400', display: 'swap' });
const homemade = Homemade_Apple({ subsets: ['latin'], weight: '400', display: 'swap' });
const alexBr   = Alex_Brush({     subsets: ['latin'], weight: '400', display: 'swap' });

const SIGNATURE_MAX_BYTES = 500 * 1024;
const UPLOAD_MAX_BYTES = 2 * 1024 * 1024; // raw file cap; the cleanup pipeline downsamples

const FONT_CHOICES = [
  { key: 'dancing',  label: 'Casual',   cssClass: dancing.className,  cssFamily: dancing.style.fontFamily },
  { key: 'vibes',    label: 'Elegant',  cssClass: vibes.className,    cssFamily: vibes.style.fontFamily },
  { key: 'sacra',    label: 'Refined',  cssClass: sacra.className,    cssFamily: sacra.style.fontFamily },
  { key: 'homemade', label: 'Personal', cssClass: homemade.className, cssFamily: homemade.style.fontFamily },
  { key: 'alex',     label: 'Formal',   cssClass: alexBr.className,   cssFamily: alexBr.style.fontFamily },
] as const;

type Mode = 'draw' | 'upload' | 'generate' | 'clean';

export interface SignatureCaptureHandle {
  /** Clear the current staged signature. */
  reset(): void;
}

export interface SignatureCaptureProps {
  /** Pre-fills the Generate mode's name input. */
  initialName?: string;
  /** Called whenever the staged signature changes (any mode). {@code null}
   *  means "no signature ready" — parent disables Save. */
  onChange: (dataUrl: string | null) => void;
  disabled?: boolean;
}

const SignatureCapture = forwardRef<SignatureCaptureHandle, SignatureCaptureProps>(
  function SignatureCapture({ initialName, onChange, disabled }, ref) {
    const [mode, setMode] = useState<Mode>('draw');
    // Track per-mode staged signature so switching tabs doesn't lose work.
    const [drawUrl, setDrawUrl] = useState<string | null>(null);
    const [uploadUrl, setUploadUrl] = useState<string | null>(null);
    const [generateUrl, setGenerateUrl] = useState<string | null>(null);
    const [cleanUrl, setCleanUrl] = useState<string | null>(null);

    const active = useMemo(() => {
      switch (mode) {
        case 'draw':     return drawUrl;
        case 'upload':   return uploadUrl;
        case 'generate': return generateUrl;
        case 'clean':    return cleanUrl;
      }
    }, [mode, drawUrl, uploadUrl, generateUrl, cleanUrl]);

    // Ref-wrap onChange so the effect below only re-fires when `active`
    // actually changes — otherwise a parent that recreates the onChange
    // closure on every render would spam onChange (F19-class effect-dep
    // footgun). Callers can freely pass inline arrows now.
    const onChangeRef = useRef(onChange);
    useEffect(() => { onChangeRef.current = onChange; }, [onChange]);

    // Report the active mode's staged URL to the parent whenever it changes.
    useEffect(() => {
      onChangeRef.current(active);
    }, [active]);

    useImperativeHandle(ref, () => ({
      reset() {
        setDrawUrl(null);
        setUploadUrl(null);
        setGenerateUrl(null);
        setCleanUrl(null);
      },
    }), []);

    const tabs: { key: Mode; label: string; icon: React.ReactNode }[] = [
      { key: 'draw',     label: 'Draw',            icon: <PenLine className="h-3.5 w-3.5" /> },
      { key: 'upload',   label: 'Upload',          icon: <Upload  className="h-3.5 w-3.5" /> },
      { key: 'generate', label: 'Generate',        icon: <Type    className="h-3.5 w-3.5" /> },
      { key: 'clean',    label: 'Upload & clean',  icon: <Wand2   className="h-3.5 w-3.5" /> },
    ];

    return (
      <div className="rounded-md border border-slate-200 bg-white">
        <div
          role="tablist"
          aria-label="Signature capture method"
          className="flex flex-wrap gap-1 border-b border-slate-100 p-1"
        >
          {tabs.map((t) => {
            const isActive = mode === t.key;
            return (
              <button
                key={t.key}
                role="tab"
                aria-selected={isActive}
                aria-controls={`sig-panel-${t.key}`}
                id={`sig-tab-${t.key}`}
                type="button"
                onClick={() => setMode(t.key)}
                disabled={disabled}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition',
                  isActive
                    ? 'bg-brand-700 text-white'
                    : 'text-slate-700 hover:bg-slate-100',
                )}
              >
                {t.icon}
                {t.label}
              </button>
            );
          })}
        </div>

        <div
          role="tabpanel"
          id={`sig-panel-${mode}`}
          aria-labelledby={`sig-tab-${mode}`}
          className="p-3"
        >
          {mode === 'draw' && (
            <DrawTab
              disabled={disabled}
              onChange={setDrawUrl}
            />
          )}
          {mode === 'upload' && (
            <UploadTab
              disabled={disabled}
              value={uploadUrl}
              onChange={setUploadUrl}
            />
          )}
          {mode === 'generate' && (
            <GenerateTab
              disabled={disabled}
              initialName={initialName ?? ''}
              value={generateUrl}
              onChange={setGenerateUrl}
            />
          )}
          {mode === 'clean' && (
            <CleanTab
              disabled={disabled}
              value={cleanUrl}
              onChange={setCleanUrl}
            />
          )}
        </div>
      </div>
    );
  },
);

export default SignatureCapture;

// ── Mode: Draw ──────────────────────────────────────────────────────

function DrawTab({ disabled, onChange }: {
  disabled?: boolean;
  onChange: (dataUrl: string | null) => void;
}) {
  const padRef = useRef<SignaturePadHandle | null>(null);
  const [empty, setEmpty] = useState(true);
  return (
    <div>
      {/* Explicit label above the pad — without this the empty white box
          reads as "unknown widget" and users don't realise they can draw
          into it. Kept small so it doesn't crowd the pad but visible enough
          to answer "what am I supposed to do here?" at a glance. */}
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-medium text-slate-600">
        <PenLine className="h-3 w-3 text-slate-500" />
        Draw your signature here
      </div>
      <SignaturePad
        ref={padRef}
        disabled={disabled}
        onChange={(e) => setEmpty(e)}
        onStrokeEnd={(url) => {
          if (url && !underSizeCap(url)) {
            onChange(null);
          } else {
            onChange(url);
          }
        }}
      />
      {empty ? (
        <p className="mt-2 text-[11px] text-slate-400">
          Use your mouse, trackpad, or finger to sign. If drawing is awkward,
          try the Upload, Generate, or Upload &amp; clean tabs above.
        </p>
      ) : (
        <p className="mt-2 text-[11px] text-slate-500">
          Draw again to replace, or switch tabs to try a different method.
        </p>
      )}
    </div>
  );
}

// ── Mode: Upload ────────────────────────────────────────────────────

function UploadTab({ disabled, value, onChange }: {
  disabled?: boolean;
  value: string | null;
  onChange: (dataUrl: string | null) => void;
}) {
  const [err, setErr] = useState<string | null>(null);

  function handleFile(f: File) {
    setErr(null);
    if (!/^image\/(png|jpe?g)$/i.test(f.type)) {
      setErr('Please pick a PNG or JPG image.');
      return;
    }
    if (f.size > UPLOAD_MAX_BYTES) {
      setErr('That file is over 2 MB — try a smaller image or use Upload & clean.');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const raw = String(reader.result ?? '');
      // For plain Upload we honour the source bitmap as-is. If the source
      // is huge (typically a phone photo of a signed sheet), advise the
      // Upload & clean tab.
      if (raw && !underSizeCap(raw)) {
        setErr('Image is over the 500 KB cap — try Upload & clean to shrink it.');
        onChange(null);
        return;
      }
      onChange(raw);
    };
    reader.readAsDataURL(f);
  }

  return (
    <div className="space-y-2">
      <label className="flex cursor-pointer items-center justify-center rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-6 text-xs font-medium text-slate-600 hover:bg-slate-100">
        <input
          type="file"
          accept="image/png,image/jpeg"
          className="hidden"
          disabled={disabled}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) handleFile(f);
          }}
        />
        <Upload className="mr-2 h-3.5 w-3.5" />
        {value ? 'Replace signature image' : 'Choose a PNG or JPG'}
      </label>
      {value && (
        <div className="rounded-md border border-slate-200 bg-white p-2">
          <img
            src={value}
            alt="Signature preview"
            className="mx-auto max-h-24"
          />
        </div>
      )}
      {err && (
        <p className="flex items-start gap-1.5 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          {err}
        </p>
      )}
    </div>
  );
}

// ── Mode: Generate ──────────────────────────────────────────────────

function GenerateTab({ disabled, initialName, value, onChange }: {
  disabled?: boolean;
  initialName: string;
  value: string | null;
  onChange: (dataUrl: string | null) => void;
}) {
  const [name, setName] = useState(initialName);
  const [pickedKey, setPickedKey] = useState<string | null>(null);

  // Re-generate the PNG whenever the name or font pick changes.
  useEffect(() => {
    if (!name.trim() || !pickedKey) {
      onChange(null);
      return;
    }
    const font = FONT_CHOICES.find((f) => f.key === pickedKey);
    if (!font) return;
    let cancelled = false;
    void (async () => {
      const url = await renderNameToPng(name.trim(), font.cssFamily);
      if (cancelled) return;
      if (url && !underSizeCap(url)) {
        onChange(null);
        return;
      }
      onChange(url);
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, pickedKey]);

  return (
    <div className="space-y-3">
      <div>
        <label className="text-xs font-medium text-slate-700">Signer's name</label>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={disabled}
          placeholder="Your name"
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
        />
      </div>
      <div>
        <p className="text-xs font-medium text-slate-700">Pick a style</p>
        <ul className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
          {FONT_CHOICES.map((f) => {
            const picked = pickedKey === f.key;
            return (
              <li key={f.key}>
                <button
                  type="button"
                  onClick={() => setPickedKey(f.key)}
                  disabled={disabled}
                  className={cn(
                    'w-full rounded-md border p-2 text-left transition',
                    picked
                      ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500'
                      : 'border-slate-200 bg-white hover:bg-slate-50',
                  )}
                >
                  <span
                    className={cn(f.cssClass, 'block truncate text-xl leading-tight text-slate-900')}
                  >
                    {name.trim() || 'Your name here'}
                  </span>
                  <span className="mt-1 block text-[10px] uppercase tracking-wide text-slate-400">
                    {f.label}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
      {value && (
        <div className="rounded-md border border-slate-200 bg-white p-2">
          <p className="mb-1 text-[10px] uppercase tracking-wide text-slate-400">Preview</p>
          <img src={value} alt="Generated signature" className="mx-auto max-h-16" />
        </div>
      )}
    </div>
  );
}

async function renderNameToPng(name: string, fontFamily: string): Promise<string | null> {
  try {
    // Warm the font — Chrome / Safari won't paint an unloaded font on
    // the first canvas draw, and we'd get Times instead. Fine to await
    // even if the font is already loaded.
    if (typeof document !== 'undefined' && document.fonts) {
      await document.fonts.load(`48px ${fontFamily}`);
    }
  } catch { /* fall through — some browsers reject the load promise */ }
  const canvas = document.createElement('canvas');
  canvas.width = 420;
  canvas.height = 96;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#0f172a';
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'center';
  // Try progressively smaller sizes until the name fits within the canvas.
  let size = 56;
  while (size > 22) {
    ctx.font = `${size}px ${fontFamily}`;
    const w = ctx.measureText(name).width;
    if (w < canvas.width - 24) break;
    size -= 3;
  }
  ctx.font = `${size}px ${fontFamily}`;
  ctx.fillText(name, canvas.width / 2, canvas.height / 2);
  return canvas.toDataURL('image/png');
}

// ── Mode: Upload & clean ────────────────────────────────────────────

function CleanTab({ disabled, value, onChange }: {
  disabled?: boolean;
  value: string | null;
  onChange: (dataUrl: string | null) => void;
}) {
  const [rawUrl, setRawUrl] = useState<string | null>(null);
  const [threshold, setThreshold] = useState<number>(180); // higher = more permissive to lighter strokes
  // debouncedThreshold trails threshold by ~120 ms so a slider drag
  // (which fires ~30 change events for a full sweep) doesn't re-run the
  // full grayscale/threshold/trim pipeline on every step (F18). The
  // number the user sees updates immediately; the actual pipeline waits.
  const [debouncedThreshold, setDebouncedThreshold] = useState<number>(180);
  useEffect(() => {
    const t = setTimeout(() => setDebouncedThreshold(threshold), 120);
    return () => clearTimeout(t);
  }, [threshold]);
  const [darkPct, setDarkPct] = useState<number>(0);       // rough "how much ink survived" gauge
  const [err, setErr] = useState<string | null>(null);
  const beforeRef = useRef<HTMLCanvasElement | null>(null);
  const afterRef  = useRef<HTMLCanvasElement | null>(null);

  function handleFile(f: File) {
    setErr(null);
    if (!/^image\/(png|jpe?g)$/i.test(f.type)) {
      setErr('Please pick a PNG or JPG image.');
      return;
    }
    if (f.size > UPLOAD_MAX_BYTES) {
      setErr('Image is over 2 MB — please shrink it before uploading.');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const url = String(reader.result ?? '');
      setRawUrl(url);
    };
    reader.readAsDataURL(f);
  }

  // Re-run the cleanup whenever the raw image or (debounced) threshold changes.
  // Also refs onChange so a parent re-render doesn't re-fire this pipeline
  // just because the onChange closure identity flipped.
  const cleanOnChangeRef = useRef(onChange);
  useEffect(() => { cleanOnChangeRef.current = onChange; }, [onChange]);
  useEffect(() => {
    if (!rawUrl) {
      cleanOnChangeRef.current(null);
      setDarkPct(0);
      return;
    }
    let cancelled = false;
    void (async () => {
      const img = new Image();
      img.onload = () => {
        if (cancelled) return;
        // Cap the working canvas so a 12MP phone photo doesn't explode.
        const MAX_DIM = 800;
        const scale = Math.min(1, MAX_DIM / Math.max(img.width, img.height));
        const w = Math.max(1, Math.round(img.width * scale));
        const h = Math.max(1, Math.round(img.height * scale));

        const before = beforeRef.current;
        if (before) {
          before.width = w; before.height = h;
          const bctx = before.getContext('2d');
          bctx?.drawImage(img, 0, 0, w, h);
        }

        const off = document.createElement('canvas');
        off.width = w; off.height = h;
        const octx = off.getContext('2d');
        if (!octx) return;
        octx.drawImage(img, 0, 0, w, h);
        const data = octx.getImageData(0, 0, w, h);
        const px = data.data;

        // 1) Grayscale + threshold + transparent background.
        // 2) Track the bounding box of dark pixels so we can auto-trim.
        let minX = w, minY = h, maxX = -1, maxY = -1;
        let dark = 0;
        for (let y = 0; y < h; y++) {
          for (let x = 0; x < w; x++) {
            const i = (y * w + x) * 4;
            const r = px[i], g = px[i + 1], b = px[i + 2];
            const gray = 0.299 * r + 0.587 * g + 0.114 * b;
            if (gray <= debouncedThreshold) {
              // Ink stroke — keep as near-black, boost contrast a bit.
              const contrast = Math.max(0, gray - 30);
              px[i]     = contrast;
              px[i + 1] = contrast;
              px[i + 2] = contrast;
              px[i + 3] = 255;
              dark++;
              if (x < minX) minX = x;
              if (y < minY) minY = y;
              if (x > maxX) maxX = x;
              if (y > maxY) maxY = y;
            } else {
              // Background — fully transparent.
              px[i + 3] = 0;
            }
          }
        }
        octx.putImageData(data, 0, 0);
        setDarkPct((dark / (w * h)) * 100);

        // Auto-trim to the bounding box (with a small padding).
        let trimmed = off;
        if (maxX >= minX && maxY >= minY) {
          const pad = 6;
          const tx = Math.max(0, minX - pad);
          const ty = Math.max(0, minY - pad);
          const tw = Math.min(w, maxX - tx + pad);
          const th = Math.min(h, maxY - ty + pad);
          const tc = document.createElement('canvas');
          tc.width = tw; tc.height = th;
          const tctx = tc.getContext('2d');
          if (tctx) {
            tctx.drawImage(off, tx, ty, tw, th, 0, 0, tw, th);
            trimmed = tc;
          }
        }

        const after = afterRef.current;
        if (after) {
          after.width = trimmed.width;
          after.height = trimmed.height;
          const actx = after.getContext('2d');
          actx?.drawImage(trimmed, 0, 0);
        }

        const outUrl = trimmed.toDataURL('image/png');
        if (!underSizeCap(outUrl)) {
          setErr('Cleaned image is still over the 500 KB cap — try a lower threshold.');
          cleanOnChangeRef.current(null);
        } else {
          setErr(null);
          cleanOnChangeRef.current(outUrl);
        }
      };
      img.onerror = () => setErr('Could not read that image.');
      img.src = rawUrl;
    })();
    return () => { cancelled = true; };
  }, [rawUrl, debouncedThreshold]);

  const looksEmpty = rawUrl != null && darkPct < 0.2;
  const looksNoisy = rawUrl != null && darkPct > 30;

  return (
    <div className="space-y-3">
      <label className="flex cursor-pointer items-center justify-center rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-4 text-xs font-medium text-slate-600 hover:bg-slate-100">
        <input
          type="file"
          accept="image/png,image/jpeg"
          className="hidden"
          disabled={disabled}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) handleFile(f);
          }}
        />
        <Upload className="mr-2 h-3.5 w-3.5" />
        {rawUrl ? 'Replace source image' : 'Choose a photo or scan of your signature'}
      </label>

      {rawUrl && (
        <>
          <div>
            <label className="text-xs font-medium text-slate-700">
              Ink sensitivity <span className="text-slate-400">({threshold})</span>
            </label>
            <input
              type="range"
              min={80}
              max={230}
              step={5}
              value={threshold}
              onChange={(e) => setThreshold(Number(e.target.value))}
              disabled={disabled}
              className="mt-1 w-full"
            />
            <p className="mt-1 text-[11px] text-slate-400">
              Slide right if the signature is faint; left if the background is bleeding through.
            </p>
          </div>

          <div className="grid grid-cols-2 gap-2 text-[10px] uppercase tracking-wide text-slate-400">
            <div>
              <p className="mb-1">Before</p>
              <div className="rounded-md border border-slate-200 bg-slate-100 p-1">
                <canvas ref={beforeRef} className="mx-auto block max-h-24 max-w-full" />
              </div>
            </div>
            <div>
              <p className="mb-1">After</p>
              <div className="rounded-md border border-slate-200 bg-white p-1 bg-[linear-gradient(45deg,#f8fafc_25%,transparent_25%,transparent_75%,#f8fafc_75%,#f8fafc),linear-gradient(45deg,#f8fafc_25%,transparent_25%,transparent_75%,#f8fafc_75%,#f8fafc)] bg-[length:12px_12px] bg-[position:0_0,6px_6px]">
                <canvas ref={afterRef} className="mx-auto block max-h-24 max-w-full" />
              </div>
            </div>
          </div>

          {looksEmpty && (
            <p className="flex items-start gap-1.5 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              We couldn't find much ink after cleanup. Slide the sensitivity right, try a clearer
              photo, or use the Draw or Generate tab instead.
            </p>
          )}
          {looksNoisy && (
            <p className="flex items-start gap-1.5 rounded-md border border-slate-200 bg-slate-50 p-2 text-xs text-slate-600">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              Too much background is coming through — slide the sensitivity left.
            </p>
          )}
          {value && !looksEmpty && !looksNoisy && !err && (
            <p className="text-[11px] text-emerald-700">
              Looks good — this is what will be saved.
            </p>
          )}
          {err && (
            <p className="flex items-start gap-1.5 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              {err}
            </p>
          )}
        </>
      )}
    </div>
  );
}

// ── Common ─────────────────────────────────────────────────────────

function underSizeCap(dataUrl: string): boolean {
  // rough byte estimate: base64 is ~4/3 the raw byte count
  const commaIdx = dataUrl.indexOf(',');
  const payload = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;
  const bytes = Math.floor(payload.length * 0.75);
  return bytes <= SIGNATURE_MAX_BYTES;
}
