'use client';

import { FormEvent, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { AlertCircle, UserCircle } from 'lucide-react';
import AuthLayout from '@/components/dashboard/AuthLayout';
import RegisterDebugPanel, {
  type RegisterDebugInfo,
} from '@/components/dashboard/RegisterDebugPanel';
import { useAuth } from '@/lib/auth-context';
import { apiBaseURL } from '@/lib/api';

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
    <AuthLayout
      title="Create your account"
      subtitle="Sign up in seconds — you'll add the rest from your dashboard."
    >
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
            disabled={loading || !acceptedTos}
            className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
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
    </AuthLayout>
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
  return (
    <div>
      <label
        htmlFor={props.id}
        className="mb-1 block text-sm font-medium text-gray-700"
      >
        {props.label}
        {props.required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      <input
        id={props.id}
        type={props.type}
        required={props.required}
        autoComplete={props.autoComplete}
        minLength={props.minLength}
        placeholder={props.placeholder}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
      />
      {props.hint && (
        <p className="mt-1 text-xs text-gray-500">{props.hint}</p>
      )}
    </div>
  );
}
