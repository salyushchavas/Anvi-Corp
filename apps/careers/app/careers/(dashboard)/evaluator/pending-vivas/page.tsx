'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  AlertTriangle,
  Calendar,
  CheckCircle2,
  Clock,
  MessageSquare,
  RefreshCw,
  RotateCcw,
  Video,
  X,
} from 'lucide-react';
import api from '@/lib/api';
import WebexHostStartCard from '@/components/meeting/WebexHostStartCard';

type ActiveSession = {
  sessionId: string;
  status: 'SCHEDULED' | 'CONDUCTED' | string;
  scheduledAt: string | null;
  meetingLink: string | null;
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
};

type PendingVivaRow = {
  projectId: string;
  projectTitle: string | null;
  techStack: string | null;
  monthYear: string | null;
  projectNumber: number | null;
  internLifecycleId: string | null;
  internUserId: string | null;
  internName: string | null;
  employeeId: string | null;
  submittedAt: string | null;
  hoursWaiting: number;
  trainerFeedback: string | null;
  activeSession: ActiveSession | null;
};

type PendingVivasResponse = { items: PendingVivaRow[]; total: number };

export default function EvaluatorPendingVivasPage() {
  const [data, setData] = useState<PendingVivasResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openProjectId, setOpenProjectId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<PendingVivasResponse>(
        '/api/v1/evaluator/pending-vivas',
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const rows = data?.items ?? [];
  const openRow = openProjectId
    ? rows.find((r) => r.projectId === openProjectId) ?? null
    : null;

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Pending Q&A</h1>
          <p className="text-xs text-slate-500">
            Projects the Trainer has approved — schedule the Q&amp;A session,
            record the conversation, and sign off (marks + remarks) to mark the
            project Completed.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            No projects awaiting Q&amp;A. The trainer hasn&apos;t approved any
            submissions yet, or all sign-offs are done.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Project</th>
                <th className="px-3 py-2">Submitted</th>
                <th className="px-3 py-2">Waiting</th>
                <th className="px-3 py-2">Q&amp;A state</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <Row key={r.projectId} r={r} onOpen={() => setOpenProjectId(r.projectId)} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {openRow && (
        <SessionModal
          row={openRow}
          onClose={() => setOpenProjectId(null)}
          onChanged={() => { void load(); }}
        />
      )}
    </div>
  );
}

function Row({ r, onOpen }: { r: PendingVivaRow; onOpen: () => void }) {
  const urgent = r.hoursWaiting > 72;
  const stateLabel = r.activeSession
    ? r.activeSession.status === 'CONDUCTED'
      ? 'Conducted — ready to sign off'
      : 'Scheduled'
    : 'Not scheduled yet';
  const stateTone = r.activeSession
    ? r.activeSession.status === 'CONDUCTED'
      ? 'bg-emerald-100 text-emerald-800'
      : 'bg-amber-100 text-amber-800'
    : 'bg-slate-100 text-slate-700';
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{r.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{r.employeeId ?? '—'}</p>
      </td>
      <td className="px-3 py-2">
        <p className="text-sm text-slate-900">{r.projectTitle ?? '—'}</p>
        <p className="text-[10px] text-slate-500">
          {r.techStack ?? '—'}
          {r.projectNumber && r.monthYear
            ? ` · P${r.projectNumber} ${r.monthYear}` : ''}
        </p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.submittedAt ? new Date(r.submittedAt).toLocaleString() : '—'}
      </td>
      <td className="px-3 py-2 text-xs">
        <span className={
          'inline-flex items-center gap-1 rounded px-2 py-0.5 font-semibold '
          + (urgent ? 'bg-red-100 text-red-800' : 'text-slate-700')
        }>
          {urgent && <AlertTriangle className="h-3 w-3" />}
          {Math.round(r.hoursWaiting)}h
        </span>
      </td>
      <td className="px-3 py-2 text-xs">
        <span className={
          'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold '
          + stateTone
        }>
          <Clock className="h-3 w-3" />
          {stateLabel}
        </span>
        {r.activeSession?.scheduledAt && (
          <p className="mt-0.5 text-[10px] text-slate-500">
            for {new Date(r.activeSession.scheduledAt).toLocaleString()}
          </p>
        )}
      </td>
      <td className="px-3 py-2 text-right">
        <button
          type="button"
          onClick={onOpen}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          {r.activeSession ? 'Open session' : 'Schedule Q&A'}
        </button>
      </td>
    </tr>
  );
}

function SessionModal({ row, onClose, onChanged }: {
  row: PendingVivaRow;
  onClose: () => void;
  onChanged: () => void;
}) {
  const existing = row.activeSession;
  const [view, setView] = useState<'schedule' | 'conduct' | 'sign-off' | 'return'>(
    existing ? (existing.status === 'CONDUCTED' ? 'sign-off' : 'conduct') : 'schedule',
  );
  const [sessionId, setSessionId] = useState<string | null>(existing?.sessionId ?? null);
  // Schedule form
  const defaultSchedule = existing?.scheduledAt
    ? new Date(existing.scheduledAt).toISOString().slice(0, 16)
    : new Date(Date.now() + 24 * 3600_000).toISOString().slice(0, 16);
  const [scheduledAt, setScheduledAt] = useState(defaultSchedule);
  const [meetingLink, setMeetingLink] = useState(existing?.meetingLink ?? '');
  // Conducted form
  const [questionsAsked, setQuestionsAsked] = useState('');
  const [internResponses, setInternResponses] = useState('');
  // Sign-off form
  const [marks, setMarks] = useState<number | ''>('');
  const [remarks, setRemarks] = useState('');
  // Return form
  const [returnReason, setReturnReason] = useState('');

  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function schedule() {
    setErr(null);
    if (!scheduledAt) { setErr('Pick a date + time.'); return; }
    setBusy(true);
    try {
      const res = await api.post<{ id: string }>('/api/v1/qa-sessions', {
        projectId: row.projectId,
        scheduledAt: new Date(scheduledAt).toISOString(),
        meetingLink: meetingLink.trim() || null,
      });
      setSessionId(res.data.id);
      setView('conduct');
      onChanged();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to schedule');
    } finally { setBusy(false); }
  }

  async function recordConducted() {
    setErr(null);
    if (!sessionId) { setErr('No session.'); return; }
    if (!questionsAsked.trim() || !internResponses.trim()) {
      setErr('Capture both questions and intern responses before saving.');
      return;
    }
    setBusy(true);
    try {
      await api.patch(`/api/v1/qa-sessions/${sessionId}/conducted`, {
        questionsAsked: questionsAsked.trim(),
        internResponses: internResponses.trim(),
      });
      setView('sign-off');
      onChanged();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally { setBusy(false); }
  }

  async function signOff() {
    setErr(null);
    if (!sessionId) { setErr('No session.'); return; }
    if (marks === '' || marks < 0 || marks > 10) {
      setErr('Marks must be 0-10.');
      return;
    }
    if (!remarks.trim()) {
      setErr('Remarks are required for final sign-off.');
      return;
    }
    setBusy(true);
    try {
      await api.post(`/api/v1/qa-sessions/${sessionId}/sign-off`, {
        marks,
        remarks: remarks.trim(),
      });
      onChanged();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to sign off');
    } finally { setBusy(false); }
  }

  async function returnForRevisions() {
    setErr(null);
    if (!sessionId) { setErr('No session.'); return; }
    if (returnReason.trim().length < 10) {
      setErr('Return reason must be at least 10 characters.');
      return;
    }
    setBusy(true);
    try {
      await api.post(`/api/v1/qa-sessions/${sessionId}/return`, {
        reason: returnReason.trim(),
      });
      onChanged();
      onClose();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to return');
    } finally { setBusy(false); }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[92vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Q&amp;A · {row.projectTitle}
            </h3>
            <p className="text-xs text-slate-500">
              {row.internName} {row.employeeId ? `· ${row.employeeId}` : ''}
              {row.submittedAt && ` · submitted ${new Date(row.submittedAt).toLocaleString()}`}
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex gap-1 border-b border-slate-200 px-5">
          <Tab active={view === 'schedule'} onClick={() => setView('schedule')}
            label="Schedule" icon={Calendar} disabled={!!existing && view === 'sign-off'} />
          <Tab active={view === 'conduct'} onClick={() => setView('conduct')}
            label="Conduct" icon={MessageSquare} disabled={!sessionId} />
          <Tab active={view === 'sign-off'} onClick={() => setView('sign-off')}
            label="Sign off" icon={CheckCircle2} disabled={!sessionId} />
          <Tab active={view === 'return'} onClick={() => setView('return')}
            label="Return" icon={RotateCcw} disabled={!sessionId} />
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto p-5">
          {row.trainerFeedback && (
            <details className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs">
              <summary className="cursor-pointer font-semibold text-slate-700">
                Trainer feedback on this submission
              </summary>
              <p className="mt-2 whitespace-pre-wrap text-slate-700">{row.trainerFeedback}</p>
            </details>
          )}

          {view === 'schedule' && (
            <div className="space-y-3">
              <Field label="Scheduled for*">
                <input type="datetime-local" value={scheduledAt}
                  onChange={(e) => setScheduledAt(e.target.value)}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
              <Field label="Meeting link (optional — paste Zoom / Meet / Teams URL)">
                <input type="url" value={meetingLink}
                  onChange={(e) => setMeetingLink(e.target.value)}
                  placeholder="https://zoom.us/j/..."
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
              {existing && (
                <p className="rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800">
                  A session is already scheduled. Re-submitting creates a new
                  session row; the previous one stays for history.
                </p>
              )}
            </div>
          )}

          {view === 'conduct' && (
            <div className="space-y-3">
              {/* Host-side start card — fetches a fresh Zoom start_url on
                  every render (~2h zak), so the evaluator's one-click
                  "Start Meeting (Host)" is always live. Falls back to the
                  pasted meetingLink when Zoom isn't wired. */}
              {existing?.zoomMeetingId ? (
                <WebexHostStartCard
                  providerMeetingId={existing.zoomMeetingId}
                  startUrl={existing.zoomStartUrl}
                />
              ) : existing?.meetingLink ? (
                <a href={existing.meetingLink} target="_blank" rel="noreferrer"
                  className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline">
                  <Video className="h-3.5 w-3.5" /> Join session
                </a>
              ) : null}
              <Field label="Questions asked* (one per line)">
                <textarea value={questionsAsked} onChange={(e) => setQuestionsAsked(e.target.value)}
                  rows={5}
                  placeholder="What does this API do?&#10;Walk me through the auth flow."
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
              <Field label="Intern responses* (one per line, matching questions)">
                <textarea value={internResponses} onChange={(e) => setInternResponses(e.target.value)}
                  rows={5}
                  placeholder="Returns the user roster filtered by lifecycle…&#10;JWT in the Authorization header, refreshed via…"
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
            </div>
          )}

          {view === 'sign-off' && (
            <div className="space-y-3">
              <p className="rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-800">
                Sign-off completes the project. The status flips to COMPLETED
                and the intern + trainer get notified.
              </p>
              <Field label="Marks (0-10)*">
                <input type="number" min={0} max={10} value={marks}
                  onChange={(e) => setMarks(e.target.value === '' ? '' : Number(e.target.value))}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
              <Field label="Remarks*">
                <textarea value={remarks} onChange={(e) => setRemarks(e.target.value)} rows={4}
                  placeholder="Strong fundamentals, articulate on the auth flow. Recommend moving to next project."
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
            </div>
          )}

          {view === 'return' && (
            <div className="space-y-3">
              <p className="rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800">
                Returns the project to the intern (status → IN_PROGRESS). They&apos;ll
                see the reason verbatim and can re-submit.
              </p>
              <Field label="Return reason* (min 10 chars)">
                <textarea value={returnReason} onChange={(e) => setReturnReason(e.target.value)} rows={4}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
            </div>
          )}

          {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
          {view === 'schedule' && (
            <button type="button" onClick={schedule} disabled={busy}
              className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
              {busy ? 'Scheduling…' : 'Schedule session'}
            </button>
          )}
          {view === 'conduct' && (
            <button type="button" onClick={recordConducted} disabled={busy}
              className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
              {busy ? 'Saving…' : 'Save Q&A notes'}
            </button>
          )}
          {view === 'sign-off' && (
            <button type="button" onClick={signOff} disabled={busy}
              className="rounded-md bg-emerald-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-emerald-800 disabled:bg-slate-300">
              {busy ? 'Signing off…' : 'Sign off — mark Completed'}
            </button>
          )}
          {view === 'return' && (
            <button type="button" onClick={returnForRevisions} disabled={busy}
              className="rounded-md bg-amber-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-amber-800 disabled:bg-slate-300">
              {busy ? 'Returning…' : 'Return for revisions'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function Tab({ active, onClick, label, icon: Icon, disabled }: {
  active: boolean; onClick: () => void; label: string;
  icon: React.ComponentType<{ className?: string }>;
  disabled?: boolean;
}) {
  return (
    <button type="button" onClick={onClick} disabled={disabled}
      className={
        '-mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-xs ' +
        (active
          ? 'border-brand-700 font-semibold text-brand-700'
          : disabled
            ? 'border-transparent text-slate-300'
            : 'border-transparent text-slate-600 hover:text-slate-900')
      }>
      <Icon className="h-3.5 w-3.5" />
      {label}
    </button>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
