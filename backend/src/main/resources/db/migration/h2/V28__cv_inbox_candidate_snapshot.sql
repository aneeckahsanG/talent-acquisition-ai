-- Snapshot the candidate's name/email at processing time instead of joining live from the
-- Candidate table — this is an audit log and must not retroactively change if a later file
-- happens to share the same email and overwrites the shared Candidate row's name.
ALTER TABLE cv_inbox_item ADD COLUMN IF NOT EXISTS candidate_name VARCHAR(255);
ALTER TABLE cv_inbox_item ADD COLUMN IF NOT EXISTS candidate_email VARCHAR(255);
