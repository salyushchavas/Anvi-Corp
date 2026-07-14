'use client';

import FormField, { inputClass } from '@/components/ui/FormField';
import { LIST_C_DOCUMENTS } from '@/lib/i9-documents';

export interface ListCDocs {
  listCTitle?: string;
  listCIssuingAuthority?: string;
  listCDocumentNumber?: string;
}

interface Props {
  data: ListCDocs;
  onChange: (patch: Partial<ListCDocs>) => void;
  errors?: Partial<Record<keyof ListCDocs, string>>;
}

export default function ListCDocumentFields({ data, onChange, errors }: Props) {
  return (
    <div>
      <h4 className="mb-3 text-sm font-semibold text-gray-900">
        List C Document (Employment Authorization)
      </h4>
      <FormField
        label="Document title"
        htmlFor="i9-listCTitle"
        required
        error={errors?.listCTitle}
      >
        <select
          id="i9-listCTitle"
          value={data.listCTitle ?? ''}
          onChange={(e) => onChange({ listCTitle: e.target.value })}
          className={inputClass(!!errors?.listCTitle)}
        >
          <option value="">Select document…</option>
          {LIST_C_DOCUMENTS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </FormField>
      <FormField
        label="Issuing authority"
        htmlFor="i9-listCIssuingAuthority"
        required
        error={errors?.listCIssuingAuthority}
      >
        <input
          id="i9-listCIssuingAuthority"
          type="text"
          placeholder="e.g. Social Security Administration"
          value={data.listCIssuingAuthority ?? ''}
          onChange={(e) =>
            onChange({ listCIssuingAuthority: e.target.value })
          }
          className={inputClass(!!errors?.listCIssuingAuthority)}
        />
      </FormField>
      <FormField
        label="Document number"
        htmlFor="i9-listCDocumentNumber"
        required
        error={errors?.listCDocumentNumber}
      >
        <input
          id="i9-listCDocumentNumber"
          type="text"
          value={data.listCDocumentNumber ?? ''}
          onChange={(e) =>
            onChange({ listCDocumentNumber: e.target.value })
          }
          className={inputClass(!!errors?.listCDocumentNumber)}
        />
      </FormField>
    </div>
  );
}
