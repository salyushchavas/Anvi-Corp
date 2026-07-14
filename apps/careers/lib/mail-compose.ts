// Pure helpers for reply / reply-all / forward prefill. No I/O, no React — easy
// to reason about and (when a frontend test runner is added) to unit-test.

import type { MailAttachmentResponse, MailMessageDetail } from './mail-client';

/** The editable compose state (recipient fields are comma-joined strings). */
export interface ComposeDraft {
  to: string;
  cc: string;
  bcc: string;
  subject: string;
  bodyText: string;
  /** message id being replied to (omitted for new/forward). */
  inReplyTo?: string;
  /** draft mailbox-entry id when editing an existing draft. */
  draftEntryId?: string;
  /** already-uploaded attachments (only when editing an existing draft). */
  attachments?: MailAttachmentResponse[];
}

function rePrefix(subject?: string | null): string {
  const s = (subject ?? '').trim();
  return /^re:/i.test(s) ? s : `Re: ${s}`.trim();
}

function fwdPrefix(subject?: string | null): string {
  const s = (subject ?? '').trim();
  return /^fwd:/i.test(s) ? s : `Fwd: ${s}`.trim();
}

function quoteBody(d: MailMessageDetail): string {
  const who = d.from?.email ?? 'unknown';
  const when = d.createdAt ?? '';
  const body = d.bodyText ?? d.bodyHtml ?? '';
  const quoted = body
    .split('\n')
    .map((line) => `> ${line}`)
    .join('\n');
  return `\n\nOn ${when}, ${who} wrote:\n${quoted}`;
}

function emailsOf(list?: MailMessageDetail['to']): string[] {
  return (list ?? []).map((p) => p.email).filter((e): e is string => !!e);
}

function dedupExcluding(emails: string[], exclude: Set<string>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const e of emails) {
    const key = e.toLowerCase();
    if (exclude.has(key) || seen.has(key)) continue;
    seen.add(key);
    out.push(e);
  }
  return out;
}

export function buildReply(d: MailMessageDetail): ComposeDraft {
  return {
    to: d.from?.email ?? '',
    cc: '',
    bcc: '',
    subject: rePrefix(d.subject),
    bodyText: quoteBody(d),
    inReplyTo: d.messageId,
  };
}

/**
 * Reply-all: the original sender becomes To and the original TO+CC (minus self
 * and the sender) become Cc. When replying-all to your OWN sent message, the
 * original recipients become To instead (replying to yourself is pointless).
 * The original BCC is intentionally NOT included (BCC is private).
 */
export function buildReplyAll(d: MailMessageDetail, selfEmail: string): ComposeDraft {
  const self = new Set<string>([selfEmail.toLowerCase()]);
  const senderEmail = d.from?.email ?? '';
  const senderIsSelf = !!senderEmail && senderEmail.toLowerCase() === selfEmail.toLowerCase();
  const to = senderIsSelf
    ? dedupExcluding(emailsOf(d.to), self)
    : dedupExcluding(senderEmail ? [senderEmail] : [], self);
  const ccCandidates = senderIsSelf ? emailsOf(d.cc) : [...emailsOf(d.to), ...emailsOf(d.cc)];
  const excludeFromCc = new Set<string>([...self, ...to.map((e) => e.toLowerCase())]);
  const cc = dedupExcluding(ccCandidates, excludeFromCc);
  return {
    to: to.join(', '),
    cc: cc.join(', '),
    bcc: '',
    subject: rePrefix(d.subject),
    bodyText: quoteBody(d),
    inReplyTo: d.messageId,
  };
}

export function buildForward(d: MailMessageDetail): ComposeDraft {
  return {
    to: '',
    cc: '',
    bcc: '',
    subject: fwdPrefix(d.subject),
    bodyText: quoteBody(d),
    // a forward starts a new thread — no inReplyTo
  };
}

/** Load an existing draft (a DRAFTS detail) into the composer. */
export function buildFromDraft(d: MailMessageDetail): ComposeDraft {
  return {
    to: d.draftTo ?? '',
    cc: d.draftCc ?? '',
    bcc: d.draftBcc ?? '',
    subject: d.subject ?? '',
    bodyText: d.bodyText ?? '',
    inReplyTo: d.inReplyTo ?? undefined,
    draftEntryId: d.entryId,
    attachments: d.attachments ?? [],
  };
}

export function emptyDraft(): ComposeDraft {
  return { to: '', cc: '', bcc: '', subject: '', bodyText: '' };
}

/** Split a comma/newline-separated recipient field into trimmed addresses. */
export function parseRecipients(field: string): string[] {
  return field
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}
