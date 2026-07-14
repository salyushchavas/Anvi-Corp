'use client';

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import { ChevronRight, History, X } from 'lucide-react';

type InternRow = { internLifecycleId: string; fullName: string | null };

type HistoryRow = {
  submissionId: string;
  projectId: string;
  internLifecycleId: string;
  internName: string | null;
  projectTitle: string;
  technologyArea: string | null;
  version: number;
  submittedAt: string;
  reviewedAt: string | null;
  trainerDecision: string | null;
  completionStatus: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  hoursToReview: number | null;
};

type HistoryPage = {
  items: HistoryRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

type HistoryDetail = {
  submissionId: string;
  projectId: string;
  internLifecycleId: string;
  internName: string | null;
  projectTitle: string;
  technologyArea: string | null;
  version: number;
  submittedAt: string;
  reviewedAt: string | null;
  description: string | null;
  linksJson: string | null;
  trainerDecision: string | null;
  trainerFeedback: string | null;
  completionStatus: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  blockersNote: string | null;
  nextAction: string | null;
  nextActionDueDate: string | null;
  reviewedLinksCsv: string | null;
};

type InternTimeline = {
  internLifecycleId: string;
  internName: string | null;
  totalSubmissions: number;
  accepted: number;
  revisionsRequested: number;
  escalations: number;
  averageTechnical: number | null;
  averageCommunication: number | null;
  items: HistoryRow[];
};

const DECISIONS = ['ACCEPT', 'REQUEST_REVISION', 'ESCALATE', 'NO_ACTION_YET'] as const;
type Decision = (typeof DECISIONS)[number];

export default function TrainerFeedbackHistoryPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <FeedbackHistoryInner />
    </Suspense>
  );
}

function FeedbackHistoryInner() {
  const sp = useSearchParams();
  const prefillIntern = sp?.get('intern') ?? '';

  const [data, setData] = useState<HistoryPage | null>(null);
  const [interns, setInterns] = useState<InternRow[]>([]);
  const [internFilter, setInternFilter] = useState(prefillIntern);
  const [decisionFilters, setDecisionFilters] = useState<Set<Decision>>(new Set());
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [timelineFor, setTimelineFor] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (internFilter) params.set('internLifecycleId', internFilter);
      if (search.trim()) params.set('search', search.trim());
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      if (decisionFilters.size > 0) {
        params.set('decisions', Array.from(decisionFilters).join(','));
      }
      params.set('page', String(page));
      params.set('pageSize', '50');
      const res = await api.get<HistoryPage>(
        `/api/v1/trainer/feedback-history?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [internFilter, search, from, to, decisionFilters, page]);

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

  function toggleDecision(d: Decision) {
    setPage(0);
    setDecisionFilters((s) => {
      const next = new Set(s);
      if (next.has(d)) next.delete(d); else next.add(d);
      return next;
    });
  }

  const rows = data?.items ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Feedback History</h1>
        <p className="text-xs text-slate-500">
          Audit-style timeline of every feedback decision published. Read-only.
        </p>
      </div>

      <div className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
        <div className="flex flex-wrap items-center gap-2">
          <select value={internFilter} onChange={(e) => { setPage(0); setInternFilter(e.target.value); }}
            className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
            <option value="">All interns</option>
            {interns.map((i) => <option key={i.internLifecycleId} value={i.internLifecycleId}>{i.fullName}</option>)}
          </select>
          <input value={search} onChange={(e) => { setPage(0); setSearch(e.target.value); }}
            placeholder="Search intern / project"
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm" />
          <input type="date" value={from} onChange={(e) => { setPage(0); setFrom(e.target.value); }}
            className="rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
          <span className="text-xs text-slate-400">→</span>
          <input type="date" value={to} onChange={(e) => { setPage(0); setTo(e.target.value); }}
            className="rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
          {internFilter && (
            <button type="button" onClick={() => setTimelineFor(internFilter)}
              className="ml-auto inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
              <History className="h-3.5 w-3.5" />
              Per-intern summary
            </button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[10px] uppercase tracking-wide text-slate-400">Decision:</span>
          {DECISIONS.map((d) => {
            const on = decisionFilters.has(d);
            return (
              <button key={d} type="button" onClick={() => toggleDecision(d)}
                className={'rounded-full border px-2.5 py-0.5 text-[11px] ' +
                  (on ? 'border-brand-600 bg-brand-600 text-white'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50')}>
                {labelDecision(d)}
              </button>
            );
          })}
          <span className="ml-auto text-xs text-slate-500">
            {data?.totalElements ?? 0} entries
          </span>
        </div>
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-40 animate-pulse" />
        ) : rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            No feedback history matches these filters.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Project</th>
                <th className="px-3 py-2">Decision</th>
                <th className="px-3 py-2">Scores</th>
                <th className="px-3 py-2">Reviewed</th>
                <th className="px-3 py-2">Turnaround</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr key={r.submissionId}>
                  <td className="px-3 py-2 font-medium text-slate-900">{r.internName ?? '—'}</td>
                  <td className="px-3 py-2">
                    <p className="text-slate-900">{r.projectTitle}</p>
                    <p className="text-[10px] text-slate-500">
                      {r.technologyArea}
                      {r.version > 1 && <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-amber-800">v{r.version}</span>}
                    </p>
                  </td>
                  <td className="px-3 py-2">
                    <DecisionPill decision={r.trainerDecision} />
                    {r.completionStatus && (
                      <p className="mt-0.5 text-[10px] text-slate-500">{r.completionStatus}</p>
                    )}
                  </td>
                  <td className="px-3 py-2 text-xs text-slate-700">
                    {r.technicalScore != null && <span title="Technical">T:{r.technicalScore}</span>}
                    {r.technicalScore != null && r.communicationScore != null && ' · '}
                    {r.communicationScore != null && <span title="Communication">C:{r.communicationScore}</span>}
                    {r.technicalScore == null && r.communicationScore == null && '—'}
                  </td>
                  <td className="px-3 py-2 text-xs text-slate-700">
                    {r.reviewedAt ? new Date(r.reviewedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-3 py-2 text-xs text-slate-700">
                    {r.hoursToReview != null ? `${Math.round(r.hoursToReview)}h` : '—'}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button type="button" onClick={() => setOpenId(r.submissionId)}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50">
                      View <ChevronRight className="h-3 w-3" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-600">
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-2">
            <button type="button" disabled={data.page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50">Prev</button>
            <button type="button" disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50">Next</button>
          </div>
        </div>
      )}

      {openId && <DetailDrawer submissionId={openId} onClose={() => setOpenId(null)} />}
      {timelineFor && <TimelineDrawer internLifecycleId={timelineFor} onClose={() => setTimelineFor(null)} />}
    </div>
  );
}

function labelDecision(d: Decision): string {
  switch (d) {
    case 'ACCEPT': return 'Accept';
    case 'REQUEST_REVISION': return 'Revision';
    case 'ESCALATE': return 'Escalate';
    case 'NO_ACTION_YET': return 'No action';
  }
}

function DecisionPill({ decision }: { decision: string | null }) {
  if (!decision) return <span className="text-xs text-slate-400">—</span>;
  const cls =
    decision === 'ACCEPT' ? 'bg-green-100 text-green-800' :
    decision === 'REQUEST_REVISION' ? 'bg-amber-100 text-amber-800' :
    decision === 'ESCALATE' ? 'bg-red-100 text-red-800' :
    'bg-slate-100 text-slate-700';
  return (
    <span className={`inline-block rounded px-1.5 py-0.5 text-[11px] font-semibold ${cls}`}>
      {labelDecision(decision as Decision)}
    </span>
  );
}

function DetailDrawer({ submissionId, onClose }: { submissionId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<HistoryDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<HistoryDetail>(
          `/api/v1/trainer/feedback-history/${submissionId}`,
        );
        setDetail(res.data);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
      }
    })();
  }, [submissionId]);

  return (
    <div className="fixed inset-0 z-50 flex items-stretch justify-end bg-black/30">
      <div className="flex w-full max-w-xl flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              {detail ? detail.projectTitle : 'Loading…'}
            </h3>
            {detail && (
              <p className="text-xs text-slate-500">
                {detail.internName} · v{detail.version} · submitted {new Date(detail.submittedAt).toLocaleString()}
              </p>
            )}
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 space-y-4 overflow-y-auto p-5 text-sm">
          {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
          {!detail && !err && <div className="h-32 animate-pulse rounded bg-slate-100" />}
          {detail && (
            <>
              <Section title="Decision">
                <div className="flex flex-wrap items-center gap-2">
                  <DecisionPill decision={detail.trainerDecision} />
                  {detail.completionStatus && (
                    <span className="text-xs text-slate-600">· {detail.completionStatus}</span>
                  )}
                  {detail.reviewedAt && (
                    <span className="text-xs text-slate-500">
                      · reviewed {new Date(detail.reviewedAt).toLocaleString()}
                    </span>
                  )}
                </div>
                <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-slate-700">
                  <span>Technical: <strong>{detail.technicalScore ?? '—'}</strong></span>
                  <span>Communication: <strong>{detail.communicationScore ?? '—'}</strong></span>
                </div>
              </Section>

              {detail.trainerFeedback && (
                <Section title="Code review notes">
                  <p className="whitespace-pre-wrap rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
                    {detail.trainerFeedback}
                  </p>
                </Section>
              )}

              {detail.blockersNote && (
                <Section title="Blockers">
                  <p className="whitespace-pre-wrap rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
                    {detail.blockersNote}
                  </p>
                </Section>
              )}

              {(detail.nextAction || detail.nextActionDueDate) && (
                <Section title="Next action">
                  <p className="text-xs text-slate-700">
                    {detail.nextAction ?? '—'}
                    {detail.nextActionDueDate && (
                      <span className="ml-2 text-slate-500">due {detail.nextActionDueDate}</span>
                    )}
                  </p>
                </Section>
              )}

              {detail.reviewedLinksCsv && (
                <Section title="Reviewed links">
                  <pre className="whitespace-pre-wrap rounded border border-slate-200 bg-white p-2 font-mono text-[11px] text-slate-700">
                    {detail.reviewedLinksCsv}
                  </pre>
                </Section>
              )}

              {detail.description && (
                <Section title="Intern's submission">
                  <p className="whitespace-pre-wrap rounded-md border border-slate-200 bg-white p-3 text-xs text-slate-700">
                    {detail.description}
                  </p>
                </Section>
              )}

              {detail.linksJson && (
                <Section title="Submission links">
                  <pre className="whitespace-pre-wrap rounded border border-slate-200 bg-white p-2 font-mono text-[11px] text-slate-700">
                    {detail.linksJson}
                  </pre>
                </Section>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function TimelineDrawer({ internLifecycleId, onClose }: {
  internLifecycleId: string; onClose: () => void;
}) {
  const [data, setData] = useState<InternTimeline | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<InternTimeline>(
          `/api/v1/trainer/feedback-history/intern/${internLifecycleId}`,
        );
        setData(res.data);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
      }
    })();
  }, [internLifecycleId]);

  const acceptanceRate = useMemo(() => {
    if (!data || data.totalSubmissions === 0) return null;
    return Math.round((data.accepted / data.totalSubmissions) * 100);
  }, [data]);

  return (
    <div className="fixed inset-0 z-50 flex items-stretch justify-end bg-black/30">
      <div className="flex w-full max-w-2xl flex-col bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              {data ? `${data.internName ?? 'Intern'} — feedback timeline` : 'Loading…'}
            </h3>
            <p className="text-xs text-slate-500">Read-only summary across all submissions.</p>
          </div>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 space-y-4 overflow-y-auto p-5 text-sm">
          {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
          {!data && !err && <div className="h-32 animate-pulse rounded bg-slate-100" />}
          {data && (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Stat label="Submissions" value={data.totalSubmissions} />
                <Stat label="Accepted" value={data.accepted} sub={acceptanceRate != null ? `${acceptanceRate}%` : undefined} />
                <Stat label="Revisions" value={data.revisionsRequested} />
                <Stat label="Escalations" value={data.escalations} tone={data.escalations > 0 ? 'rose' : 'slate'} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Stat label="Avg technical" value={data.averageTechnical != null ? data.averageTechnical.toFixed(2) : '—'} />
                <Stat label="Avg communication" value={data.averageCommunication != null ? data.averageCommunication.toFixed(2) : '—'} />
              </div>
              <div className="overflow-hidden rounded-lg border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  <thead className="bg-slate-50">
                    <tr className="text-left font-semibold uppercase tracking-wide text-slate-500">
                      <th className="px-3 py-2">When</th>
                      <th className="px-3 py-2">Project</th>
                      <th className="px-3 py-2">Decision</th>
                      <th className="px-3 py-2">Scores</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {data.items.map((r) => (
                      <tr key={r.submissionId}>
                        <td className="px-3 py-2 text-slate-700">
                          {r.reviewedAt ? new Date(r.reviewedAt).toLocaleDateString() : '—'}
                        </td>
                        <td className="px-3 py-2 text-slate-900">
                          {r.projectTitle}
                          {r.version > 1 && <span className="ml-1 text-[10px] text-amber-800">v{r.version}</span>}
                        </td>
                        <td className="px-3 py-2"><DecisionPill decision={r.trainerDecision} /></td>
                        <td className="px-3 py-2 text-slate-700">
                          {r.technicalScore ?? '—'} / {r.communicationScore ?? '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h4 className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500">{title}</h4>
      {children}
    </section>
  );
}

function Stat({ label, value, sub, tone = 'slate' }: {
  label: string; value: number | string; sub?: string; tone?: 'slate' | 'rose' | 'teal';
}) {
  const cls = tone === 'rose' ? 'text-red-700' : tone === 'teal' ? 'text-brand-700' : 'text-slate-900';
  return (
    <div className="rounded-md border border-slate-200 bg-white p-2">
      <p className="text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-0.5 text-lg font-semibold tabular-nums ${cls}`}>{value}</p>
      {sub && <p className="text-[10px] text-slate-500">{sub}</p>}
    </div>
  );
}
