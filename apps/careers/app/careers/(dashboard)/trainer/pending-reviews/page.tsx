'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import { AlertOctagon, CheckCircle2, Clock, FileText, RotateCcw, X } from 'lucide-react';

type InternRow = { internLifecycleId: string; fullName: string | null };

type PendingRow = {
  submissionId: string;
  projectId: string;
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  projectTitle: string;
  technologyArea: string | null;
  submittedAt: string;
  hoursWaiting: number;
  version: number;
  monthYear: string | null;
  projectNumber: number | null;
  dueDate: string | null;
  description: string | null;
  linksJson: string | null;
};

type PendingPage = {
  items: PendingRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

type SubmissionDetail = {
  submissionId: string;
  projectId: string;
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  projectTitle: string;
  technologyArea: string | null;
  projectInstructions: string | null;
  submittedAt: string;
  version: number;
  description: string | null;
  linksJson: string | null;
  priorRounds: Array<{
    submissionId: string;
    submittedAt: string;
    version: number;
    description: string | null;
    trainerDecision: string | null;
    trainerFeedback: string | null;
    reviewedAt: string | null;
  }>;
  technicalScore: number | null;
  communicationScore: number | null;
  blockersNote: string | null;
  nextAction: string | null;
  nextActionDueDate: string | null;
  reviewedLinksCsv: string | null;
  trainerDecision: string | null;
  trainerFeedback: string | null;
  completionStatus: string | null;
  reviewedAt: string | null;
};

export default function TrainerPendingReviewsPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <PendingReviewsInner />
    </Suspense>
  );
}

function PendingReviewsInner() {
  const sp = useSearchParams();
  const prefillIntern = sp?.get('intern') ?? '';

  const [data, setData] = useState<PendingPage | null>(null);
  const [interns, setInterns] = useState<InternRow[]>([]);
  const [internFilter, setInternFilter] = useState(prefillIntern);
  const [search, setSearch] = useState('');
  const [ageFilter, setAgeFilter] = useState<'ALL' | '24' | '48' | '72'>('ALL');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (internFilter) params.set('internLifecycleId', internFilter);
      if (search.trim()) params.set('search', search.trim());
      params.set('pageSize', '50');
      const res = await api.get<PendingPage>(
        `/api/v1/trainer/pending-reviews?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [internFilter, search]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<{ items: InternRow[] }>(
          '/api/v1/trainer/active-interns?pageSize=100',
        );
        setInterns(res.data.items ?? []);
      } catch { setInterns([]); }
    })();
  }, []);

  const minHours = ageFilter === '24' ? 24 : ageFilter === '48' ? 48 : ageFilter === '72' ? 72 : 0;
  const rows = (data?.items ?? []).filter((r) => r.hoursWaiting >= minHours);

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Pending Reviews</h1>
        <p className="text-xs text-slate-500">
          Doc §9 queue — submitted project work awaiting Trainer feedback.
          Trainer <em>reviews &amp; approves</em>; the Evaluator runs the Q&amp;A
          session and signs final completion.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-white p-3">
        <select value={internFilter} onChange={(e) => setInternFilter(e.target.value)}
          className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="">All interns</option>
          {interns.map((i) => <option key={i.internLifecycleId} value={i.internLifecycleId}>{i.fullName}</option>)}
        </select>
        <input value={search} onChange={(e) => setSearch(e.target.value)}
          placeholder="Search intern / project"
          className="rounded-md border border-slate-200 px-3 py-1.5 text-sm" />
        <select value={ageFilter} onChange={(e) => setAgeFilter(e.target.value as typeof ageFilter)}
          className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="ALL">All ages</option>
          <option value="24">&gt; 24h</option>
          <option value="48">&gt; 48h (urgent)</option>
          <option value="72">&gt; 72h (overdue)</option>
        </select>
        <span className="ml-auto text-xs text-slate-500">
          {rows.length} of {data?.totalElements ?? 0} awaiting
        </span>
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            No submissions awaiting your review.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Project</th>
                <th className="px-3 py-2">Submitted</th>
                <th className="px-3 py-2">Age</th>
                <th className="px-3 py-2">Slot</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => <Row key={r.submissionId} r={r} onOpen={() => setOpenId(r.submissionId)} />)}
            </tbody>
          </table>
        )}
      </div>

      {openId && (
        <ReviewModal
          submissionId={openId}
          onClose={() => setOpenId(null)}
          onSubmitted={() => { setOpenId(null); void load(); }}
        />
      )}
    </div>
  );
}

function Row({ r, onOpen }: { r: PendingRow; onOpen: () => void }) {
  const urgent = r.hoursWaiting > 48;
  return (
    <tr>
      <td className="px-3 py-2 text-sm font-medium text-slate-900">{r.internName ?? '—'}</td>
      <td className="px-3 py-2">
        <p className="text-sm text-slate-900">{r.projectTitle}</p>
        <p className="text-[10px] text-slate-500">
          {r.technologyArea}
          {r.version > 1 && <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-amber-800">v{r.version}</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {new Date(r.submittedAt).toLocaleString()}
      </td>
      <td className="px-3 py-2 text-xs">
        <span className={urgent
          ? 'rounded bg-red-100 px-2 py-0.5 font-semibold text-red-800'
          : 'text-slate-700'}>
          {Math.round(r.hoursWaiting)}h
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.projectNumber ? `P${r.projectNumber} · ${r.monthYear}` : '—'}
      </td>
      <td className="px-3 py-2 text-right">
        <button type="button" onClick={onOpen}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800">
          Review
        </button>
      </td>
    </tr>
  );
}

function ReviewModal({ submissionId, onClose, onSubmitted }: {
  submissionId: string; onClose: () => void; onSubmitted: () => void;
}) {
  const [detail, setDetail] = useState<SubmissionDetail | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  // Feedback form
  const [completionStatus, setCompletionStatus] = useState('Needs Revision');
  const [technicalScore, setTechnicalScore] = useState<number | ''>('');
  const [communicationScore, setCommunicationScore] = useState<number | ''>('');
  const [trainerFeedback, setTrainerFeedback] = useState('');
  const [blockersNote, setBlockersNote] = useState('');
  const [nextAction, setNextAction] = useState<string>('NONE');
  const [nextActionDueDate, setNextActionDueDate] = useState('');
  const [reviewedLinksCsv, setReviewedLinksCsv] = useState('');
  const [decision, setDecision] = useState<'ACCEPT' | 'REQUEST_REVISION' | 'ESCALATE' | 'NO_ACTION_YET'>('REQUEST_REVISION');
  const [escalationReason, setEscalationReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<SubmissionDetail>(
          `/api/v1/trainer/pending-reviews/${submissionId}`,
        );
        setDetail(res.data);
        setLoadErr(null);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setLoadErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
      }
    })();
  }, [submissionId]);

  // Decision derives from completionStatus by default, but user can override
  useEffect(() => {
    if (completionStatus === 'Completed') setDecision('ACCEPT');
    else if (completionStatus === 'Needs Revision') setDecision('REQUEST_REVISION');
    else if (completionStatus === 'Blocked') setDecision('ESCALATE');
    else if (completionStatus === 'Incomplete') setDecision('NO_ACTION_YET');
  }, [completionStatus]);

  async function submit() {
    setErr(null);
    if (decision === 'ACCEPT' || decision === 'REQUEST_REVISION') {
      if (!technicalScore || !communicationScore) {
        setErr('Technical + communication scores required for this decision.');
        return;
      }
      if (trainerFeedback.trim().length < 20) {
        setErr('Code review notes must be at least 20 chars.');
        return;
      }
    }
    if (decision === 'ESCALATE' && escalationReason.trim().length < 50) {
      setErr('Escalation reason must be at least 50 chars.');
      return;
    }
    if (decision === 'NO_ACTION_YET'
        && !confirm('Leave this submission unactioned and come back later?')) {
      return;
    }
    setSubmitting(true);
    try {
      await api.post(
        `/api/v1/trainer/pending-reviews/${submissionId}/feedback`,
        {
          completionStatus,
          technicalScore: technicalScore === '' ? null : technicalScore,
          communicationScore: communicationScore === '' ? null : communicationScore,
          trainerFeedback: trainerFeedback.trim() || null,
          blockersNote: blockersNote.trim() || null,
          nextAction: nextAction === 'NONE' ? null : nextAction,
          nextActionDueDate: nextActionDueDate || null,
          reviewedLinksCsv: reviewedLinksCsv.trim() || null,
          decision,
          escalationReason: decision === 'ESCALATE' ? escalationReason.trim() : null,
        },
      );
      onSubmitted();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to submit');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[92vh] w-full max-w-5xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              {detail ? `Review · ${detail.projectTitle}` : 'Loading…'}
            </h3>
            {detail && (
              <p className="text-xs text-slate-500">
                {detail.internName} · submitted {new Date(detail.submittedAt).toLocaleString()}
                {detail.version > 1 && <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-amber-800">v{detail.version}</span>}
              </p>
            )}
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="grid flex-1 grid-cols-1 gap-4 overflow-y-auto p-5 md:grid-cols-2">
          {/* LEFT: submission viewer */}
          <section className="space-y-3">
            <h4 className="text-xs font-semibold uppercase text-slate-500">Submission</h4>
            {loadErr && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{loadErr}</p>}
            {detail ? (
              <>
                {detail.description && (
                  <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700 whitespace-pre-wrap">
                    {detail.description}
                  </div>
                )}
                {detail.linksJson && (
                  <details className="rounded-md border border-slate-200 bg-white p-2 text-xs">
                    <summary className="cursor-pointer font-semibold">Links</summary>
                    <pre className="mt-2 whitespace-pre-wrap font-mono text-[11px] text-slate-700">{detail.linksJson}</pre>
                  </details>
                )}
                {detail.projectInstructions && (
                  <details className="rounded-md border border-slate-200 bg-white p-2 text-xs">
                    <summary className="cursor-pointer font-semibold">Project instructions</summary>
                    <pre className="mt-2 whitespace-pre-wrap font-mono text-[11px] text-slate-700">{detail.projectInstructions}</pre>
                  </details>
                )}
                {detail.priorRounds.length > 0 && (
                  <details className="rounded-md border border-amber-200 bg-amber-50 p-2 text-xs">
                    <summary className="cursor-pointer font-semibold">Prior rounds ({detail.priorRounds.length})</summary>
                    <ul className="mt-2 space-y-2">
                      {detail.priorRounds.map((p) => (
                        <li key={p.submissionId} className="rounded bg-white p-2">
                          <p className="text-[10px] text-slate-500">
                            v{p.version} · {new Date(p.submittedAt).toLocaleString()} · {p.trainerDecision ?? 'pending'}
                          </p>
                          {p.description && <p className="mt-1 whitespace-pre-wrap text-slate-700">{p.description}</p>}
                          {p.trainerFeedback && (
                            <p className="mt-1 whitespace-pre-wrap text-red-700">
                              <strong>Trainer feedback:</strong> {p.trainerFeedback}
                            </p>
                          )}
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </>
            ) : (
              <div className="h-32 animate-pulse rounded bg-slate-100" />
            )}
          </section>

          {/* RIGHT: feedback form */}
          <section className="space-y-3">
            <h4 className="text-xs font-semibold uppercase text-slate-500">Feedback Form</h4>
            <Field label="Project completion status*">
              <select value={completionStatus} onChange={(e) => setCompletionStatus(e.target.value)}
                className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                <option value="Completed">Completed</option>
                <option value="Needs Revision">Needs Revision</option>
                <option value="Incomplete">Incomplete</option>
                <option value="Blocked">Blocked</option>
              </select>
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Technical quality (1-5)">
                <select value={technicalScore} onChange={(e) => setTechnicalScore(e.target.value === '' ? '' : Number(e.target.value))}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                  <option value="">—</option>
                  {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </Field>
              <Field label="Communication / clarity (1-5)">
                <select value={communicationScore} onChange={(e) => setCommunicationScore(e.target.value === '' ? '' : Number(e.target.value))}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                  <option value="">—</option>
                  {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </Field>
            </div>
            <Field label={`Code review notes (≥ 20 chars — ${trainerFeedback.length})`}>
              <textarea value={trainerFeedback} onChange={(e) => setTrainerFeedback(e.target.value)} rows={5} maxLength={5000}
                placeholder="Shown verbatim to the intern on REQUEST_REVISION / ESCALATE."
                className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
            </Field>
            <Field label="Blockers (optional)">
              <textarea value={blockersNote} onChange={(e) => setBlockersNote(e.target.value)} rows={2} maxLength={2000}
                className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Next action">
                <select value={nextAction} onChange={(e) => setNextAction(e.target.value)}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
                  <option value="NONE">None</option>
                  <option value="REVISION">Revision</option>
                  <option value="NEXT_PROJECT">Next project</option>
                  <option value="EXTRA_TRAINING">Extra training</option>
                  <option value="ESCALATION">Escalation</option>
                </select>
              </Field>
              <Field label="Next action due date">
                <input type="date" value={nextActionDueDate} onChange={(e) => setNextActionDueDate(e.target.value)}
                  className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
              </Field>
            </div>
            <Field label="Reviewed links (CSV of URLs)">
              <textarea value={reviewedLinksCsv} onChange={(e) => setReviewedLinksCsv(e.target.value)} rows={2} maxLength={2000}
                placeholder="https://github.com/org/repo/pull/12, https://staging.example.com/preview"
                className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
            </Field>

            <hr className="border-slate-200" />

            <Field label="Decision*">
              <div className="space-y-1 text-sm">
                {[
                  { v: 'ACCEPT', label: 'Approve — send to Evaluator for Q&A', icon: CheckCircle2, tone: 'text-green-700' },
                  { v: 'REQUEST_REVISION', label: 'Request revision', icon: RotateCcw, tone: 'text-amber-700' },
                  { v: 'ESCALATE', label: 'Escalate to ERM + Manager', icon: AlertOctagon, tone: 'text-red-700' },
                  { v: 'NO_ACTION_YET', label: 'No action yet (silent state)', icon: Clock, tone: 'text-slate-700' },
                ].map(({ v, label, icon: Icon, tone }) => (
                  <label key={v}
                    className={'flex cursor-pointer items-center gap-2 rounded-md border px-2 py-1.5 ' +
                      (decision === v ? 'border-brand-700 bg-brand-50' : 'border-slate-200')}>
                    <input type="radio" name="decision" checked={decision === v}
                      onChange={() => setDecision(v as typeof decision)} />
                    <Icon className={`h-4 w-4 ${tone}`} />
                    <span>{label}</span>
                  </label>
                ))}
              </div>
            </Field>

            {decision === 'ESCALATE' && (
              <Field label={`Escalation reason (≥ 50 chars — ${escalationReason.length})*`}>
                <textarea value={escalationReason} onChange={(e) => setEscalationReason(e.target.value)} rows={3} maxLength={5000}
                  className="w-full rounded-md border border-red-300 px-2 py-1.5 text-sm" />
              </Field>
            )}

            {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}

            <div className="flex justify-end gap-2 border-t border-slate-100 pt-3">
              <button type="button" onClick={onClose} className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
              <button type="button" onClick={submit} disabled={submitting}
                className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
                <FileText className="mr-1 inline h-3.5 w-3.5" />
                {submitting ? 'Submitting…' : 'Publish feedback'}
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
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
