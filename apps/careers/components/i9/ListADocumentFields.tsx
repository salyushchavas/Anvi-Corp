'use client';

import FormField, { inputClass } from '@/components/ui/FormField';
import { LIST_A_DOCUMENTS } from '@/lib/i9-documents';

export interface ListADocs {
  listATitle?: string;
  listAIssuingAuthority?: string;
  listADocumentNumber?: string;
  listAExpirationDate?: string;
}

interface Props {
  data: ListADocs;
  onChange: (patch: Partial<ListADocs>) => void;
  errors?: Partial<Record<keyof ListADocs, string>>;
}

export default function ListADocumentFields({ data, onChange, errors }: Props) {
  return (
    <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
      <FormField
        label="Document title"
        htmlFor="i9-listATitle"
        required
        error={errors?.listATitle}
      >
        <select
          id="i9-listATitle"
          value={data.listATitle ?? ''}
          onChange={(e) => onChange({ listATitle: e.target.value })}
          className={inputClass(!!errors?.listATitle)}
        >
          <option value="">Select document…</option>
          {LIST_A_DOCUMENTS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </FormField>
      <FormField
        label="Issuing authority"
        htmlFor="i9-listAIssuingAuthority"
        required
        error={errors?.listAIssuingAuthority}
      >
        <input
          id="i9-listAIssuingAuthority"
          type="text"
          placeholder="e.g. U.S. Department of State"
          value={data.listAIssuingAuthority ?? ''}
          onChange={(e) =>
            onChange({ listAIssuingAuthority: e.target.value })
          }
          className={inputClass(!!errors?.listAIssuingAuthority)}
        />
      </FormField>
      <FormField
        label="Document number"
        htmlFor="i9-listADocumentNumber"
        required
        error={errors?.listADocumentNumber}
      >
        <input
          id="i9-listADocumentNumber"
          type="text"
          placeholder="e.g. C12345678"
          value={data.listADocumentNumber ?? ''}
          onChange={(e) =>
            onChange({ listADocumentNumber: e.target.value })
          }
          className={inputClass(!!errors?.listADocumentNumber)}
        />
      </FormField>
      <FormField
        label="Expiration date"
        htmlFor="i9-listAExpirationDate"
        helper="Leave blank if document doesn't expire"
      >
        <input
          id="i9-listAExpirationDate"
          type="date"
          value={data.listAExpirationDate ?? ''}
          onChange={(e) =>
            onChange({ listAExpirationDate: e.target.value })
          }
          className={inputClass()}
        />
      </FormField>
    </div>
  );
}
