'use client';

// useSearchParams forces this auth-gated dashboard page out of static
// prerendering at build time; wrap the inner reader in <Suspense>.

import { Suspense, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import { ChevronLeft, ChevronRight, FileText, Upload, X } from 'lucide-react';

type InternRow = {
  internLifecycleId: string;
  employeeId: string | null;
  fullName: string | null;
  email: string | null;
  technologyTitle: string | null;
};

type SlotStatus = {
  monthYear: string;
  slot1Taken: boolean;
  slot1Title: string | null;
  slot1ProjectId: string | null;
  slot2Taken: boolean;
  slot2Title: string | null;
  slot2ProjectId: string | null;
  bothTaken: boolean;
  backdatingRequired: boolean;
};

type TemplateRow = {
  id: string;
  title: string;
  technologyArea: string;
  description: string | null;
  published: boolean;
  usageCount: number;
};

type TemplateDetail = {
  id: string;
  title: string;
  technologyArea: string;
  description: string | null;
  instructionsMd: string;
  githubInstructionsMd: string | null;
  learningObjectiveLabel: string | null;
};

export default function AssignProjectPage() {
  return (
    <Suspense fallback={<Loading />}>
      <AssignProjectPageInner />
    </Suspense>
  );
}

function Loading() {
  return (
    <div className="mx-auto max-w-4xl space-y-3 p-6">
      <div className="h-8 w-72 animate-pulse rounded bg-slate-100" />
      <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
    </div>
  );
}

function currentMonthYear(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function AssignProjectPageInner() {
  const router = useRouter();
  const sp = useSearchParams();
  const prefillIntern = sp?.get('internId') ?? '';
  const prefillMonth = sp?.get('month') ?? currentMonthYear();

  const [step, setStep] = useState(1);

  // Step 1
  const [interns, setInterns] = useState<InternRow[]>([]);
  const [internLifecycleId, setInternLifecycleId] = useState(prefillIntern);
  const [monthYear, setMonthYear] = useState(prefillMonth);
  const [projectNumber, setProjectNumber] = useState<1 | 2 | null>(null);
  const [slot, setSlot] = useState<SlotStatus | null>(null);

  // Step 2
  const [title, setTitle] = useState('');
  const [technologyArea, setTechnologyArea] = useState('');
  const [secondaryTag, setSecondaryTag] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [learningObjectiveLabel, setLearningObjectiveLabel] = useState('');
  const [usesGithub, setUsesGithub] = useState(false);

  // Step 3
  const [instructions, setInstructions] = useState('');
  const [githubInstructions, setGithubInstructions] = useState('');
  const [notifyStakeholders, setNotifyStakeholders] = useState(true);
  const [projectFile, setProjectFile] = useState<File | null>(null);
  // Optional structured repo URL — when set, the publish call follows up
  // with POST /api/v1/projects/catalog/{id}/repository so the trainer
  // doesn't have to re-link from the active-intern detail page later.
  const [repositoryUrl, setRepositoryUrl] = useState('');
  const [repositoryName, setRepositoryName] = useState('');

  // Backdating
  const [backdateAuthorizedByName, setBackdateAuthorizedByName] = useState('');
  const [backdateReason, setBackdateReason] = useState('');

  // Template instantiation
  const [showTemplatePanel, setShowTemplatePanel] = useState(false);
  const [projectTemplateId, setProjectTemplateId] = useState<string | null>(null);

  // Submission
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Load interns
  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<{ items: InternRow[] }>(
          '/api/v1/trainer/active-interns?pageSize=100',
        );
        setInterns(res.data.items ?? []);
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load interns');
      }
    })();
  }, []);

  // Slot status whenever (intern, month) changes
  useEffect(() => {
    if (!internLifecycleId || !monthYear) { setSlot(null); return; }
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<SlotStatus>(
          `/api/v1/trainer/projects/slot-status?internLifecycleId=${internLifecycleId}&monthYear=${monthYear}`,
        );
        if (!cancelled) {
          setSlot(res.data);
          setErr(null);
        }
      } catch (e) {
        const ax = e as { response?: { data?: { error?: string } }; message?: string };
        if (!cancelled) {
          // Clear stale slot so the Next gate stays disabled — better than
          // letting a previous intern's slot fall through into the new
          // selection's flow.
          setSlot(null);
          setErr(ax.response?.data?.error ?? ax.message ?? 'Slot status failed');
        }
      }
    })();
    return () => { cancelled = true; };
  }, [internLifecycleId, monthYear]);

  // Auto-fill technology area from intern's job posting tech
  useEffect(() => {
    if (!internLifecycleId || technologyArea) return;
    const intern = interns.find((i) => i.internLifecycleId === internLifecycleId);
    if (intern?.technologyTitle) setTechnologyArea(intern.technologyTitle);
  }, [internLifecycleId, interns, technologyArea]);

  const selectedIntern = useMemo(
    () => interns.find((i) => i.internLifecycleId === internLifecycleId) ?? null,
    [interns, internLifecycleId],
  );

  const isBackdated = slot?.backdatingRequired ?? false;

  const canStep2 = !!(internLifecycleId && monthYear && projectNumber && slot
    && !slot.bothTaken
    && !(projectNumber === 1 && slot.slot1Taken)
    && !(projectNumber === 2 && slot.slot2Taken));

  const canStep3 = canStep2 && !!(title.trim() && technologyArea.trim() && dueDate);

  const canStep4 = canStep3 && !!instructions.trim()
    && (!usesGithub || !!githubInstructions.trim())
    && (!isBackdated
        || (backdateAuthorizedByName.trim() && backdateReason.trim().length >= 30));

  function applyTemplate(t: TemplateDetail) {
    if (!title) setTitle(t.title);
    if (!technologyArea) setTechnologyArea(t.technologyArea);
    if (!instructions) setInstructions(t.instructionsMd);
    if (t.githubInstructionsMd) {
      setUsesGithub(true);
      if (!githubInstructions) setGithubInstructions(t.githubInstructionsMd);
    }
    if (t.learningObjectiveLabel && !learningObjectiveLabel) {
      setLearningObjectiveLabel(t.learningObjectiveLabel);
    }
    setProjectTemplateId(t.id);
    setShowTemplatePanel(false);
  }

  async function publish() {
    setSubmitting(true);
    setErr(null);
    try {
      const body = {
        internLifecycleId,
        monthYear,
        projectNumber,
        title: title.trim(),
        technologyArea: technologyArea.trim(),
        secondaryTag: secondaryTag.trim() || null,
        instructions,
        githubInstructions: usesGithub ? githubInstructions : null,
        usesGithub,
        dueDate,
        learningObjectiveLabel: learningObjectiveLabel.trim() || null,
        i983ObjectiveIndex: null,
        notifyStakeholdersInternal: notifyStakeholders,
        backdateAuthorizedByName: isBackdated ? backdateAuthorizedByName.trim() : null,
        backdateReason: isBackdated ? backdateReason.trim() : null,
        projectTemplateId,
      };
      const res = await api.post<{ id: string }>(
        '/api/v1/trainer/projects', body,
      );
      const projectId = res.data.id;
      if (projectFile) {
        const fd = new FormData();
        fd.append('file', projectFile);
        try {
          await api.post(`/api/v1/trainer/projects/${projectId}/file`, fd);
        } catch (fileErr) {
          const ax = fileErr as { response?: { data?: { error?: string } } };
          alert('Project assigned, but file upload failed: '
            + (ax.response?.data?.error ?? 'unknown'));
        }
      }
      // Optional repo link — best-effort. Backend gates Grant-Repo-Access
      // on a ProjectRepositoryLink existing for the project; doing it here
      // saves the trainer a second step on the active-intern detail page.
      const repoUrlTrimmed = repositoryUrl.trim();
      if (repoUrlTrimmed) {
        const name = repositoryName.trim()
          || (parseOwnerRepo(repoUrlTrimmed) ?? '');
        try {
          await api.post(`/api/v1/projects/catalog/${projectId}/repository`, {
            repositoryName: name,
            repositoryUrl: repoUrlTrimmed,
          });
        } catch (repoErr) {
          const ax = repoErr as { response?: { data?: { error?: string } } };
          alert('Project assigned, but repository link failed: '
            + (ax.response?.data?.error ?? 'unknown')
            + '. You can link it from the active-intern detail page.');
        }
      }
      router.push(`/careers/trainer/active-interns/${internLifecycleId}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to assign');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-6">
      <header className="flex items-center justify-between">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/trainer" className="hover:text-slate-700">
              ← Trainer dashboard
            </Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Assign Project</h1>
          <p className="text-xs text-slate-500">
            Doc §6 wizard — pick intern + month + slot, fill the 11 doc §7 fields,
            publish to fan out PROJECT_ASSIGNED.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowTemplatePanel(true)}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          <FileText className="h-3.5 w-3.5" /> Use Template
        </button>
      </header>

      <StepIndicator step={step} />

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {step === 1 && (
        <Step1
          interns={interns}
          internLifecycleId={internLifecycleId}
          setInternLifecycleId={setInternLifecycleId}
          monthYear={monthYear}
          setMonthYear={setMonthYear}
          projectNumber={projectNumber}
          setProjectNumber={setProjectNumber}
          slot={slot}
        />
      )}
      {step === 2 && (
        <Step2
          title={title} setTitle={setTitle}
          technologyArea={technologyArea} setTechnologyArea={setTechnologyArea}
          secondaryTag={secondaryTag} setSecondaryTag={setSecondaryTag}
          dueDate={dueDate} setDueDate={setDueDate}
          learningObjectiveLabel={learningObjectiveLabel}
          setLearningObjectiveLabel={setLearningObjectiveLabel}
          usesGithub={usesGithub} setUsesGithub={setUsesGithub}
        />
      )}
      {step === 3 && (
        <Step3
          instructions={instructions} setInstructions={setInstructions}
          usesGithub={usesGithub}
          githubInstructions={githubInstructions}
          setGithubInstructions={setGithubInstructions}
          notifyStakeholders={notifyStakeholders}
          setNotifyStakeholders={setNotifyStakeholders}
          projectFile={projectFile} setProjectFile={setProjectFile}
          repositoryUrl={repositoryUrl} setRepositoryUrl={setRepositoryUrl}
          repositoryName={repositoryName} setRepositoryName={setRepositoryName}
          isBackdated={isBackdated}
          backdateAuthorizedByName={backdateAuthorizedByName}
          setBackdateAuthorizedByName={setBackdateAuthorizedByName}
          backdateReason={backdateReason} setBackdateReason={setBackdateReason}
        />
      )}
      {step === 4 && (
        <Step4
          intern={selectedIntern} monthYear={monthYear}
          projectNumber={projectNumber} title={title}
          technologyArea={technologyArea} secondaryTag={secondaryTag}
          dueDate={dueDate} learningObjectiveLabel={learningObjectiveLabel}
          usesGithub={usesGithub}
          instructions={instructions} githubInstructions={githubInstructions}
          notifyStakeholders={notifyStakeholders}
          projectFile={projectFile}
          isBackdated={isBackdated}
          backdateAuthorizedByName={backdateAuthorizedByName}
          backdateReason={backdateReason}
          projectTemplateId={projectTemplateId}
        />
      )}

      <div className="flex items-center justify-between border-t border-slate-200 pt-3">
        <button
          type="button"
          onClick={() => setStep((s) => Math.max(1, s - 1))}
          disabled={step === 1}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-50"
        >
          <ChevronLeft className="h-4 w-4" /> Back
        </button>
        {step < 4 ? (
          <button
            type="button"
            onClick={() => setStep((s) => s + 1)}
            disabled={(step === 1 && !canStep2)
              || (step === 2 && !canStep3)
              || (step === 3 && !canStep4)}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
          >
            Next <ChevronRight className="h-4 w-4" />
          </button>
        ) : (
          <button
            type="button"
            onClick={publish}
            disabled={submitting || !canStep4}
            className="rounded-md bg-green-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-green-800 disabled:bg-slate-300"
          >
            {submitting ? 'Publishing…' : 'Publish + Notify'}
          </button>
        )}
      </div>

      {showTemplatePanel && (
        <TemplatePicker
          onClose={() => setShowTemplatePanel(false)}
          onPick={applyTemplate}
        />
      )}
    </div>
  );
}

function StepIndicator({ step }: { step: number }) {
  const labels = ['Intern + month', 'Details', 'Instructions + file', 'Review + publish'];
  return (
    <ol className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
      {labels.map((l, i) => {
        const n = i + 1;
        const active = step === n;
        const done = step > n;
        return (
          <li key={l} className="flex items-center gap-1">
            <span className={
              'inline-flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-semibold ' +
              (done ? 'bg-green-600 text-white'
                : active ? 'bg-brand-700 text-white' : 'bg-slate-200 text-slate-600')
            }>{done ? '✓' : n}</span>
            <span className={active ? 'font-medium text-slate-900' : ''}>{l}</span>
            {n < labels.length && <span className="text-slate-300">·</span>}
          </li>
        );
      })}
    </ol>
  );
}

function Step1({
  interns, internLifecycleId, setInternLifecycleId, monthYear, setMonthYear,
  projectNumber, setProjectNumber, slot,
}: {
  interns: InternRow[];
  internLifecycleId: string;
  setInternLifecycleId: (v: string) => void;
  monthYear: string;
  setMonthYear: (v: string) => void;
  projectNumber: 1 | 2 | null;
  setProjectNumber: (v: 1 | 2 | null) => void;
  slot: SlotStatus | null;
}) {
  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <label className="block text-sm">
        <span className="text-xs font-semibold text-slate-700">Intern</span>
        <select
          value={internLifecycleId}
          onChange={(e) => setInternLifecycleId(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5"
        >
          <option value="">— pick an intern —</option>
          {interns.map((i) => (
            <option key={i.internLifecycleId} value={i.internLifecycleId}>
              {i.fullName} {i.employeeId ? `(${i.employeeId})` : ''}
            </option>
          ))}
        </select>
      </label>

      <label className="block text-sm">
        <span className="text-xs font-semibold text-slate-700">Month (YYYY-MM)</span>
        <input
          type="month"
          value={monthYear}
          onChange={(e) => setMonthYear(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-200 px-2 py-1.5"
        />
        {slot?.backdatingRequired && (
          <p className="mt-1 text-[11px] text-amber-700">
            Past month — backdating fields will appear in step 3.
          </p>
        )}
      </label>

      <div>
        <p className="text-xs font-semibold text-slate-700">Project slot</p>
        {slot ? (
          <div className="mt-2 space-y-2 text-sm">
            <SlotRadio
              n={1} taken={slot.slot1Taken} title={slot.slot1Title}
              checked={projectNumber === 1}
              onChange={() => setProjectNumber(1)}
            />
            <SlotRadio
              n={2} taken={slot.slot2Taken} title={slot.slot2Title}
              checked={projectNumber === 2}
              onChange={() => setProjectNumber(2)}
            />
            {slot.bothTaken && (
              <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                Both slots assigned for {slot.monthYear}. Cancel an existing project first or pick a different month.
              </p>
            )}
            {!slot.bothTaken && (slot.slot1Taken || slot.slot2Taken) && (
              <p className="rounded-md border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800">
                Project {slot.slot1Taken ? '1' : '2'} already assigned for {slot.monthYear}. Continue with Project {slot.slot1Taken ? '2' : '1'}?
              </p>
            )}
          </div>
        ) : (
          <p className="mt-2 text-xs text-slate-500">
            Pick an intern + month to check slot availability.
          </p>
        )}
      </div>
    </section>
  );
}

function SlotRadio({ n, taken, title, checked, onChange }: {
  n: 1 | 2; taken: boolean; title: string | null;
  checked: boolean; onChange: () => void;
}) {
  return (
    <label className={
      'flex cursor-pointer items-center justify-between rounded-md border px-3 py-2 ' +
      (taken ? 'cursor-not-allowed border-slate-200 bg-slate-50 opacity-60'
        : checked ? 'border-brand-700 bg-brand-50' : 'border-slate-200')
    }>
      <span className="flex items-center gap-2">
        <input
          type="radio"
          name="projectNumber"
          checked={checked}
          disabled={taken}
          onChange={onChange}
        />
        <span className="text-sm font-medium text-slate-900">Project {n}</span>
        {taken && <span className="text-xs text-slate-500">— assigned: {title ?? 'unknown'}</span>}
      </span>
    </label>
  );
}

function Step2(p: {
  title: string; setTitle: (v: string) => void;
  technologyArea: string; setTechnologyArea: (v: string) => void;
  secondaryTag: string; setSecondaryTag: (v: string) => void;
  dueDate: string; setDueDate: (v: string) => void;
  learningObjectiveLabel: string; setLearningObjectiveLabel: (v: string) => void;
  usesGithub: boolean; setUsesGithub: (v: boolean) => void;
}) {
  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <Field label="Project title*">
        <input value={p.title} onChange={(e) => p.setTitle(e.target.value)} maxLength={200}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Technology area*">
          <input value={p.technologyArea} onChange={(e) => p.setTechnologyArea(e.target.value)} maxLength={100}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Secondary tag (optional)">
          <input value={p.secondaryTag} onChange={(e) => p.setSecondaryTag(e.target.value)} maxLength={100}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Due date*">
          <input type="date" value={p.dueDate} onChange={(e) => p.setDueDate(e.target.value)}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Learning objective label (recommended)">
          <input value={p.learningObjectiveLabel} onChange={(e) => p.setLearningObjectiveLabel(e.target.value)} maxLength={300}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={p.usesGithub} onChange={(e) => p.setUsesGithub(e.target.checked)} />
        <span>Uses GitHub (instructions section appears in step 3)</span>
      </label>
    </section>
  );
}

function Step3(p: {
  instructions: string; setInstructions: (v: string) => void;
  usesGithub: boolean;
  githubInstructions: string; setGithubInstructions: (v: string) => void;
  notifyStakeholders: boolean; setNotifyStakeholders: (v: boolean) => void;
  projectFile: File | null; setProjectFile: (f: File | null) => void;
  repositoryUrl: string; setRepositoryUrl: (v: string) => void;
  repositoryName: string; setRepositoryName: (v: string) => void;
  isBackdated: boolean;
  backdateAuthorizedByName: string; setBackdateAuthorizedByName: (v: string) => void;
  backdateReason: string; setBackdateReason: (v: string) => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <Field label={`Instructions* (Markdown — ${p.instructions.length}/20000)`}>
        <textarea value={p.instructions} onChange={(e) => p.setInstructions(e.target.value)}
          rows={10} maxLength={20000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
      </Field>

      {p.usesGithub && (
        <Field label={`GitHub setup instructions* (${p.githubInstructions.length}/5000)`}>
          <textarea value={p.githubInstructions} onChange={(e) => p.setGithubInstructions(e.target.value)}
            rows={5} maxLength={5000}
            placeholder="Repo URL, branch convention, PR + reviewer setup, etc."
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
      )}

      <Field label="Project file (optional)">
        <input
          ref={fileRef}
          type="file"
          accept="application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/zip"
          onChange={(e) => p.setProjectFile(e.target.files?.[0] ?? null)}
          className="hidden"
        />
        {p.projectFile ? (
          <div className="flex items-center justify-between rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm">
            <span>{p.projectFile.name} ({Math.round(p.projectFile.size / 1024)} KB)</span>
            <button type="button" onClick={() => p.setProjectFile(null)} className="text-xs text-red-700">Remove</button>
          </div>
        ) : (
          <button type="button" onClick={() => fileRef.current?.click()}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-3 py-1.5 text-xs">
            <Upload className="h-3.5 w-3.5" /> Attach PDF / DOCX / ZIP (max 50 MB)
          </button>
        )}
      </Field>

      {/* Optional structured repository URL. When provided, publish() will
          POST to /api/v1/projects/catalog/{id}/repository after the project
          is created so the trainer can grant repo access immediately
          afterward without another linking step. Validated to the GitHub
          repo URL shape; bad URL disables the publish call without
          breaking it. */}
      <div className="rounded-md border border-brand-200 bg-brand-50/40 p-3">
        <p className="text-xs font-semibold text-brand-900">
          Repository link <span className="font-normal text-slate-500">(optional — enables "Grant repo access")</span>
        </p>
        <p className="mt-0.5 text-[11px] text-slate-600">
          Attach the GitHub repository so you can grant the intern access
          right after publishing. You can also link it later from the
          intern's detail page.
        </p>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">
          <Field label="Repository URL">
            <input
              type="url"
              value={p.repositoryUrl}
              onChange={(e) => p.setRepositoryUrl(e.target.value)}
              placeholder="https://github.com/owner/repo"
              className={
                'w-full rounded-md border px-2 py-1.5 text-sm '
                + (p.repositoryUrl && !isAssignWizardGithubRepoUrl(p.repositoryUrl)
                    ? 'border-red-400' : 'border-slate-200')
              }
            />
            {p.repositoryUrl && !isAssignWizardGithubRepoUrl(p.repositoryUrl) && (
              <p className="mt-1 text-[11px] text-red-700">
                Must be https://github.com/owner/repo
              </p>
            )}
          </Field>
          <Field label="Display name (optional)">
            <input
              type="text"
              value={p.repositoryName}
              onChange={(e) => p.setRepositoryName(e.target.value)}
              placeholder={parseOwnerRepo(p.repositoryUrl) || 'owner/repo'}
              className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
          </Field>
        </div>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={p.notifyStakeholders}
          onChange={(e) => p.setNotifyStakeholders(e.target.checked)} />
        <span>Notify evaluator, manager, and ERM on publish</span>
      </label>

      {p.isBackdated && (
        <div className="space-y-3 rounded-md border border-amber-200 bg-amber-50 p-3">
          <p className="text-xs font-semibold text-amber-900">
            Backdating — required fields
          </p>
          <Field label="Authorized by (name of approving Manager or ERM)*">
            <input value={p.backdateAuthorizedByName}
              onChange={(e) => p.setBackdateAuthorizedByName(e.target.value)}
              maxLength={200}
              className="w-full rounded-md border border-amber-300 px-2 py-1.5 text-sm" />
          </Field>
          <Field label={`Reason for backdating* (min 30, max 1000 — ${p.backdateReason.length})`}>
            <textarea value={p.backdateReason}
              onChange={(e) => p.setBackdateReason(e.target.value)}
              rows={3} maxLength={1000}
              className="w-full rounded-md border border-amber-300 px-2 py-1.5 text-sm" />
          </Field>
        </div>
      )}
    </section>
  );
}

function Step4(p: {
  intern: InternRow | null; monthYear: string; projectNumber: 1 | 2 | null;
  title: string; technologyArea: string; secondaryTag: string; dueDate: string;
  learningObjectiveLabel: string; usesGithub: boolean;
  instructions: string; githubInstructions: string;
  notifyStakeholders: boolean; projectFile: File | null;
  isBackdated: boolean; backdateAuthorizedByName: string; backdateReason: string;
  projectTemplateId: string | null;
}) {
  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h3 className="text-sm font-semibold text-slate-900">Review</h3>
      <Row k="Intern" v={`${p.intern?.fullName ?? '—'} (${p.intern?.employeeId ?? '—'})`} />
      <Row k="Slot" v={`Project ${p.projectNumber} · ${p.monthYear}`} />
      <Row k="Title" v={p.title} />
      <Row k="Technology" v={`${p.technologyArea}${p.secondaryTag ? ' / ' + p.secondaryTag : ''}`} />
      <Row k="Due date" v={p.dueDate} />
      {p.learningObjectiveLabel && <Row k="Learning objective" v={p.learningObjectiveLabel} />}
      {p.projectTemplateId && <Row k="Template" v={p.projectTemplateId} />}
      {p.projectFile && <Row k="Attachment" v={p.projectFile.name} />}
      <Row k="Notify stakeholders" v={p.notifyStakeholders ? 'Yes' : 'No (intern only)'} />
      {p.isBackdated && (
        <>
          <Row k="Backdate authorizer" v={p.backdateAuthorizedByName} />
          <Row k="Backdate reason" v={p.backdateReason} />
        </>
      )}
      <details className="rounded-md border border-slate-200 bg-slate-50 p-2">
        <summary className="cursor-pointer text-xs text-slate-700">Show instructions</summary>
        <pre className="mt-2 whitespace-pre-wrap font-mono text-[11px] text-slate-700">{p.instructions}</pre>
        {p.usesGithub && (
          <>
            <p className="mt-2 text-xs font-semibold text-slate-700">GitHub setup</p>
            <pre className="mt-1 whitespace-pre-wrap font-mono text-[11px] text-slate-700">{p.githubInstructions}</pre>
          </>
        )}
      </details>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function Row({ k, v }: { k: string; v: string | number | null | undefined }) {
  return (
    <div className="flex justify-between border-b border-slate-100 py-1 text-sm">
      <span className="text-slate-500">{k}</span>
      <span className="text-right text-slate-800">{v ?? '—'}</span>
    </div>
  );
}

/** GitHub repo URL: https://github.com/owner/repo (optional .git / trailing /). */
function isAssignWizardGithubRepoUrl(s: string): boolean {
  return /^https?:\/\/github\.com\/[\w.-]+\/[\w.-]+?(?:\.git)?\/?$/i.test(s.trim());
}

function parseOwnerRepo(s: string): string {
  const m = /^https?:\/\/github\.com\/([\w.-]+)\/([\w.-]+?)(?:\.git)?\/?$/i.exec(s.trim());
  if (!m) return '';
  return `${m[1]}/${m[2]}`;
}

function TemplatePicker({ onClose, onPick }: {
  onClose: () => void; onPick: (t: TemplateDetail) => void;
}) {
  const [rows, setRows] = useState<TemplateRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [picking, setPicking] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<TemplateRow[]>(
          '/api/v1/trainer/project-templates/for-wizard',
        );
        setRows(res.data ?? []);
      } catch {
        setRows([]);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  async function pick(id: string) {
    setPicking(id);
    try {
      const res = await api.get<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}`,
      );
      onPick(res.data);
    } finally {
      setPicking(null);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-2xl rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">Pick a template</h3>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="max-h-[65vh] overflow-y-auto p-4">
          {loading ? (
            <div className="h-32 animate-pulse rounded bg-slate-100" />
          ) : rows.length === 0 ? (
            <p className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              No published templates yet. <Link href="/careers/trainer/files-templates/new" className="font-medium underline">Create one</Link>.
            </p>
          ) : (
            <ul className="divide-y divide-slate-100 rounded-md border border-slate-200">
              {rows.map((t) => (
                <li key={t.id} className="flex items-start justify-between gap-3 px-3 py-2">
                  <div>
                    <p className="text-sm font-medium text-slate-900">{t.title}</p>
                    <p className="text-[11px] text-slate-500">
                      {t.technologyArea} · used {t.usageCount} time{t.usageCount === 1 ? '' : 's'}
                    </p>
                    {t.description && (
                      <p className="mt-1 text-xs text-slate-600 line-clamp-2">{t.description}</p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => pick(t.id)}
                    disabled={picking === t.id}
                    className="rounded-md bg-brand-700 px-3 py-1 text-xs font-semibold text-white"
                  >
                    {picking === t.id ? 'Loading…' : 'Use'}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
