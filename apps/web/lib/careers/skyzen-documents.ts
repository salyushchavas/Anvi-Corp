// ERM Phase 8.2 — mirror of com.skyzen.careers.erm.documents.SkyzenDocument.
// Keep in sync manually with the backend enum: same keys, same order,
// same sensitivities. The 13 blank PDFs live as static files in
// `frontend/public/document-templates/`; the URL each entry exposes is
// served as a cacheable static asset by Next.js.
//
// Filenames preserve the original casing and spacing of the PDFs in
// the repo. `publicUrl` is URL-encoded (encodeURIComponent) so spaces
// and special chars survive the round-trip to the browser. Linux
// filesystems on Vercel are case-sensitive — do not change casing
// without renaming the file on disk.

export type SkyzenDocumentKey =
  | 'W4_2026'
  | 'W9_FW9'
  | 'I9_FORM_2026'
  | 'I9_AUTHORIZED_AGENT_2026'
  | 'I983_2029'
  | 'EMPLOYEE_DATA_SHEET'
  | 'EMPLOYEE_HANDBOOK'
  | 'H1_OFFER_LETTER'
  | 'OFFER_LETTER_SOFTWARE_DEV'
  | 'MSA_TEMPLATE'
  | 'PO_TEMPLATE'
  | 'LEAVE_OF_ABSENCE_FORM'
  | 'WEEKLY_STATUS_REPORT_INSTRUCTIONS'
  // Phase E onboarding — upload-only identity / education docs.
  | 'EDU_PROVISIONAL_CERTIFICATE'
  | 'EDU_TRANSCRIPT'
  | 'PASSPORT_FRONT'
  | 'PASSPORT_BACK'
  | 'PASSPORT_VISA_STAMP'
  | 'DRIVERS_LICENSE_FRONT'
  | 'DRIVERS_LICENSE_BACK'
  | 'STATE_ID_CARD'
  | 'SSN_CARD'
  | 'I20_FORM'
  | 'I9_TRAVEL_HISTORY';

export type SkyzenDocumentCategory =
  | 'TAX'
  | 'IMMIGRATION'
  | 'EMPLOYMENT'
  | 'LEGAL'
  | 'INFORMATIONAL';

export type SkyzenDocumentSensitivity =
  | 'GENERAL'
  | 'FINANCIAL'
  | 'GOVERNMENT_ID';

export type SkyzenDocumentSpec = {
  key: SkyzenDocumentKey;
  title: string;
  category: SkyzenDocumentCategory;
  sensitivity: SkyzenDocumentSensitivity;
  /** Null for upload-only docs (scan/photo of an existing item — passport,
   *  license, transcripts, etc.) that have no fill-in template. */
  filename: string | null;
  description: string;
  /** Null when the doc has no fill-in template (mirrors the backend
   *  SkyzenDocument.publicUrl() null contract). */
  publicUrl: string | null;
  /** Hidden from the AssignPacketModal but still a valid value on the
   *  document_tasks.document_key CHECK constraint so historical packets
   *  keep rendering. Removing a key from the enum would break existing
   *  rows at the next boot-time CHECK sync. */
  deprecated?: boolean;
};

function publicUrlFor(filename: string | null): string | null {
  if (!filename) return null;
  return `/document-templates/${encodeURIComponent(filename)}`;
}

type SkyzenDocumentSpecInput = Omit<SkyzenDocumentSpec, 'publicUrl'>;

const SPECS: readonly SkyzenDocumentSpecInput[] = [
  { key: 'W4_2026', title: 'W-4 2026', category: 'TAX',
    sensitivity: 'FINANCIAL', filename: 'W4 2026.pdf',
    description: 'IRS W-4 employee withholding certificate, 2026 version.' },
  { key: 'W9_FW9', title: 'W-9 (FW9)', category: 'TAX',
    sensitivity: 'FINANCIAL', filename: 'fw9.pdf',
    description: 'IRS W-9 for contractor / 1099 tax information.',
    deprecated: true },
  { key: 'I9_FORM_2026', title: 'I-9 Form 2026', category: 'IMMIGRATION',
    sensitivity: 'GOVERNMENT_ID', filename: 'I-9 form_26.pdf',
    description: 'DHS I-9 employment eligibility, 2026 version.' },
  { key: 'I9_AUTHORIZED_AGENT_2026', title: 'I-9 Authorized Agent 2026',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID',
    filename: 'EM_i-9_authorized_agent 2026.pdf',
    description: 'I-9 with authorized agent procedures.' },
  { key: 'I983_2029', title: 'I-983 (2029)', category: 'IMMIGRATION',
    sensitivity: 'GOVERNMENT_ID', filename: 'i983_2029.pdf',
    description: 'DHS I-983 STEM OPT training plan.' },
  { key: 'EMPLOYEE_DATA_SHEET', title: 'Employee Data Sheet',
    category: 'EMPLOYMENT', sensitivity: 'GENERAL',
    filename: 'EM_Employee Data Sheet.pdf',
    description: 'Employee profile and HR details form.' },
  { key: 'EMPLOYEE_HANDBOOK', title: 'Employee Handbook',
    category: 'INFORMATIONAL', sensitivity: 'GENERAL',
    filename: 'EM_Employee Handbook.pdf',
    description: 'Company handbook with policies.' },
  { key: 'H1_OFFER_LETTER', title: 'H1 Offer Letter', category: 'LEGAL',
    sensitivity: 'GENERAL', filename: 'EM_H1 offer letter.pdf',
    description: 'Offer letter template for H1B candidates.' },
  { key: 'OFFER_LETTER_SOFTWARE_DEV',
    title: 'Offer Letter Software Developer', category: 'LEGAL',
    sensitivity: 'GENERAL',
    filename: 'EM_Offer letter_Software Developer.pdf',
    description: 'Offer letter template for software developer roles.' },
  { key: 'MSA_TEMPLATE', title: 'MSA Template', category: 'LEGAL',
    sensitivity: 'GENERAL', filename: 'EM_MSA_TEMPLATE.PDF',
    description: 'Master Service Agreement template.',
    deprecated: true },
  { key: 'PO_TEMPLATE', title: 'PO Template', category: 'INFORMATIONAL',
    sensitivity: 'GENERAL', filename: 'EM_PO Template.pdf',
    description: 'Purchase Order template.',
    deprecated: true },
  { key: 'LEAVE_OF_ABSENCE_FORM', title: 'Leave of Absence Form',
    category: 'EMPLOYMENT', sensitivity: 'GENERAL',
    filename: 'EM_LEAVE OF ABSENCE FORM.PDF',
    description: 'Time-off / leave request form.' },
  { key: 'WEEKLY_STATUS_REPORT_INSTRUCTIONS',
    title: 'Weekly Status Report Instructions',
    category: 'INFORMATIONAL', sensitivity: 'GENERAL',
    filename: 'EM_Instructions for Weekly Status Reports and timecard entry.pdf',
    description: 'Instructions for the weekly status report cadence.' },

  // Phase E onboarding — upload-only identity / education docs. No
  // fill-in template; the intern uploads a scan/photo of an existing
  // document. Multi-part docs (passport, license, education) are
  // modeled as one entry per part with " — " in the title so the
  // intern + ERM read them as a group on the flat task list.
  { key: 'EDU_PROVISIONAL_CERTIFICATE',
    title: 'Education — Provisional Certificate',
    category: 'EMPLOYMENT', sensitivity: 'GENERAL', filename: null,
    description: "Scanned copy of the provisional/degree certificate for your "
      + "most recent degree (Master's or Bachelor's)." },
  { key: 'EDU_TRANSCRIPT', title: 'Education — Transcript',
    category: 'EMPLOYMENT', sensitivity: 'GENERAL', filename: null,
    description: 'Scanned copy of the academic transcript for your most recent degree.' },
  { key: 'PASSPORT_FRONT', title: 'Passport — Front Page',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of the photo / bio page of your passport.' },
  { key: 'PASSPORT_BACK', title: 'Passport — Back Page',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of the back page of your passport '
      + '(address / signature page).' },
  { key: 'PASSPORT_VISA_STAMP', title: 'Passport — Visa Stamp Page',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of the visa stamp page inside your passport. '
      + 'Skip if you are a US citizen / permanent resident.' },
  { key: 'DRIVERS_LICENSE_FRONT', title: "Driver's License — Front",
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: "Scan or clear photo of the front of your US driver's license." },
  { key: 'DRIVERS_LICENSE_BACK', title: "Driver's License — Back",
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: "Scan or clear photo of the back of your US driver's license." },
  { key: 'STATE_ID_CARD', title: 'State ID Card',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of your state-issued ID card.' },
  { key: 'SSN_CARD', title: 'SSN Card',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of your Social Security card.' },
  { key: 'I20_FORM', title: 'I-20 (Student Visa)',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scan or clear photo of your most recent I-20 form '
      + '(SEVIS Form I-20, Certificate of Eligibility for Nonimmigrant '
      + 'Student Status). F-1 students only.' },
  { key: 'I9_TRAVEL_HISTORY', title: 'Travel History',
    category: 'IMMIGRATION', sensitivity: 'GOVERNMENT_ID', filename: null,
    description: 'Scanned summary of your US entry/exit history for the I-9. '
      + 'Use the I-94 travel history PDF from i94.cbp.dhs.gov.' },
];

export const SKYZEN_DOCUMENTS: readonly SkyzenDocumentSpec[] = SPECS.map((s) => ({
  ...s,
  publicUrl: publicUrlFor(s.filename),
}));

export const SKYZEN_DOCUMENT_BY_KEY: Record<SkyzenDocumentKey, SkyzenDocumentSpec> =
  Object.fromEntries(SKYZEN_DOCUMENTS.map((d) => [d.key, d])) as Record<
    SkyzenDocumentKey, SkyzenDocumentSpec
  >;

export const SKYZEN_DOCUMENT_CATEGORIES: readonly SkyzenDocumentCategory[] = [
  'TAX', 'IMMIGRATION', 'EMPLOYMENT', 'LEGAL', 'INFORMATIONAL',
];

export const CATEGORY_BADGE: Record<SkyzenDocumentCategory, string> = {
  TAX: 'bg-amber-100 text-amber-800',
  IMMIGRATION: 'bg-violet-100 text-violet-800',
  EMPLOYMENT: 'bg-sky-100 text-sky-800',
  LEGAL: 'bg-rose-100 text-rose-800',
  INFORMATIONAL: 'bg-slate-100 text-slate-700',
};

export const SENSITIVITY_BADGE: Record<SkyzenDocumentSensitivity, string> = {
  GENERAL: 'bg-slate-100 text-slate-700',
  FINANCIAL: 'bg-amber-100 text-amber-800',
  GOVERNMENT_ID: 'bg-rose-100 text-rose-800',
};

export const SENSITIVITY_LABEL: Record<SkyzenDocumentSensitivity, string> = {
  GENERAL: 'General',
  FINANCIAL: 'Financial',
  GOVERNMENT_ID: 'Government ID',
};
