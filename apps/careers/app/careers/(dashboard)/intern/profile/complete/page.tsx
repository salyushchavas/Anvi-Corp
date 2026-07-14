'use client';

import { FormEvent, useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import toast from 'react-hot-toast';
import { ArrowLeft, ArrowRight, Check } from 'lucide-react';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/Button';
import FormField, { inputClass } from '@/components/ui/FormField';
import FileUpload from '@/components/ui/FileUpload';
import StepperHorizontal from '@/components/ui/StepperHorizontal';
import {
  DEGREE_LEVEL_LABEL,
  type DegreeLevel,
  type WorkAuthTrack,
} from '@/types';
import {
  visaDateRequirementFor,
  VISA_TRACK_LABEL,
} from '@/lib/visa-date-requirement';
import { useInternDashboard } from '@/components/intern/InternDashboardContext';

// Mirrors backend UserProfileResponse. Only the fields the wizard reads/writes
// are listed — UpdateProfileRequest accepts the same shape and the backend
// patches the row in one PUT.
interface ProfileResponse {
  id: string;
  fullName: string;
  email: string;
  phone?: string;
  legalName?: string;
  preferredName?: string;
  school?: string;
  degree?: string;
  degreeLevel?: DegreeLevel;
  specialization?: string;
  graduationYear?: number;
  skillset?: string;
  authorizedToWork?: boolean;
  sponsorshipNeeded?: boolean;
  expectedTrack?: WorkAuthTrack;
  validityDate?: string;
  validityStartDate?: string;
}

interface ResumeRow {
  id: string;
  fileName: string;
  fileSize?: number;
  uploadedAt?: string;
  isDefault?: boolean;
}

const STEPS = [
  { key: 'contact', label: 'Contact' },
  { key: 'education', label: 'Education' },
  { key: 'skills', label: 'Skills & resume' },
  { key: 'work', label: 'Work preferences' },
];

export default function CompleteProfilePage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const dashboard = useInternDashboard();

  const [stepIdx, setStepIdx] = useState(0);

  // Form state
  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [school, setSchool] = useState('');
  const [degreeLevel, setDegreeLevel] = useState<DegreeLevel | ''>('');
  const [specialization, setSpecialization] = useState('');
  const [graduationYear, setGraduationYear] = useState<string>('');
  const [skillset, setSkillset] = useState('');
  const [authorizedToWork, setAuthorizedToWork] = useState<'' | 'yes' | 'no'>('');
  const [sponsorshipNeeded, setSponsorshipNeeded] = useState<'' | 'yes' | 'no'>('');
  const [expectedTrack, setExpectedTrack] = useState<WorkAuthTrack | ''>('');
  const [validityDate, setValidityDate] = useState('');
  const [validityStartDate, setValidityStartDate] = useState('');

  const [resume, setResume] = useState<ResumeRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Returning a focused field on the missing[] handoff is a nice-to-have;
  // for now we just open the editor at step 0 and let the user advance.
  const focusKey = searchParams?.get('focus') ?? null;

  // Prefill on mount.
  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [profileRes, resumesRes] = await Promise.all([
          api.get<ProfileResponse>('/api/v1/users/me'),
          api.get<ResumeRow[]>('/api/v1/resumes/me').catch(() => ({ data: [] as ResumeRow[] })),
        ]);
        if (cancelled) return;
        const p = profileRes.data;
        setFullName(p.fullName ?? '');
        setPhone(p.phone ?? '');
        setSchool(p.school ?? '');
        setDegreeLevel(p.degreeLevel ?? '');
        setSpecialization(p.specialization ?? '');
        setGraduationYear(p.graduationYear ? String(p.graduationYear) : '');
        setSkillset(p.skillset ?? '');
        setAuthorizedToWork(
          p.authorizedToWork === true ? 'yes' : p.authorizedToWork === false ? 'no' : '',
        );
        setSponsorshipNeeded(
          p.sponsorshipNeeded === true ? 'yes' : p.sponsorshipNeeded === false ? 'no' : '',
        );
        setExpectedTrack(p.expectedTrack ?? '');
        setValidityDate(p.validityDate ?? '');
        setValidityStartDate(p.validityStartDate ?? '');

        const rows = Array.isArray(resumesRes.data) ? resumesRes.data : [];
        const best = rows.find((r) => r.isDefault) ?? rows[0] ?? null;
        setResume(best);

        if (focusKey) {
          const focusStep = STEP_FOR_FIELD[focusKey];
          if (focusStep != null) setStepIdx(focusStep);
        }
      } catch (e: any) {
        if (!cancelled) {
          setError(e?.response?.data?.error ?? 'Could not load your profile. Try refresh.');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
    // focusKey only matters on mount; ignore exhaustive-deps re-eval after edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const visaDateReq = visaDateRequirementFor(expectedTrack || undefined);
  const showEndDate = visaDateReq !== 'NONE';
  const showStartDate = visaDateReq === 'BOTH';

  const stepValid = useMemo(() => {
    switch (stepIdx) {
      case 0:
        return phone.trim().length > 0;
      case 1:
        return (
          school.trim().length > 0
          && degreeLevel !== ''
          && graduationYear.trim().length > 0
        );
      case 2:
        return skillset.trim().length > 0 && resume != null;
      case 3:
        // Work prefs are optional — always allow Finish.
        return true;
      default:
        return false;
    }
  }, [stepIdx, phone, school, degreeLevel, graduationYear, skillset, resume]);

  async function persistAll(): Promise<boolean> {
    setError(null);
    let gradYearNum: number | undefined;
    if (graduationYear.trim()) {
      const n = parseInt(graduationYear, 10);
      if (Number.isNaN(n)) {
        setError('End year must be a number.');
        return false;
      }
      gradYearNum = n;
    }
    const body: Record<string, unknown> = {
      fullName: fullName.trim() || undefined,
      phone: phone.trim() || undefined,
      school: school.trim() || undefined,
      degreeLevel: degreeLevel || undefined,
      specialization: specialization.trim() || undefined,
      graduationYear: gradYearNum,
      skillset: skillset.trim() || undefined,
      authorizedToWork: triStateToBool(authorizedToWork),
      sponsorshipNeeded: triStateToBool(sponsorshipNeeded),
      expectedTrack: expectedTrack || undefined,
      validityDate: showEndDate && validityDate ? validityDate : undefined,
      validityStartDate: showStartDate && validityStartDate ? validityStartDate : undefined,
    };
    try {
      setSaving(true);
      await api.put('/api/v1/users/me', body);
      return true;
    } catch (e: any) {
      const msg = e?.response?.data?.error ?? e?.message ?? 'Could not save profile.';
      setError(msg);
      return false;
    } finally {
      setSaving(false);
    }
  }

  async function handleNext(e?: FormEvent) {
    if (e) e.preventDefault();
    if (!stepValid) return;
    const ok = await persistAll();
    if (!ok) return;
    if (stepIdx < STEPS.length - 1) {
      setStepIdx(stepIdx + 1);
      return;
    }
    // Final step — refresh dashboard then route home so the unlocked Apply
    // experience is immediately visible.
    toast.success('Profile updated');
    try {
      await dashboard.refresh();
    } catch {
      /* non-fatal */
    }
    router.push('/careers/intern');
  }

  async function handleResumeUpload(file: File) {
    const form = new FormData();
    form.append('file', file);
    const res = await api.post<ResumeRow>('/api/v1/resumes', form);
    setResume(res.data);
    toast.success('Resume uploaded');
  }

  async function handleResumeRemove() {
    if (!resume) return;
    try {
      await api.delete(`/api/v1/resumes/${resume.id}`);
      setResume(null);
      toast.success('Resume removed');
    } catch (e: any) {
      toast.error(e?.response?.data?.error ?? 'Could not remove resume.');
    }
  }

  if (loading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <div className="h-6 w-48 animate-pulse rounded bg-slate-200" />
        <div className="mt-6 h-32 animate-pulse rounded-xl bg-slate-100" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <header className="mb-6">
        <h1 className="text-xl font-semibold text-slate-900">Complete your profile</h1>
        <p className="mt-1 text-sm text-slate-600">
          A few quick details and you'll be able to apply to any open internship.
        </p>
      </header>

      <div className="mb-8">
        <StepperHorizontal steps={STEPS} currentIndex={stepIdx} />
      </div>

      {error && (
        <div
          role="alert"
          className="mb-5 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700"
        >
          {error}
        </div>
      )}

      <form
        onSubmit={handleNext}
        className="space-y-6 rounded-xl border border-slate-200 bg-white p-6 shadow-ds-sm"
      >
        {stepIdx === 0 && (
          <>
            <FormField label="Full name" htmlFor="fullName">
              <input
                id="fullName"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                className={inputClass()}
                autoComplete="name"
              />
            </FormField>
            <FormField label="Phone" htmlFor="phone" required>
              <input
                id="phone"
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                className={inputClass()}
                autoComplete="tel"
                placeholder="e.g. +1 555 123 4567"
              />
            </FormField>
          </>
        )}

        {stepIdx === 1 && (
          <>
            <FormField label="School" htmlFor="school" required>
              <input
                id="school"
                value={school}
                onChange={(e) => setSchool(e.target.value)}
                className={inputClass()}
                autoComplete="organization"
              />
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="Degree" htmlFor="degreeLevel" required>
                <select
                  id="degreeLevel"
                  value={degreeLevel}
                  onChange={(e) => setDegreeLevel(e.target.value as DegreeLevel | '')}
                  className={inputClass()}
                >
                  <option value="">Select…</option>
                  {(Object.keys(DEGREE_LEVEL_LABEL) as DegreeLevel[]).map((d) => (
                    <option key={d} value={d}>
                      {DEGREE_LEVEL_LABEL[d]}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField label="End year" htmlFor="graduationYear" required>
                <input
                  id="graduationYear"
                  type="number"
                  inputMode="numeric"
                  value={graduationYear}
                  onChange={(e) => setGraduationYear(e.target.value)}
                  className={inputClass()}
                  placeholder={String(new Date().getFullYear())}
                />
              </FormField>
            </div>
            <FormField label="Specialization (optional)" htmlFor="specialization">
              <input
                id="specialization"
                value={specialization}
                onChange={(e) => setSpecialization(e.target.value)}
                className={inputClass()}
                placeholder="e.g. Computer Science"
              />
            </FormField>
          </>
        )}

        {stepIdx === 2 && (
          <>
            <FormField
              label="Skills"
              htmlFor="skillset"
              required
              helper="Comma-separated — e.g. Python, SQL, React"
            >
              <textarea
                id="skillset"
                value={skillset}
                onChange={(e) => setSkillset(e.target.value)}
                rows={3}
                className={inputClass()}
              />
            </FormField>
            <FormField label="Resume" htmlFor="resume" required>
              <FileUpload
                current={resume ? { name: resume.fileName, size: resume.fileSize } : null}
                onUpload={handleResumeUpload}
                onRemove={handleResumeRemove}
              />
            </FormField>
          </>
        )}

        {stepIdx === 3 && (
          <>
            <p className="text-sm text-slate-600">
              Optional — you can fill these in later from your profile. They help us
              match you to roles with the right work-authorization fit.
            </p>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="Authorized to work in the US?">
                <YesNo
                  name="authorizedToWork"
                  value={authorizedToWork}
                  onChange={setAuthorizedToWork}
                />
              </FormField>
              <FormField label="Need sponsorship now or in future?">
                <YesNo
                  name="sponsorshipNeeded"
                  value={sponsorshipNeeded}
                  onChange={setSponsorshipNeeded}
                />
              </FormField>
            </div>
            <FormField label="Expected authorization track" htmlFor="expectedTrack">
              <select
                id="expectedTrack"
                value={expectedTrack}
                onChange={(e) => setExpectedTrack(e.target.value as WorkAuthTrack | '')}
                className={inputClass()}
              >
                <option value="">Select…</option>
                {(Object.keys(VISA_TRACK_LABEL) as WorkAuthTrack[]).map((t) => (
                  <option key={t} value={t}>
                    {VISA_TRACK_LABEL[t]}
                  </option>
                ))}
              </select>
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              {showStartDate && (
                <FormField label="Authorization start date" htmlFor="validityStartDate">
                  <input
                    id="validityStartDate"
                    type="date"
                    value={validityStartDate}
                    onChange={(e) => setValidityStartDate(e.target.value)}
                    className={inputClass()}
                  />
                </FormField>
              )}
              {showEndDate && (
                <FormField label="Authorization end date" htmlFor="validityDate">
                  <input
                    id="validityDate"
                    type="date"
                    value={validityDate}
                    onChange={(e) => setValidityDate(e.target.value)}
                    className={inputClass()}
                  />
                </FormField>
              )}
            </div>
          </>
        )}

        <div className="flex items-center justify-between border-t border-slate-200 pt-5">
          <Button
            type="button"
            variant="secondary"
            leftIcon={<ArrowLeft className="h-4 w-4" />}
            onClick={() => setStepIdx(Math.max(0, stepIdx - 1))}
            disabled={stepIdx === 0 || saving}
          >
            Back
          </Button>
          <Button
            type="submit"
            loading={saving}
            disabled={!stepValid}
            rightIcon={
              stepIdx === STEPS.length - 1 ? (
                <Check className="h-4 w-4" />
              ) : (
                <ArrowRight className="h-4 w-4" />
              )
            }
          >
            {stepIdx === STEPS.length - 1 ? 'Finish' : 'Next'}
          </Button>
        </div>
      </form>
    </div>
  );
}

const STEP_FOR_FIELD: Record<string, number> = {
  phone: 0,
  school: 1,
  degree: 1,
  graduationYear: 1,
  skillset: 2,
  resume: 2,
};

function triStateToBool(v: '' | 'yes' | 'no'): boolean | undefined {
  if (v === 'yes') return true;
  if (v === 'no') return false;
  return undefined;
}

function YesNo({
  name,
  value,
  onChange,
}: {
  name: string;
  value: '' | 'yes' | 'no';
  onChange: (v: '' | 'yes' | 'no') => void;
}) {
  return (
    <div className="flex gap-2">
      {(['yes', 'no'] as const).map((opt) => (
        <label
          key={opt}
          className={
            'flex flex-1 cursor-pointer items-center justify-center gap-2 rounded-md border px-3 py-2 text-sm transition-colors ' +
            (value === opt
              ? 'border-brand-500 bg-brand-50 text-brand-700'
              : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50')
          }
        >
          <input
            type="radio"
            name={name}
            checked={value === opt}
            onChange={() => onChange(opt)}
            className="h-4 w-4 text-brand-700 focus:ring-brand-500"
          />
          {opt === 'yes' ? 'Yes' : 'No'}
        </label>
      ))}
    </div>
  );
}
