'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  Bookmark,
  CheckCircle2,
  ChevronDown,
  Edit3,
  Eye,
  FileText,
  Info,
  Loader2,
  RefreshCw,
  Save,
  Trash2,
  UploadCloud,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { DocumentPreviewFrame } from '@/components/idms/DocumentPreviewFrame';
import { FormatToolbar } from '@/components/idms/FormatToolbar';
import { applyInheritedTypography } from '@/components/idms/InstanceRenderer';
import {
  EMPTY_FORMAT_TARGET,
  PARAGRAPH_OWNED_PROPS,
  RUN_PROPS,
  clearFormatHighlight,
  clearFormatting,
  clearPageMargins,
  firstPageSection,
  resolveFormatTarget,
  setFormatHighlight,
  setInlineDeclarations,
  setParagraphFontSize,
  stripStoredZoom,
  syncAdminMarker,
  writePageMargins,
  type FormatTarget,
  type PageMargins,
} from '@/lib/careers/studio-format';
import {
  ASSIGNEES,
  AUTO_BINDINGS,
  FIELD_TYPES,
  assigneeTone,
  humanBytes,
  parseFidelityWarnings,
  parseFieldSchema,
  type FieldAssignee,
  type FieldEntry,
  type FieldType,
  type TemplateRow,
} from '@/lib/careers/editable-templates';

/**
 * Editable Documents studio — Phase 1.
 *
 * <p>Renders the source DOCX via docx-preview (NOT mammoth — docx-preview
 * keeps headers, footers, tables and images which mammoth drops), lets
 * the admin select text ranges to wrap in a {@code <span data-field-id>}
 * per-field span, assign owner + type + required + AUTO binding, then
 * saves the canonical HTML + field schema JSON back to the server.</p>
 *
 * <p>The preview toggle at the top-right renders the exact same
 * canonical HTML but tints fields by "who fills this" — the read-only
 * mock the survey called for as the visual contract check.</p>
 */

const DOC_FIELD_CLASS = 'doc-field';

interface EditablePresignResponse {
  uploadUrl: string;
  documentId: string;
  storageKey: string;
  expiresAt: string;
}

const DOCX_MIME =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

export default function EditableTemplateStudioPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN', 'JOBS_ADMIN']}>
      <DashboardLayout title="Edit Document Template">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

type PreviewMode = 'edit' | 'erm' | 'intern';

function PageContent() {
  const params = useParams<{ key: string }>();
  const key = params?.key;
  const router = useRouter();

  const [template, setTemplate] = useState<TemplateRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  // Rendered canvas + selection tracking.
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const [rendered, setRendered] = useState(false);
  const [renderErr, setRenderErr] = useState<string | null>(null);
  const [conversionWarnings, setConversionWarnings] = useState<string[]>([]);
  const [selectionRect, setSelectionRect] = useState<{ top: number; left: number } | null>(null);
  const [selectionPresent, setSelectionPresent] = useState(false);

  // Formatting-toolbar state. `fmtTarget` is the element trio the
  // toolbar's controls act on; `fmtTargetRef` mirrors it so the
  // selection listener can compare against the current target without
  // re-subscribing on every change. `fmtVersion` is bumped after every
  // write so the toolbar re-reads the live DOM (the DOM, not React
  // state, is the source of truth — it is literally what gets saved).
  // `formatDirty` tracks unsaved formatting edits: they live only in the
  // canvas DOM until Save, so the faithful PDF preview (which renders
  // the SERVER's canonical HTML) would otherwise show stale output.
  const [fmtTarget, setFmtTarget] = useState<FormatTarget>(EMPTY_FORMAT_TARGET);
  const fmtTargetRef = useRef<FormatTarget>(EMPTY_FORMAT_TARGET);
  const [fmtVersion, setFmtVersion] = useState(0);
  const [formatDirty, setFormatDirty] = useState(false);

  // Field state — the source of truth once the studio is up.
  const [fields, setFields] = useState<FieldEntry[]>([]);
  const [inspecting, setInspecting] = useState<string | null>(null);

  // Save / re-upload machinery.
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState<string | null>(null);
  const [previewMode, setPreviewMode] = useState<PreviewMode>('edit');
  const [reuploadOpen, setReuploadOpen] = useState(false);

  // Dirty-tracking for the studio (F13 + F14). savedFieldsKeyRef is the
  // canonical field-list at load or last-successful save; any deviation
  // in `fields` flips `dirty` true. Wraps the beforeunload guard + the
  // extra reupload confirmation so an admin can't overwrite the source
  // and silently lose an in-flight redesign of the field list.
  const savedFieldsKeyRef = useRef<string>('');
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    const currentKey = JSON.stringify(fields);
    const fieldsDirty =
      savedFieldsKeyRef.current !== '' && currentKey !== savedFieldsKeyRef.current;
    // Formatting edits mutate the canvas DOM directly rather than the
    // fields array, so they need their own dirty signal — without it an
    // admin could restyle a document and navigate away with the
    // beforeunload guard silent.
    setDirty(fieldsDirty || formatDirty);
  }, [fields, formatDirty]);
  useEffect(() => {
    function beforeUnload(e: BeforeUnloadEvent) {
      if (dirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    }
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty]);

  // ── Load the template detail ─────────────────────────────────────
  const load = useCallback(async () => {
    if (!key) return;
    setLoading(true);
    try {
      const res = await api.get<TemplateRow>(
        `/api/v1/admin/editable-templates/by-key/${encodeURIComponent(key)}`,
      );
      setTemplate(res.data);
      const loaded = parseFieldSchema(res.data.fieldSchemaJson);
      setFields(loaded);
      savedFieldsKeyRef.current = JSON.stringify(loaded);
      setLoadErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setLoadErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load template');
    } finally {
      setLoading(false);
    }
  }, [key]);
  useEffect(() => { void load(); }, [load]);

  // ── Render the DOCX or reuse the saved canonical HTML ────────────
  //
  // If the row already has canonical HTML (previously saved) we render
  // THAT so the anchor ids remain intact. Otherwise we fetch the source
  // DOCX and hand it to docx-preview, which mutates the canvas in place.
  useEffect(() => {
    if (!template) return;
    if (!canvasRef.current) return;
    let cancelled = false;
    setRendered(false);
    setRenderErr(null);
    setConversionWarnings([]);

    const canvas = canvasRef.current;
    canvas.innerHTML = ''; // clear any prior render / saved html

    async function run() {
      // Path A — re-open a template that was previously saved. Use the
      // canonical HTML verbatim so the data-field-id spans stay put.
      if (template!.canonicalHtml && template!.canonicalHtml.trim()) {
        canvas.innerHTML = template!.canonicalHtml;
        setConversionWarnings(parseFidelityWarnings(template!.fidelityWarningsJson));
        setRendered(true);
        return;
      }
      // Path B — fresh render from the source DOCX via docx-preview.
      if (!template!.sourceDownloadUrl) {
        setRenderErr('Source document is missing. Upload a new .docx from the "Replace source" button.');
        return;
      }
      try {
        const [{ renderAsync }, response] = await Promise.all([
          import('docx-preview'),
          fetch(template!.sourceDownloadUrl),
        ]);
        if (cancelled) return;
        if (!response.ok) throw new Error(`Could not fetch source (HTTP ${response.status})`);
        const buffer = await response.arrayBuffer();
        // docx-preview's `experimental` option enables less-common
        // constructs (drawing frames, some field elements). Safe default.
        await renderAsync(buffer, canvas, undefined, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          ignoreFonts: false,
          breakPages: true,
          ignoreLastRenderedPageBreak: true,
          experimental: true,
          trimXmlDeclaration: true,
          useBase64URL: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
        });
        // docx-preview doesn't return warnings from renderAsync; the
        // library logs to console. Capture that stream for the banner
        // by wrapping console.warn during the render. Simple approach:
        // do a quick post-render sanity check of what's actually in the
        // canvas so we can surface obvious gaps.
        const warnings: string[] = [];
        const dropped = canvas.querySelectorAll('[data-docx-unsupported]');
        if (dropped.length > 0) {
          warnings.push(
            `${dropped.length} unsupported element(s) were dropped during preview.`,
          );
        }
        if (!canvas.querySelector('.docx')) {
          warnings.push('The preview container looks empty — check the source file.');
        }
        if (!cancelled) {
          setConversionWarnings(warnings);
          setRendered(true);
        }
      } catch (e) {
        if (!cancelled) {
          setRenderErr(e instanceof Error ? e.message : 'Failed to render preview');
        }
      }
    }
    void run();
    return () => { cancelled = true; };
  }, [template]);

  // ── Track admin selections inside the canvas ─────────────────────
  //
  // Containment: canvasRef points at the outer .doc-canvas wrapper, which
  // is the parent of EVERY docx-preview page section — so a `contains()`
  // check works for any selection on any page of a multi-page render.
  //
  // Toolbar position: absolute inside the (non-scrolling) `<div
  // className="relative">` that WRAPS the scrollable canvas. Because the
  // canvas element sits at (0,0) inside that relative parent, the correct
  // toolbar top is simply `rect.top - canvasRect.top - toolbarHeight` —
  // NO `canvas.scrollTop` addition. Adding scrollTop was the multi-page
  // bug: for a selection on page 3 with scrollTop ~2000px, the toolbar
  // was pushed ~2000px below its correct spot, off-screen.
  //
  // Empty rects: underscore runs and whitespace-only spans are legit
  // selections (signature lines land on them). If the primary rect is
  // degenerate, fall back to the first non-empty client rect, then to
  // the start container's parent element rect.
  useEffect(() => {
    function updateSelection() {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
        setSelectionPresent(false);
        setSelectionRect(null);
        return;
      }
      const range = sel.getRangeAt(0);
      if (!canvas.contains(range.commonAncestorContainer)) {
        setSelectionPresent(false);
        setSelectionRect(null);
        return;
      }
      const anchor = anchorRectForRange(range);
      if (!anchor) {
        // No usable anchor point — keep toolbar hidden but don't block
        // the make-field action; user can still trigger it if they know
        // what they've selected.
        setSelectionPresent(false);
        setSelectionRect(null);
        return;
      }
      const canvasRect = canvas.getBoundingClientRect();
      setSelectionPresent(true);
      setSelectionRect({
        top: anchor.top - canvasRect.top - 40,
        left: Math.max(0, anchor.left - canvasRect.left + anchor.width / 2),
      });
    }
    document.addEventListener('selectionchange', updateSelection);
    window.addEventListener('resize', updateSelection);
    return () => {
      document.removeEventListener('selectionchange', updateSelection);
      window.removeEventListener('resize', updateSelection);
    };
  }, []);

  // ── Restore the field list from the DOM on load ──────────────────
  // The canonical HTML we render already contains the spans; re-scan
  // to rebuild the local field state so subsequent selection wraps in
  // the correct order + we don't lose any that don't appear in the
  // JSON schema (shouldn't happen, but defends against drift).
  useLayoutEffect(() => {
    if (!rendered) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    applyOwnershipTints(canvas, fields);
    applyPreviewMode(canvas, previewMode, fields);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rendered]);

  // Re-apply tints whenever fields change or the preview mode flips.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !rendered) return;
    applyOwnershipTints(canvas, fields);
    applyPreviewMode(canvas, previewMode, fields);
    // applyPreviewMode -> resetPreviewClasses clears the formatting
    // toolbar's active outline unconditionally, so ANY field edit used
    // to erase it while the toolbar stayed bound to that paragraph —
    // the outline and the thing it points at silently disagreed.
    // Repaint it to match whatever is still bound.
    if (previewMode === 'edit') {
      setFormatHighlight(canvas, fmtTargetRef.current.paragraph);
    }
  }, [fields, previewMode, rendered]);

  // ── Wrap the current selection into a doc-field span ─────────────
  //
  // Multi-anchor model: the canonical HTML is the source of truth. A
  // field owns EVERY <span data-field-id="X"> whose id matches its own,
  // so "one field, two places" just means two spans sharing an id. The
  // FieldEntry schema stays flat (no anchor array); anchor count is
  // derived from the DOM. When Phase 2 fills a field, every anchor is
  // filled — the existing ownership-tint / preview-mode loops already
  // iterate every .doc-field span, so this works by construction.
  //
  // `existingFieldId=null` mints a fresh field; passing an id links the
  // new selection as an additional anchor of that field.
  function wrapSelection(existingFieldId: string | null) {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;
    const range = sel.getRangeAt(0);
    if (!canvas.contains(range.commonAncestorContainer)) return;

    const id = existingFieldId ?? generateUuid();
    const assignee: FieldAssignee =
      existingFieldId
        ? (fields.find((f) => f.id === existingFieldId)?.assignee ?? 'ERM')
        : 'ERM';
    const span = document.createElement('span');
    span.setAttribute('data-field-id', id);
    span.className = `${DOC_FIELD_CLASS} doc-field--${assignee.toLowerCase()}`;
    try {
      range.surroundContents(span);
    } catch {
      // surroundContents fails on partial nodes across boundaries.
      const frag = range.extractContents();
      span.appendChild(frag);
      range.insertNode(span);
    }
    // Preserve the wrapped selection's exact typography on the anchor
    // itself. Without this, wrapping underscore / whitespace runs
    // (signature-line "____________" blanks are the canonical
    // reproducer) could visibly change the font — browsers cascade
    // subtly-differently through nested spans depending on how
    // docx-preview emitted the run. Copying font-family / size /
    // weight / style / letter-spacing / line-height / color /
    // text-decoration / text-transform / font-variant onto the anchor
    // guarantees the wrap NEVER alters the visible font. Same helper
    // the fill preview uses via InstanceRenderer.
    applyInheritedTypography(span);
    sel.removeAllRanges();
    setSelectionPresent(false);
    setSelectionRect(null);

    if (existingFieldId) {
      // Linking to an existing field — no schema entry to add. Re-apply
      // tints so the new span picks up the field's colour immediately,
      // and nudge the fields array reference so useAnchorCounts re-runs
      // and the sidebar shows the new "N places" chip.
      applyOwnershipTints(canvas, fields);
      applyPreviewMode(canvas, previewMode, fields);
      setFields((prev) => [...prev]);
      setInspecting(existingFieldId);
      return;
    }

    const entry: FieldEntry = {
      id,
      name: defaultFieldName(fields.length + 1),
      type: 'text',
      assignee: 'ERM',
      required: true,
      defaultSource: null,
    };
    setFields((prev) => [...prev, entry]);
    setInspecting(id);
  }

  function updateField(id: string, patch: Partial<FieldEntry>) {
    setFields((prev) => prev.map((f) => (f.id === id ? { ...f, ...patch } : f)));
  }

  // Unwrap ALL anchors of a field (may be more than one after linking).
  function deleteField(id: string) {
    const canvas = canvasRef.current;
    if (canvas) {
      canvas.querySelectorAll(`[data-field-id="${id}"]`).forEach((el) => {
        unwrapSpan(el);
      });
    }
    setFields((prev) => prev.filter((f) => f.id !== id));
    if (inspecting === id) setInspecting(null);
  }

  // Unwrap a single anchor. If it was the last remaining anchor of its
  // field, the field entry is removed too — a field with zero anchors
  // has nothing to fill.
  function deleteAnchor(fieldId: string, anchorIndex: number) {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const anchors = canvas.querySelectorAll(`[data-field-id="${fieldId}"]`);
    const el = anchors[anchorIndex];
    if (!el) return;
    unwrapSpan(el);
    if (anchors.length <= 1) {
      setFields((prev) => prev.filter((f) => f.id !== fieldId));
      if (inspecting === fieldId) setInspecting(null);
    } else {
      // Re-render sidebar counts by nudging the fields array reference.
      setFields((prev) => [...prev]);
    }
  }

  // Cycle through a field's anchors on successive clicks — 1 → 2 → 1 …
  const cycleIndexRef = useRef<Map<string, number>>(new Map());
  function jumpToField(id: string) {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const anchors = Array.from(
      canvas.querySelectorAll(`[data-field-id="${id}"]`),
    ) as HTMLElement[];
    if (anchors.length === 0) return;
    const nextIdx = ((cycleIndexRef.current.get(id) ?? -1) + 1) % anchors.length;
    cycleIndexRef.current.set(id, nextIdx);
    const el = anchors[nextIdx];
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setInspecting(id);
    el.classList.add('doc-field--flash');
    window.setTimeout(() => el.classList.remove('doc-field--flash'), 900);
  }

  function jumpToAnchor(fieldId: string, anchorIndex: number) {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const anchors = Array.from(
      canvas.querySelectorAll(`[data-field-id="${fieldId}"]`),
    ) as HTMLElement[];
    const el = anchors[anchorIndex];
    if (!el) return;
    cycleIndexRef.current.set(fieldId, anchorIndex);
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setInspecting(fieldId);
    el.classList.add('doc-field--flash');
    window.setTimeout(() => el.classList.remove('doc-field--flash'), 900);
  }

  // Recompute anchor counts whenever fields change; the canvas DOM is
  // the source of truth so this reads directly from it.
  const anchorCounts = useAnchorCounts(canvasRef, fields, rendered);

  // ── Save ─────────────────────────────────────────────────────────
  const validation = useMemo(() => validateFields(fields), [fields]);

  async function save() {
    if (validation.error) {
      setSaveErr(validation.error);
      toast.error(validation.error);
      return;
    }
    const canvas = canvasRef.current;
    if (!canvas || !template) return;
    // Serialise the canvas HTML minus the temporary preview-mode
    // annotations — reset the preview to edit view first so those
    // classes are stripped.
    resetPreviewClasses(canvas);
    const html = canvas.innerHTML;
    setSaving(true);
    setSaveErr(null);
    try {
      const res = await api.put<TemplateRow>(
        `/api/v1/admin/editable-templates/${template.id}/schema`,
        {
          canonicalHtml: html,
          fields,
          fidelityWarnings: conversionWarnings,
        },
      );
      setTemplate(res.data);
      const savedFields = parseFieldSchema(res.data.fieldSchemaJson);
      setFields(savedFields);
      savedFieldsKeyRef.current = JSON.stringify(savedFields);
      setFormatDirty(false);
      toast.success('Template saved.');
      // Post-save auto-refresh (part of the refresh model): if the
      // faithful-PDF tab is currently open, re-render so the admin
      // sees the real result of the save they just made. Fire-and-
      // forget — the pane's own loading/error surfaces handle the
      // rest; save() itself doesn't wait on it.
      //
      // When the tab ISN'T open, mark the cached blob stale instead.
      // Formatting edits are made on the canvas tab, so without this
      // the admin would restyle, save, switch to the PDF tab and be
      // shown the PREVIOUS render — the exact stale-preview trap the
      // faithful preview exists to avoid.
      if (viewMode === 'pdf') {
        void refreshPdfPreview();
      } else {
        setPdfStale(true);
      }
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      const msg = ax.response?.data?.error ?? (e instanceof Error ? e.message : 'Save failed');
      setSaveErr(msg);
      toast.error(msg);
    } finally {
      setSaving(false);
      // Re-apply preview annotations if the user is currently in a preview mode.
      if (canvas && previewMode !== 'edit') {
        applyPreviewMode(canvas, previewMode, fields);
      }
      applyOwnershipTints(canvas, fields);
      // resetPreviewClasses() stripped the formatting highlight above
      // (that is exactly why it's stripped — so it can't reach the
      // saved HTML). Repaint it so the admin doesn't lose track of
      // which paragraph the toolbar is bound to.
      if (previewMode === 'edit') {
        setFormatHighlight(canvas, fmtTargetRef.current.paragraph);
      }
    }
  }

  // ── Faithful in-studio PDF preview ───────────────────────────────
  //
  // Second-render path alongside docx-preview: hits the EXISTING
  // /preview-pdf endpoint (unchanged) which routes through the SAME
  // DocumentInstancePdfRenderer the finalize path uses, and embeds
  // the returned bytes in an <iframe> beside the editing canvas via
  // a tab switcher. Kills the "looks fine in studio, messy in PDF"
  // surprise — the admin sees byte-representative output while they
  // author.
  //
  // Refresh model — on-demand + post-save; NOT per-keystroke. The
  // renderer is ~1-3s per doc + a single-thread executor with a 45s
  // cap (see DocumentInstancePdfRenderer), so live re-render would
  // lag visibly and starve concurrent authoring. The tab switch
  // auto-refreshes ONCE on first open; every subsequent refresh is
  // an explicit button click, plus one implicit refresh after a
  // successful Save so the admin immediately sees the real result of
  // what they just committed.
  type StudioViewMode = 'canvas' | 'pdf';
  const [viewMode, setViewMode] = useState<StudioViewMode>('canvas');
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  const [pdfLoading, setPdfLoading] = useState(false);
  const [pdfErr, setPdfErr] = useState<string | null>(null);
  const [pdfHasEverLoaded, setPdfHasEverLoaded] = useState(false);
  // Set when a save lands while the PDF tab is closed — the cached blob
  // no longer reflects the saved template, so the next switch to the
  // tab must re-render instead of showing the stale one.
  const [pdfStale, setPdfStale] = useState(false);
  // Latest-request marker: if the admin clicks Refresh twice quickly,
  // only the LATER response wins. Race-safety without a full cancel
  // (openhtmltopdf calls that started can't be aborted server-side
  // anyway — but we can discard their result client-side).
  const pdfReqIdRef = useRef(0);

  const refreshPdfPreview = useCallback(async () => {
    if (!template) return;
    // Never-saved templates have no canonical HTML — the backend
    // BadRequests them. Surface the friendly hint instead of firing
    // a doomed request.
    if (!template.canonicalHtml || !template.canonicalHtml.trim()) {
      setPdfErr('Save the template first to see the executed PDF preview.');
      setPdfLoading(false);
      return;
    }
    const reqId = ++pdfReqIdRef.current;
    setPdfLoading(true);
    setPdfErr(null);
    try {
      const res = await api.post(
        `/api/v1/admin/editable-templates/by-key/${template.key}/preview-pdf`,
        {},
        { responseType: 'blob' },
      );
      // Late-response guard — if the admin fired another refresh
      // while this one was in-flight, discard.
      if (reqId !== pdfReqIdRef.current) return;
      // Revoke the prior URL before replacing so memory doesn't
      // accumulate on repeated refreshes (many minutes of
      // authoring × frequent Refresh could otherwise pile up).
      setPdfBlobUrl((prev) => {
        if (prev) URL.revokeObjectURL(prev);
        return URL.createObjectURL(res.data as Blob);
      });
      setPdfHasEverLoaded(true);
      setPdfStale(false);
    } catch (e) {
      if (reqId !== pdfReqIdRef.current) return;
      const ax = e as { response?: { data?: { error?: string }; status?: number }; message?: string };
      // The renderer's 45s executor cap surfaces as a 500-shaped
      // failure; a template with no fields / bad HTML surfaces as
      // a 400 with a message. Either way, a clean sentence — never
      // a broken iframe.
      setPdfErr(ax.response?.data?.error
          ?? ax.message
          ?? "Couldn't render preview — check the template.");
    } finally {
      if (reqId === pdfReqIdRef.current) setPdfLoading(false);
    }
  }, [template]);

  // Auto-refresh the FIRST time the admin switches to the PDF tab,
  // so they don't have to hit Refresh separately to see anything.
  // Subsequent tab-switches show whatever's cached — explicit
  // Refresh is the only way to re-render after that (plus the
  // post-save hook below).
  useEffect(() => {
    if (viewMode === 'pdf' && (!pdfHasEverLoaded || pdfStale) && !pdfLoading) {
      void refreshPdfPreview();
    }
  }, [viewMode, pdfHasEverLoaded, pdfStale, pdfLoading, refreshPdfPreview]);

  // Cleanup any lingering blob URL on unmount — otherwise the
  // browser holds the PDF bytes alive after navigating away.
  useEffect(() => {
    return () => {
      if (pdfBlobUrl) URL.revokeObjectURL(pdfBlobUrl);
    };
    // Intentionally captures the initial pdfBlobUrl closure and re-
    // captures on every change — the cleanup fires on the PRIOR
    // effect's captured value.
  }, [pdfBlobUrl]);

  // ── Formatting toolbar — resolve, highlight, apply ───────────────
  //
  // FORMATTING ONLY. The canvas never becomes contentEditable and no
  // control touches text content; each one writes a single inline CSS
  // declaration onto the element resolved from the admin's selection.
  //
  // Coexistence with the field-wrap floater: this path READS the
  // selection but never consumes it (no removeAllRanges, no
  // preventDefault on the canvas), so the existing "Make field" floater
  // keeps firing on non-collapsed selections exactly as before. The two
  // surfaces also occupy different pixels — the floater is absolutely
  // positioned against the selection rect, the format strip is a fixed
  // row above the canvas.
  const applyTarget = useCallback((next: FormatTarget) => {
    const prev = fmtTargetRef.current;
    if (
      prev.paragraph === next.paragraph &&
      prev.run === next.run &&
      prev.section === next.section
    ) {
      return; // Same target — skip the re-render (selectionchange is chatty).
    }
    fmtTargetRef.current = next;
    setFormatHighlight(canvasRef.current, next.paragraph);
    setFmtTarget(next);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !rendered || viewMode !== 'canvas' || previewMode !== 'edit') {
      return;
    }
    function fromSelection() {
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0) return;
      const next = resolveFormatTarget(
        sel.getRangeAt(0).startContainer,
        canvasRef.current,
      );
      if (next.paragraph) applyTarget(next);
    }
    function fromClick(e: MouseEvent) {
      // elementFromPoint semantics via the event target — lets an admin
      // target an EMPTY paragraph (no text to select), which is exactly
      // the case where line-spacing repair matters most.
      const next = resolveFormatTarget(e.target as Node, canvasRef.current);
      if (next.paragraph) applyTarget(next);
    }
    document.addEventListener('selectionchange', fromSelection);
    canvas.addEventListener('click', fromClick);
    return () => {
      document.removeEventListener('selectionchange', fromSelection);
      canvas.removeEventListener('click', fromClick);
    };
  }, [rendered, viewMode, previewMode, applyTarget]);

  // Drop the target (and its highlight) whenever the canvas is no
  // longer the active, editable surface — a stale highlight pointing at
  // a detached node would otherwise survive a re-render.
  useEffect(() => {
    if (viewMode === 'canvas' && previewMode === 'edit' && rendered) return;
    fmtTargetRef.current = EMPTY_FORMAT_TARGET;
    clearFormatHighlight(canvasRef.current);
    setFmtTarget(EMPTY_FORMAT_TARGET);
  }, [viewMode, previewMode, rendered]);

  // Every write funnels through here so the bump + dirty flag can never
  // be forgotten by an individual control.
  const afterFormatWrite = useCallback(() => {
    setFmtVersion((v) => v + 1);
    setFormatDirty(true);
  }, []);

  const handleSetParagraph = useCallback(
    (prop: string, value: string | null) => {
      const target = fmtTargetRef.current;
      if (!target.paragraph) return;
      setInlineDeclarations(target.paragraph, { [prop]: value });
      // Stamp the deliberate-override marker so the backend's profile
      // corrector stops re-asserting the imported DOCX values over this
      // paragraph on save. Synced (not blindly stamped) so clearing the
      // last override also releases the element.
      syncAdminMarker(target.paragraph, PARAGRAPH_OWNED_PROPS);
      afterFormatWrite();
    },
    [afterFormatWrite],
  );

  const handleSetFontSize = useCallback(
    (value: string | null, wholeParagraph: boolean) => {
      const target = fmtTargetRef.current;
      if (wholeParagraph) {
        // Paragraph-wide: the value must also land on each descendant
        // run, because docx-preview's own inline run sizes would
        // otherwise mask a declaration on the <p>.
        setParagraphFontSize(target.paragraph, value);
      } else if (target.run) {
        setInlineDeclarations(target.run, { 'font-size': value });
        syncAdminMarker(target.run, RUN_PROPS);
      } else {
        return;
      }
      afterFormatWrite();
    },
    [afterFormatWrite],
  );

  const handleSetPageMargins = useCallback(
    (margins: PageMargins) => {
      // writePageMargins refuses any set that wouldn't survive the
      // renderer's SIMPLE_LENGTH scrape, so a rejected write leaves the
      // document untouched rather than half-applied.
      if (!writePageMargins(canvasRef.current, margins)) {
        toast.error('Margins must be a number plus in / cm / mm / pt / px.');
        return;
      }
      afterFormatWrite();
    },
    [afterFormatWrite],
  );

  const handleClearPageMargins = useCallback(() => {
    clearPageMargins(canvasRef.current);
    afterFormatWrite();
  }, [afterFormatWrite]);

  const handleResetFormatting = useCallback(() => {
    const target = fmtTargetRef.current;
    if (!target.paragraph) return;
    clearFormatting(target);
    afterFormatWrite();
  }, [afterFormatWrite]);

  // While the margins popover is open the highlight moves to the page
  // section it actually edits (the FIRST one — that's what
  // preparePageGeometry scrapes), so the admin can see the scope of the
  // change is document-wide, not the paragraph they last clicked.
  const handleSectionFocus = useCallback((focused: boolean) => {
    const canvas = canvasRef.current;
    if (focused) {
      setFormatHighlight(canvas, firstPageSection(canvas), 'section');
    } else {
      setFormatHighlight(canvas, fmtTargetRef.current.paragraph);
    }
  }, []);

  // ── Preview PDF in a new tab (legacy iterate-layout button) ──────
  const [previewingPdf, setPreviewingPdf] = useState(false);
  async function previewPdf() {
    if (!template) return;
    setPreviewingPdf(true);
    try {
      // Blob response so the browser can open the returned PDF in a
      // new tab; the shared api client attaches the session cookie +
      // CSRF header exactly like every other admin write.
      const res = await api.post(
        `/api/v1/admin/editable-templates/by-key/${template.key}/preview-pdf`,
        {},
        { responseType: 'blob' },
      );
      const url = URL.createObjectURL(res.data as Blob);
      // Open in a new tab so the studio state (canvas selections, edit
      // draft) survives — a same-tab window.location swap would nuke
      // any in-flight anchor placement the admin was making.
      const win = window.open(url, '_blank');
      // Some browsers block window.open under a fetch chain — fall
      // back to a hidden anchor download so the admin still gets the
      // file rather than a silent failure.
      if (!win) {
        const a = document.createElement('a');
        a.href = url;
        a.download = `${template.key}-preview.pdf`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      }
      // Revoke on the next macrotask so the new tab has time to load.
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Preview failed');
    } finally {
      setPreviewingPdf(false);
    }
  }

  // ── Re-upload source DOCX (clears the schema server-side) ────────
  async function reuploadSource(file: File) {
    if (!template) return;
    try {
      const presignRes = await api.post<EditablePresignResponse>(
        `/api/v1/admin/editable-templates/${template.id}/source/presign-upload`,
        {
          fileName: file.name,
          contentType: DOCX_MIME,
          fileSize: file.size,
        },
      );
      const presign = presignRes.data;
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', presign.uploadUrl);
        xhr.setRequestHeader('Content-Type', DOCX_MIME);
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) resolve();
          else reject(new Error(`S3 rejected the upload (HTTP ${xhr.status})`));
        };
        xhr.onerror = () => reject(new Error('Network error'));
        xhr.send(file);
      });
      await api.post(`/api/v1/admin/editable-templates/${template.id}/source/attach`, {
        documentId: presign.documentId,
      });
      toast.success('New source uploaded. Fields cleared — re-select on the new render.');
      setReuploadOpen(false);
      // Full reload to pull the fresh presigned URL + reset state.
      router.refresh();
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      toast.error(ax.response?.data?.error ?? 'Re-upload failed');
    }
  }

  if (loading) {
    return <div className="mx-auto max-w-6xl p-6"><div className="h-64 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (loadErr || !template) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {loadErr ?? 'Template not found.'}
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1400px] space-y-4">
      <StudioHeader
        template={template}
        previewMode={previewMode}
        onModeChange={setPreviewMode}
        onSave={save}
        onPreviewPdf={previewPdf}
        onReupload={() => setReuploadOpen(true)}
        saving={saving}
        previewingPdf={previewingPdf}
        validationError={validation.error}
      />

      {conversionWarnings.length > 0 && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <p className="font-medium">Formatting fidelity notes</p>
            <ul className="ml-4 list-disc text-xs">
              {conversionWarnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {saveErr && (
        <div className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{saveErr}</p>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        {/* ── Canvas ─────────────────────────────────────────────── */}
        <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
          {/* Tab strip — switches between the editing canvas
              (docx-preview + selection wrapping) and the faithful
              PDF preview (real DocumentInstancePdfRenderer output
              via the existing /preview-pdf endpoint). The docx-
              preview canvas is the AUTHORING surface; the PDF
              preview shows exactly what a finalized offer would
              look like, killing the "looks fine in studio, messy
              in PDF" surprise. */}
          <div className="flex items-center gap-1 border-b border-slate-100 px-2 pt-2">
            <button
              type="button"
              onClick={() => setViewMode('canvas')}
              className={`inline-flex items-center gap-1.5 rounded-t-md border-b-2 px-3 py-1.5 text-xs font-medium ${
                viewMode === 'canvas'
                  ? 'border-brand-500 text-brand-800'
                  : 'border-transparent text-slate-500 hover:text-slate-800'
              }`}
            >
              <Edit3 className="h-3.5 w-3.5" />
              Editing canvas
            </button>
            <button
              type="button"
              onClick={() => setViewMode('pdf')}
              className={`inline-flex items-center gap-1.5 rounded-t-md border-b-2 px-3 py-1.5 text-xs font-medium ${
                viewMode === 'pdf'
                  ? 'border-brand-500 text-brand-800'
                  : 'border-transparent text-slate-500 hover:text-slate-800'
              }`}
              title="Renders through the SAME pipeline as a finalized offer — margins, fonts, header/footer, list positioning, everything."
            >
              <Eye className="h-3.5 w-3.5" />
              Faithful PDF preview
            </button>
          </div>

          {viewMode === 'canvas' && (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-2 text-xs text-slate-500">
                <div className="inline-flex items-center gap-3">
                  {previewMode === 'edit' ? (
                    <>
                      <Edit3 className="h-3.5 w-3.5" />
                      Edit mode — select any text to make it a field.
                    </>
                  ) : (
                    <>
                      <Eye className="h-3.5 w-3.5" />
                      Preview mode — {previewMode === 'erm' ? 'ERM view' : 'Intern view'}. Read-only mock.
                    </>
                  )}
                </div>
                <OwnershipLegend />
              </div>

              {/* Formatting strip — a fixed row, deliberately NOT a
                  floating popover: the "Make field" floater already
                  anchors to the selection rect, so a second
                  selection-anchored surface would collide with it.
                  Formatting only; the canvas stays non-editable text. */}
              <FormatToolbar
                target={fmtTarget}
                canvas={canvasRef.current}
                version={fmtVersion}
                disabled={previewMode !== 'edit' || !rendered}
                onSetParagraph={handleSetParagraph}
                onSetFontSize={handleSetFontSize}
                onSetPageMargins={handleSetPageMargins}
                onClearPageMargins={handleClearPageMargins}
                onReset={handleResetFormatting}
                onSectionFocus={handleSectionFocus}
              />

              <div className="relative">
                {/* Shared preview frame — DocumentPreviewFrame ships the
                    slate canvas + white page-shadow + base .doc-field styles
                    so admin studio, ERM fill, and intern fill/sign render
                    the document identically. Admin-only field tints
                    (ownership colors, preview lock/input outlines, flash)
                    still live in the local <style jsx global> below. */}
                <DocumentPreviewFrame
                  ref={canvasRef}
                  className="min-h-[400px] max-h-[calc(100vh-260px)] overflow-y-auto"
                />
                {!rendered && !renderErr && (
                  <div className="absolute inset-0 flex items-center justify-center bg-white/70">
                    <div className="inline-flex items-center gap-2 text-sm text-slate-500">
                      <Loader2 className="h-4 w-4 animate-spin" />
                      Rendering document…
                    </div>
                  </div>
                )}
                {renderErr && (
                  <div className="absolute inset-0 flex items-center justify-center p-6">
                    <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
                      {renderErr}
                    </div>
                  </div>
                )}
                {previewMode === 'edit' && selectionPresent && selectionRect && (
                  <SelectionToolbar
                    top={selectionRect.top}
                    left={selectionRect.left}
                    fields={fields}
                    onNewField={() => wrapSelection(null)}
                    onLinkExisting={(id) => wrapSelection(id)}
                  />
                )}
              </div>
            </>
          )}

          {viewMode === 'pdf' && (
            <FaithfulPdfPreviewPane
              blobUrl={pdfBlobUrl}
              loading={pdfLoading}
              error={pdfErr}
              hasCanonical={Boolean(template.canonicalHtml && template.canonicalHtml.trim())}
              onRefresh={refreshPdfPreview}
              savingBlocked={saving}
              unsavedFormatting={formatDirty}
            />
          )}
        </section>

        {/* ── Sidebar — field list + inspector ──────────────────── */}
        <aside className="space-y-4">
          <FieldsSidebar
            fields={fields}
            anchorCounts={anchorCounts}
            inspecting={inspecting}
            onSelect={jumpToField}
            onDelete={deleteField}
          />
          {inspecting && (
            <FieldInspector
              field={fields.find((f) => f.id === inspecting) ?? null}
              anchorCount={anchorCounts.get(inspecting) ?? 0}
              onChange={(patch) => updateField(inspecting, patch)}
              onClose={() => setInspecting(null)}
              onJumpAnchor={(idx) => jumpToAnchor(inspecting, idx)}
              onDeleteAnchor={(idx) => deleteAnchor(inspecting, idx)}
            />
          )}
        </aside>
      </div>

      {reuploadOpen && (
        <ReuploadDialog
          template={template}
          hasUnsavedChanges={dirty}
          onClose={() => setReuploadOpen(false)}
          onUpload={reuploadSource}
        />
      )}

      <style jsx global>{`
        /* Admin-studio-only field overlays. Base .doc-field
           border-radius/padding + .doc-canvas .docx page-shadow are
           shipped by DocumentPreviewFrame — keeping them out of here
           guarantees admin, ERM, and intern see identical framing. */
        .doc-field {
          cursor: pointer;
          transition: outline 120ms ease;
        }
        .doc-field--erm { background: rgba(37, 99, 235, 0.12); }
        .doc-field--intern { background: rgba(245, 158, 11, 0.14); }
        .doc-field--auto { background: rgba(148, 163, 184, 0.16); }
        .doc-field--flash { outline: 2px solid rgb(59, 130, 246); }
        .doc-field--preview-locked {
          background: rgba(226, 232, 240, 0.6);
          color: rgba(71, 85, 105, 0.7);
        }
        .doc-field--preview-input {
          outline: 1px dashed rgba(37, 99, 235, 0.65);
        }

        /* Formatting-toolbar active target. Dashed + brand-tinted so it
           reads as "this is what the toolbar will restyle" and is
           visually distinct from the solid-blue .doc-field--flash and
           the filled ownership tints. Purely visual — stripped by
           clearFormatHighlight() before every save. */
        .studio-fmt-active {
          outline: 2px dashed var(--ds-brand-ring, rgb(42, 140, 219));
          outline-offset: 2px;
          background: rgba(42, 140, 219, 0.06);
        }
        /* A whole page section is too large to outline legibly — badge
           the corner instead so the admin sees that a page-margin edit
           is document-wide, not paragraph-scoped. */
        .studio-fmt-active--section {
          position: relative;
          background: transparent;
        }
        .studio-fmt-active--section::before {
          content: 'Page margins';
          position: absolute;
          top: 0;
          left: 0;
          transform: translateY(-100%);
          background: var(--ds-brand-ring, rgb(42, 140, 219));
          color: #fff;
          font-size: 10px;
          font-weight: 600;
          letter-spacing: 0.02em;
          padding: 2px 6px;
          border-radius: 3px 3px 0 0;
        }
      `}</style>
    </div>
  );
}

// ── Header ────────────────────────────────────────────────────────────

function StudioHeader({
  template,
  previewMode,
  onModeChange,
  onSave,
  onPreviewPdf,
  onReupload,
  saving,
  previewingPdf,
  validationError,
}: {
  template: TemplateRow;
  previewMode: PreviewMode;
  onModeChange: (m: PreviewMode) => void;
  onSave: () => void;
  onPreviewPdf: () => void;
  onReupload: () => void;
  saving: boolean;
  previewingPdf: boolean;
  validationError: string | null;
}) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div>
        <Link
          href="/careers/admin/document-templates"
          className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          All templates
        </Link>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-900">
          {template.title}
        </h1>
        <p className="mt-0.5 text-xs text-slate-500">
          <code className="font-mono">{template.key}</code>
          {template.sourceFileName && (
            <span className="ml-2">
              · {template.sourceFileName} ({humanBytes(template.sourceFileBytes)})
            </span>
          )}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <ModeToggle mode={previewMode} onChange={onModeChange} />
        <button
          type="button"
          onClick={onReupload}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Replace source
        </button>
        <button
          type="button"
          onClick={onPreviewPdf}
          disabled={previewingPdf}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          title="Render this template as a PDF with sample values — same renderer the finalized offer uses"
        >
          {previewingPdf
            ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
            : <FileText className="h-3.5 w-3.5" />}
          Preview PDF
        </button>
        <button
          type="button"
          onClick={onSave}
          disabled={saving || Boolean(validationError)}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          title={validationError ?? 'Save changes'}
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          Save
        </button>
      </div>
    </header>
  );
}

function ModeToggle({
  mode,
  onChange,
}: {
  mode: PreviewMode;
  onChange: (m: PreviewMode) => void;
}) {
  const opts: { value: PreviewMode; label: string }[] = [
    { value: 'edit', label: 'Edit' },
    { value: 'erm', label: 'ERM view' },
    { value: 'intern', label: 'Intern view' },
  ];
  return (
    <div className="inline-flex rounded-md border border-slate-200 bg-white p-0.5 text-xs">
      {opts.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={`px-3 py-1.5 font-medium transition ${
            mode === o.value
              ? 'rounded bg-brand-700 text-white shadow'
              : 'text-slate-600 hover:text-slate-900'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function OwnershipLegend() {
  return (
    <div className="inline-flex items-center gap-3 text-xs text-slate-500">
      {ASSIGNEES.map((a) => (
        <span key={a.value} className="inline-flex items-center gap-1">
          <span className={`inline-block h-3 w-3 rounded ${a.tone}`.replace('border', '')} />
          {a.label}
        </span>
      ))}
    </div>
  );
}

// ── Faithful PDF preview pane ─────────────────────────────────────────
//
// Renders the same canvas viewport space as the docx-preview editing
// surface (tab-switched via viewMode). Embeds the /preview-pdf blob
// in an <iframe> so the admin sees the ACTUAL executed output while
// they author — margins, fonts, header/footer positioning, list
// bullets, page breaks, everything. Handles: (a) never-saved
// template — hint to save first, no doomed request; (b) loading
// spinner during render (~1-3s); (c) clean error message on renderer
// failure or timeout; (d) an explicit Refresh button on the toolbar
// so re-renders are on-demand (never per-keystroke). Post-save
// auto-refresh is wired in the parent PageContent save() handler.

function FaithfulPdfPreviewPane({
  blobUrl, loading, error, hasCanonical, onRefresh, savingBlocked,
  unsavedFormatting,
}: {
  blobUrl: string | null;
  loading: boolean;
  error: string | null;
  hasCanonical: boolean;
  onRefresh: () => void;
  savingBlocked: boolean;
  /** True when the canvas holds formatting edits that haven't been
   *  saved. This pane renders the SERVER's canonical HTML, so those
   *  edits are genuinely absent from what's shown — say so rather than
   *  let the admin read a stale render as a faithful one. */
  unsavedFormatting: boolean;
}) {
  const disabled = loading || savingBlocked || !hasCanonical;
  return (
    <>
      {unsavedFormatting && (
        <div className="flex items-start gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <p>
            <span className="font-medium">
              Unsaved formatting changes aren&apos;t in this preview.
            </span>{' '}
            This pane renders the saved template. Hit Save to see your
            spacing / alignment / margin edits in the executed PDF.
          </p>
        </div>
      )}
      {/* Toolbar row — mirrors the canvas-mode toolbar shape so the
          tab-switch feels like a modeswitch rather than a
          replacement. */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-2 text-xs text-slate-500">
        <div className="inline-flex items-center gap-1.5">
          <Eye className="h-3.5 w-3.5" />
          <span>
            This is how the finalized PDF will look — rendered through
            the same pipeline as a real offer.
          </span>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={disabled}
          title={hasCanonical
            ? 'Re-render the PDF with the currently-saved template state'
            : 'Save the template first to preview'}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Loader2 className={`h-3 w-3 ${loading ? 'animate-spin' : 'hidden'}`} />
          {loading ? 'Rendering…' : 'Refresh preview'}
        </button>
      </div>

      <div className="relative min-h-[400px] max-h-[calc(100vh-260px)] overflow-hidden bg-slate-50">
        {!hasCanonical && (
          <div className="absolute inset-0 flex items-center justify-center p-6">
            <div className="max-w-md rounded-md border border-slate-200 bg-white p-4 text-center text-sm text-slate-600">
              <p className="font-medium text-slate-800">
                Save the template to preview
              </p>
              <p className="mt-1 text-xs text-slate-500">
                The faithful PDF preview renders the saved canonical
                HTML through the real finalize pipeline. Place your
                field anchors on the editing canvas, then Save — the
                preview appears here.
              </p>
            </div>
          </div>
        )}

        {hasCanonical && !blobUrl && !loading && !error && (
          <div className="absolute inset-0 flex items-center justify-center p-6">
            <div className="text-center text-sm text-slate-500">
              <p>Click <strong>Refresh preview</strong> to render the PDF.</p>
              <p className="mt-1 text-xs text-slate-400">
                Typically 1–3 seconds. On-demand only — the studio
                does not re-render on every keystroke.
              </p>
            </div>
          </div>
        )}

        {loading && !blobUrl && (
          <div className="absolute inset-0 flex items-center justify-center bg-white/70">
            <div className="inline-flex items-center gap-2 text-sm text-slate-500">
              <Loader2 className="h-4 w-4 animate-spin" />
              Rendering PDF…
            </div>
          </div>
        )}

        {error && !loading && (
          <div className="absolute inset-0 flex items-center justify-center p-6">
            <div className="max-w-md rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
              <p className="font-medium">Couldn&apos;t render preview</p>
              <p className="mt-1 text-xs">{error}</p>
              <button
                type="button"
                onClick={onRefresh}
                disabled={loading}
                className="mt-2 inline-flex items-center gap-1 rounded-md border border-red-300 bg-white px-2 py-0.5 text-[11px] font-medium text-red-800 hover:bg-red-50 disabled:opacity-60"
              >
                Try again
              </button>
            </div>
          </div>
        )}

        {blobUrl && (
          // Overlay a subtle refreshing bar when a re-render is in
          // flight, but keep the existing PDF visible underneath so
          // the admin has something to look at during the 1-3s
          // render window.
          <>
            {loading && (
              <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-center justify-center gap-2 bg-white/85 py-1 text-[11px] text-slate-600">
                <Loader2 className="h-3 w-3 animate-spin" />
                Re-rendering with the latest save…
              </div>
            )}
            <iframe
              src={blobUrl}
              title="Faithful PDF preview"
              className="h-[calc(100vh-260px)] min-h-[400px] w-full border-0 bg-white"
            />
          </>
        )}
      </div>
    </>
  );
}

// ── Sidebar list ──────────────────────────────────────────────────────

function FieldsSidebar({
  fields,
  anchorCounts,
  inspecting,
  onSelect,
  onDelete,
}: {
  fields: FieldEntry[];
  anchorCounts: Map<string, number>;
  inspecting: string | null;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Fields</h2>
        <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
          {fields.length}
        </span>
      </header>
      {fields.length === 0 ? (
        <p className="text-xs text-slate-500">
          Select text in the document and click <span className="font-medium">New field</span>.
        </p>
      ) : (
        <ul className="space-y-1.5">
          {fields.map((f) => {
            const isActive = inspecting === f.id;
            const count = anchorCounts.get(f.id) ?? 0;
            return (
              <li
                key={f.id}
                className={`group flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm ${
                  isActive ? 'border-brand-500 bg-brand-50' : 'border-slate-200 hover:bg-slate-50'
                }`}
              >
                <button
                  type="button"
                  onClick={() => onSelect(f.id)}
                  className="flex-1 text-left"
                  title={count > 1 ? `Click to cycle through ${count} places` : 'Jump to field'}
                >
                  <p className="truncate font-medium text-slate-900">
                    {f.name || 'Untitled field'}
                    {count > 1 && (
                      <span className="ml-1.5 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600">
                        {count} places
                      </span>
                    )}
                  </p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    <span className={`mr-1.5 rounded border px-1.5 py-0.5 text-[10px] font-medium ${assigneeTone(f.assignee)}`}>
                      {f.assignee}
                    </span>
                    <span className="uppercase tracking-wide">{f.type.replace('_', ' ')}</span>
                    {f.required && <span className="ml-1 text-red-500">*</span>}
                  </p>
                </button>
                <button
                  type="button"
                  onClick={() => onDelete(f.id)}
                  className="opacity-0 transition group-hover:opacity-100 focus:opacity-100"
                  aria-label={`Delete ${f.name || 'field'}`}
                  title={count > 1 ? `Delete field and unwrap all ${count} places` : 'Delete field'}
                >
                  <Trash2 className="h-4 w-4 text-slate-400 hover:text-red-600" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

// ── Inspector ─────────────────────────────────────────────────────────

function FieldInspector({
  field,
  anchorCount,
  onChange,
  onClose,
  onJumpAnchor,
  onDeleteAnchor,
}: {
  field: FieldEntry | null;
  anchorCount: number;
  onChange: (patch: Partial<FieldEntry>) => void;
  onClose: () => void;
  onJumpAnchor: (idx: number) => void;
  onDeleteAnchor: (idx: number) => void;
}) {
  if (!field) return null;
  return (
    <section className="rounded-lg border border-brand-200 bg-white p-4 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">Field details</h3>
        <button
          type="button"
          onClick={onClose}
          className="text-xs text-slate-500 hover:text-slate-700"
        >
          Close
        </button>
      </header>
      <div className="space-y-3">
        <SmallField label="Name">
          <input
            value={field.name}
            onChange={(e) => onChange({ name: e.target.value })}
            className={inputClass}
            autoFocus
          />
        </SmallField>
        <SmallField label="Type">
          <select
            value={field.type}
            onChange={(e) => onChange({ type: e.target.value as FieldType })}
            className={inputClass}
          >
            {FIELD_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </SmallField>
        <SmallField label="Filled by">
          <div className="inline-flex rounded-md border border-slate-200 bg-white p-0.5 text-xs">
            {ASSIGNEES.map((a) => (
              <button
                key={a.value}
                type="button"
                onClick={() => onChange({
                  assignee: a.value,
                  defaultSource: a.value === 'AUTO' ? (field.defaultSource ?? AUTO_BINDINGS[0].value) : null,
                })}
                className={`px-2.5 py-1 font-medium ${
                  field.assignee === a.value
                    ? 'rounded bg-brand-700 text-white'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                {a.label}
              </button>
            ))}
          </div>
        </SmallField>
        {field.assignee === 'AUTO' && (
          <SmallField label="Auto value">
            <select
              value={field.defaultSource ?? AUTO_BINDINGS[0].value}
              onChange={(e) => onChange({ defaultSource: e.target.value })}
              className={inputClass}
            >
              {AUTO_BINDINGS.map((b) => (
                <option key={b.value} value={b.value}>{b.label}</option>
              ))}
            </select>
          </SmallField>
        )}
        <label className="inline-flex items-center gap-2 text-xs text-slate-700">
          <input
            type="checkbox"
            checked={field.required}
            onChange={(e) => onChange({ required: e.target.checked })}
            className="h-4 w-4 rounded border-slate-300"
          />
          Required
        </label>

        <div className="rounded-md border border-slate-200 bg-slate-50 p-3">
          <p className="text-xs font-semibold text-slate-700">
            Anchors{anchorCount > 0 && <span className="text-slate-500"> · {anchorCount} {anchorCount === 1 ? 'place' : 'places'}</span>}
          </p>
          <p className="mt-1 text-[11px] text-slate-500">
            Every anchor of this field is filled with the same value.
            Select more text in the document and choose <span className="font-medium">Use existing field</span> to add another anchor.
          </p>
          {anchorCount > 0 && (
            <ul className="mt-2 space-y-1">
              {Array.from({ length: anchorCount }, (_, idx) => (
                <li key={idx} className="flex items-center justify-between rounded border border-slate-200 bg-white px-2 py-1 text-xs">
                  <button
                    type="button"
                    onClick={() => onJumpAnchor(idx)}
                    className="flex-1 text-left font-medium text-slate-700 hover:text-slate-900"
                  >
                    Anchor {idx + 1}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDeleteAnchor(idx)}
                    className="text-slate-400 hover:text-red-600"
                    aria-label={`Remove anchor ${idx + 1}`}
                    title={anchorCount === 1
                      ? 'Remove this anchor (also removes the field)'
                      : 'Remove this anchor'}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <p className="text-xs text-slate-500">
          <Info className="mr-1 inline-block h-3 w-3" />
          Field values are collected when this template is used — Phase 2.
        </p>
      </div>
    </section>
  );
}

// ── Selection toolbar (with "Use existing" picker) ────────────────────

function SelectionToolbar({
  top,
  left,
  fields,
  onNewField,
  onLinkExisting,
}: {
  top: number;
  left: number;
  fields: FieldEntry[];
  onNewField: () => void;
  onLinkExisting: (id: string) => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(false);
  return (
    <div
      className="pointer-events-auto absolute z-10 -translate-x-1/2 rounded-md bg-slate-900 shadow-lg"
      style={{ top, left }}
      onMouseDown={(e) => {
        // Prevent focus shift so the underlying selection isn't cleared
        // when the admin clicks toolbar controls.
        e.preventDefault();
      }}
    >
      <div className="flex items-center">
        <button
          type="button"
          onClick={onNewField}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-white hover:bg-slate-800"
        >
          <Bookmark className="h-3.5 w-3.5" />
          New field
        </button>
        {fields.length > 0 && (
          <div className="relative border-l border-slate-700">
            <button
              type="button"
              onClick={() => setPickerOpen((v) => !v)}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-100 hover:bg-slate-800"
              aria-expanded={pickerOpen}
              aria-haspopup="listbox"
            >
              Use existing field
              <ChevronDown className="h-3 w-3" />
            </button>
            {pickerOpen && (
              <div
                role="listbox"
                className="absolute left-0 top-full z-20 mt-1 max-h-56 w-56 overflow-y-auto rounded-md border border-slate-200 bg-white py-1 text-xs shadow-xl"
              >
                {fields.map((f) => (
                  <button
                    key={f.id}
                    type="button"
                    role="option"
                    onClick={() => {
                      setPickerOpen(false);
                      onLinkExisting(f.id);
                    }}
                    className="flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left hover:bg-slate-50"
                  >
                    <span className="truncate font-medium text-slate-800">
                      {f.name || 'Untitled field'}
                    </span>
                    <span className={`shrink-0 rounded border px-1.5 py-0.5 text-[10px] font-medium ${assigneeTone(f.assignee)}`}>
                      {f.assignee}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function SmallField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-semibold text-slate-600">{label}</label>
      {children}
    </div>
  );
}

const inputClass =
  'w-full rounded-md border border-slate-200 px-2.5 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500';

// ── Re-upload dialog ──────────────────────────────────────────────────

function ReuploadDialog({
  template,
  hasUnsavedChanges,
  onClose,
  onUpload,
}: {
  template: TemplateRow;
  hasUnsavedChanges: boolean;
  onClose: () => void;
  onUpload: (f: File) => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Confirm-losing-unsaved-work check (F14): if the admin has dirty
  // field edits, require them to actively acknowledge those are about
  // to be discarded — a re-upload clears the schema server-side.
  const [ackedDirty, setAckedDirty] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);

  async function submit() {
    if (!file) return;
    setError(null);
    setUploading(true);
    try {
      await onUpload(file);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h2 className="text-base font-semibold text-slate-900">Replace source document</h2>
        <p className="mt-1 text-xs text-slate-500">
          Uploading a new Word document clears all existing fields on this
          template — the layout may drift between versions, so fields need
          re-selecting on the fresh render.
        </p>
        <div className="mt-4">
          <input
            ref={inputRef}
            type="file"
            accept=".docx"
            className="hidden"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          {!file ? (
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-6 text-sm font-medium text-slate-700 hover:bg-slate-100"
            >
              <UploadCloud className="h-5 w-5" />
              Choose a .docx file
            </button>
          ) : (
            <div className="rounded-md border border-slate-200 p-3 text-sm">
              <p className="font-medium text-slate-800">{file.name}</p>
              <p className="mt-0.5 text-xs text-slate-500">{humanBytes(file.size)}</p>
            </div>
          )}
        </div>
        {template.hasSource && (
          <p className="mt-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            This template already has a source file. The new upload replaces it and
            resets the field list.
          </p>
        )}
        {hasUnsavedChanges && (
          <div className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
            <p className="flex items-start gap-2 font-medium">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              You have unsaved field changes on this template.
            </p>
            <p className="mt-1 pl-5">
              Replacing the source now will discard those edits. Consider
              cancelling first, saving your changes, then replacing.
            </p>
            <label className="mt-2 flex items-start gap-2 pl-5">
              <input
                type="checkbox"
                checked={ackedDirty}
                onChange={(e) => setAckedDirty(e.target.checked)}
                className="mt-0.5 h-3.5 w-3.5 rounded border-red-300"
              />
              <span>Yes, discard my unsaved edits and replace the source.</span>
            </label>
          </div>
        )}
        {error && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
            {error}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={uploading}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void submit()}
            disabled={!file || uploading || (hasUnsavedChanges && !ackedDirty)}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {uploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
            Replace + reset fields
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Helpers ───────────────────────────────────────────────────────────

function generateUuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `f-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

// Pick a usable rectangle for the selection toolbar. Some ranges (pure
// underscores, whitespace, decorative-underline empty spans) yield a
// (0, 0) getBoundingClientRect even though the selection is real; walk
// the client-rects list first, then fall back to the start container's
// element rect. Returns null only when nothing usable exists.
function anchorRectForRange(range: Range): DOMRect | null {
  const primary = range.getBoundingClientRect();
  if (primary.width > 0 || primary.height > 0) return primary;
  const rects = Array.from(range.getClientRects());
  const nonEmpty = rects.find((r) => r.width > 0 || r.height > 0);
  if (nonEmpty) return nonEmpty;
  const node = range.startContainer;
  const el =
    node.nodeType === Node.ELEMENT_NODE
      ? (node as Element)
      : node.parentElement;
  if (el) {
    const elRect = el.getBoundingClientRect();
    if (elRect.width > 0 || elRect.height > 0) return elRect;
  }
  return null;
}

// Unwrap a doc-field span: move every child up to the parent, then remove
// the span. Used by delete-field and delete-anchor.
function unwrapSpan(el: Element) {
  const parent = el.parentNode;
  if (!parent) return;
  while (el.firstChild) parent.insertBefore(el.firstChild, el);
  parent.removeChild(el);
}

// Count anchors (data-field-id spans) per field id. Re-runs whenever the
// fields list changes or the canvas re-renders, so counts stay honest as
// the admin adds/removes anchors.
function useAnchorCounts(
  canvasRef: React.RefObject<HTMLDivElement | null>,
  fields: FieldEntry[],
  rendered: boolean,
): Map<string, number> {
  const [counts, setCounts] = useState<Map<string, number>>(new Map());
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !rendered) {
      setCounts(new Map());
      return;
    }
    const next = new Map<string, number>();
    for (const f of fields) {
      next.set(
        f.id,
        canvas.querySelectorAll(`[data-field-id="${f.id}"]`).length,
      );
    }
    setCounts(next);
  }, [canvasRef, fields, rendered]);
  return counts;
}

function defaultFieldName(seq: number): string {
  return `Field ${seq}`;
}

function validateFields(fields: FieldEntry[]): { error: string | null } {
  if (fields.length === 0) return { error: 'Add at least one field before saving.' };
  const names = new Map<string, number>();
  for (const f of fields) {
    if (!f.name.trim()) return { error: 'Every field needs a name.' };
    const key = f.name.trim().toLowerCase();
    names.set(key, (names.get(key) ?? 0) + 1);
  }
  for (const [name, count] of names) {
    if (count > 1) return { error: `Duplicate field name: "${name}". Rename one of them.` };
  }
  return { error: null };
}

function applyOwnershipTints(canvas: HTMLElement | null, fields: FieldEntry[]) {
  if (!canvas) return;
  const byId = new Map(fields.map((f) => [f.id, f]));
  canvas.querySelectorAll(`.${DOC_FIELD_CLASS}`).forEach((el) => {
    const id = el.getAttribute('data-field-id');
    const f = id ? byId.get(id) : null;
    el.classList.remove('doc-field--erm', 'doc-field--intern', 'doc-field--auto');
    const assignee: FieldAssignee = f?.assignee ?? 'ERM';
    el.classList.add(`doc-field--${assignee.toLowerCase()}`);
  });
}

function resetPreviewClasses(canvas: HTMLElement | null) {
  if (!canvas) return;
  canvas.querySelectorAll(`.${DOC_FIELD_CLASS}`).forEach((el) => {
    el.classList.remove('doc-field--preview-locked', 'doc-field--preview-input');
  });
  // The formatting toolbar's active-target outline is a studio-only
  // affordance. save() calls this immediately before reading
  // canvas.innerHTML, so stripping it here is what guarantees the
  // marker can never reach the persisted canonical HTML — and from
  // there the intern's rendered document and the executed PDF.
  //
  // NOTE this strips only the VISUAL outline class. The formatting
  // toolbar's data-fmt-admin override marker is deliberately NOT
  // touched: it MUST persist so the backend profile corrector can tell
  // a deliberate admin override from an imported value on every
  // subsequent save.
  clearFormatHighlight(canvas);
  // Viewport-derived zoom that DocumentPreviewFrame writes onto the
  // page wrapper — junk that has no business in shared canonical HTML.
  stripStoredZoom(canvas);
}

function applyPreviewMode(
  canvas: HTMLElement | null,
  mode: PreviewMode,
  fields: FieldEntry[],
) {
  if (!canvas) return;
  resetPreviewClasses(canvas);
  if (mode === 'edit') return;
  const byId = new Map(fields.map((f) => [f.id, f]));
  canvas.querySelectorAll(`.${DOC_FIELD_CLASS}`).forEach((el) => {
    const id = el.getAttribute('data-field-id');
    const f = id ? byId.get(id) : null;
    if (!f) return;
    const seenByThisParty =
      (mode === 'erm' && (f.assignee === 'ERM' || f.assignee === 'AUTO')) ||
      (mode === 'intern' && f.assignee === 'INTERN');
    el.classList.add(seenByThisParty ? 'doc-field--preview-input' : 'doc-field--preview-locked');
  });
}
