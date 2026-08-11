'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { ChevronLeft, Save, Send, Star, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import DraftAutosaveIndicator from '@/components/ui/DraftAutosaveIndicator';
import { readDraft, useDraftAutosave } from '@/lib/careers/useDraftAutosave';
import type { EvaluatorEvaluationDetail, RecommendationFinal } from '@/components/evaluator/types';
import { RECOMMENDATIONS, RECOMMENDATIONS_FINAL } from '@/components/evaluator/types';
import WebexHostStartCard from '@/components/meeting/WebexHostStartCard';
import RecordingUploader from '@/components/dashboard/RecordingUploader';
import ReferenceQaPanel from '@/components/project/ReferenceQaPanel';
import ProjectContextPanel from '@/components/evaluator/ProjectContextPanel';
import RecentWeeklyReportsPanel from '@/components/evaluator/RecentWeeklyReportsPanel';
import SchedulePostProjectDialog from '@/components/evaluator/SchedulePostProjectDialog';

interface InternProjectOption {
  projectId: string;
  title: string;
  status: string | null;
  assignmentDate: string | null;
  dueDate: string | null;
}

const RUBRIC: { key: 'technical' | 'communication' | 'professionalism' | 'learning'; label: string; tip: string }[] = [
  { key: 'technical',      label: 'Technical Skills',        tip: 'How strong is their technical work this period? Code quality, problem-solving, design choices.' },
  { key: 'communication',  label: 'Communication',           tip: 'How clear and timely are their updates? Async writing, meeting participation, documentation.' },
  { key: 'professionalism',label: 'Professionalism',         tip: 'Attendance, attitude, ownership, reliability. Do they show up and follow through?' },
  { key: 'learning',       label: 'Learning Application',    tip: 'How well are they absorbing training and applying it to actual work?' },
];

export default function ComposePage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = params?.id;

  const [data, setData] = useState<EvaluatorEvaluationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  // Form state
  const [technical, setTechnical] = useState<number | null>(null);
  const [communication, setCommunication] = useState<number | null>(null);
  const [professionalism, setProfessionalism] = useState<number | null>(null);
  const [learning, setLearning] = useState<number | null>(null);
  const [strengths, setStrengths] = useState('');
  const [areas, setAreas] = useState('');
  const [comments, setComments] = useState('');
  const [recommendation, setRecommendation] = useState<RecommendationFinal | ''>('');
  const [internalNotes, setInternalNotes] = useState('');

  const [savingDraft, setSavingDraft] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [scheduleDialogOpen, setScheduleDialogOpen] = useState(false);

  // Session-recording state (independent of the rubric form so it can be
  // uploaded before/after publish — the compose flow doesn't gate on it).
  const [projectOptions, setProjectOptions] = useState<InternProjectOption[]>([]);
  const [selectedProject, setSelectedProject] = useState<string>('');
  const [recordingDocId, setRecordingDocId] = useState<string | null>(null);
  const [recordingErr, setRecordingErr] = useState<string | null>(null);
  const [recordingSavedAt, setRecordingSavedAt] = useState<Date | null>(null);

  // ── Draft autosave ────────────────────────────────────────────────
  // Keyed per evaluation id — sessionStorage draft survives crash /
  // refresh / accidental nav. Restored after the server payload loads
  // (so unsaved local edits take precedence over the last committed
  // draft), cleared on publish.
  const draftKey = id ? `draft:evaluator-eval:${id}` : 'draft:evaluator-eval:disabled';
  interface DraftShape {
    technical: number | null;
    communication: number | null;
    professionalism: number | null;
    learning: number | null;
    strengths: string;
    areas: string;
    comments: string;
    recommendation: RecommendationFinal | '';
    internalNotes: string;
  }
  const draftValue = useMemo<DraftShape>(() => ({
    technical, communication, professionalism, learning,
    strengths, areas, comments, recommendation, internalNotes,
  }), [technical, communication, professionalism, learning,
    strengths, areas, comments, recommendation, internalNotes]);
  const draftAutosave = useDraftAutosave(draftKey, draftValue,
    { enabled: !!id });

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<EvaluatorEvaluationDetail>(
        `/api/v1/evaluator/evaluations/${id}`,
      );
      setData(res.data);
      // Prefer a local session draft over the server payload when one
      // exists — the local blob is by definition newer than the last
      // saveDraft. Overriding-after-load lets the initial fetch
      // populate the base state first, then draft restore stomps only
      // the composer fields (recording state and other server-only
      // fields stay).
      const stored = readDraft<DraftShape>(draftKey);
      setTechnical(stored?.technical ?? res.data.technicalSkillsScore ?? null);
      setCommunication(stored?.communication ?? res.data.communicationScore ?? null);
      setProfessionalism(stored?.professionalism ?? res.data.professionalismScore ?? null);
      setLearning(stored?.learning ?? res.data.learningApplicationScore ?? null);
      setStrengths(stored?.strengths ?? res.data.strengths ?? '');
      setAreas(stored?.areas ?? res.data.areasForImprovement ?? '');
      setComments(stored?.comments ?? res.data.comments ?? '');
      setRecommendation(stored?.recommendation
        ?? (res.data.recommendation as RecommendationFinal | null) ?? '');
      setInternalNotes(stored?.internalNotes ?? res.data.internalNotes ?? '');
      setRecordingDocId(res.data.recordingDocumentId ?? null);
      if (res.data.linkedProjectId) setSelectedProject(res.data.linkedProjectId);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);
  useEffect(() => { void load(); }, [load]);

  // Load the intern's project assignments for the Session Recording
  // project selector. Best-effort — an empty list just leaves the
  // selector with the "General / no project" option only.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api.get<InternProjectOption[]>(
      `/api/v1/evaluator/evaluations/${id}/intern-projects`,
    )
      .then((res) => { if (!cancelled) setProjectOptions(res.data ?? []); })
      .catch(() => { if (!cancelled) setProjectOptions([]); });
    return () => { cancelled = true; };
  }, [id]);

  const persistRecording = useCallback(async (docId: string) => {
    if (!id) return;
    setRecordingErr(null);
    try {
      await api.post(`/api/v1/evaluator/evaluations/${id}/recording`, {
        recordingDocumentId: docId,
        linkedProjectId: selectedProject || null,
      });
      setRecordingDocId(docId);
      setRecordingSavedAt(new Date());
      // Refetch so the approval-gate banner switches to PENDING_APPROVAL
      // (or REVISION_REQUESTED → PENDING_APPROVAL on a re-upload).
      void load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setRecordingErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save recording');
    }
  }, [id, selectedProject, load]);

  const scoredCount = [technical, communication, professionalism, learning].filter((s) => s != null).length;
  const avg = scoredCount > 0
    ? (((technical ?? 0) + (communication ?? 0) + (professionalism ?? 0) + (learning ?? 0)) / scoredCount).toFixed(2)
    : '—';
  const feedbackChars = (strengths.trim().length + areas.trim().length + comments.trim().length);
  const canPublish = scoredCount === 4 && recommendation && feedbackChars >= 50;

  function body() {
    return {
      technicalSkillsScore: technical,
      communicationScore: communication,
      professionalismScore: professionalism,
      learningApplicationScore: learning,
      strengths: strengths.trim() || null,
      areasForImprovement: areas.trim() || null,
      comments: comments.trim() || null,
      recommendation: recommendation || null,
      internalNotes: internalNotes.trim() || null,
    };
  }

  async function saveDraft() {
    setSavingDraft(true);
    setErr(null);
    try {
      await api.patch(`/api/v1/evaluator/evaluations/${id}/draft`, body());
      setSavedAt(new Date());
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save draft');
    } finally {
      setSavingDraft(false);
    }
  }

  async function publish() {
    if (!canPublish) return;
    setPublishing(true);
    setErr(null);
    try {
      // Save current edits as draft first, then publish.
      await api.patch(`/api/v1/evaluator/evaluations/${id}/draft`, body());
      await api.post(`/api/v1/evaluator/evaluations/${id}/publish`);
      // Server now has the canonical row — drop the local session
      // snapshot so re-opening starts fresh instead of restoring the
      // just-published payload.
      draftAutosave.clear();
      router.push(`/careers/evaluator/evaluees/${data?.internLifecycleId}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to publish');
    } finally {
      setPublishing(false);
    }
  }

  if (loading && !data) {
    return <div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>;
  }
  if (err && !data) {
    return <div className="mx-auto max-w-6xl p-6"><p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p></div>;
  }
  if (!data) return null;

  const readOnly = data.status === 'PUBLISHED' || data.status === 'ACKNOWLEDGED' || data.status === 'AMENDED';
  const isFinal = data.evaluationType === 'FINAL';
  const recOptions = isFinal ? RECOMMENDATIONS_FINAL : RECOMMENDATIONS;

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator/pending-evaluations"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Scheduled Evaluations
        </Link>
      </p>

      <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">
              {isFinal ? 'Final evaluation' : 'Compose evaluation'} — {data.internName ?? '(unnamed)'}
            </h1>
            <p className="text-xs text-slate-500">
              {data.employeeId} {data.technology && `· ${data.technology}`}
              {data.periodStart && ` · Period: ${data.periodStart} → ${data.periodEnd}`}
            </p>
          </div>
          <div className="flex flex-col items-end gap-2">
            {data.evaluationType === 'POST_PROJECT'
              && (data.status === 'DRAFT' || data.status === 'SCHEDULED')
              && !readOnly && (
              <button type="button" onClick={() => setScheduleDialogOpen(true)}
                className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-3 py-1.5 text-xs font-semibold text-brand-800 hover:bg-brand-100">
                {data.status === 'SCHEDULED' ? 'Reschedule' : 'Schedule session'}
              </button>
            )}
            {data.zoomMeetingId && (
              <div className="w-full max-w-md">
                <WebexHostStartCard
                  providerMeetingId={data.zoomMeetingId}
                  startUrl={data.zoomStartUrl}
                />
              </div>
            )}
          </div>
        </div>
        {readOnly && (
          <p className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
            This evaluation is {data.status}. Open the detail page to amend.
          </p>
        )}
      </section>

      <div className="grid gap-4 lg:grid-cols-3">
        {/* Form column */}
        <div className="space-y-4 lg:col-span-2">
          {/* Per-project context: project panel + weekly reports. Only
              renders when the evaluation is linked to a project (auto-drafted
              POST_PROJECT evals always are; MONTHLY evals may or may not be). */}
          <ProjectContextPanel projectId={selectedProject || data.linkedProjectId || null} />

          {/* Recent weekly reports — reuse the existing per-intern fetch;
              intern's own account of the work leading into this evaluation. */}
          <RecentWeeklyReportsPanel lifecycleId={data.internLifecycleId} />

          {/* Rubric */}
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-slate-900">Structured Rubric</h2>
              <p className="text-xs text-slate-500">
                Avg: <span className="font-semibold tabular-nums text-slate-900">{avg}</span> / 5
              </p>
            </div>
            <div className="mt-3 space-y-3">
              {RUBRIC.map(({ key, label, tip }) => {
                const val = key === 'technical' ? technical
                  : key === 'communication' ? communication
                  : key === 'professionalism' ? professionalism
                  : learning;
                const set = key === 'technical' ? setTechnical
                  : key === 'communication' ? setCommunication
                  : key === 'professionalism' ? setProfessionalism
                  : setLearning;
                return (
                  <div key={key} className="rounded-md border border-slate-100 bg-slate-50 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-sm font-medium text-slate-800" title={tip}>{label}</p>
                      <div className="flex gap-1">
                        {[1, 2, 3, 4, 5].map((n) => (
                          <button
                            key={n}
                            type="button"
                            disabled={readOnly}
                            onClick={() => set(n)}
                            className={
                              'inline-flex h-7 w-7 items-center justify-center rounded-md border text-xs font-semibold ' +
                              (val === n
                                ? 'border-brand-700 bg-brand-700 text-white'
                                : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-100')
                            }
                          >
                            {n}
                          </button>
                        ))}
                      </div>
                    </div>
                    <p className="mt-1 text-[11px] text-slate-500">{tip}</p>
                  </div>
                );
              })}
            </div>
          </section>

          {/* Trainer Reference Q&A — read-only panel resolved via the
              evaluation's linked project OR the recording project selector
              (updates live if the evaluator picks a different project).
              alwaysShow renders an explicit empty-state note when the
              trainer hasn't attached any pairs — otherwise the panel
              silently disappears and the evaluator can't tell "no Q&A"
              apart from "Q&A never loaded". */}
          <ReferenceQaPanel projectId={selectedProject || data.linkedProjectId || null} alwaysShow />

          {/* Session recording */}
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <div className="flex items-center gap-2">
              <Video className="h-4 w-4 text-brand-700" />
              <h2 className="text-sm font-semibold text-slate-900">
                Session recording <span className="text-xs font-normal text-slate-500">(optional)</span>
              </h2>
            </div>
            <p className="mt-1 text-xs text-slate-500">
              Direct-to-S3 upload (up to 2 GiB). Every upload goes into
              the Manager Recording Approvals queue — your playback +
              gallery visibility unlocks once the manager approves.
            </p>

            {/* Approval-gate status banner — makes the manager-review
                state immediately visible so the evaluator knows whether
                they can play back their recording yet, and surfaces
                revision notes for the re-upload flow. */}
            {data.recordingDocumentId && data.recordingApprovalStatus === 'PENDING_APPROVAL' && (
              <p className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-900">
                Awaiting manager approval. You'll be able to play back the
                recording here once approved.
              </p>
            )}
            {data.recordingDocumentId && data.recordingApprovalStatus === 'APPROVED' && (
              <p className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-900">
                Approved by manager — recording is now visible in the
                gallery and playable below.
              </p>
            )}
            {data.recordingApprovalStatus === 'REVISION_REQUESTED' && (
              <div className="mt-3 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-900">
                <p className="font-semibold">Manager requested a revision.</p>
                <p className="mt-1 whitespace-pre-wrap text-red-800">
                  {data.recordingRevisionNotes ?? '(no notes provided)'}
                </p>
                <p className="mt-2 text-[11px] text-red-700">
                  Upload a corrected recording below to re-submit — it goes
                  back to PENDING_APPROVAL for the manager to re-review.
                </p>
              </div>
            )}
            <div className="mt-3 space-y-2">
              <label className="block text-xs font-semibold text-slate-700">
                Project this recording pertains to
                <select
                  value={selectedProject}
                  onChange={(e) => setSelectedProject(e.target.value)}
                  disabled={readOnly}
                  className="mt-1 block w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                >
                  <option value="">General / no specific project</option>
                  {projectOptions.map((p) => (
                    <option key={p.projectId} value={p.projectId}>
                      {p.title}
                      {p.status ? ` — ${p.status.replaceAll('_', ' ')}` : ''}
                    </option>
                  ))}
                </select>
              </label>
              {!readOnly && (
                <RecordingUploader
                  presignEndpoint={`/api/v1/evaluator/evaluations/${id}/recording/presign-upload`}
                  initial={recordingDocId
                    ? { kind: 'ready', fileName: 'recording attached', documentId: recordingDocId }
                    : undefined}
                  onReady={(docId) => { void persistRecording(docId); }}
                  onClear={() => { setRecordingSavedAt(null); }}
                  helperText="Uploads bypass the backend and go directly to secure storage. Video/* only."
                />
              )}
              {readOnly && recordingDocId && (
                <p className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                  Recording attached. Preview / download it from the ERM
                  or Manager Recording Gallery.
                </p>
              )}
              {recordingErr && (
                <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                  {recordingErr}
                </p>
              )}
              {recordingSavedAt && (
                <p className="text-[11px] text-green-700">
                  Recording saved at {recordingSavedAt.toLocaleTimeString()}
                  {selectedProject
                    ? ' · linked to selected project'
                    : ' · not linked to a specific project'}
                </p>
              )}
            </div>
          </section>

          {/* Free text */}
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900">Feedback</h2>
            <p className="text-xs text-slate-500">
              Total chars across all 3 fields: <span className={
                feedbackChars >= 50 ? 'text-green-700' : 'text-red-700'
              }>{feedbackChars}</span> / 50 minimum
            </p>
            <div className="mt-3 space-y-3">
              <Field label="Strengths" hint="What's the intern excelling at? Be specific.">
                <textarea
                  value={strengths}
                  onChange={(e) => setStrengths(e.target.value)}
                  rows={4} maxLength={5000} disabled={readOnly}
                  className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </Field>
              <Field label="Areas for improvement" hint="Where should they focus next month?">
                <textarea
                  value={areas}
                  onChange={(e) => setAreas(e.target.value)}
                  rows={4} maxLength={5000} disabled={readOnly}
                  className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </Field>
              <Field label="General comments">
                <textarea
                  value={comments}
                  onChange={(e) => setComments(e.target.value)}
                  rows={3} maxLength={5000} disabled={readOnly}
                  className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </Field>
            </div>
          </section>

          {/* Recommendation */}
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900">
              {isFinal ? 'Overall Internship Recommendation' : 'Recommendation'}
            </h2>
            {isFinal && (
              <p className="mt-1 text-xs text-slate-500">
                This is the final verdict for the entire engagement. Selecting{' '}
                <span className="font-semibold text-green-700">REHIRE_ELIGIBLE</span>{' '}
                marks the intern for the rehire pipeline in ERM.
              </p>
            )}
            <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {recOptions.map((r) => (
                <label
                  key={r}
                  className={
                    'flex cursor-pointer items-center gap-2 rounded-md border p-2 text-sm ' +
                    (recommendation === r
                      ? 'border-brand-700 bg-brand-50'
                      : 'border-slate-200 bg-white hover:bg-slate-50')
                  }
                >
                  <input
                    type="radio"
                    name="recommendation"
                    checked={recommendation === r}
                    onChange={() => setRecommendation(r as RecommendationFinal)}
                    disabled={readOnly}
                  />
                  <span>{r.replaceAll('_', ' ')}</span>
                </label>
              ))}
            </div>
          </section>

          {isFinal && (
            <section className="rounded-lg border border-amber-200 bg-amber-50/40 p-5 shadow-sm">
              <h2 className="text-sm font-semibold text-amber-900">
                Final summary — what gets archived
              </h2>
              <p className="mt-1 text-xs text-amber-800">
                Once published, this evaluation links to the intern&apos;s exit
                record and surfaces on the rehire pipeline. The four rubric
                scores roll up into the engagement&apos;s overall score, and
                the &quot;Strengths&quot; + &quot;Areas for improvement&quot;
                narratives become the engagement&apos;s archived feedback.
                Period covered:{' '}
                <span className="font-semibold">
                  {data.periodStart ?? '—'} → {data.periodEnd ?? '—'}
                </span>
                .
              </p>
              <ul className="mt-2 list-disc space-y-0.5 pl-5 text-[11px] text-amber-900">
                <li>Use &quot;Comments&quot; to capture the overall narrative for HR records.</li>
                <li>Internal notes remain Evaluator-only and are never surfaced to the intern.</li>
                <li>The intern still acknowledges and may write a response.</li>
              </ul>
            </section>
          )}

          {/* Internal notes (Evaluator only) */}
          <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900">Internal notes (Evaluator only)</h2>
            <p className="text-xs text-slate-500">Never visible to the intern.</p>
            <textarea
              value={internalNotes}
              onChange={(e) => setInternalNotes(e.target.value)}
              rows={3} maxLength={5000} disabled={readOnly}
              className="mt-2 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </section>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}

          {!readOnly && (
            <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-white px-4 py-3">
              <div className="flex items-center gap-3 text-[11px] text-slate-500">
                {savedAt && (
                  <span className="text-green-700">
                    Saved at {savedAt.toLocaleTimeString()}
                  </span>
                )}
                {/* Local (sessionStorage) autosave indicator — separate
                    from the server-side "Save draft" affordance above.
                    Reads "Saving draft… / Saved 12s ago / Draft
                    restored" so the evaluator knows their typed work
                    survives a refresh even before they hit Save draft. */}
                <DraftAutosaveIndicator state={draftAutosave} />
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={saveDraft}
                  disabled={savingDraft}
                  className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                >
                  <Save className="h-3.5 w-3.5" />
                  {savingDraft ? 'Saving…' : 'Save draft'}
                </button>
                <button
                  type="button"
                  onClick={publish}
                  disabled={!canPublish || publishing}
                  title={canPublish ? 'Publish to intern + CC ERM/Manager/Trainer' : 'Need all 4 scores + recommendation + ≥50 chars feedback'}
                  className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
                >
                  <Send className="h-3.5 w-3.5" />
                  {publishing ? 'Publishing…' : 'Publish'}
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Preview column */}
        <aside className="space-y-3">
          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Intern sees:
            </p>
            <p className="mt-1 text-sm font-semibold text-slate-900">
              Your monthly evaluation is ready
            </p>
            <div className="mt-3 space-y-2 text-xs text-slate-700">
              <RubricBar label="Technical" value={technical} />
              <RubricBar label="Communication" value={communication} />
              <RubricBar label="Professionalism" value={professionalism} />
              <RubricBar label="Learning" value={learning} />
            </div>
            {recommendation && (
              <p className="mt-3 rounded-md bg-slate-100 px-2 py-1 text-[11px] font-semibold text-slate-700">
                Recommendation: {recommendation.replaceAll('_', ' ')}
              </p>
            )}
            {strengths && (
              <div className="mt-3">
                <p className="text-[10px] uppercase text-slate-500">Strengths</p>
                <p className="text-xs text-slate-700">{strengths}</p>
              </div>
            )}
            {areas && (
              <div className="mt-3">
                <p className="text-[10px] uppercase text-slate-500">Areas for improvement</p>
                <p className="text-xs text-slate-700">{areas}</p>
              </div>
            )}
            {comments && (
              <div className="mt-3">
                <p className="text-[10px] uppercase text-slate-500">Comments</p>
                <p className="text-xs text-slate-700">{comments}</p>
              </div>
            )}
          </div>
        </aside>
      </div>

      {scheduleDialogOpen && data.linkedProjectId && (
        <SchedulePostProjectDialog
          projectId={data.linkedProjectId}
          projectTitle={data.linkedProject?.title ?? null}
          onClose={() => setScheduleDialogOpen(false)}
          onScheduled={() => { setScheduleDialogOpen(false); void load(); }}
        />
      )}
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      {hint && <span className="ml-2 text-[10px] text-slate-500">{hint}</span>}
      <div className="mt-1">{children}</div>
    </label>
  );
}

function RubricBar({ label, value }: { label: string; value: number | null }) {
  return (
    <div>
      <p className="text-[10px] text-slate-500">{label}</p>
      <div className="mt-0.5 flex items-center gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <Star
            key={n}
            className={
              'h-3 w-3 ' +
              (value != null && n <= value
                ? 'fill-amber-400 text-amber-400'
                : 'text-slate-300')
            }
          />
        ))}
        <span className="ml-1 text-[10px] text-slate-700">{value ?? '—'}/5</span>
      </div>
    </div>
  );
}
