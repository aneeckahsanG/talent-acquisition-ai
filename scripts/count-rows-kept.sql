-- Row counts for what the default clean-up keeps (the /all option removes these too).
SELECT 'KEPT' AS action, 'app_user' AS tbl, COUNT(*) AS row_count FROM app_user
UNION ALL SELECT 'KEPT', 'job_requisition', COUNT(*) FROM job_requisition
UNION ALL SELECT 'KEPT', 'resume_source', COUNT(*) FROM resume_source
UNION ALL SELECT 'KEPT', 'mock_job_role', COUNT(*) FROM mock_job_role
UNION ALL SELECT 'KEPT', 'availability_window', COUNT(*) FROM availability_window
UNION ALL SELECT 'KEPT', 'ai_usage_log', COUNT(*) FROM ai_usage_log
ORDER BY 2;
