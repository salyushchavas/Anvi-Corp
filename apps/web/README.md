# anvicorp.com

Next.js 14 (App Router, TypeScript, Tailwind) rebuild of the legacy PHP/HTML
brochure site. Deployed to Vercel. The previous PHP site is preserved under
`_legacy/` for visual reference.

## Stack

- **Next.js 14** App Router, TypeScript, statically rendered
- **Tailwind CSS** for styling; brand tokens in `tailwind.config.ts`
- **Kumbh Sans** via `next/font/google`
- **Lucide React** for icons
- **Embla Carousel** for the homepage hero (only client JS on the site outside the contact form)
- **Resend** for the contact form (replaces the legacy PHP `mail()`)

## Local development

```bash
npm install
cp .env.example .env.local   # then fill in RESEND_API_KEY
npm run dev                  # http://localhost:3000
```

## Environment variables

| Name              | Required | Notes |
|-------------------|----------|-------|
| `RESEND_API_KEY`  | yes      | Get one at https://resend.com/api-keys |
| `MAIL_TO`         | no       | Default: `info@anvicorp.com` |
| `MAIL_FROM`       | no       | Default: `Anvi Corp Website <onboarding@resend.dev>`. Switch to `noreply@anvicorp.com` after verifying the domain in Resend. |

Set these in **Vercel → Project → Settings → Environment Variables**, scoped to both **Production** and **Preview**.

## Deploying to Vercel

The Vercel project (`anvicorp.com`) is already created. To enable automatic deploys on push:

1. Open the project in Vercel → **Settings → Git**
2. Click **Connect Git Repository** → choose `salyushchavas/Anvi-Corp`
3. Set the production branch to `main`
4. Add the env vars above under **Settings → Environment Variables**

Once linked, every push to `main` triggers a production-target build; every push to any other branch creates a preview deployment with its own URL.

To deploy manually from your machine:

```bash
npx vercel              # preview deploy
npx vercel --prod       # production deploy
```

The `anvicorp.com` customer domain is **not** in Vercel's domain list — DNS cutover is a separate step.

## Project layout

```
app/
├── layout.tsx                 # root layout: SiteHeader + SiteFooter + fonts
├── page.tsx                   # home (composes all home/* sections)
├── icon.png                   # favicon (Next 14 convention)
├── sitemap.ts                 # generated /sitemap.xml
├── robots.ts                  # generated /robots.txt
├── not-found.tsx              # custom 404
├── services/<slug>/page.tsx   # 4 service pages — all use ServicePageTemplate
├── contact/page.tsx           # contact UI (form is a client component)
├── privacy-policy/page.tsx
├── careers/page.tsx           # Phase 2 seam — "coming soon" placeholder
└── api/contact/route.ts       # POST handler — Resend email

components/                    # shared design system
├── site-header.tsx, site-footer.tsx, back-to-top.tsx
├── button.tsx, inner-banner.tsx, check-list.tsx, section-heading.tsx
├── service-page-template.tsx  # shared service-page layout
├── contact-form.tsx           # client form with honeypot + validation
├── logo.tsx, social-icons.tsx
└── home/                      # homepage section components
    ├── hero.tsx (client — Embla carousel + video background)
    ├── services-grid.tsx, industries-section.tsx, about-section.tsx
    ├── team-section.tsx, careers-cta-section.tsx
    ├── blog-section.tsx, final-cta-section.tsx

public/
├── googleb1ffc46918a9fd23.html  # Google Search Console — exact filename
├── logo.png
├── slider/{banner,1}.mp4        # hero videos
├── services/*, industries/*, blog/*

_legacy/                       # original PHP/HTML site, kept for reference
                               # (excluded from Vercel uploads via .vercelignore)
```

## URL redirects

Every legacy URL has a 308 redirect in `next.config.mjs`:

```
/index.html                          → /
/software-development.html           → /services/software-development
/cloud-development.html              → /services/cloud-development
/mobile-application-development.html → /services/mobile-application-development
/it-consulting.html                  → /services/it-consulting
/contact-us.php                      → /contact
/privacy-policy.html                 → /privacy-policy
```

`_legacy/apply.php` was intentionally not migrated — it's an orphaned Blueera template that wasn't linked from anywhere.
