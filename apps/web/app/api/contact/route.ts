import { NextResponse } from "next/server";
import { Resend } from "resend";

export const runtime = "nodejs";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type Payload = {
  name?: string;
  email?: string;
  subject?: string;
  message?: string;
  website?: string; // honeypot
};

export async function POST(req: Request) {
  let body: Payload;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON" }, { status: 400 });
  }

  // Honeypot — bots fill this; humans never see it.
  if (body.website && body.website.length > 0) {
    return NextResponse.json({ ok: true }, { status: 200 });
  }

  const name    = (body.name    ?? "").trim();
  const email   = (body.email   ?? "").trim();
  const subject = (body.subject ?? "").trim() || "New contact form submission";
  const message = (body.message ?? "").trim();

  if (!name || name.length < 2 || name.length > 120) {
    return NextResponse.json({ error: "Please provide a valid name." }, { status: 400 });
  }
  if (!email || !EMAIL_RE.test(email) || email.length > 200) {
    return NextResponse.json({ error: "Please provide a valid email address." }, { status: 400 });
  }
  if (!message || message.length < 5 || message.length > 5000) {
    return NextResponse.json({ error: "Please provide a message between 5 and 5000 characters." }, { status: 400 });
  }

  const apiKey  = process.env.RESEND_API_KEY;
  const mailTo  = process.env.MAIL_TO   ?? "info@anvicorp.com";
  const mailFrom = process.env.MAIL_FROM ?? "Anvi Corp Website <onboarding@resend.dev>";

  if (!apiKey) {
    console.error("RESEND_API_KEY is not set");
    return NextResponse.json(
      { error: "Email service is not configured. Please email info@anvicorp.com directly." },
      { status: 500 },
    );
  }

  const resend = new Resend(apiKey);

  const html = `
    <div style="font-family: system-ui, -apple-system, Segoe UI, sans-serif; color:#0F0D1D;">
      <h2 style="color:#2A8CDB; margin:0 0 16px;">New contact form submission</h2>
      <p style="margin:0 0 8px;"><strong>From:</strong> ${escapeHtml(name)} &lt;${escapeHtml(email)}&gt;</p>
      <p style="margin:0 0 16px;"><strong>Subject:</strong> ${escapeHtml(subject)}</p>
      <hr style="border:none; border-top:1px solid #EAEBED; margin:16px 0;" />
      <p style="white-space:pre-wrap; line-height:1.6; color:#33363A;">${escapeHtml(message)}</p>
    </div>`;

  const text =
    `From: ${name} <${email}>\n` +
    `Subject: ${subject}\n\n` +
    `${message}\n`;

  try {
    const { error } = await resend.emails.send({
      from: mailFrom,
      to: [mailTo],
      replyTo: email,
      subject: `[anvicorp.com] ${subject}`,
      html,
      text,
    });
    if (error) {
      console.error("Resend error:", error);
      return NextResponse.json({ error: "Failed to send. Please try again or email us directly." }, { status: 502 });
    }
  } catch (err) {
    console.error("Resend exception:", err);
    return NextResponse.json({ error: "Failed to send. Please try again or email us directly." }, { status: 502 });
  }

  return NextResponse.json({ ok: true });
}

function escapeHtml(s: string) {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
