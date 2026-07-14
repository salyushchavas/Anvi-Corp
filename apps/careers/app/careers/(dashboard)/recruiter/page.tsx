'use client';

import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { KanbanSquare, RefreshCw, Table } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ApplicationCard from '@/components/ApplicationCard';
import ApplicationDetailDrawer from '@/components/ApplicationDetailDrawer';
import PipelineTable from '@/components/recruiter/PipelineTable';
import type {
  ApplicationResponse,
  ApplicationStatus,
  JobPostingResponse,
  Page,
} from '@/types';

type ViewMode = 'board' | 'table';

export default function RecruiterPipelinePage() {
  return (
    <ProtectedRoute
      requiredRoles={['ERM']}
    >
      <DashboardLayout title="Application Pipeline">
        <PipelineShell />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

/**
 * Owns the Board | Table view toggle and the shared postings list. The Kanban
 * view stays exactly as it was — the toggle just swaps in the new table view
 * for the 50–100-applicant case. Default is Board to keep the established
 * recruiter workflow.
 */
function PipelineShell() {
  const [view, setView] = useState<ViewMode>('board');
  const [postings, setPostings] = useState<JobPostingResponse[]>([]);

  // Postings are needed by both views' filter dropdowns; fetch once at this
  // level so switching tabs doesn't reload them.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<Page<JobPostingResponse>>(
          '/api/v1/job-postings/admin/all?page=0&size=200',
        );
        if (!cancelled) setPostings(res.data?.content ?? []);
      } catch {
        if (!cancelled) setPostings([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="space-y-4">
      <div className="inline-flex rounded-md border border-gray-200 bg-white p-0.5">
        <ViewTabButton
          active={view === 'board'}
          onClick={() => setView('board')}
          icon={<KanbanSquare className="h-4 w-4" strokeWidth={2} />}
          label="Board"
        />
        <ViewTabButton
          active={view === 'table'}
          onClick={() => setView('table')}
          icon={<Table className="h-4 w-4" strokeWidth={2} />}
          label="Table"
        />
      </div>
      {view === 'board' ? <PipelineBoard /> : <PipelineTable postings={postings} />}
    </div>
  );
}

function ViewTabButton({
  active,
  onClick,
  icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'inline-flex items-center gap-1.5 rounded px-3 py-1.5 text-sm transition ' +
        (active
          ? 'bg-accent text-white shadow-sm'
          : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900')
      }
      aria-pressed={active}
    >
      {icon}
      {label}
    </button>
  );
}

const PRIMARY_COLUMNS: ReadonlyArray<{ key: ApplicationStatus; label: string }> = [
  { key: 'APPLIED', label: 'Applied' },
  { key: 'SHORTLISTED', label: 'Shortlisted' },
  { key: 'INTERVIEW_SCHEDULED', label: 'Interview Scheduled' },
  { key: 'OFFERED', label: 'Offer Extended' },
  { key: 'ACCEPTED', label: 'Hired' },
];

// Spec wanted OFFER_DECLINED as a 3rd archive column; backend has no such status,
// so the drawer's "Mark Offer Declined" action stores REJECTED. Only 2 archive
// columns are surfaced here.
const ARCHIVED_COLUMNS: ReadonlyArray<{ key: ApplicationStatus; label: string }> = [
  { key: 'REJECTED', label: 'Rejected' },
  { key: 'WITHDRAWN', label: 'Withdrawn' },
];

function PipelineBoard() {
  const router = useRouter();
  const [applications, setApplications] = useState<ApplicationResponse[]>([]);
  const [postings, setPostings] = useState<JobPostingResponse[]>([]);
  const [postingFilter, setPostingFilter] = useState<string>(''); // '' = all
  const [showArchived, setShowArchived] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [openAppId, setOpenAppId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  // Postings list for filter dropdown
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<Page<JobPostingResponse>>(
          '/api/v1/job-postings/admin/all?page=0&size=200'
        );
        if (!cancelled) setPostings(res.data?.content ?? []);
      } catch {
        if (!cancelled) setPostings([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loadApplications = useCallback(
    async (mode: 'initial' | 'refresh' = 'initial') => {
      if (mode === 'initial') setLoading(true);
      else setRefreshing(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        params.set('size', '200');
        params.set('page', '0');
        if (postingFilter) params.set('jobPostingId', postingFilter);
        const res = await api.get<Page<ApplicationResponse>>(
          `/api/v1/applications?${params.toString()}`
        );
        setApplications(res.data?.content ?? []);
      } catch (err: any) {
        setError(err?.response?.data?.error ?? "Couldn't load applications.");
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [postingFilter]
  );

  useEffect(() => {
    void loadApplications('initial');
  }, [loadApplications]);

  const columnsByStatus = useMemo(() => {
    const map = new Map<ApplicationStatus, ApplicationResponse[]>();
    for (const c of [...PRIMARY_COLUMNS, ...ARCHIVED_COLUMNS]) map.set(c.key, []);
    for (const a of applications) {
      const list = map.get(a.status);
      if (list) list.push(a);
    }
    return map;
  }, [applications]);

  const activeApp = useMemo(
    () => (activeId ? applications.find((a) => a.id === activeId) ?? null : null),
    [activeId, applications]
  );

  function onDragStart(e: DragStartEvent) {
    setActiveId(String(e.active.id));
  }

  async function onDragEnd(e: DragEndEvent) {
    const droppedId = String(e.active.id);
    setActiveId(null);
    if (!e.over) return;
    const targetStatus = String(e.over.id) as ApplicationStatus;
    const app = applications.find((a) => a.id === droppedId);
    if (!app || app.status === targetStatus) return;

    const prevStatus = app.status;
    // Optimistic
    setApplications((prev) =>
      prev.map((a) =>
        a.id === droppedId
          ? { ...a, status: targetStatus, statusUpdatedAt: new Date().toISOString() }
          : a
      )
    );

    try {
      const res = await api.patch<ApplicationResponse>(
        `/api/v1/applications/${droppedId}/status`,
        { status: targetStatus }
      );
      setApplications((prev) => prev.map((a) => (a.id === droppedId ? res.data : a)));
    } catch (err: any) {
      // Revert the card to its original column.
      setApplications((prev) =>
        prev.map((a) => (a.id === droppedId ? { ...a, status: prevStatus } : a))
      );
      // Phase 1.1b: illegal lifecycle transitions return 400 with a clear
      // server message (e.g. "Cannot move application from APPLIED to HIRED").
      // Surface that verbatim so recruiters know why the drag was refused;
      // fall back to a generic message for network / 500 errors.
      const serverMsg = err?.response?.data?.error;
      toast.error(
        typeof serverMsg === 'string' && serverMsg.length > 0
          ? serverMsg
          : "Couldn't update status. Try again."
      );
    }
  }

  async function downloadResume(resumeId: string, fileName?: string) {
    try {
      const res = await api.get(`/api/v1/resumes/${resumeId}/download`, {
        responseType: 'blob',
      });
      const ctRaw = res.headers['content-type'];
      const contentType =
        typeof ctRaw === 'string' ? ctRaw : 'application/octet-stream';
      const blob = new Blob([res.data], { type: contentType });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      const cdRaw = res.headers['content-disposition'];
      const disposition = typeof cdRaw === 'string' ? cdRaw : '';
      const m = disposition.match(/filename="?([^";]+)"?/);
      a.download = m?.[1] ?? fileName ?? `resume-${resumeId}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? 'Resume download failed');
    }
  }

  function onDrawerUpdate(updated: ApplicationResponse) {
    setApplications((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
  }

  const visibleColumns = showArchived
    ? [...PRIMARY_COLUMNS, ...ARCHIVED_COLUMNS]
    : PRIMARY_COLUMNS;

  return (
    <div>
      {/* Top filter bar */}
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-500">Filter:</span>
          <select
            value={postingFilter}
            onChange={(e) => setPostingFilter(e.target.value)}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
            aria-label="Filter by job posting"
          >
            <option value="">All postings</option>
            {postings.map((p) => (
              <option key={p.id} value={p.id}>
                {p.title}
              </option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => setShowArchived((v) => !v)}
            aria-pressed={showArchived}
            className={
              'rounded-full px-3 py-1.5 text-xs font-medium transition-colors ' +
              (showArchived
                ? 'bg-accent text-white'
                : 'bg-gray-200 text-gray-600 hover:bg-gray-300')
            }
          >
            {showArchived ? '✓ Showing archived' : 'Show archived'}
          </button>

          <button
            type="button"
            onClick={() => void loadApplications('refresh')}
            disabled={refreshing}
            aria-label="Refresh"
            className="flex h-9 w-9 items-center justify-center rounded-md border border-gray-300 bg-white text-gray-600 transition-colors hover:bg-gray-50 disabled:opacity-50"
          >
            <RefreshCw
              className={'h-4 w-4 ' + (refreshing ? 'animate-spin' : '')}
              strokeWidth={2}
            />
          </button>
        </div>
      </div>

      {error && !loading && (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          <p className="mb-2">{error}</p>
          <button
            type="button"
            onClick={() => void loadApplications('initial')}
            className="rounded border border-red-300 px-3 py-1 text-xs font-medium hover:bg-red-100"
          >
            Retry
          </button>
        </div>
      )}

      {/* Board */}
      {loading ? (
        <SkeletonBoard />
      ) : (
        <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
          <div className="flex flex-row gap-4 overflow-x-auto pb-4">
            {visibleColumns.map((col) => (
              <KanbanColumn
                key={col.key}
                status={col.key}
                label={col.label}
                items={columnsByStatus.get(col.key) ?? []}
                onViewDetails={(id) =>
                  router.push(`/careers/erm/applications/${id}`)
                }
                onDownloadResume={downloadResume}
              />
            ))}
          </div>

          <DragOverlay>
            {activeApp ? (
              <ApplicationCard
                application={activeApp}
                onViewDetails={() => {}}
                onDownloadResume={() => {}}
                overlay
              />
            ) : null}
          </DragOverlay>
        </DndContext>
      )}

      <ApplicationDetailDrawer
        applicationId={openAppId}
        onClose={() => setOpenAppId(null)}
        onUpdated={onDrawerUpdate}
      />
    </div>
  );
}

function KanbanColumn({
  status,
  label,
  items,
  onViewDetails,
  onDownloadResume,
}: {
  status: ApplicationStatus;
  label: string;
  items: ApplicationResponse[];
  onViewDetails: (id: string) => void;
  onDownloadResume: (resumeId: string, fileName?: string) => void | Promise<void>;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: status });

  return (
    <div
      ref={setNodeRef}
      className={
        'flex max-h-[calc(100vh-200px)] min-h-[200px] w-72 shrink-0 flex-col gap-3 rounded-lg bg-gray-100 p-3 transition-all ' +
        (isOver
          ? 'ring-2 ring-primary-700 ring-offset-2 ring-offset-gray-100'
          : '')
      }
    >
      <div className="flex items-center justify-between px-2 py-1">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-gray-700">
          {label}
        </h3>
        <span className="min-w-[24px] rounded-full bg-gray-200 px-2 py-0.5 text-center text-xs text-gray-600">
          {items.length}
        </span>
      </div>

      <div className="flex flex-1 flex-col gap-2 overflow-y-auto py-1">
        {items.length === 0 ? (
          <div className="py-8 text-center text-xs italic text-gray-400">
            Drop applications here
          </div>
        ) : (
          items.map((a) => (
            <ApplicationCard
              key={a.id}
              application={a}
              onViewDetails={onViewDetails}
              onDownloadResume={onDownloadResume}
            />
          ))
        )}
      </div>
    </div>
  );
}

function SkeletonBoard() {
  return (
    <div className="flex flex-row gap-4 overflow-x-auto pb-4">
      {PRIMARY_COLUMNS.map((c) => (
        <div
          key={c.key}
          className="flex w-72 shrink-0 flex-col gap-3 rounded-lg bg-gray-100 p-3"
        >
          <div className="flex items-center justify-between px-2 py-1">
            <div className="h-3 w-24 animate-pulse rounded bg-gray-300" />
            <div className="h-4 w-6 animate-pulse rounded-full bg-gray-300" />
          </div>
          <div className="flex flex-col gap-2 py-1">
            {[0, 1].map((i) => (
              <div
                key={i}
                className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-3"
              >
                <div className="h-3.5 w-32 animate-pulse rounded bg-gray-200" />
                <div className="h-3 w-40 animate-pulse rounded bg-gray-200" />
                <div className="h-3 w-20 animate-pulse rounded bg-gray-200" />
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
