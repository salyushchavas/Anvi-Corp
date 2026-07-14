'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  Clock,
  Download,
  FileText,
  FolderArchive,
  Mail,
  RefreshCw,
} from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';

interface FileRef {
  documentId: string;
  fileName: string;
  mimeType: string | null;
  fileSize: number;
  uploadedAt: string | null;
}

interface TaskView {
  taskId: string;
  documentKey: string | null;
  documentTitle: string | null;
  category: string | null;
  sensitivity: string | null;
  status: string;
  version: number | null;
  uploadedFile: FileRef | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewReasonCode: string | null;
  reviewComments: string | null;
}

interface PacketView {
  packetId: string;
  status: string;
  assignedAt: string | null;
  internSubmittedAt: string | null;
  completedAt: string | null;
  customInstructions: string | null;
  tasks: TaskView[];
}

interface InternGalleryDetail {
  lifecycleId: string;
  userId: string | null;
  employeeId: string | null;
  fullName: string | null;
  email: string | null;
  activeStatus: string | null;
  packets: PacketView[];
}

export default function ErmDocumentGalleryDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <DetailInner />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function DetailInner() {
  const params = useParams<{ lifecycleId: string }>();
  const lifecycleId = params?.lifecycleId;
  const [data, setData] = useState<InternGalleryDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!lifecycleId) return;
    setLoading(true);
    try {
      const res = await api.get<InternGalleryDetail>(
        `/api/v1/erm/document-gallery/interns/${lifecycleId}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId]);

  useEffect(() => { void load(); }, [load]);

  if (loading && !data) {
    return (
      <div className="mx-auto max-w-5xl space-y-4 p-6">
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }
  if (err || !data) {
    return (
      <div className="mx-auto max-w-5xl space-y-4 p-6">
        <Link href="/careers/erm/document-gallery"
          className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
          <ChevronLeft className="h-4 w-4" /> Back to gallery
        </Link>
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Intern not found'}
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <div>
        <Link href="/careers/erm/document-gallery"
          className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
          <ChevronLeft className="h-4 w-4" /> Back to gallery
        </Link>
      </div>

      <header className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="inline-flex items-center gap-2 text-xl font-semibold text-slate-900">
              <FolderArchive className="h-5 w-5 text-brand-700" />
              {data.fullName ?? 'Intern'}
            </h1>
            <p className="mt-0.5 text-xs text-slate-500">
              {data.employeeId ?? 'no employee id'}
              {data.email && (
                <span className="ml-2 inline-flex items-center gap-0.5">
                  <Mail className="h-3 w-3" /> {data.email}
                </span>
              )}
            </p>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
        </div>
      </header>

      {data.packets.length === 0 ? (
        <p className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No document packets have been assigned to this intern yet.
        </p>
      ) : (
        <div className="space-y-4">
          {data.packets.map((pk) => <PacketCard key={pk.packetId} pk={pk} />)}
        </div>
      )}
    </div>
  );
}

function PacketCard({ pk }: { pk: PacketView }) {
  const uploaded = pk.tasks.filter((t) => t.uploadedFile).length;
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">
            Packet · {pk.status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase())}
          </h2>
          <p className="text-[11px] text-slate-500">
            {pk.assignedAt && <>Assigned {new Date(pk.assignedAt).toLocaleString()}</>}
            {pk.internSubmittedAt && <> · Submitted {new Date(pk.internSubmittedAt).toLocaleString()}</>}
            {pk.completedAt && <> · Completed {new Date(pk.completedAt).toLocaleString()}</>}
          </p>
        </div>
        <span className="text-xs text-slate-600">
          <strong className="text-slate-900">{uploaded}</strong> of {pk.tasks.length} uploaded
        </span>
      </header>
      {pk.customInstructions && (
        <p className="border-b border-slate-100 bg-slate-50 px-4 py-2 text-[11px] text-slate-600 whitespace-pre-wrap">
          {pk.customInstructions}
        </p>
      )}
      <ul className="divide-y divide-slate-100">
        {pk.tasks.map((t) => <TaskRow key={t.taskId} t={t} />)}
      </ul>
    </section>
  );
}

function TaskRow({ t }: { t: TaskView }) {
  const tone = statusTone(t.status);
  const iconNode = statusIcon(t.status);
  return (
    <li className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <FileText className="h-3.5 w-3.5 text-slate-500" />
          <p className="text-sm font-medium text-slate-900">
            {t.documentTitle ?? t.documentKey ?? 'Untitled document'}
          </p>
          <span className={
            'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold ' + tone
          }>
            {iconNode}{prettyStatus(t.status)}
          </span>
        </div>
        <p className="mt-0.5 text-[11px] text-slate-500">
          {t.category && <span>{t.category}</span>}
          {t.sensitivity && <span> · {t.sensitivity}</span>}
          {t.uploadedFile?.uploadedAt && (
            <span> · Uploaded {new Date(t.uploadedFile.uploadedAt).toLocaleString()}</span>
          )}
          {t.uploadedFile?.fileSize != null && (
            <span> · {formatFileSize(t.uploadedFile.fileSize)}</span>
          )}
        </p>
        {(t.status === 'REJECTED' || t.status === 'RESEND_REQUESTED')
          && t.reviewComments && (
          <p className="mt-1 rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-900 whitespace-pre-wrap">
            <strong>{t.reviewReasonCode ?? 'Revision requested'}:</strong> {t.reviewComments}
          </p>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {t.uploadedFile ? (
          <DownloadButton taskId={t.taskId} fileName={t.uploadedFile.fileName} />
        ) : (
          <span className="text-[11px] text-slate-400">No file</span>
        )}
      </div>
    </li>
  );
}

function DownloadButton({ taskId, fileName }: { taskId: string; fileName: string }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function download() {
    setBusy(true);
    setErr(null);
    try {
      // Reuses the existing /document-review/tasks/{id}/file endpoint
      // that's already gated to ERM / SUPER_ADMIN and handles the S3
      // dual-resolver. No new download path was added for the gallery.
      const res = await api.get(
        `/api/v1/erm/document-review/tasks/${taskId}/file`,
        { responseType: 'blob' },
      );
      const blob = res.data as Blob;
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName || 'document.pdf';
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Download failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-end">
      <button
        type="button"
        onClick={download}
        disabled={busy}
        className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-brand-700 hover:bg-brand-50 disabled:opacity-60"
      >
        <Download className="h-3 w-3" />
        {busy ? 'Downloading…' : 'Download'}
      </button>
      {err && <p className="mt-1 text-[10px] text-red-700">{err}</p>}
    </div>
  );
}

function statusTone(s: string): string {
  switch (s) {
    case 'ACCEPTED':
    case 'WAIVED':            return 'bg-green-100 text-green-800';
    case 'SUBMITTED':
    case 'UNDER_REVIEW':      return 'bg-slate-100 text-slate-700';
    case 'REJECTED':
    case 'RESEND_REQUESTED':  return 'bg-red-100 text-red-800';
    case 'PENDING':           return 'bg-amber-100 text-amber-800';
    default:                  return 'bg-slate-100 text-slate-600';
  }
}

function statusIcon(s: string): React.ReactNode {
  switch (s) {
    case 'ACCEPTED':
    case 'WAIVED':            return <CheckCircle2 className="h-3 w-3" />;
    case 'REJECTED':
    case 'RESEND_REQUESTED':  return <AlertTriangle className="h-3 w-3" />;
    case 'PENDING':           return <Clock className="h-3 w-3" />;
    default:                  return null;
  }
}

function prettyStatus(s: string): string {
  return s.replace(/_/g, ' ').toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
