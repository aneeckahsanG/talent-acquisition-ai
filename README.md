# TalentAcquisition AI

An AI-powered talent acquisition platform with job requisition management, AI-driven candidate sourcing and screening, a public careers page, and an integrated mock job board that forwards applications via webhook — similar to how LinkedIn Apply Connect works.

---

## Architecture

```
talent-acquisition-ai/
├── backend/                          # Spring Boot 3.3 (Java 21)
│   └── src/main/java/com/talentai/
│       ├── auth/                     # JWT authentication
│       ├── sourcing/                 # Core: sourcing, screening, AI agent
│       │   ├── controller/
│       │   │   ├── MockJobBoardController.java   # Mock job board + webhook
│       │   │   ├── InboundApplicationController.java  # Public webhook receiver
│       │   │   └── PublicJobController.java       # Careers page
│       │   ├── service/
│       │   │   ├── SourcingAgentService.java      # AI agent orchestration
│       │   │   ├── ResumeSyncService.java         # Bulk candidate sync
│       │   │   └── ClaudeApiClient.java           # Anthropic API integration
│       │   └── entity/
│       │       ├── MockJobRole.java
│       │       └── SourcingMatch.java
│       └── config/                   # Security, exception handling
├── talent-ops-frontend-final/        # React + Vite frontend (port 5173)
│   └── src/
│       ├── components/               # Dashboard UI components
│       └── pages/                    # Main app pages
└── .claude/                          # Claude Code project config
    └── launch.json                   # Dev server launch config
```

## Key Features

- **Job Requisitions** — Create and manage open roles
- **AI Sourcing** — Claude-powered candidate matching and screening
- **Careers Page** — Public `/careers` with apply modal and duplicate prevention
- **Mock Job Board** — `/mock-jobboard` simulating an external job board (LinkedIn-style)
- **Webhook Integration** — Link mock job board roles to TalentAI requisitions; applications forward instantly via HTTP POST to `/api/inbound/applications/{requisitionId}`
- **CV Parsing** — Upload resume in apply modal; AI extracts name, skills, experience
- **Talent Pool** — Manual candidate addition with AI scoring

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 (Eclipse Adoptium recommended) |
| Maven | 3.9+ |
| Node.js | 18+ |

---

## Running Locally

### Backend

```bash
cd backend
mvn spring-boot:run
```

Backend starts on **http://localhost:8080**

Default admin credentials (set in `application.properties`):
- Email: `admin@talentai.com`
- Password: `admin123`

### Frontend

```bash
cd talent-ops-frontend-final
npm install
npm run dev
```

Frontend starts on **http://localhost:5173** and proxies `/api` to the backend.

---

## Key Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /careers` | Public careers page |
| `GET /mock-jobboard` | Mock external job board |
| `POST /api/inbound/applications/{requisitionId}` | Public webhook — receives applications |
| `PATCH /mock-jobboard/jobs/{id}/link` | Link a mock job to a TalentAI requisition |
| `POST /mock-jobboard/api/sync` | Bulk sync past applicants to TalentAI |
| `POST /api/public/parse-resume` | AI resume text extraction |

---

## Webhook Flow

1. Recruiter creates a requisition in TalentAI
2. In Mock Job Board admin, click **🔗 Webhook** on a role → select the requisition → Save
3. The role is now linked: `webhook_url = http://localhost:8080/api/inbound/applications/{id}`
4. When a candidate applies on the mock job board, TalentAI instantly receives the application as a direct applicant

This mirrors the LinkedIn Apply Connect / Greenhouse Apply API pattern.

---

## Tech Stack

- **Backend:** Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, H2 (file-based), Flyway, WebFlux WebClient
- **AI:** Anthropic Claude API (raw HTTP via WebClient)
- **Frontend:** React 18, Vite, Axios
- **Build:** Maven
