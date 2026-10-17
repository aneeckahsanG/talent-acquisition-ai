-- One-time backfill for cv_inbox_item rows processed before V18 added the
-- candidate_name/candidate_email snapshot columns — they predate the fix and were
-- left null. Fill them from the candidate's current record as a best-effort backfill
-- (new rows going forward always snapshot correctly at processing time).
UPDATE cv_inbox_item i
SET candidate_name = (SELECT c.full_name FROM candidate c WHERE c.id = i.candidate_id),
    candidate_email = (SELECT c.email FROM candidate c WHERE c.id = i.candidate_id)
WHERE i.candidate_name IS NULL
  AND i.candidate_id IS NOT NULL;
