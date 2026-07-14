'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ChevronLeft,
  Clock,
  Download,
  ExternalLink,
  FileText,
  GitBranch,
  Github,
  GraduationCap,
  LifeBuoy,
  Mail,
  MessageSquare,
  PencilLine,
  Plus,
  Send,
  Trash2,
  Video,
} from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import ProjectStatusTracker, {
  effectiveStatusFor,
  effectiveStatusLabel,
  effectiveStatusTone,
} from '@/components/intern/ProjectStatusTracker';
import type {
  AssignmentSummary,
  TrainerDecision,
} from '../types';

const DECISION_LABEL: Record<TrainerDecision, string> = {
  ACCEPT:           'Accepted',
  REQUEST_REVISION: 'Changes requested',
  ESCALATE:         'Escalated',
  NO_ACTION_YET:    'No action yet',
};

export default function InternProjectDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [data, setData] = useState<AssignmentSummary | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      // No dedicated single-assignment intern endpoint — pull from /mine
      // and find ours. /mine is already owner-scoped server-side.
      const res = await api.get<AssignmentSummary[]>('/api/v1/project-assignments/mine');
      const found = (res.data ?? []).find((x) => x.id === id) ?? null;
      if (!found) {
        setErr('Project not found, or it is not assigned to you.');
      } else {
        setData(found);
        setErr(null);
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load this project');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (loading && !data) {
    return (
      <InternPageShell title="Project" hideTracker hideRightSidePanel>
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !data) {
    return (
      <InternPageShell title="Project" hideTracker hideRightSidePanel>
        <BackLink />
        <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Project not found'}
        </p>
      </InternPageShell>
    );
  }

  const name = data.project?.name ?? 'Project';

  return (
    <InternPageShell
      title={name}
      subtitle={data.project?.techStack ?? undefined}
      hideTracker
      hideRightSidePanel
    >
      {/* Compact title bar: back link + status chips in one row to
          preserve vertical space. */}
      <div className="mt-1 mb-3 flex flex-wrap items-center gap-x-3 gap-y-2">
        <BackLink />
        <StatusBar a={data} />
      </div>

      {/* Status tracker — same visual pattern as the application
          stepper / intern journey bar. Hidden during ASSIGNED /
          IN_PROGRESS (nothing in flight yet) to keep the
          government-tracker affordance focused on the submit → review
          → Q&A → completed pipeline. */}
      {data.status !== 'ASSIGNED' && data.status !== 'IN_PROGRESS' && (
        <div className="mb-3">
          <ProjectStatusTracker assignment={data} />
        </div>
      )}

      {/* One-frame layout on lg+: outer grid has bounded height + no
          overflow; each column scrolls internally. Mobile keeps natural
          flow + page scroll. */}
      <div className="grid gap-4 lg:grid-cols-3 lg:h-[calc(100vh-190px)] lg:overflow-hidden">
        <main className="lg:col-span-2 space-y-3 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:pr-1">
          <DescriptionCard a={data} />
          <ProjectFilesCard a={data} />
          <TrainerFeedbackCard a={data} />
          <SubmissionCard a={data} onChanged={(next) => setData(next)} />
          {/* Help sits at the bottom of the left column so it's right
              there while the intern is submitting, and it also fills
              the gap below Submit-your-work that would otherwise leave
              the column shorter than the right rail. */}
          <NeedHelpCard a={data} />
        </main>
        <aside className="space-y-3 lg:h-full lg:min-h-0 lg:overflow-y-auto lg:pr-1">
          <MetaCard a={data} />
          <QaSessionCard a={data} />
          <KtCard a={data} />
          <RepositoryCard a={data} />
          <YourTeamCard />
        </aside>
      </div>
    </InternPageShell>
  );
}

// ── Sub-cards ─────────────────────────────────────────────────────────────

function BackLink() {
  return (
    <Link
      href="/careers/intern/projects"
      className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
    >
      <ChevronLeft className="h-4 w-4" /> My Projects
    </Link>
  );
}

function StatusBar({ a }: { a: AssignmentSummary }) {
  // SINGLE source of truth — both the tracker and this top status pill
  // derive from effectiveStatusFor so they can never disagree. The
  // previous version showed BOTH the assignment-row label
  // ("Submitted — awaiting review") and a standalone "Trainer accepted"
  // pill at the same time, which contradicted each other once the
  // trainer approved but the assignment row hadn't yet been mirrored.
  const effective = effectiveStatusFor(a);
  const showApprovedCheck =
    effective === 'APPROVED'
    || effective === 'QA_SCHEDULED'
    || effective === 'QA_CONDUCTED'
    || effective === 'COMPLETED';
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span
        className={
          'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold '
          + effectiveStatusTone(effective)
        }
      >
        {showApprovedCheck && <CheckCircle2 className="h-3 w-3" />}
        {effectiveStatusLabel(effective)}
      </span>
      {a.dueDate && <DueChip iso={a.dueDate} submittedAt={a.submittedAt} />}
    </div>
  );
}

function DueChip({ iso, submittedAt }: { iso: string; submittedAt: string | null }) {
  const due = new Date(iso + 'T23:59:59').getTime();
  const ref = submittedAt ? new Date(submittedAt).getTime() : Date.now();
  const overdue = ref > due;
  return (
    <span className={
      'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium '
      + (overdue ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-700')
    }>
      <Clock className="h-3 w-3" />
      {overdue
        ? (submittedAt ? 'Submitted late' : 'Past due')
        : 'Due ' + formatDate(iso)}
    </span>
  );
}

function DescriptionCard({ a }: { a: AssignmentSummary }) {
  const p = a.project;
  if (!p) return null;
  const blocks: { label: string; value: string | null | undefined }[] = [
    { label: 'Description', value: p.description },
    { label: 'Requirements', value: p.requirements },
    { label: 'Objectives', value: p.objectives },
    { label: 'Deliverables', value: p.deliverables },
    { label: 'Instructions', value: p.instructions },
  ];
  const present = blocks.filter((b) => b.value && b.value.trim() !== '');
  if (present.length === 0) {
    return null;
  }
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">About this project</h3>
      <dl className="mt-2 space-y-2">
        {present.map((b) => (
          <div key={b.label}>
            <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              {b.label}
            </dt>
            <dd className="mt-0.5 whitespace-pre-wrap text-[13px] leading-snug text-slate-700">
              {b.value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function ProjectFilesCard({ a }: { a: AssignmentSummary }) {
  const files = a.project?.files ?? [];
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  if (files.length === 0) return null;

  async function downloadFile(fileId: string, fileName: string) {
    setBusyId(fileId);
    setErr(null);
    try {
      const res = await api.get(
        `/api/v1/project-assignments/${a.id}/file`,
        { params: { documentId: fileId }, responseType: 'blob' },
      );
      const blob = res.data as Blob;
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Download failed'),
      );
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">
        Project file{files.length === 1 ? '' : 's'}
      </h3>
      <p className="mt-0.5 text-[11px] text-slate-500">
        Attached by your trainer.
      </p>
      <ul className="mt-2 space-y-1.5">
        {files.map((f) => (
          <li
            key={f.id}
            className="flex items-center justify-between gap-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-2"
          >
            <span className="inline-flex min-w-0 items-center gap-2 text-sm text-slate-800">
              <FileText className="h-4 w-4 shrink-0 text-slate-500" />
              <span className="truncate" title={f.fileName}>{f.fileName}</span>
              {f.fileSize != null && (
                <span className="shrink-0 text-[11px] text-slate-500">
                  · {formatFileSize(f.fileSize)}
                </span>
              )}
            </span>
            <button
              type="button"
              onClick={() => downloadFile(f.id, f.fileName)}
              disabled={busyId === f.id}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-semibold text-brand-700 hover:bg-brand-50 disabled:opacity-60"
            >
              <Download className="h-3 w-3" />
              {busyId === f.id ? 'Downloading…' : 'Download'}
            </button>
          </li>
        ))}
      </ul>
      {err && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
    </section>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function TrainerFeedbackCard({ a }: { a: AssignmentSummary }) {
  const sub = a.latestSubmission;
  if (!sub || !sub.trainerDecision || sub.trainerDecision === 'NO_ACTION_YET') {
    return null;
  }
  const decision = sub.trainerDecision;
  const tone =
    decision === 'ACCEPT' ? 'border-green-200 bg-green-50 text-green-900'
    : decision === 'REQUEST_REVISION' ? 'border-red-200 bg-red-50 text-red-900'
    : 'border-amber-200 bg-amber-50 text-amber-900';
  return (
    <section className={'rounded-lg border p-3 shadow-sm ' + tone}>
      <div className="flex items-center gap-2">
        {decision === 'ACCEPT'
          ? <CheckCircle2 className="h-4 w-4" />
          : <AlertTriangle className="h-4 w-4" />}
        <h3 className="text-sm font-semibold">
          Feedback from trainer · {DECISION_LABEL[decision]}
        </h3>
        <span className="ml-auto text-[10px] opacity-80">
          v{sub.version}
          {sub.reviewedByName ? ' · ' + sub.reviewedByName : ''}
        </span>
      </div>
      {sub.trainerFeedback && (
        <p className="mt-1.5 whitespace-pre-wrap text-[13px] leading-snug">
          {sub.trainerFeedback}
        </p>
      )}
    </section>
  );
}

function SubmissionCard({
  a, onChanged,
}: { a: AssignmentSummary; onChanged: (next: AssignmentSummary) => void }) {
  const status = a.status;
  const revisionRequested =
    a.latestSubmission?.trainerDecision === 'REQUEST_REVISION';

  // Submitable when intern hasn't sent yet (IN_PROGRESS / RETURNED) or
  // when trainer requested revisions on the latest submission.
  const canSubmit =
    status === 'IN_PROGRESS'
    || status === 'RETURNED'
    || (status === 'SUBMITTED' && revisionRequested);

  // What they last sent — surfaced read-only when not currently submitable.
  const lastSent = a.latestSubmission;

  // ASSIGNED gets the dedicated Get-Started card (GitHub username capture +
  // access-granted wait + Start button) instead of the static placeholder.
  if (status === 'ASSIGNED') {
    return <GetStartedCard a={a} onChanged={onChanged} />;
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">
        {canSubmit
          ? (lastSent ? 'Re-submit your work' : 'Submit your work')
          : 'Your submission'}
      </h3>
      {canSubmit ? (
        <SubmissionForm
          assignmentId={a.id}
          initialLinks={lastSent?.links ?? ['']}
          initialNotes={lastSent?.description ?? ''}
          isResubmit={Boolean(lastSent)}
          onSubmitted={onChanged}
        />
      ) : lastSent ? (
        <ReadOnlySubmission sub={lastSent} />
      ) : (
        <p className="mt-2 text-sm text-slate-500">
          Nothing to submit at this stage.
        </p>
      )}
    </section>
  );
}

/**
 * Three-stage gate on the ASSIGNED state, matching the backend's
 * {@code ProjectAssignmentService.startAssignment} preconditions:
 *   1. intern has a GitHub username on file
 *   2. trainer has flipped {@code accessGranted=true}
 *   3. intern clicks Start
 * Each stage exposes the right action when it's the next step, and the
 * Start button only enables when both upstream stages are satisfied.
 */
function GetStartedCard({
  a, onChanged,
}: { a: AssignmentSummary; onChanged: (next: AssignmentSummary) => void }) {
  const initialUsername = a.intern?.githubUsername?.trim() ?? '';
  const [savedUsername, setSavedUsername] = useState(initialUsername);
  const [editingUsername, setEditingUsername] = useState(initialUsername === '');
  const [usernameDraft, setUsernameDraft] = useState(initialUsername);
  const [savingUsername, setSavingUsername] = useState(false);
  const [usernameErr, setUsernameErr] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [startErr, setStartErr] = useState<string | null>(null);

  const accessGranted = a.accessGranted === true;
  const hasUsername = savedUsername.trim() !== '';
  const canStart = hasUsername && accessGranted;
  const usernameDraftValid = isValidGitHubUsername(usernameDraft);

  async function saveUsername() {
    if (!usernameDraftValid) return;
    setSavingUsername(true);
    setUsernameErr(null);
    try {
      const res = await api.put<{ githubUsername: string }>(
        '/api/v1/users/me/github-username',
        { githubUsername: usernameDraft.trim() },
      );
      const stored = res.data?.githubUsername ?? usernameDraft.trim();
      setSavedUsername(stored);
      setUsernameDraft(stored);
      setEditingUsername(false);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setUsernameErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Could not save GitHub username'),
      );
    } finally {
      setSavingUsername(false);
    }
  }

  async function startProject() {
    if (!canStart) return;
    setStarting(true);
    setStartErr(null);
    try {
      const res = await api.post<AssignmentSummary>(
        `/api/v1/project-assignments/${a.id}/start`,
      );
      onChanged(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setStartErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Could not start the project'),
      );
    } finally {
      setStarting(false);
    }
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">Get started</h3>
      <p className="mt-1 text-sm text-slate-600">
        Add your GitHub username so your trainer can grant you repo access.
        Once they do, you can start the project.
      </p>

      {/* Stage 1 — GitHub username */}
      <div className="mt-4">
        <label
          htmlFor="ghu"
          className="text-xs font-semibold uppercase tracking-wide text-slate-500"
        >
          1. Your GitHub username
        </label>
        {editingUsername ? (
          <div className="mt-1 flex flex-wrap items-start gap-2">
            <div className="flex-1 min-w-[12rem]">
              <input
                id="ghu"
                type="text"
                value={usernameDraft}
                onChange={(e) =>
                  setUsernameDraft(e.target.value.replace(/\s+/g, ''))
                }
                placeholder="octocat"
                autoComplete="off"
                spellCheck={false}
                className={
                  'w-full rounded-md border px-3 py-2 text-sm '
                  + (usernameErr || (usernameDraft && !usernameDraftValid)
                      ? 'border-red-400'
                      : 'border-slate-200')
                }
              />
              {usernameDraft && !usernameDraftValid && (
                <p className="mt-1 text-xs text-red-700">
                  Letters, numbers and single hyphens only; max 39 characters.
                </p>
              )}
            </div>
            <button
              type="button"
              onClick={saveUsername}
              disabled={savingUsername || !usernameDraftValid}
              className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {savingUsername ? 'Saving…' : 'Save'}
            </button>
            {hasUsername && (
              <button
                type="button"
                onClick={() => {
                  setUsernameDraft(savedUsername);
                  setEditingUsername(false);
                  setUsernameErr(null);
                }}
                className="rounded-md border border-slate-200 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
            )}
          </div>
        ) : (
          <div className="mt-1 flex items-center justify-between rounded-md border border-slate-200 bg-slate-50 px-3 py-2">
            <span className="inline-flex items-center gap-2 text-sm text-slate-800">
              <Github className="h-4 w-4 text-slate-500" />
              <code className="font-mono">{savedUsername}</code>
            </span>
            <button
              type="button"
              onClick={() => {
                setEditingUsername(true);
                setUsernameErr(null);
              }}
              className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
            >
              <PencilLine className="h-3 w-3" /> Edit
            </button>
          </div>
        )}
        {usernameErr && (
          <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-700">
            {usernameErr}
          </p>
        )}
      </div>

      {/* Stage 2 — repo access */}
      <div className="mt-4">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          2. Repo access
        </p>
        {accessGranted ? (
          <p className="mt-1 inline-flex items-center gap-1.5 text-sm text-green-700">
            <CheckCircle2 className="h-4 w-4" /> Trainer has granted you repo access.
          </p>
        ) : (
          <p className="mt-1 inline-flex items-center gap-1.5 text-sm text-amber-700">
            <Clock className="h-4 w-4" />
            {hasUsername
              ? 'Waiting for your trainer to grant repo access.'
              : 'Save your GitHub username so your trainer can invite you.'}
          </p>
        )}
      </div>

      {/* Stage 3 — start */}
      <div className="mt-5 flex items-center justify-between border-t border-slate-100 pt-4">
        <p className="text-xs text-slate-500">
          {canStart
            ? 'Everything ready — start the project to enable submissions.'
            : 'Complete the steps above to enable Start.'}
        </p>
        <button
          type="button"
          onClick={startProject}
          disabled={starting || !canStart}
          title={!canStart
            ? 'Add your GitHub username and wait for trainer access first'
            : undefined}
          className="inline-flex items-center gap-2 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
        >
          {starting ? 'Starting…' : 'Start project'}
          <ArrowRight className="h-4 w-4" />
        </button>
      </div>
      {startErr && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-700">
          {startErr}
        </p>
      )}
    </section>
  );
}

/**
 * GitHub username validation — mirrors the backend's @Pattern on
 * SetGithubUsernameRequest. Letters, digits and single hyphens, no
 * leading/trailing hyphen, 1-39 chars.
 */
function isValidGitHubUsername(s: string): boolean {
  const v = s.trim();
  if (v.length === 0 || v.length > 39) return false;
  return /^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$/.test(v);
}

function ReadOnlySubmission({ sub }: { sub: NonNullable<AssignmentSummary['latestSubmission']> }) {
  return (
    <div className="mt-3 space-y-3">
      <p className="text-xs text-slate-500">
        Submitted {formatInstant(sub.submittedAt)} · v{sub.version}
      </p>
      {sub.links.length > 0 && (
        <ul className="space-y-1.5">
          {sub.links.map((l) => (
            <li key={l}>
              <a
                href={l}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 break-all text-sm text-brand-700 hover:underline"
              >
                <GitBranch className="h-3 w-3" /> {l}
              </a>
            </li>
          ))}
        </ul>
      )}
      {sub.description && (
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
            Notes
          </p>
          <p className="mt-0.5 whitespace-pre-wrap text-sm text-slate-700">
            {sub.description}
          </p>
        </div>
      )}
    </div>
  );
}

function SubmissionForm({
  assignmentId, initialLinks, initialNotes, isResubmit, onSubmitted,
}: {
  assignmentId: string;
  initialLinks: string[];
  initialNotes: string;
  isResubmit: boolean;
  onSubmitted: (next: AssignmentSummary) => void;
}) {
  const [links, setLinks] = useState<string[]>(
    initialLinks.length > 0 ? initialLinks : ['']);
  const [notes, setNotes] = useState(initialNotes);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const trimmedLinks = useMemo(
    () => links.map((l) => l.trim()).filter((l) => l !== ''),
    [links],
  );
  const linkErrors = useMemo(() => trimmedLinks.map(validateUrl), [trimmedLinks]);
  const hasInvalidUrl = linkErrors.some((e) => e !== null);
  const hasContent = trimmedLinks.length > 0 || notes.trim().length > 0;

  function setLinkAt(i: number, v: string) {
    setLinks((prev) => prev.map((p, idx) => (idx === i ? v : p)));
  }
  function addLink() {
    setLinks((prev) => [...prev, '']);
  }
  function removeLink(i: number) {
    setLinks((prev) => prev.filter((_, idx) => idx !== i));
  }

  async function submit() {
    if (!hasContent || hasInvalidUrl) return;
    setBusy(true);
    setErr(null);
    try {
      const res = await api.post<AssignmentSummary>(
        `/api/v1/project-assignments/${assignmentId}/submit`,
        {
          submissionNotes: notes.trim() || null,
          deliverableLinks: trimmedLinks,
        },
      );
      onSubmitted(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Submit failed'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-2 space-y-3">
      {isResubmit && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          The trainer requested changes — update your links / notes and re-submit.
        </p>
      )}

      <div>
        <label className="text-xs font-medium text-slate-800">
          Deliverable links
          <span className="ml-1 font-normal text-slate-500">
            (https:// — repo, PR, demo, docs)
          </span>
        </label>
        <div className="mt-1.5 space-y-1.5">
          {links.map((l, i) => {
            const trimmed = l.trim();
            const invalid = trimmed !== '' && validateUrl(trimmed) !== null;
            return (
              <div key={i} className="flex items-start gap-1.5">
                <div className="flex-1">
                  <input
                    type="url"
                    value={l}
                    onChange={(e) => setLinkAt(i, e.target.value)}
                    placeholder="https://github.com/you/your-repo"
                    className={
                      'w-full rounded-md border px-2.5 py-1.5 text-sm '
                      + (invalid ? 'border-red-400' : 'border-slate-200')
                    }
                  />
                  {invalid && (
                    <p className="mt-0.5 text-[11px] text-red-700">
                      {validateUrl(trimmed)}
                    </p>
                  )}
                </div>
                {links.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removeLink(i)}
                    aria-label="Remove link"
                    className="mt-0.5 rounded-md border border-slate-200 p-1.5 text-slate-500 hover:bg-slate-50"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
            );
          })}
          <button
            type="button"
            onClick={addLink}
            className="inline-flex items-center gap-1 text-[11px] font-medium text-brand-700 hover:underline"
          >
            <Plus className="h-3 w-3" /> Add another link
          </button>
        </div>
      </div>

      <div>
        <label className="text-xs font-medium text-slate-800">
          Notes <span className="font-normal text-slate-500">(optional)</span>
        </label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          maxLength={5000}
          placeholder="What you built, what to look at first, known gaps…"
          className="mt-1 w-full resize-y rounded-md border border-slate-200 px-2.5 py-1.5 text-sm"
        />
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}

      <div className="flex justify-end">
        <button
          type="button"
          onClick={submit}
          disabled={busy || !hasContent || hasInvalidUrl}
          className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
        >
          <Send className="h-3.5 w-3.5" />
          {busy
            ? (isResubmit ? 'Re-submitting…' : 'Submitting…')
            : (isResubmit ? 'Re-submit' : 'Submit')}
        </button>
      </div>
    </div>
  );
}

function MetaCard({ a }: { a: AssignmentSummary }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Details
      </h3>
      <dl className="mt-2 space-y-1 text-xs">
        <Row k="Assigned" v={a.assignmentDate ? formatDate(a.assignmentDate) : '—'} />
        <Row k="Due" v={a.dueDate ? formatDate(a.dueDate) : '—'} />
        <Row k="Started" v={a.startedAt ? formatInstant(a.startedAt) : '—'} />
        <Row k="Submitted" v={a.submittedAt ? formatInstant(a.submittedAt) : '—'} />
        {a.project?.difficulty && (
          <Row k="Difficulty" v={a.project.difficulty} />
        )}
        {a.project?.expectedDurationDays && (
          <Row k="Expected" v={`${a.project.expectedDurationDays} days`} />
        )}
      </dl>
    </section>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-slate-500">{k}</dt>
      <dd className="text-right text-slate-800">{v}</dd>
    </div>
  );
}

/**
 * Consolidates the three previously-scattered help affordances (Messages,
 * Need help?, Stuck on this?) into one accent-toned card. Primary action
 * is Raise-a-doubt (deep-links to the Doubts page with this project
 * preselected); secondary is the message inbox.
 */
function NeedHelpCard({ a }: { a: AssignmentSummary }) {
  const projectId = a.project?.id;
  const doubtsHref = projectId
    ? `/careers/intern/doubts?projectId=${projectId}&assignmentId=${a.id}`
    : '/careers/intern/doubts';
  return (
    <section className="rounded-lg border border-brand-200 bg-brand-50 p-3 shadow-sm">
      <div className="flex items-center gap-1.5">
        <LifeBuoy className="h-3.5 w-3.5 text-brand-700" strokeWidth={2.2} />
        <h3 className="text-xs font-semibold uppercase tracking-wide text-brand-800">
          Need help?
        </h3>
      </div>
      <p className="mt-1 text-[11px] text-brand-900/80">
        Message your trainer or raise a doubt for a reply or a live session.
      </p>
      <div className="mt-2 flex flex-wrap gap-1.5">
        <Link
          href={doubtsHref}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          Raise a doubt <ArrowRight className="h-3 w-3" />
        </Link>
        <Link
          href="/careers/intern/messages"
          className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-white px-2.5 py-1 text-[11px] font-semibold text-brand-800 hover:bg-brand-100"
        >
          <MessageSquare className="h-3 w-3" /> Message
        </Link>
      </div>
    </section>
  );
}

/**
 * Compact contacts card. Reuses /api/v1/intern/right-panel — the same
 * endpoint the global RightSidePanel polls — so the Trainer / Manager /
 * Evaluator / ERM identity surfaces here without needing a parallel
 * source. Avatar circle on the brand palette + email row on hover.
 */
interface TeamContact { name: string; email: string; role: string }
function YourTeamCard() {
  const [contacts, setContacts] = useState<TeamContact[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.get<{
      contacts: {
        erm?: TeamContact | null;
        trainer?: TeamContact | null;
        evaluator?: TeamContact | null;
        manager?: TeamContact | null;
      };
    }>('/api/v1/intern/right-panel')
      .then((res) => {
        if (cancelled) return;
        const c = res.data?.contacts ?? {};
        const out: TeamContact[] = [];
        if (c.trainer) out.push({ ...c.trainer, role: 'Trainer' });
        if (c.manager) out.push({ ...c.manager, role: 'Manager' });
        if (c.evaluator) out.push({ ...c.evaluator, role: 'Evaluator' });
        if (c.erm) out.push({ ...c.erm, role: 'ERM' });
        setContacts(out);
      })
      .catch(() => { if (!cancelled) setContacts([]); });
    return () => { cancelled = true; };
  }, []);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Your team
      </h3>
      {contacts === null ? (
        <div className="mt-1.5 h-10 animate-pulse rounded bg-slate-50" aria-hidden />
      ) : contacts.length === 0 ? (
        <p className="mt-1.5 text-[11px] text-slate-500">
          Contacts will appear here once your team is assigned.
        </p>
      ) : (
        <ul className="mt-1.5 space-y-1.5">
          {contacts.map((c) => (
            <li key={c.role} className="flex items-center gap-2">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-brand-100 text-[10px] font-semibold text-brand-800">
                {teamInitials(c.name)}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline gap-1.5">
                  <span className="truncate text-[11px] font-medium text-slate-900">
                    {c.name}
                  </span>
                  <span className="text-[10px] uppercase tracking-wide text-slate-500">
                    {c.role}
                  </span>
                </div>
                <a
                  href={`mailto:${c.email}`}
                  className="inline-flex items-center gap-0.5 truncate text-[10px] text-brand-700 hover:underline"
                  title={c.email}
                >
                  <Mail className="h-2.5 w-2.5 shrink-0" />
                  <span className="truncate">{c.email}</span>
                </a>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function teamInitials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '').join('');
}

function RepositoryCard({ a }: { a: AssignmentSummary }) {
  const repo = a.project?.repository;
  if (!repo?.repositoryUrl) return null;
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Repository
      </h3>
      <a
        href={repo.repositoryUrl}
        target="_blank"
        rel="noreferrer"
        className="mt-1.5 inline-flex items-center gap-1 break-all text-xs text-brand-700 hover:underline"
      >
        <Github className="h-3.5 w-3.5" />
        {repo.repositoryName ?? repo.repositoryUrl}
        <ExternalLink className="h-3 w-3" />
      </a>
      {a.accessGranted === false && (
        <p className="mt-1.5 text-[11px] text-amber-700">
          Access not granted yet — ask your trainer.
        </p>
      )}
    </section>
  );
}

/**
 * Q&A (viva) session card. Shows when the Evaluator has scheduled a
 * Q&A session for this project. Surfaces the Zoom join link + time +
 * who scheduled. Mirrors the KtCard layout. Pending state shown when
 * the project has reached PENDING_VIVA but the Evaluator hasn't
 * scheduled yet — so the intern knows what's next.
 */
function QaSessionCard({ a }: { a: AssignmentSummary }) {
  const qa = a.qaSession;
  const awaiting = a.status === 'PENDING_VIVA' && !qa;
  if (!qa && !awaiting) return null;

  if (awaiting) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
        <div className="flex items-center gap-1.5">
          <MessageSquare className="h-3.5 w-3.5 text-slate-500" />
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
            Q&amp;A session
          </h3>
          <span className="ml-auto rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-medium text-amber-800">
            Awaiting Evaluator
          </span>
        </div>
        <p className="mt-1.5 text-[11px] text-slate-500">
          Your trainer approved the project. Your Evaluator will schedule a
          Q&amp;A session — you&apos;ll be notified by email with the join link.
        </p>
      </section>
    );
  }
  // qa is non-null past this point.
  const hasJoin = !!qa!.zoomJoinUrl || !!qa!.meetingLink;
  const isConducted = qa!.status === 'CONDUCTED';
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <div className="flex items-center gap-1.5">
        <MessageSquare className="h-3.5 w-3.5 text-slate-500" />
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          Q&amp;A session
        </h3>
        <span className={
          'ml-auto rounded-full px-2 py-0.5 text-[10px] font-medium '
          + (isConducted
              ? 'bg-emerald-100 text-emerald-800'
              : 'bg-amber-100 text-amber-800')
        }>
          {isConducted ? 'Conducted — awaiting sign-off' : 'Scheduled'}
        </span>
      </div>

      <div className="mt-2 rounded-md border border-brand-200 bg-brand-50 p-2">
        <div className="flex flex-wrap items-baseline justify-between gap-1.5">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-800">
            Live Q&amp;A session
          </p>
          {qa!.scheduledAt && (
            <p className="text-[10px] text-slate-700">
              {new Date(qa!.scheduledAt).toLocaleString([], {
                month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
              })}
              {qa!.durationMinutes ? ` · ${qa!.durationMinutes}m` : ''}
            </p>
          )}
        </div>
        {qa!.scheduledByName && (
          <p className="mt-0.5 text-[10px] text-slate-600">
            Scheduled by {qa!.scheduledByName}, your Evaluator
          </p>
        )}
        {hasJoin && !isConducted && (
          <a
            href={(qa!.zoomJoinUrl ?? qa!.meetingLink) ?? '#'}
            target="_blank"
            rel="noreferrer noopener"
            className="mt-1.5 inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            <Video className="h-3 w-3" />
            Join Meeting
          </a>
        )}
        {isConducted && (
          <p className="mt-1.5 text-[11px] text-emerald-800">
            Session conducted. Your Evaluator will sign off with marks and
            remarks — you&apos;ll be notified when the project is marked
            Completed.
          </p>
        )}
      </div>
    </section>
  );
}

function KtCard({ a }: { a: AssignmentSummary }) {
  const kt = a.project?.kt;
  if (!kt) return null; // Catalog-only, no assignment → KT is N/A.
  const done = kt.status === 'DONE';
  const hasScheduledSession = !!kt.zoomMeetingId && !!kt.zoomJoinUrl;
  // Compact branch — KT done. Once the trainer marks done we never
  // surface the live Zoom join link again, even if the stored
  // ktZoom* fields are still on the project row. The compact "KT
  // done · {date} · {trainer}" pill IS the status. Notes (if the
  // trainer added a summary) line-clamp under it.
  if (done) {
    return (
      <section className="rounded-lg border border-green-200 bg-green-50 px-3 py-2 shadow-sm">
        <div className="flex items-center gap-1.5 text-[11px] text-green-900">
          <GraduationCap className="h-3.5 w-3.5 text-green-700" />
          <span className="font-semibold">KT done</span>
          <span className="text-green-800/80">
            {kt.completedAt ? '· ' + formatInstant(kt.completedAt) : ''}
            {kt.markedByName ? ' · ' + kt.markedByName : ''}
          </span>
        </div>
        {kt.notes && (
          <p className="mt-1 line-clamp-2 text-[11px] text-green-900/80">
            {kt.notes}
          </p>
        )}
      </section>
    );
  }
  // Expanded branch — scheduled, in-progress, or not-yet-done. Keeps
  // the Join button + scheduled time prominent.
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <div className="flex items-center gap-1.5">
        <GraduationCap className="h-3.5 w-3.5 text-slate-500" />
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          KT session
        </h3>
        <span className={
          'ml-auto rounded-full px-2 py-0.5 text-[10px] font-medium '
          + (done
              ? 'bg-green-100 text-green-800'
              : 'bg-slate-100 text-slate-700')
        }>
          {done ? 'Done' : 'Not done'}
        </span>
      </div>

      {hasScheduledSession && (
        <div className="mt-2 rounded-md border border-brand-200 bg-brand-50 p-2">
          <div className="flex flex-wrap items-baseline justify-between gap-1.5">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-800">
              Live KT session
            </p>
            {kt.scheduledFor && (
              <p className="text-[10px] text-slate-700">
                {new Date(kt.scheduledFor).toLocaleString([], {
                  month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
                })}
                {kt.durationMinutes ? ` · ${kt.durationMinutes}m` : ''}
              </p>
            )}
          </div>
          <a
            href={kt.zoomJoinUrl ?? '#'}
            target="_blank"
            rel="noreferrer noopener"
            className="mt-1.5 inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            <Video className="h-3 w-3" />
            Join Meeting
          </a>
        </div>
      )}

      {!done && !hasScheduledSession && (
        <p className="mt-1.5 text-[11px] text-slate-500">
          Your trainer will schedule a KT session and mark it done here.
        </p>
      )}
    </section>
  );
}

// ── Helpers ───────────────────────────────────────────────────────────────

function validateUrl(s: string): string | null {
  try {
    const u = new URL(s);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return 'Must be an http:// or https:// URL';
    }
    if (!u.hostname) return 'URL is missing a hostname';
    return null;
  } catch {
    return 'Not a valid URL';
  }
}

function formatDate(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
    });
  } catch { return iso; }
}

function formatInstant(iso: string): string {
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch { return iso; }
}
