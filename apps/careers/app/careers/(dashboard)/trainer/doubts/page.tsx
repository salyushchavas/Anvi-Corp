'use client';

import { useCallback, useEffect, useState } from 'react';
import { CheckCircle2, Paperclip, Send, Video, X } from 'lucide-react';
import api from '@/lib/api';

// ── Types (mirrors DoubtDtos.DoubtResponse, trainer view) ────────────────

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
  zoomStartUrl: string | null;
  zoomPassword: string | null;
  sessionScheduledFor: string | null;
  sessionDurationMinutes: number | null;
  sessionTimezone: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  createdAt: string;
  updatedAt: string;
}

export default function TrainerDoubtsPage() {
  const [openOnly, setOpenOnly] = useState(true);
  const [doubts, setDoubts] = useState<DoubtResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<DoubtResponse[]>(
        '/api/v1/trainer/doubts?open=' + openOnly);
      setDoubts(res.data ?? []);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load doubts.');
    } finally {
      setLoading(false);
    }
  }, [openOnly]);

  useEffect(() => { void load(); }, [load]);

  const updateRow = (updated: DoubtResponse) => {
    setDoubts((prev) => prev.map((r) => r.id === updated.id ? updated : r));
  };

  return (
    <div className="mx-auto max-w-5xl space-y-5 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Doubt Requests</h1>
          <p className="text-xs text-slate-500">
            Interns who&rsquo;ve hit a blocker — reply, schedule a session, or mark resolved.
          </p>
        </div>
        <label className="inline-flex items-center gap-2 text-xs text-slate-700">
          <input
            type="checkbox"
            checked={!openOnly}
            onChange={(e) => setOpenOnly(!e.target.checked)}
            className="h-3.5 w-3.5"
          />
          Show resolved
        </label>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {loading && doubts.length === 0 ? (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : doubts.length === 0 ? (
        <p className="rounded-md border border-dashed border-slate-200 bg-white p-8 text-center text-sm text-slate-600">
          {openOnly ? 'No open doubts. 🎉' : 'No doubts yet.'}
        </p>
      ) : (
        <ul className="space-y-3">
          {doubts.map((d) => (
            <DoubtRow key={d.id} d={d} onChange={updateRow} />
          ))}
        </ul>
      )}
    </div>
  );
}

// ── Row ──────────────────────────────────────────────────────────────────

function DoubtRow({
  d, onChange,
}: { d: DoubtResponse; onChange: (next: DoubtResponse) => void }) {
  const [mode, setMode] = useState<'idle' | 'reply' | 'schedule'>('idle');
  const [reply, setReply] = useState('');
  const [scheduledFor, setScheduledFor] = useState('');     // ISO local "YYYY-MM-DDTHH:mm"
  const [duration, setDuration] = useState<number>(30);
  const [topic, setTopic] = useState('Doubt-clearing session');
  const [tz, setTz] = useState<string>(() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
    catch { return 'UTC'; }
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const doReply = async () => {
    if (!reply.trim()) { setErr('Reply is empty.'); return; }
    setBusy(true); setErr(null);
    try {
      const res = await api.post<DoubtResponse>(
        `/api/v1/trainer/doubts/${d.id}/reply`,
        { reply: reply.trim() });
      onChange(res.data);
      setMode('idle');
      setReply('');
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to send reply.');
    } finally { setBusy(false); }
  };

  const doSchedule = async () => {
    if (!scheduledFor) { setErr('Pick a date + time.'); return; }
    setBusy(true); setErr(null);
    try {
      // datetime-local input gives "YYYY-MM-DDTHH:mm" without tz. Combine
      // with the resolved IANA timezone and let the server normalise.
      const iso = new Date(scheduledFor).toISOString();
      const res = await api.post<DoubtResponse>(
        `/api/v1/trainer/doubts/${d.id}/schedule-session`,
        {
          scheduledFor: iso,
          durationMinutes: duration,
          timezone: tz,
          topic,
        });
      onChange(res.data);
      setMode('idle');
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to schedule session.');
    } finally { setBusy(false); }
  };

  const doResolve = async () => {
    setBusy(true); setErr(null);
    try {
      const res = await api.post<DoubtResponse>(
        `/api/v1/trainer/doubts/${d.id}/resolve`, {});
      onChange(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to resolve.');
    } finally { setBusy(false); }
  };

  return (
    <li className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-slate-900">
            {d.internName ?? '(unknown intern)'}
          </h3>
          <p className="text-[11px] text-slate-500">
            {d.projectTitle ?? 'General'} · {relTime(d.createdAt)}
          </p>
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
        <div className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-2 text-xs text-slate-700">
          <p className="font-semibold text-slate-500">Your reply</p>
          <p className="mt-1 whitespace-pre-wrap">{d.trainerReply}</p>
        </div>
      )}

      {d.sessionScheduledFor && (
        <div className="mt-3 rounded-md border border-brand-200 bg-brand-50 p-2 text-xs">
          <p className="font-semibold text-brand-700">Session scheduled</p>
          <p className="mt-1 text-slate-800">
            {formatSession(d.sessionScheduledFor, d.sessionTimezone, d.sessionDurationMinutes)}
          </p>
          {(d.zoomStartUrl || d.zoomJoinUrl) && (
            <a
              href={d.zoomStartUrl ?? d.zoomJoinUrl ?? '#'}
              target="_blank"
              rel="noreferrer"
              className="mt-1.5 inline-flex items-center gap-1 rounded-md border border-brand-300 bg-white px-2 py-1 text-[11px] font-semibold text-brand-700 hover:bg-brand-100"
            >
              <Video className="h-3 w-3" />
              {d.zoomStartUrl ? 'Join (host)' : 'Join'}
            </a>
          )}
        </div>
      )}

      {err && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}

      {/* Action row */}
      {d.status !== 'RESOLVED' && (
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
          {mode === 'idle' && (
            <>
              <button
                type="button"
                onClick={() => setMode('reply')}
                className="rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
              >
                Reply
              </button>
              <button
                type="button"
                onClick={() => setMode('schedule')}
                className="rounded-md border border-brand-300 bg-brand-50 px-2.5 py-1 text-xs font-medium text-brand-700 hover:bg-brand-100"
              >
                Schedule session
              </button>
              <button
                type="button"
                onClick={doResolve}
                disabled={busy}
                className="ml-auto inline-flex items-center gap-1 rounded-md border border-green-300 bg-green-50 px-2.5 py-1 text-xs font-medium text-green-700 hover:bg-green-100 disabled:opacity-50"
              >
                <CheckCircle2 className="h-3 w-3" /> Mark resolved
              </button>
            </>
          )}

          {mode === 'reply' && (
            <div className="w-full space-y-2">
              <textarea
                rows={3}
                value={reply}
                onChange={(e) => setReply(e.target.value)}
                placeholder="Write a reply…"
                className="w-full resize-y rounded-md border border-slate-300 px-2.5 py-1.5 text-sm"
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={doReply}
                  disabled={busy || !reply.trim()}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
                >
                  <Send className="h-3.5 w-3.5" />
                  {busy ? 'Sending…' : 'Send reply'}
                </button>
                <button
                  type="button"
                  onClick={() => { setMode('idle'); setReply(''); setErr(null); }}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1.5 text-xs text-slate-700 hover:bg-slate-50"
                >
                  <X className="h-3 w-3" /> Cancel
                </button>
              </div>
            </div>
          )}

          {mode === 'schedule' && (
            <div className="w-full space-y-2">
              <div className="grid gap-2 sm:grid-cols-3">
                <div>
                  <label className="block text-[11px] font-medium text-slate-700">When</label>
                  <input
                    type="datetime-local"
                    value={scheduledFor}
                    onChange={(e) => setScheduledFor(e.target.value)}
                    className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
                  />
                </div>
                <div>
                  <label className="block text-[11px] font-medium text-slate-700">Duration (min)</label>
                  <input
                    type="number"
                    min={15}
                    max={180}
                    step={5}
                    value={duration}
                    onChange={(e) => setDuration(Math.max(15, Math.min(180, Number(e.target.value) || 30)))}
                    className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
                  />
                </div>
                <div>
                  <label className="block text-[11px] font-medium text-slate-700">Timezone</label>
                  <input
                    type="text"
                    value={tz}
                    onChange={(e) => setTz(e.target.value)}
                    className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
                  />
                </div>
              </div>
              <div>
                <label className="block text-[11px] font-medium text-slate-700">Topic</label>
                <input
                  type="text"
                  value={topic}
                  onChange={(e) => setTopic(e.target.value)}
                  className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
                />
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={doSchedule}
                  disabled={busy || !scheduledFor}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
                >
                  <Video className="h-3.5 w-3.5" />
                  {busy ? 'Scheduling…' : 'Schedule Zoom session'}
                </button>
                <button
                  type="button"
                  onClick={() => { setMode('idle'); setErr(null); }}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1.5 text-xs text-slate-700 hover:bg-slate-50"
                >
                  <X className="h-3 w-3" /> Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </li>
  );
}

function StatusPill({ status }: { status: DoubtStatus }) {
  const map: Record<DoubtStatus, { label: string; cls: string }> = {
    PENDING:            { label: 'Pending',         cls: 'bg-amber-100 text-amber-800' },
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
