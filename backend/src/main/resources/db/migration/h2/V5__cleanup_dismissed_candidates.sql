-- Remove pipeline and screening records for candidates dismissed in sourcing
DELETE FROM pipeline_stage
WHERE (candidate_id, requisition_id) IN (
    SELECT candidate_id, requisition_id FROM sourcing_match WHERE status = 'DISMISSED'
);

DELETE FROM screening_result
WHERE (candidate_id, requisition_id) IN (
    SELECT candidate_id, requisition_id FROM sourcing_match WHERE status = 'DISMISSED'
);
