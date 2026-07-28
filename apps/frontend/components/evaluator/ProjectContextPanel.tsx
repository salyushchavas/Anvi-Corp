'use client';

import { useEffect, useState } from 'react';
import { Briefcase } from 'lucide-react';
import api from '@/lib/careers/api';
import type { ProjectContext } from './perproject-types';

/**
 * Read-only project panel for the evaluator's compose hub. Loads the
 * linked project's context (title, deliverables, requirements, tech
 * stack, dates, trainer's review notes) so the evaluator has full
 * background without leaving the evaluation.
 *
 * <p>Renders nothing when {@code projectId} is null (MONTHLY
 * evaluations that never linked to a project).</p>
 */
interface Props {
  projectId: string | null | undefined;
}

export default function ProjectContextPanel({ projectId }: Props) {
  const [data, setData] = useState<ProjectContext | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!projectId) { setData(null); return; }
    let cancelled = false;
    setLoading(true);
    setErr(null);
    api.get<ProjectContext>(`/api/v1/evaluator/projects/${projectId}`)
      .then((res) => { if (!cancelled) setData(res.data); })
      .catch((e) => {
        if (cancelled) return;
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load project');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  if (!projectId) return null;
  if (loading && !data) {
    return <div className="h-32 animate-pulse rounded-lg bg-slate-100" aria-hidden />;
  }
  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>
    );
  }
  if (!data) return null;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-start gap-2">
        <Briefcase className="mt-0.5 h-4 w-4 text-brand-700" />
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold text-slate-900">{data.title ?? '(untitled project)'}</h2>
          <p className="mt-0.5 text-[11px] text-slate-500">
            {data.techStack && <>{data.techStack} · </>}
            {data.status && <>{data.status.replaceAll('_', ' ')}</>}
            {data.projectNumber != null && <> · Project #{data.projectNumber}</>}
            {data.monthYear && <> · {data.monthYear}</>}
          </p>
        </div>
      </div>

      <dl className="mt-3 grid grid-cols-2 gap-2 text-[11px] sm:grid-cols-4">
        {data.startDate && <FactCell label="Started" value={new Date(data.startDate).toLocaleDateString()} />}
        {data.dueDate && <FactCell label="Due" value={new Date(data.dueDate).toLocaleDateString()} />}
        {data.submittedAt && <FactCell label="Submitted" value={new Date(data.submittedAt).toLocaleDateString()} />}
        {data.completedAt && <FactCell label="Completed" value={new Date(data.completedAt).toLocaleDateString()} />}
      </dl>

      {data.description && (
        <Section title="Description" body={data.description} />
      )}
      {data.deliverables && (
        <Section title="Deliverables" body={data.deliverables} />
      )}
      {data.requirements && (
        <Section title="Requirements" body={data.requirements} />
      )}
      {data.objectives && (
        <Section title="Objectives" body={data.objectives} />
      )}
      {data.reviewNotes && (
        <Section
          title={`Trainer review notes${data.reviewerName ? ` — ${data.reviewerName}` : ''}`}
          body={data.reviewNotes}
        />
      )}
    </section>
  );
}

function Section({ title, body }: { title: string; body: string }) {
  return (
    <details className="mt-2 rounded-md border border-slate-200 bg-slate-50 p-2 text-xs" open>
      <summary className="cursor-pointer text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </summary>
      <p className="mt-1 whitespace-pre-wrap text-slate-700">{body}</p>
    </details>
  );
}

function FactCell({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-100 bg-slate-50 p-2">
      <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-0.5 text-xs text-slate-800">{value}</dd>
    </div>
  );
}
