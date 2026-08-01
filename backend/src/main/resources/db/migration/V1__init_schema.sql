-- ============================================================
-- V1: Initial schema for Agentic Talent Sourcing Platform
-- ============================================================

-- Users / Auth
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    role            VARCHAR(50)  NOT NULL DEFAULT 'RECRUITER', -- RECRUITER, ADMIN, HIRING_MANAGER
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Job Requisitions (roles open for hiring)
CREATE TABLE job_requisition (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    department      VARCHAR(100),
    location        VARCHAR(100),
    description     TEXT NOT NULL,
    required_skills TEXT,            -- comma-separated or JSON list
    experience_level VARCHAR(50),    -- JUNIOR, MID, SENIOR, LEAD
    status          VARCHAR(30) NOT NULL DEFAULT 'OPEN', -- OPEN, ON_HOLD, CLOSED
    created_by      BIGINT REFERENCES app_user(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Candidates (master record - sourced, applied, or referred)
CREATE TABLE candidate (
    id              BIGSERIAL PRIMARY KEY,
    full_name       VARCHAR(255) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(50),
    source_channel  VARCHAR(50) NOT NULL DEFAULT 'MANUAL', -- LINKEDIN, JOBSTREET, REFERRAL, MANUAL, UPLOAD
    resume_text     TEXT,             -- extracted text content
    resume_filename VARCHAR(255),
    skills          TEXT,             -- comma-separated extracted skills
    years_experience NUMERIC(4,1),
    headline        VARCHAR(255),     -- current role / summary line
    profile_url     VARCHAR(500),     -- e.g. LinkedIn URL
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_candidate_email ON candidate(email);
CREATE INDEX idx_candidate_source_channel ON candidate(source_channel);

-- =========================================================
-- SOURCING AGENT
-- Matches candidates in the talent pool to open requisitions
-- =========================================================
CREATE TABLE sourcing_match (
    id              BIGSERIAL PRIMARY KEY,
    candidate_id    BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id  BIGINT NOT NULL REFERENCES job_requisition(id) ON DELETE CASCADE,
    match_score     NUMERIC(5,2) NOT NULL,      -- 0-100
    match_rationale TEXT,                       -- Claude-generated explanation
    is_proactive    BOOLEAN NOT NULL DEFAULT TRUE, -- sourced before req opened vs reactive
    status          VARCHAR(30) NOT NULL DEFAULT 'NEW', -- NEW, REVIEWED, DISMISSED, ADVANCED
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (candidate_id, requisition_id)
);

CREATE INDEX idx_sourcing_match_requisition ON sourcing_match(requisition_id);
CREATE INDEX idx_sourcing_match_score ON sourcing_match(match_score);

-- =========================================================
-- SCREENING AGENT
-- Standardized resume vs JD scoring
-- =========================================================
CREATE TABLE screening_result (
    id                  BIGSERIAL PRIMARY KEY,
    candidate_id        BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id      BIGINT NOT NULL REFERENCES job_requisition(id) ON DELETE CASCADE,
    overall_score       NUMERIC(5,2) NOT NULL,   -- 0-100
    skills_score        NUMERIC(5,2),
    experience_score    NUMERIC(5,2),
    culture_fit_score   NUMERIC(5,2),
    strengths           TEXT,                    -- Claude-generated bullet summary
    gaps                TEXT,                    -- Claude-generated bullet summary
    rationale           TEXT,                    -- full explanation
    recommendation      VARCHAR(30) NOT NULL,    -- ADVANCE, REJECT, REVIEW (edge case)
    is_edge_case        BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by         BIGINT REFERENCES app_user(id), -- human reviewer for edge cases
    reviewer_decision   VARCHAR(30),             -- ADVANCE, REJECT (set by human)
    reviewer_notes      TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (candidate_id, requisition_id)
);

CREATE INDEX idx_screening_requisition ON screening_result(requisition_id);
CREATE INDEX idx_screening_edge_case ON screening_result(is_edge_case);

-- =========================================================
-- REFERRAL AGENT
-- =========================================================
CREATE TABLE referral (
    id                  BIGSERIAL PRIMARY KEY,
    candidate_id        BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id      BIGINT REFERENCES job_requisition(id),  -- nullable: may not target a specific role
    referred_by         BIGINT NOT NULL REFERENCES app_user(id),
    relationship_notes  TEXT,
    match_score         NUMERIC(5,2),            -- auto-match against open roles
    matched_requisition_id BIGINT REFERENCES job_requisition(id),
    status              VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED, MATCHED, SCREENING, ADVANCED, REJECTED, HIRED
    submitted_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    last_updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_referral_status ON referral(status);
CREATE INDEX idx_referral_referred_by ON referral(referred_by);

-- =========================================================
-- ADMIN / COORDINATION AGENT
-- =========================================================
CREATE TABLE interview_schedule (
    id              BIGSERIAL PRIMARY KEY,
    candidate_id    BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id  BIGINT NOT NULL REFERENCES job_requisition(id) ON DELETE CASCADE,
    interview_type  VARCHAR(50) NOT NULL DEFAULT 'SCREENING', -- SCREENING, TECHNICAL, FINAL
    proposed_slots  TEXT,            -- JSON array of ISO datetime strings, agent-suggested
    confirmed_slot  TIMESTAMP,
    status          VARCHAR(30) NOT NULL DEFAULT 'PROPOSED', -- PROPOSED, CONFIRMED, COMPLETED, CANCELLED
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE candidate_status_update (
    id              BIGSERIAL PRIMARY KEY,
    candidate_id    BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    message         TEXT NOT NULL,
    channel         VARCHAR(30) NOT NULL DEFAULT 'EMAIL', -- EMAIL, SMS
    sent_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- =========================================================
-- ORCHESTRATOR
-- Tracks each candidate's overall pipeline stage per requisition
-- =========================================================
CREATE TABLE pipeline_stage (
    id              BIGSERIAL PRIMARY KEY,
    candidate_id    BIGINT NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id  BIGINT NOT NULL REFERENCES job_requisition(id) ON DELETE CASCADE,
    stage           VARCHAR(30) NOT NULL DEFAULT 'SOURCED',
        -- SOURCED, SCREENED, SHORTLISTED, REFERRAL_MATCHED, INTERVIEW_SCHEDULED, OFFER, HIRED, REJECTED
    entered_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by_agent VARCHAR(50),    -- which agent moved it to this stage
    notes           TEXT,
    UNIQUE (candidate_id, requisition_id)
);

CREATE INDEX idx_pipeline_stage_requisition ON pipeline_stage(requisition_id);
CREATE INDEX idx_pipeline_stage_stage ON pipeline_stage(stage);

-- Agent activity log (for audit trail / dashboard feed)
CREATE TABLE agent_activity_log (
    id              BIGSERIAL PRIMARY KEY,
    agent_name      VARCHAR(50) NOT NULL,  -- SOURCING, SCREENING, REFERRAL, ADMIN, ORCHESTRATOR
    candidate_id    BIGINT REFERENCES candidate(id) ON DELETE CASCADE,
    requisition_id  BIGINT REFERENCES job_requisition(id) ON DELETE CASCADE,
    action          VARCHAR(100) NOT NULL,
    details         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_activity_log_agent ON agent_activity_log(agent_name);
CREATE INDEX idx_activity_log_created ON agent_activity_log(created_at);
