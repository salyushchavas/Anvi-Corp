'use client';

import { useEffect, useState } from 'react';
import { HelpCircle } from 'lucide-react';
import api from '@/lib/careers/api';
import type { ReferenceQaPair } from './ReferenceQaEditor';

/**
 * Read-only viewer for a project's Reference Q&A pairs. Consumed by the
 * evaluator's evaluation compose screen; also usable by anyone with
 * read RBAC on the endpoint (TRAINER / EVALUATOR / SUPER_ADMIN).
 *
 * <p>Neutral copy — "Reference Q&A" as the section heading and a short
 * neutral subtitle. Nothing implies the evaluator lacks knowledge; this
 * is trainer-provided reference material for the evaluation.</p>
 *
 * <p>Empty state: renders nothing so the compose page doesn't get a
 * dead panel when the trainer hasn't attached any pairs. Optional
 * {@code alwaysShow} prop keeps the panel visible with an empty-state
 * message when the caller wants to advertise the absence.</p>
 */
interface Props {
  projectId: string | null | undefined;
  alwaysShow?: boolean;
}

export default function ReferenceQaPanel({ projectId, alwaysShow }: Props) {
  const [pairs, setPairs] = useState<ReferenceQaPair[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!projectId) { setPairs([]); return; }
    let cancelled = false;
    setLoading(true);
    setErr(null);
    api.get<{ pairs: ReferenceQaPair[] }>(
      `/api/v1/projects/${projectId}/reference-qa`,
    )
      .then((res) => { if (!cancelled) setPairs(res.data.pairs ?? []); })
      .catch((e) => {
        if (cancelled) return;
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load Q&A');
        setPairs([]);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  if (!projectId) return null;
  if (loading) {
    return <div className="h-24 animate-pulse rounded-md bg-slate-100" aria-hidden />;
  }
  if (err) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
        {err}
      </p>
    );
  }
  const list = pairs ?? [];
  if (list.length === 0 && !alwaysShow) return null;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center gap-2">
        <HelpCircle className="h-4 w-4 text-brand-700" />
        <h2 className="text-sm font-semibold text-slate-900">
          Trainer Reference Q&amp;A
        </h2>
      </div>
      <p className="mt-1 text-xs text-slate-500">
        Discussion points the trainer attached for this project.
      </p>
      {list.length === 0 ? (
        <p className="mt-3 rounded-md border border-dashed border-slate-300 p-3 text-center text-xs text-slate-500">
          No reference Q&amp;A attached to this project.
        </p>
      ) : (
        <ol className="mt-3 space-y-3">
          {list.map((p, idx) => (
            <li key={idx} className="rounded-md border border-slate-200 bg-slate-50 p-3">
              <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                Q{idx + 1}
              </p>
              <p className="mt-0.5 whitespace-pre-wrap text-sm font-medium text-slate-900">
                {p.question}
              </p>
              <p className="mt-2 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                Answer
              </p>
              <p className="mt-0.5 whitespace-pre-wrap text-sm text-slate-700">
                {p.answer}
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
