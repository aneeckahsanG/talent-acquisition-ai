-- Lets the CV Inbox read CVs from a Google Drive folder (mode GOOGLE_DRIVE) using a service
-- account: the folder is shared with the account's email, and its JSON key is stored here.
ALTER TABLE cv_inbox_settings ADD COLUMN google_folder_id VARCHAR(255);
ALTER TABLE cv_inbox_settings ADD COLUMN google_service_account_json VARCHAR(10000);
