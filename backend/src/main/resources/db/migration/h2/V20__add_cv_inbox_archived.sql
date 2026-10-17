-- Lets HR archive CV Inbox entries (individually or in bulk) to stop them cluttering
-- the Considered/Excluded tables without deleting the audit record.
ALTER TABLE cv_inbox_item ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
