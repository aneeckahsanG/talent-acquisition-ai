ALTER TABLE job_requisition ADD COLUMN IF NOT EXISTS required_interview_rounds INT NOT NULL DEFAULT 2;
