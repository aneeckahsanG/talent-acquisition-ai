-- Row counts for the candidate data that cleanup-db.sql removes.
SELECT 'REMOVED' AS action, 'candidate' AS tbl, COUNT(*) AS row_count FROM candidate
UNION ALL SELECT 'REMOVED', 'pipeline_stage', COUNT(*) FROM pipeline_stage
UNION ALL SELECT 'REMOVED', 'screening_result', COUNT(*) FROM screening_result
UNION ALL SELECT 'REMOVED', 'sourcing_match', COUNT(*) FROM sourcing_match
UNION ALL SELECT 'REMOVED', 'referral', COUNT(*) FROM referral
UNION ALL SELECT 'REMOVED', 'interview_schedule', COUNT(*) FROM interview_schedule
UNION ALL SELECT 'REMOVED', 'interview_slot', COUNT(*) FROM interview_slot
UNION ALL SELECT 'REMOVED', 'interview_scorecard', COUNT(*) FROM interview_scorecard
UNION ALL SELECT 'REMOVED', 'interview_debrief', COUNT(*) FROM interview_debrief
UNION ALL SELECT 'REMOVED', 'interview_reminder', COUNT(*) FROM interview_reminder
UNION ALL SELECT 'REMOVED', 'offer', COUNT(*) FROM offer
UNION ALL SELECT 'REMOVED', 'offer_letter', COUNT(*) FROM offer_letter
UNION ALL SELECT 'REMOVED', 'offer_negotiation_history', COUNT(*) FROM offer_negotiation_history
UNION ALL SELECT 'REMOVED', 'offer_response_token', COUNT(*) FROM offer_response_token
UNION ALL SELECT 'REMOVED', 'cv_inbox_item', COUNT(*) FROM cv_inbox_item
UNION ALL SELECT 'REMOVED', 'agent_run', COUNT(*) FROM agent_run
UNION ALL SELECT 'REMOVED', 'agent_step', COUNT(*) FROM agent_step
UNION ALL SELECT 'REMOVED', 'agent_activity_log', COUNT(*) FROM agent_activity_log
UNION ALL SELECT 'REMOVED', 'notification_log', COUNT(*) FROM notification_log
UNION ALL SELECT 'REMOVED', 'candidate_status_update', COUNT(*) FROM candidate_status_update
UNION ALL SELECT 'REMOVED', 'candidate_access_log', COUNT(*) FROM candidate_access_log
UNION ALL SELECT 'REMOVED', 'mock_application', COUNT(*) FROM mock_application
ORDER BY 2;
