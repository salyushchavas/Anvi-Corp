import type { Metadata } from "next";
import { MailProviders } from "@/components/mail/providers";

// Shell-free layout for the mail app — its own chrome, NOT the marketing header/
// footer. Renders inside the root layout (html/body/fonts).
export const metadata: Metadata = {
  title: { default: "Mail", template: "%s · Mail" },
  robots: { index: false, follow: false },
};

export default function MailLayout({ children }: { children: React.ReactNode }) {
  return <MailProviders>{children}</MailProviders>;
}
