# TalentAcquisition AI — Feature Walkthrough

A hands-on guide to exploring everything the app can do. For architecture and
dev setup details, see the main [README.md](../README.md); this doc focuses on
*using* the running app.

## 1. Start the app

From the project root:

```bash
scripts\start.bat
```

This frees ports 8080/5173 if anything is already on them, clears old logs,
and launches both services in the background:

- **Backend** (Spring Boot + H2): http://localhost:8080 — log at `scripts/backend.log`
- **Frontend** (Vite/React): http://localhost:5173 — log at `scripts/frontend.log`

Give it ~15–20 seconds for the backend to finish compiling and starting
(check `scripts/backend.log` for `Started TalentSourcingAgentApplication`).
Then open **http://localhost:5173**.

To stop everything: `scripts\stop.bat`.

## 2. Log in

Three demo accounts are seeded automatically (password is the same for all):

| Username      | Password      | Role           | Persona            |
|---------------|---------------|----------------|---------------------|
| `recruiter1`  | `password123` | RECRUITER      | Aisha Rahman — day-to-day recruiting work |
| `hiringmgr1`  | `password123` | HIRING_MANAGER | Daniel Lim — reviews pipeline/decisions |
| `admin`       | `password123` | ADMIN          | System Admin |

Start with `recruiter1` — it has the fullest view of the app.

## 3. The feature tour

The left sidebar has six sections. Here's what to try in each.

### Dashboard
Landing page after login. Shows:
- Summary tiles (open requisitions, candidates in pipeline, screened this week,
  edge cases to review, stalled candidates, interviews scheduled) — click any
  tile to jump to the relevant section.
- **Agent Activity Feed** — a live log of what the AI sourcing/screening agent
  has been doing.
- **Needs Your Decision** — items waiting on a human call (edge-case screens).
- **Pipeline Overview** and **Talent Market Insights** — screening pass rate by
  role and funnel breakdown (Advance / Review / Reject).

### Open Roles (job requisitions)
- Three demo roles are pre-seeded: *Senior Backend Engineer (Java)*,
  *Data Analyst*, *AI/ML Engineer*.
- Click **Create Open Role** to add a new requisition (title, department,
  location, description, required skills, experience level).
- Open a role's detail panel to see its **Inbound Webhook URL** card — this is
  the integration point used by the mock job board (see §5).

### Pipeline
- Kanban-style board of candidates moving through stages: `SOURCED` →
  `SCREENED` → `SHORTLISTED` → `INTERVIEW_SCHEDULED` → offer/hired.
  Each stage has an action button to advance the candidate.
- From here you can draft outreach emails, create an offer letter, record a
  candidate's reply, and reschedule interviews — all via modal dialogs.

### Screening (the AI agent in action)
This is the core "agentic" feature. Three ways to get a candidate screened:
1. **Manual Entry** — type/paste candidate details directly.
2. **Resume File** — upload a PDF/text resume; Claude parses it into
   structured fields (name, skills, experience) automatically.
3. **CSV Import** — bulk-import a list of candidates at once.

After screening, each candidate gets an AI-generated report with **Skills**,
**Experience**, and **Culture Fit** scores, plus an overall recommendation:
**Advance**, **Review**, or **Reject**. Click "View Report" on any screened
candidate to see the full reasoning, then act on it (advance to pipeline,
reject, or flag for human review).

### Referral
Two surfaces:
- **Internal (Referral tab in the app)** — recruiters/employees can submit a
  referral with a resume upload; the Referral Agent auto-matches the candidate
  against all open roles and can auto-screen them immediately.
- **Public referral portal** — open **http://localhost:5173/refer** in a new
  tab (no login needed). This is what you'd actually share with employees:
  a simple "Refer Someone" form with resume upload.

### Integrations
Configure where candidates get sourced from automatically:
- **Resume Sources** — add a LinkedIn, JobStreet, or Custom source (a URL),
  then **Sync** (one source) or **Sync All**. Sources can be paused/resumed
  or deleted. Each shows its last sync time and how many candidates it added.

## 4. Public-facing pages (no login required)

These simulate the outside world interacting with the platform:

- **http://localhost:8080/careers** — the public careers page job seekers see.
  Includes an apply modal with resume upload and duplicate-application
  prevention.
- **http://localhost:8080/mock-jobboard** — a simulated external job board
  (think LinkedIn/JobStreet) used to demo the webhook integration end-to-end:
  1. In **Open Roles**, note a requisition's ID / open its webhook card.
  2. On the mock job board admin, click **🔗 Webhook** on a listing → select
     the TalentAI requisition → Save.
  3. Apply to that listing as a candidate (simulating an external applicant).
  4. Switch back to TalentAI → **Pipeline** — the application should appear
     almost instantly, forwarded via `POST /api/inbound/applications/{id}`.
  5. You can also **bulk sync** past applicants from the mock board's
     `/mock-jobboard/api/sync` instead of applying one at a time.
- **http://localhost:5173/refer** — public referral form (see §3, Referral).

## 5. Suggested 10-minute walkthrough

1. Log in as `recruiter1`, look at the Dashboard.
2. Go to **Open Roles**, open the *AI/ML Engineer* role, note its webhook URL.
3. Go to **Screening** → upload a sample resume (or use Manual Entry) → review
   the AI's Skills/Experience/Culture Fit scores and recommendation.
4. Advance that candidate to the **Pipeline** and move it through a stage.
5. Open `/mock-jobboard` in a new tab, link a listing to the role from step 2,
   apply as a test candidate, then check **Pipeline** in TalentAI for the new
   inbound applicant.
6. Open `/refer` in a new tab, submit a referral, then check the **Referral**
   tab to see it auto-matched.
7. Back on the Dashboard, check **Talent Market Insights** to see the funnel
   update.

## Reference: key API endpoints

| Endpoint | Purpose |
|----------|---------|
| `POST /api/auth/login` | Login, returns JWT |
| `GET /careers` | Public careers page |
| `GET /mock-jobboard` | Mock external job board |
| `POST /api/inbound/applications/{requisitionId}` | Webhook receiver for inbound applications |
| `PATCH /mock-jobboard/jobs/{id}/link` | Link a mock job listing to a requisition |
| `POST /mock-jobboard/api/sync` | Bulk sync past applicants |
| `POST /api/public/parse-resume` | AI resume parsing (used by apply/referral forms) |
| `POST /api/referrals/public` | Public referral submission |
