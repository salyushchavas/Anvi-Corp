# anvicorp.com — monorepo

Two-app monorepo for the Anvi Corp USA web presence.

```
apps/
├── web/      Next.js 14 marketing site + /careers UI         (Vercel — root dir: apps/web)
└── api/      RESERVED for Phase 2 Spring Boot 3 backend       (Railway — root dir: apps/api)
_legacy/      Original PHP/HTML cPanel site, reference only    (not deployed)
```

## Phase 1 (current) — marketing site

Plain static Next.js 14 App Router app under [`apps/web/`](./apps/web). One serverless route (`/api/contact`) sends emails via Resend; everything else prerenders. No backend, no database.

See [`apps/web/README.md`](./apps/web/README.md) for stack, local dev, env vars, and project layout.

## Phase 2 (future) — careers platform

A Spring Boot 3 / Java 21 + PostgreSQL backend lands in [`apps/api/`](./apps/api) when we build the Skyzen-style careers platform (job board, applications, recruiter dashboard, candidate auth). The frontend stays in `apps/web/` under a `/careers/(jobs|apply|dashboard)` route group sharing the same design system.

`apps/api/` is empty in Phase 1 and not deployed.

## Deployment

Both apps deploy automatically from GitHub via their respective platform integrations — **no deploy CLIs run from this repo**:

| App         | Platform | Auto-build trigger              | Root dir   |
|-------------|----------|----------------------------------|------------|
| `apps/web`  | Vercel   | Push to `main` (Vercel GitHub app) | `apps/web` |
| `apps/api`  | Railway  | Push to `main` (Phase 2)         | `apps/api` |

Each platform's project must have its **Root Directory** set to the relevant `apps/*` path in the dashboard. That's a one-time setup the project owner handles.

## Remotes

```
origin   → main repo, connected to Vercel
mirror   → backup repo, becomes Railway-watched in Phase 2
```

Every step commits to both:

```bash
git add -A
git commit -m "<message>"
git push origin <branch>
git push mirror <branch>
```
