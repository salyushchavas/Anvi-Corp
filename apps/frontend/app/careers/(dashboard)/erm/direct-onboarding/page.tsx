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
const STEPS = [
  { key: 'personal', label: 'Personal' },
  { key: 'workauth', label: 'Work authorization' },
  { key: 'reporting', label: 'Reporting structure' },
  { key: 'documents', label: 'Documents' },
  { key: 'review', label: 'Mailbox + review' },
] as const;

const WORK_AUTH_TYPES = [
  { value: 'US_CITIZEN', label: 'US Citizen' },
  { value: 'PERMANENT_RESIDENT', label: 'Permanent Resident' },
  { value: 'F1_CPT', label: 'F-1 CPT' },
  { value: 'F1_OPT', label: 'F-1 OPT' },
  { value: 'F1_STEM_OPT', label: 'F-1 STEM OPT' },
  { value: 'H1B', label: 'H-1B' },
  { value: 'OTHER', label: 'Other' },
] as const;

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

  // Step 2
  const [workAuthType, setWorkAuthType] = useState<string>('US_CITIZEN');
  const [authorizedFrom, setAuthorizedFrom] = useState<string>('');
  const [authorizedUntil, setAuthorizedUntil] = useState<string>('');
  const [eadCardNumber, setEadCardNumber] = useState('');
  const [eadExpiration, setEadExpiration] = useState<string>('');
  const [i20Expiration, setI20Expiration] = useState<string>('');
  const [i983Required, setI983Required] = useState<boolean>(false);
  const [dsoName, setDsoName] = useState('');
  const [dsoEmail, setDsoEmail] = useState('');
  const [dsoPhone, setDsoPhone] = useState('');
  const [workAuthNotes, setWorkAuthNotes] = useState('');

  // Step 3
  const [trainers, setTrainers] = useState<UserStub[]>([]);
  const [evaluators, setEvaluators] = useState<UserStub[]>([]);
  const [managers, setManagers] = useState<UserStub[]>([]);
  const [trainerId, setTrainerId] = useState('');
  const [evaluatorId, setEvaluatorId] = useState('');
  const [managerId, setManagerId] = useState('');

  // Step 4
  const [catalog, setCatalog] = useState<OnboardingDoc[]>([]);
  const [selectedDocKeys, setSelectedDocKeys] = useState<string[]>([]);
  const [resumeFile, setResumeFile] = useState<File | null>(null);
  const [docFiles, setDocFiles] = useState<Record<string, File | null>>({});

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

  useEffect(() => {
    if (stepIdx !== 2) return;
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

  useEffect(() => {
    if (stepIdx !== 3) return;
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
  const stepError = useMemo(() => {
    if (stepIdx === 0) {
      if (!email.trim()) return 'Email is required.';
      if (!email.includes('@')) return 'Enter a valid email address.';
      if (!fullName.trim()) return 'Full name is required.';
      if (!joiningDate) return 'Joining date is required.';
    }
    if (stepIdx === 1) {
      if (!workAuthType) return 'Pick a work-authorization type.';
    }
    if (stepIdx === 3) {
      if (!resumeFile) return 'Resume upload is required.';
      for (const k of selectedDocKeys) {
        if (!docFiles[k]) return `Upload a file for ${labelFor(catalog, k)}.`;
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
    resumeFile, selectedDocKeys, docFiles, catalog,
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
        eadExpiration: eadExpiration || null,
        i20Expiration: i20Expiration || null,
        i983Required,
        dsoName: dsoName.trim() || null,
        dsoEmail: dsoEmail.trim() || null,
        dsoPhone: dsoPhone.trim() || null,
        workAuthNotes: workAuthNotes.trim() || null,
        trainerUserId: trainerId || null,
        evaluatorUserId: evaluatorId || null,
        managerUserId: managerId || null,
        documents: selectedDocKeys.map((k) => ({
          documentKey: k,
          formPartName: `doc_${k}`,
        })),
        assignMailboxNow: assignMailbox,
        mailboxLocalPart: assignMailbox ? mailboxLocalPart.trim() : null,
        mailboxStartingPassword: assignMailbox ? mailboxPassword : null,
      };
      const fd = new FormData();
      fd.append('metadata', JSON.stringify(metadata));
      fd.append('resume', resumeFile);
      for (const k of selectedDocKeys) {
        const f = docFiles[k];
        if (f) fd.append(`doc_${k}`, f);
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
            eadExpiration={eadExpiration} setEadExpiration={setEadExpiration}
            i20Expiration={i20Expiration} setI20Expiration={setI20Expiration}
            i983Required={i983Required} setI983Required={setI983Required}
            dsoName={dsoName} setDsoName={setDsoName}
            dsoEmail={dsoEmail} setDsoEmail={setDsoEmail}
            dsoPhone={dsoPhone} setDsoPhone={setDsoPhone}
            workAuthNotes={workAuthNotes} setWorkAuthNotes={setWorkAuthNotes}
          />
        )}
        {stepIdx === 2 && (
          <ReportingStep
            trainers={trainers} evaluators={evaluators} managers={managers}
            trainerId={trainerId} setTrainerId={setTrainerId}
            evaluatorId={evaluatorId} setEvaluatorId={setEvaluatorId}
            managerId={managerId} setManagerId={setManagerId}
          />
        )}
        {stepIdx === 3 && (
          <DocumentsStep
            catalog={catalog}
            selectedDocKeys={selectedDocKeys} setSelectedDocKeys={setSelectedDocKeys}
            resumeFile={resumeFile} setResumeFile={setResumeFile}
            docFiles={docFiles} setDocFiles={setDocFiles}
          />
        )}
        {stepIdx === 4 && (
          <ReviewStep
            email={email} fullName={fullName} joiningDate={joiningDate}
            workAuthType={workAuthType}
            trainerId={trainerId} evaluatorId={evaluatorId} managerId={managerId}
            trainers={trainers} evaluators={evaluators} managers={managers}
            selectedDocKeys={selectedDocKeys} catalog={catalog}
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
  eadExpiration: string; setEadExpiration: (v: string) => void;
  i20Expiration: string; setI20Expiration: (v: string) => void;
  i983Required: boolean; setI983Required: (v: boolean) => void;
  dsoName: string; setDsoName: (v: string) => void;
  dsoEmail: string; setDsoEmail: (v: string) => void;
  dsoPhone: string; setDsoPhone: (v: string) => void;
  workAuthNotes: string; setWorkAuthNotes: (v: string) => void;
}) {
  const showF1Fields = props.workAuthType.startsWith('F1_');
  return (
    <div>
      <SectionHeader
        title="Work authorization"
        subtitle="Same fields ERM edits from the compliance card later."
      />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Type" required>
          <select
            value={props.workAuthType}
            onChange={(e) => props.setWorkAuthType(e.target.value)}
            className={inputClass}
          >
            {WORK_AUTH_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </Field>
        <Field label="I-983 required">
          <label className="inline-flex items-center gap-2 text-sm">
            <input
              type="checkbox" checked={props.i983Required}
              onChange={(e) => props.setI983Required(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300"
            />
            <span className="text-slate-700">STEM OPT — I-983 required</span>
          </label>
        </Field>
        <Field label="Authorized from">
          <input
            type="date" value={props.authorizedFrom}
            onChange={(e) => props.setAuthorizedFrom(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="Authorized until">
          <input
            type="date" value={props.authorizedUntil}
            onChange={(e) => props.setAuthorizedUntil(e.target.value)}
            className={inputClass}
          />
        </Field>
        {showF1Fields && (
          <>
            <Field label="EAD card number">
              <input
                value={props.eadCardNumber}
                onChange={(e) => props.setEadCardNumber(e.target.value)}
                className={inputClass}
                autoComplete="off"
              />
            </Field>
            <Field label="EAD expiration">
              <input
                type="date" value={props.eadExpiration}
                onChange={(e) => props.setEadExpiration(e.target.value)}
                className={inputClass}
              />
            </Field>
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
}) {
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
  return (
    <div>
      <SectionHeader
        icon={<FileUp className="h-4 w-4" />}
        title="Documents"
        subtitle="Upload the resume plus any onboarding docs you already have on file."
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
      {props.catalog.length === 0 ? (
        <p className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-500">
          No onboarding document templates configured. Add them from Admin → Onboarding Documents,
          or continue without any.
        </p>
      ) : (
        <ul className="space-y-2">
          {props.catalog.map((doc) => {
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
    </div>
  );
}

function ReviewStep(props: {
  email: string; fullName: string; joiningDate: string;
  workAuthType: string;
  trainerId: string; evaluatorId: string; managerId: string;
  trainers: UserStub[]; evaluators: UserStub[]; managers: UserStub[];
  selectedDocKeys: string[]; catalog: OnboardingDoc[];
  resumeFile: File | null;
  assignMailbox: boolean; setAssignMailbox: (v: boolean) => void;
  mailboxLocalPart: string; setMailboxLocalPart: (v: string) => void;
  mailboxPassword: string; setMailboxPassword: (v: string) => void;
}) {
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
          {props.selectedDocKeys.length === 0
            ? '—'
            : props.selectedDocKeys.map((k) => labelFor(props.catalog, k)).join(', ')}
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
