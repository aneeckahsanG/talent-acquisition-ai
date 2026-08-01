# Agentic AI Talent Sourcing Platform - Backend

Spring Boot backend implementing a multi-agent system for talent acquisition:
**Sourcing Agent**, **Screening Agent**, **Referral Agent**, **Admin/Coordination Agent**,
and an **Orchestrator** that ties pipeline state together.

## Tech Stack
- Java 17, Spring Boot 3.3
- PostgreSQL + Flyway migrations
- Spring Security with JWT authentication
- Apache PDFBox for resume text extraction
- Claude API (Anthropic) via WebClient for all agent reasoning

## Prerequisites
- Java 17+
- Maven 3.9+
- PostgreSQL 14+
- An Anthropic API key (https://console.anthropic.com)

## Quickstart with H2 (no database setup needed)

If you don't want to install PostgreSQL, use the built-in H2 in-memory dev profile:

**1. Create your `.env` file** (only `CLAUDE_API_KEY` is required for H2):
```env
CLAUDE_API_KEY=sk-ant-xxxxxxxxxxxxxxxx
JWT_SECRET=any-long-random-string-minimum-32-characters
CORS_ALLOWED_ORIGINS=http://localhost:5173
```

**2. Run with the dev profile:**
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

- No database installation required — H2 runs in memory
- H2 web console available at `http://localhost:8080/h2-console`
  (JDBC URL: `jdbc:h2:mem:talentai`, User: `sa`, Password: blank)
- **Data resets on every restart** — use the default profile + PostgreSQL for persistence

## Setup

### 1. Create the database
```sql
CREATE DATABASE talentai;
CREATE USER talentai WITH PASSWORD 'talentai';
GRANT ALL PRIVILEGES ON DATABASE talentai TO talentai;
```

### 2. Configure your `.env` file
Copy `.env.example` to `.env` in the project root and fill in your values:

```bash
cp .env.example .env
```

Then edit `.env`:
```env
CLAUDE_API_KEY=sk-ant-xxxxxxxxxxxxxxxx
JWT_SECRET=any-long-random-string-minimum-32-characters
DB_USERNAME=talentai
DB_PASSWORD=talentai
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173
```

The `.env` file is loaded automatically on startup via `spring-dotenv` — no shell exports needed. Never commit `.env` to version control (it's already in `.gitignore`).

### 3. Run database migrations + start the app
Flyway migrations run automatically on startup (V1: schema, V2: seed data).

```bash
mvn clean spring-boot:run
```

The API will be available at `http://localhost:8080`.

### 4. Demo login credentials (seeded)
| Username     | Password    | Role           |
|--------------|-------------|----------------|
| admin        | password123 | ADMIN          |
| recruiter1   | password123 | RECRUITER      |
| hiringmgr1   | password123 | HIRING_MANAGER |

> **IMPORTANT**: The seeded password hash is a placeholder. Before running for
> the first time, generate a real BCrypt hash for "password123" (or your own
> password) and update `V2__seed_data.sql`, e.g. using:
> ```java
> new BCryptPasswordEncoder().encode("password123")
> ```
> Or register new users via `POST /api/auth/register`.

## API Overview

### Auth
- `POST /api/auth/login` - `{ username, password }` -> JWT
- `POST /api/auth/register` - create a new user

### Requisitions
- `GET /api/requisitions` - list (optional `?status=OPEN`)
- `POST /api/requisitions` - create
- `PATCH /api/requisitions/{id}/status` - update status

### Screening Agent
- `POST /api/screening/evaluate` - screen by resume text
- `POST /api/screening/evaluate-upload` - screen by uploaded resume file (multipart)
- `GET /api/screening/requisition/{id}` - results for a requisition
- `GET /api/screening/edge-cases` - candidates flagged REVIEW (human-in-the-loop)
- `POST /api/screening/{id}/review` - record human ADVANCE/REJECT decision

### Sourcing Agent
- `POST /api/sourcing/talent-pool` - add candidate to talent pool, auto-match to open roles
- `POST /api/sourcing/talent-pool/{candidateId}/rematch` - re-match against open roles
- `GET /api/sourcing/requisition/{id}/matches` - ranked matches for a requisition
- `PATCH /api/sourcing/matches/{matchId}/status` - update match status

### Referral Agent
- `POST /api/referrals` - submit a referral (auto-matched against open roles)
- `GET /api/referrals` - all referrals
- `GET /api/referrals/mine` - referrals submitted by current user
- `PATCH /api/referrals/{id}/status` - update referral status

### Admin/Coordination Agent
- `POST /api/admin/interviews/propose` - propose interview slots + draft message
- `PATCH /api/admin/interviews/{id}/confirm` - confirm a slot
- `GET /api/admin/interviews/requisition/{id}` - schedules for a requisition
- `POST /api/admin/candidates/{id}/status-update` - log a candidate notification
- `GET /api/admin/candidates/{id}/status-updates` - notification history

### Orchestrator
- `GET /api/orchestrator/pipeline/{requisitionId}` - kanban board by stage
- `GET /api/orchestrator/activity` - cross-agent activity feed
- `GET /api/orchestrator/dashboard` - KPI summary

### Candidates
- `GET /api/candidates` - list all candidates
- `GET /api/candidates/{id}` - candidate detail

## Architecture Notes

- **Human-in-the-loop**: The Screening Agent flags ambiguous/borderline
  candidates with `recommendation: REVIEW` (`isEdgeCase: true`). These are
  surfaced via `/api/screening/edge-cases` for recruiter decision via
  `/api/screening/{id}/review`. Final hiring decisions always require this
  human step.
- **Pipeline stages**: `SOURCED -> SCREENED -> SHORTLISTED -> REFERRAL_MATCHED
  -> INTERVIEW_SCHEDULED -> OFFER -> HIRED / REJECTED`, tracked per
  candidate+requisition in `pipeline_stage` and surfaced by the Orchestrator.
- **Claude integration**: All LLM calls go through `ClaudeApiClient`
  (`common/client`), which centralizes API key handling, retries, and
  JSON-fence stripping. Each agent defines its own system prompt and
  response schema.
- **External platform APIs** (LinkedIn, JobStreet): not integrated in this
  prototype since they require partner access. The Sourcing Agent's
  "talent pool" endpoints simulate this - in production, a scheduled job
  would pull candidates from these APIs and feed them into the same
  `addCandidateAndMatch` flow.
