'use client';

import { useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import FormField, { inputClass } from '@/components/ui/FormField';
import ListADocumentFields from './ListADocumentFields';
import ListBDocumentFields from './ListBDocumentFields';
import ListCDocumentFields from './ListCDocumentFields';
import { formatDateOnly, todayDateInput } from '@/lib/format-date';
import type { I9FormResponse, Section2Request } from '@/types';

interface Props {
  form: I9FormResponse;
  onSaved: (updated: I9FormResponse) => void;
}

type DocumentMode = 'LIST_A' | 'LIST_B_C';

interface FormState {
  firstDayOfEmployment: string;
  documentMode: DocumentMode;
  listATitle: string;
  listAIssuingAuthority: string;
  listADocumentNumber: string;
  listAExpirationDate: string;
  listBTitle: string;
  listBIssuingAuthority: string;
  listBDocumentNumber: string;
  listBExpirationDate: string;
  listCTitle: string;
  listCIssuingAuthority: string;
  listCDocumentNumber: string;
  additionalInformation: string;
  employerName: string;
  employerTitle: string;
  businessOrganizationName: string;
  businessAddress: string;
}

const CITIZENSHIP_LABEL: Record<string, string> = {
  US_CITIZEN: 'U.S. citizen',
  NONCITIZEN_NATIONAL: 'Noncitizen national',
  LAWFUL_PERMANENT_RESIDENT: 'Lawful permanent resident',
  ALIEN_AUTHORIZED_TO_WORK: 'Alien authorized to work',
};

function blankToUndef(s: string): string | undefined {
  const t = s.trim();
  return t.length > 0 ? t : undefined;
}

function inferDocumentMode(form: I9FormResponse): DocumentMode {
  if (form.listBTitle || form.listCTitle) return 'LIST_B_C';
  return 'LIST_A';
}

export default function Section2Form({ form, onSaved }: Props) {
  const { user } = useAuth();

  const [data, setData] = useState<FormState>(() => ({
    firstDayOfEmployment: form.firstDayOfEmployment ?? '',
    documentMode: inferDocumentMode(form),
    listATitle: form.listATitle ?? '',
    listAIssuingAuthority: form.listAIssuingAuthority ?? '',
    listADocumentNumber: form.listADocumentNumber ?? '',
    listAExpirationDate: form.listAExpirationDate ?? '',
    listBTitle: form.listBTitle ?? '',
    listBIssuingAuthority: form.listBIssuingAuthority ?? '',
    listBDocumentNumber: form.listBDocumentNumber ?? '',
    listBExpirationDate: form.listBExpirationDate ?? '',
    listCTitle: form.listCTitle ?? '',
    listCIssuingAuthority: form.listCIssuingAuthority ?? '',
    listCDocumentNumber: form.listCDocumentNumber ?? '',
    additionalInformation: form.additionalInformation ?? '',
    employerName: form.employerName ?? user?.fullName ?? '',
    employerTitle: form.employerTitle ?? 'HR Representative',
    businessOrganizationName: form.businessOrganizationName ?? '',
    businessAddress: form.businessAddress ?? '',
  }));

  const [attested, setAttested] = useState(false);
  const [submitting, setSubmitting] = useState<'draft' | 'submit' | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [topError, setTopError] = useState<string | null>(null);

  const today = useMemo(() => todayDateInput(), []);

  function patch(p: Partial<FormState>) {
    setData((prev) => ({ ...prev, ...p }));
    setErrors((prev) => {
      const next = { ...prev };
      for (const key of Object.keys(p)) delete next[key];
      return next;
    });
  }

  function validate(): Record<string, string> {
    const e: Record<string, string> = {};
    if (!data.firstDayOfEmployment) {
      e.firstDayOfEmployment = 'First day of employment is required';
    } else if (new Date(data.firstDayOfEmployment) > new Date(today)) {
      e.firstDayOfEmployment = "Can't be in the future";
    }
    if (data.documentMode === 'LIST_A') {
      if (!data.listATitle) e.listATitle = 'Document title is required';
      if (!data.listAIssuingAuthority.trim())
        e.listAIssuingAuthority = 'Issuing authority is required';
      if (!data.listADocumentNumber.trim())
        e.listADocumentNumber = 'Document number is required';
    } else {
      if (!data.listBTitle) e.listBTitle = 'List B document title is required';
      if (!data.listBIssuingAuthority.trim())
        e.listBIssuingAuthority = 'List B issuing authority is required';
      if (!data.listBDocumentNumber.trim())
        e.listBDocumentNumber = 'List B document number is required';
      if (!data.listCTitle) e.listCTitle = 'List C document title is required';
      if (!data.listCIssuingAuthority.trim())
        e.listCIssuingAuthority = 'List C issuing authority is required';
      if (!data.listCDocumentNumber.trim())
        e.listCDocumentNumber = 'List C document number is required';
    }
    if (!data.employerName.trim())
      e.employerName = 'Employer representative name is required';
    if (!data.employerTitle.trim()) e.employerTitle = 'Title is required';
    if (!data.businessOrganizationName.trim())
      e.businessOrganizationName = 'Business organization name is required';
    if (!data.businessAddress.trim())
      e.businessAddress = 'Business address is required';
    if (!attested) e.attested = 'You must check the attestation box to submit';
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

  function buildPayload(draft: boolean): Section2Request {
    const useA = data.documentMode === 'LIST_A';
    return {
      firstDayOfEmployment: blankToUndef(data.firstDayOfEmployment),
      // Only send the fields for the selected mode; the other list's fields
      // are intentionally undefined so a mode switch clears them on save.
      listATitle: useA ? blankToUndef(data.listATitle) : undefined,
      listAIssuingAuthority: useA
        ? blankToUndef(data.listAIssuingAuthority)
        : undefined,
      listADocumentNumber: useA
        ? blankToUndef(data.listADocumentNumber)
        : undefined,
      listAExpirationDate: useA
        ? blankToUndef(data.listAExpirationDate)
        : undefined,
      listBTitle: !useA ? blankToUndef(data.listBTitle) : undefined,
      listBIssuingAuthority: !useA
        ? blankToUndef(data.listBIssuingAuthority)
        : undefined,
      listBDocumentNumber: !useA
        ? blankToUndef(data.listBDocumentNumber)
        : undefined,
      listBExpirationDate: !useA
        ? blankToUndef(data.listBExpirationDate)
        : undefined,
      listCTitle: !useA ? blankToUndef(data.listCTitle) : undefined,
      listCIssuingAuthority: !useA
        ? blankToUndef(data.listCIssuingAuthority)
        : undefined,
      listCDocumentNumber: !useA
        ? blankToUndef(data.listCDocumentNumber)
        : undefined,
      additionalInformation: blankToUndef(data.additionalInformation),
      employerName: blankToUndef(data.employerName),
      employerTitle: blankToUndef(data.employerTitle),
      businessOrganizationName: blankToUndef(data.businessOrganizationName),
      businessAddress: blankToUndef(data.businessAddress),
      draft,
    };
  }

  async function saveDraft() {
    setTopError(null);
    setSubmitting('draft');
    try {
      const res = await api.post<I9FormResponse>(
        `/api/v1/i9/${form.id}/section2`,
        buildPayload(true)
      );
      onSaved(res.data);
      toast.success('Draft saved');
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't save draft.";
      setTopError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  async function complete() {
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
        `/api/v1/i9/${form.id}/section2`,
        buildPayload(false)
      );
      onSaved(res.data);
      toast.success('I-9 complete ✓');
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't complete Section 2.";
      setTopError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  const citizenshipLabel = form.citizenshipStatus
    ? CITIZENSHIP_LABEL[form.citizenshipStatus] ?? form.citizenshipStatus
    : '—';
  const candidateName =
    [form.firstName, form.lastName].filter(Boolean).join(' ').trim() ||
    form.candidateName ||
    'this candidate';

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void complete();
      }}
      className="max-w-3xl"
    >
      {/* Candidate context */}
      <div className="mb-6 rounded-lg border border-gray-200 bg-gray-50 p-4 text-sm text-gray-800">
        <div>
          <span className="text-gray-500">Verifying documents for:</span>{' '}
          <span className="font-medium text-gray-900">{candidateName}</span>
        </div>
        <div className="mt-1">
          <span className="text-gray-500">
            Citizenship status (from Section 1):
          </span>{' '}
          <span className="font-medium text-gray-900">{citizenshipLabel}</span>
        </div>
        <div className="mt-1">
          <span className="text-gray-500">Date of birth:</span>{' '}
          <span className="font-medium text-gray-900">
            {formatDateOnly(form.dateOfBirth)}
          </span>
        </div>
      </div>

      {/* 2.1 — Employment Information */}
      <h3 className="mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Employment information
      </h3>
      <FormField
        label="First day of employment"
        htmlFor="i9-firstDayOfEmployment"
        required
        error={errors.firstDayOfEmployment}
        helper="The candidate's actual first day of work. Section 2 must be completed within 3 business days of this date."
      >
        <input
          id="i9-firstDayOfEmployment"
          type="date"
          max={today}
          value={data.firstDayOfEmployment}
          onChange={(e) => patch({ firstDayOfEmployment: e.target.value })}
          className={inputClass(!!errors.firstDayOfEmployment)}
        />
      </FormField>

      {/* 2.2 — Documents Examined */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Documents examined
      </h3>
      <p className="mb-4 text-sm text-gray-600">
        Examine the candidate&apos;s identification documents and record one of
        the following combinations:
      </p>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <DocumentModeCard
          active={data.documentMode === 'LIST_A'}
          onClick={() => patch({ documentMode: 'LIST_A' })}
          title="List A"
          body="Single document proving both identity AND employment authorization (e.g. U.S. Passport)"
        />
        <DocumentModeCard
          active={data.documentMode === 'LIST_B_C'}
          onClick={() => patch({ documentMode: 'LIST_B_C' })}
          title="List B + List C"
          body="One identity document AND one employment authorization document (e.g. Driver's License + SSN card)"
        />
      </div>

      <div className="mt-6 rounded-lg border border-gray-200 bg-gray-50 p-4">
        {data.documentMode === 'LIST_A' ? (
          <ListADocumentFields
            data={data}
            onChange={(p) => patch(p)}
            errors={errors as Partial<Record<keyof FormState, string>>}
          />
        ) : (
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <ListBDocumentFields
              data={data}
              onChange={(p) => patch(p)}
              errors={errors as Partial<Record<keyof FormState, string>>}
            />
            <ListCDocumentFields
              data={data}
              onChange={(p) => patch(p)}
              errors={errors as Partial<Record<keyof FormState, string>>}
            />
          </div>
        )}
      </div>

      {/* 2.3 — Additional information */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Additional information
      </h3>
      <FormField
        label="Additional information"
        htmlFor="i9-additionalInformation"
        helper="Use this space for: 'Other' document specifications, special situations (STEM OPT, J-1, etc.), or any context for the audit record."
      >
        <textarea
          id="i9-additionalInformation"
          value={data.additionalInformation}
          onChange={(e) => patch({ additionalInformation: e.target.value })}
          rows={3}
          className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
        />
      </FormField>

      {/* 2.4 — Employer / Hiring Representative */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Employer / hiring representative
      </h3>
      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField
          label="Employer representative full name"
          htmlFor="i9-employerName"
          required
          error={errors.employerName}
        >
          <input
            id="i9-employerName"
            type="text"
            value={data.employerName}
            onChange={(e) => patch({ employerName: e.target.value })}
            className={inputClass(!!errors.employerName)}
          />
        </FormField>
        <FormField
          label="Title"
          htmlFor="i9-employerTitle"
          required
          error={errors.employerTitle}
        >
          <input
            id="i9-employerTitle"
            type="text"
            value={data.employerTitle}
            onChange={(e) => patch({ employerTitle: e.target.value })}
            className={inputClass(!!errors.employerTitle)}
          />
        </FormField>
      </div>
      <FormField
        label="Business or organization name"
        htmlFor="i9-businessOrganizationName"
        required
        error={errors.businessOrganizationName}
        helper="The staffing entity employing this intern (e.g. Stellar USA)"
      >
        <input
          id="i9-businessOrganizationName"
          type="text"
          value={data.businessOrganizationName}
          onChange={(e) =>
            patch({ businessOrganizationName: e.target.value })
          }
          className={inputClass(!!errors.businessOrganizationName)}
        />
      </FormField>
      <FormField
        label="Business address"
        htmlFor="i9-businessAddress"
        required
        error={errors.businessAddress}
        helper="Full business address as filed with USCIS"
      >
        <textarea
          id="i9-businessAddress"
          value={data.businessAddress}
          onChange={(e) => patch({ businessAddress: e.target.value })}
          rows={2}
          className={
            (errors.businessAddress
              ? 'border-red-300 focus:ring-red-500 focus:border-red-500'
              : 'border-gray-300 focus:ring-primary-700 focus:border-primary-700') +
            ' w-full resize-y rounded-md border bg-white p-2.5 text-sm focus:outline-none focus:ring-1'
          }
        />
      </FormField>

      {/* 2.5 — Attestation */}
      <div className="mt-10 rounded-lg bg-gray-50 p-6">
        <h3 className="mb-3 text-lg font-semibold text-gray-900">
          Sign Section 2
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
            I attest, under penalty of perjury, that (1) I have examined the
            documents presented by the above-named individual, (2) the
            document(s) appear to be genuine and to relate to the individual
            named, and (3) to the best of my knowledge the individual is
            authorized to work in the United States.
          </span>
        </label>
        {errors.attested && (
          <p className="mt-2 text-xs text-red-600">{errors.attested}</p>
        )}
        <p className="mt-3 text-xs text-gray-500">
          Your name and the current date and time will be recorded as your
          electronic signature.
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
          {submitting === 'submit' ? 'Submitting…' : 'Complete Section 2'}
        </button>
      </div>
    </form>
  );
}

function DocumentModeCard({
  active,
  onClick,
  title,
  body,
}: {
  active: boolean;
  onClick: () => void;
  title: string;
  body: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'rounded-lg border p-4 text-left transition-colors ' +
        (active
          ? 'border-accent bg-accent/5'
          : 'border-gray-300 bg-white hover:bg-gray-50')
      }
      aria-pressed={active}
    >
      <div className="flex items-start gap-3">
        <span
          className={
            'mt-0.5 inline-flex h-4 w-4 flex-shrink-0 items-center justify-center rounded-full border-2 ' +
            (active ? 'border-accent' : 'border-gray-400')
          }
        >
          {active && <span className="h-2 w-2 rounded-full bg-accent" />}
        </span>
        <div>
          <div className="text-sm font-semibold text-gray-900">{title}</div>
          <div className="mt-1 text-xs text-gray-600">{body}</div>
        </div>
      </div>
    </button>
  );
}
