"use client";

import { useState } from "react";
import { Send, CheckCircle2, AlertCircle } from "lucide-react";

type Status =
  | { kind: "idle" }
  | { kind: "submitting" }
  | { kind: "success" }
  | { kind: "error"; message: string };

export function ContactForm() {
  const [status, setStatus] = useState<Status>({ kind: "idle" });

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setStatus({ kind: "submitting" });

    const data = new FormData(e.currentTarget);
    const payload = {
      name:    String(data.get("name")    ?? "").trim(),
      email:   String(data.get("email")   ?? "").trim(),
      subject: String(data.get("subject") ?? "").trim(),
      message: String(data.get("message") ?? "").trim(),
      website: String(data.get("website") ?? ""),  // honeypot
    };

    try {
      const res = await fetch("/api/contact", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(json?.error ?? "Something went wrong.");
      setStatus({ kind: "success" });
      (e.target as HTMLFormElement).reset();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Something went wrong.";
      setStatus({ kind: "error", message });
    }
  }

  if (status.kind === "success") {
    return (
      <div className="flex flex-col items-center text-center py-10">
        <CheckCircle2 className="h-12 w-12 text-brand mb-4" />
        <h3 className="text-2xl mb-2">Message sent</h3>
        <p className="text-ink-400 mb-6">Thanks for getting in touch — we&apos;ll reply within one business day.</p>
        <button
          type="button"
          onClick={() => setStatus({ kind: "idle" })}
          className="text-sm font-semibold text-brand hover:text-brand-600"
        >
          Send another message
        </button>
      </div>
    );
  }

  const submitting = status.kind === "submitting";

  return (
    <form onSubmit={onSubmit} className="space-y-5" noValidate>
      <Field label="Full name" name="name" required type="text" autoComplete="name" />
      <Field label="Email"     name="email" required type="email" autoComplete="email" />
      <Field label="Subject"   name="subject" type="text" />
      <div>
        <label htmlFor="message" className="mb-1.5 block text-sm font-medium text-ink-800">
          Message <span className="text-brand">*</span>
        </label>
        <textarea
          id="message" name="message" required rows={5}
          className="block w-full rounded-xl border border-ink-100 bg-white px-4 py-3 text-base text-ink-800 placeholder:text-ink-300 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
          placeholder="Tell us about your project…"
        />
      </div>

      {/* Honeypot — bots fill this, humans never see it */}
      <div className="absolute -left-[9999px]" aria-hidden="true">
        <label>
          Website
          <input type="text" name="website" tabIndex={-1} autoComplete="off" />
        </label>
      </div>

      {status.kind === "error" && (
        <div className="flex gap-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          <AlertCircle className="h-5 w-5 flex-shrink-0 text-red-500" />
          <p>{status.message}</p>
        </div>
      )}

      <button
        type="submit"
        disabled={submitting}
        className="inline-flex w-full items-center justify-center gap-2 rounded-full bg-brand px-7 py-4 font-semibold text-white shadow-card hover:bg-brand-600 hover:shadow-cardHover transition disabled:opacity-60 disabled:pointer-events-none"
      >
        {submitting ? "Sending…" : <>Send message <Send className="h-4 w-4" /></>}
      </button>

      <p className="text-xs text-ink-300 text-center">
        We typically reply within one business day.
      </p>
    </form>
  );
}

function Field({ label, name, type, required, autoComplete }: {
  label: string; name: string; type: string; required?: boolean; autoComplete?: string;
}) {
  return (
    <div>
      <label htmlFor={name} className="mb-1.5 block text-sm font-medium text-ink-800">
        {label} {required && <span className="text-brand">*</span>}
      </label>
      <input
        id={name} name={name} type={type} required={required} autoComplete={autoComplete}
        className="block w-full rounded-xl border border-ink-100 bg-white px-4 py-3 text-base text-ink-800 placeholder:text-ink-300 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
      />
    </div>
  );
}
