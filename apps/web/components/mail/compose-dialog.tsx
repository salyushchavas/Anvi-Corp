"use client";

import { useRef, useState } from "react";
import { FileText, Paperclip, Send, X } from "lucide-react";
import { FormField, Input, Modal, Spinner, Textarea } from "./ui";
import { formatBytes, parseAddresses } from "./format";
import { attachmentsApi, messagesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { useToast } from "./toast";
import type { MailAttachment, MailSendPayload } from "@/lib/mail-types";

const MAX_BYTES = 25 * 1024 * 1024; // matches MAIL_ATTACHMENT_MAX_BYTES (25 MiB)

export interface ComposeInitial {
  to?: string;
  cc?: string;
  bcc?: string;
  subject?: string;
  body?: string;
  inReplyTo?: string;
  draftEntryId?: string;
  attachments?: MailAttachment[];
}

export function ComposeDialog({
  initial,
  onClose,
  onSent,
}: {
  initial: ComposeInitial;
  onClose: () => void;
  onSent: () => void;
}) {
  const toast = useToast();
  const [to, setTo] = useState(initial.to ?? "");
  const [cc, setCc] = useState(initial.cc ?? "");
  const [bcc, setBcc] = useState(initial.bcc ?? "");
  const [showCc, setShowCc] = useState(Boolean(initial.cc || initial.bcc));
  const [subject, setSubject] = useState(initial.subject ?? "");
  const [body, setBody] = useState(initial.body ?? "");
  const [draftEntryId, setDraftEntryId] = useState<string | undefined>(initial.draftEntryId);
  const [attachments, setAttachments] = useState<MailAttachment[]>(initial.attachments ?? []);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState<"send" | "draft" | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  function payload(): MailSendPayload {
    return {
      to: parseAddresses(to),
      cc: parseAddresses(cc),
      bcc: parseAddresses(bcc),
      subject: subject.trim(),
      bodyText: body,
      inReplyTo: initial.inReplyTo,
    };
  }

  // Attachments are draft-anchored: ensure a draft exists, then upload to it.
  async function onFilesSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = ""; // allow re-picking the same file
    if (files.length === 0) return;
    setUploading(true);
    try {
      let did = draftEntryId;
      for (const file of files) {
        if (file.size > MAX_BYTES) {
          toast.error(`"${file.name}" exceeds the 25 MB limit.`);
          continue;
        }
        try {
          if (!did) {
            const saved = await messagesApi.saveDraft(payload());
            did = saved.entryId;
            setDraftEntryId(did);
          }
          const att = await attachmentsApi.upload(did, file);
          setAttachments((prev) => [...prev, att]);
        } catch (err) {
          // 503 (S3 not configured), 413, 404 IDOR, etc. → clear toast, never silent.
          toast.error(err instanceof MailApiError ? err.message : `Could not attach "${file.name}".`);
        }
      }
    } finally {
      setUploading(false);
    }
  }

  async function removeAttachment(id: string) {
    try {
      await attachmentsApi.remove(id);
      setAttachments((prev) => prev.filter((a) => a.id !== id));
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not remove the attachment.");
    }
  }

  async function onSend() {
    if (parseAddresses(to).length === 0 && parseAddresses(cc).length === 0 && parseAddresses(bcc).length === 0) {
      toast.error("Add at least one recipient.");
      return;
    }
    setBusy("send");
    try {
      if (draftEntryId) {
        await messagesApi.sendDraft(draftEntryId, payload());
      } else {
        await messagesApi.send(payload());
      }
      toast.success("Message sent.");
      onSent();
      onClose();
    } catch (err) {
      // Cross-domain / unknown recipient (422) and every other backend error
      // surface as a clear toast — never swallowed.
      toast.error(err instanceof MailApiError ? err.message : "Could not send the message.");
      setBusy(null);
    }
  }

  async function onSaveDraft() {
    setBusy("draft");
    try {
      const saved = draftEntryId
        ? await messagesApi.updateDraft(draftEntryId, payload())
        : await messagesApi.saveDraft(payload());
      setDraftEntryId(saved.entryId);
      toast.success("Draft saved.");
      onSent();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not save the draft.");
    } finally {
      setBusy(null);
    }
  }

  const disabled = busy !== null || uploading;

  return (
    <Modal open onClose={onClose} title={draftEntryId ? "Edit draft" : "New message"} width="max-w-2xl">
      <div className="space-y-3">
        <FormField label="To" htmlFor="to" required hint="Same-domain addresses only (local part or full address).">
          <Input id="to" value={to} onChange={(e) => setTo(e.target.value)} placeholder="alice, bob@anvicorp.com" />
        </FormField>

        {!showCc ? (
          <button type="button" onClick={() => setShowCc(true)} className="text-xs font-medium text-brand hover:text-brand-600">
            Add Cc / Bcc
          </button>
        ) : (
          <>
            <FormField label="Cc" htmlFor="cc">
              <Input id="cc" value={cc} onChange={(e) => setCc(e.target.value)} />
            </FormField>
            <FormField label="Bcc" htmlFor="bcc">
              <Input id="bcc" value={bcc} onChange={(e) => setBcc(e.target.value)} />
            </FormField>
          </>
        )}

        <FormField label="Subject" htmlFor="subject">
          <Input id="subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
        </FormField>

        <FormField label="Message" htmlFor="body">
          <Textarea id="body" rows={9} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Write your message…" />
        </FormField>

        {/* Attachments (draft-anchored). */}
        <div>
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-brand hover:text-brand-600 disabled:opacity-60"
          >
            {uploading ? <Spinner className="h-3.5 w-3.5" /> : <Paperclip className="h-3.5 w-3.5" />}
            Attach files
            <span className="text-ink-300">(max 25 MB each)</span>
          </button>
          <input ref={fileInputRef} type="file" multiple hidden onChange={onFilesSelected} />

          {attachments.length > 0 && (
            <ul className="mt-2 space-y-1.5">
              {attachments.map((a) => (
                <li key={a.id} className="flex items-center gap-2 rounded-lg border border-ink-100 bg-ink-50 px-3 py-1.5 text-sm">
                  <FileText className="h-4 w-4 flex-shrink-0 text-ink-400" />
                  <span className="min-w-0 flex-1 truncate text-ink-700">{a.filename}</span>
                  <span className="flex-shrink-0 text-xs text-ink-300">{formatBytes(a.sizeBytes)}</span>
                  <button
                    type="button"
                    onClick={() => removeAttachment(a.id)}
                    className="flex-shrink-0 rounded p-0.5 text-ink-400 hover:text-red-600"
                    aria-label={`Remove ${a.filename}`}
                  >
                    <X className="h-4 w-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div className="mt-5 flex items-center justify-between">
        <button
          type="button"
          onClick={onSaveDraft}
          disabled={disabled}
          className="rounded-full px-4 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50 disabled:opacity-60"
        >
          {busy === "draft" ? "Saving…" : "Save draft"}
        </button>
        <button
          type="button"
          onClick={onSend}
          disabled={disabled}
          className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2.5 text-sm font-semibold text-white shadow-card transition hover:bg-brand-600 hover:shadow-cardHover disabled:opacity-60"
        >
          {busy === "send" ? <Spinner className="h-4 w-4 text-white" /> : <Send className="h-4 w-4" />}
          Send
        </button>
      </div>
    </Modal>
  );
}
