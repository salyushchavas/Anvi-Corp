'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import type { JobPostingResponse, ResumeResponse } from '@/types';
import {
  Button,
  FileUpload,
  Label,
  Modal,
  StepperHorizontal,
  Textarea,
  toast,
} from '@/components/ui';

interface Props {
  posting: JobPostingResponse;
  defaultName?: string;
  defaultEmail?: string;
  onClose: () => void;
  onApplied: (applicationId: string) => void;
}

const REASON_MAX = 500;

const WIZARD_STEPS = [
  { key: 'resume', label: 'Resume' },
  { key: 'review', label: 'Review' },
];

export default function ApplyNowModal({
  posting,
  defaultName,
  defaultEmail,
  onClose,
  onApplied,
}: Props) {
  const router = useRouter();
  const [stepIdx, setStepIdx] = useState(0);
  const [reason, setReason] = useState('');
  const [resumes, setResumes] = useState<ResumeResponse[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<ResumeResponse[]>('/api/v1/resumes/me');
        if (cancelled) return;
        const list = res.data ?? [];
        setResumes(list);
        const def = list.find((r) => r.isDefault) ?? list[0];
        setSelectedResumeId(def?.id ?? '');
      } catch {
        if (!cancelled) setError("Couldn't load your resumes.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const selectedResume = resumes.find((r) => r.id === selectedResumeId) ?? null;

  async function uploadResume(file: File) {
    const form = new FormData();
    form.append('file', file);
    const res = await api.post<ResumeResponse>('/api/v1/resumes', form);
    const created = res.data;
    setResumes((prev) => [...prev, created]);
    setSelectedResumeId(created.id);
  }

  async function handleSubmit() {
    if (!selectedResumeId) {
      setError('Resume is required to submit.');
      setStepIdx(0);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const res = await api.post<{ id: string }>('/api/v1/applications', {
        jobPostingId: posting.id,
        resumeId: selectedResumeId,
      });
      toast.success('Application submitted.');
      onApplied(res.data?.id ?? '');
    } catch (err: any) {
      const status = err?.response?.status;
      const code = err?.response?.data?.code;
      if (status === 409 && code === 'PROFILE_INCOMPLETE') {
        // Approach 1 — server says the intern needs to finish their profile
        // before this Apply will be accepted. Hand off to the editor with the
        // first missing field focused; close the modal so the redirect lands
        // cleanly.
        const missing: string[] = err?.response?.data?.details?.missing ?? [];
        const focus = missing[0];
        const href = focus
          ? `/careers/intern/profile/complete?focus=${encodeURIComponent(focus)}`
          : '/careers/intern/profile/complete';
        toast.error('Complete your profile to apply.');
        onClose();
        router.push(href);
        return;
      }
      if (status === 409) {
        setError("You've already applied to this position.");
      } else {
        setError(err?.response?.data?.error ?? 'Submission failed. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  const isLast = stepIdx === WIZARD_STEPS.length - 1;
  const canAdvance = stepIdx === 0 && !!selectedResumeId;

  return (
    <Modal
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Apply for ${posting.title}`}
      description={[posting.entityName, posting.location].filter(Boolean).join(' · ')}
      size="lg"
      footer={
        <>
          {stepIdx > 0 && (
            <Button variant="secondary" onClick={() => setStepIdx((s) => s - 1)} disabled={submitting}>
              Back
            </Button>
          )}
          {isLast ? (
            <Button onClick={handleSubmit} loading={submitting}>
              Submit application
            </Button>
          ) : (
            <Button onClick={() => setStepIdx((s) => s + 1)} disabled={!canAdvance}>
              Continue
            </Button>
          )}
        </>
      }
    >
      <div className="mb-6">
        <StepperHorizontal steps={WIZARD_STEPS} currentIndex={stepIdx} />
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      {stepIdx === 0 && (
        <div className="space-y-4">
          <div>
            <Label>Resume</Label>
            {loading ? (
              <div className="h-10 w-full animate-pulse rounded-md bg-slate-100" />
            ) : selectedResume ? (
              <FileUpload
                onUpload={uploadResume}
                current={{ name: selectedResume.fileName, size: selectedResume.fileSize }}
                onRemove={() => setSelectedResumeId('')}
              />
            ) : (
              <FileUpload onUpload={uploadResume} label="Drop your resume here or click to browse" />
            )}
            {resumes.length > 1 && !selectedResume && (
              <p className="mt-2 text-xs text-slate-500">
                Or pick an existing resume below:
              </p>
            )}
            {resumes.length > 0 && !selectedResume && (
              <ul className="mt-2 space-y-1.5">
                {resumes.map((r) => (
                  <li key={r.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedResumeId(r.id)}
                      className="w-full rounded-md border border-slate-200 px-3 py-2 text-left text-sm hover:bg-slate-50"
                    >
                      {r.fileName}
                      {r.isDefault && (
                        <span className="ml-2 text-xs text-slate-500">(default)</span>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <Label htmlFor="apply-reason" hint={`${reason.length}/${REASON_MAX}`}>
              Why are you interested? (optional)
            </Label>
            <Textarea
              id="apply-reason"
              rows={4}
              maxLength={REASON_MAX}
              value={reason}
              onChange={(e) => setReason(e.target.value.slice(0, REASON_MAX))}
              placeholder="Share what draws you to this role…"
            />
          </div>
        </div>
      )}

      {stepIdx === 1 && (
        <div className="space-y-4 text-sm text-slate-700">
          <SummaryRow label="Position" value={posting.title} />
          <SummaryRow
            label="Company"
            value={[posting.entityName, posting.location].filter(Boolean).join(' · ') || '—'}
          />
          <SummaryRow label="Applicant" value={defaultName ?? '—'} />
          <SummaryRow label="Email" value={defaultEmail ?? '—'} />
          <SummaryRow label="Resume" value={selectedResume?.fileName ?? 'None'} />
          {reason && <SummaryRow label="Why interested" value={reason} multi />}
          <div className="rounded-md border border-slate-300 bg-slate-100 px-3 py-2 text-xs text-slate-700">
            By submitting, you confirm the information above is accurate.
          </div>
        </div>
      )}
    </Modal>
  );
}

function SummaryRow({ label, value, multi }: { label: string; value: string; multi?: boolean }) {
  return (
    <div className={multi ? '' : 'flex items-baseline justify-between gap-3'}>
      <p className="text-xs font-medium uppercase tracking-wider text-slate-500">{label}</p>
      <p className={multi ? 'mt-1 whitespace-pre-line text-sm text-slate-800' : 'text-sm text-slate-800'}>
        {value}
      </p>
    </div>
  );
}
