'use client';

import { ChangeEvent, DragEvent, useRef, useState } from 'react';

interface Props {
  accept?: string;
  maxSizeMB?: number;
  onFileSelected: (file: File) => Promise<void> | void;
  label?: string;
  disabled?: boolean;
}

const DEFAULT_ACCEPT = '.pdf,.doc,.docx';
const DEFAULT_MAX_MB = 10;

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function extensionsFromAccept(accept: string): string[] {
  return accept
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter((s) => s.startsWith('.'));
}

export default function FileUpload({
  accept = DEFAULT_ACCEPT,
  maxSizeMB = DEFAULT_MAX_MB,
  onFileSelected,
  label = 'Upload your resume',
  disabled = false,
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const allowedExtensions = extensionsFromAccept(accept);
  const maxSizeBytes = maxSizeMB * 1024 * 1024;

  function validate(file: File): string | null {
    const lower = file.name.toLowerCase();
    const ok = allowedExtensions.some((ext) => lower.endsWith(ext));
    if (!ok) {
      return `Unsupported file type. Allowed: ${allowedExtensions.join(', ')}.`;
    }
    if (file.size > maxSizeBytes) {
      return `File too large (max ${maxSizeMB} MB; got ${formatBytes(file.size)}).`;
    }
    return null;
  }

  async function handleFile(file: File) {
    setError(null);
    const validationError = validate(file);
    if (validationError) {
      setError(validationError);
      setSelectedName(null);
      return;
    }
    setSelectedName(file.name);
    setUploading(true);
    try {
      await onFileSelected(file);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? err?.message ?? 'Upload failed';
      setError(msg);
    } finally {
      setUploading(false);
      // Allow re-selecting the same file
      if (inputRef.current) inputRef.current.value = '';
    }
  }

  function onChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) void handleFile(file);
  }

  function onDrop(e: DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    setDragOver(false);
    if (disabled || uploading) return;
    const file = e.dataTransfer.files?.[0];
    if (file) void handleFile(file);
  }

  function onDragOver(e: DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    if (!disabled && !uploading) setDragOver(true);
  }

  function onDragLeave(e: DragEvent<HTMLLabelElement>) {
    e.preventDefault();
    setDragOver(false);
  }

  const interactive = !disabled && !uploading;

  return (
    <div>
      <label
        htmlFor="file-upload-input"
        onDrop={onDrop}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        className={
          'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-8 text-center transition ' +
          (dragOver
            ? 'border-accent bg-accent/5'
            : 'border-slate-300 bg-slate-50 hover:border-accent/60 hover:bg-accent/5') +
          (interactive ? '' : ' cursor-not-allowed opacity-60')
        }
      >
        <svg
          aria-hidden="true"
          className="h-8 w-8 text-slate-400"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 7.5m0 0L7.5 12M12 7.5V18"
          />
        </svg>
        <div className="text-sm font-medium text-slate-700">{label}</div>
        <div className="text-xs text-slate-500">
          Drag &amp; drop, or click to browse. Allowed: {allowedExtensions.join(', ')}. Max{' '}
          {maxSizeMB} MB.
        </div>
        <input
          ref={inputRef}
          id="file-upload-input"
          type="file"
          accept={accept}
          onChange={onChange}
          disabled={!interactive}
          className="sr-only"
        />
      </label>

      {selectedName && (
        <div className="mt-3 flex items-center gap-2 text-sm text-slate-600">
          <span className="font-medium">{selectedName}</span>
          {uploading && (
            <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-accent border-t-transparent" />
          )}
          {uploading && <span>Uploading…</span>}
        </div>
      )}

      {error && (
        <div className="mt-3 rounded border border-red-200 bg-red-50 p-2 text-sm text-red-700">
          {error}
        </div>
      )}
    </div>
  );
}
