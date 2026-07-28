'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, Video } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Read-only gallery of evaluator session recordings. Consumed by both
 * the ERM page (/careers/erm/evaluation-recordings) and the Manager page
 * (/careers/manager/evaluation-recordings) — same component, same data.
 *
 * <p>Rows are pre-sorted server-side ({@code monthYear DESC → internName
 * ASC → projectTitle ASC}). Grouping done client-side so a search /
 * filter refinement doesn't need a round-trip. Each item mints its own
 * short-lived presigned GET URL on Play — the URL is bound to the
 * inline {@code <video>} element and expires within 30 min.</p>
 */
export interface RecordingRow {
  evaluationId: string;
  recordingDocumentId: string;
  monthYear: string; // yyyy-MM
  periodStart: string | null;
  periodEnd: string | null;
  evaluationType: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  projectId: string | null;
  projectTitle: string | null;
  evaluatorId: string;
  evaluatorName: string | null;
  fileName: string | null;
  mimeType: string | null;
  fileSizeBytes: number | null;
  uploadedAt: string | null;
}

export default function EvaluationRecordingsGallery() {
  const [rows, setRows] = useState<RecordingRow[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    api.get<{ items: RecordingRow[] }>('/api/v1/evaluation-recordings')
      .then((res) => { if (!cancelled) setRows(res.data.items ?? []); })
      .catch((e) => {
        if (cancelled) return;
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
        setRows([]);
      });
    return () => { cancelled = true; };
  }, []);

  const filtered = useMemo(() => {
    if (!rows) return [];
    const q = filter.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) => {
      return (r.internName ?? '').toLowerCase().includes(q)
        || (r.projectTitle ?? '').toLowerCase().includes(q)
        || (r.evaluatorName ?? '').toLowerCase().includes(q)
        || r.monthYear.includes(q);
    });
  }, [rows, filter]);

  const grouped = useMemo(() => groupByMonthInternProject(filtered), [filtered]);

  if (rows === null) {
    return <div className="h-32 animate-pulse rounded-lg bg-slate-100" aria-hidden />;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <input
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter by intern, project, evaluator, or month (yyyy-MM)"
          className="w-full max-w-md rounded-md border border-slate-200 px-3 py-2 text-sm"
        />
        <p className="text-xs text-slate-500">
          {filtered.length} recording{filtered.length === 1 ? '' : 's'}
        </p>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {err}
        </p>
      )}

      {grouped.length === 0 && !err && (
        <div className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          <Video className="mx-auto mb-2 h-6 w-6 text-slate-300" />
          {filter
            ? 'No recordings match your filter.'
            : 'No evaluation recordings yet.'}
        </div>
      )}

      {grouped.map(({ monthYear, monthLabel, interns }) => (
        <section
          key={monthYear}
          className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
        >
          <h2 className="text-sm font-semibold text-slate-900">{monthLabel}</h2>
          <div className="mt-3 space-y-4">
            {interns.map(({ internUserId, internName, projects }) => (
              <div key={`${monthYear}-${internUserId}`}>
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                  {internName ?? '(unnamed intern)'}
                </p>
                <div className="mt-2 space-y-3">
                  {projects.map(({ projectId, projectTitle, recordings }) => (
                    <div
                      key={`${monthYear}-${internUserId}-${projectId ?? 'general'}`}
                      className="rounded-md border border-slate-200 bg-slate-50 p-3"
                    >
                      <p className="text-xs font-medium text-slate-700">
                        {projectTitle ?? 'General / no specific project'}
                      </p>
                      <ul className="mt-2 space-y-3">
                        {recordings.map((r) => (
                          <li
                            key={r.evaluationId}
                            className="rounded-md border border-slate-200 bg-white p-3"
                          >
                            <RecordingItem row={r} />
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function RecordingItem({ row }: { row: RecordingRow }) {
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [url, setUrl] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const play = useCallback(async () => {
    setState('loading');
    setErr(null);
    try {
      const res = await api.get<{ downloadUrl: string; expiresAt: string }>(
        `/api/v1/evaluation-recordings/${row.evaluationId}/download-url`,
      );
      setUrl(res.data.downloadUrl);
      setState('ready');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load playback URL');
      setState('error');
    }
  }, [row.evaluationId]);

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold text-slate-800">
            {row.evaluationType.replaceAll('_', ' ')} · v{/* version not exposed here */}
            {row.periodStart ? ` · ${row.periodStart} → ${row.periodEnd ?? '—'}` : ''}
          </p>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Evaluator: {row.evaluatorName ?? '—'}
            {row.uploadedAt
              ? ` · uploaded ${new Date(row.uploadedAt).toLocaleString()}`
              : ''}
          </p>
          {row.fileName && (
            <p className="mt-0.5 truncate font-mono text-[11px] text-slate-500">
              {row.fileName}
              {row.fileSizeBytes ? ` · ${formatBytes(row.fileSizeBytes)}` : ''}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {state !== 'ready' && (
            <button
              type="button"
              onClick={play}
              disabled={state === 'loading'}
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              <Video className="h-3 w-3" />
              {state === 'loading' ? 'Loading…' : 'Play'}
            </button>
          )}
          {state === 'ready' && url && (
            <a
              href={url}
              download={row.fileName ?? undefined}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              <Download className="h-3 w-3" /> Download
            </a>
          )}
        </div>
      </div>
      {state === 'ready' && url && (
        <video
          controls
          src={url}
          className="mt-2 w-full max-h-96 rounded-md bg-black"
        />
      )}
      {err && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
    </div>
  );
}

interface ProjectGroup {
  projectId: string | null;
  projectTitle: string | null;
  recordings: RecordingRow[];
}
interface InternGroup {
  internUserId: string;
  internName: string | null;
  projects: ProjectGroup[];
}
interface MonthGroup {
  monthYear: string;
  monthLabel: string;
  interns: InternGroup[];
}

function groupByMonthInternProject(rows: RecordingRow[]): MonthGroup[] {
  const byMonth = new Map<string, Map<string, Map<string, RecordingRow[]>>>();
  const internNames = new Map<string, string | null>();
  const projectTitles = new Map<string, string | null>();

  for (const r of rows) {
    const monthKey = r.monthYear || 'unknown';
    const internKey = r.internUserId;
    const projectKey = r.projectId ?? '__general__';
    internNames.set(internKey, r.internName);
    projectTitles.set(projectKey, r.projectTitle);
    if (!byMonth.has(monthKey)) byMonth.set(monthKey, new Map());
    const interns = byMonth.get(monthKey)!;
    if (!interns.has(internKey)) interns.set(internKey, new Map());
    const projects = interns.get(internKey)!;
    if (!projects.has(projectKey)) projects.set(projectKey, []);
    projects.get(projectKey)!.push(r);
  }

  const out: MonthGroup[] = [];
  for (const [monthKey, interns] of byMonth) {
    const internList: InternGroup[] = [];
    for (const [internKey, projects] of interns) {
      const projectList: ProjectGroup[] = [];
      for (const [projectKey, recordings] of projects) {
        projectList.push({
          projectId: projectKey === '__general__' ? null : projectKey,
          projectTitle: projectKey === '__general__'
            ? null
            : (projectTitles.get(projectKey) ?? null),
          recordings,
        });
      }
      internList.push({
        internUserId: internKey,
        internName: internNames.get(internKey) ?? null,
        projects: projectList,
      });
    }
    out.push({
      monthYear: monthKey,
      monthLabel: formatMonthLabel(monthKey),
      interns: internList,
    });
  }
  return out;
}

function formatMonthLabel(monthYear: string): string {
  const m = monthYear.match(/^(\d{4})-(\d{2})$/);
  if (!m) return monthYear;
  const y = Number(m[1]);
  const mm = Number(m[2]);
  const date = new Date(Date.UTC(y, mm - 1, 1));
  return date.toLocaleString(undefined, {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}
