'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft, Copy, Eye, EyeOff, Archive, RotateCcw, Save, Upload, Trash2 } from 'lucide-react';
import api from '@/lib/api';

type Attachment = {
  documentId: string;
  fileName: string;
  mimeType: string;
  fileSize: number | null;
};

type TemplateDetail = {
  id: string;
  title: string;
  technologyArea: string;
  description: string | null;
  instructionsMd: string;
  githubInstructionsMd: string | null;
  learningObjectiveLabel: string | null;
  published: boolean;
  publishedAt: string | null;
  usageCount: number;
  archived: boolean;
  archivedAt: string | null;
  createdById: string | null;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  attachments: Attachment[];
};

export default function TemplateDetailPage() {
  // useParams (next/navigation) — Next 14.2 delivers params as a plain
  // object, not a Promise; React's use() rejects non-thenables with #438.
  const params = useParams<{ id: string }>();
  const id = params?.id ?? '';
  const [t, setT] = useState<TemplateDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Edit buffer
  const [title, setTitle] = useState('');
  const [technologyArea, setTechnologyArea] = useState('');
  const [description, setDescription] = useState('');
  const [instructionsMd, setInstructionsMd] = useState('');
  const [githubInstructionsMd, setGithubInstructionsMd] = useState('');
  const [learningObjectiveLabel, setLearningObjectiveLabel] = useState('');

  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<TemplateDetail>(`/api/v1/trainer/project-templates/${id}`);
      setT(res.data);
      setTitle(res.data.title);
      setTechnologyArea(res.data.technologyArea);
      setDescription(res.data.description ?? '');
      setInstructionsMd(res.data.instructionsMd);
      setGithubInstructionsMd(res.data.githubInstructionsMd ?? '');
      setLearningObjectiveLabel(res.data.learningObjectiveLabel ?? '');
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function save() {
    setBusy(true);
    try {
      const res = await api.put<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}`,
        {
          title: title.trim(),
          technologyArea: technologyArea.trim(),
          description: description.trim() || null,
          instructionsMd,
          githubInstructionsMd: githubInstructionsMd.trim() || null,
          learningObjectiveLabel: learningObjectiveLabel.trim() || null,
        },
      );
      setT(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Save failed');
    } finally {
      setBusy(false);
    }
  }

  async function action(endpoint: string) {
    setBusy(true);
    try {
      const res = await api.post<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}/${endpoint}`,
      );
      setT(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? `${endpoint} failed`);
    } finally {
      setBusy(false);
    }
  }

  async function duplicate() {
    setBusy(true);
    try {
      const res = await api.post<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}/duplicate`,
      );
      window.location.href = `/careers/trainer/files-templates/${res.data.id}`;
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Duplicate failed');
    } finally {
      setBusy(false);
    }
  }

  async function attach(file: File) {
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await api.post<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}/attachments`, fd,
      );
      setT(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Attach failed');
    } finally {
      setBusy(false);
    }
  }

  async function detach(documentId: string) {
    if (!confirm('Remove this attachment?')) return;
    setBusy(true);
    try {
      const res = await api.delete<TemplateDetail>(
        `/api/v1/trainer/project-templates/${id}/attachments/${documentId}`,
      );
      setT(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Detach failed');
    } finally {
      setBusy(false);
    }
  }

  if (!t) {
    return (
      <div className="mx-auto max-w-3xl space-y-3 p-6">
        {err ? (
          <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
        ) : (
          <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
        )}
      </div>
    );
  }

  const dirty = title !== t.title
    || technologyArea !== t.technologyArea
    || description !== (t.description ?? '')
    || instructionsMd !== t.instructionsMd
    || githubInstructionsMd !== (t.githubInstructionsMd ?? '')
    || learningObjectiveLabel !== (t.learningObjectiveLabel ?? '');

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/trainer/files-templates" className="inline-flex items-center gap-1 hover:text-slate-700">
          <ChevronLeft className="h-3 w-3" /> Project Templates
        </Link>
      </p>

      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">{t.title}</h1>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full bg-slate-100 px-2 py-0.5">{t.technologyArea}</span>
            {t.archived ? (
              <span className="rounded-full bg-slate-200 px-2 py-0.5 font-semibold text-slate-700">Archived</span>
            ) : t.published ? (
              <span className="rounded-full bg-green-100 px-2 py-0.5 font-semibold text-green-800">Published</span>
            ) : (
              <span className="rounded-full bg-amber-100 px-2 py-0.5 font-semibold text-amber-800">Draft</span>
            )}
            <span className="text-slate-500">Used {t.usageCount} time{t.usageCount === 1 ? '' : 's'}</span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1">
          {!t.archived && (
            t.published ? (
              <button type="button" onClick={() => action('unpublish')} disabled={busy}
                className="inline-flex items-center gap-1 rounded-md border border-amber-300 px-2 py-1 text-xs font-medium text-amber-800 hover:bg-amber-50">
                <EyeOff className="h-3 w-3" /> Unpublish
              </button>
            ) : (
              <button type="button" onClick={() => action('publish')} disabled={busy}
                className="inline-flex items-center gap-1 rounded-md bg-green-700 px-2 py-1 text-xs font-semibold text-white hover:bg-green-800">
                <Eye className="h-3 w-3" /> Publish
              </button>
            )
          )}
          <button type="button" onClick={duplicate} disabled={busy}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <Copy className="h-3 w-3" /> Duplicate
          </button>
          {t.archived ? (
            <button type="button" onClick={() => action('unarchive')} disabled={busy}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
              <RotateCcw className="h-3 w-3" /> Unarchive
            </button>
          ) : (
            <button type="button" onClick={() => action('archive')} disabled={busy}
              className="inline-flex items-center gap-1 rounded-md border border-red-300 px-2 py-1 text-xs font-medium text-red-800 hover:bg-red-50">
              <Archive className="h-3 w-3" /> Archive
            </button>
          )}
        </div>
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-900">Edit</h3>
        <Field label="Title">
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Technology area">
          <input value={technologyArea} onChange={(e) => setTechnologyArea(e.target.value)} maxLength={100}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label="Description">
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <Field label={`Instructions (Markdown — ${instructionsMd.length})`}>
          <textarea value={instructionsMd} onChange={(e) => setInstructionsMd(e.target.value)} rows={12}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
        <Field label={`GitHub setup (${githubInstructionsMd.length})`}>
          <textarea value={githubInstructionsMd} onChange={(e) => setGithubInstructionsMd(e.target.value)} rows={5}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 font-mono text-xs" />
        </Field>
        <Field label="Learning objective label">
          <input value={learningObjectiveLabel} onChange={(e) => setLearningObjectiveLabel(e.target.value)} maxLength={300}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
        </Field>
        <div className="flex justify-end">
          <button type="button" onClick={save} disabled={!dirty || busy}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            <Save className="h-3.5 w-3.5" /> {busy ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </section>

      <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-900">Attachments ({t.attachments.length})</h3>
          <input ref={fileRef} type="file"
            accept="application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/zip"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void attach(f); e.target.value = ''; }}
            className="hidden" />
          <button type="button" onClick={() => fileRef.current?.click()} disabled={busy}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700">
            <Upload className="h-3 w-3" /> Attach file
          </button>
        </div>
        {t.attachments.length === 0 ? (
          <p className="text-xs text-slate-500">No attachments yet.</p>
        ) : (
          <ul className="divide-y divide-slate-100 rounded-md border border-slate-200">
            {t.attachments.map((a) => (
              <li key={a.documentId} className="flex items-center justify-between px-3 py-2 text-sm">
                <div>
                  <p className="font-medium text-slate-900">{a.fileName}</p>
                  <p className="text-[10px] text-slate-500">
                    {a.mimeType}{a.fileSize ? ` · ${Math.round(a.fileSize / 1024)} KB` : ''}
                  </p>
                </div>
                <button type="button" onClick={() => detach(a.documentId)} disabled={busy}
                  className="inline-flex items-center gap-1 rounded-md border border-red-200 px-2 py-1 text-xs text-red-800">
                  <Trash2 className="h-3 w-3" /> Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm text-xs text-slate-500">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1">
          <dt>Created by</dt><dd className="text-slate-800">{t.createdByName ?? '—'}</dd>
          <dt>Created</dt><dd className="text-slate-800">{new Date(t.createdAt).toLocaleString()}</dd>
          <dt>Updated</dt><dd className="text-slate-800">{new Date(t.updatedAt).toLocaleString()}</dd>
          {t.publishedAt && (<><dt>Published</dt><dd className="text-slate-800">{new Date(t.publishedAt).toLocaleString()}</dd></>)}
          {t.archivedAt && (<><dt>Archived</dt><dd className="text-slate-800">{new Date(t.archivedAt).toLocaleString()}</dd></>)}
        </dl>
      </section>
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
