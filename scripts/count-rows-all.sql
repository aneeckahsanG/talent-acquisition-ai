-- Row counts for the extra data that the /all option removes or resets, on top of the candidate data.
SELECT 'REMOVED' AS action, 'app_user' AS tbl, COUNT(*) AS row_count FROM app_user
UNION ALL SELECT 'REMOVED', 'job_requisition', COUNT(*) FROM job_requisition
UNION ALL SELECT 'REMOVED', 'resume_source', COUNT(*) FROM resume_source
UNION ALL SELECT 'REMOVED', 'mock_job_role', COUNT(*) FROM mock_job_role
UNION ALL SELECT 'REMOVED', 'availability_window', COUNT(*) FROM availability_window
UNION ALL SELECT 'REMOVED', 'ai_usage_log', COUNT(*) FROM ai_usage_log
UNION ALL SELECT 'REMOVED', 'ai_budget', COUNT(*) FROM ai_budget
UNION ALL SELECT 'RESET', 'cv_inbox_settings', COUNT(*) FROM cv_inbox_settings
ORDER BY 2;
