'use client';

import { useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AlertCircle, ArrowLeft, ChevronLeft, ChevronRight, FileText, UploadCloud } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { deriveKey, humanBytes, type TemplateRow } from '@/lib/careers/editable-templates';

/**
 * New template flow — two visible steps in one page:
 *
 * 1. Metadata (title / key / description) — creates the shell row.
 * 2. Source upload — presigns an S3 PUT for the .docx, uploads directly,
 *    then attaches. On success we push into the studio at /[key]/edit
 *    where the admin selects fields.
 *
 * The two steps run in one flow because there's no useful "empty
 * template" state — the studio needs the source DOCX to render before
 * fields can be picked.
 */

const DOCX_MIME =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

interface PresignResponse {
  uploadUrl: string;
  documentId: string;
  storageKey: string;
  expiresAt: string;
}

export default function NewEditableTemplatePage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN', 'JOBS_ADMIN']}>
      <DashboardLayout title="New Document Template">
        <PageContent />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PageContent() {
  const router = useRouter();

  // Step 0 — metadata; Step 1 — upload.
  const [step, setStep] = useState<0 | 1>(0);
  const [title, setTitle] = useState('');
  const [keyValue, setKeyValue] = useState('');
  const [keyManuallyEdited, setKeyManuallyEdited] = useState(false);
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<TemplateRow | null>(null);

  // Step 1 state.
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const derivedKey = useMemo(() => deriveKey(title), [title]);
  const finalKey = (keyManuallyEdited ? keyValue : derivedKey).trim();

  const canCreate = title.trim().length >= 2 && finalKey.length >= 2;

  async function submitMetadata() {
    if (!canCreate) return;
    setCreating(true);
    setError(null);
    try {
      const res = await api.post<TemplateRow>('/api/v1/admin/editable-templates', {
        title: title.trim(),
        key: finalKey,
        description: description.trim() || null,
      });
      setCreated(res.data);
      setStep(1);
      toast.success('Template created — now upload the Word document.');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setError(ax.response?.data?.error ?? 'Could not create template');
    } finally {
      setCreating(false);
    }
  }

  function pickFile(f: File | null) {
    setError(null);
    if (!f) return;
    const isDocx = f.name.toLowerCase().endsWith('.docx');
    if (!isDocx) {
      setError('Please pick a .docx file. This studio only supports Word documents; PDFs and other formats aren’t supported yet.');
      return;
    }
    setFile(f);
  }

  async function uploadAndAttach() {
    if (!file || !created) return;
    setUploading(true);
    setError(null);
    setUploadPercent(0);
    try {
      // 1. Presign
      const presignRes = await api.post<PresignResponse>(
        `/api/v1/admin/editable-templates/${created.id}/source/presign-upload`,
        {
          fileName: file.name,
          contentType: DOCX_MIME,
          fileSize: file.size,
        },
      );
      const presign = presignRes.data;

      // 2. Direct-to-S3 PUT via XHR (no bearer, exposes progress).
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', presign.uploadUrl);
        xhr.setRequestHeader('Content-Type', DOCX_MIME);
        xhr.upload.onprogress = (ev) => {
          if (!ev.lengthComputable) return;
          setUploadPercent(Math.min(99, Math.round((ev.loaded / ev.total) * 100)));
        };
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) resolve();
          else reject(new Error(`S3 rejected the upload (HTTP ${xhr.status}).`));
        };
        xhr.onerror = () => reject(new Error('Network error while uploading.'));
        xhr.send(file);
      });

      // 3. Attach on the backend (HEAD-verifies presence in S3).
      await api.post(`/api/v1/admin/editable-templates/${created.id}/source/attach`, {
        documentId: presign.documentId,
      });

      setUploadPercent(100);
      toast.success('Document uploaded — opening the studio.');
      router.push(`/careers/admin/document-templates/${created.key}/edit`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setError(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Upload failed'),
      );
      setUploading(false);
      setUploadPercent(0);
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <Link
          href="/careers/admin/document-templates"
          className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          All templates
        </Link>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">
          New Document Template
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Start by naming the template, then upload the source Word document.
        </p>
      </div>

      <Progress step={step} />

      {step === 0 && (
        <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="text-base font-semibold text-slate-900">Basic details</h2>
          <p className="mt-1 text-xs text-slate-500">
            The key is a stable identifier — it stays put even if the title changes.
          </p>

          <div className="mt-5 space-y-4">
            <Field label="Title" required>
              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="e.g. Offer Letter — 2026 revision"
                className={inputClass}
                autoComplete="off"
              />
            </Field>
            <Field label="Key" helper="Derived from the title. Adjust only if you need to.">
              <input
                value={keyManuallyEdited ? keyValue : derivedKey}
                onChange={(e) => {
                  setKeyManuallyEdited(true);
                  setKeyValue(deriveKey(e.target.value));
                }}
                placeholder="OFFER_LETTER_2026"
                className={`${inputClass} font-mono`}
                autoComplete="off"
              />
            </Field>
            <Field label="Description">
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
                placeholder="Optional short note for future admins."
                className={inputClass}
              />
            </Field>
          </div>

          {error && (
            <div className="mt-4 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <p>{error}</p>
            </div>
          )}

          <div className="mt-6 flex items-center justify-end">
            <button
              type="button"
              onClick={() => void submitMetadata()}
              disabled={!canCreate || creating}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {creating ? 'Creating…' : 'Next'}
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </section>
      )}

      {step === 1 && created && (
        <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="text-base font-semibold text-slate-900">
            Upload the Word document
          </h2>
          <p className="mt-1 text-xs text-slate-500">
            The file loads into the studio exactly as authored — headers, footers,
            tables and images included — so you can mark fields on the real layout.
          </p>

          <div className="mt-5 rounded-md border border-slate-200 bg-slate-50 p-4">
            <div className="flex items-center gap-3">
              <div className="rounded-md bg-white p-2">
                <FileText className="h-6 w-6 text-slate-500" />
              </div>
              <div className="flex-1 text-sm">
                <p className="font-medium text-slate-800">{created.title}</p>
                <p className="text-xs text-slate-500">
                  <code className="font-mono">{created.key}</code>
                </p>
              </div>
            </div>
          </div>

          <div className="mt-4">
            <input
              ref={fileInputRef}
              type="file"
              accept=".docx"
              className="hidden"
              onChange={(e) => pickFile(e.target.files?.[0] ?? null)}
            />
            {!file ? (
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="flex w-full flex-col items-center justify-center gap-2 rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-8 text-sm font-medium text-slate-700 hover:bg-slate-100"
              >
                <UploadCloud className="h-6 w-6 text-slate-500" />
                Choose a .docx file
                <span className="text-xs font-normal text-slate-500">
                  Max 25 MB — Word documents only for now.
                </span>
              </button>
            ) : (
              <div className="rounded-md border border-slate-200 bg-white p-3">
                <div className="flex items-center gap-3">
                  <FileText className="h-5 w-5 text-slate-500" />
                  <div className="flex-1 text-sm">
                    <p className="font-medium text-slate-800">{file.name}</p>
                    <p className="text-xs text-slate-500">{humanBytes(file.size)}</p>
                  </div>
                  {!uploading && (
                    <button
                      type="button"
                      onClick={() => {
                        setFile(null);
                        if (fileInputRef.current) fileInputRef.current.value = '';
                      }}
                      className="text-xs font-medium text-slate-500 hover:text-slate-700"
                    >
                      Change
                    </button>
                  )}
                </div>
                {uploading && (
                  <div className="mt-2">
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-200">
                      <div
                        className="h-full bg-brand-600 transition-all"
                        style={{ width: `${uploadPercent}%` }}
                      />
                    </div>
                    <p className="mt-1 text-xs text-slate-500">
                      Uploading… {uploadPercent}%
                    </p>
                  </div>
                )}
              </div>
            )}
          </div>

          {error && (
            <div className="mt-4 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <p>{error}</p>
            </div>
          )}

          <div className="mt-6 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setStep(0)}
              disabled={uploading}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
              Back
            </button>
            <button
              type="button"
              onClick={() => void uploadAndAttach()}
              disabled={!file || uploading}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {uploading ? `Uploading… ${uploadPercent}%` : 'Upload + open studio'}
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

function Progress({ step }: { step: 0 | 1 }) {
  return (
    <ol className="flex items-center gap-4 text-xs">
      <li className="flex items-center gap-2">
        <span
          className={`flex h-6 w-6 items-center justify-center rounded-full border font-semibold ${
            step >= 0 ? 'border-brand-700 bg-brand-700 text-white' : 'border-slate-300 bg-white text-slate-400'
          }`}
        >
          1
        </span>
        <span className={step === 0 ? 'text-slate-900 font-medium' : 'text-slate-500'}>
          Basic details
        </span>
      </li>
      <span className="h-px w-8 bg-slate-200" aria-hidden="true" />
      <li className="flex items-center gap-2">
        <span
          className={`flex h-6 w-6 items-center justify-center rounded-full border font-semibold ${
            step >= 1 ? 'border-brand-700 bg-brand-700 text-white' : 'border-slate-300 bg-white text-slate-400'
          }`}
        >
          2
        </span>
        <span className={step === 1 ? 'text-slate-900 font-medium' : 'text-slate-500'}>
          Upload document
        </span>
      </li>
    </ol>
  );
}

function Field({
  label,
  children,
  required,
  helper,
}: {
  label: string;
  children: React.ReactNode;
  required?: boolean;
  helper?: string;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-semibold text-slate-700">
        {label}
        {required && <span className="text-red-600"> *</span>}
      </label>
      {children}
      {helper && <p className="mt-1 text-xs text-slate-500">{helper}</p>}
    </div>
  );
}

const inputClass =
  'w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500';
