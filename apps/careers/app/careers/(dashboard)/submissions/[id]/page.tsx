'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import dynamic from 'next/dynamic';
import { CheckCircle2, Clock, RotateCcw } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { formatFull, formatRelative } from '@/lib/format-date';
import type {
  ReviewOutcome,
  SubmissionDetail,
  WorkspaceFile,
  WorkspaceSubmission,
} from '@/types';

/**
 * Read-only Monaco view of a single immutable submission. The Tech Evaluator
 * (and Reporting Manager / SUPER_ADMIN) lands here from the project list and
 * from email notifications; the intern who submitted can also view their own
 * snapshot. Action buttons hide for the intern.
 */
const MonacoEditor = dynamic(() => import('@monaco-editor/react'), {
  ssr: false,
  loading: () => (
    <div className="flex h-full items-center justify-center text-sm text-gray-400">
      Loading viewer…
    </div>
  ),
});

const REASON_MIN = 10;
const REASON_MAX = 2000;

export default function SubmissionReviewPage() {
  return (
    <ProtectedRoute
      requiredRoles={['TRAINER', 'REPORTING_MANAGER', 'SUPER_ADMIN', 'INTERN']}
    >
      <DashboardLayout title="Submission review">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const submissionId = params?.id;
  const { user } = useAuth();

  const [detail, setDetail] = useState<SubmissionDetail | null>(null);
  const [openPath, setOpenPath] = useState<string | null>(null);
  const [openContent, setOpenContent] = useState<string>('');
  const [loadingFile, setLoadingFile] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState<'approve' | 'return' | null>(null);
  const [confirmApprove, setConfirmApprove] = useState(false);
  const [returnOpen, setReturnOpen] = useState(false);
  const [reason, setReason] = useState('');

  const roles = user?.roles ?? [];
  const isIntern = roles.includes('INTERN');
  const canApprove =
    roles.includes('TRAINER') || roles.includes('SUPER_ADMIN');
  const canReturn =
    roles.includes('TRAINER')
    || roles.includes('REPORTING_MANAGER')
    || roles.includes('SUPER_ADMIN');

  const submission = detail?.submission ?? null;
  const files = detail?.files ?? [];
  const pending = submission?.reviewOutcome === 'PENDING';

  // ── Loaders ────────────────────────────────────────────────────────────

  const load = useCallback(async () => {
    if (!submissionId) return;
    setError(null);
    try {
      const res = await api.get<SubmissionDetail>(
        `/api/v1/submissions/${submissionId}`,
      );
      setDetail(res.data);
      if (res.data.files.length > 0 && openPath == null) {
        await openFile(submissionId, res.data.files[0].path);
      }
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the submission.");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [submissionId]);

  useEffect(() => {
    void load();
  }, [load]);

  const openFile = useCallback(
    async (sid: string, path: string) => {
      setLoadingFile(true);
      try {
        const res = await api.get<WorkspaceFile>(
          `/api/v1/submissions/${sid}/files/${encodeFilePath(path)}`,
        );
        setOpenPath(path);
        setOpenContent(res.data.content ?? '');
      } catch (err: any) {
        toast.error(err?.response?.data?.error ?? "Couldn't open the file.");
      } finally {
        setLoadingFile(false);
      }
    },
    [],
  );

  // ── Actions ────────────────────────────────────────────────────────────

  async function approve() {
    if (!submissionId || busy !== null) return;
    setBusy('approve');
    try {
      await api.post(`/api/v1/submissions/${submissionId}/approve`);
      toast.success('Submission approved.');
      // Bounce back to the evaluator project board so the reviewer can pick
      // the next one up without an extra click.
      router.push('/careers/trainer/projects');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't approve.");
      setBusy(null);
    }
  }

  async function returnForRevisions() {
    if (!submissionId || busy !== null) return;
    const trimmed = reason.trim();
    if (trimmed.length < REASON_MIN || trimmed.length > REASON_MAX) return;
    setBusy('return');
    try {
      await api.post(`/api/v1/submissions/${submissionId}/return`, {
        reason: trimmed,
      });
      toast.success('Returned for revisions.');
      router.push('/careers/trainer/projects');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't return.");
      setBusy(null);
    }
  }

  // ── Render ────────────────────────────────────────────────────────────

  if (loading) return <Skeleton />;
  if (error)
    return (
      <div className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        {error}
      </div>
    );
  if (!submission)
    return (
      <div className="rounded border border-gray-200 bg-white p-6 text-center text-sm text-gray-600">
        Submission not found, or you don&apos;t have access to it.
      </div>
    );

  return (
    <section className="flex h-[calc(100vh-180px)] flex-col">
      {/* Top bar */}
      <header className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <button
            type="button"
            onClick={() => router.back()}
            className="mb-1 text-xs text-gray-500 hover:text-gray-700"
          >
            ← Back
          </button>
          <h1 className="truncate text-xl font-semibold text-gray-900">
            Submission #{submission.submissionNumber}
          </h1>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-gray-500">
            <span title={formatFull(submission.submittedAt)}>
              Submitted {formatRelative(submission.submittedAt)}
            </span>
            <span>·</span>
            <span>{files.length} file{files.length === 1 ? '' : 's'}</span>
            <OutcomeBadge outcome={submission.reviewOutcome} />
          </div>
        </div>

        {!isIntern && pending && (
          <div className="flex items-center gap-2">
            {canReturn && (
              <button
                type="button"
                onClick={() => setReturnOpen(true)}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-2 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
              >
                <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
                Return for revisions
              </button>
            )}
            {canApprove && (
              <button
                type="button"
                onClick={() => setConfirmApprove(true)}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
              >
                <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
                Approve technically
              </button>
            )}
          </div>
        )}
      </header>

      {!pending && (
        <ReviewedBanner submission={submission} />
      )}

      {/* Three-pane layout */}
      <div className="flex flex-1 gap-3 overflow-hidden">
        {/* File tree */}
        <aside className="flex w-64 shrink-0 flex-col rounded-lg border border-gray-200 bg-white">
          <div className="border-b border-gray-200 px-3 py-2">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">
              Files ({files.length})
            </span>
          </div>
          <ul className="flex-1 overflow-y-auto py-1 text-sm">
            {files.length === 0 ? (
              <li className="px-3 py-3 text-xs italic text-gray-400">
                Empty submission.
              </li>
            ) : (
              files.map((f) => (
                <li key={f.id}>
                  <button
                    type="button"
                    onClick={() =>
                      submissionId && void openFile(submissionId, f.path)
                    }
                    className={
                      'block w-full truncate px-3 py-1.5 text-left ' +
                      (f.path === openPath
                        ? 'bg-accent/10 font-medium text-accent-dark'
                        : 'text-gray-700 hover:bg-gray-50')
                    }
                    title={f.path}
                  >
                    {f.path}
                  </button>
                </li>
              ))
            )}
          </ul>
        </aside>

        {/* Viewer */}
        <div className="flex flex-1 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white">
          {openPath ? (
            <>
              <div className="flex items-center justify-between border-b border-gray-200 px-3 py-1.5 text-xs">
                <div className="flex items-center gap-2">
                  <span className="font-medium text-gray-800">{openPath}</span>
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-gray-600">
                    Read only
                  </span>
                </div>
              </div>
              <div className="flex-1">
                {loadingFile ? (
                  <div className="flex h-full items-center justify-center text-sm text-gray-400">
                    Loading…
                  </div>
                ) : (
                  <MonacoEditor
                    height="100%"
                    language={languageForPath(openPath)}
                    value={openContent}
                    options={{
                      readOnly: true,
                      domReadOnly: true,
                      minimap: { enabled: false },
                      fontSize: 13,
                      scrollBeyondLastLine: false,
                      automaticLayout: true,
                    }}
                    theme="vs-light"
                  />
                )}
              </div>
            </>
          ) : (
            <div className="flex flex-1 items-center justify-center text-sm text-gray-400">
              Select a file to view it.
            </div>
          )}
        </div>
      </div>

      {/* Approve-confirmation modal */}
      {confirmApprove && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <h2 className="text-lg font-semibold text-gray-900">
              Approve technically?
            </h2>
            <p className="mt-2 text-sm text-gray-600">
              This marks submission #{submission.submissionNumber} as technically
              approved and moves the project forward to the Reporting Manager
              for viva.
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmApprove(false)}
                disabled={busy !== null}
                className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void approve()}
                disabled={busy !== null}
                className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
              >
                <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
                {busy === 'approve' ? 'Approving…' : 'Approve'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Return-reason modal */}
      {returnOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
            <h2 className="text-lg font-semibold text-gray-900">
              Return for revisions
            </h2>
            <p className="mt-1 text-xs text-gray-500">
              The intern sees this reason on their workspace and reopens it to
              push another submission.
            </p>
            <textarea
              rows={5}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={REASON_MAX}
              placeholder="What needs to change before this can be approved?"
              className="mt-3 w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
            <p
              className={
                'mt-1 text-[11px] ' +
                (reason.trim().length < REASON_MIN
                  ? 'text-gray-500'
                  : 'text-gray-600')
              }
            >
              {reason.trim().length} / {REASON_MAX} (min {REASON_MIN})
            </p>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setReturnOpen(false);
                  setReason('');
                }}
                disabled={busy !== null}
                className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void returnForRevisions()}
                disabled={
                  busy !== null
                  || reason.trim().length < REASON_MIN
                  || reason.trim().length > REASON_MAX
                }
                className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-1.5 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
              >
                <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
                {busy === 'return' ? 'Returning…' : 'Return'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function OutcomeBadge({ outcome }: { outcome: ReviewOutcome }) {
  const palette: Record<ReviewOutcome, string> = {
    PENDING: 'bg-amber-100 text-amber-800',
    APPROVED: 'bg-green-100 text-green-800',
    RETURNED: 'bg-amber-100 text-amber-800',
  };
  const label: Record<ReviewOutcome, string> = {
    PENDING: 'Awaiting review',
    APPROVED: 'Approved',
    RETURNED: 'Returned',
  };
  return (
    <span
      className={
        'inline-block rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
        palette[outcome]
      }
    >
      {label[outcome]}
    </span>
  );
}

function ReviewedBanner({ submission }: { submission: WorkspaceSubmission }) {
  if (submission.reviewOutcome === 'APPROVED') {
    return (
      <div className="mb-3 flex items-start gap-2 rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-900">
        <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" strokeWidth={2} />
        <div>
          <p className="font-medium">Technically approved.</p>
          {submission.reviewedAt && (
            <p className="text-xs text-green-800">
              {formatRelative(submission.reviewedAt)}
            </p>
          )}
        </div>
      </div>
    );
  }
  if (submission.reviewOutcome === 'RETURNED') {
    return (
      <div className="mb-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
        <RotateCcw className="mt-0.5 h-3.5 w-3.5 shrink-0" strokeWidth={2} />
        <div>
          <p className="font-medium">
            Returned for revisions
            {submission.reviewedAt
              ? ` ${formatRelative(submission.reviewedAt)}`
              : ''}.
          </p>
          {submission.reviewReason && (
            <p className="mt-1 whitespace-pre-wrap text-amber-800">
              {submission.reviewReason}
            </p>
          )}
        </div>
      </div>
    );
  }
  return (
    <div className="mb-3 flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
      <Clock className="mt-0.5 h-3.5 w-3.5 shrink-0" strokeWidth={2} />
      <p>Awaiting review.</p>
    </div>
  );
}

function encodeFilePath(path: string): string {
  return path.split('/').map(encodeURIComponent).join('/');
}

function languageForPath(path: string): string {
  const ext = path.toLowerCase().split('.').pop() ?? '';
  switch (ext) {
    case 'js':
    case 'jsx':
    case 'mjs':
    case 'cjs':
      return 'javascript';
    case 'ts':
    case 'tsx':
      return 'typescript';
    case 'py':
      return 'python';
    case 'java':
      return 'java';
    case 'go':
      return 'go';
    case 'rs':
      return 'rust';
    case 'html':
    case 'htm':
      return 'html';
    case 'css':
      return 'css';
    case 'json':
      return 'json';
    case 'md':
    case 'markdown':
      return 'markdown';
    case 'yml':
    case 'yaml':
      return 'yaml';
    case 'sh':
    case 'bash':
      return 'shell';
    case 'sql':
      return 'sql';
    default:
      return 'plaintext';
  }
}

function Skeleton() {
  return (
    <div className="space-y-3">
      <div className="h-8 w-1/3 animate-pulse rounded bg-gray-100" />
      <div className="h-[60vh] animate-pulse rounded-lg bg-gray-100" />
    </div>
  );
}
