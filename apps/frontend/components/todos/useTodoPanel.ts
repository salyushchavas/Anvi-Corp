'use client';

import { useCallback, useEffect, useState } from 'react';
import api from '@/lib/careers/api';
import type { TodoPanelResponse, TodoRole } from './todo-types';
import { endpointForRole } from './todo-types';

const POLL_MS = 45_000;

interface Ctx {
  data: TodoPanelResponse | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  markSeen: () => Promise<void>;
  dismiss: (todoKey: string) => Promise<void>;
  undismiss: (todoKey: string) => Promise<void>;
}

/**
 * Hook that drives the persistent to-do panel + login pop-up gate.
 * Polls the per-role aggregator every 45s so items visibly drop after
 * an action is taken. Reuses the axios instance (bearer + refresh
 * interceptor already wired).
 */
export function useTodoPanel(role: TodoRole): Ctx {
  const [data, setData] = useState<TodoPanelResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<TodoPanelResponse>(endpointForRole(role));
      setData(res.data);
      setError(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setError(ax.response?.data?.error ?? ax.message ?? 'Failed to load to-do panel');
    } finally {
      setLoading(false);
    }
  }, [role]);

  const markSeen = useCallback(async () => {
    try {
      const res = await api.post<{ lastSeenAt?: string }>('/api/v1/todos/seen');
      // Optimistic — clear the "new since last seen" flag locally so the
      // pop-up doesn't re-fire while the next poll is in flight.
      setData((prev) =>
        prev
          ? { ...prev, hasNewSinceLastSeen: false, lastSeenAt: res.data.lastSeenAt ?? new Date().toISOString() }
          : prev,
      );
    } catch {
      // Silent — user still gets the visual dismiss client-side.
    }
  }, []);

  const dismiss = useCallback(async (todoKey: string) => {
    try {
      await api.post(`/api/v1/todos/${encodeURIComponent(todoKey)}/dismiss`);
      setData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          buckets: prev.buckets.map((b) => ({
            ...b,
            topItems: b.topItems.map((it) =>
              it.todoKey === todoKey ? { ...it, dismissed: true } : it,
            ),
          })),
        };
      });
    } catch {
      /* silent */
    }
  }, []);

  const undismiss = useCallback(async (todoKey: string) => {
    try {
      await api.delete(`/api/v1/todos/${encodeURIComponent(todoKey)}/dismiss`);
      setData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          buckets: prev.buckets.map((b) => ({
            ...b,
            topItems: b.topItems.map((it) =>
              it.todoKey === todoKey ? { ...it, dismissed: false } : it,
            ),
          })),
        };
      });
    } catch {
      /* silent */
    }
  }, []);

  useEffect(() => {
    void load();
    const id = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(id);
  }, [load]);

  return { data, loading, error, refresh: load, markSeen, dismiss, undismiss };
}
