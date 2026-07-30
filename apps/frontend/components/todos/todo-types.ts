/** Client-side mirror of com.anvicorp.api.todos.TodoPanelResponse. */
export interface TodoPanelResponse {
  role: 'MANAGER' | 'EVALUATOR' | 'ERM' | string;
  buckets: TodoBucket[];
  totalCount: number;
  hasNewSinceLastSeen: boolean;
  lastSeenAt: string | null;
}

export interface TodoBucket {
  bucketKey: string;
  label: string;
  /** Lucide icon key — TodoPanel maps this to a component. */
  icon: string;
  count: number;
  actionUrl: string;
  severity: 'INFO' | 'WARN' | 'URGENT';
  topItems: TodoItem[];
}

export interface TodoItem {
  /** Composite key {@code "<BUCKET_KEY>:<domain-row-id>"}. */
  todoKey: string;
  label: string;
  subLabel: string | null;
  actionUrl: string;
  firstAppearedAt: string | null;
  dismissed: boolean;
}

/** Which per-role todos endpoint to hit. Extend when TRAINER ships. */
export type TodoRole = 'MANAGER' | 'EVALUATOR' | 'ERM';

export function endpointForRole(role: TodoRole): string {
  switch (role) {
    case 'MANAGER': return '/api/v1/manager/todos';
    case 'EVALUATOR': return '/api/v1/evaluator/todos';
    case 'ERM': return '/api/v1/erm/todos';
  }
}
