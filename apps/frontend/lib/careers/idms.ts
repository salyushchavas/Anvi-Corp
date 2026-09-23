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
  /** ERM "correct & re-send" — true only while the offer is SENT but
   *  the intern hasn't submitted. Once they submit, correcting the
   *  document supersedes rather than reopens, which is a different
   *  flow. */
  canErmCorrect: boolean;
}

/**
 * Body for {@code POST /api/v1/erm/idms/{id}/correct-and-reopen}.
 *
 * <p>Every field is optional. Unlike a return or a revoke — both of
 * which are addressed to the intern and so require a reason they can
 * read — this is the ERM annotating their own typo for the audit trail,
 * and requiring a form to fix your own mistake is friction with no
 * reader. Omitted, the backend records {@code ERM_CORRECTION}.</p>
 */
export interface CorrectRequest {
  reasonCode?: string;
  comments?: string;
  expectedUpdatedAt?: number;
}

/** Endpoint for the ERM correct-and-reopen action. Kept here beside the
 *  request type so the path and its shape stay together; the POST
 *  itself runs in the page against the shared axios client, matching
 *  how every other IDMS transition is called (this module stays
 *  dependency-light on purpose — see downloadIdmsFinalPdf). */
export function correctAndReopenPath(instanceId: string): string {
  return `/api/v1/erm/idms/${instanceId}/correct-and-reopen`;
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
  /** Field-level correction lock — the ids ERM explicitly unlocked on
   *  the last RETURN. {@code null} = legacy behaviour (every intern-
   *  assigneed field editable when {@code internLocked} is false). A
   *  populated array narrows the set — only these ids are editable
   *  and the server-side write path rejects writes to others. */
  unlockedFieldIds: string[] | null;
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
  /**
   * True when the admin has edited the source template since this
   * draft was snapshotted. Meaningful ONLY on {@code status ===
   * 'DRAFT'} — every non-draft state returns false regardless of
   * what the underlying template has done, because sent / signed
   * / finalized docs are frozen. The ERM draft view uses this to
   * gate the "Update to latest template" banner (double-gate:
   * {@code isTemplateStale && status === 'DRAFT'}).
   */
  isTemplateStale: boolean;
}

/**
 * Compact summary of what happened to the ERM's already-entered
 * field values during a re-sync — populated ONLY on the resync
 * endpoint's response, not on general instance-detail reads.
 *
 * <p>The frontend uses this to surface an honest, specific
 * confirmation: "Draft updated. N field(s) you'd filled were
 * removed or changed in the updated template: <names>. Please
 * review before sending." — so the ERM never discovers a
 * silently-vanished value on a legal doc about to be sent.</p>
 */
export interface ResyncSummary {
  /** Value rows kept (id + type matched) — also counts renamed. */
  keptCount: number;
  /** Value rows deleted because the field id is gone. */
  droppedRemovedCount: number;
  /** Value rows deleted because the field type changed. */
  droppedTypeChangedCount: number;
  /** Field ids added by the admin — the ERM will fill these. */
  newFieldCount: number;
  /** Human names of REMOVED fields whose values were dropped —
   *  used verbatim in the notice so the ERM knows which of their
   *  entries vanished. Not populated for type-changed drops
   *  (those are discoverable in the refreshed form). May contain
   *  null entries if a legacy value row had no fieldName snapshot;
   *  filter defensively before rendering. */
  droppedRemovedFieldNames: Array<string | null>;
}

/** POST /api/v1/erm/idms/{id}/resync-template — a wrapper around
 *  the refreshed instance detail plus the resync summary. */
export interface ResyncTemplateResponse {
  instance: InstanceDetail;
  summary: ResyncSummary;
}

/**
 * Summary of what happened on the sibling reopen endpoint —
 * {@code POST /api/v1/erm/idms/{id}/reopen-template-update}. Unlike
 * {@link ResyncSummary} (DRAFT in-place resync), the reopen path is
 * the backward-hop for IN-FLIGHT docs (SENT_TO_INTERN / RETURNED /
 * INTERN_SUBMITTED / VERIFIED). The summary tells the frontend
 * WHICH way the doc got routed so the toast + redirect can match.
 */
export interface ReopenSummary {
  /** Status the doc was in BEFORE the reopen. Useful for audit
   *  logging on the frontend telemetry side. */
  fromStatus: InstanceStatus;
  /** Status the doc was routed to. If equal to {@code fromStatus},
   *  the reopen was a metadata-only no-op (AUTO_ONLY / NONE). */
  toStatus: InstanceStatus;
  /** Who's been re-routed to re-engage:
   *   - {@code "INTERN"}     — RETURNED, intern re-reviews + re-signs.
   *   - {@code "ERM"}        — DRAFT, ERM re-does their part.
   *   - {@code "BOTH_VIA_ERM"} — DRAFT, ERM first; forward flow
   *                              carries it to the intern on Send.
   *   - {@code "AUTO_ONLY"}  — no human; AUTO fields re-resolved.
   *   - {@code "NONE"}       — no party-relevant change; no-op. */
  reroutedTo: 'INTERN' | 'ERM' | 'BOTH_VIA_ERM' | 'AUTO_ONLY' | 'NONE';
  /** Value rows kept (id + type matched). Includes rows whose name
   *  was refreshed on the value row. */
  keptCount: number;
  /** Human names of REMOVED fields whose values were dropped — used
   *  verbatim in the frontend notice so the ERM knows which of their
   *  entries vanished. May contain null entries if a legacy value
   *  row had no fieldName snapshot; filter defensively. */
  droppedRemovedFieldNames: Array<string | null>;
  /** Signatures the reopen invalidated ({@code valueRepo.delete} on
   *  the signature value rows). Backend picks per R-SIG cautious
   *  policy + content over-approximation. */
  invalidatedSignatureCount: number;
  /** Field ids added by the admin — the appropriate party will fill
   *  these on the reopened cycle. */
  newFieldCount: number;
}

/** POST /api/v1/erm/idms/{id}/reopen-template-update — wrapper
 *  around the refreshed instance detail plus the reopen summary.
 *  Used ONLY for in-flight docs (not DRAFT — DRAFT has its own
 *  in-place resync path). */
export interface ReopenTemplateResponse {
  instance: InstanceDetail;
  summary: ReopenSummary;
}

/** Field schema entry (mirrors the Phase 1 studio + backend
 *  DocumentInstanceService.FieldSchemaEntry). */
export interface FieldSchemaEntry {
  id: string;
  name: string;
  type: 'text' | 'date' | 'signature' | 'content_block' | 'salutation';
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
      // Normalise the discriminants to their canonical case ONCE at
      // the schema-parse boundary. Every consumer in the codebase
      // compares strictly (schema.type === 'date', assignee === 'ERM'),
      // so a legacy template stored with uppercase "DATE" or "INTERN"
      // would fall through to the wrong branch — e.g. InstanceRenderer's
      // date-formatting branch is skipped, the raw ISO YYYY-MM-DD leaks
      // into the document preview instead of the MM/DD/YYYY that the
      // editor + PDF show. Normalising once here fixes every downstream
      // consumer by construction and removes the need for defensive
      // per-consumer .toLowerCase() checks (FieldForm's local defense
      // stays but becomes redundant + harmless).
      type: normaliseFieldType(f.type),
      assignee: normaliseAssignee(f.assignee),
      required: Boolean(f.required),
      defaultSource: f.defaultSource ?? null,
    }));
  } catch {
    return [];
  }
}

/** Normalise a raw stored field type to the canonical lowercase form
 *  the {@link FieldSchemaEntry} type union expects. Unknown values fall
 *  back to {@code 'text'} — the safe default that renders a plain
 *  text input rather than swallowing the field. */
function normaliseFieldType(raw: unknown): FieldSchemaEntry['type'] {
  if (typeof raw !== 'string') return 'text';
  switch (raw.trim().toLowerCase()) {
    case 'date':          return 'date';
    case 'signature':     return 'signature';
    case 'content_block': return 'content_block';
    case 'salutation':    return 'salutation';
    case 'text':          return 'text';
    default:              return 'text';
  }
}

/** The four allowed values for a {@code salutation}-type field.
 *  Fixed universal list — same set every template uses. Kept as a
 *  frontend constant (no schema change) per the survey's LIGHT PATH
 *  ruling; the backend mirrors this exact set inside
 *  {@code DocumentInstanceService.applyFieldValues} as a whitelist
 *  so a crafted client can't slip an off-list value onto a legal
 *  doc. Any change here MUST land in the backend set too — they
 *  are contract-bound. */
export const SALUTATIONS = ['Mr.', 'Ms.', 'Mx.', 'Dr.'] as const;
export type Salutation = (typeof SALUTATIONS)[number];

/** Normalise a raw stored assignee to the canonical uppercase form
 *  the {@link FieldSchemaEntry} type union expects. Unknown values
 *  fall back to {@code 'ERM'} to match the pre-normalisation default. */
function normaliseAssignee(raw: unknown): FieldSchemaEntry['assignee'] {
  if (typeof raw !== 'string') return 'ERM';
  switch (raw.trim().toUpperCase()) {
    case 'INTERN': return 'INTERN';
    case 'AUTO':   return 'AUTO';
    case 'ERM':    return 'ERM';
    default:       return 'ERM';
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
