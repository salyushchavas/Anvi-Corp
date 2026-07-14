'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowRight, MessageSquare, Video } from 'lucide-react';
import api from '@/lib/api';
import type {
  AssignmentSummary,
  QaSessionSummary,
} from '@/app/careers/(dashboard)/intern/projects/types';

/**
 * Surfaces the intern's NEXT upcoming Q&A (viva) session — the soonest
 * SCHEDULED session across all their projects. Hidden when no scheduled
 * Q&A exists. Fetches once on mount via /api/v1/project-assignments/mine
 * (same payload the project pages already consume; no extra endpoint).
 *
 * Conducted sessions are filtered out (those are awaiting sign-off, not
 * a join-now signal). The earliest scheduledAt wins so the card always
 * highlights the next session the intern needs to attend.
 */
export default function UpcomingQaCard() {
  const [upcoming, setUpcoming] = useState<UpcomingQa | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.get<AssignmentSummary[]>('/api/v1/project-assignments/mine')
      .then((res) => {
        if (cancelled) return;
        setUpcoming(pickNextQa(res.data ?? []));
        setLoaded(true);
      })
      .catch(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => { cancelled = true; };
  }, []);

  if (!loaded || !upcoming) return null;
  const { qa, assignmentId, projectName } = upcoming;
  const hasJoin = !!qa.zoomJoinUrl || !!qa.meetingLink;
  const whenLabel = qa.scheduledAt
    ? new Date(qa.scheduledAt).toLocaleString([], {
        weekday: 'short', month: 'short', day: 'numeric',
        hour: 'numeric', minute: '2-digit',
      })
    : 'Scheduled';

  return (
    <section className="rounded-lg border border-brand-200 bg-brand-50/60 p-6 shadow-ds-sm">
      <div className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-brand-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-brand-800">
        <MessageSquare className="h-3 w-3" strokeWidth={2.5} />
        Upcoming Q&amp;A session
      </div>
      <h2 className="text-xl font-semibold text-slate-900">
        {projectName ?? 'Project Q&A'}
      </h2>
      <p className="mt-1 text-sm text-slate-700">
        {qa.scheduledByName
          ? `${qa.scheduledByName}, your Evaluator,`
          : 'Your Evaluator'} scheduled your Q&amp;A (viva) session.
        This is the final step before completion.
      </p>
      <dl className="mt-3 grid gap-2 text-xs text-slate-700 sm:grid-cols-2">
        <div>
          <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">When</dt>
          <dd className="mt-0.5 text-sm text-slate-900">
            {whenLabel}
            {qa.durationMinutes ? ` · ${qa.durationMinutes} min` : ''}
            {qa.timezone ? ` · ${qa.timezone}` : ''}
          </dd>
        </div>
        <div>
          <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Status</dt>
          <dd className="mt-0.5 text-sm text-slate-900">
            {qa.status === 'CONDUCTED' ? 'Conducted — awaiting sign-off' : 'Scheduled'}
          </dd>
        </div>
      </dl>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        {hasJoin && qa.status !== 'CONDUCTED' && (
          <a
            href={(qa.zoomJoinUrl ?? qa.meetingLink) ?? '#'}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800"
          >
            <Video className="h-4 w-4" />
            Join Meeting
          </a>
        )}
        <Link
          href={`/careers/intern/projects/${assignmentId}`}
          className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-3 py-1.5 text-xs font-semibold text-brand-800 hover:bg-brand-100"
        >
          Open project
          <ArrowRight className="h-3.5 w-3.5" />
        </Link>
      </div>
    </section>
  );
}

interface UpcomingQa {
  assignmentId: string;
  projectName: string | null;
  qa: QaSessionSummary;
}

function pickNextQa(rows: AssignmentSummary[]): UpcomingQa | null {
  let best: UpcomingQa | null = null;
  let bestTime = Number.POSITIVE_INFINITY;
  for (const r of rows) {
    const qa = r.qaSession;
    if (!qa) continue;
    // Only surface SCHEDULED + CONDUCTED — both leave the intern with
    // something on the card. COMPLETED / RETURNED are filtered out at
    // the API level (the backend only returns active sessions).
    if (qa.status !== 'SCHEDULED' && qa.status !== 'CONDUCTED') continue;
    const t = qa.scheduledAt ? new Date(qa.scheduledAt).getTime() : NaN;
    if (!Number.isFinite(t)) continue;
    if (t < bestTime) {
      bestTime = t;
      best = {
        assignmentId: r.id,
        projectName: r.project?.name ?? null,
        qa,
      };
    }
  }
  return best;
}
