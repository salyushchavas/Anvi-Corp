'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Download, ExternalLink, FileText, Loader2 } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Inline preview + download for a weekly-report attachment. Same shape as
 * {@link ../erm/applications/ResumePreview}: fetch the bytes via axios
 * (so the interceptor supplies the correct baseURL + Bearer token),
 * render PDFs in an iframe via {@code URL.createObjectURL}, fall back to
 * a download-only prompt for doc/docx.
 *
 * <p>Why blob-based instead of a plain {@code <a href>}: the download URL
 * from the API is relative ({@code /api/v1/weekly-reports/{id}/attachment}),
 * so a plain anchor navigates to the Next.js host (404), and even a
 * fully-qualified URL wouldn&apos;t carry the {@code Authorization} header
 * that lives on the axios client. Fetching via axios sidesteps both.</p>
 */
export interface WeeklyReportAttachment {
  documentId: string | null;
  fileName: string | null;
  fileSize: number | null;
  mimeType: string | null;
  /** Server-provided relative path — passed straight to axios. */
  downloadUrl: string | null;
}

interface Props {
  attachment: WeeklyReportAttachment | null;
  /** Height of the embedded iframe. Defaults to {@code 620px} — the ERM/Evaluator
      review modals want a tighter frame than the resume drawer&apos;s 720. */
  height?: number;
}

export default function WeeklyReportAttachmentPreview({ attachment, height = 620 }: Props) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const createdRef = useRef<string | null>(null);

  const hasFile = !!(attachment && attachment.downloadUrl);
  const mime = attachment?.mimeType ?? '';
  const isPdf = hasFile
    && (mime === 'application/pdf'
        || (attachment?.fileName?.toLowerCase().endsWith('.pdf') ?? false));

  const load = useCallback(async () => {
    if (!attachment?.downloadUrl) return;
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<Blob>(attachment.downloadUrl, {
        responseType: 'blob',
      });
      const url = URL.createObjectURL(
        new Blob([res.data], { type: mime || 'application/octet-stream' }),
      );
      if (createdRef.current) URL.revokeObjectURL(createdRef.current);
      createdRef.current = url;
      setBlobUrl(url);
    } catch (e) {
      const ax = e as {
        response?: { status?: number; data?: { error?: string } };
        message?: string;
      };
      setErr(
        ax.response?.data?.error
          ?? ax.message
          ?? 'Failed to load attachment',
      );
    } finally {
      setLoading(false);
    }
  }, [attachment, mime]);

  // Lazy-fetch only PDFs (we can render them inline). Non-PDFs skip the
  // network call — the reviewer downloads them via the Download button
  // which also goes through axios.
  useEffect(() => {
    if (!hasFile || !isPdf) return;
    void load();
    return () => {
      if (createdRef.current) {
        URL.revokeObjectURL(createdRef.current);
        createdRef.current = null;
      }
    };
    // load is intentionally captured — re-firing on every render would
    // re-download the file whenever the parent re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attachment?.documentId, isPdf]);

  async function forceDownload() {
    if (!attachment?.downloadUrl) return;
    try {
      // Reuse the already-loaded blob when we have one (PDF branch);
      // otherwise fetch fresh for doc/docx.
      let url = blobUrl;
      let revokeAfter = false;
      if (!url) {
        setLoading(true);
        const res = await api.get<Blob>(attachment.downloadUrl, {
          responseType: 'blob',
        });
        url = URL.createObjectURL(
          new Blob([res.data], { type: mime || 'application/octet-stream' }),
        );
        revokeAfter = true;
      }
      const a = document.createElement('a');
      a.href = url;
      a.download = attachment.fileName ?? 'weekly-report';
      document.body.appendChild(a);
      a.click();
      a.remove();
      if (revokeAfter) {
        // Give the browser a beat to start the download before revoking.
        setTimeout(() => URL.revokeObjectURL(url!), 1000);
      }
    } catch (e) {
      const ax = e as {
        response?: { data?: { error?: string } };
        message?: string;
      };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Download failed');
    } finally {
      setLoading(false);
    }
  }

  if (!hasFile) {
    return (
      <div className="flex items-center gap-3 rounded-md border border-dashed border-slate-300 bg-slate-50 p-6 text-sm text-slate-500">
        <FileText className="h-5 w-5 text-slate-400" />
        <span>No attachment on this weekly report.</span>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-slate-900" title={attachment!.fileName ?? undefined}>
            {attachment!.fileName ?? 'weekly-report'}
          </p>
          <p className="text-[11px] text-slate-500">
            {fmtBytes(attachment!.fileSize)}
            {attachment!.mimeType && ` · ${attachment!.mimeType}`}
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
          <button
            type="button"
            onClick={forceDownload}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            <Download className="h-3.5 w-3.5" /> Download
          </button>
        </div>
      </header>

      {isPdf ? (
        <div className="overflow-hidden rounded-md border border-slate-200 bg-slate-50">
          {loading && !blobUrl && (
            <div
              className="flex items-center justify-center text-sm text-slate-500"
              style={{ height }}
            >
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              Loading attachment…
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
              title={`Weekly report attachment: ${attachment!.fileName ?? 'file'}`}
              className="w-full"
              style={{ height }}
            />
          )}
        </div>
      ) : (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-4 text-xs text-amber-900">
          <p className="font-semibold">Inline preview not supported for this file type.</p>
          <p className="mt-1">
            Browsers can&apos;t render <code>{attachment!.mimeType ?? 'this format'}</code>{' '}
            inline. Use <strong>Download</strong> above to open it locally.
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
