-- Holds tool_result blocks already computed earlier in the SAME turn a
-- gated tool call was found in, so they aren't lost when the run pauses
-- for human approval. Claude's Messages API requires every tool_use id
-- from an assistant turn to be resolved together in one subsequent user
-- message — so results collected before the gate can't be sent back
-- immediately; they're held here and merged with the approved/declined
-- tool's result once the run resumes.
ALTER TABLE agent_run ADD COLUMN pending_prior_results VARCHAR(65535);
