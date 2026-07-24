'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import toast from 'react-hot-toast';
import { Briefcase, Pencil, Plus, Search } from 'lucide-react';
import api from '@/lib/careers/api';
import { formatDateOnly } from '@/lib/careers/format-date';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type {
  EmploymentType,
  JobPostingResponse,
  JobPostingStatus,
  Page,
  UserRole,
  Uuid,
} from '@/types';

interface PostingFormState {
  jobId: string;
  title: string;
  employmentType: EmploymentType;
  location: string;
  aboutCompany: string;
  positionSummary: string;
  requiredSkills: string;
  workAuthorization: string;
  qualifications: string;
  status: JobPostingStatus;
}

const PAGE_SIZE = 25;

const STATUS_OPTIONS: JobPostingStatus[] = ['DRAFT', 'OPEN', 'PAUSED', 'CLOSED'];

const STATUS_COLOR: Record<JobPostingStatus, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  OPEN: 'bg-green-100 text-green-800',
  PAUSED: 'bg-amber-100 text-amber-800',
  CLOSED: 'bg-red-100 text-red-800',
};

const EMPLOYMENT_LABEL: Record<EmploymentType, string> = {
  INTERNSHIP: 'Internship',
  CONTRACT: 'Contract',
  FULL_TIME: 'Full-time',
  FULL_TIME_INTERNSHIP: 'Full-Time / Internship',
};

const POSTING_JOB_TYPE_OPTIONS: EmploymentType[] = [
  'FULL_TIME',
  'INTERNSHIP',
  'FULL_TIME_INTERNSHIP',
];

const WORK_LOCATION_OPTIONS = [
  'On-site',
  'Hybrid',
  'Remote',
  'On-site/Hybrid/Remote',
];

const EMPTY_FORM: PostingFormState = {
  jobId: '',
  title: '',
  employmentType: 'INTERNSHIP',
  location: 'On-site/Hybrid/Remote',
  aboutCompany: '',
  positionSummary: '',
  requiredSkills: '',
  workAuthorization: '',
  qualifications: '',
  status: 'DRAFT',
};

function StatusBadge({ status }: { status: JobPostingStatus }) {
  return (
    <span
      className={
        'inline-block whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ' +
        STATUS_COLOR[status]
      }
    >
      {status[0] + status.slice(1).toLowerCase()}
    </span>
  );
}

export function PostingsManagementPage({
  requiredRoles,
}: {
  requiredRoles: UserRole[];
}) {
  return (
    <ProtectedRoute requiredRoles={requiredRoles}>
      <DashboardLayout title="Job Postings">
        <PostingsTable />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function PostingsTable() {
  const [searchInput, setSearchInput] = useState('');
  const [committedSearch, setCommittedSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<JobPostingStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<Page<JobPostingResponse> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updatingId, setUpdatingId] = useState<Uuid | null>(null);
  const [editing, setEditing] = useState<JobPostingResponse | 'new' | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setCommittedSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (committedSearch) params.search = committedSearch;
      if (statusFilter !== 'ALL') params.status = statusFilter;
      const res = await api.get<Page<JobPostingResponse>>(
        '/api/v1/job-postings/admin/all',
        { params },
      );
      setData(
        res.data ?? {
          content: [],
          page,
          size: PAGE_SIZE,
          totalElements: 0,
          totalPages: 0,
        },
      );
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load postings.");
      setData(null);
    }
  }, [page, committedSearch, statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    setPage(0);
  }, [statusFilter]);

  const rows = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const currentPage = data?.page ?? page;
  async function toggleStatus(posting: JobPostingResponse) {
    const next: JobPostingStatus =
      posting.status === 'OPEN' ? 'CLOSED' : posting.status === 'CLOSED' ? 'OPEN' : 'OPEN';
    setUpdatingId(posting.id);
    try {
      await api.patch(`/api/v1/job-postings/${posting.id}/status`, { status: next });
      toast.success(`Posting "${posting.title}" is now ${next.toLowerCase()}.`);
      await load();
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't update posting status.");
    } finally {
      setUpdatingId(null);
    }
  }

  return (
    <section>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-3">
          <label className="relative block min-w-0 flex-1 sm:max-w-xs">
            <span className="sr-only">Search postings</span>
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
            <input
              type="search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder="Search by title or description"
              className="w-full rounded-md border border-gray-300 bg-white py-2 pl-9 pr-3 text-sm placeholder:text-gray-400 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </label>

          <select
            value={statusFilter}
            onChange={(event) =>
              setStatusFilter(event.target.value as JobPostingStatus | 'ALL')
            }
            className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="ALL">All statuses</option>
            {STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {status[0] + status.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </div>

        <button
          type="button"
          onClick={() => setEditing('new')}
          className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-sm font-medium text-white hover:bg-accent/90"
        >
          <Plus className="h-4 w-4" />
          New posting
        </button>
      </div>

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

      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
        <table className="min-w-full text-sm">
          <thead className="border-b border-gray-200 bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3 font-medium">Job ID</th>
              <th scope="col" className="px-4 py-3 font-medium">Job Title</th>
              <th scope="col" className="px-4 py-3 font-medium">Type</th>
              <th scope="col" className="px-4 py-3 font-medium">Status</th>
              <th scope="col" className="px-4 py-3 font-medium">Applicants</th>
              <th scope="col" className="px-4 py-3 font-medium">Created</th>
              <th scope="col" className="px-4 py-3 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {data === null && !error ? (
              <SkeletonRows />
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-10 text-center text-sm text-gray-500">
                  <Briefcase className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
                  {committedSearch || statusFilter !== 'ALL'
                    ? 'No postings match those filters.'
                    : 'No postings yet.'}
                </td>
              </tr>
            ) : (
              rows.map((posting) => (
                <tr
                  key={posting.id}
                  className="border-b border-gray-100 last:border-0 hover:bg-gray-50"
                >
                  <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-gray-600">
                    {posting.jobId ?? '-'}
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      href={`/careers/openings/${posting.slug ?? posting.id}`}
                      className="font-medium text-gray-900 hover:underline"
                    >
                      {posting.title}
                    </Link>
                    {posting.location && (
                      <div className="mt-0.5 text-xs text-gray-500">{posting.location}</div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-gray-700">
                    {EMPLOYMENT_LABEL[posting.employmentType] ?? posting.employmentType}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={posting.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-700">{posting.applicantCount ?? 0}</td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-700">
                    {posting.createdAt ? formatDateOnly(posting.createdAt) : '-'}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-right">
                    <button
                      type="button"
                      onClick={() => setEditing(posting)}
                      className="mr-2 inline-flex items-center gap-1 rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50"
                    >
                      <Pencil className="h-3.5 w-3.5" />
                      Edit
                    </button>
                    {(posting.status === 'OPEN' || posting.status === 'CLOSED') && (
                      <button
                        type="button"
                        onClick={() => void toggleStatus(posting)}
                        disabled={updatingId === posting.id}
                        className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
                      >
                        {updatingId === posting.id
                          ? '...'
                          : posting.status === 'OPEN'
                            ? 'Close'
                            : 'Reopen'}
                      </button>
                    )}
                    {posting.status === 'DRAFT' && (
                      <button
                        type="button"
                        onClick={() => void toggleStatus(posting)}
                        disabled={updatingId === posting.id}
                        className="rounded-md bg-accent px-2.5 py-1 text-xs font-medium text-white hover:bg-accent/90 disabled:opacity-60"
                      >
                        {updatingId === posting.id ? '...' : 'Publish'}
                      </button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 0 && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-gray-600">
          <span>
            {totalElements} posting{totalElements === 1 ? '' : 's'}
          </span>
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={currentPage === 0}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
            >
              Prev
            </button>
            <span>
              Page {currentPage + 1} of {Math.max(totalPages, 1)}
            </span>
            <button
              type="button"
              onClick={() =>
                setPage((current) => Math.min(Math.max(totalPages - 1, 0), current + 1))
              }
              disabled={currentPage >= totalPages - 1}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}

      {editing && (
        <PostingEditorModal
          mode={editing === 'new' ? 'create' : 'edit'}
          posting={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      )}
    </section>
  );
}

function PostingEditorModal({
  mode,
  posting,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  posting: JobPostingResponse | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [form, setForm] = useState<PostingFormState>(() =>
    posting ? formFromPosting(posting) : EMPTY_FORM,
  );
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const title = mode === 'create' ? 'New job posting' : 'Edit job posting';
  const preview = useMemo(() => buildPostingText(form), [form]);

  function update<K extends keyof PostingFormState>(key: K, value: PostingFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  async function save() {
    if (!form.jobId.trim()) {
      setError('Job ID is required.');
      return;
    }
    if (!form.title.trim()) {
      setError('Job Title is required.');
      return;
    }
    if (!form.aboutCompany.trim()) {
      setError('About Company is required.');
      return;
    }
    if (!form.location.trim()) {
      setError('Work Location is required.');
      return;
    }
    if (!form.positionSummary.trim()) {
      setError('Position Summary is required.');
      return;
    }

    setSaving(true);
    setError(null);
    const payload = {
      jobId: form.jobId.trim(),
      title: form.title.trim(),
      employmentType: form.employmentType,
      location: form.location.trim(),
      description: preview.description,
      requirements: preview.requirements,
      status: form.status,
    };

    try {
      if (mode === 'create') {
        await api.post('/api/v1/job-postings', payload);
        toast.success('Posting created as draft.');
      } else if (posting) {
        await api.put(`/api/v1/job-postings/${posting.id}`, payload);
        toast.success('Posting updated.');
      }
      await onSaved();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't save posting.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="flex max-h-[92vh] w-full max-w-5xl flex-col rounded-lg bg-white shadow-xl">
        <div className="border-b border-gray-200 px-6 py-4">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <p className="mt-1 text-xs text-gray-500">
            Job ID is entered manually by Jobs Admin and must be unique.
          </p>
        </div>

        <div className="grid min-h-0 flex-1 gap-6 overflow-y-auto p-6 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,0.8fr)]">
          <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Job ID" required>
                <input
                  value={form.jobId}
                  onChange={(event) => update('jobId', event.target.value)}
                  placeholder="ANVI-2026-07-JD"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </Field>
              <Field label="Job Title" required>
                <input
                  value={form.title}
                  onChange={(event) => update('title', event.target.value)}
                  placeholder="Java/J2EE Developer - Junior to Mid-Level"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </Field>
            </div>

            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Job Type" required>
                <select
                  value={form.employmentType}
                  onChange={(event) => update('employmentType', event.target.value as EmploymentType)}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  {POSTING_JOB_TYPE_OPTIONS.map((value) => (
                    <option key={value} value={value}>
                      {EMPLOYMENT_LABEL[value]}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Work Location" required>
                <select
                  value={form.location}
                  onChange={(event) => update('location', event.target.value)}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  {!WORK_LOCATION_OPTIONS.includes(form.location) && form.location && (
                    <option value={form.location}>{form.location}</option>
                  )}
                  {WORK_LOCATION_OPTIONS.map((location) => (
                    <option key={location} value={location}>
                      {location}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Status">
                <select
                  value={form.status}
                  onChange={(event) => update('status', event.target.value as JobPostingStatus)}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  {STATUS_OPTIONS.map((status) => (
                    <option key={status} value={status}>
                      {status[0] + status.slice(1).toLowerCase()}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <Field label="About Company" required>
              <textarea
                value={form.aboutCompany}
                onChange={(event) => update('aboutCompany', event.target.value)}
                rows={3}
                placeholder="Anvi Corp USA is a technology services and consulting company..."
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>

            <Field label="Position Summary" required>
              <textarea
                value={form.positionSummary}
                onChange={(event) => update('positionSummary', event.target.value)}
                rows={4}
                placeholder="Seeking a Junior to Mid-Level Java/J2EE Developer to develop and support enterprise-level applications and services."
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>

            <Field label="Required Skills">
              <textarea
                value={form.requiredSkills}
                onChange={(event) => update('requiredSkills', event.target.value)}
                rows={6}
                placeholder={'Basic to intermediate knowledge of Java and J2EE.\nExperience or training in Spring Boot, Hibernate, REST APIs, SQL, and Maven or Gradle.\nStrong analytical and communication skills.'}
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>

            <Field label="Work Authorization">
              <textarea
                value={form.workAuthorization}
                onChange={(event) => update('workAuthorization', event.target.value)}
                rows={4}
                placeholder={'Must be authorized to work in the United States.\nSponsorship requirements may vary by position.'}
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>

            <Field label="Qualification">
              <textarea
                value={form.qualifications}
                onChange={(event) => update('qualifications', event.target.value)}
                rows={4}
                placeholder={"Internship: Currently pursuing or recently completed a Bachelor's or Master's degree...\nFull-Time: Bachelor's or Master's degree with approximately 1-5 years of experience..."}
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>
          </div>

          <aside className="rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">Preview</div>
            <div className="mt-3 space-y-3 text-sm text-gray-800">
              <PreviewRow label="Job ID" value={form.jobId || '-'} mono />
              <PreviewRow label="Job Title" value={form.title || '-'} />
              <PreviewRow label="Job Type" value={EMPLOYMENT_LABEL[form.employmentType]} />
              <PreviewRow label="Work Location" value={form.location || '-'} />
              <div>
                <div className="text-xs font-semibold text-gray-500">Description block</div>
                <pre className="mt-1 max-h-52 overflow-auto whitespace-pre-wrap rounded border border-gray-200 bg-white p-3 text-xs leading-relaxed text-gray-700">
                  {preview.description || '-'}
                </pre>
              </div>
              <div>
                <div className="text-xs font-semibold text-gray-500">Requirements block</div>
                <pre className="mt-1 max-h-52 overflow-auto whitespace-pre-wrap rounded border border-gray-200 bg-white p-3 text-xs leading-relaxed text-gray-700">
                  {preview.requirements || '-'}
                </pre>
              </div>
            </div>
          </aside>
        </div>

        {error && (
          <div className="mx-6 mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </div>
        )}

        <div className="flex justify-end gap-2 border-t border-gray-200 px-6 py-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:opacity-60"
          >
            {saving ? 'Saving...' : mode === 'create' ? 'Create posting' : 'Save changes'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-medium text-gray-700">
        {label} {required && <span className="text-red-500">*</span>}
      </span>
      {children}
    </label>
  );
}

function PreviewRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-xs font-semibold text-gray-500">{label}</dt>
      <dd className={'truncate text-right text-sm text-gray-800 ' + (mono ? 'font-mono' : '')}>
        {value}
      </dd>
    </div>
  );
}

function SkeletonRows() {
  return (
    <>
      {[0, 1, 2, 3, 4].map((row) => (
        <tr key={row} className="border-b border-gray-100 last:border-0">
          {[0, 1, 2, 3, 4, 5, 6].map((cell) => (
            <td key={cell} className="px-4 py-3">
              <div className="h-4 w-full animate-pulse rounded bg-gray-100" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}

function buildPostingText(form: PostingFormState): {
  description: string;
  requirements: string;
} {
  const descriptionSections = [
    section('About Company', form.aboutCompany, false),
    section('Position Summary', form.positionSummary, false),
  ].filter(Boolean);

  const requirementSections = [
    section('Required Skills', form.requiredSkills, true),
    section('Work Authorization', form.workAuthorization, true),
    section('Qualification', form.qualifications, false),
  ].filter(Boolean);

  return {
    description: descriptionSections.join('\n\n'),
    requirements: requirementSections.join('\n\n'),
  };
}

function section(title: string, value: string, bullets: boolean): string {
  const lines = normalizeLines(value);
  if (lines.length === 0) return '';
  if (!bullets) {
    return `${title}\n${lines.join('\n')}`;
  }
  return `${title}\n${lines.map((line) => `- ${line}`).join('\n')}`;
}

function normalizeLines(value: string): string[] {
  return value
    .split(/\r?\n/)
    .map((line) => line.replace(/^[-*•\s]+/, '').trim())
    .filter(Boolean);
}

function formFromPosting(posting: JobPostingResponse): PostingFormState {
  return {
    jobId: posting.jobId ?? '',
    title: posting.title ?? '',
    employmentType: posting.employmentType ?? 'INTERNSHIP',
    location: posting.location ?? 'On-site/Hybrid/Remote',
    aboutCompany: extractSection(posting.description, 'About Company'),
    positionSummary: extractSection(posting.description, 'Position Summary'),
    requiredSkills: extractSection(posting.requirements, 'Required Skills'),
    workAuthorization: extractSection(posting.requirements, 'Work Authorization'),
    qualifications: extractSection(posting.requirements, 'Qualification'),
    status: posting.status ?? 'DRAFT',
  };
}

function extractSection(text: string | undefined, heading: string): string {
  if (!text) return '';
  const headings = [
    'About Company',
    'Position Summary',
    'Required Skills',
    'Work Authorization',
    'Qualification',
    'Qualifications',
    'About the Role',
    'Key Responsibilities',
  ];
  const escapedHeading = escapeRegExp(heading);
  const otherHeadings = headings
    .filter((candidate) => candidate !== heading)
    .map(escapeRegExp)
    .join('|');
  const pattern = new RegExp(
    `(?:^|\\n)${escapedHeading}\\s*\\n([\\s\\S]*?)(?=\\n(?:${otherHeadings})\\s*\\n|$)`,
    'i',
  );
  const match = text.match(pattern);
  if (!match) {
    return heading === 'About Company' || heading === 'Position Summary'
      ? text.trim()
      : '';
  }
  return normalizeLines(match[1]).join('\n');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
