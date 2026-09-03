'use client';

import { FormEvent, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import {
  AlertCircle,
  Briefcase,
  CheckCircle2,
  ClipboardList,
  FileCheck,
  UserCircle,
} from 'lucide-react';
import PasswordInput from '@/components/careers/PasswordInput';
import SiteFooter from '@/components/careers/SiteFooter';
import RegisterDebugPanel, {
  type RegisterDebugInfo,
} from '@/components/dashboard/RegisterDebugPanel';
import TurnstileWidget from '@/components/ui/TurnstileWidget';
import { useAuth } from '@/lib/careers/auth-context';
import { apiBaseURL } from '@/lib/careers/api';
import { BRAND } from '@/lib/careers/brand';

export default function RegisterPage() {
  return (
    <Suspense fallback={null}>
      <RegisterPageInner />
    </Suspense>
  );
}

function RegisterPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { register } = useAuth();

  const debugEnabled = useMemo(() => {
    if (process.env.NEXT_PUBLIC_DEBUG === 'true') return true;
    if (searchParams?.get('debug') === '1') return true;
    return false;
  }, [searchParams]);

  const [lastAttempt, setLastAttempt] = useState<RegisterDebugInfo['lastAttempt']>(null);

  // Approach 1 — signup collects only the 4 legal-essentials. Everything
  // else (phone, education, work-auth, skills, resume) is gathered on the
  // /careers/intern/profile/complete wizard after the user lands in the
  // dashboard. The apply endpoint guards on the same derived check, so the
  // intern can browse immediately but Apply stays locked until the editor
  // is finished.
  const [legalName, setLegalName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [acceptedTos, setAcceptedTos] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Bot-mitigation state.
  //  - captchaToken: Cloudflare Turnstile token. Empty when the widget
  //    hasn't produced one yet OR when NEXT_PUBLIC_TURNSTILE_SITE_KEY
  //    isn't configured (dev). The backend Turnstile verifier is
  //    defaulted OFF in the same environment so this passes end-to-end.
  //  - companyWebsite: honeypot. Real users never see or fill it
  //    (positioned off-screen, aria-hidden, tabindex=-1); naive bots
  //    fill every input by name. The server rejects any non-blank
  //    value in this field before any downstream work.
  const [captchaToken, setCaptchaToken] = useState('');
  const [companyWebsite, setCompanyWebsite] = useState('');
  const turnstileEnabled = Boolean(process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY);

  const errorRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (error && errorRef.current) {
      errorRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [error]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!legalName.trim()) {
      setError('Please enter your legal name as it appears on your government ID.');
      return;
    }
    if (password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    if (!acceptedTos) {
      setError('Please accept the Privacy Policy and Terms of Service to continue.');
      return;
    }
    // Client-side gate — when Turnstile is enabled in this deploy the
    // widget must have produced a token before we submit. Otherwise the
    // backend rejects the request with "human-verification challenge"
    // and the user sees the generic 400 without any actionable hint.
    if (turnstileEnabled && !captchaToken) {
      setError('Please complete the human-verification challenge below before continuing.');
      return;
    }

    setLoading(true);
    const startedAt = performance.now();
    const trimmedName = legalName.trim();
    const requestBody = {
      email,
      password: '***',
      fullName: trimmedName,
      // legalName mirrors fullName at signup — both columns get the same
      // value so the offer letter / compliance flows that key off legalName
      // don't break, and the dashboard / nav that read fullName render the
      // intern's name. The profile editor can split them later if needed.
      legalName: trimmedName,
      acceptedTos,
      // Included in the debug snapshot so a future "why did register 400"
      // triage sees whether the widget produced a token. The value itself
      // is masked (length only) — no point leaking the token to the log.
      captchaToken: captchaToken
        ? `present (${captchaToken.length} chars)` : '(empty)',
      companyWebsite: companyWebsite ? '(honeypot filled!)' : '(honeypot empty)',
    };

    // eslint-disable-next-line no-console
    console.group('[REGISTER_DEBUG] request');
    // eslint-disable-next-line no-console
    console.log('url', `${apiBaseURL}/auth/register`);
    // eslint-disable-next-line no-console
    console.log('method', 'POST');
    // eslint-disable-next-line no-console
    console.log('body (password masked)', requestBody);
    // eslint-disable-next-line no-console
    console.groupEnd();

    try {
      const user = await register(
        email,
        password,
        trimmedName,
        undefined,
        { legalName: trimmedName },
        acceptedTos,
        // captchaToken → RegisterRequest.captchaToken (verified server-side
        // by TurnstileVerifier). companyWebsite → RegisterRequest.companyWebsite
        // (honeypot — any non-blank value = bot signal, request rejected).
        { captchaToken, companyWebsite },
      );

      const durationMs = Math.round(performance.now() - startedAt);
      setLastAttempt({
        at: new Date().toISOString(),
        requestBody,
        status: 200,
        statusText: 'OK',
        responseBody: { userId: user.userId, email: user.email, emailVerified: user.emailVerified },
        errorMessage: null,
        errorCode: null,
        errorClass: null,
        durationMs,
      });

      if (user.emailVerified === false || user.emailVerified === undefined) {
        const params = new URLSearchParams({ email: user.email });
        const returnTo = safeReturnTo();
        if (returnTo) params.set('returnTo', returnTo);
        router.replace(`/careers/verify-email?${params.toString()}`);
        return;
      }
      const returnTo = safeReturnTo();
      router.replace(returnTo ?? '/careers/intern');
    } catch (err: unknown) {
      const durationMs = Math.round(performance.now() - startedAt);
      const classified = classifyRegistrationError(err, `${apiBaseURL}/auth/register`);
      setError(classified.userMessage);
      setLastAttempt({
        at: new Date().toISOString(),
        requestBody,
        status: classified.status,
        statusText: classified.statusText,
        responseBody: classified.responseBody,
        errorMessage: classified.errorMessage,
        errorCode: classified.errorCode,
        errorClass: classified.errorClass,
        durationMs,
      });
    } finally {
      setLoading(false);
    }
  }

  function safeReturnTo(): string | null {
    if (typeof window === 'undefined') return null;
    const raw = new URLSearchParams(window.location.search).get('returnTo');
    if (!raw) return null;
    const decoded = decodeURIComponent(raw);
    if (!decoded.startsWith('/') || decoded.startsWith('//')) return null;
    return decoded;
  }

  return (
    // W5 #1 — Bespoke two-column shell for /register only. The
    // shared AuthLayout (login, forgot, reset, verify) stays
    // untouched — this page opts out because the "start your career"
    // signup benefits from a brand+value column that would look off
    // on the shorter auth flows. Mobile collapses to the form only;
    // desktop shows both sides. Every field + the honeypot + the
    // TurnstileWidget + the submit gate below are 100% preserved.
    <div className="flex min-h-screen flex-col bg-gradient-to-br from-gray-50 to-gray-100">
      <main className="flex flex-1 items-stretch justify-center p-4 py-8">
        <div className="mx-auto grid w-full max-w-6xl overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-lg lg:grid-cols-12">
          {/* Left column — brand + value proposition. Desktop only. */}
          <aside className="hidden bg-gradient-to-br from-primary-700 to-primary-800 p-10 text-white lg:col-span-5 lg:flex lg:flex-col lg:justify-between">
            <div>
              {/* Dark-navy aside: use the same white-pill treatment
                  SiteFooter uses so a dark-wordmark logo (Anvi's) stays
                  visible on the dark background. The previous
                  `brightness-0 invert` filter assumed the logo was a
                  black-on-transparent silhouette — Anvi's logo is a
                  full-color wordmark, so the filter mashed it into a
                  solid white rectangle. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={BRAND.logoUrl}
                alt={BRAND.name}
                className="h-10 w-auto rounded-md bg-white/95 px-2 py-1.5"
              />
              <h2 className="mt-10 text-3xl font-semibold leading-tight">
                Start your {BRAND.name} career
              </h2>
              <p className="mt-3 text-sm leading-relaxed text-white/80">
                Create an account to apply to open internships, track your
                application status live, and complete onboarding from one
                dashboard.
              </p>
              <ul className="mt-8 space-y-4">
                <ValueBullet
                  icon={<Briefcase className="h-4 w-4" />}
                  title="Apply to open internships"
                  desc="Browse the openings and submit in a few clicks."
                />
                <ValueBullet
                  icon={<ClipboardList className="h-4 w-4" />}
                  title="Track your status live"
                  desc="See where your application is in the pipeline."
                />
                <ValueBullet
                  icon={<FileCheck className="h-4 w-4" />}
                  title="Onboard from your dashboard"
                  desc="Sign your offer, complete forms, all in one place."
                />
              </ul>
            </div>
            <p className="mt-10 text-sm text-white/70">
              Already have an account?{' '}
              <Link
                href="/careers/login"
                className="font-semibold text-white underline-offset-4 hover:underline"
              >
                Sign in
              </Link>
            </p>
          </aside>

          {/* Right column — the form. All fields, the honeypot, and the
              Turnstile widget below are UNCHANGED from the pre-redesign
              layout. Only the outer shell moved from AuthLayout to this
              split. */}
          <section className="p-6 sm:p-10 lg:col-span-7">
            {/* Mobile-only brand — the aside is hidden below lg, so
                the intern sees this at the top instead. */}
            <div className="mb-6 flex flex-col items-center lg:hidden">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={BRAND.logoUrl}
                alt={BRAND.name}
                className="h-10 w-auto"
              />
            </div>
            <div className="mb-6">
              <h1 className="text-2xl font-semibold text-gray-900 sm:text-3xl">
                Create your account
              </h1>
              <p className="mt-1 text-sm text-gray-500">
                Sign up in seconds — you&apos;ll add the rest from your
                dashboard.
              </p>
            </div>
      <form onSubmit={onSubmit} className="space-y-6">
        <section className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5">
          <header className="flex items-start gap-3">
            <span className="rounded-md bg-accent/10 p-1.5 text-accent">
              <UserCircle className="h-4 w-4" />
            </span>
            <div>
              <h3 className="text-sm font-semibold text-gray-900">Your account</h3>
              <p className="mt-0.5 text-xs text-gray-500">
                We'll collect the rest (school, skills, resume) right after sign-up so you can start applying.
              </p>
            </div>
          </header>
          <div className="space-y-3">
            <Field
              id="legalName"
              label="Legal name"
              type="text"
              value={legalName}
              onChange={setLegalName}
              required
              autoComplete="name"
              hint="As per your government ID"
            />
            <Field
              id="email"
              label="Email"
              type="email"
              value={email}
              onChange={setEmail}
              required
              autoComplete="email"
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field
                id="password"
                label="Password"
                type="password"
                value={password}
                onChange={setPassword}
                required
                autoComplete="new-password"
                minLength={8}
                hint="At least 8 characters"
              />
              <Field
                id="confirmPassword"
                label="Confirm"
                type="password"
                value={confirmPassword}
                onChange={setConfirmPassword}
                required
                autoComplete="new-password"
              />
            </div>
          </div>
        </section>

        <div className="space-y-4 rounded-xl border border-gray-200 bg-gray-50/60 p-5">
          <label className="flex items-start gap-2 text-sm text-gray-700">
            <input
              type="checkbox"
              checked={acceptedTos}
              onChange={(e) => setAcceptedTos(e.target.checked)}
              className="mt-0.5 h-4 w-4 cursor-pointer rounded border-gray-300 text-accent focus:ring-accent"
              aria-required="true"
            />
            <span>
              I agree to the{' '}
              <Link
                href="/privacy"
                target="_blank"
                className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
              >
                Privacy Policy
              </Link>{' '}
              and{' '}
              <Link
                href="/terms"
                target="_blank"
                className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
              >
                Terms of Service
              </Link>
              .
            </span>
          </label>

          {/* Honeypot — real users never see it (off-screen + tabindex=-1 +
              aria-hidden + autocomplete=off). Naive form-filling bots
              fill every input by name; the server rejects any non-blank
              value in this field before any downstream work. Kept inside
              the form so the browser autofill / a11y tree behaviours
              stay consistent. */}
          <div
            aria-hidden="true"
            style={{
              position: 'absolute',
              left: '-9999px',
              width: 1,
              height: 1,
              overflow: 'hidden',
            }}
          >
            <label htmlFor="companyWebsite">Company website</label>
            <input
              id="companyWebsite"
              name="companyWebsite"
              type="text"
              tabIndex={-1}
              autoComplete="off"
              value={companyWebsite}
              onChange={(e) => setCompanyWebsite(e.target.value)}
            />
          </div>

          {/* Turnstile challenge — renders when NEXT_PUBLIC_TURNSTILE_SITE_KEY
              is configured; hidden no-op otherwise. onToken updates
              captchaToken; the widget's own expired-callback resets to
              empty so submit re-disables and the user re-solves. */}
          {turnstileEnabled && (
            <div className="pt-1">
              <TurnstileWidget onToken={setCaptchaToken} />
            </div>
          )}

          {error && (
            <div
              ref={errorRef}
              role="alert"
              className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700"
            >
              <AlertCircle
                className="mt-0.5 h-4 w-4 shrink-0"
                strokeWidth={2}
              />
              <p>{error}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={loading || !acceptedTos || (turnstileEnabled && !captchaToken)}
            className="w-full rounded-full bg-accent hover:bg-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
          >
            {loading ? 'Creating account…' : 'Create account'}
          </button>
        </div>
      </form>
      <div className="mt-6 text-center text-sm">
        <Link
          href="/careers/login"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Already have an account? Sign in
        </Link>
      </div>

      {debugEnabled && (
        <RegisterDebugPanel
          info={{
            apiBaseURL,
            registerUrl: `${apiBaseURL}/auth/register`,
            method: 'POST',
            envApiUrl: process.env.NEXT_PUBLIC_API_URL,
            envDebug: process.env.NEXT_PUBLIC_DEBUG,
            lastAttempt,
          }}
        />
      )}
          </section>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}

/**
 * W5 #1 — Bullet used in the left brand column of the split shell.
 * Icon tile + short title + one-line description. Kept co-located
 * with the page so this is self-contained; the split is only used
 * here, no other auth flow adopts it.
 */
function ValueBullet({
  icon,
  title,
  desc,
}: {
  icon: React.ReactNode;
  title: string;
  desc: string;
}) {
  return (
    <li className="flex items-start gap-3">
      <span
        className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/10 text-white"
        aria-hidden
      >
        {icon}
      </span>
      <div>
        <p className="text-sm font-semibold text-white">{title}</p>
        <p className="mt-0.5 text-xs text-white/70">{desc}</p>
      </div>
    </li>
  );
}

interface ClassifiedError {
  userMessage: string;
  status: number | string | null;
  statusText: string | null;
  responseBody: unknown;
  errorMessage: string;
  errorCode: string | null;
  errorClass: 'NETWORK' | 'DNS' | 'TIMEOUT' | 'CLIENT' | 'SERVER' | 'UNKNOWN';
}

interface AxiosLikeError {
  message?: string;
  code?: string;
  config?: { url?: string };
  response?: {
    status?: number;
    statusText?: string;
    data?: unknown;
  };
  request?: unknown;
}

function classifyRegistrationError(err: unknown, fullUrl: string): ClassifiedError {
  const e = (err ?? {}) as AxiosLikeError;
  const rawMessage = e.message ?? String(err);
  const code = e.code ?? null;
  const response = e.response;

  if (response && typeof response.status === 'number') {
    const status = response.status;
    const statusText = response.statusText ?? null;
    const body = response.data;
    const backendMsg =
      (typeof body === 'object' && body !== null && 'error' in body && typeof (body as Record<string, unknown>).error === 'string'
        ? ((body as Record<string, unknown>).error as string)
        : null);
    if (status >= 400 && status < 500) {
      return {
        userMessage: backendMsg ?? `Registration rejected (${status}). ${statusText ?? ''}`.trim(),
        status,
        statusText,
        responseBody: body,
        errorMessage: rawMessage,
        errorCode: code,
        errorClass: 'CLIENT',
      };
    }
    if (status >= 500) {
      const bodyStr = typeof body === 'string' ? body : JSON.stringify(body ?? '');
      return {
        userMessage: `Server error. Status: ${status}. ${bodyStr.slice(0, 200) || backendMsg || statusText || 'Try again shortly.'}`,
        status,
        statusText,
        responseBody: body,
        errorMessage: rawMessage,
        errorCode: code,
        errorClass: 'SERVER',
      };
    }
    return {
      userMessage: backendMsg ?? `Unexpected response (${status}).`,
      status,
      statusText,
      responseBody: body,
      errorMessage: rawMessage,
      errorCode: code,
      errorClass: 'UNKNOWN',
    };
  }

  if (code === 'ECONNABORTED' || /timeout/i.test(rawMessage)) {
    const match = rawMessage.match(/timeout of (\d+)ms/);
    const seconds = match ? Math.round(Number(match[1]) / 1000) : null;
    return {
      userMessage: seconds != null
        ? `Request timed out after ${seconds}s. Server is unreachable or slow.`
        : 'Request timed out. Server is unreachable or slow.',
      status: 'Timeout',
      statusText: null,
      responseBody: null,
      errorMessage: rawMessage,
      errorCode: code,
      errorClass: 'TIMEOUT',
    };
  }

  const looksLikeDns =
    /name.?not.?resolved|getaddrinfo|enotfound|err_name_not_resolved/i.test(rawMessage);
  if (looksLikeDns) {
    return {
      userMessage: `Cannot reach server. URL: ${fullUrl}. Check your network or contact admin.`,
      status: 'DNS Error',
      statusText: null,
      responseBody: null,
      errorMessage: rawMessage,
      errorCode: code,
      errorClass: 'DNS',
    };
  }

  if (e.request || /network error|failed to fetch|err_connection|err_internet/i.test(rawMessage)) {
    return {
      userMessage: `Cannot reach server. URL: ${fullUrl}. Check your network or contact admin.`,
      status: 'Network Error',
      statusText: null,
      responseBody: null,
      errorMessage: rawMessage,
      errorCode: code,
      errorClass: 'NETWORK',
    };
  }

  return {
    userMessage: rawMessage || 'Registration failed.',
    status: null,
    statusText: null,
    responseBody: null,
    errorMessage: rawMessage,
    errorCode: code,
    errorClass: 'UNKNOWN',
  };
}

interface FieldProps {
  id: string;
  label: string;
  type: string;
  value: string;
  onChange: (v: string) => void;
  required?: boolean;
  autoComplete?: string;
  minLength?: number;
  placeholder?: string;
  hint?: string;
}

function Field(props: FieldProps) {
  const inputClass =
    'w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent';
  return (
    <div>
      <label
        htmlFor={props.id}
        className="mb-1 block text-sm font-medium text-gray-700"
      >
        {props.label}
        {props.required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {props.type === 'password' ? (
        <PasswordInput
          id={props.id}
          required={props.required}
          autoComplete={props.autoComplete}
          minLength={props.minLength}
          placeholder={props.placeholder}
          value={props.value}
          onChange={(e) => props.onChange(e.target.value)}
          className={inputClass}
        />
      ) : (
        <input
          id={props.id}
          type={props.type}
          required={props.required}
          autoComplete={props.autoComplete}
          minLength={props.minLength}
          placeholder={props.placeholder}
          value={props.value}
          onChange={(e) => props.onChange(e.target.value)}
          className={inputClass}
        />
      )}
      {props.hint && (
        <p className="mt-1 text-xs text-gray-500">{props.hint}</p>
      )}
    </div>
  );
}
