# TalentAcquisition AI — System Architecture

All diagrams below reflect the implementation on branch `feature/spring-ai-refactor`
(HEAD `aa9a560`). Component names map to real packages and classes.

**Consistent colour legend used throughout:**

| Colour | Meaning |
|---|---|
| 🟦 Blue | Frontend / user-facing surfaces |
| 🟩 Green | Backend application & business logic |
| 🟪 Purple | AI agents & LLM components |
| 🟧 Orange | Data & persistence |
| ⬜ Grey | External systems & integrations |

---

## Diagram 1 — System Context

Who uses the system, what it talks to, and where its boundary sits.

```mermaid
graph TB
    subgraph people["People"]
        REC["👤 Recruiter<br/><i>triage, pipeline, approvals</i>"]
        HM["👤 Hiring Manager<br/><i>pipeline & decision quality</i>"]
        INT["👤 Interviewer<br/><i>own interviews + scorecards</i>"]
        ADM["👤 Administrator<br/><i>users, AI budget</i>"]
        CAND["👤 Candidate<br/><i>applies, books, signs</i>"]
        EMP["👤 Employee<br/><i>submits referrals</i>"]
    end

    SYS["🎯 <b>TalentAcquisition AI</b><br/>Agentic recruiting platform<br/>──────────────<br/>Sourcing · Screening · Pipeline<br/>Interviewing · Offers · AI Governance"]

    subgraph ext["External Systems"]
        CLAUDE["🤖 Anthropic Claude API<br/><i>via Spring AI</i>"]
        SMTP["✉️ SMTP / Email<br/><i>Gmail</i>"]
        SFTP["📁 Agency SFTP Server<br/><i>bulk CV drops</i>"]
        GD["☁️ Google Drive<br/><i>CV folder</i>"]
        SP["☁️ SharePoint<br/><i>CV folder</i>"]
        JB["🌐 Job Board<br/><i>Apply-Connect webhook</i>"]
    end

    REC --> SYS
    HM --> SYS
    INT --> SYS
    ADM --> SYS
    CAND -->|"careers page · booking link · offer link"| SYS
    EMP -->|"referral portal"| SYS

    SYS <-->|"prompts / completions / tool calls"| CLAUDE
    SYS -->|"invitations, reminders, offers"| SMTP
    SFTP -->|"CV files"| SYS
    GD -->|"CV files"| SYS
    SP -->|"CV files"| SYS
    JB -->|"POST /api/inbound/applications/{id}"| SYS

    style SYS fill:#1e3a8a,stroke:#1e40af,stroke-width:3px,color:#fff
    style CLAUDE fill:#ede9fe,stroke:#7c3aed,stroke-width:2px
    style people fill:#eff6ff,stroke:#3b82f6
    style ext fill:#f8fafc,stroke:#94a3b8
```

**Boundary note:** the platform owns intake, evaluation, coordination and offer execution.
It deliberately does *not* own the hiring decision — that stays with humans at every gated step.

---

## Diagram 2 — High-Level Technical Architecture

```mermaid
graph TB
    subgraph fe["🟦 Presentation — React 18 + Vite (:5173)"]
        APP["TalentOpsApp<br/><i>recruiter workspace</i>"]
        PUB["Public pages<br/><i>InterviewBook · OfferRespond</i>"]
        SSR["Server-rendered<br/><i>/careers · /mock-jobboard</i>"]
    end

    subgraph api["🟩 API Layer — Spring MVC · 25 controllers · 147 endpoints"]
        SEC["🔒 Spring Security<br/>JWT · BCrypt · stateless<br/>4 roles"]
        CTRL["Controllers<br/><i>requisition · screening · sourcing<br/>orchestrator · cv-inbox · calendar<br/>interviewer · intelligence · users</i>"]
        PUBAPI["Public endpoints<br/><i>inbound webhook · offer-respond<br/>interview-book · referrals</i>"]
    end

    subgraph biz["🟩 Business Services — 32 @Service classes"]
        REQ["RequisitionService"]
        ORCH["OrchestratorService<br/><i>pipeline board · stages</i>"]
        BOOK["InterviewBookingService<br/><i>clash-safe booking</i>"]
        REM["InterviewReminderService<br/><i>reminders · chasers</i>"]
        OFF["OfferResponseService<br/><i>token · e-signature · PDF</i>"]
        CVI["CvInboxProcessingService"]
        QUAL["ScreeningQualityService<br/><i>AI-vs-human agreement</i>"]
        DIR["CandidateDirectoryService<br/><i>+ access audit</i>"]
    end

    subgraph ai["🟪 AI Layer — Spring AI 1.1.8"]
        CC["ChatClient<br/><i>model: ${CLAUDE_MODEL}</i>"]
        ADV["⚡ AiUsageAdvisor<br/><i>CallAdvisor: meters every call</i>"]
        GUARD["🛑 AiBudgetGuard<br/><i>daily/monthly USD · hard stop</i>"]
        subgraph agents["Agents"]
            AG1["Sourcing Agent"]
            AG2["Screening Agent"]
            AG3["Referral Agent"]
            AG4["Coordination Agent"]
            AG5["🧠 Orchestrator Agent<br/><i>tool-calling loop · 12 tools</i>"]
            AG6["Assistant"]
        end
        GATE["✋ HumanApprovalGate<br/><i>HITL on candidate-facing actions</i>"]
        PL["PromptLoader<br/><i>10 versioned .md prompts</i>"]
        RETRY["AiCallRetry · AiJsonExtractor<br/><i>resilience</i>"]
    end

    subgraph src["🟩 Intake — CvSource Strategy"]
        S1["EmbeddedFolderSource"]
        S2["SftpSource<br/><i>JSch + HTTP proxy</i>"]
        S3["GoogleDriveSource"]
        S4["SharePointSource"]
    end

    subgraph data["🟧 Persistence"]
        JPA["Spring Data JPA<br/><i>30 entities</i>"]
        DB[("H2 file DB<br/><i>PostgreSQL mode</i><br/>31 tables")]
        FLY["Flyway<br/><i>39 migrations</i>"]
    end

    subgraph extsys["⬜ External"]
        CLAUDE["Anthropic Claude API"]
        MAIL["SMTP"]
        MINA["Apache MINA SSHD<br/><i>embedded SFTP :2222</i>"]
        GAPI["Google Drive API"]
        MSG["Microsoft Graph API"]
    end

    APP --> SEC
    PUB --> PUBAPI
    SSR --> CTRL
    SEC --> CTRL
    CTRL --> biz
    PUBAPI --> biz

    biz --> CC
    AG5 -.->|"12 @Tool methods"| biz
    AG5 --> GATE
    CC --> ADV
    ADV --> GUARD
    ADV --> CLAUDE
    PL --> CC
    RETRY --> CC
    agents --> CC

    CVI --> src
    S2 --> MINA
    S3 --> GAPI
    S4 --> MSG
    biz --> JPA
    JPA --> DB
    FLY --> DB
    biz --> MAIL

    style fe fill:#eff6ff,stroke:#3b82f6,stroke-width:2px
    style api fill:#f0fdf4,stroke:#22c55e,stroke-width:2px
    style biz fill:#f0fdf4,stroke:#22c55e,stroke-width:2px
    style src fill:#f0fdf4,stroke:#22c55e,stroke-width:2px
    style ai fill:#f5f3ff,stroke:#8b5cf6,stroke-width:2px
    style agents fill:#ede9fe,stroke:#7c3aed
    style data fill:#fff7ed,stroke:#f97316,stroke-width:2px
    style extsys fill:#f8fafc,stroke:#94a3b8,stroke-width:2px
    style AG5 fill:#ddd6fe,stroke:#6d28d9,stroke-width:3px
    style GATE fill:#fef3c7,stroke:#d97706,stroke-width:3px
    style GUARD fill:#fee2e2,stroke:#dc2626,stroke-width:2px
```

### Why these choices

| Decision | Rationale |
|---|---|
| **Modular monolith**, package-by-feature | One deployable, no distributed-transaction cost; feature packages give clean seams if extraction is ever needed |
| **Spring AI** over raw HTTP | Gives `ChatClient`, tool-calling, and the `CallAdvisor` hook that makes cost metering cross-cutting rather than per-call-site |
| **Prompts as versioned `.md` files** | Prompt changes are reviewable in diffs with `last_updated` front-matter — prompts are treated as source, not literals |
| **`CvSource` Strategy** | Adding a 5th channel means one class, not a new pipeline |
| **H2 file + PostgreSQL mode** | Zero-setup demo that runs anywhere; migrations are already Postgres-compatible for the real target |
| **Haiku-class default model** | Screening/extraction are structured-output tasks; cost fell substantially with no measured quality loss on the eval set |

---

## Diagram 3 — User Interaction & Workflow (end-to-end application)

```mermaid
sequenceDiagram
    actor C as 👤 Candidate
    participant W as 🌐 Careers / Job Board
    participant API as 🟩 Inbound API
    participant SA as 🟪 Sourcing Agent
    participant SC as 🟪 Screening Agent
    participant FU as 🟩 FollowUpService
    participant OA as 🧠 Orchestrator Agent
    participant GT as ✋ Approval Gate
    participant DB as 🟧 Database
    actor R as 👤 Recruiter

    C->>W: Submit application + CV
    W->>API: POST /api/inbound/applications/{reqId}
    API->>DB: Save candidate + sourcing_match
    API-->>C: 200 — "Application received"
    Note over API,C: Candidate is never blocked<br/>by AI processing

    API->>SA: Score fit against role
    SA-->>DB: match_score + rationale
    API->>SC: Apply screening rubric
    SC-->>DB: skills/experience/culture + recommendation

    API->>FU: afterApplication(...)
    FU->>R: ✉️ Notify HR of new application
    FU->>OA: startRunAsync()

    rect rgb(245, 243, 255)
        Note over OA: Tool-calling loop (max 20 steps)
        OA->>DB: screenCandidate (reuses saved result)
        OA->>DB: getPipelineBoard
        alt ADVANCE (score ≥ 75)
            OA->>DB: moveCandidateStage → SHORTLISTED
            OA->>OA: draftOutreach
            OA->>GT: sendOutreach → requires approval
            GT-->>OA: PENDING_APPROVAL
        else REVIEW / borderline
            OA->>DB: moveCandidateStage → SCREENED
            Note over OA: Hold for human — never auto-decide
        else REJECT
            OA->>GT: rejection → requires approval
            GT-->>OA: PENDING_APPROVAL
        end
    end

    OA->>DB: Persist run status PAUSED_FOR_APPROVAL<br/>+ exact pending payload
    Note over DB: Survives a backend restart

    R->>DB: Review run + exact email text
    R->>OA: Approve
    OA->>GT: Grant approved actions
    OA->>C: ✉️ Execute approved action (outreach / rejection)
    OA->>DB: Status COMPLETED + summary
```

---

## Diagram 4 — Core Feature Interaction

### 4a. How the subsystems depend on each other

```mermaid
graph LR
    INTAKE["📥 <b>Intake</b><br/>careers · webhook<br/>drop zone · referral"]
    SOURCE["🟪 <b>Sourcing</b><br/>fit score 0-100"]
    SCREEN["🟪 <b>Screening</b><br/>rubric + recommendation"]
    ORCHA["🧠 <b>Orchestrator</b><br/>12 tools · HITL"]
    PIPE["📊 <b>Pipeline</b><br/>stage machine"]
    INTV["📅 <b>Interviewing</b><br/>booking · reminders<br/>scorecards"]
    DEBRIEF["🟪 <b>AI Debrief</b><br/>synthesise scorecards"]
    OFFER["📄 <b>Offer</b><br/>letter · e-sign · PDF"]
    QUALITY["📈 <b>Screening Quality</b><br/>AI vs human agreement"]
    USAGE["💰 <b>AI Governance</b><br/>cost · budget · hard stop"]

    INTAKE --> SOURCE --> SCREEN --> ORCHA --> PIPE
    PIPE --> INTV --> DEBRIEF --> OFFER
    SCREEN -.->|"decisions + human overrides<br/>become the eval dataset"| QUALITY
    SOURCE -.-> USAGE
    SCREEN -.-> USAGE
    ORCHA -.-> USAGE
    DEBRIEF -.-> USAGE
    USAGE -.->|"hard stop can refuse calls"| SCREEN
    QUALITY -.->|"informs rubric tuning"| SCREEN

    style ORCHA fill:#ddd6fe,stroke:#6d28d9,stroke-width:3px
    style SOURCE fill:#ede9fe,stroke:#7c3aed
    style SCREEN fill:#ede9fe,stroke:#7c3aed
    style DEBRIEF fill:#ede9fe,stroke:#7c3aed
    style QUALITY fill:#dcfce7,stroke:#16a34a,stroke-width:2px
    style USAGE fill:#fee2e2,stroke:#dc2626,stroke-width:2px
```

**The two dotted feedback loops are the architecturally interesting part:**
every screening decision and every human override becomes training signal for
*Screening Quality*, and every LLM call is metered by *AI Governance* which can refuse
further calls when the budget is exhausted.

### 4b. The HITL approval gate in detail

```mermaid
sequenceDiagram
    participant OA as 🧠 Orchestrator Agent
    participant T as 🔧 RecruitingTools
    participant G as ✋ HumanApprovalGate
    participant DB as 🟧 agent_run
    actor R as 👤 Recruiter

    OA->>T: sendOutreach(matchId, emailBody)
    T->>G: isApproved(actionKey)?
    G-->>T: false
    T->>G: requestApproval(key, description, payload, runId)
    T-->>OA: "PENDING_APPROVAL — awaiting recruiter"

    OA->>DB: status=PAUSED_FOR_APPROVAL<br/>pendingTool + payload (exact email text)
    Note over DB,R: 🔄 Backend can restart here<br/>without losing the request

    R->>DB: Open run — sees tool, description, exact text
    R->>OA: approve(runId)
    OA->>G: approveRun + re-grant saved approvals
    Note over OA,G: Re-grant covers the in-memory gate<br/>having been cleared by a restart
    OA->>OA: Re-run loop with "approved: ..." appended to goal
    OA->>T: sendOutreach(same args)
    T->>G: isApproved? → true
    T->>T: ✉️ Actually send
    T-->>OA: "Sent"
    OA->>DB: status=COMPLETED + summary
```

---

## Diagram 5 — Deployment Architecture

**Honest statement of current state:** the system runs as a local two-process development
deployment. There is no Dockerfile, CI pipeline or cloud manifest in the repository.
The left side is what exists today; the right side is the documented target.

```mermaid
graph TB
    subgraph current["✅ Current — Local Development (what exists)"]
        direction TB
        DEV["💻 Developer / Demo Machine<br/><i>scripts/start.bat</i>"]
        subgraph procs["Two processes"]
            BE["☕ Spring Boot<br/><i>mvn spring-boot:run</i><br/>:8080"]
            FEP["⚡ Vite dev server<br/><i>npm run dev</i><br/>:5173 → proxies /api"]
        end
        FILES[("📁 Local files<br/>backend/data/talentai.mv.db<br/>backend/data/cv-inbox/")]
        MINAS["📡 Embedded SFTP<br/>Apache MINA :2222"]
        ENVF["🔑 backend/.env<br/><i>CLAUDE_API_KEY · CLAUDE_MODEL<br/>JWT_SECRET · AI_ENABLED</i>"]

        DEV --> BE
        DEV --> FEP
        FEP -->|"/api proxy"| BE
        BE --> FILES
        BE --> MINAS
        ENVF -.->|"spring-dotenv"| BE
    end

    subgraph target["🎯 Target — Production (not yet implemented)"]
        direction TB
        CDN["🌐 CDN / static host<br/><i>built React bundle</i>"]
        LB["⚖️ Load balancer + TLS"]
        APPC["🐳 App containers<br/><i>stateless, N replicas</i>"]
        PG[("🐘 PostgreSQL<br/><i>driver + migrations already present</i>")]
        REDIS[("⚡ Shared store<br/><i>needed for approval gate<br/>+ budget totals</i>")]
        SECRETS["🔐 Secret manager"]
        OBS["📊 Metrics / logs / traces"]

        CDN --> LB --> APPC
        APPC --> PG
        APPC --> REDIS
        SECRETS -.-> APPC
        APPC -.-> OBS
    end

    EXT["⬜ Anthropic Claude API · SMTP · Google Drive · SharePoint"]
    BE --> EXT
    APPC --> EXT

    style current fill:#f0fdf4,stroke:#22c55e,stroke-width:2px
    style target fill:#f8fafc,stroke:#94a3b8,stroke-width:2px,stroke-dasharray: 5 5
    style BE fill:#dcfce7,stroke:#16a34a
    style FEP fill:#dbeafe,stroke:#2563eb
    style FILES fill:#fff7ed,stroke:#f97316
    style PG fill:#fff7ed,stroke:#f97316
    style REDIS fill:#fff7ed,stroke:#f97316
```

### What must change for production (stated plainly)

| # | Change | Why |
|---|---|---|
| 1 | Remove `permitAll()` on `/api/admin/**` and `/h2-console/**` | Currently unauthenticated — demo convenience, real gap |
| 2 | Move SMTP credentials out of `application.yml` | Only secret still committed in plaintext |
| 3 | Switch datasource to PostgreSQL | Driver and Postgres-mode migrations already in place; low effort |
| 4 | Externalise `HumanApprovalGate` + `AiBudgetGuard` state | Both in-memory per instance; blocks horizontal scaling |
| 5 | Add `@EnableScheduling` or an external scheduler | The 6-hourly resume sync is currently inert by design |
| 6 | Containerise + CI | No Dockerfile or pipeline today |
| 7 | Turn off `demo-mode` | It intentionally relaxes candidate email uniqueness |

---

## Technology Stack (verified against `pom.xml` / `package.json`)

| Layer | Technology |
|---|---|
| Language | Java 21 · JavaScript (ES2022) |
| Backend | Spring Boot 3.3.4 · Spring MVC · Spring WebFlux · Spring Data JPA · Spring Security · Spring Mail |
| AI | Spring AI 1.1.8 (`spring-ai-starter-model-anthropic`) · Anthropic Claude |
| Persistence | H2 (file, PostgreSQL mode) · Flyway 39 migrations · PostgreSQL driver ready |
| Auth | JJWT · BCrypt · stateless JWT |
| File handling | Apache PDFBox (PDF) · direct OOXML read (.docx) · OpenCSV |
| Transfer | Apache MINA SSHD 2.13.2 (embedded SFTP server) · JSch 0.2.21 (SFTP client + HTTP proxy) |
| Frontend | React 18.3 · Vite 5.4 · Tailwind CSS 3.4 · lucide-react · native `fetch` |
| Config | spring-dotenv 4.0 |
| Test | JUnit 5 · Spring Boot Test · Spring Security Test · AssertJ |
