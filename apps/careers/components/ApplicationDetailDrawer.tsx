'use client';

import { useCallback, useEffect, useState } from 'react';
import { Ban, CheckCircle, Download, ExternalLink, LogOut, Video, X, XCircle, type LucideIcon } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type { ApplicationResponse, ApplicationStatus } from '@/types';

const TERMINAL_STATUSES: ReadonlyArray<ApplicationStatus> = [
  'ACCEPTED',
  'REJECTED',
  'WITHDRAWN',
  'COMPLETED',
  'LAPSED',
  'NO_SHOW',
];

function formatDate(iso?: string): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  } catch {
    return iso;
  }
}

interface Props {
  applicationId: string | null;
  onClose: () => void;
  /** Called after any successful status / notes patch so parent can update Kanban state. */
  onUpdated: (application: ApplicationResponse) => void;
}

export default function ApplicationDetailDrawer({
  applicationId,
  onClose,
  onUpdated,
}: Props) {
  const [app, setApp] = useState<ApplicationResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState('');
  const [savingNotes, setSavingNotes] = useState(false);
  const [savedFlashAt, setSavedFlashAt] = useState<number | null>(null);
  const [busyAction, setBusyAction] = useState<ApplicationStatus | null>(null);

  const open = applicationId !== null;

  const load = useCallback(async () => {
    if (!applicationId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<ApplicationResponse>(`/api/v1/applications/${applicationId}`);
      setApp(res.data);
      setNotes(res.data.recruiterNotes ?? '');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Failed to load application');
      setApp(null);
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => {
    if (open) {
      void load();
    } else {
      setApp(null);
      setError(null);
      setNotes('');
      setSavedFlashAt(null);
    }
  }, [open, load]);

  // Escape to close
  useEffect(() => {
    if (!open) return;
    function handler(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  async function downloadResume() {
    if (!app?.resumeId) return;
    try {
      const res = await api.get(`/api/v1/resumes/${app.resumeId}/download`, {
        responseType: 'blob',
      });
      const ctRaw = res.headers['content-type'];
      const contentType =
        typeof ctRaw === 'string' ? ctRaw : 'application/octet-stream';
      const blob = new Blob([res.data], { type: contentType });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      const cdRaw = res.headers['content-disposition'];
      const disposition = typeof cdRaw === 'string' ? cdRaw : '';
      const m = disposition.match(/filename="?([^";]+)"?/);
      a.download = m?.[1] ?? app.resumeFileName ?? `resume-${app.resumeId}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? 'Resume download failed');
    }
  }

  async function patchStatus(targetStatus: ApplicationStatus, successMsg: string) {
    if (!app) return;
    setBusyAction(targetStatus);
    try {
      const res = await api.patch<ApplicationResponse>(
        `/api/v1/applications/${app.id}/status`,
        { status: targetStatus }
      );
      setApp(res.data);
      onUpdated(res.data);
      toast.success(successMsg);
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? 'Status update failed');
    } finally {
      setBusyAction(null);
    }
  }

  async function saveNotes() {
    if (!app) return;
    setSavingNotes(true);
    try {
      const res = await api.patch<ApplicationResponse>(
        `/api/v1/applications/${app.id}/status`,
        { status: app.status, recruiterNotes: notes }
      );
      setApp(res.data);
      onUpdated(res.data);
      setSavedFlashAt(Date.now());
      setTimeout(() => {
        setSavedFlashAt((current) => (current === null ? null : current));
      }, 2000);
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? 'Could not save notes');
    } finally {
      setSavingNotes(false);
    }
  }

  function confirmAndPatch(target: ApplicationStatus, prompt: string, msg: string) {
    if (typeof window !== 'undefined' && window.confirm(prompt)) {
      void patchStatus(target, msg);
    }
  }

  const isTerminal = app ? TERMINAL_STATUSES.includes(app.status) : false;
  const savedFlashOn = savedFlashAt !== null && Date.now() - savedFlashAt < 2000;

  return (
    <>
      {/* Backdrop */}
      <button
        type="button"
        aria-label="Close detail panel"
        onClick={onClose}
        className={
          'fixed inset-0 z-40 bg-black/30 transition-opacity duration-200 ' +
          (open ? 'pointer-events-auto opacity-100' : 'pointer-events-none opacity-0')
        }
      />

      {/* Panel */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-title"
        className={
          'fixed bottom-0 right-0 top-0 z-50 flex w-full max-w-md flex-col bg-white shadow-2xl transition-transform duration-200 ease-out ' +
          (open ? 'translate-x-0' : 'translate-x-full')
        }
      >
        <header className="sticky top-0 flex h-16 items-center justify-between border-b border-gray-200 bg-white px-6">
          <h2 id="drawer-title" className="text-base font-semibold text-gray-900">
            Application details
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-9 w-9 items-center justify-center rounded-md text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
            aria-label="Close"
          >
            <X className="h-5 w-5" strokeWidth={2} />
          </button>
        </header>

        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-6">
          {loading && (
            <>
              <div className="space-y-2">
                <div className="h-5 w-40 animate-pulse rounded bg-gray-200" />
                <div className="h-4 w-56 animate-pulse rounded bg-gray-200" />
              </div>
              <div className="space-y-2">
                <div className="h-3 w-24 animate-pulse rounded bg-gray-200" />
                <div className="h-4 w-48 animate-pulse rounded bg-gray-200" />
              </div>
              <div className="h-32 animate-pulse rounded bg-gray-200" />
            </>
          )}

          {error && !loading && (
            <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              <p className="mb-2">{error}</p>
              <button
                type="button"
                onClick={() => void load()}
                className="rounded border border-red-300 px-3 py-1 text-xs font-medium hover:bg-red-100"
              >
                Retry
              </button>
            </div>
          )}

          {app && !loading && !error && (
            <>
              {/* Candidate */}
              <section>
                <div className="text-lg font-semibold text-gray-900">
                  {app.candidateName ?? '(unnamed candidate)'}
                </div>
                {app.candidateEmail && (
                  <div className="text-sm text-gray-500">{app.candidateEmail}</div>
                )}
              </section>

              {/* Position */}
              <section>
                <div className="mb-1 text-xs uppercase tracking-wide text-gray-400">
                  Applied to
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-gray-900">
                    {app.jobPostingTitle ?? '(unlinked posting)'}
                  </span>
                  {app.jobPostingId && (
                    <a
                      href={`/careers/openings/${app.jobPostingId}`}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-0.5 text-xs font-medium text-primary-700 hover:underline"
                    >
                      View posting <ExternalLink className="h-3 w-3" strokeWidth={2} />
                    </a>
                  )}
                </div>
              </section>

              {/* Status + applied date */}
              <section>
                <div className="mb-2 text-xs uppercase tracking-wide text-gray-400">
                  Current status
                </div>
                <span className="inline-flex">
                  <ApplicationStatusBadge status={app.status} />
                </span>
                <div className="mt-2 text-xs text-gray-500">
                  Applied {formatDate(app.appliedAt)}
                </div>
              </section>

              {/* Resume */}
              <section>
                <div className="mb-2 text-xs uppercase tracking-wide text-gray-400">
                  Resume
                </div>
                {app.resumeId ? (
                  <div className="flex items-center justify-between rounded-lg border border-gray-200 px-3 py-2">
                    <span className="flex-1 truncate text-sm text-gray-700">
                      {app.resumeFileName ?? `Resume ${app.resumeId.slice(0, 8)}`}
                    </span>
                    <button
                      type="button"
                      onClick={() => void downloadResume()}
                      className="flex items-center gap-1 text-sm text-primary-700 hover:underline"
                    >
                      <Download className="h-4 w-4" strokeWidth={2} />
                      Download
                    </button>
                  </div>
                ) : (
                  <p className="text-sm text-gray-400">No resume attached.</p>
                )}
              </section>

              {/* Recruiter notes */}
              <section>
                <div className="mb-2 text-xs uppercase tracking-wide text-gray-400">
                  Recruiter notes
                </div>
                <textarea
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  rows={5}
                  placeholder="Add internal notes about this candidate…"
                  className="w-full resize-y rounded-md border border-gray-300 p-3 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700"
                />
                <div className="mt-2 flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => void saveNotes()}
                    disabled={savingNotes || notes === (app.recruiterNotes ?? '')}
                    className="rounded-md bg-accent px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
                  >
                    {savingNotes ? 'Saving…' : 'Save notes'}
                  </button>
                  {savedFlashOn && (
                    <span className="text-xs text-green-600">Saved ✓</span>
                  )}
                </div>
              </section>

              {/* Status actions */}
              {!isTerminal && (
                <section>
                  <div className="mb-2 text-xs uppercase tracking-wide text-gray-400">
                    Move to…
                  </div>
                  <div className="space-y-2">
                    {app.status === 'INTERVIEW_SCHEDULED' && (
                      <ActionButton
                        label="Mark Interviewed"
                        Icon={Video}
                        busy={busyAction === 'INTERVIEWED'}
                        onClick={() =>
                          void patchStatus('INTERVIEWED', 'Marked as interviewed')
                        }
                      />
                    )}
                    {app.status === 'OFFERED' && (
                      <>
                        <ActionButton
                          label="Mark Offer Accepted"
                          Icon={CheckCircle}
                          variant="success"
                          busy={busyAction === 'ACCEPTED'}
                          onClick={() =>
                            void patchStatus('ACCEPTED', 'Marked offer as accepted (Hired)')
                          }
                        />
                        <ActionButton
                          label="Mark Offer Declined"
                          Icon={XCircle}
                          variant="muted"
                          busy={busyAction === 'REJECTED'}
                          onClick={() =>
                            confirmAndPatch(
                              'REJECTED',
                              'Mark offer as declined? (Stored as REJECTED — backend has no separate OFFER_DECLINED status.)',
                              'Marked offer as declined'
                            )
                          }
                        />
                      </>
                    )}
                    <ActionButton
                      label="Reject"
                      Icon={Ban}
                      variant="danger"
                      busy={busyAction === 'REJECTED'}
                      onClick={() =>
                        confirmAndPatch(
                          'REJECTED',
                          'Reject this application?',
                          'Application rejected'
                        )
                      }
                    />
                    <ActionButton
                      label="Withdraw"
                      Icon={LogOut}
                      variant="muted"
                      busy={busyAction === 'WITHDRAWN'}
                      onClick={() =>
                        confirmAndPatch(
                          'WITHDRAWN',
                          'Mark this application as withdrawn?',
                          'Application withdrawn'
                        )
                      }
                    />
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </aside>
    </>
  );
}

function ActionButton({
  label,
  onClick,
  Icon,
  busy = false,
  variant = 'default',
}: {
  label: string;
  onClick: () => void;
  Icon: LucideIcon;
  busy?: boolean;
  variant?: 'default' | 'success' | 'danger' | 'muted';
}) {
  const variants: Record<string, string> = {
    default:
      'border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
    success:
      'border-green-200 bg-white text-green-700 hover:bg-green-50',
    danger:
      'border-red-200 bg-white text-red-600 hover:bg-red-50',
    muted:
      'border-gray-300 bg-white text-gray-600 hover:bg-gray-50',
  };
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className={
        'flex w-full items-center gap-2 rounded-md border px-3 py-2 text-sm font-medium transition-colors disabled:opacity-50 ' +
        variants[variant]
      }
    >
      <Icon className="h-4 w-4" strokeWidth={2} />
      {busy ? 'Updating…' : label}
    </button>
  );
}
