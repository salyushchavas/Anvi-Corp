"use client";

import { Loader2, X } from "lucide-react";
import { type ReactNode, type InputHTMLAttributes, type TextareaHTMLAttributes, useEffect } from "react";

export function Spinner({ className = "h-5 w-5" }: { className?: string }) {
  return <Loader2 className={`animate-spin text-brand ${className}`} />;
}

export function FullScreenSpinner() {
  return (
    <div className="flex h-screen w-full items-center justify-center bg-ink-50">
      <Spinner className="h-8 w-8" />
    </div>
  );
}

const fieldBase =
  "block w-full rounded-xl border border-ink-100 bg-white px-4 py-2.5 text-sm text-ink-800 placeholder:text-ink-300 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20 disabled:opacity-60";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  const { className = "", ...rest } = props;
  return <input className={`${fieldBase} ${className}`} {...rest} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const { className = "", ...rest } = props;
  return <textarea className={`${fieldBase} ${className}`} {...rest} />;
}

export function FormField({
  label,
  htmlFor,
  required,
  hint,
  error,
  children,
}: {
  label: string;
  htmlFor?: string;
  required?: boolean;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-ink-800">
        {label} {required && <span className="text-brand">*</span>}
      </label>
      {children}
      {hint && !error && <p className="mt-1 text-xs text-ink-300">{hint}</p>}
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  );
}

export function Modal({
  open,
  onClose,
  title,
  children,
  width = "max-w-lg",
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  width?: string;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[90] flex items-start justify-center overflow-y-auto bg-ink-800/40 p-4 sm:items-center">
      <div className={`w-full ${width} rounded-2xl border border-ink-100 bg-white shadow-cardHover`}>
        <div className="flex items-center justify-between border-b border-ink-100 px-5 py-4">
          <h2 className="text-lg font-semibold text-ink-800">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-ink-400 hover:bg-ink-50 hover:text-ink-800"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="px-5 py-5">{children}</div>
      </div>
    </div>
  );
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = "Confirm",
  danger,
  busy,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal open={open} onClose={onCancel} title={title} width="max-w-md">
      <p className="text-sm leading-relaxed text-ink-500">{message}</p>
      <div className="mt-6 flex justify-end gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={busy}
          className="rounded-full px-5 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50 disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={busy}
          className={`inline-flex items-center gap-2 rounded-full px-5 py-2 text-sm font-semibold text-white disabled:opacity-60 ${
            danger ? "bg-red-600 hover:bg-red-700" : "bg-brand hover:bg-brand-600"
          }`}
        >
          {busy && <Spinner className="h-4 w-4 text-white" />}
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}

const AVATAR_TONES = [
  "from-brand-400 to-brand-600",
  "from-accent to-accent-600",
  "from-brand-500 to-accent",
  "from-ink-500 to-ink-700",
];

export function Avatar({ name, email, size = "h-9 w-9" }: { name?: string | null; email?: string; size?: string }) {
  const label = (name || email || "?").trim();
  const initials = label
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase())
    .join("");
  let hash = 0;
  for (let i = 0; i < (email || label).length; i++) hash = (hash * 31 + (email || label).charCodeAt(i)) % 997;
  const tone = AVATAR_TONES[hash % AVATAR_TONES.length];
  return (
    <span
      className={`inline-flex ${size} flex-shrink-0 items-center justify-center rounded-full bg-gradient-to-br ${tone} text-xs font-semibold text-white`}
    >
      {initials || "?"}
    </span>
  );
}

export function RoleBadge({ role }: { role: string }) {
  const map: Record<string, string> = {
    USER: "bg-ink-100 text-ink-600",
    ADMIN: "bg-brand-100 text-brand-700",
    SUPER_ADMIN: "bg-accent/15 text-accent-600",
  };
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${map[role] ?? "bg-ink-100 text-ink-600"}`}>
      {role.replace("_", " ")}
    </span>
  );
}

export function StatusBadge({ status }: { status: string }) {
  const active = status === "ACTIVE";
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${
        active ? "bg-green-100 text-green-700" : "bg-red-100 text-red-700"
      }`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${active ? "bg-green-500" : "bg-red-500"}`} />
      {status}
    </span>
  );
}
