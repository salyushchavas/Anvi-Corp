'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { ChevronLeft, Download } from 'lucide-react';
import api from '@/lib/api';

interface ReportKpis {
  totalEvaluations: number;
  publishedCount: number;
  acknowledgedCount: number;
  pendingAckCount: number;
  amendedCount: number;
  averageOverallScore: number | null;
  averageDaysToAck: number | null;
}

interface RecommendationBucket {
  recommendation: string;
  count: number;
  pct: number;
}

interface CriterionAverages {
  technical: number | null;
  communication: number | null;
  professionalism: number | null;
  learningApplication: number | null;
}

interface InternRollup {
  internLifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  evaluationsThisPeriod: number;
  averageOverallScore: number | null;
  lastPublishedAt: string | null;
}

interface MonthlyReport {
  year: number;
  month: number;
  monthLabel: string;
  kpis: ReportKpis;
  recommendationMix: RecommendationBucket[];
  criterionAverages: CriterionAverages;
  perInternRollup: InternRollup[];
}

export default function EvaluatorReportsPage() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [data, setData] = useState<MonthlyReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<MonthlyReport>(
        `/api/v1/evaluator/reports/monthly?year=${year}&month=${month}`,
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load report');
    } finally {
      setLoading(false);
    }
  }, [year, month]);

  useEffect(() => { void load(); }, [load]);

  const monthOptions = useMemo(
    () => Array.from({ length: 12 }, (_, i) => ({
      value: i + 1,
      label: new Date(2000, i, 1).toLocaleString('default', { month: 'long' }),
    })),
    [],
  );

  const csvHref = `/api/v1/evaluator/reports/monthly.csv?year=${year}&month=${month}`;

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>

      <header className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Monthly Reports</h1>
          <p className="text-xs text-slate-500">
            KPIs, recommendation distribution, and per-intern roll-up for the
            selected month.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={month}
            onChange={(e) => setMonth(parseInt(e.target.value, 10))}
            className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm"
          >
            {monthOptions.map((m) => (
              <option key={m.value} value={m.value}>{m.label}</option>
            ))}
          </select>
          <select
            value={year}
            onChange={(e) => setYear(parseInt(e.target.value, 10))}
            className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm"
          >
            {[now.getFullYear(), now.getFullYear() - 1, now.getFullYear() - 2].map((y) => (
              <option key={y} value={y}>{y}</option>
            ))}
          </select>
          <a
            href={csvHref}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            <Download className="h-3.5 w-3.5" />
            Download CSV
          </a>
        </div>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      )}

      {data && (
        <>
          <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <KpiCard label="Total evaluations" value={data.kpis.totalEvaluations} />
            <KpiCard label="Published" value={data.kpis.publishedCount} />
            <KpiCard label="Acknowledged" value={data.kpis.acknowledgedCount} />
            <KpiCard
              label="Pending ack"
              value={data.kpis.pendingAckCount}
              tone={data.kpis.pendingAckCount > 0 ? 'amber' : 'emerald'}
            />
            <KpiCard label="Amended" value={data.kpis.amendedCount} />
            <KpiCard
              label="Avg overall score"
              value={data.kpis.averageOverallScore != null
                ? `${data.kpis.averageOverallScore.toFixed(2)} / 5`
                : '—'}
            />
            <KpiCard
              label="Avg days to ack"
              value={data.kpis.averageDaysToAck != null
                ? `${data.kpis.averageDaysToAck.toFixed(1)} d`
                : '—'}
            />
            <KpiCard label="Period" value={data.monthLabel} />
          </section>

          <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Panel title="Recommendation distribution">
              {data.recommendationMix.length === 0 ? (
                <p className="text-sm text-slate-500">
                  No evaluations were published this month.
                </p>
              ) : (
                <ul className="space-y-2">
                  {data.recommendationMix.map((b) => (
                    <li key={b.recommendation}>
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-medium text-slate-700">
                          {b.recommendation.replaceAll('_', ' ')}
                        </span>
                        <span className="tabular-nums text-slate-600">
                          {b.count} · {b.pct.toFixed(1)}%
                        </span>
                      </div>
                      <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-slate-100">
                        <div
                          className="h-full bg-brand-600"
                          style={{ width: `${Math.min(100, b.pct)}%` }}
                        />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>

            <Panel title="Criterion averages">
              <ul className="space-y-3 text-sm">
                <CriterionRow label="Technical skills" value={data.criterionAverages.technical} />
                <CriterionRow label="Communication" value={data.criterionAverages.communication} />
                <CriterionRow label="Professionalism" value={data.criterionAverages.professionalism} />
                <CriterionRow label="Learning application" value={data.criterionAverages.learningApplication} />
              </ul>
            </Panel>
          </section>

          <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
            <div className="border-b border-slate-200 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-900">Per-intern roll-up</h2>
              <p className="text-[11px] text-slate-500">
                One row per intern with at least one published evaluation in {data.monthLabel}.
              </p>
            </div>
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-600">
                <tr>
                  <th className="px-3 py-2 text-left">Intern</th>
                  <th className="px-3 py-2 text-left">Employee ID</th>
                  <th className="px-3 py-2 text-right">Evaluations</th>
                  <th className="px-3 py-2 text-right">Avg score</th>
                  <th className="px-3 py-2 text-left">Last published</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.perInternRollup.length === 0 ? (
                  <tr><td colSpan={6} className="p-6 text-center text-slate-500">
                    No interns to roll up for this month.
                  </td></tr>
                ) : data.perInternRollup.map((r) => (
                  <tr key={r.internLifecycleId} className="hover:bg-slate-50">
                    <td className="px-3 py-2 font-medium text-slate-900">
                      {r.internName ?? '—'}
                    </td>
                    <td className="px-3 py-2 text-[11px] text-slate-700">
                      {r.employeeId ?? '—'}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {r.evaluationsThisPeriod}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {r.averageOverallScore != null
                        ? r.averageOverallScore.toFixed(2) : '—'}
                    </td>
                    <td className="px-3 py-2 text-[11px] text-slate-700">
                      {r.lastPublishedAt
                        ? new Date(r.lastPublishedAt).toLocaleDateString()
                        : '—'}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <Link
                        href={`/careers/evaluator/evaluees/${r.internLifecycleId}`}
                        className="text-[11px] font-medium text-brand-700 hover:underline"
                      >
                        Open →
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}
    </div>
  );
}

function KpiCard({
  label, value, tone = 'slate',
}: {
  label: string;
  value: number | string;
  tone?: 'slate' | 'amber' | 'emerald';
}) {
  const cls = tone === 'amber'
    ? 'text-amber-700'
    : tone === 'emerald'
      ? 'text-green-700'
      : 'text-slate-900';
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </p>
      <p className={`mt-1 text-lg font-semibold tabular-nums ${cls}`}>{value}</p>
    </div>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      <div className="mt-3">{children}</div>
    </div>
  );
}

function CriterionRow({ label, value }: { label: string; value: number | null }) {
  const pct = value != null ? (value / 5) * 100 : 0;
  return (
    <li>
      <div className="flex items-center justify-between text-xs">
        <span className="font-medium text-slate-700">{label}</span>
        <span className="tabular-nums text-slate-600">
          {value != null ? `${value.toFixed(2)} / 5` : '—'}
        </span>
      </div>
      <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-slate-100">
        <div
          className="h-full bg-brand-600"
          style={{ width: `${Math.min(100, pct)}%` }}
        />
      </div>
    </li>
  );
}
