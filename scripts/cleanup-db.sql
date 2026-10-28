-- Clears everything about candidates and their journey, and keeps the set-up data.
--
-- REMOVED: candidates and their profiles, applications, pipeline stages, screening results, sourcing matches,
--   referrals, interviews (schedules, bookings, scorecards, debriefs, reminders), offers (letters, negotiation
--   history, response links), Resume Drop Zone items, agent runs/steps/activity, notification and access logs.
-- KEPT: users (app_user), job requisitions, resume sources, mock job-board roles, Resume Drop Zone settings,
--   interviewer availability windows, AI budget limits and AI usage history, and the schema history (Flyway).
--
-- Integrity checks are switched off for the clean-up so the order of the tables does not matter, then back on.
-- RESTART IDENTITY makes the next candidate, interview, etc. start again from 1.

SET REFERENTIAL_INTEGRITY FALSE;

TRUNCATE TABLE agent_step RESTART IDENTITY;
TRUNCATE TABLE agent_run RESTART IDENTITY;
TRUNCATE TABLE agent_activity_log RESTART IDENTITY;
TRUNCATE TABLE interview_debrief RESTART IDENTITY;
TRUNCATE TABLE interview_reminder RESTART IDENTITY;
TRUNCATE TABLE interview_scorecard_rating RESTART IDENTITY;
TRUNCATE TABLE interview_scorecard RESTART IDENTITY;
TRUNCATE TABLE interview_slot RESTART IDENTITY;
TRUNCATE TABLE interview_schedule RESTART IDENTITY;
TRUNCATE TABLE offer_response_token RESTART IDENTITY;
TRUNCATE TABLE offer_letter RESTART IDENTITY;
TRUNCATE TABLE offer_negotiation_history RESTART IDENTITY;
TRUNCATE TABLE offer RESTART IDENTITY;
TRUNCATE TABLE sourcing_match RESTART IDENTITY;
TRUNCATE TABLE screening_result RESTART IDENTITY;
TRUNCATE TABLE referral RESTART IDENTITY;
TRUNCATE TABLE pipeline_stage RESTART IDENTITY;
TRUNCATE TABLE candidate_status_update RESTART IDENTITY;
TRUNCATE TABLE candidate_access_log RESTART IDENTITY;
TRUNCATE TABLE notification_log RESTART IDENTITY;
TRUNCATE TABLE cv_inbox_item RESTART IDENTITY;
TRUNCATE TABLE mock_application RESTART IDENTITY;
TRUNCATE TABLE candidate RESTART IDENTITY;

SET REFERENTIAL_INTEGRITY TRUE;
