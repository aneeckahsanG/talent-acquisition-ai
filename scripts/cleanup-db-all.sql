-- The rest of a full reset, run after cleanup-db.sql when cleanup-db.bat is given /all.
--
-- REMOVED: users, job requisitions, resume sources, mock job-board roles, interviewer availability windows,
--   AI usage history and the AI spending limits.
-- RESET to its defaults: the Resume Drop Zone connection settings (embedded SFTP, no Drive/SharePoint/SFTP details).
--
-- Nothing is left to sign in with, but the backend re-creates the four demo users (admin, recruiter1, hiringmgr1,
-- interviewer1, all with password123) and three demo job requisitions the next time it starts, and recreates the
-- AI limits (none) when it first needs them. Anything else you added by hand is gone.

SET REFERENTIAL_INTEGRITY FALSE;

TRUNCATE TABLE job_requisition RESTART IDENTITY;
TRUNCATE TABLE resume_source RESTART IDENTITY;
TRUNCATE TABLE mock_job_role RESTART IDENTITY;
TRUNCATE TABLE availability_window RESTART IDENTITY;
TRUNCATE TABLE ai_usage_log RESTART IDENTITY;
TRUNCATE TABLE app_user RESTART IDENTITY;
DELETE FROM ai_budget;

UPDATE cv_inbox_settings SET
    mode = 'EMBEDDED',
    embedded_port = 2222, embedded_username = 'hr', embedded_password = 'change-me',
    external_host = NULL, external_port = NULL, external_username = NULL, external_password = NULL, external_root_path = NULL,
    proxy_enabled = FALSE, proxy_host = NULL, proxy_port = NULL,
    google_folder_id = NULL, google_service_account_json = NULL,
    sharepoint_tenant_id = NULL, sharepoint_client_id = NULL, sharepoint_client_secret = NULL, sharepoint_folder_link = NULL;

SET REFERENTIAL_INTEGRITY TRUE;
