'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Download, ExternalLink, FileText, Loader2 } from 'lucide-react';
import api from '@/lib/careers/api';

interface Props {
  resume: {
    documentId: string;
    fileName: string;
    fileSize: number | null;
    mimeType: string | null;
    downloadUrl: string;
  } | null;
}

/**
 * ERM-side inline preview of a candidate's resume — supports any upload
 * format the intern side accepts (pdf/doc/docx/image). The bytes come
 * through the axios client (blob response) so the Bearer token + baseURL
 * apply, same pattern as the weekly-report attachment fix.
 *
 *   • PDF   → rendered in an iframe from a same-origin blob URL.
 *   • DOCX  → converted to HTML in-browser via mammoth.js (dynamic
 *             import so it code-splits) and rendered inline as styled
 *             prose. No server-side conversion, no LibreOffice.
 *   • image → rendered inline as an <img>.
 *   • doc / anything else mammoth can't render → clean "preview not
 *             available" fallback with a token-aware Download button.
 *
 * The Download button re-uses the fetched blob (already has the file in
 * memory) so a plain download click stays token-aware without a second
 * network trip.
 */
export default function ResumePreview({ resume }: Props) {
  const [buffer, setBuffer] = useState<ArrayBuffer | null>(null);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [docxHtml, setDocxHtml] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [converting, setConverting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Track the URL we created so cleanup always revokes the right one,
  // even if state has moved on.
  const createdRef = useRef<string | null>(null);

  const kind = useMemo(() => detectKind(resume), [resume]);
  const canPreview = kind === 'pdf' || kind === 'docx' || kind === 'image';

  const load = useCallback(async () => {
    if (!resume) return;
    setLoading(true);
    setErr(null);
    setDocxHtml(null);
    try {
      const res = await api.get<ArrayBuffer>(resume.downloadUrl, {
        responseType: 'arraybuffer',
      });
      const ab = res.data;
      const mime = resume.mimeType || guessMimeFromKind(kind);
      const url = URL.createObjectURL(new Blob([ab], { type: mime }));
      if (createdRef.current) URL.revokeObjectURL(createdRef.current);
      createdRef.current = url;
      setBuffer(ab);
      setBlobUrl(url);
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
        message?: string;
      };
      setErr(
        ax.response?.data?.error
          ?? ax.message
          ?? 'Failed to load resume',
      );
    } finally {
      setLoading(false);
    }
  }, [resume, kind]);

  // Fetch once per resume — mammoth conversion (below) runs off the
  // ArrayBuffer we already have, so switching between kinds doesn't
  // re-hit the network.
  useEffect(() => {
    if (!resume) return;
    void load();
    return () => {
      if (createdRef.current) {
        URL.revokeObjectURL(createdRef.current);
        createdRef.current = null;
      }
    };
    // load captures resume + kind — refire only when the resume id changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resume?.documentId]);

  // Convert DOCX → HTML lazily via a dynamic import so mammoth only
  // ships to the browser when someone actually opens a .docx.
  useEffect(() => {
    if (kind !== 'docx' || !buffer) return;
    let cancelled = false;
    setConverting(true);
    (async () => {
      try {
        const mammoth = await import('mammoth/mammoth.browser');
        const result = await mammoth.convertToHtml({ arrayBuffer: buffer });
        if (!cancelled) setDocxHtml(result.value ?? '');
      } catch (e) {
        if (!cancelled) {
          setErr((e as Error)?.message ?? 'Failed to render document');
        }
      } finally {
        if (!cancelled) setConverting(false);
      }
    })();
    return () => { cancelled = true; };
  }, [kind, buffer]);

  if (!resume) {
    return (
      <div className="flex items-center gap-3 rounded-md border border-dashed border-slate-300 bg-slate-50 p-6 text-sm text-slate-500">
        <FileText className="h-5 w-5 text-slate-400" />
        No resume on this application.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-slate-900" title={resume.fileName}>
            {resume.fileName}
          </p>
          <p className="text-[11px] text-slate-500">
            {fmtBytes(resume.fileSize)}
            {resume.mimeType && ` · ${resume.mimeType}`}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {blobUrl && kind !== 'docx' && (
            <a
              href={blobUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              <ExternalLink className="h-3.5 w-3.5" /> Open in new tab
            </a>
          )}
          {/* Token-aware download: the blob was fetched via axios with the
              Bearer token, so serving it back through a same-origin object
              URL avoids the un-authed plain-anchor trap. */}
          <a
            href={blobUrl ?? '#'}
            download={blobUrl ? resume.fileName : undefined}
            aria-disabled={!blobUrl}
            onClick={(e) => { if (!blobUrl) e.preventDefault(); }}
            className={
              'inline-flex items-center gap-1 rounded-md px-3 py-1.5 text-xs font-semibold ' +
              (blobUrl
                ? 'bg-brand-700 text-white hover:bg-brand-800'
                : 'cursor-not-allowed bg-slate-300 text-slate-600')
            }
          >
            <Download className="h-3.5 w-3.5" /> Download
          </a>
        </div>
      </header>

      {err && (
        <div className="space-y-2 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
          <p>{err}</p>
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1 rounded-md border border-red-200 bg-white px-3 py-1 text-xs font-medium text-red-800 hover:bg-red-50"
          >
            Retry
          </button>
        </div>
      )}

      {!err && loading && !buffer && (
        <div className="flex h-[720px] items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-sm text-slate-500">
          <Loader2 className="mr-2 h-4 w-4 animate-spin" /> Loading resume…
        </div>
      )}

      {!err && buffer && kind === 'pdf' && blobUrl && (
        <div className="overflow-hidden rounded-md border border-slate-200 bg-slate-50">
          <iframe
            src={blobUrl}
            title={`Resume preview: ${resume.fileName}`}
            className="h-[720px] w-full"
          />
        </div>
      )}

      {!err && buffer && kind === 'docx' && (
        <div className="overflow-hidden rounded-md border border-slate-200 bg-white">
          {converting && !docxHtml ? (
            <div className="flex h-[720px] items-center justify-center text-sm text-slate-500">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" /> Rendering document…
            </div>
          ) : docxHtml != null ? (
            <div
              className="h-[720px] overflow-y-auto p-6 text-sm text-slate-800 [&_h1]:mt-4 [&_h1]:mb-2 [&_h1]:text-lg [&_h1]:font-semibold [&_h1]:text-slate-900 [&_h2]:mt-3 [&_h2]:mb-2 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:text-slate-900 [&_h3]:mt-2 [&_h3]:mb-1 [&_h3]:text-sm [&_h3]:font-semibold [&_h3]:text-slate-900 [&_p]:my-2 [&_p]:leading-relaxed [&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-6 [&_li]:my-1 [&_strong]:font-semibold [&_strong]:text-slate-900 [&_em]:italic [&_a]:text-brand-700 [&_a]:underline [&_table]:my-3 [&_table]:w-full [&_table]:border-collapse [&_th]:border [&_th]:border-slate-200 [&_th]:px-2 [&_th]:py-1 [&_th]:text-left [&_th]:font-semibold [&_td]:border [&_td]:border-slate-200 [&_td]:px-2 [&_td]:py-1 [&_img]:my-2 [&_img]:max-w-full"
              // mammoth output is well-formed HTML derived from the docx
              // structure — no user-supplied markup path, so this is safe
              // to render as HTML for the inline preview.
              dangerouslySetInnerHTML={{ __html: docxHtml }}
            />
          ) : null}
        </div>
      )}

      {!err && buffer && kind === 'image' && blobUrl && (
        <div className="max-h-[720px] overflow-auto rounded-md border border-slate-200 bg-slate-50 p-2">
          <img
            src={blobUrl}
            alt={`Resume: ${resume.fileName}`}
            className="mx-auto max-w-full"
          />
        </div>
      )}

      {!err && buffer && !canPreview && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-xs text-amber-900">
          <p className="font-semibold">Preview not available for this format.</p>
          <p className="mt-1">
            Legacy <code>.doc</code> and other formats can&apos;t render inline
            in the browser. Use <strong>Download</strong> above to open it
            locally, or ask the candidate to re-upload as PDF or DOCX.
          </p>
        </div>
      )}
    </div>
  );
}

type Kind = 'pdf' | 'docx' | 'doc' | 'image' | 'other';

function detectKind(resume: Props['resume']): Kind {
  if (!resume) return 'other';
  const mime = (resume.mimeType ?? '').toLowerCase();
  const name = (resume.fileName ?? '').toLowerCase();
  if (mime === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
  if (mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
      || name.endsWith('.docx')) return 'docx';
  if (mime === 'application/msword'
      || (name.endsWith('.doc') && !name.endsWith('.docx'))) return 'doc';
  if (mime.startsWith('image/')
      || name.endsWith('.jpg') || name.endsWith('.jpeg')
      || name.endsWith('.png') || name.endsWith('.webp')
      || name.endsWith('.gif')) return 'image';
  return 'other';
}

function guessMimeFromKind(kind: Kind): string {
  switch (kind) {
    case 'pdf': return 'application/pdf';
    case 'docx':
      return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    case 'doc': return 'application/msword';
    case 'image': return 'image/jpeg';
    default: return 'application/octet-stream';
  }
}

function fmtBytes(n: number | null): string {
  if (n == null) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
