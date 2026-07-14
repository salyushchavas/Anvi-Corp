'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  CheckCircle2,
  Clock,
  ExternalLink,
  RotateCcw,
  Save,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { formatFull, formatRelative } from '@/lib/format-date';
import type { QaSession, QaSessionStatus } from '@/types';

const REASON_MIN = 10;
const REASON_MAX = 2000;
const AUTO_SAVE_MS = 1500;

export default function QaSessionDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['REPORTING_MANAGER', 'SUPER_ADMIN']}>
      <DashboardLayout title="Q&A Session">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const sessionId = params?.id;

  const [session, setSession] = useState<QaSession | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [questions, setQuestions] = useState('');
  const [responses, setResponses] = useState('');
  const [marks, setMarks] = useState<string>('');
  const [remarks, setRemarks] = useState('');
  const [savedQuestions, setSavedQuestions] = useState('');
  const [savedResponses, setSavedResponses] = useState('');

  const [busy, setBusy] = useState<'save' | 'signoff' | 'return' | null>(null);
  const [returnOpen, setReturnOpen] = useState(false);
  const [returnReason, setReturnReason] = useState('');
  const [confirmSignOff, setConfirmSignOff] = useState(false);

  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const savingRef = useRef(false);

  const status = session?.status;
  const isTerminal = status === 'COMPLETED' || status === 'RETURNED';
  const canEdit = status === 'SCHEDULED' || status === 'CONDUCTED';

  const load = useCallback(async () => {
    if (!sessionId) return;
    setError(null);
    try {
      const res = await api.get<QaSession>(`/api/v1/qa-sessions/${sessionId}`);
      setSession(res.data);
      setQuestions(res.data.questionsAsked ?? '');
      setResponses(res.data.internResponses ?? '');
      setSavedQuestions(res.data.questionsAsked ?? '');
      setSavedResponses(res.data.internResponses ?? '');
      setRemarks(res.data.remarks ?? '');
      setMarks(res.data.marks != null ? String(res.data.marks) : '');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the session.");
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Auto-save the conducted notes on debounce while editing.
  const dirty = questions !== savedQuestions || responses !== savedResponses;

  const saveConducted = useCallback(async () => {
    if (!sessionId || !canEdit || savingRef.current) return;
    if (questions === savedQuestions && responses === savedResponses) return;
    savingRef.current = true;
    setBusy('save');
    try {
      const res = await api.patch<QaSession>(
        `/api/v1/qa-sessions/${sessionId}/conducted`,
        {
          questionsAsked: questions,
          internResponses: responses,
        },
      );
      setSession(res.data);
      setSavedQuestions(res.data.questionsAsked ?? '');
      setSavedResponses(res.data.internResponses ?? '');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't save the notes.");
    } finally {
      savingRef.current = false;
      setBusy(null);
    }
  }, [sessionId, canEdit, questions, responses, savedQuestions, savedResponses]);

  useEffect(() => {
    if (!dirty || !canEdit) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      void saveConducted();
    }, AUTO_SAVE_MS);
    return () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    };
  }, [questions, responses, dirty, canEdit, saveConducted]);

  const canSignOff = useMemo(() => {
    return (
      canEdit
      && savedQuestions.trim().length > 0
      && savedResponses.trim().length > 0
      && !dirty
    );
  }, [canEdit, savedQuestions, savedResponses, dirty]);

  async function signOff() {
    if (!sessionId || !canSignOff || busy !== null) return;
    const marksValue = marks.trim() === '' ? null : Number(marks);
    if (marksValue != null && (marksValue < 0 || marksValue > 10 || Number.isNaN(marksValue))) {
      toast.error('Marks must be between 0 and 10.');
      return;
    }
    setBusy('signoff');
    try {
      await api.post(`/api/v1/qa-sessions/${sessionId}/sign-off`, {
        marks: marksValue,
        remarks: remarks.trim() || null,
      });
      toast.success('Project signed off.');
      router.push('/careers/reporting-manager');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't sign off.");
      setBusy(null);
    }
  }

  async function returnForRevisions() {
    if (!sessionId || busy !== null) return;
    const trimmed = returnReason.trim();
    if (trimmed.length < REASON_MIN || trimmed.length > REASON_MAX) return;
    setBusy('return');
    try {
      await api.post(`/api/v1/qa-sessions/${sessionId}/return`, {
        reason: trimmed,
      });
      toast.success('Returned to In Progress.');
      router.push('/careers/reporting-manager');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't return the session.");
      setBusy(null);
    }
  }

  if (loading) return <Skeleton />;
  if (error)
    return (
      <div className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        {error}
      </div>
    );
  if (!session) return null;

  return (
    <section className="mx-auto max-w-3xl space-y-5">
      <header>
        <button
          type="button"
          onClick={() => router.back()}
          className="mb-1 text-xs text-gray-500 hover:text-gray-700"
        >
          ← Back
        </button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="truncate text-xl font-semibold text-gray-900">
              {session.projectTitle ?? 'Session'}
            </h1>
            <p className="mt-0.5 text-xs text-gray-500">
              {session.internName ?? '—'} · scheduled{' '}
              <span title={formatFull(session.scheduledAt)}>
                {formatRelative(session.scheduledAt)}
              </span>
            </p>
          </div>
          <StatusBadge status={session.status} />
        </div>
      </header>

      {/* Scheduled meta */}
      <div className="rounded-lg border border-gray-200 bg-white p-4">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-gray-500">
          Scheduled
        </div>
        <div className="mt-1 text-sm text-gray-800">
          {formatFull(session.scheduledAt)}
        </div>
        {session.meetingLink && (
          <a
            href={session.meetingLink}
            target="_blank"
            rel="noreferrer"
            className="mt-1 inline-flex items-center gap-1 text-xs text-accent-dark hover:underline"
          >
            <ExternalLink className="h-3 w-3" strokeWidth={2} />
            {session.meetingLink}
          </a>
        )}
      </div>

      {/* Terminal banners */}
      {session.status === 'COMPLETED' && (
        <Banner kind="success" title="Signed off — project completed.">
          {session.completedAt && (
            <p className="text-xs">{formatRelative(session.completedAt)}</p>
          )}
          {session.marks != null && (
            <p className="text-xs">Marks: {session.marks}/10</p>
          )}
          {session.remarks && (
            <p className="mt-1 whitespace-pre-wrap text-xs">{session.remarks}</p>
          )}
        </Banner>
      )}
      {session.status === 'RETURNED' && (
        <Banner kind="warn" title="Returned to In Progress.">
          {session.returnReason && (
            <p className="mt-1 whitespace-pre-wrap text-xs">
              {session.returnReason}
            </p>
          )}
        </Banner>
      )}

      {/* Conduct section */}
      <div className="space-y-3 rounded-lg border border-gray-200 bg-white p-4">
        <h2 className="text-sm font-semibold text-gray-900">
          Capture the Q&amp;A
        </h2>
        <Field label="Questions you asked (one per line)">
          <textarea
            rows={5}
            value={questions}
            onChange={(e) => setQuestions(e.target.value)}
            disabled={!canEdit}
            placeholder="Walk me through your architecture…"
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm disabled:bg-gray-50 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </Field>
        <Field label="Intern's responses">
          <textarea
            rows={6}
            value={responses}
            onChange={(e) => setResponses(e.target.value)}
            disabled={!canEdit}
            placeholder="Notes on what they said…"
            className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm disabled:bg-gray-50 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </Field>
        {canEdit && (
          <div className="flex items-center justify-end gap-2 text-xs text-gray-500">
            <span
              className={
                'h-2 w-2 rounded-full ' +
                (dirty ? 'bg-amber-500' : 'bg-green-500')
              }
            />
            {busy === 'save'
              ? 'Saving…'
              : dirty
                ? 'Unsaved changes (auto-saving)'
                : 'Saved'}
            <button
              type="button"
              onClick={() => void saveConducted()}
              disabled={!dirty || busy !== null}
              className="inline-flex items-center gap-1 rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
            >
              <Save className="h-3 w-3" strokeWidth={2} />
              Save progress
            </button>
          </div>
        )}
      </div>

      {/* Sign-off section */}
      {!isTerminal && (
        <div className="space-y-3 rounded-lg border border-green-200 bg-green-50/30 p-4">
          <h2 className="text-sm font-semibold text-gray-900">Sign off</h2>
          <p className="text-xs text-gray-600">
            Available once questions and responses are saved. Locks the project
            as COMPLETED.
          </p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Field label="Marks (0–10, optional)">
              <input
                type="number"
                min={0}
                max={10}
                step={1}
                value={marks}
                onChange={(e) => setMarks(e.target.value)}
                disabled={!canSignOff}
                className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm disabled:bg-gray-50 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>
            <div className="sm:col-span-2">
              <Field label="Remarks (optional)">
                <textarea
                  rows={2}
                  value={remarks}
                  onChange={(e) => setRemarks(e.target.value)}
                  disabled={!canSignOff}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm disabled:bg-gray-50 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </Field>
            </div>
          </div>
          <div className="flex justify-end">
            <button
              type="button"
              onClick={() => setConfirmSignOff(true)}
              disabled={!canSignOff || busy !== null}
              className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
            >
              <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
              Sign off &amp; complete project
            </button>
          </div>
        </div>
      )}

      {/* Return action */}
      {!isTerminal && (
        <div className="flex justify-end">
          <button
            type="button"
            onClick={() => setReturnOpen(true)}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-1.5 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
          >
            <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
            Return to In Progress
          </button>
        </div>
      )}

      {/* Sign-off confirmation modal */}
      {confirmSignOff && (
        <Modal title="Sign off & complete project?">
          <p className="text-sm text-gray-600">
            This locks the project as COMPLETED. Make sure your notes and
            marks reflect the viva.
          </p>
          <div className="mt-5 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirmSignOff(false)}
              disabled={busy !== null}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmSignOff(false);
                void signOff();
              }}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
            >
              <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
              {busy === 'signoff' ? 'Signing off…' : 'Sign off'}
            </button>
          </div>
        </Modal>
      )}

      {/* Return modal */}
      {returnOpen && (
        <Modal title="Return to In Progress">
          <p className="text-xs text-gray-500">
            The intern sees this reason on their project page and the project
            reopens for edits.
          </p>
          <textarea
            rows={5}
            value={returnReason}
            onChange={(e) => setReturnReason(e.target.value)}
            maxLength={REASON_MAX}
            placeholder="What needs to change before sign-off?"
            className="mt-3 w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
          <p className="mt-1 text-[11px] text-gray-500">
            {returnReason.trim().length} / {REASON_MAX} (min {REASON_MIN})
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => {
                setReturnOpen(false);
                setReturnReason('');
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
                || returnReason.trim().length < REASON_MIN
                || returnReason.trim().length > REASON_MAX
              }
              className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-1.5 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
            >
              <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
              {busy === 'return' ? 'Returning…' : 'Return'}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-gray-700">
        {label}
      </label>
      {children}
    </div>
  );
}

function StatusBadge({ status }: { status: QaSessionStatus }) {
  const palette: Record<QaSessionStatus, string> = {
    SCHEDULED: 'bg-slate-100 text-slate-700',
    CONDUCTED: 'bg-amber-100 text-amber-800',
    COMPLETED: 'bg-green-100 text-green-800',
    RETURNED: 'bg-amber-100 text-amber-800',
  };
  const label: Record<QaSessionStatus, string> = {
    SCHEDULED: 'Scheduled',
    CONDUCTED: 'Conducted',
    COMPLETED: 'Completed',
    RETURNED: 'Returned',
  };
  return (
    <span
      className={
        'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
        palette[status]
      }
    >
      {label[status]}
    </span>
  );
}

function Banner({
  kind,
  title,
  children,
}: {
  kind: 'success' | 'warn';
  title: string;
  children?: React.ReactNode;
}) {
  const style =
    kind === 'success'
      ? 'border-green-200 bg-green-50 text-green-900'
      : 'border-amber-200 bg-amber-50 text-amber-900';
  const Icon = kind === 'success' ? CheckCircle2 : Clock;
  return (
    <div
      className={'flex items-start gap-2 rounded-md border p-3 text-sm ' + style}
    >
      <Icon className="mt-0.5 h-3.5 w-3.5 shrink-0" strokeWidth={2} />
      <div className="min-w-0">
        <p className="font-medium">{title}</p>
        {children}
      </div>
    </div>
  );
}

function Modal({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h2 className="mb-2 text-lg font-semibold text-gray-900">{title}</h2>
        {children}
      </div>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="space-y-3">
      <div className="h-8 w-1/3 animate-pulse rounded bg-gray-100" />
      <div className="h-32 animate-pulse rounded-lg bg-gray-100" />
      <div className="h-48 animate-pulse rounded-lg bg-gray-100" />
    </div>
  );
}
