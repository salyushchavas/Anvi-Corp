'use client';

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Paperclip, Send, Video } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import { useInternDashboard } from '@/components/intern/InternDashboardContext';

// ── Types (mirrors DoubtDtos.DoubtResponse) ──────────────────────────────

type DoubtStatus =
  | 'PENDING'
  | 'REPLIED'
  | 'SESSION_SCHEDULED'
  | 'RESOLVED'
  | 'CANCELLED';

interface DoubtResponse {
  id: string;
  internUserId: string;
  internName: string | null;
  trainerUserId: string;
  trainerName: string | null;
  projectId: string | null;
  projectTitle: string | null;
  projectAssignmentId: string | null;
  text: string;
  attachmentDocumentId: string | null;
  attachmentFileName: string | null;
  status: DoubtStatus;
  trainerReply: string | null;
  repliedAt: string | null;
  repliedByName: string | null;
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;        // always null on intern responses
  zoomPassword: string | null;        // always null on intern responses
  sessionScheduledFor: string | null;
  sessionDurationMinutes: number | null;
  sessionTimezone: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  createdAt: string;
  updatedAt: string;
}

interface AssignmentRef {
  id: string;
  project: { id: string; name: string | null } | null;
}

// ── Page ─────────────────────────────────────────────────────────────────

export default function InternDoubtsPage() {
  return (
    <Suspense fallback={null}>
      <InternDoubtsInner />
    </Suspense>
  );
}

function InternDoubtsInner() {
  const search = useSearchParams();
  const prefillProjectId = search.get('projectId') ?? '';
  const prefillAssignmentId = search.get('assignmentId') ?? '';

  const { data: dashboard } = useInternDashboard();
  const visible = dashboard?.modules?.doubts?.visible ?? false;

  const [doubts, setDoubts] = useState<DoubtResponse[]>([]);
  const [assignments, setAssignments] = useState<AssignmentRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [d, a] = await Promise.all([
        api.get<DoubtResponse[]>('/api/v1/intern/doubts'),
        api.get<AssignmentRef[]>('/api/v1/project-assignments/mine'),
      ]);
      setDoubts(d.data ?? []);
      setAssignments(a.data ?? []);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load your doubts.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (!visible) {
    return (
      <InternPageShell title="Doubts">
        <p className="rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
          Doubts open up once you become an active intern. Until then, talk to
          your ERM or trainer directly.
        </p>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell title="Doubts" subtitle="Stuck on something? Ask your trainer.">
      <div className="space-y-6">
        <RaiseDoubtCard
          assignments={assignments}
          prefillProjectId={prefillProjectId}
          prefillAssignmentId={prefillAssignmentId}
          onCreated={load}
        />

        {err && (
          <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <section className="space-y-3">
          <h2 className="text-sm font-semibold text-slate-900">My doubts</h2>
          {loading && doubts.length === 0 ? (
            <div className="h-24 animate-pulse rounded-lg bg-slate-100" aria-hidden />
          ) : doubts.length === 0 ? (
            <p className="rounded-md border border-dashed border-slate-200 bg-white p-6 text-center text-sm text-slate-600">
              You haven&rsquo;t raised any doubts yet.
            </p>
          ) : (
            <ul className="space-y-3">
              {doubts.map((d) => (
                <DoubtRow key={d.id} d={d} />
              ))}
            </ul>
          )}
        </section>
      </div>
    </InternPageShell>
  );
}

// ── Raise form ───────────────────────────────────────────────────────────

function RaiseDoubtCard({
  assignments, prefillProjectId, prefillAssignmentId, onCreated,
}: {
  assignments: AssignmentRef[];
  prefillProjectId: string;
  prefillAssignmentId: string;
  onCreated: () => void | Promise<void>;
}) {
  const [text, setText] = useState('');
  const [projectId, setProjectId] = useState<string>(prefillProjectId);
  const [assignmentId, setAssignmentId] = useState<string>(prefillAssignmentId);
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);

  // If only one assignment exists and nothing was prefilled, auto-select it
  // so the intern doesn't have to pick from a list of one.
  useEffect(() => {
    if (!projectId && !assignmentId && assignments.length === 1) {
      const a = assignments[0];
      setProjectId(a.project?.id ?? '');
      setAssignmentId(a.id);
    }
  }, [assignments, projectId, assignmentId]);

  const handleProjectChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const aId = e.target.value;
    setAssignmentId(aId);
    const a = assignments.find((x) => x.id === aId);
    setProjectId(a?.project?.id ?? '');
  };

  const submit = async () => {
    if (!text.trim()) {
      setErr('Please describe what you are stuck on.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    setOk(null);
    try {
      // Upload attachment first (best-effort — text-only doubts allowed).
      let attachmentDocumentId: string | undefined;
      if (file) {
        const fd = new FormData();
        fd.append('file', file);
        fd.append('category', 'DOUBT_ATTACHMENT');
        const up = await api.post<{ id: string }>('/api/v1/documents', fd);
        attachmentDocumentId = up.data.id;
      }
      await api.post('/api/v1/intern/doubts', {
        projectId: projectId || null,
        projectAssignmentId: assignmentId || null,
        text: text.trim(),
        attachmentDocumentId: attachmentDocumentId ?? null,
      });
      setText('');
      setFile(null);
      setOk('Doubt sent — your trainer will respond soon.');
      await onCreated();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to send doubt.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="text-sm font-semibold text-slate-900">Raise a new doubt</h2>
      <p className="mt-0.5 text-xs text-slate-500">
        Your trainer will reply or schedule a quick live session.
      </p>

      <div className="mt-3 space-y-3">
        <div>
          <label className="mb-1 block text-xs font-medium text-slate-700">
            Project
          </label>
          <select
            value={assignmentId}
            onChange={handleProjectChange}
            className="w-full rounded-md border border-slate-300 px-2.5 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          >
            <option value="">No specific project</option>
            {assignments.map((a) => (
              <option key={a.id} value={a.id}>
                {a.project?.name ?? '(untitled project)'}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="mb-1 block text-xs font-medium text-slate-700">
            What are you stuck on?
          </label>
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={4}
            placeholder="Describe the blocker, what you've tried, and what you expected…"
            className="w-full resize-y rounded-md border border-slate-300 px-2.5 py-1.5 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>

        <div className="flex items-center justify-between gap-2">
          <label className="inline-flex cursor-pointer items-center gap-1 text-xs text-slate-600 hover:text-slate-800">
            <Paperclip className="h-3.5 w-3.5" />
            <span>{file ? file.name : 'Attach screenshot / file (optional)'}</span>
            <input
              type="file"
              className="hidden"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </label>
          <button
            type="button"
            onClick={submit}
            disabled={submitting || !text.trim()}
            className="inline-flex items-center gap-1 rounded-md bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
          >
            <Send className="h-3.5 w-3.5" />
            {submitting ? 'Sending…' : 'Send doubt'}
          </button>
        </div>

        {err && (
          <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {err}
          </p>
        )}
        {ok && (
          <p className="rounded-md border border-green-200 bg-green-50 p-2 text-xs text-green-800">
            {ok}
          </p>
        )}
      </div>
    </section>
  );
}

// ── Row ──────────────────────────────────────────────────────────────────

function DoubtRow({ d }: { d: DoubtResponse }) {
  const when = useMemo(() => relTime(d.createdAt), [d.createdAt]);
  return (
    <li className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-slate-900">
            {d.projectTitle ?? 'General'}
          </h3>
          <p className="text-[11px] text-slate-500">Raised {when}</p>
        </div>
        <StatusPill status={d.status} />
      </header>

      <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">{d.text}</p>

      {d.attachmentDocumentId && (
        <a
          href={`/api/v1/documents/${d.attachmentDocumentId}/content`}
          className="mt-2 inline-flex items-center gap-1 text-xs text-brand-700 hover:underline"
        >
          <Paperclip className="h-3 w-3" />
          {d.attachmentFileName ?? 'Attachment'}
        </a>
      )}

      {d.trainerReply && (
        <div className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-3">
          <p className="text-[11px] font-semibold uppercase text-slate-500">
            Reply from your Trainer
            {d.repliedByName ? ` (${d.repliedByName})` : ''}
          </p>
          <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
            {d.trainerReply}
          </p>
        </div>
      )}

      {d.zoomJoinUrl && d.sessionScheduledFor && (
        <div className="mt-3 rounded-md border border-brand-200 bg-brand-50 p-3">
          <p className="text-[11px] font-semibold uppercase text-brand-700">
            Live session scheduled
          </p>
          <p className="mt-1 text-sm text-slate-800">
            {formatSession(d.sessionScheduledFor, d.sessionTimezone, d.sessionDurationMinutes)}
          </p>
          <a
            href={d.zoomJoinUrl}
            target="_blank"
            rel="noreferrer"
            className="mt-2 inline-flex items-center gap-1 rounded-md bg-brand-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-700"
          >
            <Video className="h-3 w-3" /> Join session
          </a>
        </div>
      )}

      {d.status === 'RESOLVED' && d.resolvedAt && (
        <p className="mt-3 text-[11px] text-slate-500">
          Marked resolved {relTime(d.resolvedAt)}
          {d.resolvedByName ? ` by ${d.resolvedByName}` : ''}.
        </p>
      )}
    </li>
  );
}

function StatusPill({ status }: { status: DoubtStatus }) {
  const map: Record<DoubtStatus, { label: string; cls: string }> = {
    PENDING:            { label: 'Pending',         cls: 'bg-slate-100 text-slate-700' },
    REPLIED:            { label: 'Replied',         cls: 'bg-blue-100 text-blue-800' },
    SESSION_SCHEDULED:  { label: 'Session set',     cls: 'bg-brand-100 text-brand-800' },
    RESOLVED:           { label: 'Resolved',        cls: 'bg-green-100 text-green-800' },
    CANCELLED:          { label: 'Cancelled',       cls: 'bg-slate-100 text-slate-500' },
  };
  const m = map[status];
  return (
    <span className={'rounded-full px-2 py-0.5 text-[11px] font-semibold ' + m.cls}>
      {m.label}
    </span>
  );
}

// ── Helpers ──────────────────────────────────────────────────────────────

function relTime(iso: string): string {
  const t = new Date(iso).getTime();
  const ms = Date.now() - t;
  const m = Math.round(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return m + 'm ago';
  const h = Math.round(m / 60);
  if (h < 24) return h + 'h ago';
  const d = Math.round(h / 24);
  return d + 'd ago';
}

function formatSession(iso: string, tz: string | null, mins: number | null): string {
  try {
    const d = new Date(iso);
    const zone = tz && tz.trim() ? tz : 'UTC';
    const fmt = new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: zone,
    });
    return `${fmt.format(d)} (${zone})${mins ? ` · ${mins}min` : ''}`;
  } catch {
    return iso;
  }
}
