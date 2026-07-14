'use client';

import { CSS } from '@dnd-kit/utilities';
import { useDraggable } from '@dnd-kit/core';
import { Download } from 'lucide-react';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import type { ApplicationResponse, ApplicationStatus } from '@/types';

const ARCHIVED_STATUSES: ReadonlyArray<ApplicationStatus> = [
  'REJECTED',
  'WITHDRAWN',
  'LAPSED',
  'NO_SHOW',
];

function timeAgo(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const s = Math.floor((Date.now() - d.getTime()) / 1000);
  if (s < 60 * 60) return 'just now';
  if (s < 60 * 60 * 24) return `${Math.floor(s / 3600)}h ago`;
  if (s < 60 * 60 * 24 * 7) return `${Math.floor(s / 86400)}d ago`;
  if (s < 60 * 60 * 24 * 30) return `${Math.floor(s / 604800)}w ago`;
  if (s < 60 * 60 * 24 * 365) return `${Math.floor(s / 2592000)}mo ago`;
  return `${Math.floor(s / 31536000)}y ago`;
}

interface Props {
  application: ApplicationResponse;
  onViewDetails: (id: string) => void;
  onDownloadResume: (resumeId: string, fileName?: string) => void | Promise<void>;
  /** When true, render in DragOverlay style (no drag wiring, just visual). */
  overlay?: boolean;
  /** Hide source card while a drag overlay is shown. */
  ghosted?: boolean;
}

export default function ApplicationCard({
  application,
  onViewDetails,
  onDownloadResume,
  overlay = false,
  ghosted = false,
}: Props) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: application.id,
    data: { status: application.status },
    disabled: overlay,
  });

  const inArchive = ARCHIVED_STATUSES.includes(application.status);

  const style: React.CSSProperties = overlay
    ? { transform: 'rotate(1deg)', cursor: 'grabbing' }
    : {
        transform: CSS.Translate.toString(transform),
        opacity: ghosted || isDragging ? 0.4 : 1,
        cursor: isDragging ? 'grabbing' : 'grab',
      };

  return (
    <div
      ref={overlay ? undefined : setNodeRef}
      style={style}
      {...(overlay ? {} : attributes)}
      {...(overlay ? {} : listeners)}
      className={
        'flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-3 transition-all duration-150 ' +
        (overlay
          ? 'shadow-lg opacity-80'
          : 'shadow-sm hover:shadow-md')
      }
    >
      <div className="flex items-start justify-between gap-2">
        <span className="truncate text-sm font-semibold text-gray-900">
          {application.candidateName ?? '(unnamed candidate)'}
        </span>
        {inArchive && <ApplicationStatusBadge status={application.status} />}
      </div>

      <div className="truncate text-xs text-gray-500">
        {application.jobPostingTitle ?? '(unlinked posting)'}
      </div>

      <div className="text-xs text-gray-400">
        Applied {timeAgo(application.appliedAt)}
      </div>

      <div className="mt-1 flex items-center justify-between">
        {application.resumeId ? (
          <button
            type="button"
            title="Download resume"
            aria-label={`Download resume ${application.resumeFileName ?? ''}`}
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              void onDownloadResume(
                application.resumeId as string,
                application.resumeFileName
              );
            }}
            className="rounded p-1 text-gray-400 transition-colors hover:text-gray-700"
          >
            <Download className="h-4 w-4" strokeWidth={2} />
          </button>
        ) : (
          <span className="text-xs text-gray-300">No resume</span>
        )}
        <button
          type="button"
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => {
            e.stopPropagation();
            onViewDetails(application.id);
          }}
          className="text-xs font-medium text-primary-700 transition-colors hover:underline"
        >
          Review
        </button>
      </div>
    </div>
  );
}
