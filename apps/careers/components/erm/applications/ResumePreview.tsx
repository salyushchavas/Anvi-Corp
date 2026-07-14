'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Download, ExternalLink, FileText, Loader2 } from 'lucide-react';
import api from '@/lib/api';

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
 * ERM-side inline preview of a candidate's resume. Reuses the existing
 * GET /api/v1/resumes/{id}/download endpoint (already authorized for
 * ERM via PreAuthorize + row-level ensureCanDownload). The endpoint's
 * Content-Disposition: attachment only forces download on direct
 * navigation — fetching as a blob via axios lets us render the bytes
 * inline by handing the browser a same-origin object URL with the
 * correct mime type.
 *
 * PDFs render in an iframe; doc/docx have no reliable in-browser
 * viewer, so we surface a clean "preview not supported" state alongside
 * the original Download button. No resume → "No resume on file".
 */
export default function ResumePreview({ resume }: Props) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Track the URL we created so cleanup always revokes the right one,
  // even if state has moved on.
  const createdRef = useRef<string | null>(null);

  const mime = resume?.mimeType ?? '';
  const isPdf = mime === 'application/pdf'
    || (resume?.fileName?.toLowerCase().endsWith('.pdf') ?? false);

  const load = useCallback(async () => {
    if (!resume) return;
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<Blob>(resume.downloadUrl, {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(
        new Blob([res.data], { type: mime || 'application/octet-stream' }),
      );
      // Revoke any prior URL before swapping.
      if (createdRef.current) URL.revokeObjectURL(createdRef.current);
      createdRef.current = url;
      setBlobUrl(url);
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
      setErr(
        ax.response?.data?.error
          ?? ax.message
          ?? 'Failed to load resume',
      );
    } finally {
      setLoading(false);
    }
  }, [resume, mime]);

  // Lazy fetch — only when a PDF is on file. Non-PDFs skip the network
  // call since we can't preview them inline anyway.
  useEffect(() => {
    if (!resume || !isPdf) return;
    void load();
    return () => {
      if (createdRef.current) {
        URL.revokeObjectURL(createdRef.current);
        createdRef.current = null;
      }
    };
    // load is intentionally captured at first effect to avoid re-firing on
    // every render; eslint disable is scoped + documented.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resume?.documentId, isPdf]);

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
          {blobUrl && (
            <a
              href={blobUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              <ExternalLink className="h-3.5 w-3.5" /> Open in new tab
            </a>
          )}
          <a
            href={resume.downloadUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            <Download className="h-3.5 w-3.5" /> Download
          </a>
        </div>
      </header>

      {isPdf ? (
        <div className="overflow-hidden rounded-md border border-slate-200 bg-slate-50">
          {loading && !blobUrl && (
            <div className="flex h-[720px] items-center justify-center text-sm text-slate-500">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Loading resume…
            </div>
          )}
          {err && !blobUrl && (
            <div className="space-y-2 p-6 text-sm">
              <p className="rounded-md border border-red-200 bg-red-50 p-3 text-red-800">
                {err}
              </p>
              <button
                type="button"
                onClick={() => void load()}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                Retry
              </button>
            </div>
          )}
          {blobUrl && (
            <iframe
              src={blobUrl}
              title={`Resume preview: ${resume.fileName}`}
              className="h-[720px] w-full"
            />
          )}
        </div>
      ) : (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-xs text-amber-900">
          <p className="font-semibold">Inline preview not supported for this file type.</p>
          <p className="mt-1">
            Browsers can&apos;t render <code>{resume.mimeType ?? 'this format'}</code>{' '}
            inline. Use <strong>Download</strong> above to open it locally,
            or ask the candidate to upload a PDF version.
          </p>
        </div>
      )}
    </div>
  );
}

function fmtBytes(n: number | null): string {
  if (n == null) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
