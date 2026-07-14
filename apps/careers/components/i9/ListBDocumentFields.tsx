'use client';

import FormField, { inputClass } from '@/components/ui/FormField';
import { LIST_B_DOCUMENTS } from '@/lib/i9-documents';

export interface ListBDocs {
  listBTitle?: string;
  listBIssuingAuthority?: string;
  listBDocumentNumber?: string;
  listBExpirationDate?: string;
}

interface Props {
  data: ListBDocs;
  onChange: (patch: Partial<ListBDocs>) => void;
  errors?: Partial<Record<keyof ListBDocs, string>>;
}

export default function ListBDocumentFields({ data, onChange, errors }: Props) {
  return (
    <div>
      <h4 className="mb-3 text-sm font-semibold text-gray-900">
        List B Document (Identity)
      </h4>
      <FormField
        label="Document title"
        htmlFor="i9-listBTitle"
        required
        error={errors?.listBTitle}
      >
        <select
          id="i9-listBTitle"
          value={data.listBTitle ?? ''}
          onChange={(e) => onChange({ listBTitle: e.target.value })}
          className={inputClass(!!errors?.listBTitle)}
        >
          <option value="">Select document…</option>
          {LIST_B_DOCUMENTS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </FormField>
      <FormField
        label="Issuing authority"
        htmlFor="i9-listBIssuingAuthority"
        required
        error={errors?.listBIssuingAuthority}
      >
        <input
          id="i9-listBIssuingAuthority"
          type="text"
          placeholder="e.g. California DMV"
          value={data.listBIssuingAuthority ?? ''}
          onChange={(e) =>
            onChange({ listBIssuingAuthority: e.target.value })
          }
          className={inputClass(!!errors?.listBIssuingAuthority)}
        />
      </FormField>
      <FormField
        label="Document number"
        htmlFor="i9-listBDocumentNumber"
        required
        error={errors?.listBDocumentNumber}
      >
        <input
          id="i9-listBDocumentNumber"
          type="text"
          value={data.listBDocumentNumber ?? ''}
          onChange={(e) =>
            onChange({ listBDocumentNumber: e.target.value })
          }
          className={inputClass(!!errors?.listBDocumentNumber)}
        />
      </FormField>
      <FormField
        label="Expiration date"
        htmlFor="i9-listBExpirationDate"
        helper="Leave blank if document doesn't expire"
      >
        <input
          id="i9-listBExpirationDate"
          type="date"
          value={data.listBExpirationDate ?? ''}
          onChange={(e) =>
            onChange({ listBExpirationDate: e.target.value })
          }
          className={inputClass()}
        />
      </FormField>
    </div>
  );
}
