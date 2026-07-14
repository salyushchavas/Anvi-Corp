// Helper for fetching from the backend inside Server Components.
// Public endpoints only — no auth header, no localStorage.

import type { JobPostingResponse, Page } from '@/types';

function baseUrl(): string {
  const url = process.env.NEXT_PUBLIC_API_URL;
  if (!url) {
    throw new Error('NEXT_PUBLIC_API_URL is not set');
  }
  return url.replace(/\/+$/, '');
}

async function fetchJson<T>(path: string): Promise<T | null> {
  const res = await fetch(`${baseUrl()}${path}`, {
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  });
  if (res.status === 404) return null;
  if (!res.ok) {
    throw new Error(`Backend ${res.status} on ${path}`);
  }
  return (await res.json()) as T;
}

export async function fetchOpenJobPostings(
  page = 0,
  size = 50
): Promise<Page<JobPostingResponse> | null> {
  return fetchJson<Page<JobPostingResponse>>(
    `/api/v1/job-postings?page=${page}&size=${size}`
  );
}

export async function fetchJobPosting(
  idOrSlug: string
): Promise<JobPostingResponse | null> {
  return fetchJson<JobPostingResponse>(
    `/api/v1/job-postings/${encodeURIComponent(idOrSlug)}`
  );
}
