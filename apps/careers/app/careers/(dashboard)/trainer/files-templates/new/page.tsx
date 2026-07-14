'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Plus, ChevronLeft } from 'lucide-react';
import api from '@/lib/api';

export default function NewTemplatePage() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [technologyArea, setTechnologyArea] = useState('');
  const [description, setDescription] = useState('');
  const [instructionsMd, setInstructionsMd] = useState('');
  const [githubInstructionsMd, setGithubInstructionsMd] = useState('');
  const [learningObjectiveLabel, setLearningObjectiveLabel] = useState('');
  const [publishNow, setPublishNow] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!title.trim() || !technologyArea.trim() || !instructionsMd.trim()) {
      setErr('Title, technology area, and instructions are required.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<{ id: string }>(
        '/api/v1/trainer/project-templates',
        {
          title: title.trim(),
          technologyArea: technologyArea.trim(),
          description: description.trim() || null,
          instructionsMd,
          githubInstructionsMd: githubInstructionsMd.trim() || null,
          learningObjectiveLabel: learningObjectiveLabel.trim() || null,
          publish: publishNow,
        },
      );
      router.push(`/careers/trainer/files-templates/${res.data.id}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to create');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/trainer/files-templates" className="inline-flex items-center gap-1 hover:text-slate-700">
          <ChevronLeft className="h-3 w-3" /> Project Templates
        </Link>
      </p>
      <h1 className="text-xl font-semibold text-slate-900">New project template</h1>

      <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <Field label="Title*">
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Technology area*">
          <input value={technologyArea} onChange={(e) => setTechnologyArea(e.target.value)} maxLength={100}
            placeholder="e.g. Java Full Stack, AI/ML, Data Engineering"
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Description (optional)">
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label={`Instructions* (Markdown — ${instructionsMd.length})`}>
          <textarea value={instructionsMd} onChange={(e) => setInstructionsMd(e.target.value)}
            rows={12} placeholder="What the intern needs to deliver, scope, acceptance criteria, references, etc."
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
        <Field label={`GitHub setup (optional — ${githubInstructionsMd.length})`}>
          <textarea value={githubInstructionsMd} onChange={(e) => setGithubInstructionsMd(e.target.value)} rows={5}
            placeholder="Repo URL, branch convention, PR setup, etc."
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
        <Field label="Learning objective label (recommended)">
          <input value={learningObjectiveLabel} onChange={(e) => setLearningObjectiveLabel(e.target.value)} maxLength={300}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={publishNow} onChange={(e) => setPublishNow(e.target.checked)} />
          <span>Publish immediately (otherwise saved as draft)</span>
        </label>
        {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
        <div className="flex justify-end gap-2">
          <Link href="/careers/trainer/files-templates"
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">Cancel</Link>
          <button type="button" onClick={submit} disabled={submitting}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            <Plus className="h-4 w-4" /> {submitting ? 'Creating…' : 'Create template'}
          </button>
        </div>
      </div>
    </div>
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
