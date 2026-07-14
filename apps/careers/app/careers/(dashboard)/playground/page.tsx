'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Loader2,
  Play,
  Terminal,
  XCircle,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';

/**
 * Coding playground. Source flows to Judge0 via the backend
 * /api/v1/playground/run + /languages endpoints — nothing executes in
 * the browser.
 */
const MonacoEditor = dynamic(() => import('@monaco-editor/react'), {
  ssr: false,
  loading: () => (
    <div className="flex h-full items-center justify-center text-sm text-gray-400">
      Loading editor…
    </div>
  ),
});

interface Language {
  id: number;
  name: string;
}

interface RunResult {
  stdout?: string;
  stderr?: string;
  compileOutput?: string;
  statusId: number;
  statusDescription?: string;
  time?: string;
  memory?: number;
  exitCode?: number;
}

const DEFAULT_SAMPLES: Record<number, string> = {
  71: "print('Hello, Skyzen!')\n",            // Python (3.8.1)
  62: 'public class Main {\n  public static void main(String[] args) {\n    System.out.println(2 + 3);\n  }\n}\n', // Java (OpenJDK 13)
  63: "console.log('Hello, Skyzen!');\n",     // JavaScript (Node 12)
  54: '#include <stdio.h>\nint main() {\n  printf("Hello, Skyzen!\\n");\n  return 0;\n}\n', // C++ (GCC 9)
  60: 'package main\nimport "fmt"\nfunc main() { fmt.Println("Hello, Skyzen!") }\n', // Go
};

export default function PlaygroundPage() {
  return (
    <ProtectedRoute>
      <DashboardLayout title="Coding Playground">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const [languages, setLanguages] = useState<Language[]>([]);
  const [languageId, setLanguageId] = useState<number | null>(null);
  const [sourceCode, setSourceCode] = useState<string>(
    "print('Hello, Skyzen!')\n",
  );
  const [stdin, setStdin] = useState<string>('');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<RunResult | null>(null);
  const [langError, setLangError] = useState<string | null>(null);

  // Track which source the user last manually-set vs. seeded from a
  // template — only seed a new template if the editor still holds the
  // previous template (no user edits).
  const seededSourceRef = useRef<string>("print('Hello, Skyzen!')\n");

  // ── Load languages once on mount ───────────────────────────────────────

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<Language[]>('/api/v1/playground/languages');
        const list = res.data ?? [];
        setLanguages(list);
        if (list.length === 0) {
          setLangError(
            'No languages available — sandbox may not be configured. '
            + 'Set JUDGE0_RAPIDAPI_KEY on the backend.',
          );
        } else {
          // Prefer Python (id=71) as the default; else the first row.
          const preferred = list.find((l) => l.id === 71) ?? list[0];
          setLanguageId(preferred.id);
          const sample = DEFAULT_SAMPLES[preferred.id];
          if (sample) {
            setSourceCode(sample);
            seededSourceRef.current = sample;
          }
        }
      } catch (err: any) {
        setLangError(
          err?.response?.data?.error
          ?? "Couldn't load the language list — check your connection.",
        );
      }
    })();
  }, []);

  // ── Language switcher seeds a sample if the editor is still untouched ──

  const onLanguageChange = useCallback(
    (next: number) => {
      setLanguageId(next);
      const sample = DEFAULT_SAMPLES[next];
      if (sample && sourceCode === seededSourceRef.current) {
        setSourceCode(sample);
        seededSourceRef.current = sample;
      }
    },
    [sourceCode],
  );

  // ── Run ────────────────────────────────────────────────────────────────

  async function runCode() {
    if (!languageId || running) return;
    if (!sourceCode.trim()) {
      toast.error('Write some code first.');
      return;
    }
    setRunning(true);
    setResult(null);
    try {
      const res = await api.post<RunResult>('/api/v1/playground/run', {
        sourceCode,
        languageId,
        stdin: stdin.length > 0 ? stdin : undefined,
      });
      setResult(res.data);
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't run the code.");
    } finally {
      setRunning(false);
    }
  }

  const languageForMonaco = useMemo(
    () => monacoLanguageOf(languageId, languages),
    [languageId, languages],
  );

  return (
    <section className="flex h-[calc(100vh-180px)] flex-col gap-3">
      {/* Top bar */}
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold text-gray-900">
            Coding Playground
          </h1>
          <p className="mt-1 text-xs text-gray-600">
            Run code in a sandboxed environment. Source executes on Judge0,
            never in your browser or our server.
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <div>
            <label className="mb-1 block text-[11px] font-medium text-gray-700">
              Language
            </label>
            <select
              value={languageId ?? ''}
              onChange={(e) => onLanguageChange(Number(e.target.value))}
              disabled={languages.length === 0 || running}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-60"
            >
              {languages.length === 0 ? (
                <option value="">No languages</option>
              ) : (
                languages.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))
              )}
            </select>
          </div>
          <button
            type="button"
            onClick={() => void runCode()}
            disabled={running || !languageId}
            className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-4 py-2 text-sm font-semibold text-white hover:bg-green-700 disabled:opacity-60"
          >
            {running ? (
              <Loader2 className="h-4 w-4 animate-spin" strokeWidth={2.5} />
            ) : (
              <Play className="h-4 w-4" strokeWidth={2.5} />
            )}
            {running ? 'Running…' : 'Run code'}
          </button>
        </div>
      </header>

      {langError && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          {langError}
        </div>
      )}

      {/* Editor + IO panes */}
      <div className="flex flex-1 min-h-0 gap-3">
        {/* Editor (left) */}
        <div className="flex flex-1 min-w-0 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white">
          <div className="border-b border-gray-200 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
            Source
          </div>
          <div className="flex-1 min-h-0">
            <MonacoEditor
              height="100%"
              language={languageForMonaco}
              value={sourceCode}
              onChange={(v) => setSourceCode(v ?? '')}
              options={{
                minimap: { enabled: false },
                fontSize: 13,
                scrollBeyondLastLine: false,
                automaticLayout: true,
              }}
              theme="vs-light"
            />
          </div>
        </div>

        {/* IO panes (right) */}
        <aside className="flex w-[420px] shrink-0 flex-col gap-3">
          {/* stdin */}
          <div className="flex flex-col overflow-hidden rounded-lg border border-gray-200 bg-white">
            <div className="border-b border-gray-200 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
              Stdin (optional)
            </div>
            <textarea
              value={stdin}
              onChange={(e) => setStdin(e.target.value)}
              placeholder="Feed input lines here…"
              className="h-28 resize-none border-0 px-3 py-2 text-sm font-mono focus:outline-none"
            />
          </div>

          {/* Output */}
          <div className="flex flex-1 min-h-0 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white">
            <div className="flex items-center justify-between border-b border-gray-200 px-3 py-1.5">
              <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
                <Terminal className="h-3.5 w-3.5" strokeWidth={2} />
                Output
              </div>
              {result && <StatusBadge result={result} />}
            </div>
            <div className="flex-1 min-h-0 overflow-y-auto px-3 py-2 font-mono text-xs">
              {!result ? (
                <p className="italic text-gray-400">
                  Run the code to see the output here.
                </p>
              ) : (
                <OutputBlocks result={result} />
              )}
            </div>
            {result && (result.time || result.memory != null) && (
              <div className="border-t border-gray-100 px-3 py-1.5 text-[11px] text-gray-500">
                {result.time && <span>{result.time}s</span>}
                {result.time && result.memory != null && <span> · </span>}
                {result.memory != null && <span>{result.memory} KB</span>}
                {result.exitCode != null && (
                  <span> · exit {result.exitCode}</span>
                )}
              </div>
            )}
          </div>
        </aside>
      </div>
    </section>
  );
}

// ── Status + output rendering ──────────────────────────────────────────────

function StatusBadge({ result }: { result: RunResult }) {
  const { statusId, statusDescription } = result;
  const label = statusDescription ?? `Status ${statusId}`;
  if (statusId === 3) {
    return (
      <Badge tone="success" icon={<CheckCircle2 className="h-3 w-3" strokeWidth={2.5} />}>
        {label}
      </Badge>
    );
  }
  if (statusId === 6 || statusId === 4 || statusId === 5) {
    return (
      <Badge tone="warn" icon={<AlertTriangle className="h-3 w-3" strokeWidth={2.5} />}>
        {label}
      </Badge>
    );
  }
  if (statusId >= 7 && statusId <= 12) {
    return (
      <Badge tone="error" icon={<XCircle className="h-3 w-3" strokeWidth={2.5} />}>
        {label}
      </Badge>
    );
  }
  if (statusId < 0) {
    // Our sentinel statuses — quota / poll-timeout / unavailable / not-configured.
    return (
      <Badge tone="warn" icon={<Clock className="h-3 w-3" strokeWidth={2.5} />}>
        {label}
      </Badge>
    );
  }
  return (
    <Badge tone="neutral" icon={<Terminal className="h-3 w-3" strokeWidth={2.5} />}>
      {label}
    </Badge>
  );
}

function Badge({
  tone,
  icon,
  children,
}: {
  tone: 'success' | 'warn' | 'error' | 'neutral';
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  const palette: Record<typeof tone, string> = {
    success: 'bg-green-100 text-green-800',
    warn: 'bg-amber-100 text-amber-800',
    error: 'bg-red-100 text-red-800',
    neutral: 'bg-gray-100 text-gray-700',
  };
  return (
    <span
      className={
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide '
        + palette[tone]
      }
    >
      {icon}
      {children}
    </span>
  );
}

function OutputBlocks({ result }: { result: RunResult }) {
  const hasAny =
    !!result.stdout || !!result.stderr || !!result.compileOutput;
  if (!hasAny) {
    return <p className="italic text-gray-400">No output.</p>;
  }
  return (
    <div className="space-y-3">
      {result.compileOutput && (
        <div>
          <div className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-amber-700">
            Compile output
          </div>
          <pre className="whitespace-pre-wrap text-amber-900">
            {result.compileOutput}
          </pre>
        </div>
      )}
      {result.stderr && (
        <div>
          <div className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-red-700">
            Stderr
          </div>
          <pre className="whitespace-pre-wrap text-red-900">{result.stderr}</pre>
        </div>
      )}
      {result.stdout && (
        <div>
          <div className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-500">
            Stdout
          </div>
          <pre className="whitespace-pre-wrap text-gray-900">{result.stdout}</pre>
        </div>
      )}
    </div>
  );
}

// ── Monaco language picker ────────────────────────────────────────────────

/**
 * Best-effort map from a Judge0 language id → Monaco's language token.
 * Falls back to scanning the human-readable name from /languages so we
 * don't need to hand-maintain a full id table.
 */
function monacoLanguageOf(
  id: number | null,
  languages: Language[],
): string {
  if (id == null) return 'plaintext';
  const row = languages.find((l) => l.id === id);
  const name = (row?.name ?? '').toLowerCase();
  if (name.includes('python')) return 'python';
  if (name.includes('javascript') || name.includes('node')) return 'javascript';
  if (name.includes('typescript')) return 'typescript';
  if (name.includes('java') && !name.includes('javascript')) return 'java';
  if (name.includes('kotlin')) return 'kotlin';
  if (name.includes('c#') || name.includes('csharp')) return 'csharp';
  if (name.includes('c++') || name.includes('cpp')) return 'cpp';
  if (name.startsWith('c ') || name === 'c') return 'c';
  if (name.includes('go ')) return 'go';
  if (name.includes('rust')) return 'rust';
  if (name.includes('ruby')) return 'ruby';
  if (name.includes('php')) return 'php';
  if (name.includes('swift')) return 'swift';
  if (name.includes('bash') || name.includes('shell')) return 'shell';
  if (name.includes('sql')) return 'sql';
  if (name.includes('r ') || name === 'r') return 'r';
  return 'plaintext';
}
