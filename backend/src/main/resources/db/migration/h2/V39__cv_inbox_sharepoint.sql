-- Lets the Resume Drop Zone read CVs from a SharePoint folder (mode SHAREPOINT) through Microsoft Graph,
-- using an Entra ID app registration. Additive: existing rows and the other modes are untouched.
ALTER TABLE cv_inbox_settings ADD COLUMN sharepoint_tenant_id VARCHAR(255);
ALTER TABLE cv_inbox_settings ADD COLUMN sharepoint_client_id VARCHAR(255);
ALTER TABLE cv_inbox_settings ADD COLUMN sharepoint_client_secret VARCHAR(1000);
ALTER TABLE cv_inbox_settings ADD COLUMN sharepoint_folder_link VARCHAR(2000);
