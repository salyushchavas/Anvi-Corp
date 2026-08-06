'use client';

import { useEffect, useMemo, useState } from 'react';
import api from '@/lib/careers/api';
import type { InstanceDetail } from '@/lib/careers/idms';

/**
 * Resolve every signature URL on an {@link InstanceDetail} to an
 * in-memory object URL that the img tag can safely render.
 *
 * <p>Signature bytes are PII-encrypted at rest on the server — the
 * browser can't render a direct S3 presigned URL for one (it gets the
 * encrypted envelope, not a PNG). The backend gates decryption behind
 * an authenticated endpoint; we hit it via axios (Bearer token
 * attaches through the shared interceptor) and wrap the returned blob
 * in an object URL that stays stable across re-renders.</p>
 *
 * <h3>Stability contract</h3>
 * The effect keys on a memoised concatenation of
 * {@code (fieldId, signatureUrl)} pairs. Since the backend now emits
 * a stable URL per (instance, field, documentId) — no per-request
 * presign signature — the key is invariant across auto-save cycles,
 * so unrelated keystrokes never trigger a re-fetch. The blob URLs
 * that InstanceRenderer paints therefore never churn either, which
 * preserves the keystroke-flicker + node-reuse guarantees from the
 * prior signature-render fix.
 *
 * <h3>Cleanup</h3>
 * Each object URL is revoked on unmount + on refresh, so the browser's
 * blob store doesn't leak decrypted PII bytes across navigations.
 */
export function useSignatureBlobs(
  detail: InstanceDetail | null,
): Record<string, string> {
  const [blobs, setBlobs] = useState<Record<string, string>>({});

  // Stable key derived from just the URLs — auto-save re-fetch of a
  // detail whose signatures haven't changed produces the same key and
  // no re-download runs.
  const urlsKey = useMemo(() => {
    if (!detail) return '';
    const entries: string[] = [];
    for (const [fid, v] of Object.entries(detail.values ?? {})) {
      if (v.signatureUrl) entries.push(`${fid}||${v.signatureUrl}`);
    }
    entries.sort();
    return entries.join('|');
  }, [detail]);

  useEffect(() => {
    if (!detail || urlsKey === '') {
      setBlobs({});
      return;
    }
    const wanted = new Map<string, string>();
    for (const [fid, v] of Object.entries(detail.values ?? {})) {
      if (v.signatureUrl) wanted.set(fid, v.signatureUrl);
    }
    if (wanted.size === 0) {
      setBlobs({});
      return;
    }

    let cancelled = false;
    const createdBlobs: string[] = [];

    void (async () => {
      const out: Record<string, string> = {};
      // Fetch all signatures in parallel — typically 2 per document
      // (ERM + intern) so this is a two-request burst at most.
      await Promise.all(
        Array.from(wanted.entries()).map(async ([fid, url]) => {
          try {
            const res = await api.get(url, { responseType: 'blob' });
            if (cancelled) return;
            const blobUrl = URL.createObjectURL(res.data as Blob);
            createdBlobs.push(blobUrl);
            out[fid] = blobUrl;
          } catch {
            // Leave the field's slot empty — the renderer falls through
            // to a neutral "signature unavailable" placeholder rather
            // than a broken-image icon.
          }
        }),
      );
      if (!cancelled) setBlobs(out);
    })();

    return () => {
      cancelled = true;
      for (const u of createdBlobs) URL.revokeObjectURL(u);
    };
    // urlsKey encodes the exact set of URLs we want; depending on
    // `detail` directly would refire on every auto-save round-trip.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlsKey]);

  return blobs;
}
