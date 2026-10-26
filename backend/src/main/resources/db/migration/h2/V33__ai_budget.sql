-- Spending limits for AI calls. A single row (id = 1); NULL limit means no limit.
CREATE TABLE ai_budget (
    id                BIGINT PRIMARY KEY,
    daily_limit_usd   DECIMAL(12,4),
    monthly_limit_usd DECIMAL(12,4),
    warn_percent      INT     NOT NULL DEFAULT 80,
    hard_stop         BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_budget (id, daily_limit_usd, monthly_limit_usd, warn_percent, hard_stop)
VALUES (1, NULL, NULL, 80, TRUE);
