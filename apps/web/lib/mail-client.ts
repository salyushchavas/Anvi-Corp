// Typed calls to every A1–A3 endpoint, all on the single mail-api instance.
import { mailApi, mailDownloadBlob, mailUpload, type BlobDownload } from "./mail-api";
import type {
  MailAttachment,
  MailAuthResponse,
  MailCredential,
  MailCustomFolder,
  MailDomain,
  MailFolder,
  MailFolderCount,
  MailMailbox,
  MailMe,
  MailMessageDetail,
  MailMessageSummary,
  MailPage,
  MailRole,
  MailRule,
  MailRuleInput,
  MailSendPayload,
  MailThreadResponse,
} from "./mail-types";

// ── Auth (A1) ────────────────────────────────────────────────────────────────
export const authApi = {
  login: (email: string, password: string) =>
    mailApi<MailAuthResponse>("/api/mail/auth/login", { method: "POST", body: { email, password }, auth: false }),
  logout: (refreshToken: string) =>
    mailApi<void>("/api/mail/auth/logout", { method: "POST", body: { refreshToken }, auth: false }),
  me: () => mailApi<MailMe>("/api/mail/me"),
  changePassword: (currentPassword: string, newPassword: string) =>
    mailApi<MailAuthResponse>("/api/mail/me/change-password", {
      method: "POST",
      body: { currentPassword, newPassword },
    }),
};

// ── Messages / folders / threads / flags / search / drafts (A2) ──────────────
export const messagesApi = {
  listFolder: (folder: MailFolder, page = 0, size = 25) =>
    mailApi<MailPage<MailMessageSummary>>(`/api/mail/folders/${folder}`, { query: { page, size } }),
  folderCounts: () => mailApi<MailFolderCount[]>("/api/mail/folder-counts"),
  starred: (page = 0, size = 25) =>
    mailApi<MailPage<MailMessageSummary>>("/api/mail/starred", { query: { page, size } }),
  get: (entryId: string) => mailApi<MailMessageDetail>(`/api/mail/messages/${entryId}`),
  thread: (threadId: string) => mailApi<MailThreadResponse>(`/api/mail/threads/${threadId}`),
  send: (payload: MailSendPayload) =>
    mailApi<MailMessageDetail>("/api/mail/messages", { method: "POST", body: payload }),
  saveDraft: (payload: MailSendPayload) =>
    mailApi<MailMessageDetail>("/api/mail/drafts", { method: "POST", body: payload }),
  updateDraft: (entryId: string, payload: MailSendPayload) =>
    mailApi<MailMessageDetail>(`/api/mail/drafts/${entryId}`, { method: "PUT", body: payload }),
  sendDraft: (entryId: string, payload?: MailSendPayload) =>
    mailApi<MailMessageDetail>(`/api/mail/drafts/${entryId}/send`, { method: "POST", body: payload ?? {} }),
  move: (entryId: string, folder: MailFolder) =>
    mailApi<MailMessageDetail>(`/api/mail/messages/${entryId}/folder`, { method: "PATCH", body: { folder } }),
  moveToCustomFolder: (entryId: string, customFolderId: string) =>
    mailApi<MailMessageDetail>(`/api/mail/messages/${entryId}/folder`, { method: "PATCH", body: { customFolderId } }),
  trash: (entryId: string) =>
    mailApi<MailMessageDetail>(`/api/mail/messages/${entryId}/trash`, { method: "POST" }),
  setFlags: (entryId: string, flags: { isRead?: boolean; isStarred?: boolean; isImportant?: boolean }) =>
    mailApi<MailMessageDetail>(`/api/mail/messages/${entryId}/flags`, { method: "PATCH", body: flags }),
  remove: (entryId: string) => mailApi<void>(`/api/mail/messages/${entryId}`, { method: "DELETE" }),
  search: (q: string, page = 0, size = 25) =>
    mailApi<MailPage<MailMessageSummary>>("/api/mail/search", { query: { q, page, size } }),
};

// ── Admin / provisioning (A3) ────────────────────────────────────────────────
export const adminApi = {
  listMailboxes: (filters: { domainId?: string; status?: string; search?: string } = {}) =>
    mailApi<MailMailbox[]>("/api/mail/admin/mailboxes", { query: filters }),
  createMailbox: (body: {
    domainId: string;
    localPart: string;
    displayName?: string;
    password?: string;
    requireChangeOnFirstLogin?: boolean;
  }) => mailApi<MailCredential>("/api/mail/admin/mailboxes", { method: "POST", body }),
  resetPassword: (id: string, password?: string) =>
    mailApi<MailCredential>(`/api/mail/admin/mailboxes/${id}/reset-password`, {
      method: "POST",
      body: { password },
    }),
  suspend: (id: string) => mailApi<MailMailbox>(`/api/mail/admin/mailboxes/${id}/suspend`, { method: "POST" }),
  reactivate: (id: string) =>
    mailApi<MailMailbox>(`/api/mail/admin/mailboxes/${id}/reactivate`, { method: "POST" }),
  setRole: (id: string, role: MailRole) =>
    mailApi<MailMailbox>(`/api/mail/admin/mailboxes/${id}/role`, { method: "PATCH", body: { role } }),
  listDomains: () => mailApi<MailDomain[]>("/api/mail/admin/domains"),
  createDomain: (name: string, displayName?: string) =>
    mailApi<MailDomain>("/api/mail/admin/domains", { method: "POST", body: { name, displayName } }),
  updateDomain: (id: string, body: { displayName?: string; active?: boolean }) =>
    mailApi<MailDomain>(`/api/mail/admin/domains/${id}`, { method: "PATCH", body }),
  deleteDomain: (id: string) => mailApi<void>(`/api/mail/admin/domains/${id}`, { method: "DELETE" }),
};

// ── Attachments (A5) — draft-anchored upload, walled proxy download, delete ──
export const attachmentsApi = {
  /** Raw octet-stream upload to the caller's own draft; filename/type as query params. */
  upload: (draftId: string, file: File) =>
    mailUpload<MailAttachment>("/api/mail/attachments", file, {
      draftId,
      filename: file.name,
      contentType: file.type || "application/octet-stream",
    }),
  /** Bearer-authed blob download through the walled proxy (no raw/presigned URL). */
  download: (id: string): Promise<BlobDownload> => mailDownloadBlob(`/api/mail/attachments/${id}`),
  remove: (id: string) => mailApi<void>(`/api/mail/attachments/${id}`, { method: "DELETE" }),
};

// ── Custom folders (A6) — CRUD + per-folder message listing ──────────────────
export const foldersApi = {
  list: () => mailApi<MailCustomFolder[]>("/api/mail/folders"),
  create: (name: string) => mailApi<MailCustomFolder>("/api/mail/folders", { method: "POST", body: { name } }),
  rename: (id: string, name: string) =>
    mailApi<MailCustomFolder>(`/api/mail/folders/${id}`, { method: "PUT", body: { name } }),
  remove: (id: string) => mailApi<void>(`/api/mail/folders/${id}`, { method: "DELETE" }),
  messages: (id: string, page = 0, size = 25) =>
    mailApi<MailPage<MailMessageSummary>>(`/api/mail/folders/${id}/messages`, { query: { page, size } }),
};

// ── Inbox rules (A7) — walled per-account CRUD + reorder/toggle ───────────────
export const rulesApi = {
  list: () => mailApi<MailRule[]>("/api/mail/rules"),
  create: (input: MailRuleInput) => mailApi<MailRule>("/api/mail/rules", { method: "POST", body: input }),
  update: (id: string, input: MailRuleInput) =>
    mailApi<MailRule>(`/api/mail/rules/${id}`, { method: "PUT", body: input }),
  remove: (id: string) => mailApi<void>(`/api/mail/rules/${id}`, { method: "DELETE" }),
  setEnabled: (id: string, enabled: boolean) =>
    mailApi<MailRule>(`/api/mail/rules/${id}/enabled`, { method: "PATCH", body: { enabled } }),
  reorder: (ids: string[]) => mailApi<MailRule[]>("/api/mail/rules/reorder", { method: "POST", body: { ids } }),
};
