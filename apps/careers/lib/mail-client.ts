// Typed calls against the S1 mail backend. Shapes match the ACTUAL S1 DTOs:
//   MailAuthResponse: { token, refreshToken?, accessTokenExpiresInSeconds?,
//                       accountId, email, displayName?, role, mustChangePassword? }
//   MailMeResponse:   { accountId, email, displayName?, domainId, role,
//                       mustChangePassword? }

import { mailApi } from './mail-api';
import type { MailAccount } from './mail-auth-storage';

// Re-exported so consumers (MailAuthProvider) import session control from one place.
export { invalidateMailSession } from './mail-api';

export interface MailAuthResponse {
  token: string;
  refreshToken?: string;
  accessTokenExpiresInSeconds?: number;
  accountId: string;
  email: string;
  displayName?: string;
  role: string;
  mustChangePassword?: boolean;
}

export interface MailMeResponse {
  accountId: string;
  email: string;
  displayName?: string;
  domainId: string;
  role: string;
  mustChangePassword?: boolean;
}

export function accountFromAuthResponse(r: MailAuthResponse): MailAccount {
  return {
    accountId: r.accountId,
    email: r.email,
    displayName: r.displayName ?? null,
    role: r.role,
    mustChangePassword: r.mustChangePassword === true,
    domainId: null,
  };
}

export function accountFromMe(r: MailMeResponse): MailAccount {
  return {
    accountId: r.accountId,
    email: r.email,
    displayName: r.displayName ?? null,
    role: r.role,
    mustChangePassword: r.mustChangePassword === true,
    domainId: r.domainId,
  };
}

export async function mailLogin(
  email: string,
  password: string,
): Promise<MailAuthResponse> {
  const res = await mailApi.post<MailAuthResponse>('/api/mail/auth/login', {
    email,
    password,
  });
  return res.data;
}

export async function mailRefresh(refreshToken: string): Promise<MailAuthResponse> {
  const res = await mailApi.post<MailAuthResponse>('/api/mail/auth/refresh', {
    refreshToken,
  });
  return res.data;
}

export async function mailLogout(refreshToken: string): Promise<void> {
  await mailApi.post('/api/mail/auth/logout', { refreshToken });
}

export async function mailGetMe(): Promise<MailMeResponse> {
  const res = await mailApi.get<MailMeResponse>('/api/mail/me');
  return res.data;
}

export async function mailChangePassword(
  currentPassword: string,
  newPassword: string,
): Promise<MailAuthResponse> {
  const res = await mailApi.post<MailAuthResponse>(
    '/api/mail/me/change-password',
    { currentPassword, newPassword },
  );
  return res.data;
}

// ── Admin console (S4) ────────────────────────────────────────────────
// All calls ride the SAME mailApi instance, so they inherit the mail Bearer,
// the single-flight 401 refresh, and the logout-race epoch guard. Shapes match
// the S3 backend DTOs.

export interface MailMailboxResponse {
  accountId: string;
  domainId: string;
  domainName: string;
  localPart: string;
  email: string;
  displayName: string | null;
  role: string;
  status: string;
  mustChangePassword: boolean;
  requireChangeOnFirstLogin: boolean;
  quotaBytes: number;
  createdAt: string;
}

/** SHOW-ONCE provisioning result. oneTimePassword is never refetched. */
export interface MailCredentialResponse {
  accountId: string;
  email: string;
  oneTimePassword: string;
  mustChangePassword: boolean;
}

export interface MailDomainResponse {
  id: string;
  name: string;
  displayName: string | null;
  active: boolean;
  accountCount: number;
  createdAt: string;
}

export interface CreateMailboxInput {
  domainId: string;
  localPart: string;
  displayName?: string;
  password?: string;
  requireChangeOnFirstLogin?: boolean;
}

export interface MailboxFilters {
  domainId?: string;
  status?: string;
  search?: string;
}

export async function listMailboxes(
  filters: MailboxFilters = {},
): Promise<MailMailboxResponse[]> {
  const res = await mailApi.get<MailMailboxResponse[]>('/api/mail/admin/mailboxes', {
    params: {
      domainId: filters.domainId || undefined,
      status: filters.status || undefined,
      search: filters.search || undefined,
    },
  });
  return res.data;
}

export async function createMailbox(
  input: CreateMailboxInput,
): Promise<MailCredentialResponse> {
  const res = await mailApi.post<MailCredentialResponse>(
    '/api/mail/admin/mailboxes',
    input,
  );
  return res.data;
}

export async function resetMailboxPassword(
  accountId: string,
  password?: string,
): Promise<MailCredentialResponse> {
  const res = await mailApi.post<MailCredentialResponse>(
    `/api/mail/admin/mailboxes/${accountId}/reset-password`,
    { password: password || undefined },
  );
  return res.data;
}

export async function suspendMailbox(accountId: string): Promise<MailMailboxResponse> {
  const res = await mailApi.post<MailMailboxResponse>(
    `/api/mail/admin/mailboxes/${accountId}/suspend`,
  );
  return res.data;
}

export async function reactivateMailbox(accountId: string): Promise<MailMailboxResponse> {
  const res = await mailApi.post<MailMailboxResponse>(
    `/api/mail/admin/mailboxes/${accountId}/reactivate`,
  );
  return res.data;
}

export async function setMailboxRole(
  accountId: string,
  role: string,
): Promise<MailMailboxResponse> {
  const res = await mailApi.patch<MailMailboxResponse>(
    `/api/mail/admin/mailboxes/${accountId}/role`,
    { role },
  );
  return res.data;
}

export async function listDomains(): Promise<MailDomainResponse[]> {
  const res = await mailApi.get<MailDomainResponse[]>('/api/mail/admin/domains');
  return res.data;
}

export async function createDomain(
  name: string,
  displayName?: string,
): Promise<MailDomainResponse> {
  const res = await mailApi.post<MailDomainResponse>('/api/mail/admin/domains', {
    name,
    displayName,
  });
  return res.data;
}

export async function updateDomain(
  id: string,
  patch: { displayName?: string; active?: boolean },
): Promise<MailDomainResponse> {
  const res = await mailApi.patch<MailDomainResponse>(
    `/api/mail/admin/domains/${id}`,
    patch,
  );
  return res.data;
}

export async function deleteDomain(id: string): Promise<void> {
  await mailApi.delete(`/api/mail/admin/domains/${id}`);
}

/** Extract a user-facing message from a mail API error (interceptor-enriched). */
export function mailErrorMessage(err: unknown, fallback = 'Something went wrong'): string {
  const e = err as {
    userMessage?: string;
    response?: { data?: { message?: string; error?: string } };
  };
  return e?.userMessage ?? e?.response?.data?.message ?? e?.response?.data?.error ?? fallback;
}

// ── Mail core (S6 client) ─────────────────────────────────────────────
// Shapes match the S5 DTOs exactly. NOTE: paged responses use the custom
// MailPage shape { items, page, size, total } — NOT Spring's Page. Nullable
// fields (subject, bodyHtml, inReplyTo, draft*, displayName) are omitted by the
// backend (@JsonInclude NON_NULL) so they are optional here.

export interface MailParticipant {
  accountId: string;
  email: string;
  displayName?: string | null;
}

export interface MailMessageSummary {
  entryId: string;
  messageId: string;
  threadId?: string | null;
  folder: string;
  subject?: string | null;
  from?: MailParticipant | null;
  to?: MailParticipant[] | null;
  isRead: boolean;
  isStarred: boolean;
  isImportant: boolean;
  hasAttachments: boolean;
  createdAt: string;
}

export interface MailMessageDetail {
  entryId: string;
  messageId: string;
  threadId?: string | null;
  inReplyTo?: string | null;
  folder: string;
  subject?: string | null;
  bodyText?: string | null;
  bodyHtml?: string | null;
  from?: MailParticipant | null;
  to?: MailParticipant[] | null;
  cc?: MailParticipant[] | null;
  bcc?: MailParticipant[] | null;
  isRead: boolean;
  isStarred: boolean;
  isImportant: boolean;
  hasAttachments: boolean;
  draftTo?: string | null;
  draftCc?: string | null;
  draftBcc?: string | null;
  createdAt: string;
  attachments?: MailAttachmentResponse[];
  /** Non-null when this entry lives in a custom folder (placement precedence). */
  customFolderId?: string | null;
}

export interface MailCustomFolder {
  id: string;
  name: string;
  total: number;
  unread: number;
}

export interface MailAttachmentResponse {
  id: string;
  filename: string;
  contentType?: string | null;
  sizeBytes: number;
}

export interface MailFolderCount {
  folder: string;
  total: number;
  unread: number;
}

export interface MailThreadResponse {
  threadId: string;
  messages: MailMessageDetail[];
}

export interface MailPageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** Send/draft payload — recipients are arrays of bare local parts or addresses. */
export interface ComposePayload {
  to: string[];
  cc?: string[];
  bcc?: string[];
  subject?: string;
  bodyText?: string;
  bodyHtml?: string;
  inReplyTo?: string;
}

export async function listFolder(
  folder: string,
  page = 0,
  size = 25,
): Promise<MailPageResult<MailMessageSummary>> {
  const res = await mailApi.get<MailPageResult<MailMessageSummary>>(
    `/api/mail/folders/${folder}`,
    { params: { page, size } },
  );
  return res.data;
}

export async function listStarred(
  page = 0,
  size = 25,
): Promise<MailPageResult<MailMessageSummary>> {
  const res = await mailApi.get<MailPageResult<MailMessageSummary>>('/api/mail/starred', {
    params: { page, size },
  });
  return res.data;
}

export async function folderCounts(): Promise<MailFolderCount[]> {
  const res = await mailApi.get<MailFolderCount[]>('/api/mail/folder-counts');
  return res.data;
}

export async function getMessage(entryId: string): Promise<MailMessageDetail> {
  const res = await mailApi.get<MailMessageDetail>(`/api/mail/messages/${entryId}`);
  return res.data;
}

export async function getThread(threadId: string): Promise<MailThreadResponse> {
  const res = await mailApi.get<MailThreadResponse>(`/api/mail/threads/${threadId}`);
  return res.data;
}

export async function sendMessage(payload: ComposePayload): Promise<MailMessageDetail> {
  const res = await mailApi.post<MailMessageDetail>('/api/mail/messages', payload);
  return res.data;
}

export async function saveDraft(payload: ComposePayload): Promise<MailMessageDetail> {
  const res = await mailApi.post<MailMessageDetail>('/api/mail/drafts', payload);
  return res.data;
}

export async function updateDraft(
  entryId: string,
  payload: ComposePayload,
): Promise<MailMessageDetail> {
  const res = await mailApi.put<MailMessageDetail>(`/api/mail/drafts/${entryId}`, payload);
  return res.data;
}

export async function sendDraft(
  entryId: string,
  payload?: ComposePayload,
): Promise<MailMessageDetail> {
  const res = await mailApi.post<MailMessageDetail>(
    `/api/mail/drafts/${entryId}/send`,
    payload ?? {},
  );
  return res.data;
}

export async function moveMessage(entryId: string, folder: string): Promise<MailMessageDetail> {
  const res = await mailApi.patch<MailMessageDetail>(
    `/api/mail/messages/${entryId}/folder`,
    { folder },
  );
  return res.data;
}

export async function moveMessageToCustomFolder(
  entryId: string,
  customFolderId: string,
): Promise<MailMessageDetail> {
  const res = await mailApi.patch<MailMessageDetail>(
    `/api/mail/messages/${entryId}/folder`,
    { customFolderId },
  );
  return res.data;
}

export async function setMessageFlags(
  entryId: string,
  flags: { isRead?: boolean; isStarred?: boolean; isImportant?: boolean },
): Promise<MailMessageDetail> {
  const res = await mailApi.patch<MailMessageDetail>(
    `/api/mail/messages/${entryId}/flags`,
    flags,
  );
  return res.data;
}

export async function deleteMessage(entryId: string): Promise<void> {
  await mailApi.delete(`/api/mail/messages/${entryId}`);
}

export async function searchMessages(
  q: string,
  page = 0,
  size = 25,
): Promise<MailPageResult<MailMessageSummary>> {
  const res = await mailApi.get<MailPageResult<MailMessageSummary>>('/api/mail/search', {
    params: { q, page, size },
  });
  return res.data;
}

// ── Attachments (S7a) ─────────────────────────────────────────────────
// Upload streams the raw File body (NOT multipart) on the mailApi instance so
// it inherits the Bearer + refresh + epoch guard; the backend bounds it to 25 MB.
// Download hits the walled proxy as a Bearer-authed blob — never a raw S3 URL.

export async function uploadAttachment(
  draftEntryId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<MailAttachmentResponse> {
  const res = await mailApi.post<MailAttachmentResponse>('/api/mail/attachments', file, {
    params: {
      draftId: draftEntryId,
      filename: file.name,
      contentType: file.type || 'application/octet-stream',
    },
    headers: { 'Content-Type': 'application/octet-stream' },
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100));
    },
  });
  return res.data;
}

export async function deleteAttachment(id: string): Promise<void> {
  await mailApi.delete(`/api/mail/attachments/${id}`);
}

/** Download via the authed proxy → object URL → click → revoke. No raw S3 link. */
export async function downloadAttachment(att: MailAttachmentResponse): Promise<void> {
  const res = await mailApi.get(`/api/mail/attachments/${att.id}`, { responseType: 'blob' });
  const url = URL.createObjectURL(res.data as Blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = att.filename || 'attachment';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ── Inbox rules (S7b) ─────────────────────────────────────────────────

export type MailRuleField = 'FROM' | 'TO' | 'CC' | 'SUBJECT' | 'HAS_ATTACHMENT';
export type MailRuleOperator = 'CONTAINS' | 'EQUALS' | 'IS_TRUE';
export type MailRuleActionType = 'MOVE_TO_FOLDER' | 'MARK_READ' | 'STAR' | 'MARK_IMPORTANT' | 'DELETE';
export type MailRuleMatchMode = 'ALL' | 'ANY';

export interface MailRuleCondition {
  field: MailRuleField;
  operator: MailRuleOperator;
  value?: string | null;
}

export interface MailRuleAction {
  type: MailRuleActionType;
  targetFolder?: string | null;
  targetCustomFolderId?: string | null;
}

export interface MailRuleResponse {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  matchMode: MailRuleMatchMode;
  stopProcessing: boolean;
  conditions: MailRuleCondition[];
  actions: MailRuleAction[];
  createdAt: string;
  updatedAt?: string | null;
}

export interface MailRuleRequest {
  name: string;
  priority?: number;
  enabled?: boolean;
  matchMode?: MailRuleMatchMode;
  stopProcessing?: boolean;
  conditions: MailRuleCondition[];
  actions: MailRuleAction[];
}

export async function listRules(): Promise<MailRuleResponse[]> {
  const res = await mailApi.get<MailRuleResponse[]>('/api/mail/rules');
  return res.data;
}

export async function createRule(req: MailRuleRequest): Promise<MailRuleResponse> {
  const res = await mailApi.post<MailRuleResponse>('/api/mail/rules', req);
  return res.data;
}

export async function updateRule(id: string, req: MailRuleRequest): Promise<MailRuleResponse> {
  const res = await mailApi.put<MailRuleResponse>(`/api/mail/rules/${id}`, req);
  return res.data;
}

export async function deleteRule(id: string): Promise<void> {
  await mailApi.delete(`/api/mail/rules/${id}`);
}

// ── Custom folders (S9) ───────────────────────────────────────────────

export async function listCustomFolders(): Promise<MailCustomFolder[]> {
  const res = await mailApi.get<MailCustomFolder[]>('/api/mail/folders');
  return res.data;
}

export async function createCustomFolder(name: string): Promise<MailCustomFolder> {
  const res = await mailApi.post<MailCustomFolder>('/api/mail/folders', { name });
  return res.data;
}

export async function renameCustomFolder(id: string, name: string): Promise<MailCustomFolder> {
  const res = await mailApi.put<MailCustomFolder>(`/api/mail/folders/${id}`, { name });
  return res.data;
}

export async function deleteCustomFolder(id: string): Promise<void> {
  await mailApi.delete(`/api/mail/folders/${id}`);
}

export async function listCustomFolderMessages(
  id: string,
  page = 0,
  size = 25,
): Promise<MailPageResult<MailMessageSummary>> {
  const res = await mailApi.get<MailPageResult<MailMessageSummary>>(`/api/mail/folders/${id}/messages`, {
    params: { page, size },
  });
  return res.data;
}
