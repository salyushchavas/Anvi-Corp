import { formatDateOnly } from '@/lib/format-date';
import type { I9FormResponse } from '@/types';

interface Props {
  form: I9FormResponse;
}

function maskDocNumber(n?: string): string {
  if (!n) return '—';
  if (n.length <= 4) return '···· ' + n;
  return '···· ' + n.slice(-4);
}

interface DocItem {
  title?: string;
  issuingAuthority?: string;
  documentNumber?: string;
  expirationDate?: string;
}

function isPresent(d: DocItem): boolean {
  return Boolean(d.title || d.documentNumber || d.issuingAuthority);
}

export default function Section2ReadOnlyView({ form }: Props) {
  const listA: DocItem = {
    title: form.listATitle,
    issuingAuthority: form.listAIssuingAuthority,
    documentNumber: form.listADocumentNumber,
    expirationDate: form.listAExpirationDate,
  };
  const listB: DocItem = {
    title: form.listBTitle,
    issuingAuthority: form.listBIssuingAuthority,
    documentNumber: form.listBDocumentNumber,
    expirationDate: form.listBExpirationDate,
  };
  const listC: DocItem = {
    title: form.listCTitle,
    issuingAuthority: form.listCIssuingAuthority,
    documentNumber: form.listCDocumentNumber,
  };

  const useListA = isPresent(listA);

  return (
    <div className="mt-6 max-w-3xl">
      <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-base font-semibold text-gray-900">
          Employment & verification
        </h3>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
          <Pair
            label="First day of employment"
            value={formatDateOnly(form.firstDayOfEmployment)}
          />
          <Pair
            label="Verified on"
            value={
              form.section2SignedAt
                ? formatDateOnly(form.section2SignedAt)
                : undefined
            }
          />
          <Pair label="Verified by" value={form.section2SignedByName} />
          <Pair label="Employer rep" value={form.employerName} />
          <Pair label="Title" value={form.employerTitle} />
          <Pair label="Organization" value={form.businessOrganizationName} />
        </dl>
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <h3 className="mb-4 text-base font-semibold text-gray-900">
          Documents recorded
        </h3>
        {useListA ? (
          <DocBlock label="List A" doc={listA} />
        ) : (
          <>
            {isPresent(listB) && <DocBlock label="List B" doc={listB} />}
            {isPresent(listC) && (
              <div className="mt-4">
                <DocBlock label="List C" doc={listC} />
              </div>
            )}
            {!isPresent(listB) && !isPresent(listC) && (
              <p className="text-sm text-gray-500">No documents recorded.</p>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function DocBlock({ label, doc }: { label: string; doc: DocItem }) {
  return (
    <div>
      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
        {label}
      </div>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
        <Pair label="Document" value={doc.title} />
        <Pair label="Issuing authority" value={doc.issuingAuthority} />
        <Pair
          label="Document number"
          value={maskDocNumber(doc.documentNumber)}
        />
        {doc.expirationDate && (
          <Pair label="Expiration" value={formatDateOnly(doc.expirationDate)} />
        )}
      </dl>
    </div>
  );
}

function Pair({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-gray-500">{label}</dt>
      <dd className="mt-1 text-sm font-medium text-gray-900">
        {value && value.trim().length > 0 ? value : '—'}
      </dd>
    </div>
  );
}
