'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AlertCircle, ArrowLeft, CheckCircle2, ChevronLeft, ChevronRight, FileUp, User as UserIcon } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import StepperHorizontal from '@/components/ui/StepperHorizontal';
import type { UserStub } from '@/components/erm/offers/types';
import { cn } from '@/lib/careers/cn';

/**
 * Direct Onboarding wizard — one-shot registration of a pre-platform
 * employee. Five steps: Personal → Work Authorization → Reporting
 * Structure → Documents → Mailbox / Review. POSTs multipart to
 * /api/v1/erm/direct-onboarding; on success the ERM lands on the new
 * intern's Active Interns detail page.
 */

// ── Step definitions ───────────────────────────────────────────────────────
// Order: Personal → Work authorization → Documents → Reporting → Review.
// Documents comes BEFORE Reporting because the per-type proof documents
// (Passport / Citizenship Card, Green Card) are surfaced inline on the
// Work Auth step and share the same document-submission pipeline as the
// Documents step; putting Documents next keeps the file-upload flow
// contiguous before the ERM assigns supervisors.
const STEPS = [
  { key: 'personal', label: 'Personal' },
  { key: 'workauth', label: 'Work authorization' },
  { key: 'documents', label: 'Documents' },
  { key: 'reporting', label: 'Reporting structure' },
  { key: 'review', label: 'Mailbox + review' },
] as const;

const WORK_AUTH_TYPES = [
  { value: 'US_CITIZEN', label: 'US Citizen' },
  { value: 'PERMANENT_RESIDENT', label: 'Permanent Resident' },
  { value: 'F1_CPT', label: 'F-1 CPT' },
  { value: 'F1_OPT', label: 'F-1 OPT' },
  { value: 'F1_STEM_OPT', label: 'F-1 STEM OPT' },
  { value: 'H1B', label: 'H-1B' },
  { value: 'H4', label: 'H-4' },
  { value: 'OTHER', label: 'Other' },
] as const;

// Reserved document keys the wizard surfaces inline on the Work Auth
// step for citizens + permanent residents. They travel through the same
// `docFiles` map + `documents[]` submission as any other catalog doc, so
// no separate storage path exists — the server sees them as regular
// enum-backed onboarding-document rows already seeded from SkyzenDocument.
const US_CITIZEN_PROOF_KEY = 'US_CITIZEN_PROOF';
const PERMANENT_RESIDENT_GREEN_CARD_KEY = 'PERMANENT_RESIDENT_GREEN_CARD';

interface CustomDoc {
  /** Client-generated key, prefixed CUSTOM_ so the server takes the
   *  seed-inactive-template branch and honours titleOverride. */
  key: string;
  title: string;
  file: File | null;
}

interface OnboardingDoc {
  key: string;
  title: string;
  category: string;
  sensitivity: string;
}

interface StaffingEntityStub {
  id: string;
  name: string;
}

interface DirectOnboardingResponse {
  userId: string;
  internLifecycleId: string;
  employeeId: string;
  applicantId: string;
  email: string;
  companyEmail: string | null;
  mailboxProvisioned: boolean;
  credentialsEmailSent: boolean | null;
  activatedAt: string;
  activeInternDetailPath: string;
}

export default function DirectOnboardingPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <DirectOnboardingWizard />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

// The authorization window (from → until) is the SINGLE source of truth
// for expiration across every visa type. Type-specific labels contextualize
// the "until" field so the ERM sees "EAD expiration" for OPT, "CPT
// expiration" for CPT, etc. — same field, no duplicate.
function authUntilLabelFor(t: string): string {
  switch (t) {
    case 'F1_CPT': return 'Authorized until (CPT expiration)';
    case 'F1_OPT':
    case 'F1_STEM_OPT': return 'Authorized until (EAD expiration)';
    case 'H1B': return 'Authorized until (H-1B end date)';
    case 'H4': return 'Authorized until (H-4 EAD expiration)';
    case 'OTHER': return 'Authorized until (EAD expiration)';
    default: return 'Authorized until';
  }
}
function authFromLabelFor(t: string): string {
  return t === 'H1B' ? 'Authorized from (receipt start)' : 'Authorized from';
}

function DirectOnboardingWizard() {
  const router = useRouter();
  const [stepIdx, setStepIdx] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState<DirectOnboardingResponse | null>(null);

  // Step 1
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [legalName, setLegalName] = useState('');
  const [phone, setPhone] = useState('');
  const [joiningDate, setJoiningDate] = useState<string>(() => todayISO());
  const [entityId, setEntityId] = useState<string>('');
  const [entities, setEntities] = useState<StaffingEntityStub[]>([]);

  // Step 2 — Work authorization
  const [workAuthType, setWorkAuthType] = useState<string>('US_CITIZEN');
  const [authorizedFrom, setAuthorizedFrom] = useState<string>('');
  const [authorizedUntil, setAuthorizedUntil] = useState<string>('');
  const [eadCardNumber, setEadCardNumber] = useState('');
  const [i20Expiration, setI20Expiration] = useState<string>('');
  const [i983Required, setI983Required] = useState<boolean>(false);
  const [dsoName, setDsoName] = useState('');
  const [dsoEmail, setDsoEmail] = useState('');
  const [dsoPhone, setDsoPhone] = useState('');
  const [workAuthNotes, setWorkAuthNotes] = useState('');
  // Per-type extensions — number/identifier fields ONLY. Every date lives
  // in the shared authorizedFrom/authorizedUntil range above; per-type
  // labels contextualize the "until" for the visa type (see
  // authUntilLabelFor). No standalone end-date input per type — that was
  // the redundant field this fix removes.
  const [sevisNumber, setSevisNumber] = useState('');
  const [h1ReceiptNumber, setH1ReceiptNumber] = useState('');

  // Step 3 — Documents (moved before reporting)
  const [catalog, setCatalog] = useState<OnboardingDoc[]>([]);
  const [selectedDocKeys, setSelectedDocKeys] = useState<string[]>([]);
  const [resumeFile, setResumeFile] = useState<File | null>(null);
  const [docFiles, setDocFiles] = useState<Record<string, File | null>>({});
  const [customDocs, setCustomDocs] = useState<CustomDoc[]>([]);

  // Step 4 — Reporting structure
  const [trainers, setTrainers] = useState<UserStub[]>([]);
  const [evaluators, setEvaluators] = useState<UserStub[]>([]);
  const [managers, setManagers] = useState<UserStub[]>([]);
  const [trainerId, setTrainerId] = useState('');
  const [evaluatorId, setEvaluatorId] = useState('');
  const [managerId, setManagerId] = useState('');

  // Step 5
  const [assignMailbox, setAssignMailbox] = useState(true);
  const [mailboxLocalPart, setMailboxLocalPart] = useState('');
  const [mailboxPassword, setMailboxPassword] = useState(() => generatePassword());

  // ── Lazy-load reference data ─────────────────────────────────────────────
  useEffect(() => {
    void (async () => {
      try {
        const res = await api.get<StaffingEntityStub[]>('/api/v1/admin/entities');
        const list = res.data ?? [];
        setEntities(list);
        if (!entityId && list.length > 0) setEntityId(list[0].id);
      } catch {
        setEntities([]);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reporting-structure step (new position: index 3) — lazy load the
  // trainer / evaluator / manager pickers.
  useEffect(() => {
    if (stepIdx !== 3) return;
    if (trainers.length + evaluators.length + managers.length > 0) return;
    void (async () => {
      try {
        const [t, ev, m] = await Promise.all([
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-trainers'),
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-evaluators'),
          api.get<UserStub[]>('/api/v1/erm/new-hire/eligible-managers'),
        ]);
        setTrainers(t.data ?? []);
        setEvaluators(ev.data ?? []);
        setManagers(m.data ?? []);
      } catch {
        // Silent — the ERM can still submit with reporting structure blank
        // (server auto-links from DEFAULT_TRAINER_EMAIL etc.).
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stepIdx]);

  // Documents step (new position: index 2) — lazy load the pickable
  // template catalog. E-Verify + the per-type proof docs surface here
  // automatically once the SkyzenDocument enum is seeded.
  useEffect(() => {
    if (stepIdx !== 2) return;
    if (catalog.length > 0) return;
    void (async () => {
      try {
        const res = await api.get<{ items: OnboardingDoc[] }>(
          '/api/v1/erm/onboarding-templates/pickable',
        );
        setCatalog(
          (res.data?.items ?? []).map((d) => ({
            key: d.key,
            title: d.title,
            category: d.category,
            sensitivity: d.sensitivity,
          })),
        );
      } catch {
        setCatalog([]);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stepIdx]);

  // ── Per-step validation ──────────────────────────────────────────────────
  //
  // Step indices under the new order:
  //   0 Personal
  //   1 Work authorization  (per-type field + type-specific doc)
  //   2 Documents           (catalog + custom docs)
  //   3 Reporting structure
  //   4 Mailbox + review
  const stepError = useMemo(() => {
    if (stepIdx === 0) {
      if (!email.trim()) return 'Email is required.';
      if (!email.includes('@')) return 'Enter a valid email address.';
      if (!fullName.trim()) return 'Full name is required.';
      if (!joiningDate) return 'Joining date is required.';
    }
    if (stepIdx === 1) {
      if (!workAuthType) return 'Pick a work-authorization type.';
      // Per-type field requirements — mirror the server matrix.
      if (workAuthType === 'US_CITIZEN' && !docFiles[US_CITIZEN_PROOF_KEY]) {
        return 'Upload a passport or citizenship card.';
      }
      if (workAuthType === 'PERMANENT_RESIDENT' && !docFiles[PERMANENT_RESIDENT_GREEN_CARD_KEY]) {
        return 'Upload the Green Card.';
      }
      if (workAuthType === 'F1_CPT') {
        if (!sevisNumber.trim()) return 'SEVIS number is required for F-1 CPT.';
        if (!authorizedUntil) return 'CPT expiration (Authorized until) is required for F-1 CPT.';
      }
      if (workAuthType === 'F1_OPT' || workAuthType === 'F1_STEM_OPT'
          || workAuthType === 'H4' || workAuthType === 'OTHER') {
        if (!eadCardNumber.trim()) return 'EAD card number is required.';
        if (!authorizedUntil) return 'EAD expiration (Authorized until) is required.';
      }
      if (workAuthType === 'H1B') {
        if (!h1ReceiptNumber.trim()) return 'H-1 receipt number is required for H-1B.';
        if (!authorizedFrom) return 'Receipt start (Authorized from) is required for H-1B.';
        if (!authorizedUntil) return 'H-1B end date (Authorized until) is required for H-1B.';
        if (authorizedUntil && authorizedFrom && authorizedUntil < authorizedFrom) {
          return 'Authorized until cannot be before Authorized from.';
        }
      }
    }
    if (stepIdx === 2) {
      if (!resumeFile) return 'Resume upload is required.';
      for (const k of selectedDocKeys) {
        if (!docFiles[k]) return `Upload a file for ${labelFor(catalog, k)}.`;
      }
      for (const c of customDocs) {
        if (!c.title.trim()) return 'Every custom document needs a name.';
        if (!c.file) return `Upload a file for "${c.title.trim() || 'Custom document'}".`;
      }
    }
    if (stepIdx === 4 && assignMailbox) {
      if (!mailboxLocalPart.trim()) return 'Mailbox local-part is required when assigning a mailbox now.';
      if (!/^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$/.test(mailboxLocalPart.trim())) {
        return 'Local-part must be lowercase alphanumeric, may include . _ -, and start/end with a letter or digit.';
      }
      if (mailboxPassword.length < 8) {
        return 'Mailbox starting password must be at least 8 characters.';
      }
    }
    return null;
  }, [
    stepIdx, email, fullName, joiningDate, workAuthType,
    authorizedFrom, authorizedUntil,
    sevisNumber, eadCardNumber, h1ReceiptNumber,
    resumeFile, selectedDocKeys, docFiles, catalog, customDocs,
    assignMailbox, mailboxLocalPart, mailboxPassword,
  ]);

  function next() {
    if (stepError) {
      toast.error(stepError);
      return;
    }
    setStepIdx((n) => Math.min(n + 1, STEPS.length - 1));
  }
  function back() {
    setStepIdx((n) => Math.max(n - 1, 0));
  }

  async function submit() {
    if (stepError) {
      toast.error(stepError);
      return;
    }
    if (!resumeFile) {
      toast.error('Resume upload is required.');
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      // Enforce I-983 visibility rule client-side too: strip true for
      // non-F-1-OPT types so the payload never carries a stale checkbox
      // value from an earlier type selection.
      const effectiveI983 =
        workAuthType === 'F1_OPT' || workAuthType === 'F1_STEM_OPT'
          ? i983Required
          : false;

      // The type-specific proof documents live in the SAME docFiles map
      // as the catalog picks (they just arrive from the Work Auth step).
      // Collect every enum-backed key that has a file attached — that's
      // the set of assignments the server expects.
      const workAuthDocKeys: string[] = [];
      if (workAuthType === 'US_CITIZEN' && docFiles[US_CITIZEN_PROOF_KEY]) {
        workAuthDocKeys.push(US_CITIZEN_PROOF_KEY);
      }
      if (workAuthType === 'PERMANENT_RESIDENT'
          && docFiles[PERMANENT_RESIDENT_GREEN_CARD_KEY]) {
        workAuthDocKeys.push(PERMANENT_RESIDENT_GREEN_CARD_KEY);
      }
      const enumBackedKeys = Array.from(
        new Set<string>([...selectedDocKeys, ...workAuthDocKeys]),
      );

      const metadata = {
        email: email.trim(),
        fullName: fullName.trim(),
        legalName: legalName.trim() || fullName.trim(),
        phoneNumber: phone.trim() || null,
        joiningDate,
        entityId: entityId || null,
        workAuthType,
        authorizedFrom: authorizedFrom || null,
        authorizedUntil: authorizedUntil || null,
        eadCardNumber: eadCardNumber.trim() || null,
        i20Expiration: i20Expiration || null,
        i983Required: effectiveI983,
        dsoName: dsoName.trim() || null,
        dsoEmail: dsoEmail.trim() || null,
        dsoPhone: dsoPhone.trim() || null,
        workAuthNotes: workAuthNotes.trim() || null,
        sevisNumber: sevisNumber.trim() || null,
        h1ReceiptNumber: h1ReceiptNumber.trim() || null,
        trainerUserId: trainerId || null,
        evaluatorUserId: evaluatorId || null,
        managerUserId: managerId || null,
        documents: [
          ...enumBackedKeys.map((k) => ({
            documentKey: k,
            formPartName: `doc_${k}`,
            titleOverride: null,
          })),
          ...customDocs
            .filter((c) => c.file && c.title.trim())
            .map((c) => ({
              documentKey: c.key,
              formPartName: `doc_${c.key}`,
              titleOverride: c.title.trim(),
            })),
        ],
        assignMailboxNow: assignMailbox,
        mailboxLocalPart: assignMailbox ? mailboxLocalPart.trim() : null,
        mailboxStartingPassword: assignMailbox ? mailboxPassword : null,
      };
      const fd = new FormData();
      fd.append('metadata', JSON.stringify(metadata));
      fd.append('resume', resumeFile);
      for (const k of enumBackedKeys) {
        const f = docFiles[k];
        if (f) fd.append(`doc_${k}`, f);
      }
      for (const c of customDocs) {
        if (c.file && c.title.trim()) fd.append(`doc_${c.key}`, c.file);
      }
      const res = await api.post<DirectOnboardingResponse>(
        '/api/v1/erm/direct-onboarding',
        fd,
      );
      setSuccess(res.data);
      toast.success(`Onboarded ${res.data.employeeId}`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string; message?: string } } };
      const msg = ax.response?.data?.error
        ?? ax.response?.data?.message
        ?? (e instanceof Error ? e.message : 'Direct onboarding failed');
      setSubmitError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (success) {
    return (
      <div className="mx-auto max-w-3xl">
        <PageHeader
          title="Onboarding complete"
          subtitle="The new employee is active in the current month's roster."
          breadcrumb={[
            { label: 'ERM', href: '/careers/erm' },
            { label: 'Direct Onboarding' },
          ]}
        />
        <section className="rounded-lg border border-emerald-200 bg-emerald-50 p-6">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="h-6 w-6 shrink-0 text-emerald-600" />
            <div>
              <h2 className="text-base font-semibold text-emerald-900">
                {success.employeeId} is now an Active Intern
              </h2>
              <p className="mt-1 text-sm text-emerald-800">
                They&apos;ll appear on this month&apos;s Active Interns roster from
                now on. Month-wise tracking begins with the registration month;
                the joining date you entered is retained as a display-only fact.
              </p>
              <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-sm text-emerald-900">
                <dt className="font-medium">Employee ID</dt>
                <dd>{success.employeeId}</dd>
                <dt className="font-medium">Applicant ID</dt>
                <dd>{success.applicantId}</dd>
                <dt className="font-medium">Login email</dt>
                <dd>{success.email}</dd>
                {success.mailboxProvisioned && (
                  <>
                    <dt className="font-medium">Company mailbox</dt>
                    <dd>{success.companyEmail}</dd>
                    <dt className="font-medium">Credentials email sent</dt>
                    <dd>{success.credentialsEmailSent ? 'Yes' : 'No — share manually'}</dd>
                  </>
                )}
              </dl>
            </div>
          </div>
          <div className="mt-6 flex gap-3">
            <Link
              href={success.activeInternDetailPath}
              className="rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
            >
              Open Active Intern detail
            </Link>
            <button
              type="button"
              onClick={() => router.push('/careers/erm/active-interns')}
              className="rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Back to Active Interns
            </button>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="Direct Onboarding"
        subtitle="Register a pre-platform employee straight into the platform. They'll land in Active Interns immediately."
        breadcrumb={[
          { label: 'ERM', href: '/careers/erm' },
          { label: 'New Hire List', href: '/careers/erm/new-hire' },
          { label: 'Direct Onboarding' },
        ]}
        primaryAction={
          <Link
            href="/careers/erm/new-hire"
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            Back
          </Link>
        }
      />

      <div className="mb-8">
        <StepperHorizontal
          steps={STEPS.map((s) => ({ key: s.key, label: s.label }))}
          currentIndex={stepIdx}
        />
      </div>

      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        {stepIdx === 0 && (
          <PersonalStep
            email={email} setEmail={setEmail}
            fullName={fullName} setFullName={setFullName}
            legalName={legalName} setLegalName={setLegalName}
            phone={phone} setPhone={setPhone}
            joiningDate={joiningDate} setJoiningDate={setJoiningDate}
            entityId={entityId} setEntityId={setEntityId}
            entities={entities}
          />
        )}
        {stepIdx === 1 && (
          <WorkAuthStep
            workAuthType={workAuthType} setWorkAuthType={setWorkAuthType}
            authorizedFrom={authorizedFrom} setAuthorizedFrom={setAuthorizedFrom}
            authorizedUntil={authorizedUntil} setAuthorizedUntil={setAuthorizedUntil}
            eadCardNumber={eadCardNumber} setEadCardNumber={setEadCardNumber}
            i20Expiration={i20Expiration} setI20Expiration={setI20Expiration}
            i983Required={i983Required} setI983Required={setI983Required}
            dsoName={dsoName} setDsoName={setDsoName}
            dsoEmail={dsoEmail} setDsoEmail={setDsoEmail}
            dsoPhone={dsoPhone} setDsoPhone={setDsoPhone}
            workAuthNotes={workAuthNotes} setWorkAuthNotes={setWorkAuthNotes}
            sevisNumber={sevisNumber} setSevisNumber={setSevisNumber}
            h1ReceiptNumber={h1ReceiptNumber} setH1ReceiptNumber={setH1ReceiptNumber}
            docFiles={docFiles} setDocFiles={setDocFiles}
          />
        )}
        {stepIdx === 2 && (
          <DocumentsStep
            catalog={catalog}
            selectedDocKeys={selectedDocKeys} setSelectedDocKeys={setSelectedDocKeys}
            resumeFile={resumeFile} setResumeFile={setResumeFile}
            docFiles={docFiles} setDocFiles={setDocFiles}
            customDocs={customDocs} setCustomDocs={setCustomDocs}
          />
        )}
        {stepIdx === 3 && (
          <ReportingStep
            trainers={trainers} evaluators={evaluators} managers={managers}
            trainerId={trainerId} setTrainerId={setTrainerId}
            evaluatorId={evaluatorId} setEvaluatorId={setEvaluatorId}
            managerId={managerId} setManagerId={setManagerId}
          />
        )}
        {stepIdx === 4 && (
          <ReviewStep
            email={email} fullName={fullName} joiningDate={joiningDate}
            workAuthType={workAuthType}
            trainerId={trainerId} evaluatorId={evaluatorId} managerId={managerId}
            trainers={trainers} evaluators={evaluators} managers={managers}
            selectedDocKeys={selectedDocKeys} catalog={catalog}
            customDocs={customDocs}
            docFiles={docFiles}
            resumeFile={resumeFile}
            assignMailbox={assignMailbox} setAssignMailbox={setAssignMailbox}
            mailboxLocalPart={mailboxLocalPart} setMailboxLocalPart={setMailboxLocalPart}
            mailboxPassword={mailboxPassword} setMailboxPassword={setMailboxPassword}
          />
        )}

        {(submitError || stepError) && (
          <div className="mt-6 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
            <p>{submitError ?? stepError}</p>
          </div>
        )}

        <div className="mt-8 flex items-center justify-between">
          <button
            type="button"
            onClick={back}
            disabled={stepIdx === 0 || submitting}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ChevronLeft className="h-4 w-4" />
            Back
          </button>
          {stepIdx < STEPS.length - 1 ? (
            <button
              type="button"
              onClick={next}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={submit}
              disabled={submitting}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-5 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {submitting ? 'Creating…' : 'Create + activate'}
            </button>
          )}
        </div>
      </section>
    </div>
  );
}

// ── Step components ────────────────────────────────────────────────────────

function PersonalStep(props: {
  email: string; setEmail: (v: string) => void;
  fullName: string; setFullName: (v: string) => void;
  legalName: string; setLegalName: (v: string) => void;
  phone: string; setPhone: (v: string) => void;
  joiningDate: string; setJoiningDate: (v: string) => void;
  entityId: string; setEntityId: (v: string) => void;
  entities: StaffingEntityStub[];
}) {
  return (
    <div>
      <SectionHeader
        icon={<UserIcon className="h-4 w-4" />}
        title="Personal details"
        subtitle="What you'd normally see on their intake form."
      />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Email" required>
          <input
            type="email" value={props.email}
            onChange={(e) => props.setEmail(e.target.value)}
            className={inputClass}
            autoComplete="off"
          />
        </Field>
        <Field label="Phone">
          <input
            type="tel" value={props.phone}
            onChange={(e) => props.setPhone(e.target.value)}
            className={inputClass}
            autoComplete="off"
          />
        </Field>
        <Field label="Full name" required>
          <input
            value={props.fullName}
            onChange={(e) => props.setFullName(e.target.value)}
            className={inputClass}
            autoComplete="off"
          />
        </Field>
        <Field label="Legal name" helper="Defaults to full name.">
          <input
            value={props.legalName}
            onChange={(e) => props.setLegalName(e.target.value)}
            className={inputClass}
            autoComplete="off"
          />
        </Field>
        <Field
          label="Joining date"
          required
          helper="True historical joining date. Month-wise tracking begins today."
        >
          <input
            type="date"
            value={props.joiningDate}
            onChange={(e) => props.setJoiningDate(e.target.value)}
            className={inputClass}
          />
        </Field>
        {props.entities.length > 0 && (
          <Field label="Staffing entity" helper="Defaults to the first active entity.">
            <select
              value={props.entityId}
              onChange={(e) => props.setEntityId(e.target.value)}
              className={inputClass}
            >
              {props.entities.map((e) => (
                <option key={e.id} value={e.id}>{e.name}</option>
              ))}
            </select>
          </Field>
        )}
      </div>
    </div>
  );
}

function WorkAuthStep(props: {
  workAuthType: string; setWorkAuthType: (v: string) => void;
  authorizedFrom: string; setAuthorizedFrom: (v: string) => void;
  authorizedUntil: string; setAuthorizedUntil: (v: string) => void;
  eadCardNumber: string; setEadCardNumber: (v: string) => void;
  i20Expiration: string; setI20Expiration: (v: string) => void;
  i983Required: boolean; setI983Required: (v: boolean) => void;
  dsoName: string; setDsoName: (v: string) => void;
  dsoEmail: string; setDsoEmail: (v: string) => void;
  dsoPhone: string; setDsoPhone: (v: string) => void;
  workAuthNotes: string; setWorkAuthNotes: (v: string) => void;
  sevisNumber: string; setSevisNumber: (v: string) => void;
  h1ReceiptNumber: string; setH1ReceiptNumber: (v: string) => void;
  docFiles: Record<string, File | null>;
  setDocFiles: (v: Record<string, File | null>) => void;
}) {
  const t = props.workAuthType;
  const showEadFields = t === 'F1_OPT' || t === 'F1_STEM_OPT'
    || t === 'H4' || t === 'OTHER';
  const showI20AndDso = t === 'F1_CPT' || t === 'F1_OPT' || t === 'F1_STEM_OPT';
  const showI983 = t === 'F1_OPT' || t === 'F1_STEM_OPT';
  function setDoc(key: string, file: File | null) {
    props.setDocFiles({ ...props.docFiles, [key]: file });
  }

  return (
    <div>
      <SectionHeader
        title="Work authorization"
        subtitle="Fields shown match the selected type — nothing you don't need."
      />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Type" required>
          <select
            value={t}
            onChange={(e) => props.setWorkAuthType(e.target.value)}
            className={inputClass}
          >
            {WORK_AUTH_TYPES.map((row) => (
              <option key={row.value} value={row.value}>{row.label}</option>
            ))}
          </select>
        </Field>
        <Field label={authFromLabelFor(t)}>
          <input
            type="date" value={props.authorizedFrom}
            onChange={(e) => props.setAuthorizedFrom(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label={authUntilLabelFor(t)}>
          <input
            type="date" value={props.authorizedUntil}
            onChange={(e) => props.setAuthorizedUntil(e.target.value)}
            className={inputClass}
          />
        </Field>

        {/* US_CITIZEN + PERMANENT_RESIDENT — no visa fields, one required upload. */}
        {t === 'US_CITIZEN' && (
          <div className="sm:col-span-2">
            <ProofUpload
              label="Passport or Citizenship Card"
              required
              helper="Upload either one — whichever proves US citizenship most cleanly."
              file={props.docFiles[US_CITIZEN_PROOF_KEY] ?? null}
              onChange={(f) => setDoc(US_CITIZEN_PROOF_KEY, f)}
            />
          </div>
        )}
        {t === 'PERMANENT_RESIDENT' && (
          <div className="sm:col-span-2">
            <ProofUpload
              label="Green Card"
              required
              helper="Both sides in one file if possible."
              file={props.docFiles[PERMANENT_RESIDENT_GREEN_CARD_KEY] ?? null}
              onChange={(f) => setDoc(PERMANENT_RESIDENT_GREEN_CARD_KEY, f)}
            />
          </div>
        )}

        {/* F1_CPT — SEVIS number only. CPT expiration is captured by the
            authorized-until date above (its label reads "Authorized until
            (CPT expiration)" when this type is selected). */}
        {t === 'F1_CPT' && (
          <Field label="SEVIS number" required>
            <input
              value={props.sevisNumber}
              onChange={(e) => props.setSevisNumber(e.target.value)}
              className={inputClass}
              autoComplete="off"
              placeholder="N00XXXXXXXX"
            />
          </Field>
        )}

        {/* EAD card number — F-1 OPT, F-1 STEM OPT, H-4, OTHER. EAD
            expiration is captured by the authorized-until date above
            (its label reads "Authorized until (EAD expiration)" when
            these types are selected). */}
        {showEadFields && (
          <Field label="EAD card number" required>
            <input
              value={props.eadCardNumber}
              onChange={(e) => props.setEadCardNumber(e.target.value)}
              className={inputClass}
              autoComplete="off"
            />
          </Field>
        )}

        {/* H-1B — receipt number only. Receipt validity window is
            captured by the authorized-from / authorized-until pair
            above (labels adapt to "Authorized from (receipt start)"
            and "Authorized until (H-1B end date)" for this type). */}
        {t === 'H1B' && (
          <Field label="H-1 receipt number" required>
            <input
              value={props.h1ReceiptNumber}
              onChange={(e) => props.setH1ReceiptNumber(e.target.value)}
              className={inputClass}
              autoComplete="off"
              placeholder="EAC-XX-XXX-XXXXX"
            />
          </Field>
        )}

        {/* I-20 + DSO — F-1 track only. */}
        {showI20AndDso && (
          <>
            <Field label="I-20 expiration">
              <input
                type="date" value={props.i20Expiration}
                onChange={(e) => props.setI20Expiration(e.target.value)}
                className={inputClass}
              />
            </Field>
            <Field label="DSO name">
              <input
                value={props.dsoName}
                onChange={(e) => props.setDsoName(e.target.value)}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
            <Field label="DSO email">
              <input
                type="email" value={props.dsoEmail}
                onChange={(e) => props.setDsoEmail(e.target.value)}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
            <Field label="DSO phone">
              <input
                type="tel" value={props.dsoPhone}
                onChange={(e) => props.setDsoPhone(e.target.value)}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
          </>
        )}

        {/* I-983 checkbox — F-1 OPT / F-1 STEM OPT only. */}
        {showI983 && (
          <div className="sm:col-span-2">
            <label className="inline-flex items-center gap-2 text-sm">
              <input
                type="checkbox" checked={props.i983Required}
                onChange={(e) => props.setI983Required(e.target.checked)}
                className="h-4 w-4 rounded border-slate-300"
              />
              <span className="text-slate-700">I-983 required (STEM OPT training plan)</span>
            </label>
          </div>
        )}

        <div className="sm:col-span-2">
          <Field label="Notes (ERM-only)">
            <textarea
              value={props.workAuthNotes}
              onChange={(e) => props.setWorkAuthNotes(e.target.value)}
              rows={3}
              className={inputClass}
            />
          </Field>
        </div>
      </div>
    </div>
  );
}

/**
 * Small inline file-upload used by the Work Auth step for the two
 * type-specific proof documents. Stores through the same docFiles map
 * as the Documents-step uploads so a single submit path handles them.
 */
function ProofUpload({ label, required, helper, file, onChange }: {
  label: string;
  required?: boolean;
  helper?: string;
  file: File | null;
  onChange: (f: File | null) => void;
}) {
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-4">
      <p className="text-sm font-medium text-slate-800">
        {label}
        {required && <span className="text-red-600"> *</span>}
      </p>
      {helper && <p className="mt-0.5 text-xs text-slate-500">{helper}</p>}
      <input
        type="file"
        onChange={(e) => onChange(e.target.files?.[0] ?? null)}
        className="mt-2 block w-full text-sm text-slate-700 file:mr-3 file:rounded-md file:border-0 file:bg-brand-700 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white hover:file:bg-brand-800"
      />
      {file && <p className="mt-1 text-xs text-slate-500">Selected: {file.name}</p>}
    </div>
  );
}

function ReportingStep(props: {
  trainers: UserStub[]; evaluators: UserStub[]; managers: UserStub[];
  trainerId: string; setTrainerId: (v: string) => void;
  evaluatorId: string; setEvaluatorId: (v: string) => void;
  managerId: string; setManagerId: (v: string) => void;
}) {
  return (
    <div>
      <SectionHeader
        title="Reporting structure"
        subtitle="Leave any of these blank to auto-link from the org-wide defaults."
      />
      <div className="space-y-4">
        <RolePicker
          label="Trainer" value={props.trainerId} options={props.trainers}
          onChange={props.setTrainerId}
        />
        <RolePicker
          label="Evaluator" value={props.evaluatorId} options={props.evaluators}
          onChange={props.setEvaluatorId}
        />
        <RolePicker
          label="Reporting Manager" value={props.managerId} options={props.managers}
          onChange={props.setManagerId}
        />
      </div>
    </div>
  );
}

function DocumentsStep(props: {
  catalog: OnboardingDoc[];
  selectedDocKeys: string[]; setSelectedDocKeys: (v: string[]) => void;
  resumeFile: File | null; setResumeFile: (v: File | null) => void;
  docFiles: Record<string, File | null>;
  setDocFiles: (v: Record<string, File | null>) => void;
  customDocs: CustomDoc[]; setCustomDocs: (v: CustomDoc[]) => void;
}) {
  // Hide the two proof-of-status entries from the catalog list — they
  // live on the Work Auth step where the ERM has type-in-hand context.
  const catalog = props.catalog.filter(
    (d) => d.key !== US_CITIZEN_PROOF_KEY
        && d.key !== PERMANENT_RESIDENT_GREEN_CARD_KEY,
  );
  function toggleDoc(key: string) {
    if (props.selectedDocKeys.includes(key)) {
      props.setSelectedDocKeys(props.selectedDocKeys.filter((k) => k !== key));
      const next = { ...props.docFiles };
      delete next[key];
      props.setDocFiles(next);
    } else {
      props.setSelectedDocKeys([...props.selectedDocKeys, key]);
    }
  }
  function addCustom() {
    const key = `CUSTOM_${customKeySuffix()}`;
    props.setCustomDocs([...props.customDocs, { key, title: '', file: null }]);
  }
  function updateCustom(idx: number, patch: Partial<CustomDoc>) {
    props.setCustomDocs(props.customDocs.map((c, i) => i === idx ? { ...c, ...patch } : c));
  }
  function removeCustom(idx: number) {
    props.setCustomDocs(props.customDocs.filter((_, i) => i !== idx));
  }
  return (
    <div>
      <SectionHeader
        icon={<FileUp className="h-4 w-4" />}
        title="Documents"
        subtitle="Resume plus any onboarding docs you already have on file — including custom ones."
      />
      <div className="mb-6 rounded-md border border-slate-200 bg-slate-50 p-4">
        <p className="text-sm font-medium text-slate-800">Resume <span className="text-red-600">*</span></p>
        <input
          type="file"
          accept=".pdf,.doc,.docx"
          onChange={(e) => props.setResumeFile(e.target.files?.[0] ?? null)}
          className="mt-2 block w-full text-sm text-slate-700 file:mr-3 file:rounded-md file:border-0 file:bg-brand-700 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white hover:file:bg-brand-800"
        />
        {props.resumeFile && (
          <p className="mt-1 text-xs text-slate-500">Selected: {props.resumeFile.name}</p>
        )}
      </div>
      <p className="mb-2 text-sm font-medium text-slate-800">Onboarding documents</p>
      <p className="mb-3 text-xs text-slate-500">
        Pick every document you have on file. Each selected row needs a file to submit.
      </p>
      {catalog.length === 0 ? (
        <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
          No onboarding document templates configured. Add them from Admin → Onboarding Documents,
          or continue without any.
        </p>
      ) : (
        <ul className="space-y-2">
          {catalog.map((doc) => {
            const selected = props.selectedDocKeys.includes(doc.key);
            return (
              <li
                key={doc.key}
                className={cn(
                  'rounded-md border p-3',
                  selected ? 'border-brand-500 bg-brand-50/40' : 'border-slate-200 bg-white',
                )}
              >
                <label className="flex items-start gap-3">
                  <input
                    type="checkbox"
                    checked={selected}
                    onChange={() => toggleDoc(doc.key)}
                    className="mt-0.5 h-4 w-4 rounded border-slate-300"
                  />
                  <div className="flex-1">
                    <p className="text-sm font-medium text-slate-800">{doc.title}</p>
                    <p className="text-xs text-slate-500">
                      {doc.category} · {doc.sensitivity}
                    </p>
                    {selected && (
                      <input
                        type="file"
                        onChange={(e) => {
                          const f = e.target.files?.[0] ?? null;
                          props.setDocFiles({ ...props.docFiles, [doc.key]: f });
                        }}
                        className="mt-2 block w-full text-sm text-slate-700 file:mr-3 file:rounded-md file:border-0 file:bg-slate-700 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white hover:file:bg-slate-800"
                      />
                    )}
                  </div>
                </label>
              </li>
            );
          })}
        </ul>
      )}

      {/* Custom documents — name + file per row, repeatable. */}
      <div className="mt-6">
        <div className="mb-2 flex items-center justify-between">
          <p className="text-sm font-medium text-slate-800">Custom documents</p>
          <button
            type="button"
            onClick={addCustom}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            + Add another document
          </button>
        </div>
        <p className="mb-3 text-xs text-slate-500">
          Anything not in the catalog above — offer letter attachments, prior-employer verifications, agreements.
          Give each a clear name.
        </p>
        {props.customDocs.length === 0 ? (
          <p className="rounded-md border border-dashed border-slate-200 bg-white p-3 text-xs text-slate-500">
            No custom documents added.
          </p>
        ) : (
          <ul className="space-y-2">
            {props.customDocs.map((c, idx) => (
              <li key={c.key} className="rounded-md border border-slate-200 bg-white p-3">
                <div className="flex items-start gap-3">
                  <div className="flex-1 space-y-2">
                    <input
                      value={c.title}
                      onChange={(e) => updateCustom(idx, { title: e.target.value })}
                      placeholder="Document name (e.g. Signed offer letter)"
                      className={inputClass}
                      autoComplete="off"
                    />
                    <input
                      type="file"
                      onChange={(e) => updateCustom(idx, { file: e.target.files?.[0] ?? null })}
                      className="block w-full text-sm text-slate-700 file:mr-3 file:rounded-md file:border-0 file:bg-slate-700 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white hover:file:bg-slate-800"
                    />
                    {c.file && <p className="text-xs text-slate-500">Selected: {c.file.name}</p>}
                  </div>
                  <button
                    type="button"
                    onClick={() => removeCustom(idx)}
                    className="text-xs font-medium text-slate-500 hover:text-red-600"
                    aria-label={`Remove ${c.title || 'custom document'}`}
                  >
                    Remove
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function ReviewStep(props: {
  email: string; fullName: string; joiningDate: string;
  workAuthType: string;
  trainerId: string; evaluatorId: string; managerId: string;
  trainers: UserStub[]; evaluators: UserStub[]; managers: UserStub[];
  selectedDocKeys: string[]; catalog: OnboardingDoc[];
  customDocs: CustomDoc[];
  docFiles: Record<string, File | null>;
  resumeFile: File | null;
  assignMailbox: boolean; setAssignMailbox: (v: boolean) => void;
  mailboxLocalPart: string; setMailboxLocalPart: (v: string) => void;
  mailboxPassword: string; setMailboxPassword: (v: string) => void;
}) {
  // Compose the full document list for the summary — catalog picks +
  // type-specific proof docs from the Work Auth step + custom docs.
  const workAuthDocs: string[] = [];
  if (props.workAuthType === 'US_CITIZEN'
      && props.docFiles[US_CITIZEN_PROOF_KEY]) {
    workAuthDocs.push('Passport or Citizenship Card');
  }
  if (props.workAuthType === 'PERMANENT_RESIDENT'
      && props.docFiles[PERMANENT_RESIDENT_GREEN_CARD_KEY]) {
    workAuthDocs.push('Green Card');
  }
  const summaryDocs: string[] = [
    ...workAuthDocs,
    ...props.selectedDocKeys.map((k) => labelFor(props.catalog, k)),
    ...props.customDocs
      .filter((c) => c.file && c.title.trim())
      .map((c) => `${c.title.trim()} (custom)`),
  ];
  return (
    <div>
      <SectionHeader
        title="Review + mailbox"
        subtitle="Confirm the summary, then decide whether to assign a company mailbox now."
      />
      <dl className="mb-6 grid grid-cols-2 gap-x-6 gap-y-2 rounded-md border border-slate-200 bg-slate-50 p-4 text-sm">
        <dt className="font-medium text-slate-600">Full name</dt>
        <dd className="text-slate-800">{props.fullName || '—'}</dd>
        <dt className="font-medium text-slate-600">Email</dt>
        <dd className="text-slate-800">{props.email || '—'}</dd>
        <dt className="font-medium text-slate-600">Joining date</dt>
        <dd className="text-slate-800">{props.joiningDate || '—'}</dd>
        <dt className="font-medium text-slate-600">Work authorization</dt>
        <dd className="text-slate-800">{props.workAuthType || '—'}</dd>
        <dt className="font-medium text-slate-600">Trainer</dt>
        <dd className="text-slate-800">{labelForUser(props.trainers, props.trainerId)}</dd>
        <dt className="font-medium text-slate-600">Evaluator</dt>
        <dd className="text-slate-800">{labelForUser(props.evaluators, props.evaluatorId)}</dd>
        <dt className="font-medium text-slate-600">Reporting Manager</dt>
        <dd className="text-slate-800">{labelForUser(props.managers, props.managerId)}</dd>
        <dt className="font-medium text-slate-600">Resume</dt>
        <dd className="text-slate-800">{props.resumeFile?.name ?? '—'}</dd>
        <dt className="font-medium text-slate-600">Documents</dt>
        <dd className="text-slate-800">
          {summaryDocs.length === 0 ? '—' : summaryDocs.join(', ')}
        </dd>
      </dl>
      <div className="rounded-md border border-slate-200 p-4">
        <label className="flex items-start gap-3">
          <input
            type="checkbox" checked={props.assignMailbox}
            onChange={(e) => props.setAssignMailbox(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded border-slate-300"
          />
          <div className="flex-1">
            <p className="text-sm font-medium text-slate-800">Assign company mailbox now</p>
            <p className="text-xs text-slate-500">
              Turn off to skip — you can assign one later from the Active Intern detail page.
            </p>
          </div>
        </label>
        {props.assignMailbox && (
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Mailbox local-part" required helper="The bit before @anvicorp.com.">
              <input
                value={props.mailboxLocalPart}
                onChange={(e) => props.setMailboxLocalPart(e.target.value.toLowerCase())}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
            <Field label="Starting password" required>
              <input
                value={props.mailboxPassword}
                onChange={(e) => props.setMailboxPassword(e.target.value)}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Small helpers ──────────────────────────────────────────────────────────

function Field({
  label, children, required, helper,
}: { label: string; children: React.ReactNode; required?: boolean; helper?: string }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-semibold text-slate-700">
        {label}{required && <span className="text-red-600"> *</span>}
      </label>
      {children}
      {helper && <p className="mt-1 text-xs text-slate-500">{helper}</p>}
    </div>
  );
}

function SectionHeader({
  icon, title, subtitle,
}: { icon?: React.ReactNode; title: string; subtitle?: string }) {
  return (
    <header className="mb-5 flex items-start gap-3 border-b border-slate-100 pb-4">
      {icon && <span className="mt-0.5 rounded-md bg-brand-50 p-1.5 text-brand-700">{icon}</span>}
      <div>
        <h2 className="text-base font-semibold text-slate-900">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
      </div>
    </header>
  );
}

function RolePicker({
  label, value, options, onChange,
}: {
  label: string; value: string; options: UserStub[]; onChange: (v: string) => void;
}) {
  return (
    <div>
      <label className="text-sm font-medium text-slate-800">{label}</label>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={cn(inputClass, 'mt-1')}
      >
        <option value="">Auto-link from default</option>
        {options.map((o) => (
          <option key={o.userId} value={o.userId}>
            {o.fullName} · {o.currentInternCount} intern
            {o.currentInternCount === 1 ? '' : 's'}
          </option>
        ))}
      </select>
    </div>
  );
}

const inputClass =
  'w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500';

function labelFor(catalog: OnboardingDoc[], key: string): string {
  return catalog.find((d) => d.key === key)?.title ?? key;
}

function labelForUser(list: UserStub[], id: string): string {
  if (!id) return 'Auto-link';
  return list.find((u) => u.userId === id)?.fullName ?? id;
}

function todayISO(): string {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

/** Short random key suffix for custom-document keys (CUSTOM_<suffix>). */
function customKeySuffix(): string {
  const bytes = new Uint8Array(6);
  const crypto = typeof window !== 'undefined' ? window.crypto : undefined;
  if (crypto) {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out.toUpperCase();
}

function generatePassword(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  let out = '';
  const crypto = typeof window !== 'undefined' ? window.crypto : undefined;
  const bytes = new Uint8Array(12);
  if (crypto) {
    crypto.getRandomValues(bytes);
    for (const b of bytes) out += alphabet[b % alphabet.length];
    return out;
  }
  for (let i = 0; i < 12; i++) out += alphabet[Math.floor(Math.random() * alphabet.length)];
  return out;
}
