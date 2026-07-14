'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, CalendarClock, ClipboardList, Clock, Plus, Star } from 'lucide-react';
import api from '@/lib/api';
import { formatDateOnly, formatFull } from '@/lib/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { Uuid } from '@/types';

interface InternSummaryResponse {
  candidateId: Uuid;
  name: string | null;
  email: string | null;
  position: string | null;
  entityName: string | null;
  hiredDate: string | null;
  assignedEvaluatorName: string | null;
}

type AssignmentStatus = 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'REVIEWED';

interface AssignmentResponse {
  id: Uuid;
  title: string;
  description: string | null;
  weekOf: string;
  dueDate: string;
  status: AssignmentStatus;
  submissionText: string | null;
  submissionLink: string | null;
  submittedAt: string | null;
  reviewNote: string | null;
  reviewedAt: string | null;
  assignedByName: string | null;
  createdAt: string;
}

type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

interface TimesheetResponse {
  id: Uuid;
  weekStart: string;
  hours: number | string;
  description: string | null;
  status: TimesheetStatus;
  approvedByName: string | null;
  approvedAt: string | null;
  reviewNote: string | null;
  createdAt: string;
}

interface TimesheetListResponse {
  entries: TimesheetResponse[];
  totalApprovedHours: number | string;
}

type EvaluationStatus = 'SCHEDULED' | 'COMPLETED' | 'MISSED';

interface EvaluationSessionResponse {
  id: Uuid;
  scheduledAt: string;
  status: EvaluationStatus;
  evaluatorName: string | null;
  evaluatorId: Uuid | null;
  overallRating: number | null;
  strengths: string | null;
  areasForImprovement: string | null;
  notes: string | null;
  completedAt: string | null;
  createdAt: string;
}

interface EvaluatorOption {
  id: Uuid;
  name: string;
}

const ASSIGNMENT_COLOR: Record<AssignmentStatus, string> = {
  ASSIGNED: 'bg-slate-100 text-slate-700',
  IN_PROGRESS: 'bg-amber-100 text-amber-800',
  SUBMITTED: 'bg-slate-100 text-slate-700',
  REVIEWED: 'bg-green-100 text-green-800',
};

const TIMESHEET_COLOR: Record<TimesheetStatus, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  SUBMITTED: 'bg-slate-100 text-slate-700',
  APPROVED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
};

const EVALUATION_COLOR: Record<EvaluationStatus, string> = {
  SCHEDULED: 'bg-slate-100 text-slate-700',
  COMPLETED: 'bg-green-100 text-green-800',
  MISSED: 'bg-gray-100 text-gray-700',
};

function AssignmentBadge({ status }: { status: AssignmentStatus }) {
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        ASSIGNMENT_COLOR[status]
      }
    >
      {status === 'REVIEWED' ? 'Reviewed ✓' : status.replace('_', ' ')}
    </span>
  );
}

function TimesheetBadge({ status }: { status: TimesheetStatus }) {
  const labels: Record<TimesheetStatus, string> = {
    DRAFT: 'Draft',
    SUBMITTED: 'Submitted',
    APPROVED: 'Approved ✓',
    REJECTED: 'Rejected',
  };
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        TIMESHEET_COLOR[status]
      }
    >
      {labels[status]}
    </span>
  );
}

function EvaluationBadge({ status }: { status: EvaluationStatus }) {
  const labels: Record<EvaluationStatus, string> = {
    SCHEDULED: 'Scheduled',
    COMPLETED: 'Completed ✓',
    MISSED: 'Missed',
  };
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        EVALUATION_COLOR[status]
      }
    >
      {labels[status]}
    </span>
  );
}

function StarRow({
  value,
  onChange,
  size = 18,
}: {
  value: number;
  onChange?: (v: number) => void;
  size?: number;
}) {
  return (
    <div className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((n) => {
        const filled = n <= value;
        const interactive = !!onChange;
        return (
          <button
            type="button"
            key={n}
            disabled={!interactive}
            onClick={() => onChange?.(n)}
            className={interactive ? 'cursor-pointer' : 'cursor-default'}
            aria-label={`${n} star${n > 1 ? 's' : ''}`}
          >
            <Star
              className={filled ? 'fill-amber-400 text-amber-400' : 'text-gray-300'}
              style={{ width: size, height: size }}
              strokeWidth={1.5}
            />
          </button>
        );
      })}
    </div>
  );
}

function formatHours(h: number | string | null | undefined): string {
  if (h === null || h === undefined) return '0';
  const n = typeof h === 'number' ? h : Number(h);
  if (Number.isNaN(n)) return String(h);
  return n % 1 === 0 ? String(n) : n.toFixed(2);
}

export default function SupervisedInternDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'TRAINER']}>
      <DashboardLayout title="Supervised Intern">
        <InternDetail />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function InternDetail() {
  const params = useParams<{ candidateId: string }>();
  const candidateId = params?.candidateId;

  const [intern, setIntern] = useState<InternSummaryResponse | null>(null);
  const [internLoaded, setInternLoaded] = useState(false);

  const [assignments, setAssignments] = useState<AssignmentResponse[] | null>(null);
  const [assignmentError, setAssignmentError] = useState<string | null>(null);
  const [showNewAssignment, setShowNewAssignment] = useState(false);

  const [timesheets, setTimesheets] = useState<TimesheetListResponse | null>(null);
  const [timesheetError, setTimesheetError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState<TimesheetResponse | null>(null);

  const [sessions, setSessions] = useState<EvaluationSessionResponse[] | null>(null);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [evaluators, setEvaluators] = useState<EvaluatorOption[] | null>(null);
  const [showSchedule, setShowSchedule] = useState(false);
  const [showChangeEvaluator, setShowChangeEvaluator] = useState(false);
  const [recording, setRecording] = useState<EvaluationSessionResponse | null>(null);

  const [toast, setToast] = useState<string | null>(null);

  const loadIntern = useCallback(async () => {
    if (!candidateId) return;
    try {
      const res = await api.get<InternSummaryResponse[]>('/api/v1/supervised/interns');
      const found = (res.data ?? []).find((i) => i.candidateId === candidateId) ?? null;
      setIntern(found);
    } catch {
      setIntern(null);
    } finally {
      setInternLoaded(true);
    }
  }, [candidateId]);

  const loadAssignments = useCallback(async () => {
    if (!candidateId) return;
    setAssignmentError(null);
    try {
      const res = await api.get<AssignmentResponse[]>(
        `/api/v1/supervised/interns/${candidateId}/assignments`,
      );
      setAssignments(res.data ?? []);
    } catch (err: any) {
      setAssignmentError(err?.response?.data?.error ?? "Couldn't load assignments.");
      setAssignments([]);
    }
  }, [candidateId]);

  const loadTimesheets = useCallback(async () => {
    if (!candidateId) return;
    setTimesheetError(null);
    try {
      const res = await api.get<TimesheetListResponse>(
        `/api/v1/supervised/interns/${candidateId}/timesheets`,
      );
      setTimesheets(res.data ?? { entries: [], totalApprovedHours: 0 });
    } catch (err: any) {
      setTimesheetError(err?.response?.data?.error ?? "Couldn't load timesheets.");
      setTimesheets({ entries: [], totalApprovedHours: 0 });
    }
  }, [candidateId]);

  const loadSessions = useCallback(async () => {
    if (!candidateId) return;
    setSessionError(null);
    try {
      const res = await api.get<EvaluationSessionResponse[]>(
        `/api/v1/supervised/interns/${candidateId}/evaluations`,
      );
      setSessions(res.data ?? []);
    } catch (err: any) {
      setSessionError(err?.response?.data?.error ?? "Couldn't load evaluations.");
      setSessions([]);
    }
  }, [candidateId]);

  const loadEvaluators = useCallback(async () => {
    try {
      const res = await api.get<EvaluatorOption[]>('/api/v1/supervised/evaluators');
      setEvaluators(res.data ?? []);
    } catch {
      setEvaluators([]);
    }
  }, []);

  useEffect(() => {
    void loadIntern();
    void loadAssignments();
    void loadTimesheets();
    void loadSessions();
    void loadEvaluators();
  }, [loadIntern, loadAssignments, loadTimesheets, loadSessions, loadEvaluators]);

  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 3000);
    return () => window.clearTimeout(t);
  }, [toast]);

  const approveTimesheet = async (t: TimesheetResponse) => {
    try {
      await api.post(`/api/v1/supervised/timesheets/${t.id}/approve`);
      setToast('Timesheet approved.');
      void loadTimesheets();
    } catch (err: any) {
      setToast(err?.response?.data?.error ?? 'Could not approve.');
    }
  };

  return (
    <section className="space-y-8">
      <div>
        <Link
          href="/careers/supervised"
          className="mb-4 inline-flex items-center gap-1 text-sm text-gray-600 hover:text-gray-900"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to Supervised Interns
        </Link>

        <header>
          <h1 className="text-2xl font-semibold text-slate-900">
            {intern?.name ?? (internLoaded ? 'Intern not found' : 'Loading…')}
          </h1>
          {intern?.position ? (
            <p className="mt-1 text-sm text-slate-600">
              {intern.position}
              {intern.entityName ? <> · {intern.entityName}</> : null}
            </p>
          ) : null}
        </header>
      </div>

      {toast && (
        <div className="rounded border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-800">
          {toast}
        </div>
      )}

      {/* Assignments */}
      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-gray-900">Assignments</h2>
          <button
            type="button"
            onClick={() => setShowNewAssignment(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
          >
            <Plus className="h-4 w-4" />
            New Assignment
          </button>
        </div>

        {assignmentError && (
          <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {assignmentError}
          </div>
        )}

        {assignments === null ? (
          <div className="py-8 text-center text-sm text-gray-500">Loading…</div>
        ) : assignments.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-8 text-center">
            <ClipboardList className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
            <p className="text-sm text-gray-600">No assignments yet.</p>
          </div>
        ) : (
          <ul className="space-y-3">
            {assignments.map((a) => (
              <AssignmentRow
                key={a.id}
                assignment={a}
                onReviewed={(msg) => {
                  setToast(msg);
                  void loadAssignments();
                }}
              />
            ))}
          </ul>
        )}
      </div>

      {/* Timesheets */}
      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-lg font-semibold text-gray-900">Timesheets</h2>
          <div className="text-sm text-gray-500">
            Total approved:{' '}
            <span className="font-medium text-gray-900">
              {formatHours(timesheets?.totalApprovedHours ?? 0)} hrs
            </span>
          </div>
        </div>

        {timesheetError && (
          <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {timesheetError}
          </div>
        )}

        {timesheets === null ? (
          <div className="py-8 text-center text-sm text-gray-500">Loading…</div>
        ) : timesheets.entries.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-8 text-center">
            <Clock className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
            <p className="text-sm text-gray-600">No timesheets logged yet.</p>
          </div>
        ) : (
          <ul className="space-y-3">
            {timesheets.entries.map((t) => (
              <li key={t.id} className="rounded-lg border border-gray-200 bg-white p-4">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
                  <div className="font-semibold text-gray-900">
                    Week of {formatDateOnly(t.weekStart)} ·{' '}
                    <span className="font-normal text-gray-700">{formatHours(t.hours)} hrs</span>
                  </div>
                  <TimesheetBadge status={t.status} />
                </div>
                {t.description && (
                  <p className="mt-2 whitespace-pre-wrap text-sm text-gray-700">{t.description}</p>
                )}

                {t.status === 'APPROVED' && (
                  <div className="mt-2 text-xs text-gray-500">
                    Approved
                    {t.approvedByName ? <> by {t.approvedByName}</> : null}
                    {t.approvedAt ? <> · {formatDateOnly(t.approvedAt)}</> : null}
                  </div>
                )}

                {t.status === 'REJECTED' && t.reviewNote && (
                  <div className="mt-3 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                    <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-red-700">
                      Reason
                    </div>
                    <p className="whitespace-pre-wrap">{t.reviewNote}</p>
                  </div>
                )}

                {t.status === 'SUBMITTED' && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => void approveTimesheet(t)}
                      className="rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
                    >
                      Approve ✓
                    </button>
                    <button
                      type="button"
                      onClick={() => setRejecting(t)}
                      className="rounded-md border border-red-300 bg-white px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50"
                    >
                      Reject ✗
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Evaluations */}
      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="text-lg font-semibold text-gray-900">Evaluations</h2>
            <div className="text-sm text-gray-600">
              Evaluator:{' '}
              <span className="font-medium text-gray-900">
                {intern?.assignedEvaluatorName ?? '— unassigned'}
              </span>
              <button
                type="button"
                onClick={() => setShowChangeEvaluator(true)}
                className="ml-2 text-xs text-accent hover:underline"
              >
                Change
              </button>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setShowSchedule(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
          >
            <Plus className="h-4 w-4" />
            Schedule session
          </button>
        </div>

        {sessionError && (
          <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {sessionError}
          </div>
        )}

        {sessions === null ? (
          <div className="py-8 text-center text-sm text-gray-500">Loading…</div>
        ) : sessions.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-8 text-center">
            <CalendarClock className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
            <p className="text-sm text-gray-600">No evaluation sessions yet.</p>
          </div>
        ) : (
          <ul className="space-y-3">
            {sessions.map((s) => (
              <li key={s.id} className="rounded-lg border border-gray-200 bg-white p-4">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
                  <div className="font-semibold text-gray-900">
                    {formatFull(s.scheduledAt)}
                    {s.evaluatorName ? (
                      <span className="font-normal text-gray-600"> · {s.evaluatorName}</span>
                    ) : null}
                  </div>
                  <EvaluationBadge status={s.status} />
                </div>

                {s.status === 'COMPLETED' && (
                  <div className="mt-3 space-y-2 text-sm">
                    {s.overallRating != null && (
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                          Rating
                        </span>
                        <StarRow value={s.overallRating} size={16} />
                      </div>
                    )}
                    {s.strengths && (
                      <div>
                        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                          Strengths
                        </div>
                        <p className="whitespace-pre-wrap text-gray-800">{s.strengths}</p>
                      </div>
                    )}
                    {s.areasForImprovement && (
                      <div>
                        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                          Areas for improvement
                        </div>
                        <p className="whitespace-pre-wrap text-gray-800">
                          {s.areasForImprovement}
                        </p>
                      </div>
                    )}
                    {s.notes && (
                      <div>
                        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">
                          Notes
                        </div>
                        <p className="whitespace-pre-wrap text-gray-800">{s.notes}</p>
                      </div>
                    )}
                  </div>
                )}

                {s.status === 'SCHEDULED' && (
                  <div className="mt-3">
                    <button
                      type="button"
                      onClick={() => setRecording(s)}
                      className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
                    >
                      Record evaluation
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {showNewAssignment && candidateId && (
        <NewAssignmentModal
          candidateId={candidateId}
          onClose={() => setShowNewAssignment(false)}
          onCreated={() => {
            setShowNewAssignment(false);
            setToast('Assignment created.');
            void loadAssignments();
          }}
        />
      )}

      {rejecting && (
        <RejectTimesheetModal
          timesheet={rejecting}
          onClose={() => setRejecting(null)}
          onRejected={() => {
            setRejecting(null);
            setToast('Timesheet rejected.');
            void loadTimesheets();
          }}
        />
      )}

      {showSchedule && candidateId && (
        <ScheduleSessionModal
          candidateId={candidateId}
          evaluators={evaluators ?? []}
          defaultEvaluatorName={intern?.assignedEvaluatorName ?? null}
          onClose={() => setShowSchedule(false)}
          onScheduled={() => {
            setShowSchedule(false);
            setToast('Session scheduled.');
            void loadSessions();
          }}
        />
      )}

      {showChangeEvaluator && candidateId && (
        <ChangeEvaluatorModal
          candidateId={candidateId}
          evaluators={evaluators ?? []}
          currentName={intern?.assignedEvaluatorName ?? null}
          onClose={() => setShowChangeEvaluator(false)}
          onSaved={() => {
            setShowChangeEvaluator(false);
            setToast('Evaluator updated.');
            void loadIntern();
          }}
        />
      )}

      {recording && (
        <RecordEvaluationModal
          session={recording}
          onClose={() => setRecording(null)}
          onSaved={() => {
            setRecording(null);
            setToast('Evaluation recorded.');
            void loadSessions();
          }}
        />
      )}
    </section>
  );
}

function AssignmentRow({
  assignment,
  onReviewed,
}: {
  assignment: AssignmentResponse;
  onReviewed: (msg: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [reviewError, setReviewError] = useState<string | null>(null);

  const isSubmitted = assignment.status === 'SUBMITTED';
  const isReviewed = assignment.status === 'REVIEWED';

  const submitReview = async () => {
    if (!note.trim()) {
      setReviewError('Please write a review note.');
      return;
    }
    setSubmitting(true);
    setReviewError(null);
    try {
      await api.post(`/api/v1/supervised/assignments/${assignment.id}/review`, {
        reviewNote: note.trim(),
      });
      setReviewOpen(false);
      setNote('');
      onReviewed('Marked as reviewed.');
    } catch (err: any) {
      setReviewError(err?.response?.data?.error ?? 'Could not mark reviewed.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <li className="rounded-lg border border-gray-200 bg-white p-4">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="text-left font-semibold text-gray-900 hover:underline"
        >
          {assignment.title}
        </button>
        <AssignmentBadge status={assignment.status} />
      </div>
      <div className="text-xs text-gray-500">
        Week of {formatDateOnly(assignment.weekOf)} · Due {formatDateOnly(assignment.dueDate)}
        {assignment.assignedByName ? <> · By {assignment.assignedByName}</> : null}
      </div>

      {expanded && (
        <div className="mt-3 space-y-3 border-t border-gray-100 pt-3 text-sm">
          {assignment.description && (
            <div className="whitespace-pre-wrap text-gray-700">{assignment.description}</div>
          )}
          {(isSubmitted || isReviewed) && (
            <div className="rounded border border-gray-200 bg-gray-50 p-3">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500">
                Submission
              </div>
              {assignment.submissionText && (
                <p className="whitespace-pre-wrap text-gray-800">{assignment.submissionText}</p>
              )}
              {assignment.submissionLink && (
                <a
                  href={assignment.submissionLink}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-1 inline-block break-all text-xs text-accent hover:underline"
                >
                  {assignment.submissionLink}
                </a>
              )}
              {assignment.submittedAt && (
                <div className="mt-1 text-xs text-gray-500">
                  Submitted {formatDateOnly(assignment.submittedAt)}
                </div>
              )}
            </div>
          )}
          {isReviewed && assignment.reviewNote && (
            <div className="rounded border border-green-200 bg-green-50 p-3 text-sm text-green-900">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-green-700">
                Reviewer note
              </div>
              <p className="whitespace-pre-wrap">{assignment.reviewNote}</p>
            </div>
          )}
        </div>
      )}

      {isSubmitted && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {!reviewOpen ? (
            <button
              type="button"
              onClick={() => setReviewOpen(true)}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Review
            </button>
          ) : (
            <div className="w-full space-y-2">
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                placeholder="Review note for the intern…"
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
              {reviewError && <div className="text-xs text-red-600">{reviewError}</div>}
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={submitReview}
                  disabled={submitting}
                  className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
                >
                  {submitting ? 'Saving…' : 'Mark Reviewed'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setReviewOpen(false);
                    setNote('');
                    setReviewError(null);
                  }}
                  className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </li>
  );
}

function NewAssignmentModal({
  candidateId,
  onClose,
  onCreated,
}: {
  candidateId: string;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [weekOf, setWeekOf] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!title.trim() || !weekOf || !dueDate) {
      setError('Title, week of, and due date are required.');
      return;
    }
    if (dueDate < weekOf) {
      setError('Due date cannot be before the week-of date.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/supervised/interns/${candidateId}/assignments`, {
        title: title.trim(),
        description: description.trim() || null,
        weekOf,
        dueDate,
      });
      onCreated();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not create assignment.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-4 text-lg font-semibold text-gray-900">New assignment</h3>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Title <span className="text-red-500">*</span>
            </label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                Week of <span className="text-red-500">*</span>
              </label>
              <input
                type="date"
                value={weekOf}
                onChange={(e) => setWeekOf(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                Due date <span className="text-red-500">*</span>
              </label>
              <input
                type="date"
                value={dueDate}
                onChange={(e) => setDueDate(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </div>
          </div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Creating…' : 'Create'}
          </button>
        </div>
      </div>
    </div>
  );
}

function RejectTimesheetModal({
  timesheet,
  onClose,
  onRejected,
}: {
  timesheet: TimesheetResponse;
  onClose: () => void;
  onRejected: () => void;
}) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!reason.trim()) {
      setError('A reason is required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/supervised/timesheets/${timesheet.id}/reject`, {
        reason: reason.trim(),
      });
      onRejected();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not reject the timesheet.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-1 text-lg font-semibold text-gray-900">Reject timesheet</h3>
        <p className="mb-4 text-xs text-gray-500">
          Week of {formatDateOnly(timesheet.weekStart)} · {formatHours(timesheet.hours)} hrs
        </p>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Reason <span className="text-red-500">*</span>
        </label>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          placeholder="Hours don't match the assignment…"
        />
        {error && <div className="mt-2 text-sm text-red-600">{error}</div>}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
          >
            {submitting ? 'Rejecting…' : 'Reject'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ScheduleSessionModal({
  candidateId,
  evaluators,
  defaultEvaluatorName,
  onClose,
  onScheduled,
}: {
  candidateId: string;
  evaluators: EvaluatorOption[];
  defaultEvaluatorName: string | null;
  onClose: () => void;
  onScheduled: () => void;
}) {
  const defaultId =
    evaluators.find((e) => e.name === defaultEvaluatorName)?.id ?? evaluators[0]?.id ?? '';
  const [when, setWhen] = useState('');
  const [evaluatorId, setEvaluatorId] = useState<string>(defaultId);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!when) {
      setError('Date & time is required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/supervised/interns/${candidateId}/evaluations`, {
        scheduledAt: when,
        evaluatorId: evaluatorId || null,
      });
      onScheduled();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not schedule the session.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-4 text-lg font-semibold text-gray-900">Schedule session</h3>
        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Date &amp; time <span className="text-red-500">*</span>
            </label>
            <input
              type="datetime-local"
              value={when}
              onChange={(e) => setWhen(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Evaluator</label>
            <select
              value={evaluatorId}
              onChange={(e) => setEvaluatorId(e.target.value)}
              className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="">— Assigned evaluator —</option>
              {evaluators.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name}
                </option>
              ))}
            </select>
          </div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Scheduling…' : 'Schedule'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ChangeEvaluatorModal({
  candidateId,
  evaluators,
  currentName,
  onClose,
  onSaved,
}: {
  candidateId: string;
  evaluators: EvaluatorOption[];
  currentName: string | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const currentId = evaluators.find((e) => e.name === currentName)?.id ?? '';
  const [evaluatorId, setEvaluatorId] = useState<string>(currentId);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!evaluatorId) {
      setError('Please pick an evaluator.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/supervised/interns/${candidateId}/assign-evaluator`, {
        evaluatorId,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not save the evaluator.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-4 text-lg font-semibold text-gray-900">Change evaluator</h3>
        <label className="mb-1 block text-sm font-medium text-gray-700">Evaluator</label>
        <select
          value={evaluatorId}
          onChange={(e) => setEvaluatorId(e.target.value)}
          className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">— Pick one —</option>
          {evaluators.map((e) => (
            <option key={e.id} value={e.id}>
              {e.name}
            </option>
          ))}
        </select>
        {error && <div className="mt-2 text-sm text-red-600">{error}</div>}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

function RecordEvaluationModal({
  session,
  onClose,
  onSaved,
}: {
  session: EvaluationSessionResponse;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [rating, setRating] = useState<number>(session.overallRating ?? 0);
  const [strengths, setStrengths] = useState(session.strengths ?? '');
  const [areas, setAreas] = useState(session.areasForImprovement ?? '');
  const [notes, setNotes] = useState(session.notes ?? '');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (rating < 1 || rating > 5) {
      setError('Overall rating is required (1–5).');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/supervised/evaluations/${session.id}/complete`, {
        overallRating: rating,
        strengths: strengths.trim() || null,
        areasForImprovement: areas.trim() || null,
        notes: notes.trim() || null,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Could not save the evaluation.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h3 className="mb-1 text-lg font-semibold text-gray-900">Record evaluation</h3>
        <p className="mb-4 text-xs text-gray-500">{formatFull(session.scheduledAt)}</p>
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Overall rating <span className="text-red-500">*</span>
            </label>
            <StarRow value={rating} onChange={setRating} size={22} />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Strengths</label>
            <textarea
              value={strengths}
              onChange={(e) => setStrengths(e.target.value)}
              rows={3}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Areas for improvement
            </label>
            <textarea
              value={areas}
              onChange={(e) => setAreas(e.target.value)}
              rows={3}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Notes</label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Save evaluation'}
          </button>
        </div>
      </div>
    </div>
  );
}
