'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Clock, ExternalLink, FolderGit2, Send } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import type { AssignmentSummary, ProjectAssignmentStatus } from './types';

const STATUS_TONE: Record<ProjectAssignmentStatus, string> = {
  ASSIGNED:       'bg-slate-100 text-slate-700',
  IN_PROGRESS:    'bg-amber-100 text-amber-800',
  SUBMITTED:      'bg-slate-100 text-slate-700',
  RETURNED:       'bg-red-100 text-red-800',
  TECH_APPROVED:  'bg-green-100 text-green-800',
  PENDING_VIVA:   'bg-slate-100 text-slate-700',
  COMPLETED:      'bg-green-100 text-green-800',
};

const STATUS_LABEL: Record<ProjectAssignmentStatus, string> = {
  ASSIGNED:       'Assigned',
  IN_PROGRESS:    'In progress',
  SUBMITTED:      'Submitted — awaiting review',
  RETURNED:       'Returned for revisions',
  TECH_APPROVED:  'Tech approved',
  PENDING_VIVA:   'Pending viva',
  COMPLETED:      'Completed',
};

export default function InternProjectsPage() {
  const [items, setItems] = useState<AssignmentSummary[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<AssignmentSummary[]>('/api/v1/project-assignments/mine');
      setItems(res.data ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load your projects');
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (err) {
    return (
      <InternPageShell title="My Projects">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err}
        </p>
      </InternPageShell>
    );
  }
  if (items === null) {
    return (
      <InternPageShell title="My Projects">
        <div className="space-y-3" aria-hidden>
          <div className="h-24 animate-pulse rounded-lg bg-slate-100" />
          <div className="h-24 animate-pulse rounded-lg bg-slate-100" />
        </div>
      </InternPageShell>
    );
  }
  if (items.length === 0) {
    return (
      <InternPageShell title="My Projects">
        <section className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
          <FolderGit2 className="mx-auto h-8 w-8 text-slate-300" aria-hidden />
          <h2 className="mt-3 text-base font-semibold text-slate-900">
            No projects assigned yet
          </h2>
          <p className="mt-1 text-sm text-slate-600">
            Your trainer will assign your first project once you&apos;re onboarded.
          </p>
        </section>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell
      title="My Projects"
      subtitle="Your assigned projects, status, and submissions"
    >
      <ul className="space-y-3">
        {items.map((a) => (
          <li key={a.id}>
            <AssignmentRow a={a} />
          </li>
        ))}
      </ul>
    </InternPageShell>
  );
}

function AssignmentRow({ a }: { a: AssignmentSummary }) {
  const projectName = a.project?.name ?? 'Project';
  const dueLine = a.dueDate ? formatDate(a.dueDate) : null;
  const submittedLine = a.submittedAt ? formatInstant(a.submittedAt) : null;
  const revisionRequested =
    a.latestSubmission?.trainerDecision === 'REQUEST_REVISION';

  return (
    <Link
      href={`/careers/intern/projects/${a.id}`}
      className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition-colors hover:border-brand-300 hover:bg-brand-50/30"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-base font-semibold text-slate-900">
            {projectName}
          </h3>
          {a.project?.techStack && (
            <p className="mt-0.5 text-xs text-slate-500">
              {a.project.techStack}
            </p>
          )}
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-600">
            <span className={'rounded-full px-2 py-0.5 font-medium ' + STATUS_TONE[a.status]}>
              {STATUS_LABEL[a.status]}
            </span>
            {revisionRequested && a.status !== 'RETURNED' && (
              <span className="rounded-full bg-red-100 px-2 py-0.5 font-medium text-red-800">
                Trainer requested changes
              </span>
            )}
            {dueLine && (
              <span className="inline-flex items-center gap-1">
                <Clock className="h-3 w-3" /> Due {dueLine}
              </span>
            )}
            {submittedLine && (
              <span className="inline-flex items-center gap-1">
                <Send className="h-3 w-3" /> Submitted {submittedLine}
              </span>
            )}
            {a.latestSubmission && (
              <span className="text-slate-500">
                v{a.latestSubmission.version}
              </span>
            )}
          </div>
        </div>
        <ExternalLink className="h-4 w-4 text-slate-400" />
      </div>
    </Link>
  );
}

function formatDate(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch {
    return iso;
  }
}

function formatInstant(iso: string): string {
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
