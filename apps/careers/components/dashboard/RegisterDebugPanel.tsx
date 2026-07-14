'use client';

import { useState } from 'react';
import {
  Activity,
  ChevronDown,
  ChevronRight,
  ClipboardCopy,
  PlayCircle,
} from 'lucide-react';

export interface RegisterDebugInfo {
  apiBaseURL: string;
  registerUrl: string;
  method: 'POST';
  envApiUrl: string | undefined;
  envDebug: string | undefined;
  lastAttempt: {
    at: string;
    requestBody: Record<string, unknown>;
    status: string | number | null;
    statusText: string | null;
    responseBody: unknown;
    errorMessage: string | null;
    errorCode: string | null;
    errorClass: string | null;
    durationMs: number | null;
  } | null;
}

interface Props {
  info: RegisterDebugInfo;
}

interface HealthCheckResult {
  at: string;
  endpoint: string;
  ok: boolean;
  status: number | null;
  body: string;
  errorMessage: string | null;
}

export default function RegisterDebugPanel({ info }: Props) {
  const [open, setOpen] = useState(true);
  const [copied, setCopied] = useState(false);
  const [healthChecking, setHealthChecking] = useState(false);
  const [healthResult, setHealthResult] = useState<HealthCheckResult | null>(null);

  async function copyJson() {
    try {
      await navigator.clipboard.writeText(JSON.stringify(info, null, 2));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard blocked — non-fatal */
    }
  }

  async function testApiConnection() {
    setHealthChecking(true);
    const candidates = ['/actuator/health', '/api/v1/health'];
    let result: HealthCheckResult | null = null;
    for (const path of candidates) {
      const endpoint = info.apiBaseURL.replace(/\/+$/, '') + path;
      const started = performance.now();
      try {
        const res = await fetch(endpoint, { method: 'GET', mode: 'cors' });
        const text = await res.text().catch(() => '');
        result = {
          at: new Date().toISOString(),
          endpoint,
          ok: res.ok,
          status: res.status,
          body: text.slice(0, 500),
          errorMessage: null,
        };
        // eslint-disable-next-line no-console
        console.group('[REGISTER_DEBUG] testApiConnection');
        // eslint-disable-next-line no-console
        console.log('endpoint', endpoint);
        // eslint-disable-next-line no-console
        console.log('status', res.status, res.statusText);
        // eslint-disable-next-line no-console
        console.log('body', text);
        // eslint-disable-next-line no-console
        console.log('elapsed', performance.now() - started, 'ms');
        // eslint-disable-next-line no-console
        console.groupEnd();
        if (res.ok || res.status < 500) break;
      } catch (e) {
        const err = e as Error;
        result = {
          at: new Date().toISOString(),
          endpoint,
          ok: false,
          status: null,
          body: '',
          errorMessage: err.message,
        };
        // eslint-disable-next-line no-console
        console.group('[REGISTER_DEBUG] testApiConnection error');
        // eslint-disable-next-line no-console
        console.error('endpoint', endpoint);
        // eslint-disable-next-line no-console
        console.error('error', err);
        // eslint-disable-next-line no-console
        console.groupEnd();
      }
    }
    setHealthResult(result);
    setHealthChecking(false);
  }

  const statusTone =
    info.lastAttempt == null
      ? 'text-slate-500'
      : typeof info.lastAttempt.status === 'number'
        ? info.lastAttempt.status >= 200 && info.lastAttempt.status < 300
          ? 'text-green-700'
          : info.lastAttempt.status >= 400 && info.lastAttempt.status < 500
            ? 'text-amber-700'
            : 'text-red-700'
        : 'text-red-700';

  return (
    <section className="mt-8 rounded-lg border border-slate-300 bg-slate-50 font-mono text-[11px] text-slate-800">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-300 bg-slate-100 px-3 py-2">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="inline-flex items-center gap-1.5 font-semibold uppercase tracking-wide text-slate-700 hover:text-slate-900"
        >
          {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
          <Activity className="h-3.5 w-3.5" />
          Register Debug Panel
        </button>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => void testApiConnection()}
            disabled={healthChecking}
            className="inline-flex items-center gap-1 rounded border border-slate-300 bg-white px-2 py-0.5 text-[10px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            <PlayCircle className="h-3 w-3" />
            {healthChecking ? 'Testing…' : 'Test API Connection'}
          </button>
          <button
            type="button"
            onClick={() => void copyJson()}
            className="inline-flex items-center gap-1 rounded border border-slate-300 bg-white px-2 py-0.5 text-[10px] font-semibold text-slate-700 hover:bg-slate-50"
          >
            <ClipboardCopy className="h-3 w-3" />
            {copied ? 'Copied!' : 'Copy JSON'}
          </button>
        </div>
      </header>

      {open && (
        <div className="space-y-3 p-3">
          <DefList
            rows={[
              ['NEXT_PUBLIC_API_URL', info.envApiUrl ?? '(unset → falls back to http://localhost:8080)'],
              ['NEXT_PUBLIC_DEBUG', info.envDebug ?? '(unset)'],
              ['Resolved apiBaseURL', info.apiBaseURL],
              ['Computed register URL', info.registerUrl],
              ['HTTP method', info.method],
            ]}
          />

          <div>
            <p className="mb-1 font-semibold uppercase tracking-wide text-slate-600">Last attempt</p>
            {info.lastAttempt == null ? (
              <p className="rounded border border-slate-200 bg-white px-2 py-1.5 text-slate-500">
                No attempts yet — submit the form to populate.
              </p>
            ) : (
              <div className="space-y-2">
                <DefList
                  rows={[
                    ['Timestamp', info.lastAttempt.at],
                    [
                      'Status',
                      <span key="s" className={statusTone}>
                        {info.lastAttempt.status ?? '—'}
                        {info.lastAttempt.statusText && ` · ${info.lastAttempt.statusText}`}
                      </span>,
                    ],
                    ['Duration (ms)', info.lastAttempt.durationMs ?? '—'],
                    ['Error class', info.lastAttempt.errorClass ?? '—'],
                    ['Error code', info.lastAttempt.errorCode ?? '—'],
                  ]}
                />
                {info.lastAttempt.errorMessage && (
                  <Block label="Error message" tone="rose">
                    {info.lastAttempt.errorMessage}
                  </Block>
                )}
                <Block label="Request body (password masked)">
                  {JSON.stringify(info.lastAttempt.requestBody, null, 2)}
                </Block>
                {info.lastAttempt.responseBody != null && (
                  <Block label="Response body">
                    {typeof info.lastAttempt.responseBody === 'string'
                      ? info.lastAttempt.responseBody
                      : JSON.stringify(info.lastAttempt.responseBody, null, 2)}
                  </Block>
                )}
              </div>
            )}
          </div>

          {healthResult && (
            <div>
              <p className="mb-1 font-semibold uppercase tracking-wide text-slate-600">
                Last API connection test
              </p>
              <DefList
                rows={[
                  ['Timestamp', healthResult.at],
                  ['Endpoint', healthResult.endpoint],
                  [
                    'OK',
                    <span key="ok" className={healthResult.ok ? 'text-green-700' : 'text-red-700'}>
                      {String(healthResult.ok)}
                    </span>,
                  ],
                  ['Status', healthResult.status ?? '—'],
                ]}
              />
              {healthResult.errorMessage && (
                <Block label="Error" tone="rose">{healthResult.errorMessage}</Block>
              )}
              {healthResult.body && <Block label="Body">{healthResult.body}</Block>}
            </div>
          )}

          <p className="text-[10px] text-slate-500">
            Panel visible because NEXT_PUBLIC_DEBUG is truthy OR ?debug=1 is in the URL. Hidden in
            production by default. Console logs grouped under [REGISTER_DEBUG] prefix.
          </p>
        </div>
      )}
    </section>
  );
}

function DefList({ rows }: { rows: Array<[string, React.ReactNode]> }) {
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5 rounded border border-slate-200 bg-white px-2 py-1.5">
      {rows.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-slate-500">{k}</dt>
          <dd className="break-all text-slate-900">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

function Block({
  label,
  children,
  tone = 'slate',
}: {
  label: string;
  children: React.ReactNode;
  tone?: 'slate' | 'rose';
}) {
  const cls =
    tone === 'rose'
      ? 'border-red-300 bg-red-50 text-red-900'
      : 'border-slate-200 bg-white text-slate-900';
  return (
    <div>
      <p className="mb-0.5 text-[10px] uppercase tracking-wide text-slate-500">{label}</p>
      <pre className={`max-h-48 overflow-auto whitespace-pre-wrap break-all rounded border px-2 py-1.5 ${cls}`}>
        {children}
      </pre>
    </div>
  );
}
