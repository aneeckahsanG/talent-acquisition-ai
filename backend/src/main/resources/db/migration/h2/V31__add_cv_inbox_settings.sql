-- Makes the CV Inbox SFTP configuration (embedded server port/credentials, or an
-- external SFTP server + optional HTTP proxy) editable from the UI instead of fixed
-- in application.yml. Singleton row (id=1); seeded here from today's application.yml
-- defaults so behavior is unchanged until HR explicitly edits and saves new settings.
CREATE TABLE IF NOT EXISTS cv_inbox_settings (
    id                   BIGINT PRIMARY KEY,
    mode                 VARCHAR(20) NOT NULL,
    embedded_port        INT,
    embedded_username    VARCHAR(255),
    embedded_password    VARCHAR(255),
    external_host        VARCHAR(255),
    external_port        INT,
    external_username    VARCHAR(255),
    external_password    VARCHAR(255),
    external_root_path   VARCHAR(500),
    proxy_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    proxy_host           VARCHAR(255),
    proxy_port           INT,
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO cv_inbox_settings (id, mode, embedded_port, embedded_username, embedded_password, proxy_enabled)
VALUES (1, 'EMBEDDED', 2222, 'hr', 'change-me', FALSE);
