-- V41 rebuilt the current stage from pipeline_stage.entered_at. For a candidate whose interview was booked, that
-- time was reset when the candidate picked their slot, so the move to INTERVIEW_SCHEDULED looked as if it came
-- after the interview. The move really happened when the first booking link was sent, which is when the first
-- interview record was created. Bring that step back to just before it; later re-entries and steps already
-- earlier than the interview are left alone.
UPDATE pipeline_stage_history h
SET occurred_at = DATEADD('MILLISECOND', -1, (
    SELECT MIN(i.created_at) FROM interview_schedule i
    WHERE i.candidate_id = h.candidate_id AND i.requisition_id = h.requisition_id))
WHERE h.to_stage = 'INTERVIEW_SCHEDULED'
  AND h.from_stage IN ('SCREENED', 'SHORTLISTED')
  AND NOT EXISTS (SELECT 1 FROM pipeline_stage_history e
                  WHERE e.candidate_id = h.candidate_id AND e.requisition_id = h.requisition_id
                    AND e.to_stage = 'INTERVIEW_SCHEDULED' AND e.id <> h.id AND e.occurred_at < h.occurred_at)
  AND h.occurred_at > (SELECT MIN(i.created_at) FROM interview_schedule i
                       WHERE i.candidate_id = h.candidate_id AND i.requisition_id = h.requisition_id);
