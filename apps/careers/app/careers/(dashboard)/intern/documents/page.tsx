'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { CheckCircle2, Download, Lock, Send, Upload } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import Modal from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import type {
  InternPacketView,
  InternTaskView,
  TaskStatus,
} from '@/components/erm/documents/types';

export default function InternDocumentsPage() {
  const [data, setData] = useState<InternPacketView | null>(null);
  const [empty, setEmpty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<InternPacketView>('/api/v1/intern/documents/packet');
      if (res.status === 204) {
        setEmpty(true);
        setData(null);
      } else {
        setData(res.data);
        setEmpty(false);
      }
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { status?: number; data?: { error?: string } }; message?: string };
      if (ax.response?.status === 204) {
        setEmpty(true);
        setData(null);
      } else {
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <InternPageShell
      title="Documents"
      subtitle="Download each template, fill it offline, then upload the completed file."
    >
      {err && (
        <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}
      {loading && !data && !empty ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" />
      ) : empty ? (
        <div className="rounded-lg border border-slate-200 bg-white p-8 text-center text-sm text-slate-500">
          No documents have been assigned to you yet. Your ERM agent will assign them
          once your reporting structure is in place.
        </div>
      ) : data ? (
        <PacketView packet={data} onChanged={load} />
      ) : null}
    </InternPageShell>
  );
}

function PacketView({ packet, onChanged }: {
  packet: InternPacketView;
  onChanged: () => void;
}) {
  const total = packet.tasks.length;
  const done = packet.tasks.filter((t) => t.status === 'ACCEPTED' || t.status === 'WAIVED').length;
  const pct = total === 0 ? 0 : Math.round((done * 100) / total);
  const pending = packet.pendingTasks ?? 0;

  return (
    <>
      <section className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-xs text-slate-500">Packet progress</p>
            <p className="text-sm font-semibold text-slate-900">{done} of {total} accepted</p>
          </div>
          <span className={
            'rounded-full px-3 py-1 text-xs font-semibold ' +
            (packet.status === 'COMPLETED'
              ? 'bg-green-100 text-green-800'
              : 'bg-amber-100 text-amber-800')
          }>
            {packet.status.replace('_', ' ')}
          </span>
        </div>
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-slate-100">
          <div className="h-full bg-green-500" style={{ width: `${pct}%` }} />
        </div>
        {packet.customInstructions && (
          <p className="mt-3 whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-xs text-slate-700">
            {packet.customInstructions}
          </p>
        )}
      </section>

      <SubmitHandoffCard packet={packet} pending={pending}
        onSubmitted={onChanged} />

      <p className="mb-3 inline-flex items-center gap-1.5 text-[11px] text-slate-500">
        <Lock className="h-3 w-3" aria-hidden="true" />
        Your documents are encrypted and stored securely.
      </p>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {packet.tasks.map((t) => (
          <TaskCard key={t.taskId} task={t} packet={packet} onChanged={onChanged} />
        ))}
      </div>
    </>
  );
}

/**
 * Top-of-page handoff banner. Three visual states:
 *  - tasks still PENDING → disabled Submit + "X document(s) still to upload"
 *  - all uploaded, not yet submitted → enabled Submit
 *  - already submitted (internLocked) → green "Submitted, awaiting ERM
 *    verification" — uploads disabled on every task below.
 *  - packet COMPLETED → green "ERM verified all documents".
 */
function SubmitHandoffCard({
  packet, pending, onSubmitted,
}: {
  packet: InternPacketView;
  pending: number;
  onSubmitted: () => void;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  function openConfirm() {
    if (pending > 0 || packet.internLocked) return;
    setConfirmOpen(true);
  }

  async function doSubmit() {
    setSubmitting(true);
    try {
      await api.post(`/api/v1/intern/documents/packets/${packet.packetId}/submit`);
      setConfirmOpen(false);
      toast.success('Packet submitted to ERM for verification.');
      onSubmitted();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Submit failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (packet.status === 'COMPLETED') {
    return (
      <section className="mb-4 rounded-lg border border-green-200 bg-green-50 p-4 shadow-sm">
        <p className="inline-flex items-center gap-2 text-sm font-semibold text-green-900">
          <CheckCircle2 className="h-4 w-4" />
          ERM has verified all your documents. You&apos;re onboarded.
        </p>
      </section>
    );
  }

  if (packet.internLocked) {
    return (
      <section className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-4 shadow-sm">
        <div className="flex items-start gap-3">
          <Lock className="mt-0.5 h-4 w-4 text-amber-800" />
          <div className="flex-1">
            <p className="text-sm font-semibold text-amber-900">
              Submitted to ERM — awaiting verification
            </p>
            <p className="mt-0.5 text-xs text-amber-800">
              Your uploads are locked while ERM reviews each document.
              If anything needs changes, ERM will reject that document
              and it will reopen for you to upload again.
            </p>
            {packet.internSubmittedAt && (
              <p className="mt-1 text-[11px] text-amber-800">
                Submitted {new Date(packet.internSubmittedAt).toLocaleString()}
              </p>
            )}
          </div>
        </div>
      </section>
    );
  }

  const enabled = pending === 0 && !submitting;
  return (
    <>
      <section className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-sm font-semibold text-slate-900">
              Ready to hand off?
            </p>
            <p className="mt-0.5 text-xs text-slate-600">
              Once you&apos;ve uploaded every required document, submit the
              full packet to ERM for verification.
            </p>
            {pending > 0 && (
              <p className="mt-1 text-xs font-medium text-amber-700">
                {pending} required document{pending === 1 ? '' : 's'} still need
                to be uploaded before you can submit.
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={openConfirm}
            disabled={!enabled}
            title={pending > 0
              ? `Upload the remaining ${pending} document(s) first`
              : 'Submit the entire packet to ERM for verification'}
            className={
              'inline-flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-semibold transition-colors '
              + (enabled
                ? 'bg-brand-700 text-white hover:bg-brand-800'
                : 'cursor-not-allowed bg-slate-200 text-slate-500')
            }
          >
            <Send className="h-3.5 w-3.5" />
            {submitting ? 'Submitting…' : 'Submit all documents to ERM'}
          </button>
        </div>
      </section>

      <Modal
        open={confirmOpen}
        onOpenChange={(o) => !submitting && setConfirmOpen(o)}
        title="Submit packet to ERM?"
        size="sm"
        footer={
          <>
            <button
              type="button"
              onClick={() => setConfirmOpen(false)}
              disabled={submitting}
              className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={doSubmit}
              disabled={submitting}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              <Send className="h-3.5 w-3.5" />
              {submitting ? 'Submitting…' : 'Yes, submit'}
            </button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          You won&apos;t be able to change your documents while ERM is reviewing.
          If ERM rejects any document, that document will reopen for you to
          upload again and re-submit.
        </p>
      </Modal>
    </>
  );
}

function TaskCard({ task, packet, onChanged }: {
  task: InternTaskView;
  packet: InternPacketView;
  onChanged: () => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [showHow, setShowHow] = useState(false);

  async function upload(file: File) {
    // ERM Phase 8.2 — strict client-side PDF check, mirroring the
    // server gate. Some browsers leave file.type empty for older PDFs,
    // so we also check the filename extension.
    const filename = file.name.toLowerCase();
    const isPdfMime = file.type === 'application/pdf';
    const isPdfName = filename.endsWith('.pdf');
    if (!isPdfMime && !isPdfName) {
      toast.error('Only PDF files are accepted. Scan all filled pages into a '
        + 'single PDF using your phone\'s scanner app, then upload that PDF.');
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      toast.error('File exceeds 10 MB limit.');
      return;
    }
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      await api.post(`/api/v1/intern/documents/tasks/${task.taskId}/upload`, fd);
      toast.success(`${task.templateTitle} uploaded.`);
      onChanged();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Upload failed');
    } finally {
      setUploading(false);
    }
  }

  const showReviewerComments =
    (task.status === 'REJECTED' || task.status === 'RESEND_REQUESTED')
    && task.reviewComments;
  // Phase 1.6 — also hide Upload while the packet is locked. The server
  // enforces the same rule (409) so hiding the button here is just UX.
  const canUpload = task.status !== 'ACCEPTED'
    && task.status !== 'WAIVED'
    && !packet.internLocked;
  const category = task.templatePublicUrl ? 'Fillable template' : 'Identity / supporting doc';

  return (
    <section className="flex flex-col rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-slate-900" title={task.templateTitle}>
            {task.templateTitle}
          </h3>
          <p className="mt-0.5 text-[11px] uppercase tracking-wide text-slate-500">
            {category}
          </p>
        </div>
        <TaskBadge status={task.status} />
      </header>

      {task.description && (
        <p className="mt-2 text-xs text-slate-600">{task.description}</p>
      )}

      <div className="mt-2 text-xs">
        <button
          type="button"
          onClick={() => setShowHow((v) => !v)}
          className="text-brand-700 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
          aria-expanded={showHow}
        >
          {showHow ? 'Hide instructions' : 'How to complete'}
        </button>
        {showHow && (
          <p className="mt-1 rounded-md bg-slate-50 p-2 text-slate-700">
            {task.templatePublicUrl ? (
              <span className="block">
                1. Download the PDF below.<br />
                2. Print and fill it out by hand (blue or black pen).<br />
                3. Use your phone&apos;s scanner app to combine all filled pages
                into a single PDF.<br />
                4. Upload the scanned PDF here.
              </span>
            ) : (
              <span className="block">
                1. Take a clear photo or scan of your existing document.<br />
                2. Combine multiple pages into a single PDF.<br />
                3. Upload the PDF here.
              </span>
            )}
          </p>
        )}
      </div>

      {task.taskInstructions && (
        <p className="mt-2 whitespace-pre-wrap rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
          <strong className="block">Note from your ERM agent:</strong>
          {task.taskInstructions}
        </p>
      )}
      {showReviewerComments && (
        <div className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          <p className="font-semibold">ERM feedback:</p>
          <p className="mt-1 whitespace-pre-wrap">{task.reviewComments}</p>
        </div>
      )}

      <div className="mt-auto pt-3">
        {task.submittedAt && (
          <p className="mb-2 text-[11px] text-slate-500">
            Uploaded {new Date(task.submittedAt).toLocaleDateString()}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2">
          {task.templatePublicUrl && (
            <a
              href={task.templatePublicUrl}
              target="_blank"
              rel="noreferrer"
              download
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              <Download className="h-3.5 w-3.5" /> Template
            </a>
          )}
          {canUpload && (
            <>
              <input
                ref={fileRef}
                type="file"
                accept="application/pdf,.pdf"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void upload(f);
                  e.target.value = '';
                }}
                className="hidden"
              />
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                disabled={uploading}
                className="inline-flex flex-1 items-center justify-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
              >
                <Upload className="h-3.5 w-3.5" />
                {uploading ? 'Uploading…' : task.submittedAt ? 'Replace' : 'Upload PDF'}
              </button>
            </>
          )}
        </div>
        {canUpload && (
          <p className="mt-1.5 text-[10px] text-slate-500">PDF only · max 10 MB</p>
        )}
      </div>
    </section>
  );
}

function TaskBadge({ status }: { status: TaskStatus }) {
  const styles: Record<TaskStatus, string> = {
    PENDING: 'bg-slate-100 text-slate-700',
    SUBMITTED: 'bg-slate-100 text-slate-700',
    UNDER_REVIEW: 'bg-amber-100 text-amber-800',
    ACCEPTED: 'bg-green-100 text-green-800',
    REJECTED: 'bg-red-100 text-red-800',
    RESEND_REQUESTED: 'bg-amber-100 text-amber-800',
    WAIVED: 'bg-slate-200 text-slate-700',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${styles[status]}`}>
      {status.replace('_', ' ')}
    </span>
  );
}
