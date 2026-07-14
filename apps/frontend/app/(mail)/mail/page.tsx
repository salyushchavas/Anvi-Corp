import { MailGuard } from "@/components/mail/guards";
import { MailShell } from "@/components/mail/mail-shell";

export default function MailInboxPage() {
  return (
    <MailGuard>
      <MailShell />
    </MailGuard>
  );
}
