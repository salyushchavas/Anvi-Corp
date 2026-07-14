'use client';

import { ChangeEvent, DragEvent, useRef, useState } from 'react';
import { FileText, UploadCloud, X } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface FileUploadProps {
  accept?: string;
  maxSizeMB?: number;
  onUpload: (file: File) => Promise<void>;
  /** Existing file shown in the preview state. */
  current?: { name: string; size?: number } | null;
  onRemove?: () => void;
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

export default function FileUpload({
  accept = DEFAULT_ACCEPT,
  maxSizeMB = DEFAULT_MAX_MB,
  onUpload,
  current,
  onRemove,
  label = 'Drop file here or click to browse',
  disabled,
}: FileUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);

  const allowed = accept.split(',').map((s) => s.trim().toLowerCase());

  async function handleFile(file: File) {
    setError(null);
    const lower = file.name.toLowerCase();
    if (!allowed.some((ext) => lower.endsWith(ext))) {
      setError(`Unsupported file type. Allowed: ${allowed.join(', ')}.`);
      return;
    }
    if (file.size > maxSizeMB * 1024 * 1024) {
      setError(`File too large (max ${maxSizeMB} MB).`);
      return;
    }
    setUploading(true);
    setProgress(15);
    try {
      // Fake-grow the bar while the upload resolves; the call we're wrapping
      // is opaque so we synthesize progress for visual feedback.
      const tick = window.setInterval(
        () => setProgress((p) => Math.min(85, p + 10)),
        200,
      );
      await onUpload(file);
      window.clearInterval(tick);
      setProgress(100);
    } catch (e: any) {
      setError(e?.response?.data?.error ?? e?.message ?? 'Upload failed');
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
      window.setTimeout(() => setProgress(0), 500);
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

  if (current) {
    return (
      <div className="flex items-center gap-3 rounded-lg border border-slate-200 bg-white p-3">
        <span className="flex h-10 w-10 items-center justify-center rounded-md bg-slate-100">
          <FileText className="h-5 w-5 text-slate-500" strokeWidth={2} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-slate-800">{current.name}</p>
          {typeof current.size === 'number' && (
            <p className="text-xs text-slate-500">{formatBytes(current.size)}</p>
          )}
        </div>
        {onRemove && (
          <button
            type="button"
            onClick={onRemove}
            aria-label="Remove file"
            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100"
          >
            <X className="h-4 w-4" strokeWidth={2} />
          </button>
        )}
      </div>
    );
  }

  return (
    <div>
      <label
        onDrop={onDrop}
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled && !uploading) setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        htmlFor="ds-file-upload"
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed py-8 px-4 text-center transition-colors',
          dragOver
            ? 'border-brand-500 bg-brand-50/40'
            : 'border-slate-300 bg-white hover:border-slate-400',
          (disabled || uploading) && 'cursor-not-allowed opacity-60',
        )}
      >
        <UploadCloud className="h-7 w-7 text-slate-400" strokeWidth={1.5} />
        <p className="text-sm font-medium text-slate-700">{label}</p>
        <p className="text-xs text-slate-500">
          {allowed.join(', ')} · max {maxSizeMB} MB
        </p>
        <input
          ref={inputRef}
          id="ds-file-upload"
          type="file"
          accept={accept}
          onChange={onChange}
          className="sr-only"
          disabled={disabled || uploading}
        />
      </label>
      {uploading && (
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
          <div
            className="h-full bg-brand-500 transition-all duration-200 ease-out"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}
      {error && (
        <p className="mt-2 text-xs text-red-700" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
