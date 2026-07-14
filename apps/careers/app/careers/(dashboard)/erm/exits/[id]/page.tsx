'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import AssetStatusModal from '@/components/erm/exits/AssetStatusModal';
import ManagerOverrideModal from '@/components/erm/exits/ManagerOverrideModal';
import {
  CHECKLIST_LABEL,
  EXIT_TYPE_TONE,
  type AssetStatus,
  type ChecklistItemRow,
  type ChecklistStatus,
  type ErmExitDetail,
} from '@/components/erm/exits/types';

const POLL_MS = 60_000;

export default function ExitDetailPage() {
  // useParams (next/navigation) — Next 14.2 delivers params as a plain
  // object, not a Promise; React's use() rejects non-thenables with #438.
  const params = useParams<{ id: string }>();
  const id = params?.id ?? '';
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN', 'MANAGER']}>
      <DashboardLayout>
        <Body id={id} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ id }: { id: string }) {
  const [d, setD] = useState<ErmExitDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [showAssets, setShowAssets] = useState(false);
  const [showOverride, setShowOverride] = useState(false);
  const [showLinkEval, setShowLinkEval] = useState(false);
  const [noteDraft, setNoteDraft] = useState('');
  const [savingNote, setSavingNote] = useState(false);

  const load = useCallback(async () => {
    try {
      const res = await api.get<ErmExitDetail>(`/api/v1/erm/exits/${id}`);
      setD(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load exit');
    }
  }, [id]);

  useEffect(() => {
    void load();
    const t = setInterval(() => {
      void load();
    }, POLL_MS);
    return () => clearInterval(t);
  }, [load]);

  async function updateItem(
    itemKey: string,
    status: ChecklistStatus,
    note?: string,
  ) {
    try {
      const res = await api.post<ErmExitDetail>(
        `/api/v1/erm/exits/${id}/checklist/${itemKey}`,
        { status, note },
      );
      setD(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Update failed');
    }
  }

  async function retryRevocation() {
    try {
      const res = await api.post<ErmExitDetail>(
        `/api/v1/erm/exits/${id}/retry-revocation`,
      );
      setD(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Retry failed');
    }
  }

  async function saveNote() {
    if (noteDraft.trim().length < 5) return;
    setSavingNote(true);
    try {
      await api.post(`/api/v1/erm/exits/${id}/internal-note`, {
        note: noteDraft.trim(),
      });
      setNoteDraft('');
      await load();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Note add failed');
    } finally {
      setSavingNote(false);
    }
  }

  if (err && !d) {
    return (
      <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </p>
    );
  }
  if (!d) return <div className="h-64 animate-pulse rounded-lg bg-slate-100" />;

  const pendingCount = d.checklist.filter((c) => c.status === 'PENDING').length;
  const completeCount = d.checklist.filter((c) =>
    ['COMPLETE', 'NOT_APPLICABLE', 'WAIVED'].includes(c.status),
  ).length;
  const totalCount = d.checklist.length;
  const assets: AssetStatus | null = (() => {
    if (!d.assetStatusJson) return null;
    try { return JSON.parse(d.assetStatusJson) as AssetStatus; }
    catch { return null; }
  })();

  return (
    <>
      <PageHeader
        title={d.internName ?? 'Exit record'}
        subtitle={`${d.employeeId ?? ''} · ${d.internEmail ?? ''} · ${d.daysSinceInitiate}d since initiate`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white px-4 py-3">
        <span
          className={
            'rounded-full px-2 py-0.5 text-[11px] font-semibold ' +
            EXIT_TYPE_TONE[d.exitType]
          }
        >
          {d.exitType}
        </span>
        <span className="text-xs text-slate-700">
          Exit {d.exitDate ?? '—'}
          {d.lastWorkingDay && d.lastWorkingDay !== d.exitDate && (
            <span className="ml-1 text-[11px] text-slate-500">
              (LWD {d.lastWorkingDay})
            </span>
          )}
        </span>
        {d.reasonCode && (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-700">
            {d.reasonCode}
          </span>
        )}
        {d.managerOverrideAt && (
          <span className="rounded-full bg-red-100 px-2 py-0.5 text-[11px] text-red-700">
            Manager override active
          </span>
        )}
        <div className="ml-auto flex gap-2">
          <Link
            href="/careers/erm/exits"
            className="rounded-md border border-slate-200 px-3 py-1 text-xs text-slate-700 hover:bg-slate-50"
          >
            ← Queue
          </Link>
          <Link
            href={`/careers/erm/exits/${id}/feedback`}
            className="rounded-md border border-slate-200 px-3 py-1 text-xs text-slate-700 hover:bg-slate-50"
          >
            Feedback
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="lg:col-span-2 space-y-4">
          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-900">
                Exit checklist
              </h3>
              <span className="text-xs text-slate-500">
                {completeCount} of {totalCount} resolved · {pendingCount} pending
              </span>
            </div>
            <ul className="space-y-2">
              {d.checklist.map((c) => (
                <ChecklistRow
                  key={c.id}
                  item={c}
                  onMark={(s, n) => updateItem(c.itemKey, s, n)}
                  onOpenAssets={
                    c.itemKey === 'ASSETS_RETURNED'
                      ? () => setShowAssets(true)
                      : undefined
                  }
                  onOpenLinkEval={
                    c.itemKey === 'FINAL_EVALUATION'
                      ? () => setShowLinkEval(true)
                      : undefined
                  }
                  onRetryRevocation={
                    c.itemKey === 'GITHUB_REVOKED'
                      ? () => void retryRevocation()
                      : undefined
                  }
                  revocationSummary={
                    c.itemKey === 'GITHUB_REVOKED'
                      ? d.accessRevocationSummary
                      : null
                  }
                  feedbackSubmitted={
                    c.itemKey === 'EXIT_FEEDBACK_SUBMITTED'
                      ? d.feedbackSubmitted
                      : null
                  }
                />
              ))}
            </ul>
          </div>

          {pendingCount > 0 && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-4">
              <h3 className="mb-2 text-sm font-semibold text-amber-900">
                Manager override
              </h3>
              <p className="mb-2 text-xs text-amber-800">
                If business needs require closing this exit before every item
                resolves, a MANAGER (this intern's reporting manager) or
                SUPER_ADMIN can waive the remaining {pendingCount} PENDING
                item{pendingCount === 1 ? '' : 's'} with a documented reason.
              </p>
              <button
                type="button"
                onClick={() => setShowOverride(true)}
                className="rounded-md bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-700"
              >
                Manager override…
              </button>
            </div>
          )}

          {d.managerOverrideReason && (
            <div className="rounded-lg border border-slate-300 bg-slate-50 p-4">
              <p className="text-xs font-semibold uppercase text-slate-600">
                Override reason
              </p>
              <p className="mt-1 whitespace-pre-wrap text-sm text-slate-800">
                {d.managerOverrideReason}
              </p>
              <p className="mt-1 text-[11px] text-slate-500">
                {d.managerOverrideAt &&
                  new Date(d.managerOverrideAt).toLocaleString()}
              </p>
            </div>
          )}

          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <h3 className="mb-2 text-sm font-semibold text-slate-900">
              Add internal note
              <span className="ml-2 text-[10px] font-normal text-red-600">
                ERM-only
              </span>
            </h3>
            <textarea
              rows={2}
              value={noteDraft}
              onChange={(e) => setNoteDraft(e.target.value)}
              placeholder="At least 5 characters — appended to the internal log"
              className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
            />
            <div className="mt-2 flex justify-end">
              <button
                type="button"
                disabled={savingNote || noteDraft.trim().length < 5}
                onClick={() => void saveNote()}
                className="rounded-md border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                {savingNote ? 'Saving…' : 'Append note'}
              </button>
            </div>
            {d.internalNotes && (
              <pre className="mt-3 max-h-48 overflow-auto whitespace-pre-wrap rounded bg-slate-50 p-2 text-[11px] text-slate-700">
                {d.internalNotes}
              </pre>
            )}
          </div>
        </section>

        <aside className="space-y-3">
          <section className="rounded-lg border border-slate-200 bg-white p-4">
            <h3 className="mb-2 text-sm font-semibold text-slate-900">
              Intern summary
            </h3>
            <dl className="space-y-1 text-xs text-slate-700">
              <Row k="Intern" v={d.internName} />
              <Row k="Email" v={d.internEmail} />
              <Row k="Employee ID" v={d.employeeId} />
              <Row k="Final timesheets" v={d.finalTimesheetStatus} />
              <Row k="Rehire eligible" v={d.rehireEligible ? 'Yes' : 'No'} />
              <Row k="Initiated by" v={d.initiatedByName} />
              <Row
                k="Created"
                v={d.createdAt ? new Date(d.createdAt).toLocaleString() : null}
              />
            </dl>
            <div className="mt-3 space-y-1 text-[11px]">
              <Link
                href={`/careers/erm/active-interns/${d.internLifecycleId}`}
                className="block text-brand-700 hover:underline"
              >
                Active intern monitor →
              </Link>
              <Link
                href={`/careers/erm/compliance/${d.internUserId}`}
                className="block text-brand-700 hover:underline"
              >
                Compliance tracker →
              </Link>
            </div>
          </section>

          {assets && (
            <section className="rounded-lg border border-slate-200 bg-white p-4">
              <h3 className="mb-2 text-sm font-semibold text-slate-900">
                Asset status
              </h3>
              <dl className="space-y-1 text-xs">
                <Row k="Laptop" v={assets.laptopReturned ? '✓' : '—'} />
                <Row k="Badge" v={assets.badgeReturned ? '✓' : '—'} />
                <Row
                  k="Building"
                  v={assets.buildingAccessRemoved ? '✓' : '—'}
                />
                <Row k="Parking" v={assets.parkingPassReturned ? '✓' : '—'} />
                <Row k="Keys" v={assets.keysReturned ? '✓' : '—'} />
              </dl>
              {assets.otherNotes && (
                <p className="mt-2 rounded bg-slate-50 p-2 text-[11px] text-slate-700">
                  {assets.otherNotes}
                </p>
              )}
            </section>
          )}
        </aside>
      </div>

      {showAssets && (
        <AssetStatusModal
          exitRecordId={id}
          initial={assets}
          onClose={() => setShowAssets(false)}
          onSaved={(u) => {
            setD(u);
            setShowAssets(false);
          }}
        />
      )}
      {showOverride && (
        <ManagerOverrideModal
          exitRecordId={id}
          pendingCount={pendingCount}
          onClose={() => setShowOverride(false)}
          onDone={(u) => {
            setD(u);
            setShowOverride(false);
          }}
        />
      )}
      {showLinkEval && (
        <LinkEvaluationModal
          exitRecordId={id}
          internLifecycleId={d.internLifecycleId}
          onClose={() => setShowLinkEval(false)}
          onLinked={(u) => {
            setD(u);
            setShowLinkEval(false);
          }}
        />
      )}
    </>
  );
}

function ChecklistRow({
  item,
  onMark,
  onOpenAssets,
  onOpenLinkEval,
  onRetryRevocation,
  revocationSummary,
  feedbackSubmitted,
}: {
  item: ChecklistItemRow;
  onMark: (s: ChecklistStatus, n?: string) => void;
  onOpenAssets?: () => void;
  onOpenLinkEval?: () => void;
  onRetryRevocation?: () => void;
  revocationSummary: string | null;
  feedbackSubmitted: boolean | null;
}) {
  const tone =
    item.status === 'COMPLETE'
      ? 'bg-green-50 border-green-200'
      : item.status === 'WAIVED'
        ? 'bg-slate-50 border-slate-200'
        : item.status === 'NOT_APPLICABLE'
          ? 'bg-slate-100 border-slate-300'
          : 'bg-white border-amber-200';
  return (
    <li className={'rounded-md border px-3 py-2 ' + tone}>
      <div className="flex flex-wrap items-center gap-2">
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium text-slate-900">
            {CHECKLIST_LABEL[item.itemKey] ?? item.itemKey}
          </div>
          <div className="text-[11px] text-slate-500">
            {item.status}
            {item.completedAt && (
              <span>
                {' '}
                · {new Date(item.completedAt).toLocaleString()}
                {item.completedByName && ' by ' + item.completedByName}
              </span>
            )}
          </div>
          {item.note && (
            <p className="mt-1 text-[11px] text-slate-700">{item.note}</p>
          )}
          {revocationSummary && (
            <p className="mt-1 rounded bg-slate-100 p-1 text-[11px] text-slate-700">
              {revocationSummary}
            </p>
          )}
          {feedbackSubmitted === false && (
            <p className="mt-1 text-[11px] text-amber-700">
              Intern has not yet submitted exit feedback.
            </p>
          )}
        </div>
        <div className="flex gap-1">
          {onOpenLinkEval && item.status !== 'COMPLETE' && (
            <button
              type="button"
              onClick={onOpenLinkEval}
              className="rounded-md border border-slate-300 px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-50"
            >
              Link evaluation
            </button>
          )}
          {onOpenAssets && (
            <button
              type="button"
              onClick={onOpenAssets}
              className="rounded-md border border-slate-300 px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-50"
            >
              Edit assets
            </button>
          )}
          {onRetryRevocation && item.status !== 'COMPLETE' && (
            <button
              type="button"
              onClick={onRetryRevocation}
              className="rounded-md border border-amber-300 px-2 py-0.5 text-[11px] text-amber-700 hover:bg-amber-100"
            >
              Retry revocation
            </button>
          )}
          {item.status !== 'COMPLETE' && !onOpenAssets && !onOpenLinkEval && (
            <button
              type="button"
              onClick={() => onMark('COMPLETE')}
              className="rounded-md bg-green-600 px-2 py-0.5 text-[11px] font-semibold text-white hover:bg-green-700"
            >
              Mark complete
            </button>
          )}
          {item.status !== 'NOT_APPLICABLE' && item.status !== 'WAIVED' && (
            <button
              type="button"
              onClick={() => onMark('NOT_APPLICABLE')}
              className="rounded-md border border-slate-300 px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-50"
            >
              N/A
            </button>
          )}
        </div>
      </div>
    </li>
  );
}

function LinkEvaluationModal({
  exitRecordId,
  internLifecycleId,
  onClose,
  onLinked,
}: {
  exitRecordId: string;
  internLifecycleId: string;
  onClose: () => void;
  onLinked: (d: ErmExitDetail) => void;
}) {
  const [evaluationId, setEvaluationId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!evaluationId.trim()) return;
    setSubmitting(true);
    setErr(null);
    try {
      const res = await api.post<ErmExitDetail>(
        `/api/v1/erm/exits/${exitRecordId}/link-evaluation`,
        { evaluationId: evaluationId.trim() },
      );
      onLinked(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Link failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="border-b border-slate-200 px-5 py-3">
          <h3 className="text-sm font-semibold text-slate-900">
            Link FINAL evaluation
          </h3>
          <p className="text-xs text-slate-500">
            Paste the FINAL evaluation UUID for this intern lifecycle
            ({internLifecycleId.slice(0, 8)}). Must be PUBLISHED.
          </p>
        </div>
        <div className="px-5 py-4">
          <input
            value={evaluationId}
            onChange={(e) => setEvaluationId(e.target.value)}
            placeholder="Evaluation UUID"
            className="block w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
          />
          {err && (
            <p className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
              {err}
            </p>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={submitting || !evaluationId.trim()}
            onClick={() => void submit()}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50"
          >
            {submitting ? 'Linking…' : 'Link'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string | null | undefined }) {
  return (
    <div className="flex justify-between border-b border-slate-100 pb-0.5">
      <span className="text-slate-500">{k}</span>
      <span className="text-slate-800">{v ?? '—'}</span>
    </div>
  );
}
