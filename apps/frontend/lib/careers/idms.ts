/** IDMS Phase 2 — shared types + tiny helpers for the ERM cockpit + intern
 *  Agreements pages. Kept honest against backend
 *  {@code com.anvicorp.api.erm.idms.DocumentInstanceDtos}. */

export type InstanceStatus =
  | 'DRAFT'
  | 'SENT_TO_INTERN'
  | 'INTERN_SUBMITTED'
  | 'RETURNED'
  | 'VERIFIED'
  | 'FINALIZED'
  | 'REVOKED'
  | 'SUPERSEDED'
  | 'VOIDED';

export interface QueueRow {
  instanceId: string | null;
  applicationId: string | null;
  internLifecycleId: string | null;
  internUserId: string | null;
  internName: string | null;
  internEmail: string | null;
  templateTitle: string | null;
  templateKey: string | null;
  stage: string;
  statusRaw: string;
  lastActivityAt: string | null;
  canRevoke: boolean;
  canSupersede: boolean;
  /** REVOKED-tab-only. Populated on rows whose statusRaw==='REVOKED';
   *  null everywhere else. The Revoked Offers section renders these
   *  as dedicated columns; all other tabs ignore them. */
  revokedAt: string | null;
  revokeReasonHuman: string | null;
  /** AWAITING_OFFER-only. 'PENDING' when the intern hasn't clicked
   *  "Receive my offer letter" on their dashboard yet; 'READY' once
   *  they have. Null on every non-awaiting row. Mirrors the backend
   *  SelectionAckPolicy.needsAck gate so the row's chip never
   *  disagrees with the 409 that /api/v1/erm/idms throws for
   *  offer-family templates on a not-yet-ack'd application. */
  selectionAckStatus: 'PENDING' | 'READY' | null;
}

export interface PickableTemplate {
  id: string;
  key: string;
  title: string;
  description: string | null;
  fieldCount: number;
}

export interface FieldValue {
  fieldId: string;
  fieldName: string;
  type: string;                // TEXT | DATE | SIGNATURE | CONTENT_BLOCK
  assignee: string;            // ERM | INTERN | AUTO
  valueText: string | null;
  signatureUrl: string | null;
  filledByRole: string | null;
  filledAt: string | null;
}

export interface ReviewLogEntry {
  action: string;
  reasonCode: string | null;
  comments: string | null;
  actorUserId: string;
  actorName: string | null;
  actorRole: string | null;
  createdAt: string;
}

export interface InstanceActions {
  canErmFill: boolean;
  canErmSend: boolean;
  canInternFill: boolean;
  canInternSubmit: boolean;
  canErmReturn: boolean;
  canErmVerify: boolean;
  canErmFinalize: boolean;
  canErmRevoke: boolean;
  revokeBlockedReason: string | null;
}

export interface InstanceDetail {
  id: string;
  templateId: string;
  templateKey: string;
  templateTitle: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  status: InstanceStatus;
  version: number;
  internLocked: boolean;
  canonicalHtml: string;
  fieldSchemaJson: string;
  values: Record<string, FieldValue>;
  finalPdfUrl: string | null;
  returnReasonCode: string | null;
  returnComments: string | null;
  revokeReasonCode: string | null;
  revokeComments: string | null;
  supersedesId: string | null;
  sentAt: string | null;
  internSubmittedAt: string | null;
  returnedAt: string | null;
  verifiedAt: string | null;
  finalizedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
  updatedAt: string;
  history: ReviewLogEntry[];
  actions: InstanceActions;
}

/** Field schema entry (mirrors the Phase 1 studio + backend
 *  DocumentInstanceService.FieldSchemaEntry). */
export interface FieldSchemaEntry {
  id: string;
  name: string;
  type: 'text' | 'date' | 'signature' | 'content_block';
  assignee: 'ERM' | 'INTERN' | 'AUTO';
  required: boolean;
  defaultSource: string | null;
}

export function parseFieldSchema(json: string | null | undefined): FieldSchemaEntry[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((f) => ({
      id: String(f.id ?? ''),
      name: String(f.name ?? ''),
      type: (f.type as FieldSchemaEntry['type']) ?? 'text',
      assignee: (f.assignee as FieldSchemaEntry['assignee']) ?? 'ERM',
      required: Boolean(f.required),
      defaultSource: f.defaultSource ?? null,
    }));
  } catch {
    return [];
  }
}

export function stageToneClass(stage: string): string {
  switch (stage) {
    case 'Draft':               return 'bg-slate-100 text-slate-700';
    case 'Sent':                return 'bg-sky-100 text-sky-800';
    case 'Signed — verifying':  return 'bg-violet-100 text-violet-800';
    case 'Returned':            return 'bg-amber-100 text-amber-900';
    case 'Verified':            return 'bg-emerald-100 text-emerald-800';
    case 'Executed':            return 'bg-emerald-100 text-emerald-900';
    case 'Revoked':             return 'bg-red-100 text-red-800';
    case 'Replaced':            return 'bg-slate-100 text-slate-600';
    case 'Awaiting offer':      return 'bg-amber-50 text-amber-900';
    // Split awaiting-offer chip after the SelectionAckPolicy gate landed:
    // "Acknowledgement pending" means the intern hasn't clicked "Receive
    // my offer letter" yet, so Send Document is server-blocked. "Ready
    // for offer" means the ack has stamped and the ERM can proceed.
    case 'Acknowledgement pending': return 'bg-slate-100 text-slate-700';
    case 'Ready for offer':     return 'bg-emerald-100 text-emerald-900';
    default:                    return 'bg-slate-100 text-slate-700';
  }
}

/** DOCUMENT_REJECT reason codes — mirrors backend ReasonCode enum. */
export const RETURN_REASONS: { code: string; label: string }[] = [
  { code: 'DOC_REJECT_INCOMPLETE',    label: 'Missing information' },
  { code: 'DOC_REJECT_WRONG_FILE',    label: 'Wrong document' },
  { code: 'DOC_REJECT_ILLEGIBLE',     label: 'Hard to read' },
  { code: 'DOC_REJECT_EXPIRED',       label: 'Expired document' },
  { code: 'DOC_REJECT_INFO_MISMATCH', label: 'Details don’t match' },
  { code: 'DOC_REJECT_OTHER',         label: 'Other (add comments)' },
];

export const REVOKE_REASONS: { code: string; label: string }[] = [
  { code: 'REVOKE_ROLE_CHANGE',   label: 'Role or terms changed' },
  { code: 'REVOKE_CANDIDATE_WITHDREW', label: 'Candidate withdrew' },
  { code: 'REVOKE_ISSUED_IN_ERROR',    label: 'Issued in error' },
  { code: 'REVOKE_COMPLIANCE',    label: 'Compliance concern' },
  { code: 'REVOKE_OTHER',         label: 'Other (add comments)' },
];

export function humanDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  } catch {
    return iso;
  }
}

/**
 * Format an ISO date (or datetime) as MM/DD/YYYY (US letter convention).
 *
 * <p>Used for every document-interpolated date — both the live preview and
 * the backend-rendered PDF must produce the identical string. The backend
 * peer is {@code IdmsDateFormat.formatIsoDate(String)} in the pdf renderer;
 * both are locale-independent (year/month/day pulled from the ISO literal
 * so browser locale can't drift).</p>
 *
 * <p>Falls back to the input verbatim when the string isn't ISO-shaped
 * (safer than hiding a mis-stored value from the reader).</p>
 */
/**
 * Trigger a save-as download of the IDMS executed PDF via the
 * authenticated backend endpoint.
 *
 * <p>The {@code finalPdfUrl} field returned by
 * {@code /api/v1/erm/idms/{id}} and {@code /api/v1/intern/agreements/{id}}
 * is now a same-origin path (e.g. {@code /api/v1/erm/idms/{id}/final-pdf}),
 * not a presigned S3 URL. A plain {@code <a href={url}>} click on those
 * paths won't send the Bearer JWT so the download would 401. This helper
 * fetches the bytes via axios (headers attached) as a Blob, extracts
 * the filename from Content-Disposition, then triggers a
 * client-side {@code <a download>} click on a blob URL — same pattern
 * as {@code ReviewTaskModal.downloadUpload}. Cleanly revokes the
 * blob URL afterwards so we don't leak.</p>
 *
 * @param apiClient  the shared axios instance (imported from
 *                   {@code @/lib/careers/api}). Injected instead of
 *                   pulled here to keep this module dependency-light.
 * @param url        relative path returned in {@code finalPdfUrl}
 * @param fallback   filename to use if Content-Disposition is missing —
 *                   e.g. {@code "Offer Letter.pdf"}
 */
export async function downloadIdmsFinalPdf(
  apiClient: {
    get<T>(url: string, config?: { responseType?: 'blob' }): Promise<{
      data: T;
      headers: Record<string, string> | { get?: (k: string) => string | null };
    }>;
  },
  url: string,
  fallback: string,
): Promise<void> {
  const res = await apiClient.get<Blob>(url, { responseType: 'blob' });
  const filename = filenameFromContentDisposition(headerValue(res.headers, 'content-disposition'))
    ?? fallback;
  const blobUrl = URL.createObjectURL(res.data);
  try {
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    // Give the browser a beat to actually start the download before
    // revoking; some browsers cancel the download if the URL is revoked
    // synchronously after click().
    setTimeout(() => URL.revokeObjectURL(blobUrl), 5_000);
  }
}

function headerValue(
  h: Record<string, string> | { get?: (k: string) => string | null },
  key: string,
): string | undefined {
  const asAxios = h as { get?: (k: string) => string | null };
  if (typeof asAxios.get === 'function') {
    const v = asAxios.get(key);
    return v ?? undefined;
  }
  const asObj = h as Record<string, string>;
  return asObj[key] ?? asObj[key.toLowerCase()];
}

/** Prefer RFC 5987 {@code filename*=UTF-8''<encoded>}; fall back to the
 *  legacy {@code filename="ascii"} — matches the two-header pattern the
 *  backend {@code ContentDispositionFilenames} emits. */
function filenameFromContentDisposition(cd: string | undefined): string | null {
  if (!cd) return null;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (star && star[1]) {
    try { return decodeURIComponent(star[1]); } catch { /* fall through */ }
  }
  const plain = /filename=("([^"]+)"|([^;]+))/i.exec(cd);
  if (plain) return (plain[2] ?? plain[3] ?? '').trim() || null;
  return null;
}

export function formatIsoDateMdy(v: string | null | undefined): string {
  if (!v) return '';
  // Accept both bare YYYY-MM-DD and full ISO timestamps.
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v.trim());
  if (!m) return v;
  const [, y, mo, d] = m;
  return `${mo}/${d}/${y}`;
}
