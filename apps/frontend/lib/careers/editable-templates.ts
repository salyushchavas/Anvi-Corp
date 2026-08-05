/**
 * Shared TypeScript types + tiny helpers for the Editable Documents
 * admin studio. Kept co-located so the list / new / edit pages import
 * from one place and the shape stays honest against the backend
 * {@code EditableTemplateDtos}.
 */

export type FieldType = 'text' | 'date' | 'signature' | 'content_block';
export type FieldAssignee = 'ERM' | 'INTERN' | 'AUTO';

/** Mirror of {@code EditableTemplateDtos.FieldEntry}. */
export interface FieldEntry {
  id: string;
  name: string;
  type: FieldType;
  assignee: FieldAssignee;
  required: boolean;
  /** For {@code assignee = AUTO}: the binding key Phase 2 will pull the
   *  value from. Null otherwise. */
  defaultSource: string | null;
}

/** Mirror of {@code EditableTemplateDtos.TemplateRow}. */
export interface TemplateRow {
  id: string;
  key: string;
  title: string;
  description: string | null;
  active: boolean;
  sortOrder: number;
  hasSource: boolean;
  sourceDocumentId: string | null;
  sourceFileName: string | null;
  sourceFileBytes: number | null;
  sourceMimeType: string | null;
  /** Presigned GET URL for the source DOCX (detail responses only). */
  sourceDownloadUrl: string | null;
  fieldCount: number;
  /** Detail responses only. */
  canonicalHtml: string | null;
  /** Detail responses only. JSON string encoding {@link FieldEntry}[]. */
  fieldSchemaJson: string | null;
  /** Detail responses only. JSON string encoding {@code string[]}. */
  fidelityWarningsJson: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Starter set of AUTO bindings the field inspector exposes as a picker. */
export const AUTO_BINDINGS: { value: string; label: string }[] = [
  { value: 'intern.fullName', label: "Intern full name" },
  { value: 'intern.legalName', label: "Intern legal name" },
  { value: 'intern.email', label: 'Intern email' },
  { value: 'intern.employeeId', label: 'Employee ID' },
  { value: 'intern.position', label: 'Position / role' },
  { value: 'today', label: "Today's date" },
];

export const FIELD_TYPES: { value: FieldType; label: string }[] = [
  { value: 'text', label: 'Text' },
  { value: 'date', label: 'Date' },
  { value: 'signature', label: 'Signature' },
  { value: 'content_block', label: 'Content block' },
];

export const ASSIGNEES: { value: FieldAssignee; label: string; tone: string }[] = [
  { value: 'ERM',    label: 'ERM',    tone: 'bg-brand-100 text-brand-800 border-brand-300' },
  { value: 'INTERN', label: 'Intern', tone: 'bg-amber-100 text-amber-800 border-amber-300' },
  { value: 'AUTO',   label: 'Auto',   tone: 'bg-slate-100 text-slate-700 border-slate-300' },
];

export function assigneeTone(a: FieldAssignee): string {
  return ASSIGNEES.find((x) => x.value === a)?.tone
    ?? 'bg-slate-100 text-slate-700 border-slate-300';
}

/** Convert a mid-word title to a STABLE_KEY_STYLE identifier. */
export function deriveKey(title: string): string {
  return title.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
}

/** Format bytes for the file-size chip. */
export function humanBytes(b: number | null | undefined): string {
  if (b == null) return '';
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / (1024 * 1024)).toFixed(2)} MB`;
}

/** Parse the JSON-string field schema stored on the row into typed entries. */
export function parseFieldSchema(fieldSchemaJson: string | null): FieldEntry[] {
  if (!fieldSchemaJson) return [];
  try {
    const parsed = JSON.parse(fieldSchemaJson);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((f) => ({
      id: String(f.id ?? ''),
      name: String(f.name ?? ''),
      type: (f.type as FieldType) ?? 'text',
      assignee: (f.assignee as FieldAssignee) ?? 'ERM',
      required: Boolean(f.required),
      defaultSource: f.defaultSource ?? null,
    }));
  } catch {
    return [];
  }
}

/** Parse the JSON-string fidelity warnings stored on the row. */
export function parseFidelityWarnings(json: string | null): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((w) => String(w));
  } catch {
    return [];
  }
}
