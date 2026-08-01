ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS invitation_email TEXT;
ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS candidate_reply TEXT;
ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS candidate_replied_at TIMESTAMP;
