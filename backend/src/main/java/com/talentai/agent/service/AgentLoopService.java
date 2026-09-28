package com.talentai.agent.service;

import com.talentai.agent.tool.RecruitingTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Runs the multi-turn agentic loop.
 *
 * Spring AI handles the tool-call / tool-result cycle automatically:
 * when the LLM returns a tool_use block, the framework invokes the
 * matching @Tool method, appends the result, and calls the model again
 * until it produces a plain text response or hits maxIterations.
 *
 * HITL gate: high-risk @Tool methods inside RecruitingTools call
 * HumanApprovalGate. When approval is missing the tool returns a
 * PENDING_APPROVAL message; the LLM surfaces it in its final reply.
 * The recruiter approves via /api/agent/approvals/{id}/approve and
 * re-submits the loop — the gate is now open and the tool executes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentLoopService {

    private static final String SYSTEM_PROMPT = """
            You are the Recruiting Orchestrator Agent for an AI-powered talent acquisition platform.

            You have access to a set of tools that let you query the pipeline, screen candidates,
            match talent to roles, propose interviews, and move candidates through stages.

            Guidelines:
            - Always read the current pipeline state before proposing actions.
            - Never skip the screening step for a new candidate.
            - For OFFER and HIRED stage moves, and for any candidate-facing communication,
              you must request human approval. If a tool returns PENDING_APPROVAL, surface
              the Approval ID to the recruiter and stop — do not retry until re-invoked.
            - Be concise: summarise what you did, what is pending, and what the recruiter
              should do next.
            - If a tool returns an ERROR, explain what failed and suggest a manual workaround.
            """;

    private final ChatClient chatClient;
    private final RecruitingTools recruitingTools;

    /**
     * Runs the agent loop for a given recruiter goal.
     *
     * @param goal   Natural-language instruction from the recruiter.
     * @param context Optional extra context (e.g. "Requisition ID is 5").
     * @return The agent's final response after all tool calls are resolved.
     */
    public String run(String goal, String context) {
        String userMessage = (context != null && !context.isBlank())
                ? goal + "\n\nContext:\n" + context
                : goal;

        log.info("Agent loop started — goal: {}", goal);

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .tools(recruitingTools)
                .call()
                .content();

        log.info("Agent loop completed");
        return response;
    }
}
