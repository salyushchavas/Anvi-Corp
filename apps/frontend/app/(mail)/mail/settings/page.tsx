import { MailGuard } from "@/components/mail/guards";
import { RulesScreen } from "@/components/mail/settings/rules-screen";

export default function MailSettingsPage() {
  return (
    <MailGuard>
      <RulesScreen />
    </MailGuard>
  );
}
