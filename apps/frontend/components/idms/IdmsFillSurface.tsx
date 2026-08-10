'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  ArrowLeft,
  Cloud,
  CloudOff,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import toast from 'react-hot-toast';
import ConfirmDialog from '@/components/ConfirmDialog';
import InstanceRenderer from '@/components/idms/InstanceRenderer';
import FieldForm, { type FieldFormHandle } from '@/components/idms/FieldForm';
import { useSignatureBlobs } from '@/components/idms/useSignatureBlobs';
import {
  useCoalescedAutoSave,
  type SaveState,
} from '@/components/idms/useCoalescedAutoSave';
import { useFillCompleteness } from '@/components/idms/useFillCompleteness';
import {
  parseFieldSchema,
  type FieldSchemaEntry,
  type InstanceDetail,
} from '@/lib/careers/idms';

/**
 * ONE shared IDMS fill/sign surface for BOTH the ERM fill page and the
 * intern offer-letter fill/agreements page. Parity by construction —
 * every future fix to signature capture, font matching, completeness,
 * autosave, blocking-reason banner, or split-view layout lands on both
 * sides automatically because there is only one implementation.
 *
 * <h3>What lives here (once)</h3>
 * <ul>
 *   <li>Load / error / loading UI</li>
 *   <li>Header: back link, title, subtitle, completion counter,
 *       SaveIndicator, primary action button</li>
 *   <li>Blocking-reason banner</li>
 *   <li>Extra banners (RETURNED / REVOKED / anything role-supplied)</li>
 *   <li>Split-view grid: preview left (InstanceRenderer) + panel right
 *       (FieldForm + optional footer)</li>
 *   <li>Inline signature capture via FieldForm + InlineSignatureCapture</li>
 *   <li>Coalesced autosave, beforeUnload prompt, seed-last-saved-key</li>
 *   <li>Optimistic-signed tracking</li>
 *   <li>Send/Submit gate + confirm dialog + success toast</li>
 *   <li>Validate-on-click for missing required signatures / text fields</li>
 *   <li>In-preview signature-slot click → expand panel row + scroll</li>
 * </ul>
 *
 * <h3>What the config supplies (parameterised)</h3>
 * <ul>
 *   <li>{@link IdmsFillSurfaceConfig#role}: 'ERM' or 'INTERN' — drives
 *       ownership (which fields the viewer edits vs sees locked).</li>
 *   <li>{@link IdmsFillSurfaceConfig#resource}: bound API surface — the
 *       four calls (load, fill, sign, transition) with role-scoped paths.</li>
 *   <li>{@link IdmsFillSurfaceConfig#canEdit}: predicate over the loaded
 *       detail — ERM wants status==='DRAFT'; intern wants
 *       SENT_TO_INTERN or RETURNED.</li>
 *   <li>{@link IdmsFillSurfaceConfig#header} chrome: back link, subtitle
 *       (e.g. "For X · Y" or a status chip), optional title-area actions
 *       (e.g. Download executed copy).</li>
 *   <li>{@link IdmsFillSurfaceConfig#primaryAction}: label / toast /
 *       confirm-dialog copy for Send vs Submit.</li>
 *   <li>{@link IdmsFillSurfaceConfig#extraBanners}: optional banners
 *       (RETURNED / REVOKED — intern-only).</li>
 *   <li>{@link IdmsFillSurfaceConfig#fullPageOverride}: return non-null
 *       to replace the whole layout (e.g. ERM's "not in draft anymore"
 *       panel).</li>
 *   <li>{@link IdmsFillSurfaceConfig#signerName}: pre-fills the
 *       Generate-mode name input + typed-name field.</li>
 *   <li>Style knobs: {@link IdmsFillSurfaceConfig#containerMaxWidthClass},
 *       {@link IdmsFillSurfaceConfig#previewMaxHeightClass}.</li>
 * </ul>
 *
 * <p>Save/send race: autosave runs through {@link useCoalescedAutoSave}
 * — a single-slot queue that never drops the latest keystroke and never
 * fires two concurrent /fill calls. Transition explicitly flushes
 * pending saves via {@code flush(textValues)} and then posts the SAME
 * values on /send or /submit as the server-side authority — a final
 * keystroke can never be dropped between save and transition (F1
 * exemplar).</p>
 */

/** Bound POST body for /fill and /send | /submit. */
export interface IdmsFillPayload {
  values: Record<string, string>;
  expectedUpdatedAt?: number;
}

/** Bound POST body for /sign. */
export interface IdmsSignPayload {
  fieldId: string;
  signatureImageDataUrl: string;
  typedName: string;
}

/** Role-scoped API surface — the wrapper page pre-binds the four calls
 *  with the right URL prefix ('/api/v1/erm/idms/…' vs
 *  '/api/v1/intern/agreements/…'). The surface never sees the raw
 *  paths; it just calls these functions. */
export interface IdmsFillResource {
  load(): Promise<InstanceDetail>;
  fill(payload: IdmsFillPayload): Promise<InstanceDetail>;
  sign(payload: IdmsSignPayload): Promise<InstanceDetail>;
  /** Send (ERM) or Submit (intern). Some servers return an updated
   *  detail (intern's /submit does); others return 200 with no body
   *  (ERM's /send). Return the detail when available so the surface
   *  can setDetail; return void when it's a fire-and-forget. */
  transition(payload: IdmsFillPayload): Promise<InstanceDetail | void>;
}

export interface IdmsFillSurfaceConfig {
  instanceId: string;
  role: 'ERM' | 'INTERN';
  /** Full name of the current user — pre-fills the typed-name field +
   *  the Generate-mode name input in InlineSignatureCapture. */
  signerName?: string;

  resource: IdmsFillResource;

  /** Which loaded-detail statuses allow field editing. ERM:
   *  status==='DRAFT'. Intern: 'SENT_TO_INTERN' | 'RETURNED'. */
  canEdit(detail: InstanceDetail): boolean;

  /** Return non-null to replace the ENTIRE page render (used e.g. for
   *  ERM's "This document isn't in draft anymore" panel that
   *  short-circuits the whole layout). Return null to render the
   *  standard header + banners + grid + optional footer. */
  fullPageOverride?(detail: InstanceDetail): ReactNode | null;

  header: {
    backLink: { href: string; label: string };
    subtitle(detail: InstanceDetail): ReactNode;
    /** Extra action(s) to render inside the header's right cluster
     *  BEFORE the completion counter (e.g. Download executed copy). */
    titleActions?(detail: InstanceDetail): ReactNode;
  };

  primaryAction: {
    /** "Send to intern" / "Submit". */
    label: string;
    /** Icon rendered before the label; defaults to <Send/> if omitted. */
    icon?: ReactNode;
    successToast: string;
    confirmTitle: string;
    confirmDescription: string;
    confirmLabel: string;
    /** Called after a successful transition. Navigate to detail page,
     *  refresh state, whatever the caller needs. Receives the updated
     *  detail if the resource.transition returned one; else undefined. */
    onSuccess?(detail: InstanceDetail | undefined): void;
    /** Only shown to the caller when {@link IdmsFillSurfaceConfig#canEdit}
     *  is true. Intern's FINALIZED / REVOKED views don't render a
     *  primary action — the header actions handle those (e.g. Download).
     *  Defaults to true. */
    showWhenCantEdit?: boolean;
  };

  /** Rendered between the blocking-reason banner and the grid. Intern
   *  uses this for RETURNED + REVOKED banners; ERM omits (returns null). */
  extraBanners?(detail: InstanceDetail, canEdit: boolean): ReactNode;

  /** Small hint text below the panel (below FieldForm). */
  panelFooter?(detail: InstanceDetail): ReactNode;

  containerMaxWidthClass?: string;   // default 'max-w-6xl'
  previewMaxHeightClass?: string;    // default 'max-h-[calc(100vh-220px)]'
}

export default function IdmsFillSurface({ config }: { config: IdmsFillSurfaceConfig }) {
  const {
    role, signerName, resource, canEdit: canEditFn,
    fullPageOverride, header, primaryAction, extraBanners, panelFooter,
    containerMaxWidthClass = 'max-w-6xl',
    previewMaxHeightClass = 'max-h-[calc(100vh-220px)]',
  } = config;

  const [detail, setDetail] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [transitioning, setTransitioning] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const [textValues, setTextValues] = useState<Record<string, string>>({});
  // Signature currently expanded in the FieldForm panel — InlineSignatureCapture
  // renders directly beneath its row. Only one at a time; opening a
  // second row auto-closes the first (state is a single string).
  const [activeSignature, setActiveSignature] = useState<string | null>(null);
  const [focusedField, setFocusedField] = useState<string | null>(null);
  // Optimistic-signed: after /sign returns, the fieldId sits here until
  // the next detail refetch fills detail.values[id].signatureUrl.
  // Without this the completeness selector would flash "incomplete" for
  // ~200 ms after signing (F15).
  const [optimisticSigned, setOptimisticSigned] = useState<Set<string>>(new Set());

  const fieldFormRef = useRef<FieldFormHandle | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await resource.load();
      setDetail(data);
      const seed: Record<string, string> = {};
      for (const [k, v] of Object.entries(data.values ?? {})) {
        if (v.valueText != null && v.type !== 'signature') seed[k] = v.valueText;
      }
      setTextValues(seed);
      setOptimisticSigned(new Set());
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load document');
    } finally {
      setLoading(false);
    }
  }, [resource]);
  useEffect(() => { void load(); }, [load]);

  const fields: FieldSchemaEntry[] = useMemo(
    () => detail ? parseFieldSchema(detail.fieldSchemaJson) : [],
    [detail],
  );
  const signatureBlobs = useSignatureBlobs(detail);
  const canEdit = detail ? canEditFn(detail) : false;

  // Refs so persist() reads the FRESH detail (expectedUpdatedAt) and
  // field list without re-arming autosave on every server response,
  // and so persist knows whether editing is even allowed right now.
  const detailRef = useRef<InstanceDetail | null>(null);
  const fieldsRef = useRef<FieldSchemaEntry[]>([]);
  const canEditRef = useRef<boolean>(false);
  useEffect(() => { detailRef.current = detail; }, [detail]);
  useEffect(() => { fieldsRef.current = fields; }, [fields]);
  useEffect(() => { canEditRef.current = canEdit; }, [canEdit]);

  const completeness = useFillCompleteness({
    detail,
    fields,
    role,
    textValues,
    optimisticallySignedFieldIds: optimisticSigned,
  });

  // ── Autosave (single-slot coalesced) ─────────────────────────────
  const persist = useCallback(async (
    values: Record<string, string>,
  ): Promise<boolean> => {
    const currentDetail = detailRef.current;
    const currentFields = fieldsRef.current;
    if (!currentDetail || !canEditRef.current) return false;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== role) continue;
      if (f.type === 'signature') continue;
      payload[f.id] = values[f.id] ?? '';
    }
    if (Object.keys(payload).length === 0) return true;
    const data = await resource.fill({
      values: payload,
      expectedUpdatedAt: currentDetail.updatedAt
        ? Date.parse(currentDetail.updatedAt) || undefined
        : undefined,
    });
    setDetail(data);
    return true;
  }, [resource, role]);

  const saveController = useCoalescedAutoSave<Record<string, string>>({
    persist,
    enabled: canEdit,
    onConflict: () => { void load(); },
  });
  const { saveState, scheduleSave, flush, retry, seedLastSavedKey } = saveController;

  // Seed the last-saved key on load so an initial no-op scheduleSave
  // (from setTextValues in load()) is short-circuited.
  useEffect(() => {
    if (!detail) return;
    const currentFields = fieldsRef.current;
    const payload: Record<string, string> = {};
    for (const f of currentFields) {
      if (f.assignee !== role || f.type === 'signature') continue;
      payload[f.id] = textValues[f.id] ?? '';
    }
    seedLastSavedKey(payload);
    // Intentionally seeds only on detail identity (fresh load / server
    // response) — not on every textValues keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail, seedLastSavedKey]);

  function onTextChange(id: string, v: string) {
    setTextValues((p) => {
      const next = { ...p, [id]: v };
      scheduleSave(next);
      return next;
    });
  }

  // beforeUnload prompt during mid-debounce navigate/close.
  useEffect(() => {
    function beforeUnload(e: BeforeUnloadEvent) {
      if (saveState.kind === 'dirty' || saveState.kind === 'saving') {
        e.preventDefault();
        e.returnValue = '';
      }
    }
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [saveState]);

  // POST /sign for the row expanded via activeSignature. Called from
  // FieldForm row's InlineSignatureCapture. Success clears
  // activeSignature (collapses the inline section). On error the
  // inline section stays expanded and surfaces the message so the user
  // can retry without redrawing.
  async function submitSignature(
    fieldId: string, dataUrl: string, typedName: string,
  ): Promise<void> {
    if (!detail) return;
    const data = await resource.sign({
      fieldId, signatureImageDataUrl: dataUrl, typedName,
    });
    setDetail(data);
    setOptimisticSigned((s) => {
      const n = new Set(s);
      n.add(fieldId);
      return n;
    });
    setActiveSignature(null);
  }

  // Send/Submit validate-on-click. Missing required fields trigger a
  // scroll + toast; signatures are prioritised (they're the most-hunted
  // field type) and open the panel row's inline capture automatically.
  function openConfirm() {
    if (!detail) return;
    if (!completeness.canTransition) {
      const first =
        completeness.requiredMissing.find((f) => f.type === 'signature')
        ?? completeness.requiredMissing[0];
      if (first) {
        if (first.type === 'signature') {
          setActiveSignature(first.id);
          fieldFormRef.current?.focusField(first.id);
          toast.error('Please add your signature to continue.');
        } else {
          setFocusedField(first.id);
          fieldFormRef.current?.focusField(first.id);
          toast.error(completeness.blockingReason
            ?? `Please fill "${first.name}" first.`);
        }
      } else {
        toast.error(completeness.blockingReason ?? 'Complete required fields first.');
      }
      return;
    }
    setConfirmOpen(true);
  }

  async function runTransition() {
    if (!detail) return;
    setConfirmOpen(false);
    setTransitioning(true);
    try {
      // Flush any pending debounced save with the LATEST values so a
      // final keystroke can never be dropped between save and transition.
      const saved = await flush(textValues);
      if (!saved) {
        toast.error("Couldn't save your latest changes — please try again in a moment.");
        setTransitioning(false);
        return;
      }
      const latestUpdatedAt = detailRef.current?.updatedAt;
      const payload: Record<string, string> = {};
      for (const f of fields) {
        if (f.assignee !== role || f.type === 'signature') continue;
        payload[f.id] = textValues[f.id] ?? '';
      }
      const returned = await resource.transition({
        values: payload,
        expectedUpdatedAt: latestUpdatedAt
          ? Date.parse(latestUpdatedAt) || undefined
          : undefined,
      });
      // Server may return an updated detail (intern's /submit) or nothing
      // (ERM's /send). Reflect either uniformly.
      if (returned) setDetail(returned);
      toast.success(primaryAction.successToast);
      primaryAction.onSuccess?.(returned ?? undefined);
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
      };
      if (ax.response?.status === 409) {
        toast.error('This document changed elsewhere — reloading the latest state.');
        void load();
      } else {
        toast.error(ax.response?.data?.error ?? 'Action failed');
      }
      setTransitioning(false);
    }
  }

  function onFieldClickInPreview(fieldId: string) {
    if (!canEdit) return;
    const f = fields.find((x) => x.id === fieldId);
    if (!f) return;
    if (f.assignee === role && f.type !== 'signature') {
      fieldFormRef.current?.focusField(fieldId);
      setFocusedField(fieldId);
    }
  }

  function onSignatureAnchorClick(fieldId: string) {
    if (!canEdit) return;
    setActiveSignature(fieldId);
    fieldFormRef.current?.focusField(fieldId);
  }

  // ── Render branches ──────────────────────────────────────────────
  if (loading) {
    return (
      <div className={`mx-auto ${containerMaxWidthClass} p-6`}>
        <div className="h-64 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }
  if (err || !detail) {
    return (
      <div className={`mx-auto ${containerMaxWidthClass} p-6`}>
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err ?? 'Document not found.'}
        </div>
      </div>
    );
  }

  const override = fullPageOverride?.(detail);
  if (override) return <>{override}</>;

  const primaryDisabled = transitioning || saveState.kind === 'saving';
  const showPrimary = canEdit || primaryAction.showWhenCantEdit === true;

  return (
    <div className={`mx-auto ${containerMaxWidthClass} space-y-4`}>
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link
            href={header.backLink.href}
            className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            {header.backLink.label}
          </Link>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900">
            {detail.templateTitle}
          </h1>
          <p className="mt-0.5 text-xs text-slate-500">
            {header.subtitle(detail)}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {canEdit && (
            <span className="text-xs text-slate-500">
              {completeness.ownDone} of {completeness.ownTotal} complete
            </span>
          )}
          {canEdit && <SaveIndicator state={saveState} onRetry={retry} />}
          {header.titleActions?.(detail)}
          {showPrimary && (
            <button
              type="button"
              onClick={openConfirm}
              disabled={primaryDisabled}
              title={primaryReason(primaryDisabled, completeness.blockingReason, saveState, primaryAction.label)}
              className={`inline-flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-semibold text-white disabled:opacity-60 ${
                completeness.canTransition
                  ? 'bg-brand-700 hover:bg-brand-800'
                  : 'bg-slate-500 hover:bg-slate-600'
              }`}
            >
              {transitioning
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : (primaryAction.icon ?? <SendIcon />)}
              {primaryAction.label}
            </button>
          )}
        </div>
      </header>

      {canEdit && completeness.blockingReason && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{completeness.blockingReason}</p>
        </div>
      )}

      {extraBanners?.(detail, canEdit)}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
        {/* Preview column — border+rounded+shadow chrome only. The
            DocumentPreviewFrame inside InstanceRenderer supplies the
            slate canvas + white page-shadow so the doc renders exactly
            like the admin studio. Overflow lives on the InstanceRenderer
            wrapper so the shared p-6 canvas stays flush inside the
            chrome (no double padding, no colour fight). */}
        <section className="overflow-hidden rounded-lg border border-slate-200 shadow-sm">
          <div className={`${previewMaxHeightClass} overflow-y-auto`}>
            <InstanceRenderer
              detail={detail}
              fields={fields}
              editRole={canEdit ? role : null}
              textValues={textValues}
              signatureBlobs={signatureBlobs}
              focusedFieldId={focusedField}
              onFieldClick={canEdit ? onFieldClickInPreview : undefined}
              onSignatureClick={canEdit ? onSignatureAnchorClick : undefined}
              activeSignatureFieldId={activeSignature}
            />
          </div>
        </section>

        <aside className="space-y-4">
          {canEdit && (
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <FieldForm
                ref={fieldFormRef}
                detail={detail}
                fields={fields}
                role={role}
                textValues={textValues}
                onTextChange={onTextChange}
                onOpenSignature={(fid) => setActiveSignature(fid)}
                activeSignatureFieldId={activeSignature}
                focusedFieldId={focusedField}
                onFocusField={setFocusedField}
                signatureBlobs={signatureBlobs}
                onSaveSignature={submitSignature}
                onCancelSignature={() => setActiveSignature(null)}
                signerName={signerName ?? ''}
              />
              {panelFooter && (
                <div className="mt-4 text-[11px] text-slate-400">
                  {panelFooter(detail)}
                </div>
              )}
            </section>
          )}
        </aside>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={runTransition}
        title={primaryAction.confirmTitle}
        description={primaryAction.confirmDescription}
        confirmLabel={primaryAction.confirmLabel}
        cancelLabel="Cancel"
        variant="primary"
      />
    </div>
  );
}

// ── Local UI primitives ──────────────────────────────────────────────

/** Default primary-action icon (Send). Callers can override via
 *  {@link IdmsFillSurfaceConfig#primaryAction.icon}. */
function SendIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="2" strokeLinecap="round"
      strokeLinejoin="round" className="h-4 w-4"
    >
      <line x1="22" y1="2" x2="11" y2="13" />
      <polygon points="22 2 15 22 11 13 2 9 22 2" />
    </svg>
  );
}

function SaveIndicator({
  state,
  onRetry,
}: {
  state: SaveState;
  onRetry: () => void;
}) {
  if (state.kind === 'saving') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        Saving…
      </span>
    );
  }
  if (state.kind === 'dirty') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500">
        <Cloud className="h-3.5 w-3.5" />
        Unsaved changes
      </span>
    );
  }
  if (state.kind === 'saved') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-emerald-700">
        <Cloud className="h-3.5 w-3.5" />
        Saved
      </span>
    );
  }
  if (state.kind === 'error') {
    return (
      <span className="inline-flex items-center gap-2 text-xs text-red-700" title={state.message}>
        <span className="inline-flex items-center gap-1.5">
          <CloudOff className="h-3.5 w-3.5" />
          {state.message}
        </span>
        {state.retriable && (
          <button
            type="button"
            onClick={onRetry}
            className="inline-flex items-center gap-1 rounded border border-red-200 bg-white px-1.5 py-0.5 text-[11px] font-medium text-red-700 hover:bg-red-50"
          >
            <RefreshCw className="h-3 w-3" />
            Retry
          </button>
        )}
      </span>
    );
  }
  return null;
}

function primaryReason(
  disabled: boolean,
  blockingReason: string | null,
  state: SaveState,
  label: string,
): string {
  if (!disabled) return label;
  if (state.kind === 'saving') return `Saving your changes — ${label} unlocks in a moment.`;
  if (blockingReason) return blockingReason;
  return label;
}
