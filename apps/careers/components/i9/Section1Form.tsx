'use client';

import { useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import FormField, { inputClass } from '@/components/ui/FormField';
import { US_STATES } from '@/lib/us-states';
import { todayDateInput } from '@/lib/format-date';
import type {
  CitizenshipStatus,
  I9FormResponse,
  OnboardingTaskResponse,
  Section1Request,
} from '@/types';

interface Props {
  form: I9FormResponse;
  onSaved: (updated: I9FormResponse) => void;
}

interface FormState {
  lastName: string;
  firstName: string;
  middleInitial: string;
  otherLastNamesUsed: string;
  addressStreet: string;
  addressAptNumber: string;
  addressCity: string;
  addressState: string;
  addressZipCode: string;
  dateOfBirth: string;
  ssn: string;
  email: string;
  phoneNumber: string;
  citizenshipStatus: CitizenshipStatus | '';
  alienRegistrationNumber: string;
  foreignPassportNumber: string;
  foreignPassportCountry: string;
  workAuthExpirationDate: string;
  preparerTranslatorUsed: boolean;
}

const CITIZENSHIP_OPTIONS: { value: CitizenshipStatus; label: string }[] = [
  { value: 'US_CITIZEN', label: 'I am a citizen of the United States' },
  {
    value: 'NONCITIZEN_NATIONAL',
    label: 'I am a noncitizen national of the United States',
  },
  {
    value: 'LAWFUL_PERMANENT_RESIDENT',
    label: 'I am a lawful permanent resident',
  },
  {
    value: 'ALIEN_AUTHORIZED_TO_WORK',
    label: 'I am an alien authorized to work',
  },
];

const ZIP_RE = /^\d{5}(-\d{4})?$/;
const SSN_RE = /^\d{3}-\d{2}-\d{4}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function formatSSN(value: string): string {
  const digits = value.replace(/\D/g, '').slice(0, 9);
  if (digits.length <= 3) return digits;
  if (digits.length <= 5) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
}

function blankToUndef(s: string): string | undefined {
  const t = s.trim();
  return t.length > 0 ? t : undefined;
}

export default function Section1Form({ form, onSaved }: Props) {
  const { user } = useAuth();

  const [data, setData] = useState<FormState>(() => ({
    lastName: form.lastName ?? '',
    firstName: form.firstName ?? '',
    middleInitial: form.middleInitial ?? '',
    otherLastNamesUsed: form.otherLastNamesUsed ?? '',
    addressStreet: form.addressStreet ?? '',
    addressAptNumber: form.addressAptNumber ?? '',
    addressCity: form.addressCity ?? '',
    addressState: form.addressState ?? '',
    addressZipCode: form.addressZipCode ?? '',
    dateOfBirth: form.dateOfBirth ?? '',
    ssn: form.ssn ?? '',
    email: form.email ?? user?.email ?? '',
    phoneNumber: form.phoneNumber ?? '',
    citizenshipStatus: form.citizenshipStatus ?? '',
    alienRegistrationNumber: form.alienRegistrationNumber ?? '',
    foreignPassportNumber: form.foreignPassportNumber ?? '',
    foreignPassportCountry: form.foreignPassportCountry ?? '',
    workAuthExpirationDate: form.workAuthExpirationDate ?? '',
    preparerTranslatorUsed: form.preparerTranslatorUsed ?? false,
  }));

  const [attested, setAttested] = useState(false);
  const [submitting, setSubmitting] = useState<'draft' | 'submit' | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [topError, setTopError] = useState<string | null>(null);

  const formRef = useRef<HTMLFormElement>(null);

  const today = useMemo(() => todayDateInput(), []);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setData((prev) => ({ ...prev, [key]: value }));
    if (errors[key as string]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key as string];
        return next;
      });
    }
  }

  function validate(): Record<string, string> {
    const e: Record<string, string> = {};
    if (!data.lastName.trim()) e.lastName = 'Last name is required';
    if (!data.firstName.trim()) e.firstName = 'First name is required';
    if (!data.addressStreet.trim()) e.addressStreet = 'Street address is required';
    if (!data.addressCity.trim()) e.addressCity = 'City is required';
    if (!data.addressState) e.addressState = 'State is required';
    if (!data.addressZipCode.trim()) {
      e.addressZipCode = 'ZIP code is required';
    } else if (!ZIP_RE.test(data.addressZipCode.trim())) {
      e.addressZipCode = 'ZIP must be 5 digits or 5+4 (e.g. 12345 or 12345-6789)';
    }
    if (!data.dateOfBirth) {
      e.dateOfBirth = 'Date of birth is required';
    } else if (new Date(data.dateOfBirth) >= new Date(today)) {
      e.dateOfBirth = 'Date of birth must be in the past';
    }
    if (!data.email.trim()) {
      e.email = 'Email is required';
    } else if (!EMAIL_RE.test(data.email.trim())) {
      e.email = 'Enter a valid email address';
    }
    if (!data.phoneNumber.trim()) {
      e.phoneNumber = 'Phone number is required';
    } else if (data.phoneNumber.replace(/\D/g, '').length < 10) {
      e.phoneNumber = 'Phone number must have at least 10 digits';
    }
    if (!data.citizenshipStatus) {
      e.citizenshipStatus = 'Please select your citizenship/immigration status';
    } else if (data.citizenshipStatus === 'LAWFUL_PERMANENT_RESIDENT') {
      if (!data.alienRegistrationNumber.trim()) {
        e.alienRegistrationNumber =
          'Alien Registration / USCIS Number is required for lawful permanent residents';
      }
    } else if (data.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK') {
      if (!data.workAuthExpirationDate) {
        e.workAuthExpirationDate =
          'Work authorization expiration date is required';
      } else if (new Date(data.workAuthExpirationDate) <= new Date(today)) {
        e.workAuthExpirationDate =
          'Work authorization expiration date must be in the future';
      }
    }
    if (data.ssn.trim() && !SSN_RE.test(data.ssn.trim())) {
      e.ssn = 'SSN must be in XXX-XX-XXXX format';
    }
    if (!attested) {
      e.attested = 'You must check the attestation box to submit';
    }
    return e;
  }

  function scrollToFirstError(errs: Record<string, string>) {
    const first = Object.keys(errs)[0];
    if (!first) return;
    const el = document.getElementById('i9-' + first);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (el && (el as HTMLInputElement).focus) {
      (el as HTMLInputElement).focus({ preventScroll: true });
    }
  }

  function buildPayload(draft: boolean): Section1Request {
    return {
      lastName: blankToUndef(data.lastName),
      firstName: blankToUndef(data.firstName),
      middleInitial: blankToUndef(data.middleInitial),
      otherLastNamesUsed: blankToUndef(data.otherLastNamesUsed),
      addressStreet: blankToUndef(data.addressStreet),
      addressAptNumber: blankToUndef(data.addressAptNumber),
      addressCity: blankToUndef(data.addressCity),
      addressState: blankToUndef(data.addressState),
      addressZipCode: blankToUndef(data.addressZipCode),
      dateOfBirth: blankToUndef(data.dateOfBirth),
      ssn: blankToUndef(data.ssn),
      email: blankToUndef(data.email),
      phoneNumber: blankToUndef(data.phoneNumber),
      citizenshipStatus: data.citizenshipStatus || undefined,
      alienRegistrationNumber: blankToUndef(data.alienRegistrationNumber),
      foreignPassportNumber: blankToUndef(data.foreignPassportNumber),
      foreignPassportCountry: blankToUndef(data.foreignPassportCountry),
      workAuthExpirationDate: blankToUndef(data.workAuthExpirationDate),
      preparerTranslatorUsed: data.preparerTranslatorUsed,
      draft,
    };
  }

  async function autoMarkOnboardingTask() {
    try {
      const res = await api.get<OnboardingTaskResponse[]>(
        '/api/v1/onboarding/me'
      );
      const t = res.data?.find((task) => task.taskKey === 'I9_SECTION_1');
      if (t && t.status !== 'COMPLETED') {
        await api.patch(`/api/v1/onboarding/tasks/${t.id}`, {
          status: 'COMPLETED',
        });
      }
    } catch {
      // Silently ignore — onboarding sync is best-effort.
    }
  }

  async function saveDraft() {
    setTopError(null);
    setSubmitting('draft');
    try {
      const res = await api.post<I9FormResponse>(
        `/api/v1/i9/${form.id}/section1`,
        buildPayload(true)
      );
      onSaved(res.data);
      toast.success('Draft saved');
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't save draft. Try again.";
      setTopError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  async function submitSection1() {
    setTopError(null);
    const errs = validate();
    if (Object.keys(errs).length > 0) {
      setErrors(errs);
      setTimeout(() => scrollToFirstError(errs), 0);
      return;
    }
    setSubmitting('submit');
    try {
      const res = await api.post<I9FormResponse>(
        `/api/v1/i9/${form.id}/section1`,
        buildPayload(false)
      );
      onSaved(res.data);
      toast.success('Section 1 submitted ✓');
      void autoMarkOnboardingTask();
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't submit Section 1.";
      setTopError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  const showAlienRegistration =
    data.citizenshipStatus === 'LAWFUL_PERMANENT_RESIDENT' ||
    data.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK';

  return (
    <form
      ref={formRef}
      onSubmit={(e) => {
        e.preventDefault();
        void submitSection1();
      }}
      className="max-w-2xl"
    >
      {/* Legal attestation panel */}
      <div className="mb-8 rounded-lg border border-gray-200 bg-gray-50 p-4 text-sm text-gray-700">
        <p>
          <strong>Legal notice.</strong> By completing this form, you attest that
          the information you provide is true and accurate. False statements may
          result in fines, imprisonment, or both, under federal law.
        </p>
      </div>

      {/* Section 1.1 — Personal */}
      <h3 className="mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Personal information
      </h3>

      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField label="Last name" htmlFor="i9-lastName" required error={errors.lastName}>
          <input
            id="i9-lastName"
            type="text"
            value={data.lastName}
            onChange={(e) => set('lastName', e.target.value)}
            className={inputClass(!!errors.lastName)}
            autoComplete="family-name"
          />
        </FormField>
        <FormField label="First name" htmlFor="i9-firstName" required error={errors.firstName}>
          <input
            id="i9-firstName"
            type="text"
            value={data.firstName}
            onChange={(e) => set('firstName', e.target.value)}
            className={inputClass(!!errors.firstName)}
            autoComplete="given-name"
          />
        </FormField>
        <FormField label="Middle initial" htmlFor="i9-middleInitial">
          <input
            id="i9-middleInitial"
            type="text"
            maxLength={4}
            value={data.middleInitial}
            onChange={(e) => set('middleInitial', e.target.value)}
            className={inputClass()}
          />
        </FormField>
        <FormField
          label="Other last names used"
          htmlFor="i9-otherLastNamesUsed"
          helper="If applicable. Leave blank if N/A."
        >
          <input
            id="i9-otherLastNamesUsed"
            type="text"
            value={data.otherLastNamesUsed}
            onChange={(e) => set('otherLastNamesUsed', e.target.value)}
            className={inputClass()}
          />
        </FormField>
        <FormField label="Date of birth" htmlFor="i9-dateOfBirth" required error={errors.dateOfBirth}>
          <input
            id="i9-dateOfBirth"
            type="date"
            max={today}
            value={data.dateOfBirth}
            onChange={(e) => set('dateOfBirth', e.target.value)}
            className={inputClass(!!errors.dateOfBirth)}
            autoComplete="bday"
          />
        </FormField>
        <FormField label="Email" htmlFor="i9-email" required error={errors.email}>
          <input
            id="i9-email"
            type="email"
            value={data.email}
            onChange={(e) => set('email', e.target.value)}
            className={inputClass(!!errors.email)}
            autoComplete="email"
          />
        </FormField>
        <FormField
          label="Phone number"
          htmlFor="i9-phoneNumber"
          required
          error={errors.phoneNumber}
        >
          <input
            id="i9-phoneNumber"
            type="tel"
            placeholder="(555) 123-4567"
            value={data.phoneNumber}
            onChange={(e) => set('phoneNumber', e.target.value)}
            className={inputClass(!!errors.phoneNumber)}
            autoComplete="tel"
          />
        </FormField>
      </div>

      {/* Section 1.2 — Address */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Address
      </h3>

      <FormField
        label="Street address"
        htmlFor="i9-addressStreet"
        required
        error={errors.addressStreet}
      >
        <input
          id="i9-addressStreet"
          type="text"
          value={data.addressStreet}
          onChange={(e) => set('addressStreet', e.target.value)}
          className={inputClass(!!errors.addressStreet)}
          autoComplete="street-address"
        />
      </FormField>

      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField
          label="Apt / unit number"
          htmlFor="i9-addressAptNumber"
          helper="If applicable"
        >
          <input
            id="i9-addressAptNumber"
            type="text"
            value={data.addressAptNumber}
            onChange={(e) => set('addressAptNumber', e.target.value)}
            className={inputClass()}
          />
        </FormField>
        <FormField label="City" htmlFor="i9-addressCity" required error={errors.addressCity}>
          <input
            id="i9-addressCity"
            type="text"
            value={data.addressCity}
            onChange={(e) => set('addressCity', e.target.value)}
            className={inputClass(!!errors.addressCity)}
            autoComplete="address-level2"
          />
        </FormField>
        <FormField
          label="State"
          htmlFor="i9-addressState"
          required
          error={errors.addressState}
        >
          <select
            id="i9-addressState"
            value={data.addressState}
            onChange={(e) => set('addressState', e.target.value)}
            className={inputClass(!!errors.addressState)}
            autoComplete="address-level1"
          >
            <option value="">Select state…</option>
            {US_STATES.map((s) => (
              <option key={s.code} value={s.code}>
                {s.name}
              </option>
            ))}
          </select>
        </FormField>
        <FormField
          label="ZIP code"
          htmlFor="i9-addressZipCode"
          required
          error={errors.addressZipCode}
        >
          <input
            id="i9-addressZipCode"
            type="text"
            placeholder="12345 or 12345-6789"
            value={data.addressZipCode}
            onChange={(e) => set('addressZipCode', e.target.value)}
            className={inputClass(!!errors.addressZipCode)}
            autoComplete="postal-code"
          />
        </FormField>
      </div>

      {/* Section 1.3 — Citizenship */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Citizenship / immigration status
      </h3>

      <div
        id="i9-citizenshipStatus"
        className="space-y-2"
        role="radiogroup"
        aria-label="Citizenship status"
      >
        {CITIZENSHIP_OPTIONS.map((opt) => {
          const selected = data.citizenshipStatus === opt.value;
          return (
            <label
              key={opt.value}
              className={
                'flex cursor-pointer items-start gap-3 rounded-lg border p-4 text-sm transition-colors ' +
                (selected
                  ? 'border-accent bg-accent/5'
                  : 'border-gray-300 hover:bg-gray-50')
              }
            >
              <input
                type="radio"
                name="citizenshipStatus"
                value={opt.value}
                checked={selected}
                onChange={() => set('citizenshipStatus', opt.value)}
                className="mt-0.5 h-4 w-4 cursor-pointer accent-accent"
              />
              <span className="text-gray-900">{opt.label}</span>
            </label>
          );
        })}
      </div>
      {errors.citizenshipStatus && (
        <p className="mt-2 text-xs text-red-600">{errors.citizenshipStatus}</p>
      )}

      {/* Conditional fields by citizenship */}
      {(data.citizenshipStatus === 'LAWFUL_PERMANENT_RESIDENT' ||
        data.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK') && (
        <div className="mt-5 rounded-lg border border-gray-200 bg-gray-50 p-4">
          {data.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK' && (
            <FormField
              label="Work authorization expiration date"
              htmlFor="i9-workAuthExpirationDate"
              required
              error={errors.workAuthExpirationDate}
            >
              <input
                id="i9-workAuthExpirationDate"
                type="date"
                min={today}
                value={data.workAuthExpirationDate}
                onChange={(e) =>
                  set('workAuthExpirationDate', e.target.value)
                }
                className={inputClass(!!errors.workAuthExpirationDate)}
              />
            </FormField>
          )}

          {showAlienRegistration && (
            <FormField
              label="Alien Registration / USCIS Number"
              htmlFor="i9-alienRegistrationNumber"
              required={
                data.citizenshipStatus === 'LAWFUL_PERMANENT_RESIDENT'
              }
              error={errors.alienRegistrationNumber}
              helper="Begins with 'A' followed by 9 digits"
            >
              <input
                id="i9-alienRegistrationNumber"
                type="text"
                value={data.alienRegistrationNumber}
                onChange={(e) =>
                  set('alienRegistrationNumber', e.target.value)
                }
                className={inputClass(!!errors.alienRegistrationNumber)}
              />
            </FormField>
          )}

          {data.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK' && (
            <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
              <FormField
                label="Foreign passport number"
                htmlFor="i9-foreignPassportNumber"
              >
                <input
                  id="i9-foreignPassportNumber"
                  type="text"
                  value={data.foreignPassportNumber}
                  onChange={(e) =>
                    set('foreignPassportNumber', e.target.value)
                  }
                  className={inputClass()}
                />
              </FormField>
              <FormField
                label="Country of issuance"
                htmlFor="i9-foreignPassportCountry"
                helper="Only relevant if passport is provided"
              >
                <input
                  id="i9-foreignPassportCountry"
                  type="text"
                  value={data.foreignPassportCountry}
                  onChange={(e) =>
                    set('foreignPassportCountry', e.target.value)
                  }
                  className={inputClass()}
                />
              </FormField>
            </div>
          )}
        </div>
      )}

      {/* Section 1.4 — SSN */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Social Security Number
      </h3>

      <FormField
        label="SSN"
        htmlFor="i9-ssn"
        error={errors.ssn}
        helper="Required only if your employer uses E-Verify. You may leave blank otherwise."
      >
        <input
          id="i9-ssn"
          type="text"
          inputMode="numeric"
          placeholder="XXX-XX-XXXX"
          value={data.ssn}
          onChange={(e) => set('ssn', e.target.value)}
          onBlur={(e) => set('ssn', formatSSN(e.target.value))}
          className={inputClass(!!errors.ssn)}
          autoComplete="off"
          maxLength={11}
        />
      </FormField>

      {/* Section 1.5 — Preparer / Translator */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Preparer / translator
      </h3>

      <label className="flex cursor-pointer items-start gap-3">
        <input
          type="checkbox"
          checked={data.preparerTranslatorUsed}
          onChange={(e) => set('preparerTranslatorUsed', e.target.checked)}
          className="mt-0.5 h-4 w-4 cursor-pointer accent-accent"
        />
        <span className="text-sm text-gray-900">
          Did someone help you prepare or translate this form?
        </span>
      </label>
      {data.preparerTranslatorUsed && (
        <p className="mt-2 text-xs text-gray-500">
          HR will follow up with you to capture preparer/translator details.
        </p>
      )}

      {/* Section 1.6 — Attestation */}
      <div className="mt-10 rounded-lg bg-gray-50 p-6">
        <h3 className="mb-3 text-lg font-semibold text-gray-900">
          Sign Section 1
        </h3>
        <label className="flex cursor-pointer items-start gap-3">
          <input
            id="i9-attested"
            type="checkbox"
            checked={attested}
            onChange={(e) => {
              setAttested(e.target.checked);
              if (errors.attested && e.target.checked) {
                setErrors((prev) => {
                  const next = { ...prev };
                  delete next.attested;
                  return next;
                });
              }
            }}
            className="mt-0.5 h-4 w-4 cursor-pointer accent-accent"
          />
          <span className="text-sm text-gray-900">
            I attest, under penalty of perjury, that the information I have
            provided in Section 1 is true and accurate.
          </span>
        </label>
        {errors.attested && (
          <p className="mt-2 text-xs text-red-600">{errors.attested}</p>
        )}
        <p className="mt-3 text-xs text-gray-500">
          Your full legal name will be recorded as your electronic signature,
          along with the date and time of submission.
        </p>
      </div>

      {topError && (
        <div className="mt-6 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {topError}
        </div>
      )}

      <div className="mt-8 flex flex-wrap items-center justify-end gap-3">
        <button
          type="button"
          onClick={() => void saveDraft()}
          disabled={submitting !== null}
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {submitting === 'draft' ? 'Saving…' : 'Save Draft'}
        </button>
        <button
          type="submit"
          disabled={submitting !== null || !attested}
          className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
        >
          {submitting === 'submit' ? 'Submitting…' : 'Submit Section 1'}
        </button>
      </div>
    </form>
  );
}
