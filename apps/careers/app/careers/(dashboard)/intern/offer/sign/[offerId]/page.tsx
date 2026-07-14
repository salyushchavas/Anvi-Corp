'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  AlertCircle,
  CheckCircle2,
  Eraser,
  FileText,
  Loader2,
  PenLine,
} from 'lucide-react';
import api from '@/lib/api';
import { BRAND } from '@/lib/brand';
import ProtectedRoute from '@/components/ProtectedRoute';
import InternPageShell from '@/components/intern/InternPageShell';

type OfferStatus = 'DRAFT' | 'SENT' | 'SIGNED' | 'EXPIRED' | 'VOIDED' | 'DECLINED';

interface ApplicantOfferView {
  offerId: string;
  status: OfferStatus;
  applicantFullName: string | null;
  applicantId: string | null;
  applicantEmail: string | null;
  roleTitle: string | null;
  jobTitle: string | null;
  compensationSummary: string | null;
  worksite: string | null;
  expectedHoursPerWeek: number | null;
  tentativeStartDate: string | null;
  expiresAt: string | null;
  sentAt: string | null;
  signedAt: string | null;
  signedByTypedName: string | null;
  signedSignatureImage: string | null;
  letterContent: string | null;
  contingencies: string | null;
}

export default function InternOfferSignPage() {
  const params = useParams<{ offerId: string }>();
  const router = useRouter();
  const offerId = params?.offerId ?? '';

  const [offer, setOffer] = useState<ApplicantOfferView | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [agreed, setAgreed] = useState(false);
  const [hasSignature, setHasSignature] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitErr, setSubmitErr] = useState<string | null>(null);
  const padRef = useRef<SignaturePadHandle | null>(null);

  const load = useCallback(async () => {
    if (!offerId) return;
    setLoading(true);
    try {
      const res = await api.get<ApplicantOfferView>(
        `/api/v1/applicant/offers/${offerId}`,
      );
      setOffer(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } } };
      if (ax.response?.status === 403) {
        router.replace('/careers/intern?toast=offer-not-found');
        return;
      }
      setErr(ax.response?.data?.error ?? (e instanceof Error ? e.message : 'Failed to load offer'));
    } finally {
      setLoading(false);
    }
  }, [offerId, router]);

  useEffect(() => { void load(); }, [load]);

  const canSubmit = hasSignature && agreed && !submitting;

  async function submit() {
    if (!canSubmit || !offer || !padRef.current) return;
    const dataUrl = padRef.current.toDataURL();
    if (!dataUrl) {
      setSubmitErr('Please draw your signature in the box above.');
      return;
    }
    setSubmitting(true);
    setSubmitErr(null);
    try {
      await api.post(`/api/v1/applicant/offers/${offer.offerId}/sign`, {
        signatureImage: dataUrl,
      });
      router.replace('/careers/intern?toast=offer-signed');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setSubmitErr(ax.response?.data?.error ?? (e instanceof Error ? e.message : 'Sign failed'));
    } finally {
      setSubmitting(false);
    }
  }

  const expired = useMemo(() => {
    if (!offer?.expiresAt) return false;
    return new Date(offer.expiresAt).getTime() < Date.now();
  }, [offer]);

  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <InternPageShell title="Offer of Internship" subtitle="Review and sign below.">
        {loading ? (
          <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
        ) : err ? (
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err}
          </p>
        ) : !offer ? (
          <p className="text-sm text-slate-500">Offer not found.</p>
        ) : (
          <div className="space-y-6">
            {/* Status banners */}
            {offer.status === 'SIGNED' && (
              <div className="rounded-lg border border-green-200 bg-green-50 p-4 text-sm text-green-900">
                <div className="flex items-start gap-3">
                  <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-green-600" />
                  <div className="flex-1">
                    <p className="font-semibold">
                      You signed this offer
                      {offer.signedAt ? ` on ${new Date(offer.signedAt).toLocaleString()}` : ''}.
                    </p>
                    <p className="mt-1 text-xs">
                      Welcome to {BRAND.name}!
                      {offer.signedByTypedName && ` Signed as ${offer.signedByTypedName}.`}
                    </p>
                  </div>
                </div>
                {offer.signedSignatureImage && (
                  <div className="mt-3 rounded-md border border-green-200 bg-white p-3">
                    <p className="text-[10px] uppercase tracking-wide text-green-700">
                      Your signature
                    </p>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={offer.signedSignatureImage}
                      alt="Your signature"
                      className="mt-1 max-h-32 w-auto"
                    />
                  </div>
                )}
              </div>
            )}
            {(offer.status === 'EXPIRED' || (offer.status === 'SENT' && expired)) && (
              <div className="flex items-start gap-3 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-900">
                <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-red-600" />
                <div>
                  <p className="font-semibold">
                    This offer expired
                    {offer.expiresAt ? ` on ${new Date(offer.expiresAt).toLocaleDateString()}` : ''}.
                  </p>
                  <p className="mt-1 text-xs">Please contact ERM to discuss next steps.</p>
                </div>
              </div>
            )}
            {(offer.status === 'VOIDED' || offer.status === 'DECLINED') && (
              <div className="flex items-start gap-3 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
                <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-slate-500" />
                <div>
                  <p className="font-semibold">
                    This offer is no longer active (status: {offer.status}).
                  </p>
                  <p className="mt-1 text-xs">Please contact ERM if you have questions.</p>
                </div>
              </div>
            )}

            {/* Offer letter body */}
            <article className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
              <header className="mb-4 flex items-center gap-2 border-b border-slate-100 pb-3">
                <FileText className="h-5 w-5 text-brand-700" />
                <h2 className="text-base font-semibold text-slate-900">
                  {BRAND.name} — Offer Letter
                </h2>
              </header>

              {offer.letterContent ? (
                <pre className="whitespace-pre-wrap font-sans text-sm leading-6 text-slate-800">
                  {offer.letterContent}
                </pre>
              ) : (
                <DefaultLetter offer={offer} />
              )}
            </article>

            {/* Signing block — only when actionable */}
            {offer.status === 'SENT' && !expired && (
              <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
                <div className="flex items-center gap-2">
                  <PenLine className="h-5 w-5 text-brand-700" />
                  <h3 className="text-sm font-semibold text-slate-900">Sign this offer</h3>
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  Draw your signature in the box below, tick the agreement, and click
                  <strong> Sign Offer</strong>. By signing you indicate your acceptance of
                  this offer and your intent to be electronically bound by its terms.
                </p>

                <div className="mt-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-slate-800">
                      Your signature
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        padRef.current?.clear();
                        setHasSignature(false);
                      }}
                      className="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-800"
                    >
                      <Eraser className="h-3 w-3" />
                      Clear
                    </button>
                  </div>
                  <SignaturePad
                    ref={padRef}
                    onChange={(isEmpty) => setHasSignature(!isEmpty)}
                  />
                  <p className="mt-1 text-[11px] text-slate-500">
                    Signed as <strong>{offer.applicantFullName ?? 'you'}</strong>.
                  </p>
                </div>

                <label className="mt-4 flex items-start gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={agreed}
                    onChange={(e) => setAgreed(e.target.checked)}
                    className="mt-0.5 h-4 w-4 cursor-pointer rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                  />
                  <span>I have read and agree to the terms of this offer.</span>
                </label>

                {submitErr && (
                  <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                    {submitErr}
                  </p>
                )}

                <div className="mt-4 flex items-center justify-between gap-3">
                  <p className="text-[11px] text-slate-500">
                    {!hasSignature
                      ? 'Draw your signature above to enable Sign Offer.'
                      : !agreed
                        ? 'Tick the agreement to enable Sign Offer.'
                        : 'Ready to sign.'}
                  </p>
                  <button
                    type="button"
                    onClick={submit}
                    disabled={!canSubmit}
                    className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                    {submitting ? 'Signing…' : 'Sign Offer'}
                  </button>
                </div>
              </section>
            )}

            <footer className="text-center text-xs text-slate-500">
              Need help? Contact your ERM at{' '}
              <a className="text-brand-700 hover:underline" href="mailto:erm@skyzentech.com">
                erm@skyzentech.com
              </a>
              .{' '}
              <Link href="/careers/intern" className="text-brand-700 hover:underline">
                Back to dashboard
              </Link>
            </footer>
          </div>
        )}
      </InternPageShell>
    </ProtectedRoute>
  );
}

function DefaultLetter({ offer }: { offer: ApplicantOfferView }) {
  const fieldRows: Array<[string, string | null]> = [
    ['Role', offer.roleTitle ?? offer.jobTitle ?? '—'],
    ['Applicant ID', offer.applicantId ?? '—'],
    ['Compensation', offer.compensationSummary ?? '—'],
    ['Tentative start date', offer.tentativeStartDate ?? '—'],
    ['Worksite', offer.worksite ?? '—'],
    ['Expected hours', offer.expectedHoursPerWeek != null ? `${offer.expectedHoursPerWeek}/week` : '—'],
    ['Expires on', offer.expiresAt ? new Date(offer.expiresAt).toLocaleDateString() : '—'],
  ];
  return (
    <div className="space-y-4 text-sm leading-6 text-slate-800">
      <p>Hello {offer.applicantFullName ?? 'there'},</p>
      <p>{BRAND.name} is pleased to extend the following internship offer:</p>
      <dl className="grid grid-cols-[10rem_1fr] gap-y-1 rounded-md border border-slate-200 bg-slate-50 p-4 text-sm">
        {fieldRows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="font-medium text-slate-600">{k}</dt>
            <dd className="text-slate-900">{v}</dd>
          </div>
        ))}
      </dl>
      {offer.contingencies && (
        <p className="text-slate-700">{offer.contingencies}</p>
      )}
      <p>
        This offer expires on{' '}
        <strong>
          {offer.expiresAt ? new Date(offer.expiresAt).toLocaleDateString() : 'the date above'}
        </strong>
        . Please review and sign below before then.
      </p>
      <p>
        By drawing your signature in the signing block below and clicking <em>Sign Offer</em>, you
        indicate your acceptance of this offer and your intent to be electronically bound by its
        terms.
      </p>
    </div>
  );
}

// ── Signature pad ─────────────────────────────────────────────────────────
// Minimal canvas-based signature capture. Pointer events cover mouse,
// touch, and pen; HiDPI-aware via devicePixelRatio scaling; exports to
// a PNG data URL. No external dependency.

interface SignaturePadHandle {
  clear: () => void;
  isEmpty: () => boolean;
  toDataURL: () => string | null;
}

interface SignaturePadProps {
  onChange?: (isEmpty: boolean) => void;
}

const SignaturePad = forwardRef<SignaturePadHandle, SignaturePadProps>(
  function SignaturePad({ onChange }, ref) {
    const canvasRef = useRef<HTMLCanvasElement | null>(null);
    const drawingRef = useRef(false);
    const lastRef = useRef<{ x: number; y: number } | null>(null);
    const emptyRef = useRef(true);

    function getCtx(): CanvasRenderingContext2D | null {
      const c = canvasRef.current;
      return c ? c.getContext('2d') : null;
    }

    useEffect(() => {
      function setSize() {
        const c = canvasRef.current;
        if (!c) return;
        const rect = c.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        // Preserve drawing across resize by snapshotting + re-blitting.
        const snap = emptyRef.current ? null : c.toDataURL();
        c.width = Math.round(rect.width * dpr);
        c.height = Math.round(rect.height * dpr);
        const ctx = getCtx();
        if (!ctx) return;
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.lineWidth = 2;
        ctx.strokeStyle = '#0f172a';
        if (snap) {
          const img = new Image();
          img.onload = () => {
            ctx.drawImage(img, 0, 0, rect.width, rect.height);
          };
          img.src = snap;
        }
      }
      setSize();
      const ro = new ResizeObserver(setSize);
      if (canvasRef.current) ro.observe(canvasRef.current);
      return () => ro.disconnect();
    }, []);

    function pointFromEvent(e: ReactPointerEvent<HTMLCanvasElement>): { x: number; y: number } {
      const c = canvasRef.current!;
      const rect = c.getBoundingClientRect();
      return { x: e.clientX - rect.left, y: e.clientY - rect.top };
    }

    function start(e: ReactPointerEvent<HTMLCanvasElement>) {
      e.preventDefault();
      const c = canvasRef.current;
      if (!c) return;
      try { c.setPointerCapture(e.pointerId); } catch { /* ignore */ }
      drawingRef.current = true;
      lastRef.current = pointFromEvent(e);
    }

    function move(e: ReactPointerEvent<HTMLCanvasElement>) {
      if (!drawingRef.current) return;
      e.preventDefault();
      const ctx = getCtx();
      const p = pointFromEvent(e);
      const last = lastRef.current;
      if (!ctx || !last) return;
      ctx.beginPath();
      ctx.moveTo(last.x, last.y);
      ctx.lineTo(p.x, p.y);
      ctx.stroke();
      lastRef.current = p;
      if (emptyRef.current) {
        emptyRef.current = false;
        onChange?.(false);
      }
    }

    function end(e: ReactPointerEvent<HTMLCanvasElement>) {
      const c = canvasRef.current;
      if (c) {
        try { c.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
      }
      drawingRef.current = false;
      lastRef.current = null;
    }

    useImperativeHandle(ref, () => ({
      clear() {
        const c = canvasRef.current;
        const ctx = getCtx();
        if (!c || !ctx) return;
        const rect = c.getBoundingClientRect();
        ctx.clearRect(0, 0, rect.width, rect.height);
        emptyRef.current = true;
        onChange?.(true);
      },
      isEmpty() {
        return emptyRef.current;
      },
      toDataURL() {
        const c = canvasRef.current;
        if (!c || emptyRef.current) return null;
        return c.toDataURL('image/png');
      },
    }), [onChange]);

    return (
      <canvas
        ref={canvasRef}
        onPointerDown={start}
        onPointerMove={move}
        onPointerUp={end}
        onPointerLeave={end}
        onPointerCancel={end}
        className="mt-1 block h-40 w-full touch-none cursor-crosshair rounded-md border border-slate-300 bg-white"
      />
    );
  },
);
