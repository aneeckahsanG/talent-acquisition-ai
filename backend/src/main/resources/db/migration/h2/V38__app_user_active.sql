-- Lets an administrator disable an account without deleting it (the user's interviews, scorecards
-- and audit trail keep pointing at the row). Existing users stay active.
ALTER TABLE app_user ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
