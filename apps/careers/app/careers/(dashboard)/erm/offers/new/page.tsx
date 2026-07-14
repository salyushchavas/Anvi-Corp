'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';

interface CreateRequest {
  applicationId: string;
  roleTitle: string;
  technology: string;
  tentativeStartDate: string;
  compensationSummary: string;
  worksite: string;
  expectedHoursPerWeek: number;
  expiryDays: number;
  contingencies: string;
}

// useSearchParams is unsafe outside <Suspense> during static prerendering.
// Wrap the inner reader so Next 14 build doesn't bail.
export default function CreateOfferPage() {
  return (
    <Suspense fallback={null}>
      <CreateOfferPageInner />
    </Suspense>
  );
}

function CreateOfferPageInner() {
  const router = useRouter();
  const sp = useSearchParams();
  const applicationId = sp?.get('applicationId') ?? '';

  // Phase 8.6 — pre-fill defaults when entered from Decision Center /
  // Interview detail / Offers "New Offer" CTA. Tentative start date is
  // 14 days from today so ERM doesn't have to fill it manually each time.
  const defaultStartDate = (() => {
    const d = new Date();
    d.setDate(d.getDate() + 14);
    return d.toISOString().slice(0, 10);
  })();

  const [roleTitle, setRoleTitle] = useState('');
  const [technology, setTechnology] = useState('');
  const [tentativeStartDate, setTentativeStartDate] = useState(defaultStartDate);
  const [compensationSummary, setCompensationSummary] = useState('');
  const [worksite, setWorksite] = useState('Remote');
  const [expectedHoursPerWeek, setExpectedHoursPerWeek] = useState<number>(40);
  const [expiryDays, setExpiryDays] = useState<number>(7);
  const [contingencies, setContingencies] = useState(
    'Subject to satisfactory background check and verification of work authorization.',
  );
  const [preview, setPreview] = useState<{ subject: string; body: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Phase 8.6.1 — pull role title + worksite from the job posting the
  // applicant applied to so ERM doesn't retype them. JobPosting has no
  // technology column (yet), so technology stays blank for ERM to fill.
  const [autoFilled, setAutoFilled] = useState<{
    roleTitle?: string;
    worksite?: string;
    applicantName?: string;
  }>({});
  // Mirrors SelectionAckPolicy.needsAck(app) — when true, the applicant
  // is SELECTED but hasn't clicked "Receive my offer letter" yet, so
  // the Send-Offer endpoint would 409. Used to disable Submit + show a
  // warning so the ERM can't click into a guaranteed-fail call.
  const [needsSelectionAck, setNeedsSelectionAck] = useState(false);

  useEffect(() => {
    if (!applicationId) return;
    void (async () => {
      try {
        const res = await api.get<{
          applicant?: { firstName?: string; lastName?: string };
          job?: { title?: string | null; location?: string | null };
          availableActions?: { needsSelectionAck?: boolean };
        }>(`/api/v1/erm/applications/${applicationId}`);
        const jobTitle = res.data.job?.title?.trim() ?? '';
        const jobLocation = res.data.job?.location?.trim() ?? '';
        const applicantName = [res.data.applicant?.firstName, res.data.applicant?.lastName]
          .filter(Boolean).join(' ').trim();
        const filled: typeof autoFilled = {};
        if (jobTitle) {
          setRoleTitle(jobTitle);
          filled.roleTitle = jobTitle;
        }
        if (jobLocation) {
          setWorksite(jobLocation);
          filled.worksite = jobLocation;
        }
        if (applicantName) filled.applicantName = applicantName;
        setAutoFilled(filled);
        setNeedsSelectionAck(Boolean(res.data.availableActions?.needsSelectionAck));
      } catch {
        // Non-fatal — the form still works, just without pre-fill.
      }
    })();
  }, [applicationId]);

  const renderPreview = useCallback(async () => {
    if (!applicationId) return;
    try {
      const body: CreateRequest = {
        applicationId,
        roleTitle,
        technology,
        tentativeStartDate,
        compensationSummary,
        worksite,
        expectedHoursPerWeek,
        expiryDays,
        contingencies,
      };
      const res = await api.post<{ subject: string; body: string }>(
        '/api/v1/erm/offers/preview',
        body,
      );
      setPreview(res.data);
    } catch {
      setPreview(null);
    }
  }, [
    applicationId,
    roleTitle,
    technology,
    tentativeStartDate,
    compensationSummary,
    worksite,
    expectedHoursPerWeek,
    expiryDays,
    contingencies,
  ]);

  useEffect(() => {
    const t = setTimeout(() => { void renderPreview(); }, 500);
    return () => clearTimeout(t);
  }, [renderPreview]);

  async function submit() {
    setErr(null);
    if (!applicationId) { setErr('applicationId is required.'); return; }
    // Phase 8.9 — start date is the activation gate; reject explicitly so
    // ERM never sends a "no start date" offer that would later strand the
    // intern at ONBOARDING_ACCEPTED. The backend also enforces this on
    // SIGN, but blocking here avoids a wasted offer round-trip.
    if (!tentativeStartDate) {
      setErr('Start date is required — the intern can\'t activate without one.');
      return;
    }
    if (!roleTitle.trim() || !compensationSummary.trim()) {
      setErr('Role title and compensation are required.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<{ id: string }>(
        '/api/v1/erm/offers',
        {
          applicationId,
          roleTitle: roleTitle.trim(),
          technology: technology.trim() || null,
          tentativeStartDate,
          compensationSummary: compensationSummary.trim(),
          worksite: worksite.trim(),
          expectedHoursPerWeek,
          expiryDays,
          contingencies: contingencies.trim() || null,
        },
      );
      router.push(`/careers/erm/offers/${res.data.id}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string; code?: string } } };
      setErr(
        ax.response?.data?.error ??
          (e instanceof Error ? e.message : 'Failed to create offer'),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Create Offer"
          subtitle={applicationId
            ? (autoFilled.applicantName
                ? `For ${autoFilled.applicantName}`
                : `Application: ${applicationId}`)
            : 'Open from an Application detail with SELECTED interview decision.'}
        />

        <div className="grid gap-6 lg:grid-cols-2">
          <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
            {!applicationId && (
              <p className="mb-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
                Open this page from the Application detail "Send offer" CTA so the
                applicationId is attached. Without it, create will fail.
              </p>
            )}
            <div className="space-y-3">
              <Field label="Role title" value={roleTitle} onChange={setRoleTitle} required
                helper={renderAutoFilledHelper(roleTitle, autoFilled.roleTitle, setRoleTitle)} />
              <Field label="Technology" value={technology} onChange={setTechnology} />
              <div className="grid grid-cols-2 gap-2">
                <DateField label="Tentative start date" value={tentativeStartDate}
                  onChange={setTentativeStartDate} required />
                <NumField label="Expiry (days)" value={expiryDays}
                  onChange={setExpiryDays} min={1} max={30} />
              </div>
              <Field label="Compensation summary" value={compensationSummary}
                onChange={setCompensationSummary} required maxLength={500} />
              <div className="grid grid-cols-2 gap-2">
                <Field label="Worksite" value={worksite} onChange={setWorksite} required maxLength={200}
                  helper={renderAutoFilledHelper(worksite, autoFilled.worksite, setWorksite)} />
                <NumField label="Hours / week" value={expectedHoursPerWeek}
                  onChange={setExpectedHoursPerWeek} min={1} max={40} />
              </div>
              <div>
                <label className="text-sm font-medium text-slate-800">Contingencies</label>
                <textarea
                  value={contingencies}
                  onChange={(e) => setContingencies(e.target.value)}
                  rows={3}
                  maxLength={1000}
                  className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
                />
              </div>

              {needsSelectionAck && (
                <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                  <strong>Selection acknowledgment required.</strong>{' '}
                  The applicant hasn&apos;t clicked &quot;Receive my offer letter&quot;
                  on their dashboard yet, so the offer can&apos;t be sent. The
                  Submit button will re-enable as soon as they acknowledge.
                </p>
              )}

              {err && (
                <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                  {err}
                </p>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => router.back()}
                  className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={submit}
                  disabled={submitting || needsSelectionAck}
                  title={needsSelectionAck
                    ? 'Waiting on the applicant\'s selection acknowledgment.'
                    : undefined}
                  className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60 disabled:cursor-not-allowed"
                >
                  {submitting ? 'Sending…' : 'Submit & send via IDMS'}
                </button>
              </div>
            </div>
          </div>

          <aside className="rounded-lg border border-slate-200 bg-slate-50 p-6">
            <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Email preview to applicant
            </p>
            {preview ? (
              <div className="mt-3 rounded-md border border-slate-200 bg-white p-4">
                <p className="text-sm font-semibold text-slate-900">{preview.subject}</p>
                <p className="mt-3 whitespace-pre-wrap text-sm text-slate-700">{preview.body}</p>
                <p className="mt-4 text-[11px] text-slate-400">Template: OFFER_LETTER</p>
              </div>
            ) : (
              <p className="mt-3 text-sm text-slate-500">
                Fill the form to see a live preview.
              </p>
            )}
          </aside>
        </div>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Field({
  label, value, onChange, required, maxLength, helper,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  required?: boolean;
  maxLength?: number;
  helper?: React.ReactNode;
}) {
  return (
    <div>
      <label className="text-sm font-medium text-slate-800">
        {label} {required && <span className="text-red-600">*</span>}
      </label>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        maxLength={maxLength}
        className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
      />
      {helper}
    </div>
  );
}

// Phase 8.6.1 — small visual indicator that the field was pre-filled from
// the job posting. When ERM overrides it, swap to a "reset to default"
// link so they can undo without re-typing the original.
function renderAutoFilledHelper(
  current: string,
  source: string | undefined,
  setValue: (v: string) => void,
) {
  if (!source) return null;
  if (current === source) {
    return (
      <p className="mt-1 text-[11px] text-slate-500">
        Auto-filled from job posting.
      </p>
    );
  }
  return (
    <p className="mt-1 text-[11px] text-slate-500">
      Overridden.{' '}
      <button
        type="button"
        onClick={() => setValue(source)}
        className="font-medium text-brand-700 hover:underline"
      >
        Reset to job posting value
      </button>
    </p>
  );
}

function DateField({
  label, value, onChange, required,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  required?: boolean;
}) {
  return (
    <div>
      <label className="text-sm font-medium text-slate-800">
        {label} {required && <span className="text-red-600">*</span>}
      </label>
      <input
        type="date"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
      />
    </div>
  );
}

function NumField({
  label, value, onChange, min, max,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
}) {
  return (
    <div>
      <label className="text-sm font-medium text-slate-800">{label}</label>
      <input
        type="number"
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
      />
    </div>
  );
}
