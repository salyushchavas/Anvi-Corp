'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { CalendarClock, ChevronLeft, PencilLine, Zap, X } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import AssignReportingStructureModal from '@/components/erm/newhire/AssignReportingStructureModal';
import UpdateStartDateModal from '@/components/erm/offers/UpdateStartDateModal';
import AssignPacketModal from '@/components/erm/documents/AssignPacketModal';
import AssignCompanyEmailDialog from '@/components/erm/mail/AssignCompanyEmailDialog';
import OnboardingStepTracker from '@/components/erm/onboarding/OnboardingStepTracker';
import { Mail } from 'lucide-react';
import toast from 'react-hot-toast';
import type { NewHireDetail, UserStub } from '@/components/erm/offers/types';

export default function NewHireDetailPage() {
  const params = useParams<{ lifecycleId: string }>();
  const id = params?.lifecycleId;
  const [data, setData] = useState<NewHireDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [modal, setModal] = useState<'reporting' | 'startdate' | 'packet' | 'manager' | 'joining' | 'companyEmail' | null>(null);
  const [activating, setActivating] = useState(false);
  const [activateErr, setActivateErr] = useState<string | null>(null);
  // Bumped after any sibling modal that mutates onboarding-tracker
  // state (assign packet, set joining date, assign company email) so
  // the OnboardingStepTracker re-fetches its own payload. The tracker
  // has its own internal fetch — the page's load() refreshes
  // NewHireDetail but not the tracker payload, hence the explicit cue.
  const [trackerRefreshKey, setTrackerRefreshKey] = useState(0);
  const bumpTracker = () => setTrackerRefreshKey((n) => n + 1);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<NewHireDetail>(`/api/v1/erm/new-hire/${id}`);
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load new hire');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  async function activateNow() {
    if (!data) return;
    if (!confirm(
      'Activate this intern now? This bypasses the start-date gate and '
      + 'flips them to ACTIVE_INTERN immediately. Only use when ERM has '
      + 'documented an early-start exception.',
    )) return;
    setActivating(true);
    setActivateErr(null);
    try {
      await api.post(`/api/v1/intern-lifecycles/${data.internLifecycleId}/activate`);
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setActivateErr(ax.response?.data?.error ?? ax.message ?? 'Activation failed');
    } finally {
      setActivating(false);
    }
  }

  if (loading && !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="New Hire" />
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
        </DashboardLayout>
      </ProtectedRoute>
    );
  }
  if (err || !data) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="New Hire" />
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err ?? 'New hire not found'}
          </p>
        </DashboardLayout>
      </ProtectedRoute>
    );
  }

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Link
          href="/careers/erm/new-hire"
          className="mb-3 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <ChevronLeft className="h-4 w-4" /> Back to New Hire List
        </Link>
        <PageHeader
          title={data.internName ?? 'New Hire'}
          subtitle={`${data.employeeId} · ${data.internEmail ?? ''}`}
        />

        <div className="grid gap-6 lg:grid-cols-3">
          <main className="lg:col-span-2 space-y-6">
            <OnboardingStepTracker
              lifecycleId={data.internLifecycleId}
              onChanged={() => void load()}
              refreshKey={trackerRefreshKey}
              onOpenAssignPacketModal={() => setModal('packet')}
              onOpenCompanyEmailModal={() => setModal('companyEmail')}
              onOpenJoiningDateModal={() => setModal('joining')}
              companyEmailReady={
                data.mailHandoverState !== null
                && data.mailHandoverState !== undefined
                && !!data.employeeId
              }
              mailHandoverState={data.mailHandoverState}
            />
            {data.signedOffer && (
              <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                <h3 className="text-sm font-semibold text-slate-900">Signed offer summary</h3>
                <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
                  <Row label="Role" value={data.signedOffer.roleTitle} />
                  <Row label="Worksite" value={data.signedOffer.worksite} />
                  <Row label="Compensation" value={data.signedOffer.compensationSummary} />
                  <Row label="Hours / week"
                    value={data.signedOffer.expectedHoursPerWeek?.toString()} />
                  <Row label="Tentative start" value={data.signedOffer.tentativeStartDate} />
                  <Row label="Signed at"
                    value={data.signedOffer.signedAt ? new Date(data.signedOffer.signedAt).toLocaleString() : null} />
                </div>
                {/* Legacy signed-PDF download has been removed —
                    IDMS-signed offers store the signature image inline
                    on the offer row; pre-IDMS archived PDFs are
                    available through the Document Vault. */}
              </section>
            )}

            <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900">Reporting structure</h3>
                <span className="text-[11px] text-slate-500">
                  Trainer + Evaluator auto-linked from system config at sign time.
                </span>
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                <RoleCard role="Trainer" stub={data.trainer} />
                <RoleCard role="Evaluator" stub={data.evaluator} />
                <RoleCardWithAssign
                  role="Manager"
                  stub={data.manager}
                  onAssign={() => setModal('manager')}
                />
              </div>
              <div className="mt-4 flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setModal('reporting')}
                  className="text-xs font-medium text-brand-700 hover:underline"
                >
                  Edit Trainer/Evaluator (legacy)
                </button>
              </div>
            </section>

            <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-900">Next steps</h3>
              <div className="mt-3 flex flex-wrap items-center gap-3">
                <button
                  type="button"
                  onClick={() => setModal('packet')}
                  disabled={data.onboardingAssigned}
                  title={data.onboardingAssigned ? 'Already assigned' : ''}
                  className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300"
                >
                  {data.onboardingAssigned
                    ? 'Document packet assigned ✓'
                    : 'Assign document packet…'}
                </button>
                {/* ERM Pass 2 — joining-date control. Only enabled after
                    docs accepted (ONBOARDING_ACCEPTED) so the date is
                    committed at the right moment in the funnel. The
                    activation job uses this date (not the offer's
                    tentative_start_date) to flip the lifecycle. */}
                <button
                  type="button"
                  onClick={() => setModal('joining')}
                  disabled={!data.docsAccepted}
                  title={
                    data.docsAccepted
                      ? 'Set the date the intern auto-activates on'
                      : 'Available once onboarding documents are accepted'
                  }
                  className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-4 py-2 text-sm font-semibold text-brand-800 hover:bg-brand-50 disabled:opacity-50"
                >
                  <CalendarClock className="h-4 w-4" />
                  {data.joiningDate ? 'Update joining date' : 'Set joining date'}
                </button>
                {data.canActivateNow && (
                  <button
                    type="button"
                    onClick={activateNow}
                    disabled={activating}
                    title="Bypass the joining-date gate and activate this intern immediately"
                    className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-amber-50 px-4 py-2 text-sm font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-60"
                  >
                    <Zap className="h-4 w-4" />
                    {activating ? 'Activating…' : 'Activate now'}
                  </button>
                )}
              </div>
              {data.docsAccepted && (
                <p className="mt-2 text-[11px] text-slate-500">
                  {data.joiningDate
                    ? `Joining date set to ${data.joiningDate} — the intern
                       auto-activates on/after that date (next scan: ≤ 10 min).`
                    : 'Documents accepted. Set a joining date to schedule '
                      + 'auto-activation, or use Activate now for an early start.'}
                </p>
              )}
              {activateErr && (
                <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                  {activateErr}
                </p>
              )}
            </section>

            <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900">Tentative start date</h3>
                <button
                  type="button"
                  onClick={() => setModal('startdate')}
                  className="text-xs font-medium text-brand-700 hover:underline"
                >
                  Update
                </button>
              </div>
              <p className="mt-2 text-sm text-slate-700">
                {data.tentativeStartDate ?? 'Not set'}
              </p>
            </section>

            {/* Mail bridge Phase 5 — company-email handover. Branches on
                mailHandoverState; renders nothing pre-EMPLOYEE_ID_CREATED. */}
            <CompanyEmailSection
              data={data}
              onOpenAssign={() => setModal('companyEmail')}
            />
          </main>

          <aside className="space-y-4">
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Lifecycle</h3>
              <Row label="Status" value={data.activeStatus} />
              <Row label="Hired at"
                value={data.hiredAt ? new Date(data.hiredAt).toLocaleString() : null} />
              {data.reportingStructureCompletedAt && (
                <Row label="Structure assigned"
                  value={new Date(data.reportingStructureCompletedAt).toLocaleString()} />
              )}
            </section>
            {data.erm && (
              <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">ERM</h3>
                <p className="mt-2 text-sm font-medium text-slate-900">{data.erm.fullName}</p>
                <p className="text-[11px] text-slate-500">{data.erm.email}</p>
              </section>
            )}
          </aside>
        </div>

        {modal === 'reporting' && (
          <AssignReportingStructureModal
            open
            lifecycleId={data.internLifecycleId}
            currentTrainerId={data.trainer?.userId}
            currentEvaluatorId={data.evaluator?.userId}
            currentManagerId={data.manager?.userId}
            onClose={() => setModal(null)}
            onApplied={() => void load()}
          />
        )}
        {modal === 'startdate' && (
          <UpdateStartDateModal
            open
            lifecycleId={data.internLifecycleId}
            currentDate={data.tentativeStartDate}
            onClose={() => setModal(null)}
            onApplied={() => void load()}
          />
        )}
        {modal === 'packet' && (
          <AssignPacketModal
            open
            lifecycleId={data.internLifecycleId}
            internName={data.internName ?? null}
            onClose={() => setModal(null)}
            onAssigned={() => { setModal(null); bumpTracker(); void load(); }}
          />
        )}
        {modal === 'manager' && (
          <AssignManagerModal
            lifecycleId={data.internLifecycleId}
            currentManagerId={data.manager?.userId ?? null}
            onClose={() => setModal(null)}
            onApplied={() => { setModal(null); void load(); }}
          />
        )}
        {modal === 'joining' && (
          <SetJoiningDateModal
            lifecycleId={data.internLifecycleId}
            currentDate={data.joiningDate}
            onClose={() => setModal(null)}
            onApplied={() => { setModal(null); bumpTracker(); void load(); }}
          />
        )}
        {modal === 'companyEmail' && (
          <AssignCompanyEmailDialog
            open
            userId={data.internUserId}
            internName={data.internName ?? null}
            personalEmail={data.internEmail ?? null}
            onClose={() => setModal(null)}
            onAssigned={(addr) => {
              toast.success(
                `Company email ${addr} created — starting credentials emailed to `
                + `${data.internEmail ?? 'the intern’s personal email'}.`,
              );
              setModal(null);
              bumpTracker();
              void load();
            }}
          />
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

/**
 * ERM Pass 2 — small modal for setting / clearing
 * intern_lifecycles.joining_date. Date today/past triggers a sync
 * activation attempt on the server; future just persists and waits
 * for the next scan. Clear with the empty input.
 */
function SetJoiningDateModal({
  lifecycleId, currentDate, onClose, onApplied,
}: {
  lifecycleId: string;
  currentDate: string | null;
  onClose: () => void;
  onApplied: () => void;
}) {
  const [date, setDate] = useState(currentDate ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/intern-lifecycles/${lifecycleId}/joining-date`, {
        joiningDate: date || null,
      });
      onApplied();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Set joining date</h3>
            <p className="text-xs text-slate-500">
              Distinct from the offer&rsquo;s tentative start date. The intern
              auto-activates on this date — today/past activates on the next
              scan (≤ 10 min); future waits. Clear to cancel scheduled activation.
            </p>
          </div>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-3 p-5">
          <label className="block">
            <span className="text-sm font-medium text-slate-800">Joining date</span>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
            <p className="mt-1 text-[11px] text-slate-500">
              Leave blank to clear.
            </p>
          </label>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="mb-2 text-sm">
      <p className="text-[11px] uppercase text-slate-500">{label}</p>
      <p className="text-slate-800">{value && value !== 'null' ? value : '—'}</p>
    </div>
  );
}

function RoleCard({
  role, stub,
}: {
  role: string;
  stub: { userId: string; fullName: string | null; email: string | null } | null;
}) {
  return (
    <div className={
      'rounded-md border p-3 ' +
      (stub ? 'border-green-200 bg-green-50/30' : 'border-dashed border-slate-300 bg-slate-50')
    }>
      <p className="text-[11px] font-semibold uppercase text-slate-500">{role}</p>
      {stub ? (
        <>
          <p className="mt-1 text-sm font-medium text-slate-900">{stub.fullName}</p>
          <p className="text-[11px] text-slate-500">{stub.email}</p>
        </>
      ) : (
        <p className="mt-1 text-sm text-slate-500">Not assigned</p>
      )}
    </div>
  );
}

// Phase 8.6.4 — Manager card with an inline assign/edit action. Always
// editable, never blocking.
function RoleCardWithAssign({
  role, stub, onAssign,
}: {
  role: string;
  stub: { userId: string; fullName: string | null; email: string | null } | null;
  onAssign: () => void;
}) {
  return (
    <div className={
      'rounded-md border p-3 ' +
      (stub ? 'border-green-200 bg-green-50/30' : 'border-dashed border-slate-300 bg-slate-50')
    }>
      <div className="flex items-center justify-between">
        <p className="text-[11px] font-semibold uppercase text-slate-500">{role}</p>
        <button
          type="button"
          onClick={onAssign}
          className="inline-flex items-center gap-0.5 text-[11px] font-medium text-brand-700 hover:underline"
          title={stub ? 'Change manager' : 'Assign manager'}
        >
          <PencilLine className="h-3 w-3" />
          {stub ? 'Change' : 'Assign'}
        </button>
      </div>
      {stub ? (
        <>
          <p className="mt-1 text-sm font-medium text-slate-900">{stub.fullName}</p>
          <p className="text-[11px] text-slate-500">{stub.email}</p>
        </>
      ) : (
        <p className="mt-1 text-sm text-slate-500">
          Not assigned <span className="text-slate-400">(non-blocking)</span>
        </p>
      )}
    </div>
  );
}

// Phase 8.6.4 — small inline modal for setting / changing / clearing the
// Manager on an InternLifecycle. Loads eligible managers from the legacy
// /eligible-managers endpoint, PATCHes /manager on submit. Non-blocking.
function AssignManagerModal({
  lifecycleId, currentManagerId, onClose, onApplied,
}: {
  lifecycleId: string;
  currentManagerId: string | null;
  onClose: () => void;
  onApplied: () => void;
}) {
  const [eligible, setEligible] = useState<UserStub[]>([]);
  const [managerId, setManagerId] = useState<string>(currentManagerId ?? '');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const res = await api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-managers');
        setEligible(res.data ?? []);
      } catch {
        setEligible([]);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  async function submit() {
    setSubmitting(true);
    setErr(null);
    try {
      await api.patch(`/api/v1/erm/new-hire/${lifecycleId}/manager`, {
        managerUserId: managerId || null,
      });
      onApplied();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to save');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        <div className="flex items-start justify-between border-b border-slate-200 px-5 py-3">
          <div>
            <h3 className="text-base font-semibold text-slate-900">Assign Manager</h3>
            <p className="text-xs text-slate-500">
              Manager handles timesheet approvals + escalations.
              Non-blocking — can be changed anytime.
            </p>
          </div>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-3 p-5">
          {loading ? (
            <div className="h-10 animate-pulse rounded bg-slate-100" />
          ) : (
            <label className="block">
              <span className="text-sm font-medium text-slate-800">Manager</span>
              <select
                value={managerId}
                onChange={(e) => setManagerId(e.target.value)}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                <option value="">— Unassigned —</option>
                {eligible.map((u) => (
                  <option key={u.userId} value={u.userId}>
                    {u.fullName} · {u.email}
                  </option>
                ))}
              </select>
              {eligible.length === 0 && (
                <p className="mt-1 text-[11px] text-amber-700">
                  No users found with the MANAGER role.
                </p>
              )}
            </label>
          )}
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>

        <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-700">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={submitting}
            className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Mail bridge Phase 5 — handover section. Branches on
 * data.mailHandoverState:
 *   PERSONAL + employeeId          -> action button (opens dialog)
 *   PERSONAL but no employeeId     -> render nothing (pre-EMPLOYEE_ID_CREATED)
 *   PENDING_ACTIVATION             -> amber chip "awaiting activation"
 *   ACTIVATED                      -> green chip "active"
 * Uses the same card shell + chip palette as the page's other
 * sections so it slots in without a new design.
 */
function CompanyEmailSection({
  data, onOpenAssign,
}: {
  data: NewHireDetail;
  onOpenAssign: () => void;
}) {
  const state = data.mailHandoverState;
  const hasEmployeeId = !!data.employeeId;

  // Backend hasn't surfaced the field (legacy client) OR pre-handover
  // intern who hasn't reached EMPLOYEE_ID_CREATED -> render nothing so
  // the page is unchanged for non-handover users.
  if (!state) return null;
  if (state === 'PERSONAL' && !hasEmployeeId) return null;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">Company email</h3>
        {state === 'PENDING_ACTIVATION' && (
          <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2.5 py-0.5 text-[11px] font-semibold text-amber-800 ring-1 ring-amber-200">
            awaiting activation
          </span>
        )}
        {state === 'ACTIVATED' && (
          <span className="inline-flex items-center gap-1 rounded-full bg-green-50 px-2.5 py-0.5 text-[11px] font-semibold text-green-700 ring-1 ring-green-200">
            active
          </span>
        )}
      </div>

      {state === 'PERSONAL' && (
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={onOpenAssign}
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
          >
            <Mail className="h-4 w-4" />
            Assign company email
          </button>
          <p className="text-[11px] text-slate-500">
            Provisions a {`<localpart>@skyzentech.com`} mailbox and emails the
            starting credentials to the intern&rsquo;s personal Gmail. After
            they change the password in the mailbox, the same credentials
            sign them into the dashboard.
          </p>
        </div>
      )}

      {state === 'PENDING_ACTIVATION' && (
        <p className="mt-2 text-sm text-slate-700">
          <span className="font-mono">{data.internEmail}</span> &mdash; intern
          must log into <span className="font-mono">/mail</span> and change
          the starting password before they can sign into the dashboard.
          {data.personalEmail && (
            <>
              {' '}Credentials emailed to{' '}
              <span className="font-mono">{data.personalEmail}</span>.
            </>
          )}
        </p>
      )}

      {state === 'ACTIVATED' && (
        <p className="mt-2 text-sm text-slate-700">
          <span className="font-mono">{data.internEmail}</span> &mdash; signs
          into both the mailbox and the dashboard with the same password.
          Notifications now land in the company mailbox.
        </p>
      )}
    </section>
  );
}
