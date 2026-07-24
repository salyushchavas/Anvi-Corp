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

interface AdminEntityResponse {
  id: Uuid;
  name: string;
}

interface PostingFormState {
  title: string;
  entityId: string;
  employmentType: EmploymentType;
  location: string;
  aboutRole: string;
  responsibilities: string;
  requiredSkills: string;
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
};

const EMPTY_FORM: PostingFormState = {
  title: '',
  entityId: '',
  employmentType: 'INTERNSHIP',
  location: '',
  aboutRole: '',
  responsibilities: '',
  requiredSkills: '',
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
  const [entityFilter, setEntityFilter] = useState<string>('ALL');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<Page<JobPostingResponse> | null>(null);
  const [entities, setEntities] = useState<AdminEntityResponse[]>([]);
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

  const loadEntities = useCallback(async () => {
    try {
      const res = await api.get<AdminEntityResponse[]>('/api/v1/admin/entities');
      setEntities(res.data ?? []);
    } catch {
      setEntities([]);
    }
  }, []);

  useEffect(() => {
    void loadEntities();
  }, [loadEntities]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (committedSearch) params.search = committedSearch;
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (entityFilter !== 'ALL') params.entityId = entityFilter;
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
  }, [page, committedSearch, statusFilter, entityFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    setPage(0);
  }, [statusFilter, entityFilter]);

  const rows = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const currentPage = data?.page ?? page;
  const canCreate = entities.length > 0;

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
            value={entityFilter}
            onChange={(event) => setEntityFilter(event.target.value)}
            className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="ALL">All entities</option>
            {entities.map((entity) => (
              <option key={entity.id} value={entity.id}>
                {entity.name}
              </option>
            ))}
          </select>

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
          disabled={!canCreate}
          className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-2 text-sm font-medium text-white hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-60"
          title={canCreate ? undefined : 'No company/entity is available for posting yet'}
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
              <th scope="col" className="px-4 py-3 font-medium">Role</th>
              <th scope="col" className="px-4 py-3 font-medium">Company</th>
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
                <td colSpan={8} className="px-4 py-10 text-center text-sm text-gray-500">
                  <Briefcase className="mx-auto mb-3 h-8 w-8 text-gray-400" strokeWidth={1.5} />
                  {committedSearch || statusFilter !== 'ALL' || entityFilter !== 'ALL'
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
                  <td className="px-4 py-3 text-gray-700">{posting.entityName ?? '-'}</td>
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
          entities={entities}
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
  entities,
  onClose,
  onSaved,
}: {
  mode: 'create' | 'edit';
  posting: JobPostingResponse | null;
  entities: AdminEntityResponse[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [form, setForm] = useState<PostingFormState>(() =>
    posting ? formFromPosting(posting) : { ...EMPTY_FORM, entityId: entities[0]?.id ?? '' },
  );
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const title = mode === 'create' ? 'New job posting' : 'Edit job posting';
  const preview = useMemo(() => buildPostingText(form), [form]);

  function update<K extends keyof PostingFormState>(key: K, value: PostingFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  async function save() {
    if (!form.title.trim()) {
      setError('Role is required.');
      return;
    }
    if (!form.entityId) {
      setError('Company is required.');
      return;
    }
    if (!form.location.trim()) {
      setError('Location is required.');
      return;
    }
    if (!form.aboutRole.trim()) {
      setError('About the Role is required.');
      return;
    }

    setSaving(true);
    setError(null);
    const payload = {
      title: form.title.trim(),
      entityId: form.entityId,
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
            Job ID is generated by the system. Use the fields below to create the public posting format.
          </p>
        </div>

        <div className="grid min-h-0 flex-1 gap-6 overflow-y-auto p-6 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,0.8fr)]">
          <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Role" required>
                <input
                  value={form.title}
                  onChange={(event) => update('title', event.target.value)}
                  placeholder="Java/J2EE Developer - Junior to Mid-Level"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </Field>
              <Field label="Company" required>
                <select
                  value={form.entityId}
                  onChange={(event) => update('entityId', event.target.value)}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  <option value="">Select company</option>
                  {entities.map((entity) => (
                    <option key={entity.id} value={entity.id}>
                      {entity.name}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <div className="grid gap-4 sm:grid-cols-3">
              <Field label="Job Type" required>
                <select
                  value={form.employmentType}
                  onChange={(event) => update('employmentType', event.target.value as EmploymentType)}
                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  {Object.entries(EMPLOYMENT_LABEL).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Location" required>
                <input
                  value={form.location}
                  onChange={(event) => update('location', event.target.value)}
                  placeholder="On-site, Hybrid, or Remote"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
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

            <Field label="About the Role" required>
              <textarea
                value={form.aboutRole}
                onChange={(event) => update('aboutRole', event.target.value)}
                rows={3}
                placeholder="Seeking a Junior to Mid-Level Java/J2EE Developer..."
                className="w-full resize-y rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              />
            </Field>

            <Field label="Key Responsibilities">
              <textarea
                value={form.responsibilities}
                onChange={(event) => update('responsibilities', event.target.value)}
                rows={6}
                placeholder={'Develop applications using Java, J2EE, Spring Boot, and Hibernate.\nBuild and maintain RESTful services.\nSupport testing, debugging, deployment, and production issues.'}
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

            <Field label="Qualifications">
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
              <PreviewRow label="Job ID" value={posting?.jobId ?? 'Generated after save'} mono />
              <PreviewRow label="Role" value={form.title || '-'} />
              <PreviewRow
                label="Company"
                value={entities.find((entity) => entity.id === form.entityId)?.name ?? '-'}
              />
              <PreviewRow label="Job Type" value={EMPLOYMENT_LABEL[form.employmentType]} />
              <PreviewRow label="Location" value={form.location || '-'} />
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
          {[0, 1, 2, 3, 4, 5, 6, 7].map((cell) => (
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
    section('About the Role', form.aboutRole, false),
    section('Key Responsibilities', form.responsibilities, true),
  ].filter(Boolean);

  const requirementSections = [
    section('Required Skills', form.requiredSkills, true),
    section('Qualifications', form.qualifications, false),
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
    title: posting.title ?? '',
    entityId: posting.entityId ?? '',
    employmentType: posting.employmentType ?? 'INTERNSHIP',
    location: posting.location ?? '',
    aboutRole: extractSection(posting.description, 'About the Role'),
    responsibilities: extractSection(posting.description, 'Key Responsibilities'),
    requiredSkills: extractSection(posting.requirements, 'Required Skills'),
    qualifications: extractSection(posting.requirements, 'Qualifications'),
    status: posting.status ?? 'DRAFT',
  };
}

function extractSection(text: string | undefined, heading: string): string {
  if (!text) return '';
  const headings = ['About the Role', 'Key Responsibilities', 'Required Skills', 'Qualifications'];
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
  if (!match) return heading === 'About the Role' ? text.trim() : '';
  return normalizeLines(match[1]).join('\n');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
