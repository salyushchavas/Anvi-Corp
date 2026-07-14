"use client";

import { type ReactNode } from "react";
import { ToastProvider } from "./toast";
import { MailAuthProvider } from "./mail-auth-provider";

/** Client-side providers wrapping the whole (mail) route group. */
export function MailProviders({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <MailAuthProvider>{children}</MailAuthProvider>
    </ToastProvider>
  );
}
