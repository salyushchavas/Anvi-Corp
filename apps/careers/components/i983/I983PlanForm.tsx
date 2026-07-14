'use client';

import { useState } from 'react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import FormField, { inputClass } from '@/components/ui/FormField';
import type {
  CompensationFrequency,
  DegreeLevel,
  I983PlanResponse,
  UpdateI983Request,
} from '@/types';

interface Props {
  plan: I983PlanResponse;
  onSaved: (updated: I983PlanResponse) => void;
}

interface FormState {
  // Section 1
  studentLastName: string;
  studentFirstName: string;
  studentMiddleName: string;
  studentEmail: string;
  sevisId: string;
  uscisNumber: string;
  degreeAwarded: string;
  degreeLevel: DegreeLevel | '';
  universityName: string;
  universityCipCode: string;
  dateOfDegreeAward: string;
  optStartDate: string;
  optEndDate: string;
  // Section 2
  employerName: string;
  employerEin: string;
  employerWebsite: string;
  employerNaicsCode: string;
  employerNumberOfFullTimeEmployees: string;
  employerOfficialName: string;
  employerOfficialTitle: string;
  employerOfficialEmail: string;
  employerOfficialPhone: string;
  employerAddress: string;
  // Section 3
  jobTitle: string;
  trainingStartDate: string;
  trainingEndDate: string;
  hoursPerWeek: string;
  compensationAmount: string;
  compensationFrequency: CompensationFrequency | '';
  supervisorName: string;
  supervisorTitle: string;
  supervisorEmail: string;
  supervisorPhone: string;
  // Section 4
  trainingProgramDescription: string;
  howTrainingRelatesToDegree: string;
  trainingGoalsAndObjectives: string;
  performanceEvaluationMethod: string;
  reportingRequirements: string;
  skillsKnowledgeLearned: string;
  resourcesEquipmentMaterials: string;
  supervisorCommitments: string;
}

function toState(plan: I983PlanResponse): FormState {
  return {
    studentLastName: plan.studentLastName ?? '',
    studentFirstName: plan.studentFirstName ?? '',
    studentMiddleName: plan.studentMiddleName ?? '',
    studentEmail: plan.studentEmail ?? '',
    sevisId: plan.sevisId ?? '',
    uscisNumber: plan.uscisNumber ?? '',
    degreeAwarded: plan.degreeAwarded ?? '',
    degreeLevel: plan.degreeLevel ?? '',
    universityName: plan.universityName ?? '',
    universityCipCode: plan.universityCipCode ?? '',
    dateOfDegreeAward: plan.dateOfDegreeAward ?? '',
    optStartDate: plan.optStartDate ?? '',
    optEndDate: plan.optEndDate ?? '',
    employerName: plan.employerName ?? '',
    employerEin: plan.employerEin ?? '',
    employerWebsite: plan.employerWebsite ?? '',
    employerNaicsCode: plan.employerNaicsCode ?? '',
    employerNumberOfFullTimeEmployees:
      plan.employerNumberOfFullTimeEmployees != null
        ? String(plan.employerNumberOfFullTimeEmployees)
        : '',
    employerOfficialName: plan.employerOfficialName ?? '',
    employerOfficialTitle: plan.employerOfficialTitle ?? '',
    employerOfficialEmail: plan.employerOfficialEmail ?? '',
    employerOfficialPhone: plan.employerOfficialPhone ?? '',
    employerAddress: plan.employerAddress ?? '',
    jobTitle: plan.jobTitle ?? '',
    trainingStartDate: plan.trainingStartDate ?? '',
    trainingEndDate: plan.trainingEndDate ?? '',
    hoursPerWeek:
      plan.hoursPerWeek != null ? String(plan.hoursPerWeek) : '',
    compensationAmount:
      plan.compensationAmount != null ? String(plan.compensationAmount) : '',
    compensationFrequency: plan.compensationFrequency ?? '',
    supervisorName: plan.supervisorName ?? '',
    supervisorTitle: plan.supervisorTitle ?? '',
    supervisorEmail: plan.supervisorEmail ?? '',
    supervisorPhone: plan.supervisorPhone ?? '',
    trainingProgramDescription: plan.trainingProgramDescription ?? '',
    howTrainingRelatesToDegree: plan.howTrainingRelatesToDegree ?? '',
    trainingGoalsAndObjectives: plan.trainingGoalsAndObjectives ?? '',
    performanceEvaluationMethod: plan.performanceEvaluationMethod ?? '',
    reportingRequirements: plan.reportingRequirements ?? '',
    skillsKnowledgeLearned: plan.skillsKnowledgeLearned ?? '',
    resourcesEquipmentMaterials: plan.resourcesEquipmentMaterials ?? '',
    supervisorCommitments: plan.supervisorCommitments ?? '',
  };
}

function buildPayload(s: FormState): UpdateI983Request {
  const numOrUndef = (v: string): number | undefined => {
    if (v.trim() === '') return undefined;
    const n = Number(v);
    return Number.isFinite(n) ? n : undefined;
  };
  return {
    studentLastName: s.studentLastName || undefined,
    studentFirstName: s.studentFirstName || undefined,
    studentMiddleName: s.studentMiddleName || undefined,
    studentEmail: s.studentEmail || undefined,
    sevisId: s.sevisId || undefined,
    uscisNumber: s.uscisNumber || undefined,
    degreeAwarded: s.degreeAwarded || undefined,
    degreeLevel: s.degreeLevel || undefined,
    universityName: s.universityName || undefined,
    universityCipCode: s.universityCipCode || undefined,
    dateOfDegreeAward: s.dateOfDegreeAward || undefined,
    optStartDate: s.optStartDate || undefined,
    optEndDate: s.optEndDate || undefined,
    employerName: s.employerName || undefined,
    employerEin: s.employerEin || undefined,
    employerAddress: s.employerAddress || undefined,
    employerWebsite: s.employerWebsite || undefined,
    employerNaicsCode: s.employerNaicsCode || undefined,
    employerNumberOfFullTimeEmployees: numOrUndef(
      s.employerNumberOfFullTimeEmployees
    ),
    employerOfficialName: s.employerOfficialName || undefined,
    employerOfficialTitle: s.employerOfficialTitle || undefined,
    employerOfficialEmail: s.employerOfficialEmail || undefined,
    employerOfficialPhone: s.employerOfficialPhone || undefined,
    jobTitle: s.jobTitle || undefined,
    trainingStartDate: s.trainingStartDate || undefined,
    trainingEndDate: s.trainingEndDate || undefined,
    hoursPerWeek: numOrUndef(s.hoursPerWeek),
    compensationAmount: numOrUndef(s.compensationAmount),
    compensationFrequency: s.compensationFrequency || undefined,
    supervisorName: s.supervisorName || undefined,
    supervisorTitle: s.supervisorTitle || undefined,
    supervisorEmail: s.supervisorEmail || undefined,
    supervisorPhone: s.supervisorPhone || undefined,
    trainingProgramDescription: s.trainingProgramDescription || undefined,
    howTrainingRelatesToDegree: s.howTrainingRelatesToDegree || undefined,
    trainingGoalsAndObjectives: s.trainingGoalsAndObjectives || undefined,
    performanceEvaluationMethod: s.performanceEvaluationMethod || undefined,
    reportingRequirements: s.reportingRequirements || undefined,
    skillsKnowledgeLearned: s.skillsKnowledgeLearned || undefined,
    resourcesEquipmentMaterials: s.resourcesEquipmentMaterials || undefined,
    supervisorCommitments: s.supervisorCommitments || undefined,
  };
}

export default function I983PlanForm({ plan, onSaved }: Props) {
  const [data, setData] = useState<FormState>(() => toState(plan));
  const [submitting, setSubmitting] = useState<'draft' | 'save' | null>(null);
  const [topError, setTopError] = useState<string | null>(null);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setData((prev) => ({ ...prev, [key]: value }));
  }

  async function save(mode: 'draft' | 'save') {
    setTopError(null);
    setSubmitting(mode);
    try {
      const res = await api.patch<I983PlanResponse>(
        `/api/v1/i983/${plan.id}`,
        buildPayload(data)
      );
      onSaved(res.data);
      toast.success(mode === 'draft' ? 'Draft saved' : 'Saved');
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? "Couldn't save.";
      setTopError(msg);
      toast.error(msg);
    } finally {
      setSubmitting(null);
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void save('save');
      }}
      className="max-w-3xl"
    >
      {/* Section 1 — Student */}
      <h3 className="mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Section 1 — Student information
      </h3>
      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField label="First name" required htmlFor="i983-studentFirstName">
          <input id="i983-studentFirstName" type="text" value={data.studentFirstName}
            onChange={(e) => set('studentFirstName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Last name" required htmlFor="i983-studentLastName">
          <input id="i983-studentLastName" type="text" value={data.studentLastName}
            onChange={(e) => set('studentLastName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Middle name" htmlFor="i983-studentMiddleName">
          <input id="i983-studentMiddleName" type="text" value={data.studentMiddleName}
            onChange={(e) => set('studentMiddleName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Email" required htmlFor="i983-studentEmail">
          <input id="i983-studentEmail" type="email" value={data.studentEmail}
            onChange={(e) => set('studentEmail', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="SEVIS ID" required htmlFor="i983-sevisId"
          helper="From your Form I-20">
          <input id="i983-sevisId" type="text" placeholder="N1234567890" value={data.sevisId}
            onChange={(e) => set('sevisId', e.target.value)} className={inputClass() + ' font-mono'} />
        </FormField>
        <FormField label="USCIS Number (A-Number)" htmlFor="i983-uscisNumber"
          helper="Alien Registration Number, if applicable">
          <input id="i983-uscisNumber" type="text" placeholder="A123456789" value={data.uscisNumber}
            onChange={(e) => set('uscisNumber', e.target.value)} className={inputClass() + ' font-mono'} />
        </FormField>
        <FormField label="Degree awarded" required htmlFor="i983-degreeAwarded">
          <input id="i983-degreeAwarded" type="text" placeholder="M.S. in Computer Science"
            value={data.degreeAwarded}
            onChange={(e) => set('degreeAwarded', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Degree level" required htmlFor="i983-degreeLevel">
          <select id="i983-degreeLevel" value={data.degreeLevel}
            onChange={(e) => set('degreeLevel', e.target.value as DegreeLevel)} className={inputClass()}>
            <option value="">Select…</option>
            <option value="BACHELORS">Bachelor&apos;s</option>
            <option value="MASTERS">Master&apos;s</option>
            <option value="DOCTORATE">Doctorate</option>
          </select>
        </FormField>
        <FormField label="University" required htmlFor="i983-universityName">
          <input id="i983-universityName" type="text" value={data.universityName}
            onChange={(e) => set('universityName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="CIP code" required htmlFor="i983-universityCipCode"
          helper="Classification of Instructional Programs — must be on DHS STEM-Designated list">
          <input id="i983-universityCipCode" type="text" value={data.universityCipCode}
            onChange={(e) => set('universityCipCode', e.target.value)} className={inputClass() + ' font-mono'} />
        </FormField>
        <FormField label="Date of degree" required htmlFor="i983-dateOfDegreeAward">
          <input id="i983-dateOfDegreeAward" type="date" value={data.dateOfDegreeAward}
            onChange={(e) => set('dateOfDegreeAward', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="OPT EAD start date" required htmlFor="i983-optStartDate">
          <input id="i983-optStartDate" type="date" value={data.optStartDate}
            onChange={(e) => set('optStartDate', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="OPT EAD end date" required htmlFor="i983-optEndDate">
          <input id="i983-optEndDate" type="date" value={data.optEndDate}
            onChange={(e) => set('optEndDate', e.target.value)} className={inputClass()} />
        </FormField>
      </div>

      {/* Section 2 — Employer */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Section 2 — Employer information
      </h3>
      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField label="Employer name" required htmlFor="i983-employerName">
          <input id="i983-employerName" type="text" value={data.employerName}
            onChange={(e) => set('employerName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Employer EIN" required htmlFor="i983-employerEin"
          helper="XX-XXXXXXX">
          <input id="i983-employerEin" type="text" placeholder="12-3456789" value={data.employerEin}
            onChange={(e) => set('employerEin', e.target.value)} className={inputClass() + ' font-mono'} />
        </FormField>
        <FormField label="Website" htmlFor="i983-employerWebsite">
          <input id="i983-employerWebsite" type="url" placeholder="https://example.com"
            value={data.employerWebsite}
            onChange={(e) => set('employerWebsite', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="NAICS code" required htmlFor="i983-employerNaicsCode"
          helper="North American Industry Classification System">
          <input id="i983-employerNaicsCode" type="text" value={data.employerNaicsCode}
            onChange={(e) => set('employerNaicsCode', e.target.value)} className={inputClass() + ' font-mono'} />
        </FormField>
        <FormField label="Full-time employees" required htmlFor="i983-employerNumberOfFullTimeEmployees">
          <input id="i983-employerNumberOfFullTimeEmployees" type="number" min={1}
            value={data.employerNumberOfFullTimeEmployees}
            onChange={(e) => set('employerNumberOfFullTimeEmployees', e.target.value)}
            className={inputClass()} />
        </FormField>
        <div />
        <FormField label="Official representative" required htmlFor="i983-employerOfficialName"
          helper="Person at the employer who signs this form">
          <input id="i983-employerOfficialName" type="text" value={data.employerOfficialName}
            onChange={(e) => set('employerOfficialName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Title" required htmlFor="i983-employerOfficialTitle">
          <input id="i983-employerOfficialTitle" type="text" value={data.employerOfficialTitle}
            onChange={(e) => set('employerOfficialTitle', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Email" required htmlFor="i983-employerOfficialEmail">
          <input id="i983-employerOfficialEmail" type="email" value={data.employerOfficialEmail}
            onChange={(e) => set('employerOfficialEmail', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Phone" required htmlFor="i983-employerOfficialPhone">
          <input id="i983-employerOfficialPhone" type="tel" value={data.employerOfficialPhone}
            onChange={(e) => set('employerOfficialPhone', e.target.value)} className={inputClass()} />
        </FormField>
        <div className="sm:col-span-2">
          <FormField label="Business address" required htmlFor="i983-employerAddress">
            <textarea id="i983-employerAddress" rows={2} value={data.employerAddress}
              onChange={(e) => set('employerAddress', e.target.value)}
              className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700" />
          </FormField>
        </div>
      </div>

      {/* Section 3 — Training Program */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Section 3 — Training program logistics
      </h3>
      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <FormField label="Job title" required htmlFor="i983-jobTitle">
          <input id="i983-jobTitle" type="text" value={data.jobTitle}
            onChange={(e) => set('jobTitle', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Hours per week" required htmlFor="i983-hoursPerWeek"
          helper="Federal rule: must be at least 20 hours per week">
          <input id="i983-hoursPerWeek" type="number" min={20} value={data.hoursPerWeek}
            onChange={(e) => set('hoursPerWeek', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Training start date" required htmlFor="i983-trainingStartDate"
          helper="Must be within the OPT period">
          <input id="i983-trainingStartDate" type="date" value={data.trainingStartDate}
            onChange={(e) => set('trainingStartDate', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Training end date" required htmlFor="i983-trainingEndDate">
          <input id="i983-trainingEndDate" type="date" value={data.trainingEndDate}
            onChange={(e) => set('trainingEndDate', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Compensation amount" required htmlFor="i983-compensationAmount">
          <input id="i983-compensationAmount" type="number" min="0.01" step="0.01"
            value={data.compensationAmount}
            onChange={(e) => set('compensationAmount', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Frequency" required htmlFor="i983-compensationFrequency">
          <select id="i983-compensationFrequency" value={data.compensationFrequency}
            onChange={(e) =>
              set('compensationFrequency', e.target.value as CompensationFrequency)
            }
            className={inputClass()}>
            <option value="">Select…</option>
            <option value="HOURLY">Hourly</option>
            <option value="MONTHLY">Monthly</option>
            <option value="YEARLY">Yearly</option>
          </select>
        </FormField>
        <FormField label="Supervisor name" required htmlFor="i983-supervisorName">
          <input id="i983-supervisorName" type="text" value={data.supervisorName}
            onChange={(e) => set('supervisorName', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Supervisor title" required htmlFor="i983-supervisorTitle">
          <input id="i983-supervisorTitle" type="text" value={data.supervisorTitle}
            onChange={(e) => set('supervisorTitle', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Supervisor email" required htmlFor="i983-supervisorEmail">
          <input id="i983-supervisorEmail" type="email" value={data.supervisorEmail}
            onChange={(e) => set('supervisorEmail', e.target.value)} className={inputClass()} />
        </FormField>
        <FormField label="Supervisor phone" required htmlFor="i983-supervisorPhone">
          <input id="i983-supervisorPhone" type="tel" value={data.supervisorPhone}
            onChange={(e) => set('supervisorPhone', e.target.value)} className={inputClass()} />
        </FormField>
      </div>

      {/* Section 4 — Narrative */}
      <h3 className="mt-10 mb-4 border-b border-gray-200 pb-2 text-lg font-semibold text-gray-900">
        Section 4 — Training program narrative
      </h3>

      <Narrative label="Training program description" required rows={5}
        htmlFor="i983-trainingProgramDescription"
        helper="Detailed description of the role, responsibilities, and day-to-day activities."
        value={data.trainingProgramDescription}
        onChange={(v) => set('trainingProgramDescription', v)} />
      <Narrative label="How the training relates to the STEM degree" required rows={4}
        htmlFor="i983-howTrainingRelatesToDegree"
        helper="Explicitly connect tasks to skills/knowledge from the student's degree program."
        value={data.howTrainingRelatesToDegree}
        onChange={(v) => set('howTrainingRelatesToDegree', v)} />
      <Narrative label="Goals and objectives" required rows={4}
        htmlFor="i983-trainingGoalsAndObjectives"
        helper="What the student will achieve by the end of the training period."
        value={data.trainingGoalsAndObjectives}
        onChange={(v) => set('trainingGoalsAndObjectives', v)} />
      <Narrative label="How the supervisor will evaluate performance" required rows={4}
        htmlFor="i983-performanceEvaluationMethod"
        helper="Specific methods, frequency, and metrics."
        value={data.performanceEvaluationMethod}
        onChange={(v) => set('performanceEvaluationMethod', v)} />
      <Narrative label="Reporting requirements" rows={3}
        htmlFor="i983-reportingRequirements"
        helper="Standard SEVP reporting (12-month + final evaluation) plus any additional."
        value={data.reportingRequirements}
        onChange={(v) => set('reportingRequirements', v)} />
      <Narrative label="Skills and knowledge the student will gain" rows={4}
        htmlFor="i983-skillsKnowledgeLearned"
        value={data.skillsKnowledgeLearned}
        onChange={(v) => set('skillsKnowledgeLearned', v)} />
      <Narrative label="Resources, equipment, and materials provided" rows={3}
        htmlFor="i983-resourcesEquipmentMaterials"
        value={data.resourcesEquipmentMaterials}
        onChange={(v) => set('resourcesEquipmentMaterials', v)} />
      <Narrative label="Supervisor's commitments to the training" rows={4}
        htmlFor="i983-supervisorCommitments"
        helper="Time commitment, mentorship, support resources, etc."
        value={data.supervisorCommitments}
        onChange={(v) => set('supervisorCommitments', v)} />

      {topError && (
        <div className="mt-6 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {topError}
        </div>
      )}

      <div className="mt-10 flex flex-wrap items-center justify-end gap-3">
        <button
          type="button"
          onClick={() => void save('draft')}
          disabled={submitting !== null}
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {submitting === 'draft' ? 'Saving…' : 'Save Draft'}
        </button>
        <button
          type="submit"
          disabled={submitting !== null}
          className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
        >
          {submitting === 'save' ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  );
}

function Narrative({
  label,
  required,
  rows,
  htmlFor,
  helper,
  value,
  onChange,
}: {
  label: string;
  required?: boolean;
  rows: number;
  htmlFor: string;
  helper?: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <FormField label={label} required={required} htmlFor={htmlFor} helper={helper}>
      <textarea
        id={htmlFor}
        rows={rows}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
      />
    </FormField>
  );
}
