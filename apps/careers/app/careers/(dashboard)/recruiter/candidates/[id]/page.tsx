'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ArrowLeft, Download, ExternalLink, FileText } from 'lucide-react';
import api from '@/lib/api';
import { formatDateOnly } from '@/lib/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type { Uuid } from '@/types';

interface CandidateDetailResponse {
  candidateId: Uuid;
  name: string | null;
  email: string | null;
  phone: string | null;
  dateOfBirth: string | null;
  createdAt: string | null;
  resume: { id: Uuid; fileName: string | null } | null;
  applications: Array<{
    id: Uuid;
    position: string | null;
    entityName: string | null;
    status: string | null;
    appliedAt: string | null;
  }> | null;
}

function initialsOf(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((p) => p[0]?.toUpperCase() ?? '').join('') || '?';
}

export default function RecruiterCandidateDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM']}>
      <DashboardLayout title="Candidate">
        <CandidateDetail />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function CandidateDetail() {
  const params = useParams<{ id: string }>();
  const candidateId = params?.id;

  const [data, setData] = useState<CandidateDetailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const load = useCallback(async () => {
    if (!candidateId) return;
    setError(null);
    setNotFound(false);
    try {
      const res = await api.get<CandidateDetailResponse>(
        `/api/v1/candidates/${candidateId}`,
      );
      setData(res.data ?? null);
    } catch (err: any) {
      if (err?.response?.status === 404) {
        setNotFound(true);
        setData(null);
      } else {
        setError(err?.response?.data?.error ?? "Couldn't load this candidate.");
        setData(null);
      }
    }
  }, [candidateId]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section>
      <Link
        href="/careers/erm/candidates"
        className="mb-4 inline-flex items-center gap-1 text-sm text-gray-600 hover:text-gray-900"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to candidates
      </Link>

      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          <p className="mb-2">{error}</p>
          <button
            type="button"
            onClick={() => void load()}
            className="rounded border border-red-300 px-3 py-1 font-medium hover:bg-red-100"
          >
            Retry
          </button>
        </div>
      )}

      {notFound ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-10 text-center text-sm text-gray-600">
          Candidate not found.
        </div>
      ) : data === null && !error ? (
        <div className="py-10 text-center text-sm text-gray-500">Loading…</div>
      ) : data === null ? null : (
        <>
          <header className="mb-6 flex items-start gap-4">
            <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-accent text-lg font-semibold text-white">
              {initialsOf(data.name)}
            </div>
            <div className="min-w-0">
              <h1 className="text-2xl font-semibold text-slate-900">
                {data.name ?? '—'}
              </h1>
              <p className="mt-1 text-sm text-slate-600">
                {data.email ?? '—'}
                {data.phone ? <> · {data.phone}</> : null}
              </p>
              {data.createdAt && (
                <p className="text-xs text-slate-500">
                  Joined {formatDateOnly(data.createdAt)}
                </p>
              )}
            </div>
          </header>

          <ResumeSection resume={data.resume} />

          <ApplicationsSection applications={data.applications} />
        </>
      )}
    </section>
  );
}

function ResumeSection({
  resume,
}: {
  resume: CandidateDetailResponse['resume'];
}) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [blobError, setBlobError] = useState<string | null>(null);
  const filenameRef = useRef<string>(resume?.fileName ?? 'resume.pdf');

  useEffect(() => {
    if (!resume?.id) return;
    let cancelled = false;
    let createdUrl: string | null = null;
    setLoading(true);
    setBlobError(null);

    (async () => {
      try {
        const res = await api.get(`/api/v1/resumes/${resume.id}/download`, {
          responseType: 'blob',
        });
        if (cancelled) return;
        const ctRaw = res.headers['content-type'];
        const contentType =
          typeof ctRaw === 'string' ? ctRaw : 'application/octet-stream';

        const cdRaw = res.headers['content-disposition'];
        const cd = typeof cdRaw === 'string' ? cdRaw : '';
        const m = cd.match(/filename="?([^";]+)"?/);
        filenameRef.current = m?.[1] ?? resume.fileName ?? 'resume.pdf';

        const blob = new Blob([res.data], { type: contentType });
        createdUrl = URL.createObjectURL(blob);
        if (!cancelled) setBlobUrl(createdUrl);
      } catch (err: any) {
        if (!cancelled) {
          setBlobError(
            err?.response?.data?.error ?? "Couldn't load resume.",
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [resume?.id, resume?.fileName]);

  function handleViewFull() {
    if (!blobUrl) return;
    window.open(blobUrl, '_blank', 'noopener');
  }

  function handleDownload() {
    if (!blobUrl) return;
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filenameRef.current;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  return (
    <section className="mb-6 rounded-lg border border-gray-200 bg-white p-5">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-900">Resume</h2>
        {resume && blobUrl && (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={handleViewFull}
              className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
            >
              <ExternalLink className="h-3.5 w-3.5" strokeWidth={2} />
              View
            </button>
            <button
              type="button"
              onClick={handleDownload}
              className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
            >
              <Download className="h-3.5 w-3.5" strokeWidth={2} />
              Download
            </button>
          </div>
        )}
      </div>

      {!resume ? (
        <div className="rounded-md border border-dashed border-gray-300 bg-gray-50 p-8 text-center text-sm text-gray-500">
          <FileText className="mx-auto mb-2 h-6 w-6 text-gray-400" strokeWidth={1.5} />
          No resume uploaded.
        </div>
      ) : loading ? (
        <div className="flex h-[480px] items-center justify-center rounded-md border border-gray-200 bg-gray-50">
          <div className="flex flex-col items-center gap-2">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
            <p className="text-xs text-gray-500">Loading resume…</p>
          </div>
        </div>
      ) : blobError ? (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {blobError}
        </div>
      ) : blobUrl ? (
        <iframe
          src={blobUrl}
          title={`Resume: ${filenameRef.current}`}
          className="h-[480px] w-full rounded-md border border-gray-200"
        />
      ) : null}
    </section>
  );
}

function ApplicationsSection({
  applications,
}: {
  applications: CandidateDetailResponse['applications'];
}) {
  return (
    <section className="rounded-lg border border-gray-200 bg-white p-5">
      <h2 className="mb-3 text-sm font-semibold text-gray-900">Applications</h2>
      {(applications?.length ?? 0) === 0 ? (
        <p className="text-sm text-gray-500">No applications yet.</p>
      ) : (
        <ul className="space-y-2">
          {(applications ?? []).map((a) => (
            <li
              key={a.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-gray-100 bg-white p-3"
            >
              <div className="min-w-0">
                <div className="truncate font-medium text-gray-900">
                  {a.position ?? '—'}
                  {a.entityName ? (
                    <span className="text-gray-600"> · {a.entityName}</span>
                  ) : null}
                </div>
                <div className="text-xs text-gray-500">
                  Applied {a.appliedAt ? formatDateOnly(a.appliedAt) : '—'}
                </div>
              </div>
              {a.status ? <ApplicationStatusBadge status={a.status} /> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
