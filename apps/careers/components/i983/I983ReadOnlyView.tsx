import { formatDateOnly } from '@/lib/format-date';
import type { DegreeLevel, I983PlanResponse } from '@/types';

interface Props {
  plan: I983PlanResponse;
}

const DEGREE_LABEL: Record<DegreeLevel, string> = {
  HIGH_SCHOOL: 'High School',
  ASSOCIATE: 'Associate',
  DIPLOMA: 'Diploma',
  BACHELORS: "Bachelor's",
  MASTERS: "Master's",
  MBA: 'MBA',
  DOCTORATE: 'Doctorate',
  OTHER: 'Other',
};

const FREQ_LABEL: Record<string, string> = {
  HOURLY: '/ hour',
  MONTHLY: '/ month',
  YEARLY: '/ year',
};

function formatCompensation(plan: I983PlanResponse): string | undefined {
  if (plan.compensationAmount == null) return undefined;
  const n =
    typeof plan.compensationAmount === 'string'
      ? Number(plan.compensationAmount)
      : plan.compensationAmount;
  if (!Number.isFinite(n)) return String(plan.compensationAmount);
  let formatted: string;
  try {
    formatted = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: plan.compensationCurrency ?? 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(n);
  } catch {
    formatted = n.toFixed(2) + ' ' + (plan.compensationCurrency ?? 'USD');
  }
  const suffix = plan.compensationFrequency
    ? ' ' + (FREQ_LABEL[plan.compensationFrequency] ?? '')
    : '';
  return formatted + suffix;
}

export default function I983ReadOnlyView({ plan }: Props) {
  return (
    <div className="max-w-3xl">
      {/* Section 1 — Student */}
      <Section title="Section 1 — Student information">
        <Pair label="First name" value={plan.studentFirstName} />
        <Pair label="Last name" value={plan.studentLastName} />
        <Pair label="Middle name" value={plan.studentMiddleName} />
        <Pair label="Email" value={plan.studentEmail} />
        <Pair label="SEVIS ID" value={plan.sevisId} mono />
        <Pair label="USCIS Number (A-Number)" value={plan.uscisNumber} mono />
        <Pair label="Degree awarded" value={plan.degreeAwarded} />
        <Pair
          label="Degree level"
          value={plan.degreeLevel ? DEGREE_LABEL[plan.degreeLevel] : undefined}
        />
        <Pair label="University" value={plan.universityName} />
        <Pair label="CIP code" value={plan.universityCipCode} mono />
        <Pair label="Date of degree" value={formatDateOnly(plan.dateOfDegreeAward)} />
        <Pair label="OPT EAD start" value={formatDateOnly(plan.optStartDate)} />
        <Pair label="OPT EAD end" value={formatDateOnly(plan.optEndDate)} />
      </Section>

      {/* Section 2 — Employer */}
      <Section title="Section 2 — Employer information">
        <Pair label="Employer name" value={plan.employerName} />
        <Pair label="Employer EIN" value={plan.employerEin} mono />
        <Pair label="Website" value={plan.employerWebsite} />
        <Pair label="NAICS code" value={plan.employerNaicsCode} mono />
        <Pair
          label="Full-time employees"
          value={
            plan.employerNumberOfFullTimeEmployees != null
              ? String(plan.employerNumberOfFullTimeEmployees)
              : undefined
          }
        />
        <Pair label="Official representative" value={plan.employerOfficialName} />
        <Pair label="Title" value={plan.employerOfficialTitle} />
        <Pair label="Email" value={plan.employerOfficialEmail} />
        <Pair label="Phone" value={plan.employerOfficialPhone} />
        <Pair
          label="Address"
          value={plan.employerAddress}
          multiline
          fullWidth
        />
      </Section>

      {/* Section 3 — Training Program */}
      <Section title="Section 3 — Training program logistics">
        <Pair label="Job title" value={plan.jobTitle} />
        <Pair label="Hours per week" value={
          plan.hoursPerWeek != null ? String(plan.hoursPerWeek) : undefined
        } />
        <Pair
          label="Training start"
          value={formatDateOnly(plan.trainingStartDate)}
        />
        <Pair
          label="Training end"
          value={formatDateOnly(plan.trainingEndDate)}
        />
        <Pair label="Compensation" value={formatCompensation(plan)} />
        <Pair label="Supervisor name" value={plan.supervisorName} />
        <Pair label="Supervisor title" value={plan.supervisorTitle} />
        <Pair label="Supervisor email" value={plan.supervisorEmail} />
        <Pair label="Supervisor phone" value={plan.supervisorPhone} />
      </Section>

      {/* Section 4 — Narrative */}
      <Section title="Section 4 — Training program narrative">
        <NarrativeBlock
          label="Training program description"
          value={plan.trainingProgramDescription}
        />
        <NarrativeBlock
          label="How training relates to the STEM degree"
          value={plan.howTrainingRelatesToDegree}
        />
        <NarrativeBlock
          label="Goals and objectives"
          value={plan.trainingGoalsAndObjectives}
        />
        <NarrativeBlock
          label="Performance evaluation method"
          value={plan.performanceEvaluationMethod}
        />
        <NarrativeBlock
          label="Reporting requirements"
          value={plan.reportingRequirements}
        />
        <NarrativeBlock
          label="Skills and knowledge to be gained"
          value={plan.skillsKnowledgeLearned}
        />
        <NarrativeBlock
          label="Resources, equipment, and materials"
          value={plan.resourcesEquipmentMaterials}
        />
        <NarrativeBlock
          label="Supervisor's commitments"
          value={plan.supervisorCommitments}
        />
      </Section>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mb-8 rounded-lg border border-gray-200 bg-white p-6">
      <h3 className="mb-4 text-base font-semibold text-gray-900">{title}</h3>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
        {children}
      </dl>
    </div>
  );
}

function Pair({
  label,
  value,
  mono,
  multiline,
  fullWidth,
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
  multiline?: boolean;
  fullWidth?: boolean;
}) {
  return (
    <div className={fullWidth ? 'sm:col-span-2' : ''}>
      <dt className="text-xs uppercase tracking-wide text-gray-500">{label}</dt>
      <dd
        className={
          'mt-1 text-sm font-medium text-gray-900 ' +
          (mono ? 'font-mono ' : '') +
          (multiline ? 'whitespace-pre-line' : '')
        }
      >
        {value && String(value).trim().length > 0 ? value : '—'}
      </dd>
    </div>
  );
}

function NarrativeBlock({
  label,
  value,
}: {
  label: string;
  value?: string | null;
}) {
  return (
    <div className="sm:col-span-2">
      <dt className="text-xs uppercase tracking-wide text-gray-500">{label}</dt>
      <dd className="mt-1 whitespace-pre-line text-sm text-gray-800">
        {value && value.trim().length > 0 ? (
          value
        ) : (
          <span className="text-gray-400">—</span>
        )}
      </dd>
    </div>
  );
}
