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

  // Field state — the source of truth once the studio is up.
  const [fields, setFields] = useState<FieldEntry[]>([]);
  const [inspecting, setInspecting] = useState<string | null>(null);

  // Save / re-upload machinery.
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState<string | null>(null);
  const [previewMode, setPreviewMode] = useState<PreviewMode>('edit');
  const [reuploadOpen, setReuploadOpen] = useState(false);

  // ── Load the template detail ─────────────────────────────────────
  const load = useCallback(async () => {
    if (!key) return;
    setLoading(true);
    try {
      const res = await api.get<TemplateRow>(
        `/api/v1/admin/editable-templates/by-key/${encodeURIComponent(key)}`,
      );
      setTemplate(res.data);
      setFields(parseFieldSchema(res.data.fieldSchemaJson));
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
      setFields(parseFieldSchema(res.data.fieldSchemaJson));
      toast.success('Template saved.');
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
        onReupload={() => setReuploadOpen(true)}
        saving={saving}
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

          <div className="relative">
            <div
              ref={canvasRef}
              className="doc-canvas min-h-[400px] max-h-[calc(100vh-260px)] overflow-y-auto bg-slate-50 p-6"
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
          onClose={() => setReuploadOpen(false)}
          onUpload={reuploadSource}
        />
      )}

      <style jsx global>{`
        .doc-canvas .docx {
          background: white;
          box-shadow: 0 1px 3px 0 rgb(0 0 0 / 0.1);
          margin: 0 auto;
        }
        .doc-field {
          border-radius: 3px;
          padding: 0 2px;
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
  onReupload,
  saving,
  validationError,
}: {
  template: TemplateRow;
  previewMode: PreviewMode;
  onModeChange: (m: PreviewMode) => void;
  onSave: () => void;
  onReupload: () => void;
  saving: boolean;
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
          onClick={onSave}
          disabled={saving}
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
  onClose,
  onUpload,
}: {
  template: TemplateRow;
  onClose: () => void;
  onUpload: (f: File) => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
            disabled={!file || uploading}
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
