-- Per-tool and per-LLM-call tracing for agent runs.
--
-- Until now only START / APPROVAL_* / FINAL / ERROR were recorded, so a run could not show
-- which tools it actually called. These columns let a step point at the step that triggered
-- it, turning the flat list into a call chain: run -> tool call -> the LLM call that tool made.
ALTER TABLE agent_step ADD COLUMN parent_step_no INT;
ALTER TABLE agent_step ADD COLUMN depth INT DEFAULT 0 NOT NULL;
ALTER TABLE agent_step ADD COLUMN duration_ms BIGINT;

-- Short structured detail for a traced call: tool arguments, or model + tokens + cost.
ALTER TABLE agent_step ADD COLUMN detail VARCHAR(2000);

-- Set on AI_CALL steps so a run can be costed without joining ai_usage_log.
ALTER TABLE agent_step ADD COLUMN model VARCHAR(80);
ALTER TABLE agent_step ADD COLUMN input_tokens INT;
ALTER TABLE agent_step ADD COLUMN output_tokens INT;
ALTER TABLE agent_step ADD COLUMN estimated_cost DECIMAL(12, 6);

CREATE INDEX idx_agent_step_parent ON agent_step(run_id, parent_step_no);
