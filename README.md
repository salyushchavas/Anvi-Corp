# Anvi Careers — Local Setup (Step by Step)

## What this is

Anvi Careers is the hiring and internship platform for Anvi Corp. Candidates
apply for roles, staff review and interview them, and accepted interns get
offer letters they sign online plus onboarding documents they submit.

It's made of three pieces: a **website** (what you see in the browser), a
**backend** (the logic and rules), and a **database** (where everything is
stored). You don't need to install or configure any of them individually —
the steps below start all three together.

Following this page top to bottom takes about **10 minutes**, most of which
is the computer downloading things while you wait.

---

## Before you start — install one thing

**Docker Desktop.** Download it from
[docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/),
install it, then **open it and wait until it says "Engine running"** (bottom-left
corner). Docker has to be running in the background the whole time you're
using the app.

That's the only thing you need to install. Docker provides the database, the
backend, and the website for you — you don't need Java, Node, or Postgres.

**Check it worked.** Open a terminal and run:

```bash
docker --version
docker compose version
```

You should see a version number from each, something like:

```
Docker version 27.3.1, build ce12230
Docker Compose version v2.29.7
```

If you instead see "command not found", Docker isn't installed or isn't
running yet — open Docker Desktop and wait for "Engine running", then try
again.

---

## Step 1 — Unzip the project

Unzip the folder somewhere easy to find, like your Desktop or Documents.
Avoid folder names with spaces or accents if you can.

Now open a terminal **inside that folder**:

- **Windows:** open the unzipped folder in File Explorer, click the address
  bar at the top, type `powershell`, and press Enter.
- **Mac:** right-click the folder → Services → **New Terminal at Folder**.
  (If you don't see that option: open Terminal, type `cd ` with a space, then
  drag the folder onto the Terminal window and press Enter.)

**What you should see:** a terminal whose prompt shows you're in the project
folder. To confirm, run `ls` (Mac) or `dir` (Windows) — you should see
`apps`, `docker-compose.yml`, and `.env.example` listed.

---

## Step 2 — Create your settings file

Copy the example settings file. Run **one** of these, matching your computer:

- **Windows:**

  ```
  copy .env.example .env
  ```

- **Mac / Linux:**

  ```bash
  cp .env.example .env
  ```

**You don't need to change anything inside it.** The defaults are set up for
local use and just work.

**What you should see:** a confirmation like `1 file(s) copied` on Windows,
or no output at all on Mac (silence means success there).

---

## Step 3 — Start everything (one command)

```bash
docker compose up --build
```

⚠️ **The first time takes several minutes** — usually 5 to 10. Docker is
downloading Java, Node, and Postgres, then building the app. Lots of text
will scroll past. **This is normal. Let it finish.** You only pay this cost
once; later starts take about 30 seconds.

Leave this terminal window open. It's running the app — closing it stops
everything.

**What you should see:** the scrolling eventually settles down, and among
the last lines you'll spot these three (the colours and timestamps will
differ):

```
postgres-1  | database system is ready to accept connections
backend-1   | Started AnviApiApplication in 24.61 seconds
frontend-1  | ✓ Ready in 412ms
```

Once you've seen `Started AnviApiApplication`, the app is up. (A few
warnings in the log along the way are expected and harmless.)

---

## Step 4 — Open the app

Open this address in your browser:

**http://localhost:3000/careers/login**

**What you should see:** the Anvi Careers sign-in page, with email and
password boxes.

If the page doesn't load, give it another 15 seconds — the website starts a
moment after the backend does — then refresh.

---

## Step 5 — Log in

Use any of these test accounts. They're created automatically the first time
you start the app.

| Role | Email | Password |
|---|---|---|
| Admin (Super Admin) | `admin@anvicorp.com` | `Admin@Local2026` |
| ERM (recruiting / offers) | `erm@anvicorp.com` | `Erm@Anvi2026` |
| Intern | `intern-a@anvicorp.com` | `TestIntern@123` |
| Manager | `manager@anvicorp.com` | `Manager@Anvi2026` |
| Evaluator | `evaluator@anvicorp.com` | `Evaluator@Anvi2026` |
| Trainer | `trainer@anvicorp.com` | `Trainer@Anvi2026` |

Log in with any of them to see that role's dashboard. To switch roles, log
out and sign in as a different one.

> These accounts exist **only** on your local copy, because the settings file
> from Step 2 switches them on. They are never created on the real site.

---

## What each role does

Each role sees a different set of screens. Here's where to look first:

- **Admin** — document templates and user management.
  Start at **Admin → Document Templates**, where offer letters and onboarding
  forms are designed.
- **ERM** — recruiting and offers. This is the busiest role.
  Start at **Offers**, where offer letters are filled in, sent, signed, and
  finalised.
- **Intern** — the candidate's own view: their application, their offer
  letter to review and sign, and the onboarding documents they upload.
- **Manager** — hiring approvals and the team view: the applicant pipeline
  and active interns.
- **Evaluator** — evaluations and reviews of interns' work.
- **Trainer** — assigning projects, answering intern questions, and tracking
  weekly progress.

A good first tour: log in as **ERM**, open **Offers**, and look at how an
offer letter is filled in and sent. Then log in as the **Intern** to see the
same offer from the other side.

---

## Stopping and restarting

**To stop:** press `Ctrl + C` in the terminal that's running it. Or, from
another terminal in the same folder:

```bash
docker compose down
```

**To start again:**

```bash
docker compose up
```

(No `--build` this time — it's already built, so this takes about 30
seconds.)

**To reset everything and start from a completely fresh database:**

```bash
docker compose down -v
docker compose up
```

The `-v` deletes the stored data, so you get brand-new test accounts and no
leftover records. Handy when you've made a mess and want a clean slate.

---

## If something goes wrong

**"Port is already in use" / "port is already allocated"**
Something else on your computer is already using port 3000 or 8080 (often
another project). Open `.env` and change the number, for example:

```
FRONTEND_PORT=3001
BACKEND_PORT=8081
NEXT_PUBLIC_API_URL=http://localhost:8081
```

⚠️ If you change `BACKEND_PORT`, change the port in `NEXT_PUBLIC_API_URL` to
match — that's how the website knows where to find the backend. Then run
`docker compose up --build` again.

**The first build is taking forever**
Normal if it's the first run — 5 to 10 minutes is expected. As long as text
is still moving, it's working. Only worry if it's totally frozen for more
than 15 minutes.

**Database errors in the log right at the start**
Usually harmless. The backend waits for the database to be ready before
starting, but a few warnings can appear while it waits. If it genuinely
doesn't recover, stop with `Ctrl + C` and run `docker compose up` again.

**Login doesn't work**
The test accounts are created during the first successful startup. If that
first run was interrupted, they may be missing. Fix it with a clean start:

```bash
docker compose down -v
docker compose up
```

Then wait for `Started AnviApiApplication` before trying to log in again.
Also double-check you copied the password exactly — they're case-sensitive
and include the `@`.

**The page loads but everything fails / spins forever**
The website can't reach the backend. Check that `NEXT_PUBLIC_API_URL` in your
`.env` matches `BACKEND_PORT` (both `8080` by default). If you edited either,
you must rebuild: `docker compose up --build`.

**I want to start over completely**

```bash
docker compose down -v
docker compose up --build
```

---

## (Optional) Connecting real S3, email, or Zoom

You don't need any of these — the app is fully usable without them.

- **File storage (S3):** off by default. Uploaded documents and generated
  offer-letter PDFs are saved inside Docker instead, so those features work
  normally. To use a real AWS bucket, set `AWS_S3_ENABLED=true` in `.env` and
  fill in the four `AWS_S3_*` values.
- **Email:** off by default. The app skips sending and logs instead —
  notifications still appear inside the app, they just aren't emailed. To
  send real email, fill in the `SMTP_*` values.
- **Zoom (interview scheduling):** off by default. The scheduling screens
  work; only creating an actual Zoom meeting doesn't. To enable, set
  `ZOOM_ENABLED=true` and fill in the `ZOOM_*` values.

All of these are grouped at the bottom of `.env.example` with notes.

---

## For developers

Running the pieces directly, without Docker, needs **Java 17**, **Node 20+**,
and a local **Postgres**:

```bash
# Backend — from apps/backend (runs the full test suite)
./mvnw clean package
./mvnw spring-boot:run

# Frontend — from apps/frontend
npm install
npm run dev
```

Repository layout:

```
apps/
├── frontend/   Next.js 14 — marketing site + /careers app   (deploys to Vercel)
└── backend/    Spring Boot 3 / Java 17 + PostgreSQL          (deploys to Railway)
_legacy/        Original PHP site, kept for reference only    (not deployed)
```

See [`apps/frontend/README.md`](./apps/frontend/README.md) for frontend
specifics.

⚠️ Never commit a `.env` file. It's gitignored — only `.env.example` belongs
in the repository.
