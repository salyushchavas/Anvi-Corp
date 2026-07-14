import { US_STATES } from '@/lib/us-states';
import { formatDateOnly, formatFull } from '@/lib/format-date';
import type { CitizenshipStatus, I9FormResponse } from '@/types';

interface Props {
  form: I9FormResponse;
}

const CITIZENSHIP_LABEL: Record<CitizenshipStatus, string> = {
  US_CITIZEN: 'U.S. citizen',
  NONCITIZEN_NATIONAL: 'Noncitizen national of the United States',
  LAWFUL_PERMANENT_RESIDENT: 'Lawful permanent resident',
  ALIEN_AUTHORIZED_TO_WORK: 'Alien authorized to work',
};

function stateName(code?: string): string | undefined {
  if (!code) return undefined;
  return US_STATES.find((s) => s.code === code)?.name ?? code;
}

function fullName(form: I9FormResponse): string {
  const parts = [form.firstName, form.middleInitial, form.lastName].filter(
    Boolean
  );
  return parts.length > 0 ? parts.join(' ') : '—';
}

function fullAddress(form: I9FormResponse): string {
  const line1 = [form.addressStreet, form.addressAptNumber]
    .filter(Boolean)
    .join(', ');
  const line2 = [form.addressCity, stateName(form.addressState), form.addressZipCode]
    .filter(Boolean)
    .join(', ');
  return [line1, line2].filter(Boolean).join('\n');
}

function maskSSN(ssn?: string): string {
  if (!ssn) return '—';
  return '···-··-' + ssn.slice(-4);
}

export default function Section1ReadOnlyView({ form }: Props) {
  return (
    <div className="max-w-3xl">
      {/* Personal */}
      <Card title="Personal information">
        <Pair label="Full legal name" value={fullName(form)} />
        <Pair label="Other names used" value={form.otherLastNamesUsed} />
        <Pair label="Date of birth" value={formatDateOnly(form.dateOfBirth)} />
        <Pair label="Email" value={form.email} />
        <Pair label="Phone" value={form.phoneNumber} />
        <Pair label="SSN" value={maskSSN(form.ssn)} />
      </Card>

      {/* Address */}
      <Card title="Address">
        <Pair
          label="Street"
          value={fullAddress(form)}
          multiline
        />
      </Card>

      {/* Citizenship */}
      <Card title="Citizenship / immigration status">
        <Pair
          label="Status"
          value={
            form.citizenshipStatus
              ? CITIZENSHIP_LABEL[form.citizenshipStatus]
              : undefined
          }
        />
        {(form.citizenshipStatus === 'LAWFUL_PERMANENT_RESIDENT' ||
          form.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK') && (
          <Pair
            label="Alien Registration / USCIS #"
            value={form.alienRegistrationNumber}
          />
        )}
        {form.citizenshipStatus === 'ALIEN_AUTHORIZED_TO_WORK' && (
          <>
            <Pair
              label="Work authorization expires"
              value={formatDateOnly(form.workAuthExpirationDate)}
            />
            <Pair
              label="Foreign passport"
              value={form.foreignPassportNumber}
            />
            <Pair
              label="Country of issuance"
              value={form.foreignPassportCountry}
            />
          </>
        )}
        <Pair
          label="Preparer / translator used"
          value={form.preparerTranslatorUsed ? 'Yes' : 'No'}
        />
      </Card>

      {form.section1SignedAt && (
        <p className="mt-2 text-sm italic text-gray-500">
          Section 1 signed by{' '}
          <span className="font-medium">
            {form.section1SignedByName ?? fullName(form)}
          </span>{' '}
          on {formatFull(form.section1SignedAt)}
        </p>
      )}
    </div>
  );
}

function Card({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
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
  multiline,
}: {
  label: string;
  value?: string | null;
  multiline?: boolean;
}) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-gray-500">{label}</dt>
      <dd
        className={
          'mt-1 text-sm font-medium text-gray-900 ' +
          (multiline ? 'whitespace-pre-line' : '')
        }
      >
        {value && value.trim().length > 0 ? value : '—'}
      </dd>
    </div>
  );
}
