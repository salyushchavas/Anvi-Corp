// ERM Phase 8.2 — mirrors of com.skyzen.careers.erm.documents.DocumentDtos.
// All template-management types are gone — the 13 docs live in
// `lib/skyzen-documents.ts` and the backend exposes a `documentKey` (the
// SkyzenDocument enum name) + `templatePublicUrl` on every task DTO.

import type { SkyzenDocumentKey } from '@/lib/skyzen-documents';

export type PacketStatus =
  | 'DRAFT'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'ALL_SUBMITTED'
  | 'COMPLETED'
  | 'CANCELLED';

export type TaskStatus =
  | 'PENDING'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'RESEND_REQUESTED'
  | 'WAIVED';

export type DocumentPacketRow = {
  packetId: string;
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  internEmployeeId: string | null;
  status: PacketStatus;
  totalTasks: number;
  acceptedTasks: number;
  submittedTasks: number;
  pendingTasks: number;
  rejectedTasks: number;
  waivedTasks: number;
  assignedAt: string | null;
  completedAt: string | null;
  /** Phase 1.6 — intern has handed off; ERM should verify. Reset to
   *  false when ERM rejects any task on the packet. */
  internLocked: boolean;
  internSubmittedAt: string | null;
};

export type DocumentPacketListPage = {
  items: DocumentPacketRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

export type TaskSummary = {
  taskId: string;
  documentKey: SkyzenDocumentKey | null;
  templateTitle: string;
  category: string | null;
  sensitivity: string | null;
  templatePublicUrl: string | null;
  status: TaskStatus;
  version: number | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewReasonCode: string | null;
  reviewComments: string | null;
  uploadedFileId: string | null;
  uploadedFileName: string | null;
  taskInstructions: string | null;
};

export type DocumentPacketDetail = {
  packetId: string;
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  internEmail: string | null;
  internEmployeeId: string | null;
  status: PacketStatus;
  customInstructions: string | null;
  assignedAt: string | null;
  firstSubmissionAt: string | null;
  allSubmittedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  tasks: TaskSummary[];
  readyToClose: boolean;
};

export type DocumentTaskRow = {
  taskId: string;
  packetId: string;
  internLifecycleId: string | null;
  internUserId: string | null;
  internName: string | null;
  documentKey: SkyzenDocumentKey | null;
  templateTitle: string;
  category: string | null;
  status: TaskStatus;
  version: number | null;
  submittedAt: string | null;
  hoursWaiting: number;
};

export type DocumentTaskListPage = {
  items: DocumentTaskRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

export type InternReviewQueueRow = {
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  pendingCount: number;
  oldestSubmittedAt: string | null;
  oldestHoursWaiting: number;
};

export type InternReviewQueuePage = {
  items: InternReviewQueueRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

export type ReviewEventEntry = {
  id: string;
  actorUserId: string | null;
  actorName: string | null;
  eventType: string;
  previousStatus: string | null;
  newStatus: string | null;
  reasonCode: string | null;
  comments: string | null;
  createdAt: string;
};

export type DocumentTaskDetail = {
  taskId: string;
  packetId: string;
  documentKey: SkyzenDocumentKey | null;
  templateTitle: string;
  category: string | null;
  sensitivity: string | null;
  templatePublicUrl: string | null;
  status: TaskStatus;
  version: number | null;
  taskInstructions: string | null;
  uploadedFileId: string | null;
  uploadedFileName: string | null;
  uploadedFileSize: number | null;
  uploadedFileMime: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewedById: string | null;
  reviewedByName: string | null;
  reviewReasonCode: string | null;
  reviewComments: string | null;
  internalNote: string | null;
  internLifecycleId: string | null;
  internUserId: string | null;
  internName: string | null;
  history: ReviewEventEntry[];
  /** Pass 2 verify-after-download gate. Null until any ERM has fetched
   *  the file at least once; once set, the "Mark verified" decision
   *  unlocks. Server is authoritative — the verify endpoint also
   *  rejects an ACCEPT call when this is null. */
  lastDownloadedAt: string | null;
  downloadCount: number | null;
};

export type ReasonCodeOption = {
  code: string;
  label: string;
  requiresFreeText: boolean;
};

export type ReasonCodeGroup = {
  family: string;
  options: ReasonCodeOption[];
};

export type AssignPacketRequest = {
  internLifecycleId: string;
  selectedDocumentKeys: SkyzenDocumentKey[];
  customInstructions?: string | null;
  perDocumentInstructions?: Partial<Record<SkyzenDocumentKey, string>> | null;
};

export type ReviewTaskRequest = {
  decision: 'ACCEPT' | 'REJECT' | 'RESEND_REQUEST';
  reasonCode?: string | null;
  reasonText?: string | null;
  ermComments?: string | null;
  internalNote?: string | null;
};

export type BulkReviewRequest = {
  taskIds: string[];
  decision: 'ACCEPT';
  reasonCode?: string | null;
  reasonText?: string | null;
};

export type BulkReviewResult = {
  accepted: number;
  skipped: number;
  skippedReasons: { taskId: string; reason: string }[];
};

// Intern-facing variants — strict subset of the ERM types, with ERM-only
// fields stripped at the server.

export type InternTaskView = {
  taskId: string;
  documentKey: SkyzenDocumentKey | null;
  templateTitle: string;
  description: string | null;
  category: string | null;
  sensitivity: string | null;
  templatePublicUrl: string | null;
  status: TaskStatus;
  version: number | null;
  taskInstructions: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewReasonCode: string | null;
  reviewComments: string | null;
  uploadedFileName: string | null;
};

export type InternPacketView = {
  packetId: string;
  status: PacketStatus;
  customInstructions: string | null;
  assignedAt: string | null;
  completedAt: string | null;
  tasks: InternTaskView[];
  totalTasks: number;
  acceptedTasks: number;
  /** Phase 1.6 — true when the intern has explicitly clicked "Submit
   *  all documents to ERM". Locks per-task upload until ERM rejects
   *  a task (which clears the flag server-side). */
  internLocked: boolean;
  internSubmittedAt: string | null;
  /** Tasks still PENDING (not yet uploaded). When 0 the Submit button
   *  enables. */
  pendingTasks: number;
};
