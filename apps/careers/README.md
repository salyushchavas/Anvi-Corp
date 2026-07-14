# Skyzen Careers — Frontend

Next.js 14 (App Router) frontend for Skyzen Careers — the internship lifecycle platform that replaces the legacy `jobs.php` system on skyzentech.com.

In production it will live at **`https://skyzentech.com/careers`** via a reverse proxy in front of this Next.js app — that's why `basePath` is set to `/careers` in `next.config.js`.

## Stack

- Next.js 14 (App Router)
- TypeScript
- Tailwind CSS
- axios (API client)
- resend (transactional email — wired up in a later phase)

## Required environment variables

| Variable               | Description                                                                              | Example                         |
| ---------------------- | ---------------------------------------------------------------------------------------- | ------------------------------- |
| `NEXT_PUBLIC_API_URL`  | Base URL of the Skyzen Careers backend. **Must not include a trailing slash.**           | `http://localhost:8080`         |
| `RESEND_API_KEY`       | API key for Resend (server-side only). Leave blank in Phase 0.1 — wired up later.        | `re_…`                          |

Copy `.env.local.example` to `.env.local` and fill in values.

## Local setup

```bash
# 1. Install
npm install

# 2. Configure
cp .env.local.example .env.local
# edit .env.local — at minimum, set NEXT_PUBLIC_API_URL to http://localhost:8080

# 3. Run the dev server
npm run dev
```

Open <http://localhost:3000/careers> — note the `/careers` prefix is required because of `basePath`.

## Production

In production the app is served at `https://skyzentech.com/careers`. The reverse-proxy on skyzentech.com forwards `/careers/*` to this Next.js app, and the `basePath: '/careers'` setting tells Next.js to generate URLs/assets under that prefix.

## Project layout

```
app/                Next.js App Router pages
├── layout.tsx
├── page.tsx
└── globals.css

lib/api.ts          Shared axios client (reads NEXT_PUBLIC_API_URL)
types/index.ts      Shared frontend types
```

## Notes

- `NEXT_PUBLIC_API_URL` must **not** end with `/` — the axios client appends paths without trimming.
- The `basePath: '/careers'` setting must remain in place from day one; removing it will break production routing.
- Primary color tokens in `tailwind.config.ts` are placeholders — they'll be tuned to skyzentech.com branding in Phase 1.

<!-- deploy-trigger: 2026-05-19 -->



