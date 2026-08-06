'use client';

import { useRef, useState } from 'react';
import { Upload } from 'lucide-react';
import api from '@/lib/careers/api';
import type { ResumeResponse } from '@/types';

interface Props {
  applicationId: string;
  fields: string[];
  message?: string;
  reasonLabel?: string;
  onClose: () => void;
  onProvided: () => void;
}

export default function ProvideInfoModal({
  applicationId,
  fields,
  message,
  reasonLabel,
  onClose,
  onProvided,
}: Props) {
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [resumeFile, setResumeFile] = useState<File | null>(null);
  const [uploadedResumeId, setUploadedResumeId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [workAuthType, setWorkAuthType] = useState('');
  const [workAuthValidUntil, setWorkAuthValidUntil] = useState('');
  const [educationSchool, setEducationSchool] = useState('');
  const [educationDegree, setEducationDegree] = useState('');
  const [freeTextResponse, setFreeTextResponse] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const need = (k: string) => fields.includes(k);

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setErr(null);
    setResumeFile(file);
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await api.post<ResumeResponse>('/api/v1/resumes', form);
      setUploadedResumeId(res.data.id);
    } catch (ex) {
      setResumeFile(null);
      setUploadedResumeId(null);
      const ax = ex as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (ex instanceof Error ? ex.message : 'Resume upload failed'),
      );
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function submit() {
    setErr(null);
    if (need('resume') && !uploadedResumeId) {
      setErr('Please upload a new resume before submitting.');
      return;
    }
    if (need('other') && !freeTextResponse.trim()) {
      setErr('Additional details are required.');
      return;
    }
    setSubmitting(true);
    try {
      const body: Record<string, unknown> = {};
      if (uploadedResumeId) {
        body.resumeFileId = uploadedResumeId;
      }
      if (need('workAuth')) {
        body.workAuthUpdate = {
          type: workAuthType || null,
          validUntil: workAuthValidUntil || null,
        };
      }
      if (need('education')) {
        body.educationUpdate = {
          school: educationSchool || null,
          degree: educationDegree || null,
        };
      }
      if (freeTextResponse.trim()) {
        body.freeTextResponse = freeTextResponse.trim();
      }
      await api.post(
        `/api/v1/applications/${applicationId}/provide-info`,
        body,
      );
      onProvided();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to submit information'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white p-6 shadow-xl">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">
            Provide requested information
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        {(reasonLabel || message) && (
          <div className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            {reasonLabel && (
              <p className="font-medium">Reviewer note: {reasonLabel}</p>
            )}
            {message && (
              <p className="mt-1 whitespace-pre-wrap">{message}</p>
            )}
          </div>
        )}

        <div className="mt-4 space-y-4">
          {need('resume') && (
            <div>
              <label className="text-sm font-medium text-slate-800">
                Updated resume <span className="text-red-600">*</span>
              </label>
              <div className="mt-2 flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => fileRef.current?.click()}
                  disabled={uploading}
                  className="inline-flex items-center gap-2 rounded-md border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
                >
                  <Upload className="h-4 w-4" strokeWidth={2} />
                  {uploading
                    ? 'Uploading…'
                    : resumeFile
                    ? 'Replace file'
                    : 'Choose file'}
                </button>
                <span className="text-xs text-slate-600">
                  {resumeFile ? resumeFile.name : 'No file chosen'}
                </span>
              </div>
              <input
                ref={fileRef}
                type="file"
                accept=".pdf,.doc,.docx"
                onChange={handleFileChange}
                className="hidden"
              />
              {uploadedResumeId && (
                <p className="mt-1 text-[11px] text-green-700">
                  Uploaded — ready to submit.
                </p>
              )}
              <p className="mt-1 text-[11px] text-slate-500">
                PDF or Word document. The new file replaces the resume on this
                application; your previous resume stays in your profile.
              </p>
            </div>
          )}
          {need('workAuth') && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Work auth type
                </label>
                <input
                  value={workAuthType}
                  onChange={(e) => setWorkAuthType(e.target.value)}
                  placeholder="OPT / CPT / GC / USC"
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Valid until
                </label>
                <input
                  type="date"
                  value={workAuthValidUntil}
                  onChange={(e) => setWorkAuthValidUntil(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
            </div>
          )}
          {need('education') && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-sm font-medium text-slate-800">
                  School
                </label>
                <input
                  value={educationSchool}
                  onChange={(e) => setEducationSchool(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="text-sm font-medium text-slate-800">
                  Degree
                </label>
                <input
                  value={educationDegree}
                  onChange={(e) => setEducationDegree(e.target.value)}
                  className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>
            </div>
          )}
          <div>
            <label className="text-sm font-medium text-slate-800">
              Message to the reviewer{' '}
              {need('other') && <span className="text-red-600">*</span>}
            </label>
            <textarea
              value={freeTextResponse}
              onChange={(e) => setFreeTextResponse(e.target.value)}
              rows={4}
              placeholder={
                need('other')
                  ? 'Describe what you are providing'
                  : 'Optional — add any context for the reviewer'
              }
              className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={submitting || uploading}
            className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {submitting ? 'Submitting…' : 'Submit information'}
          </button>
        </div>
      </div>
    </div>
  );
}
