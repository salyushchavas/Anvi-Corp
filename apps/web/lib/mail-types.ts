// TypeScript shapes mirroring the REAL A1–A3 backend DTOs (flat records) served
// at /api/mail/**. Kept in sync with com.anvicorp.api.mail.dto.*.

export type MailRole = "USER" | "ADMIN" | "SUPER_ADMIN";
export type MailAccountStatus = "ACTIVE" | "SUSPENDED";
export type MailFolder = "INBOX" | "SENT" | "DRAFTS" | "ARCHIVE" | "TRASH";

/** Login / refresh / change-password response (A1 MailAuthResponse). */
export interface MailAuthResponse {
  token: string;
  refreshToken: string;
  accessTokenExpiresInSeconds: number | null;
  accountId: string;
  email: string;
  displayName: string | null;
  role: MailRole;
  mustChangePassword: boolean;
}

/** GET /me (A1 MailMeResponse). */
export interface MailMe {
  accountId: string;
  email: string;
  displayName: string | null;
  domainId: string;
  role: MailRole;
  mustChangePassword: boolean;
}

export interface MailParticipant {
  accountId: string;
  email: string;
  displayName: string | null;
}

/** Custom paged envelope (A2 MailPage). */
export interface MailPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface MailMessageSummary {
  entryId: string;
  messageId: string;
  threadId: string | null;
  folder: MailFolder;
  subject: string | null;
  from: MailParticipant;
  to: MailParticipant[];
  isRead: boolean;
  isStarred: boolean;
  isImportant: boolean;
  hasAttachments: boolean;
  createdAt: string;
}

export interface MailMessageDetail {
  entryId: string;
  messageId: string;
  threadId: string | null;
  inReplyTo: string | null;
  folder: MailFolder;
  subject: string | null;
  bodyText: string | null;
  bodyHtml: string | null;
  from: MailParticipant;
  to: MailParticipant[];
  cc: MailParticipant[];
  bcc: MailParticipant[];
  isRead: boolean;
  isStarred: boolean;
  isImportant: boolean;
  hasAttachments: boolean;
  draftTo: string | null;
  draftCc: string | null;
  draftBcc: string | null;
  createdAt: string;
}

export interface MailFolderCount {
  folder: MailFolder;
  total: number;
  unread: number;
}

export interface MailThreadResponse {
  threadId: string;
  messages: MailMessageDetail[];
}

/** Admin (A3). */
export interface MailMailbox {
  accountId: string;
  domainId: string;
  domainName: string;
  localPart: string;
  email: string;
  displayName: string | null;
  role: MailRole;
  status: MailAccountStatus;
  mustChangePassword: boolean;
  requireChangeOnFirstLogin: boolean;
  quotaBytes: number;
  createdAt: string;
}

export interface MailDomain {
  id: string;
  name: string;
  displayName: string | null;
  active: boolean;
  accountCount: number;
  createdAt: string;
}

/** Show-once provisioning response (A3 MailCredentialResponse). */
export interface MailCredential {
  accountId: string;
  email: string;
  oneTimePassword: string;
  mustChangePassword: boolean;
}

/** Compose/send + draft payload (A2 MailSendRequest / MailDraftRequest). */
export interface MailSendPayload {
  to?: string[];
  cc?: string[];
  bcc?: string[];
  subject?: string;
  bodyText?: string;
  bodyHtml?: string;
  inReplyTo?: string;
}
