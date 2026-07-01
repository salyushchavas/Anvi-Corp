"use client";

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { useMailAuth } from "../mail-auth-provider";
import { FullScreenSpinner } from "../ui";
import { useToast } from "../toast";
import { MailboxesPanel } from "./mailboxes-panel";
import { DomainsPanel } from "./domains-panel";
import { adminApi, authApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import type { MailDomain } from "@/lib/mail-types";

export function AdminConsole() {
  const { account } = useMailAuth();
  const toast = useToast();
  const [tab, setTab] = useState<"mailboxes" | "domains">("mailboxes");
  const [domains, setDomains] = useState<MailDomain[] | null>(null);

  const isSuper = account?.role === "SUPER_ADMIN";

  useEffect(() => {
    if (!account) return;
    let cancelled = false;
    (async () => {
      try {
        if (isSuper) {
          const list = await adminApi.listDomains();
          if (!cancelled) setDomains(list);
        } else {
          // An ADMIN can't list domains (super-only); derive their single domain from /me.
          const me = await authApi.me();
          if (!cancelled) {
            setDomains([
              {
                id: me.domainId,
                name: me.email.split("@")[1] ?? "",
                displayName: null,
                active: true,
                accountCount: 0,
                createdAt: "",
              },
            ]);
          }
        }
      } catch (err) {
        if (!cancelled) {
          toast.error(err instanceof MailApiError ? err.message : "Could not load the admin console.");
          setDomains([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [account, isSuper, toast]);

  if (!account || domains === null) return <FullScreenSpinner />;

  return (
    <div className="min-h-screen bg-ink-50">
      <header className="flex h-14 items-center gap-3 border-b border-ink-100 bg-white px-4">
        <Link href="/mail" className="inline-flex flex-shrink-0 items-center gap-1.5 text-sm font-medium text-ink-600 hover:text-brand">
          <ArrowLeft className="h-4 w-4" /> Mail
        </Link>
        <span className="mx-1 h-5 w-px flex-shrink-0 bg-ink-100" />
        <span className="flex-shrink-0 font-bold text-ink-800">Admin console</span>
        <span className="ml-auto min-w-0 truncate text-sm text-ink-400">{account.email}</span>
      </header>

      <div className="mx-auto max-w-5xl px-4 py-6">
        <div className="mb-6 flex gap-1 border-b border-ink-100">
          <TabButton active={tab === "mailboxes"} onClick={() => setTab("mailboxes")}>
            Mailboxes
          </TabButton>
          {isSuper && (
            <TabButton active={tab === "domains"} onClick={() => setTab("domains")}>
              Domains
            </TabButton>
          )}
        </div>

        {tab === "mailboxes" ? <MailboxesPanel account={account} domains={domains} /> : isSuper ? <DomainsPanel /> : null}
      </div>
    </div>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium transition ${
        active ? "border-brand text-brand" : "border-transparent text-ink-500 hover:text-ink-800"
      }`}
    >
      {children}
    </button>
  );
}
