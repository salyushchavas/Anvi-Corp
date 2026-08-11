'use client';

/**
 * B2 profile expansion — the intern's Profile page.
 *
 * <p>View + edit surface for the full profile (contact, education list,
 * address, skills, work-auth attestation + dates) and a resume replace
 * control. Reuses the same {@code FormField} + {@code inputClass} + {@code
 * FileUpload} primitives the completion wizard uses — no duplicate look
 * and feel. Every write hits the existing {@code PUT /api/v1/users/me}
 * (backend fans out to Candidate + WAR upserts + submission-ack + edit
 * notifications), plus the new {@code /api/v1/candidates/me/educations}
 * CRUD for the multi-degree table.</p>
 */

import { FormEvent, useCallback, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { Plus, Save, Star, StarOff, Trash2 } from 'lucide-react';
import { api } from '@/lib/careers/api';
import { Button } from '@/components/ui/Button';
import ConfirmDialog from '@/components/ConfirmDialog';
import FormField, { inputClass } from '@/components/ui/FormField';
import PhoneInput from '@/components/ui/PhoneInput';
import FileUpload from '@/components/ui/FileUpload';
import { US_STATES, US_ZIP_RE } from '@/lib/careers/us-states';
import {
  DEGREE_LEVEL_LABEL,
  type DegreeLevel,
  type WorkAuthTrack,
} from '@/types';
import {
  visaDateRequirementFor,
  VISA_TRACK_LABEL,
} from '@/lib/careers/visa-date-requirement';

interface ProfileResponse {
  id: string;
  fullName: string;
  email: string;
  phone?: string;
  legalName?: string;
  preferredName?: string;
  skillset?: string;
  authorizedToWork?: boolean;
  sponsorshipNeeded?: boolean;
  expectedTrack?: WorkAuthTrack;
  validityDate?: string;
  validityStartDate?: string;
  // B2 additions
  addressStreet?: string;
  addressApt?: string;
  addressCity?: string;
  addressState?: string;
  addressZip?: string;
  addressCountry?: string;
  authorizedFrom?: string;
  authorizedUntil?: string;
  profileSubmittedAt?: string;
}

interface EducationRow {
  id: string;
  degreeLevel: DegreeLevel | null;
  institution: string | null;
  fieldOfStudy: string | null;
  graduationDate: string | null;
  isPrimary: boolean;
}

interface ResumeRow {
  id: string;
  fileName: string;
  fileSize?: number;
  uploadedAt?: string;
  isDefault?: boolean;
}

export default function InternProfilePage() {
  const [profile, setProfile] = useState<ProfileResponse | null>(null);
  const [educations, setEducations] = useState<EducationRow[]>([]);
  const [resume, setResume] = useState<ResumeRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [pRes, eRes, rRes] = await Promise.all([
        api.get<ProfileResponse>('/api/v1/users/me'),
        api.get<{ items: EducationRow[] }>('/api/v1/candidates/me/educations'),
        api.get<ResumeRow[]>('/api/v1/resumes/me').catch(() => ({ data: [] as ResumeRow[] })),
      ]);
      setProfile(pRes.data);
      setEducations(eRes.data.items ?? []);
      const rows = Array.isArray(rRes.data) ? rRes.data : [];
      setResume(rows.find((r) => r.isDefault) ?? rows[0] ?? null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setError(ax.response?.data?.error ?? ax.message ?? 'Could not load profile.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadAll(); }, [loadAll]);

  if (loading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <div className="h-6 w-48 animate-pulse rounded bg-slate-200" />
        <div className="mt-6 h-32 animate-pulse rounded-xl bg-slate-100" />
      </div>
    );
  }

  if (error || !profile) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-10">
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {error ?? 'Profile not found.'}
        </p>
      </div>
    );
  }

  async function saveProfile(patch: Partial<ProfileResponse>) {
    if (!profile) return;
    // Client-side ZIP + state validation mirroring the server. Both are
    // no-ops when the fields are empty (partial saves stay open).
    const zip = (patch.addressZip ?? profile.addressZip ?? '').trim();
    if (zip && !US_ZIP_RE.test(zip)) {
      toast.error('ZIP must be 5 digits or 5+4 (e.g. 12345 or 12345-6789)');
      return;
    }
    const state = (patch.addressState ?? profile.addressState ?? '').trim().toUpperCase();
    if (state && !US_STATES.some((s) => s.code === state)) {
      toast.error('State must be a 2-letter US state code (or DC)');
      return;
    }
    setSaving(true);
    try {
      const body = {
        fullName: patch.fullName ?? profile.fullName,
        phone: patch.phone ?? profile.phone,
        legalName: patch.legalName ?? profile.legalName,
        preferredName: patch.preferredName ?? profile.preferredName,
        skillset: patch.skillset ?? profile.skillset,
        authorizedToWork: patch.authorizedToWork ?? profile.authorizedToWork,
        sponsorshipNeeded: patch.sponsorshipNeeded ?? profile.sponsorshipNeeded,
        expectedTrack: patch.expectedTrack ?? profile.expectedTrack,
        validityDate: patch.validityDate ?? profile.validityDate,
        validityStartDate: patch.validityStartDate ?? profile.validityStartDate,
        addressStreet: patch.addressStreet ?? profile.addressStreet,
        addressApt: patch.addressApt ?? profile.addressApt,
        addressCity: patch.addressCity ?? profile.addressCity,
        addressState: state || undefined,
        addressZip: zip || undefined,
        authorizedFrom: patch.authorizedFrom ?? profile.authorizedFrom,
        authorizedUntil: patch.authorizedUntil ?? profile.authorizedUntil,
      };
      const res = await api.put<ProfileResponse>('/api/v1/users/me', body);
      setProfile(res.data);
      toast.success('Profile saved');
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Save failed.');
    } finally {
      setSaving(false);
    }
  }

  async function handleResumeUpload(file: File) {
    const form = new FormData();
    form.append('file', file);
    const res = await api.post<ResumeRow>('/api/v1/resumes', form);
    setResume(res.data);
    toast.success('Resume replaced');
  }

  async function handleResumeRemove() {
    if (!resume) return;
    await api.delete(`/api/v1/resumes/${resume.id}`);
    setResume(null);
    toast.success('Resume removed');
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8">
      <header>
        <h1 className="text-xl font-semibold text-slate-900">My Profile</h1>
        <p className="mt-1 text-sm text-slate-600">
          Everything ERM sees — update anything here.{' '}
          {profile.profileSubmittedAt ? (
            <span className="text-emerald-700">
              Submitted {new Date(profile.profileSubmittedAt).toLocaleDateString()}.
            </span>
          ) : (
            <span className="text-amber-700">
              Not yet submitted — complete every section to finalize.
            </span>
          )}
        </p>
      </header>

      <ContactSection profile={profile} saving={saving} onSave={saveProfile} />
      <EducationSection
        educations={educations}
        onReload={loadAll}
      />
      <AddressSection profile={profile} saving={saving} onSave={saveProfile} />
      <WorkAuthSection profile={profile} saving={saving} onSave={saveProfile} />
      <SkillsSection profile={profile} saving={saving} onSave={saveProfile} />
      <ResumeSection
        resume={resume}
        onUpload={handleResumeUpload}
        onRemove={handleResumeRemove}
      />
    </div>
  );
}

// ── Section components ────────────────────────────────────────────────

function SectionCard({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-ds-sm">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </h2>
      {children}
    </section>
  );
}

function ContactSection({
  profile, saving, onSave,
}: { profile: ProfileResponse; saving: boolean; onSave: (p: Partial<ProfileResponse>) => void }) {
  const [fullName, setFullName] = useState(profile.fullName ?? '');
  const [phone, setPhone] = useState(profile.phone ?? '');

  function submit(e: FormEvent) {
    e.preventDefault();
    onSave({ fullName, phone });
  }

  return (
    <SectionCard title="Contact">
      <form className="space-y-4" onSubmit={submit}>
        <FormField label="Full name" htmlFor="p_fullName">
          <input
            id="p_fullName" value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            className={inputClass()} autoComplete="name"
          />
        </FormField>
        <FormField label="Phone" htmlFor="p_phone">
          <PhoneInput
            id="p_phone"
            value={phone}
            onChange={setPhone}
            autoComplete="tel"
          />
        </FormField>
        <p className="text-xs text-slate-500">Email: {profile.email}</p>
        <div className="flex justify-end">
          <Button type="submit" loading={saving} leftIcon={<Save className="h-4 w-4" />}>
            Save contact
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}

function EducationSection({
  educations, onReload,
}: { educations: EducationRow[]; onReload: () => Promise<void> }) {
  const [draft, setDraft] = useState<{
    degreeLevel: DegreeLevel | '';
    institution: string;
    fieldOfStudy: string;
    graduationDate: string;
  }>({ degreeLevel: '', institution: '', fieldOfStudy: '', graduationDate: '' });
  const [busy, setBusy] = useState(false);
  const [showAdd, setShowAdd] = useState(false);

  async function add(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post('/api/v1/candidates/me/educations', {
        degreeLevel: draft.degreeLevel || undefined,
        institution: draft.institution.trim() || undefined,
        fieldOfStudy: draft.fieldOfStudy.trim() || undefined,
        graduationDate: draft.graduationDate || undefined,
      });
      setDraft({ degreeLevel: '', institution: '', fieldOfStudy: '', graduationDate: '' });
      setShowAdd(false);
      toast.success('Education added');
      await onReload();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Add failed');
    } finally {
      setBusy(false);
    }
  }

  async function setPrimary(id: string) {
    try {
      await api.post(`/api/v1/candidates/me/educations/${id}/set-primary`);
      toast.success('Primary degree updated');
      await onReload();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Update failed');
    }
  }

  const [removeId, setRemoveId] = useState<string | null>(null);
  async function remove() {
    if (!removeId) return;
    try {
      await api.delete(`/api/v1/candidates/me/educations/${removeId}`);
      toast.success('Removed');
      setRemoveId(null);
      await onReload();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      toast.error(ax.response?.data?.error ?? ax.message ?? 'Delete failed');
    }
  }

  return (
    <SectionCard title="Education">
      <ul className="space-y-2">
        {educations.length === 0 && (
          <li className="rounded-md border border-dashed border-slate-300 bg-slate-50 p-3 text-xs text-slate-600">
            No degrees yet — add your Bachelor&apos;s and Master&apos;s as separate entries.
          </li>
        )}
        {educations.map((e) => (
          <li key={e.id} className="flex items-start justify-between gap-2 rounded-md border border-slate-200 bg-white p-3">
            <div className="min-w-0 flex-1 text-sm">
              <p className="font-medium text-slate-900">
                {e.degreeLevel ? DEGREE_LEVEL_LABEL[e.degreeLevel] : 'Degree'}
                {e.fieldOfStudy ? ` — ${e.fieldOfStudy}` : ''}
                {e.isPrimary && (
                  <span className="ml-2 rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold text-emerald-700">
                    Primary
                  </span>
                )}
              </p>
              <p className="text-xs text-slate-600">
                {e.institution ?? '—'}
                {e.graduationDate ? ` · ${e.graduationDate}` : ''}
              </p>
            </div>
            <div className="flex shrink-0 gap-2">
              {!e.isPrimary && (
                <button
                  type="button"
                  onClick={() => void setPrimary(e.id)}
                  title="Mark as primary"
                  className="rounded-md border border-slate-200 bg-white p-1.5 text-slate-600 hover:bg-slate-50"
                >
                  <Star className="h-4 w-4" />
                </button>
              )}
              {e.isPrimary && (
                <span
                  title="Primary — set another entry as primary to demote this one"
                  className="rounded-md border border-slate-200 bg-slate-50 p-1.5 text-slate-400"
                >
                  <StarOff className="h-4 w-4" />
                </span>
              )}
              <button
                type="button"
                onClick={() => setRemoveId(e.id)}
                title="Remove"
                className="rounded-md border border-red-200 bg-red-50 p-1.5 text-red-700 hover:bg-red-100"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          </li>
        ))}
      </ul>

      {showAdd ? (
        <form className="mt-3 space-y-3 rounded-md border border-slate-200 bg-slate-50 p-3" onSubmit={add}>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Degree" htmlFor="e_degree">
              <select
                id="e_degree" value={draft.degreeLevel}
                onChange={(v) => setDraft({ ...draft, degreeLevel: v.target.value as DegreeLevel | '' })}
                className={inputClass()}
              >
                <option value="">Select…</option>
                {(Object.keys(DEGREE_LEVEL_LABEL) as DegreeLevel[]).map((d) => (
                  <option key={d} value={d}>{DEGREE_LEVEL_LABEL[d]}</option>
                ))}
              </select>
            </FormField>
            <FormField label="Graduation date" htmlFor="e_grad">
              <input
                id="e_grad" type="date" value={draft.graduationDate}
                onChange={(v) => setDraft({ ...draft, graduationDate: v.target.value })}
                className={inputClass()}
              />
            </FormField>
          </div>
          <FormField label="Institution" htmlFor="e_inst">
            <input
              id="e_inst" value={draft.institution}
              onChange={(v) => setDraft({ ...draft, institution: v.target.value })}
              className={inputClass()}
            />
          </FormField>
          <FormField label="Field of study" htmlFor="e_fos">
            <input
              id="e_fos" value={draft.fieldOfStudy}
              onChange={(v) => setDraft({ ...draft, fieldOfStudy: v.target.value })}
              className={inputClass()}
            />
          </FormField>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setShowAdd(false)}>Cancel</Button>
            <Button type="submit" loading={busy}>Add</Button>
          </div>
        </form>
      ) : (
        <Button type="button" variant="secondary" leftIcon={<Plus className="h-4 w-4" />} onClick={() => setShowAdd(true)}>
          Add degree
        </Button>
      )}
      <ConfirmDialog
        open={removeId != null}
        onClose={() => setRemoveId(null)}
        onConfirm={remove}
        title="Remove this education entry?"
        description="This entry will be deleted from your profile. You can re-add it any time."
        confirmLabel="Remove"
        variant="danger"
      />
    </SectionCard>
  );
}

function AddressSection({
  profile, saving, onSave,
}: { profile: ProfileResponse; saving: boolean; onSave: (p: Partial<ProfileResponse>) => void }) {
  const [street, setStreet] = useState(profile.addressStreet ?? '');
  const [apt, setApt] = useState(profile.addressApt ?? '');
  const [city, setCity] = useState(profile.addressCity ?? '');
  const [state, setState] = useState(profile.addressState ?? '');
  const [zip, setZip] = useState(profile.addressZip ?? '');
  const [zipError, setZipError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    // Extra client-side validation before the save round-trip.
    if (zip && !US_ZIP_RE.test(zip)) {
      setZipError('ZIP must be 5 digits or 5+4 (e.g. 12345 or 12345-6789)');
      return;
    }
    setZipError(null);
    onSave({
      addressStreet: street,
      addressApt: apt,
      addressCity: city,
      addressState: state,
      addressZip: zip,
    });
  }

  return (
    <SectionCard title="Address (US)">
      <form className="space-y-4" onSubmit={submit}>
        <FormField label="Street" htmlFor="p_street">
          <input
            id="p_street" value={street}
            onChange={(e) => setStreet(e.target.value)}
            className={inputClass()} autoComplete="address-line1"
          />
        </FormField>
        <div className="grid gap-4 sm:grid-cols-3">
          <FormField label="Apt / Unit (optional)" htmlFor="p_apt">
            <input
              id="p_apt" value={apt}
              onChange={(e) => setApt(e.target.value)}
              className={inputClass()} autoComplete="address-line2"
            />
          </FormField>
          <FormField label="City" htmlFor="p_city">
            <input
              id="p_city" value={city}
              onChange={(e) => setCity(e.target.value)}
              className={inputClass()} autoComplete="address-level2"
            />
          </FormField>
          <FormField label="State" htmlFor="p_state">
            <select
              id="p_state" value={state}
              onChange={(e) => setState(e.target.value)}
              className={inputClass()} autoComplete="address-level1"
            >
              <option value="">Select…</option>
              {US_STATES.map((s) => (
                <option key={s.code} value={s.code}>{s.code} — {s.name}</option>
              ))}
            </select>
          </FormField>
        </div>
        <FormField label="ZIP" htmlFor="p_zip" error={zipError} required>
          <input
            id="p_zip" value={zip}
            onChange={(e) => { setZip(e.target.value); setZipError(null); }}
            className={inputClass(!!zipError)}
            placeholder="12345 or 12345-6789"
            autoComplete="postal-code"
          />
        </FormField>
        <p className="text-xs text-slate-500">
          Country: United States (default).
        </p>
        <div className="flex justify-end">
          <Button type="submit" loading={saving} leftIcon={<Save className="h-4 w-4" />}>
            Save address
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}

function WorkAuthSection({
  profile, saving, onSave,
}: { profile: ProfileResponse; saving: boolean; onSave: (p: Partial<ProfileResponse>) => void }) {
  const [track, setTrack] = useState<WorkAuthTrack | ''>(profile.expectedTrack ?? '');
  const [authorizedFrom, setAuthorizedFrom] = useState(profile.authorizedFrom ?? '');
  const [authorizedUntil, setAuthorizedUntil] = useState(profile.authorizedUntil ?? '');
  const [validityDate, setValidityDate] = useState(profile.validityDate ?? '');
  const [validityStartDate, setValidityStartDate] = useState(profile.validityStartDate ?? '');

  const req = visaDateRequirementFor(track || undefined);
  const showEnd = req !== 'NONE';
  const showStart = req === 'BOTH';

  function submit(e: FormEvent) {
    e.preventDefault();
    onSave({
      expectedTrack: (track || undefined) as WorkAuthTrack | undefined,
      validityDate: showEnd && validityDate ? validityDate : undefined,
      validityStartDate: showStart && validityStartDate ? validityStartDate : undefined,
      authorizedFrom: authorizedFrom || undefined,
      authorizedUntil: authorizedUntil || undefined,
    });
  }

  return (
    <SectionCard title="Work authorization">
      <form className="space-y-4" onSubmit={submit}>
        <FormField label="Expected authorization track" htmlFor="p_track">
          <select
            id="p_track" value={track}
            onChange={(e) => setTrack(e.target.value as WorkAuthTrack | '')}
            className={inputClass()}
          >
            <option value="">Select…</option>
            {(Object.keys(VISA_TRACK_LABEL) as WorkAuthTrack[]).map((t) => (
              <option key={t} value={t}>{VISA_TRACK_LABEL[t]}</option>
            ))}
          </select>
        </FormField>
        <div className="grid gap-4 sm:grid-cols-2">
          {showStart && (
            <FormField label="Self-declared start" htmlFor="p_vs">
              <input
                id="p_vs" type="date" value={validityStartDate}
                onChange={(e) => setValidityStartDate(e.target.value)}
                className={inputClass()}
              />
            </FormField>
          )}
          {showEnd && (
            <FormField label="Self-declared end" htmlFor="p_ve">
              <input
                id="p_ve" type="date" value={validityDate}
                onChange={(e) => setValidityDate(e.target.value)}
                className={inputClass()}
              />
            </FormField>
          )}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Authorized from" htmlFor="p_af">
            <input
              id="p_af" type="date" value={authorizedFrom}
              onChange={(e) => setAuthorizedFrom(e.target.value)}
              className={inputClass()}
            />
          </FormField>
          <FormField label="Authorized until" htmlFor="p_au">
            <input
              id="p_au" type="date" value={authorizedUntil}
              onChange={(e) => setAuthorizedUntil(e.target.value)}
              className={inputClass()}
            />
          </FormField>
        </div>
        <p className="text-xs text-slate-500">
          {'"Authorized from" and "Authorized until" post to the same work-authorization '
            + 'record ERM uses for the Compliance tracker.'}
        </p>
        <div className="flex justify-end">
          <Button type="submit" loading={saving} leftIcon={<Save className="h-4 w-4" />}>
            Save work authorization
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}

function SkillsSection({
  profile, saving, onSave,
}: { profile: ProfileResponse; saving: boolean; onSave: (p: Partial<ProfileResponse>) => void }) {
  const [skillset, setSkillset] = useState(profile.skillset ?? '');
  function submit(e: FormEvent) {
    e.preventDefault();
    onSave({ skillset });
  }
  return (
    <SectionCard title="Skillset">
      <form className="space-y-4" onSubmit={submit}>
        <FormField
          label="Skills" htmlFor="p_skills"
          helper="Comma-separated — e.g. Python, SQL, React"
        >
          <textarea
            id="p_skills" value={skillset} rows={3}
            onChange={(e) => setSkillset(e.target.value)}
            className={inputClass()}
          />
        </FormField>
        <div className="flex justify-end">
          <Button type="submit" loading={saving} leftIcon={<Save className="h-4 w-4" />}>
            Save skills
          </Button>
        </div>
      </form>
    </SectionCard>
  );
}

function ResumeSection({
  resume, onUpload, onRemove,
}: { resume: ResumeRow | null; onUpload: (file: File) => Promise<void>; onRemove: () => Promise<void> }) {
  return (
    <SectionCard title="Resume">
      <FormField label="Current resume" htmlFor="p_resume">
        <FileUpload
          current={resume ? { name: resume.fileName, size: resume.fileSize } : null}
          onUpload={onUpload}
          onRemove={onRemove}
        />
      </FormField>
      <p className="text-xs text-slate-500">
        Uploading a new file replaces the previous one. Resume-only changes do not notify ERM.
      </p>
    </SectionCard>
  );
}
