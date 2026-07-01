"use client";

import { useState } from "react";
import { Send } from "lucide-react";
import { FormField, Input, Modal, Spinner, Textarea } from "./ui";
import { parseAddresses } from "./format";
import { messagesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { useToast } from "./toast";
import type { MailSendPayload } from "@/lib/mail-types";

export interface ComposeInitial {
  to?: string;
  cc?: string;
  bcc?: string;
  subject?: string;
  body?: string;
  inReplyTo?: string;
  draftEntryId?: string;
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
  const [busy, setBusy] = useState<"send" | "draft" | null>(null);

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
          <Textarea id="body" rows={10} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Write your message…" />
        </FormField>
      </div>

      <div className="mt-5 flex items-center justify-between">
        <button
          type="button"
          onClick={onSaveDraft}
          disabled={busy !== null}
          className="rounded-full px-4 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50 disabled:opacity-60"
        >
          {busy === "draft" ? "Saving…" : "Save draft"}
        </button>
        <button
          type="button"
          onClick={onSend}
          disabled={busy !== null}
          className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2.5 text-sm font-semibold text-white shadow-card transition hover:bg-brand-600 hover:shadow-cardHover disabled:opacity-60"
        >
          {busy === "send" ? <Spinner className="h-4 w-4 text-white" /> : <Send className="h-4 w-4" />}
          Send
        </button>
      </div>
    </Modal>
  );
}
