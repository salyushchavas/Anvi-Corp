'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  ChevronRight,
  Download,
  Folder,
  Home,
  PlayCircle,
  Video,
} from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Unified Recording Gallery — file-explorer navigation across:
 *   Intern → Type (Interview | Monthly evaluations) → [Month → Project]
 *   → video files.
 *
 * <p>Role-based visibility is enforced server-side by
 * {@code /api/v1/recording-gallery}; the component just renders
 * whatever the API returns. Interview recordings hidden for the
 * evaluator role are absent from the response entirely.</p>
 *
 * <p>Playback is lazy — each file card fetches its own presigned
 * URL via the {@code downloadUrlPath} the server hands back, so
 * clicking Play never leaks a live URL until the user asks for it.</p>
 */
export interface RecordingFile {
  evaluationId: string | null;
  documentId: string;
  fileName: string;
  mimeType: string | null;
  fileSizeBytes: number | null;
  uploadedAt: string;
  uploadedByName: string | null;
  approvalStatus: string | null;
  scope: string | null;
  downloadUrlPath: string;
}
interface ProjectFolder {
  scope: string;
  projectId: string | null;
  projectTitle: string | null;
  /**
   * Server-computed human-friendly folder name — used in both the
   * tile and the breadcrumb leaf. Never a raw id; never blank.
   * Examples: "P1 · Real Project Name", "P1 + P2", "P1".
   */
  folderLabel: string;
  files: RecordingFile[];
}
interface MonthFolder {
  /** "YYYY-MM" grouping key (also used as the React list key). */
  monthYear: string;
  /**
   * Server-computed human-friendly month label — used in both the
   * tile and the breadcrumb. Never the literal "unknown"; if
   * period_start is missing, the server falls back to the session
   * date or the recording's upload month.
   */
  monthLabel: string;
  projects: ProjectFolder[];
}
interface InternFolder {
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  interviewRecordings: RecordingFile[];
  evaluationMonths: MonthFolder[];
  totalRecordings: number;
}
interface GalleryResponse {
  interns: InternFolder[];
}

type Level =
  | { kind: 'root' }
  | { kind: 'intern'; internUserId: string }
  | { kind: 'type'; internUserId: string; type: 'interview' | 'evaluation' }
  | { kind: 'month'; internUserId: string; monthYear: string }
  | { kind: 'project'; internUserId: string; monthYear: string; projectKey: string };

interface Props {
  /** When true, hide the "Interview" folder — the evaluator role
   *  should never see interview recordings even if the server slips
   *  one through. Server-side gate is still authoritative; this is
   *  belt-and-suspenders. */
  hideInterviews?: boolean;
  /** Header title — role-specific ("All Recordings" for ERM/Mgr,
   *  "My Approved Recordings" for Evaluator). */
  title: string;
  subtitle: string;
}

export default function RecordingGallery({ hideInterviews = false, title, subtitle }: Props) {
  const [data, setData] = useState<GalleryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [level, setLevel] = useState<Level>({ kind: 'root' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<GalleryResponse>('/api/v1/recording-gallery');
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const intern = level.kind !== 'root'
    ? data?.interns.find((i) => i.internUserId === level.internUserId) ?? null
    : null;
  const month = intern && (level.kind === 'month' || level.kind === 'project')
    ? intern.evaluationMonths.find((m) => m.monthYear === level.monthYear) ?? null
    : null;
  const project = month && level.kind === 'project'
    ? month.projects.find((p) => `${p.scope}|${p.projectId ?? ''}` === level.projectKey) ?? null
    : null;

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
        <p className="text-xs text-slate-500">{subtitle}</p>
      </header>

      <Breadcrumb
        level={level}
        intern={intern}
        month={month}
        project={project}
        onNavigate={setLevel}
      />

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {loading && !data && (
        <div className="h-48 animate-pulse rounded-lg border border-slate-200 bg-white" />
      )}

      {!loading && data && (
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          {level.kind === 'root' && (
            <InternGrid interns={data.interns} onOpen={(u) =>
              setLevel({ kind: 'intern', internUserId: u })} />
          )}
          {level.kind === 'intern' && intern && (
            <TypeGrid
              intern={intern}
              hideInterviews={hideInterviews}
              onOpen={(t) => setLevel({ kind: 'type', internUserId: intern.internUserId, type: t })}
            />
          )}
          {level.kind === 'type' && intern && level.type === 'interview' && (
            <FileGrid files={intern.interviewRecordings} />
          )}
          {level.kind === 'type' && intern && level.type === 'evaluation' && (
            <MonthGrid
              months={intern.evaluationMonths}
              onOpen={(m) => setLevel({
                kind: 'month', internUserId: intern.internUserId, monthYear: m,
              })}
            />
          )}
          {level.kind === 'month' && intern && month && (
            <ProjectGrid
              projects={month.projects}
              onOpen={(key) => setLevel({
                kind: 'project', internUserId: intern.internUserId,
                monthYear: month.monthYear, projectKey: key,
              })}
            />
          )}
          {level.kind === 'project' && project && (
            <FileGrid files={project.files} />
          )}
        </div>
      )}
    </div>
  );
}

function Breadcrumb({ level, intern, month, project, onNavigate }: {
  level: Level;
  intern: InternFolder | null;
  month: MonthFolder | null;
  project: ProjectFolder | null;
  onNavigate: (l: Level) => void;
}) {
  const crumbs: { label: string; go: Level }[] = [];
  crumbs.push({ label: 'All Interns', go: { kind: 'root' } });
  if (intern) {
    crumbs.push({
      label: intern.internName ?? '(unknown)',
      go: { kind: 'intern', internUserId: intern.internUserId },
    });
  }
  if (level.kind === 'type' || level.kind === 'month' || level.kind === 'project') {
    const typeLabel = level.kind === 'type' && level.type === 'interview'
      ? 'Interview'
      : 'Monthly Evaluations';
    crumbs.push({
      label: typeLabel,
      go: level.kind === 'type'
        ? level
        : { kind: 'type', internUserId: intern!.internUserId, type: 'evaluation' },
    });
  }
  if (month) {
    crumbs.push({
      label: month.monthLabel,
      go: { kind: 'month', internUserId: intern!.internUserId, monthYear: month.monthYear },
    });
  }
  if (project) {
    crumbs.push({ label: project.folderLabel, go: level });
  }
  return (
    <nav className="flex flex-wrap items-center gap-1 text-xs text-slate-600">
      {crumbs.map((c, i) => {
        const isLast = i === crumbs.length - 1;
        return (
          <span key={i} className="inline-flex items-center gap-1">
            {i === 0 && <Home className="h-3 w-3" />}
            <button
              type="button"
              disabled={isLast}
              onClick={() => onNavigate(c.go)}
              className={isLast
                ? 'font-semibold text-slate-900'
                : 'hover:text-brand-700 hover:underline'}
            >
              {c.label}
            </button>
            {!isLast && <ChevronRight className="h-3 w-3 text-slate-400" />}
          </span>
        );
      })}
    </nav>
  );
}

function InternGrid({ interns, onOpen }: {
  interns: InternFolder[];
  onOpen: (internUserId: string) => void;
}) {
  if (interns.length === 0) {
    return <EmptyState label="No recordings uploaded yet." />;
  }
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {interns.map((i) => (
        <button
          key={i.internUserId}
          type="button"
          onClick={() => onOpen(i.internUserId)}
          className="flex flex-col items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-3 text-left hover:border-brand-300 hover:bg-brand-50"
        >
          <Folder className="h-6 w-6 text-brand-700" />
          <p className="text-sm font-semibold text-slate-900">
            {i.internName ?? '(unknown)'}
          </p>
          <p className="text-[10px] text-slate-500">
            {i.employeeId ?? '—'} · {i.totalRecordings} file{i.totalRecordings === 1 ? '' : 's'}
          </p>
        </button>
      ))}
    </div>
  );
}

function TypeGrid({ intern, hideInterviews, onOpen }: {
  intern: InternFolder;
  hideInterviews: boolean;
  onOpen: (t: 'interview' | 'evaluation') => void;
}) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {!hideInterviews && (
        <button
          type="button"
          onClick={() => onOpen('interview')}
          disabled={intern.interviewRecordings.length === 0}
          className="flex flex-col items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-4 text-left hover:border-brand-300 hover:bg-brand-50 disabled:opacity-50"
        >
          <Folder className="h-6 w-6 text-amber-600" />
          <p className="text-sm font-semibold text-slate-900">Interview</p>
          <p className="text-[11px] text-slate-500">
            {intern.interviewRecordings.length} recording
            {intern.interviewRecordings.length === 1 ? '' : 's'}
          </p>
        </button>
      )}
      <button
        type="button"
        onClick={() => onOpen('evaluation')}
        disabled={intern.evaluationMonths.length === 0}
        className="flex flex-col items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-4 text-left hover:border-brand-300 hover:bg-brand-50 disabled:opacity-50"
      >
        <Folder className="h-6 w-6 text-brand-700" />
        <p className="text-sm font-semibold text-slate-900">Monthly Evaluations</p>
        <p className="text-[11px] text-slate-500">
          {intern.evaluationMonths.length} month
          {intern.evaluationMonths.length === 1 ? '' : 's'}
        </p>
      </button>
    </div>
  );
}

function MonthGrid({ months, onOpen }: {
  months: MonthFolder[];
  onOpen: (monthYear: string) => void;
}) {
  if (months.length === 0) return <EmptyState label="No evaluation recordings yet." />;
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {months.map((m) => (
        <button
          key={m.monthYear}
          type="button"
          onClick={() => onOpen(m.monthYear)}
          className="flex flex-col items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-3 text-left hover:border-brand-300 hover:bg-brand-50"
        >
          <Folder className="h-6 w-6 text-brand-700" />
          <p className="text-sm font-semibold text-slate-900">{m.monthLabel}</p>
          <p className="text-[10px] text-slate-500">
            {m.projects.length} project{m.projects.length === 1 ? '' : 's'}
          </p>
        </button>
      ))}
    </div>
  );
}

function ProjectGrid({ projects, onOpen }: {
  projects: ProjectFolder[];
  onOpen: (key: string) => void;
}) {
  if (projects.length === 0) return <EmptyState label="No projects with recordings this month." />;
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {projects.map((p) => {
        const key = `${p.scope}|${p.projectId ?? ''}`;
        return (
          <button
            key={key}
            type="button"
            onClick={() => onOpen(key)}
            className="flex flex-col items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-3 text-left hover:border-brand-300 hover:bg-brand-50"
          >
            <Folder className="h-6 w-6 text-brand-700" />
            <p className="text-sm font-semibold text-slate-900">
              {p.folderLabel}
            </p>
            <p className="text-[10px] text-slate-500">
              {p.files.length} file{p.files.length === 1 ? '' : 's'}
            </p>
          </button>
        );
      })}
    </div>
  );
}

function FileGrid({ files }: { files: RecordingFile[] }) {
  if (files.length === 0) return <EmptyState label="No recordings here." />;
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {files.map((f) => <FileCard key={f.documentId} file={f} />)}
    </div>
  );
}

function FileCard({ file }: { file: RecordingFile }) {
  const [playUrl, setPlayUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function fetchUrl(): Promise<string | null> {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<{ downloadUrl: string }>(file.downloadUrlPath);
      return res.data.downloadUrl;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load video');
      return null;
    } finally {
      setLoading(false);
    }
  }
  async function play() {
    const u = await fetchUrl();
    if (u) setPlayUrl(u);
  }
  async function download() {
    const u = await fetchUrl();
    if (!u) return;
    const a = document.createElement('a');
    a.href = u;
    a.download = file.fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
  return (
    <div className="space-y-2 rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-start gap-2">
        <Video className="mt-0.5 h-5 w-5 text-brand-700" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-slate-900" title={file.fileName}>
            {file.fileName}
          </p>
          <p className="text-[10px] text-slate-500">
            {new Date(file.uploadedAt).toLocaleString()}
            {file.uploadedByName && <> · {file.uploadedByName}</>}
          </p>
          {file.approvalStatus && (
            <span className={
              'mt-1 inline-block rounded px-1.5 py-0.5 text-[10px] font-semibold '
              + (file.approvalStatus === 'APPROVED'
                  ? 'bg-emerald-100 text-emerald-800'
                  : file.approvalStatus === 'REVISION_REQUESTED'
                    ? 'bg-red-100 text-red-700'
                    : 'bg-amber-100 text-amber-900')
            }>
              {file.approvalStatus.replaceAll('_', ' ')}
            </span>
          )}
        </div>
      </div>
      {playUrl ? (
        // eslint-disable-next-line jsx-a11y/media-has-caption
        <video controls src={playUrl} className="w-full rounded bg-black" />
      ) : (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => void play()}
            disabled={loading}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-1 text-[11px] font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            <PlayCircle className="h-3 w-3" />
            {loading ? 'Loading…' : 'Play'}
          </button>
          <button
            type="button"
            onClick={() => void download()}
            disabled={loading}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
          >
            <Download className="h-3 w-3" />
            Download
          </button>
        </div>
      )}
      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
          {err}
        </p>
      )}
    </div>
  );
}

function EmptyState({ label }: { label: string }) {
  return (
    <div className="rounded-md border border-dashed border-slate-300 bg-slate-50 p-10 text-center text-sm text-slate-500">
      {label}
    </div>
  );
}
