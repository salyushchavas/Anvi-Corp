import { MailAdminGuard } from "@/components/mail/guards";
import { AdminConsole } from "@/components/mail/admin/admin-console";

export default function MailAdminPage() {
  return (
    <MailAdminGuard>
      <AdminConsole />
    </MailAdminGuard>
  );
}
