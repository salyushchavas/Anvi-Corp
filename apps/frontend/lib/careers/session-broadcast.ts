/**
 * Tiny cross-tab session bus over BroadcastChannel('skyzen.session').
 * Used by the idle-timeout provider and SignOutButton so siblings tabs
 * stay in lock-step:
 *  - 'activity' from any tab resets every sibling's idle countdown
 *  - 'logout' from any tab tears every sibling down (matching reason)
 *
 * BroadcastChannel is not in SSR; callers should be guarded by
 * `typeof window !== 'undefined'` or by useEffect mounting.
 */

export type SessionEvent =
  | { type: 'activity'; ts: number }
  | { type: 'logout'; reason?: 'idle' };

const CHANNEL_NAME = 'skyzen.session';
let channel: BroadcastChannel | null = null;

function getChannel(): BroadcastChannel | null {
  if (typeof window === 'undefined') return null;
  if (typeof BroadcastChannel === 'undefined') return null;
  if (!channel) channel = new BroadcastChannel(CHANNEL_NAME);
  return channel;
}

export function broadcastSessionEvent(ev: SessionEvent): void {
  const c = getChannel();
  if (!c) return;
  try {
    c.postMessage(ev);
  } catch {
    // Posting a structured-clone-incompatible payload would throw — ignore.
  }
}

export function subscribeSessionEvents(
  handler: (ev: SessionEvent) => void,
): () => void {
  const c = getChannel();
  if (!c) return () => {};
  function listener(e: MessageEvent<SessionEvent>) {
    handler(e.data);
  }
  c.addEventListener('message', listener);
  return () => c.removeEventListener('message', listener);
}
