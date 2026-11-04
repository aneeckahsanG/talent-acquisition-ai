package com.talentai.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.admin.dto.AdminDtos.ProposeInterviewRequest;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI tool bean — all @Tool methods are exposed to the LLM in the agentic loop.
 *
 * Risk tiers:
 *   LOW  — read-only queries, always execute immediately.
 *   MED  — write ops that are reversible (stage moves, draft generation).
 *   HIGH — candidate-facing or irreversible actions; gated by HumanApprovalGate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecruitingTools {

    /** Recorded as the actor on stage moves the agent makes, so the board does not credit them to a recruiter. */
    private static final String AGENT_ACTOR = "ORCHESTRATOR";

    /** Tool-context key the orchestrator uses to tell gated tools which run is calling them. */
    public static final String RUN_ID_KEY = "runId";

    private final ScreeningAgentService screeningAgentService;
    private final SourcingAgentService sourcingAgentService;
    private final AdminAgentService adminAgentService;
    private final OrchestratorService orchestratorService;
    private final HumanApprovalGate approvalGate;
    private final CandidateRepository candidateRepository;
    private final ObjectMapper objectMapper;
    private final com.talentai.orchestrator.agent.AgentTrace agentTrace;

    /**
     * Runs a tool body inside a trace span, so the run records which tool was called, with what
     * arguments, how long it took and what it returned — and any LLM call the tool makes is nested
     * underneath it. Outside an agent run the span is a no-op.
     */
    private String traced(String tool, String arguments, java.util.function.Supplier<String> body) {
        try (com.talentai.orchestrator.agent.AgentTrace.Span span = agentTrace.tool(tool, arguments)) {
            String result = body.get();
            span.result(result);
            return result;
        }
    }

    // ── LOW RISK — read ───────────────────────────────────────────────────────

    @Tool(description = "Get the pipeline board for a job requisition, showing every candidate, their current stage, screening score, and interview rounds completed.")
    public String getPipelineBoard(Long requisitionId) {
        return traced("getPipelineBoard", "requisitionId=" + requisitionId, () -> {
            try {
                return toJson(orchestratorService.getPipelineBoard(requisitionId));
            } catch (Exception e) {
                return error("getPipelineBoard", e);
            }
        });
    }

    @Tool(description = "Get how a candidate's interviews went for a job requisition: each round's type, status and, once the "
            + "interviewer has submitted it, the verdict (STRONG_YES, YES, NO or STRONG_NO), average rating (1-5), ratings and "
            + "notes. nextRound is the round that follows the latest completed one, or null if there is none.")
    public String getInterviewFeedback(Long requisitionId, Long candidateId) {
        try {
            return toJson(orchestratorService.interviewFeedback(requisitionId, candidateId));
        } catch (Exception e) {
            return error("getInterviewFeedback", e);
        }
    }

    @Tool(description = "Get a high-level dashboard summary: open requisitions, total screenings, edge cases awaiting review, referrals, and scheduled interviews.")
    public String getDashboardSummary() {
        return traced("getDashboardSummary", null, () -> {
            try {
                return toJson(orchestratorService.getDashboardSummary());
            } catch (Exception e) {
                return error("getDashboardSummary", e);
            }
        });
    }

    @Tool(description = "List all sourcing matches for a job requisition, including match scores and current status.")
    public String getMatchesForRequisition(Long requisitionId) {
        return traced("getMatchesForRequisition", "requisitionId=" + requisitionId, () -> {
            try {
                return toJson(sourcingAgentService.getMatchesForRequisition(requisitionId));
            } catch (Exception e) {
                return error("getMatchesForRequisition", e);
            }
        });
    }

    @Tool(description = "List screening edge cases (HOLD results) that are awaiting human review for a given requisition.")
    public String getEdgeCases(Long requisitionId) {
        return traced("getEdgeCases", "requisitionId=" + requisitionId, () -> {
            try {
                return toJson(screeningAgentService.getEdgeCases(requisitionId));
            } catch (Exception e) {
                return error("getEdgeCases", e);
            }
        });
    }

    // ── MEDIUM RISK — reversible writes ──────────────────────────────────────

    @Tool(description = "Get a candidate's screening result for a job requisition: a score (0-100) and an ADVANCE / REVIEW / REJECT recommendation. "
            + "A new application has normally been screened already, so this returns the saved result at no cost; it only screens if there is no result yet.")
    public String screenCandidate(Long candidateId, Long requisitionId) {
        return traced("screenCandidate", "candidateId=" + candidateId + ", requisitionId=" + requisitionId, () -> {
            try {
                return toJson(screeningAgentService.screenExistingCandidate(candidateId, requisitionId));
            } catch (Exception e) {
                return error("screenCandidate", e);
            }
        });
    }

    @Tool(description = "Match an existing candidate in the talent pool against all currently open job requisitions and store the best matches.")
    public String matchCandidateToOpenRoles(Long candidateId) {
        return traced("matchCandidateToOpenRoles", "candidateId=" + candidateId, () -> {
            try {
                return toJson(sourcingAgentService.matchExistingCandidateToOpenRoles(candidateId));
            } catch (Exception e) {
                return error("matchCandidateToOpenRoles", e);
            }
        });
    }

    @Tool(description = "Draft an outreach email for a sourcing match. Returns the draft text for recruiter review; does NOT send it.")
    public String draftOutreach(Long matchId) {
        return traced("draftOutreach", "matchId=" + matchId, () -> {
            try {
                return toJson(sourcingAgentService.draftOutreach(matchId));
            } catch (Exception e) {
                return error("draftOutreach", e);
            }
        });
    }

    @Tool(description = "Propose up to 3 interview time slots for a candidate and generate a draft invitation message. interviewType must be one of: SCREENING, TECHNICAL, CULTURAL, FINAL.")
    public String proposeInterview(Long candidateId, Long requisitionId, String interviewType) {
        return traced("proposeInterview",
                "candidateId=" + candidateId + ", requisitionId=" + requisitionId + ", interviewType=" + interviewType, () -> {
            try {
                ProposeInterviewRequest req = new ProposeInterviewRequest();
                req.setCandidateId(candidateId);
                req.setRequisitionId(requisitionId);
                req.setInterviewType(interviewType);
                return toJson(adminAgentService.proposeInterview(req));
            } catch (Exception e) {
                return error("proposeInterview", e);
            }
        });
    }

    @Tool(description = "Move a candidate to a new pipeline stage. Valid stages: SCREENED, SHORTLISTED, INTERVIEW_SCHEDULED, OFFER, HIRED, REJECTED. "
            + "Stages OFFER, HIRED and REJECTED require recruiter approval (moving to REJECTED also emails the candidate): "
            + "the tool queues the move and returns PENDING_APPROVAL, so stop and report that it is waiting.")
    public String moveCandidateStage(Long requisitionId, Long candidateId, String newStage, ToolContext toolContext) {
        return traced("moveCandidateStage",
                "requisitionId=" + requisitionId + ", candidateId=" + candidateId + ", newStage=" + newStage, () -> {
            String actionKey = "moveStage:" + requisitionId + ":" + candidateId + ":" + newStage;
            boolean gated = isHighRiskStage(newStage);
            if (gated && !approvalGate.isApproved(actionKey)) {
                String pendingId = approvalGate.requestApproval(actionKey,
                        "Move " + candidateLabel(candidateId) + " to " + newStage.toUpperCase()
                                + " on requisition " + requisitionId
                                + (newStage.equalsIgnoreCase("REJECTED") ? " (the candidate is emailed)" : ""),
                        "moveCandidateStage", null, runIdOf(toolContext));
                return "PENDING_APPROVAL — action queued for recruiter review. Approval ID: " + pendingId
                        + ". Retry this tool call once the recruiter approves.";
            }
            try {
                String result = toJson(orchestratorService.moveStage(requisitionId, candidateId, newStage, AGENT_ACTOR));
                if (gated) approvalGate.consume(actionKey);
                return result;
            } catch (Exception e) {
                return error("moveCandidateStage", e);
            }
        });
    }

    // ── HIGH RISK — candidate-facing / irreversible ───────────────────────────

    @Tool(description = "Send an outreach email to a sourced candidate. Always requires human approval. "
            + "Pass the matchId and the email body to send. Call requestApproval first if not yet approved.")
    public String sendOutreach(Long matchId, String emailBody, ToolContext toolContext) {
        return traced("sendOutreach", "matchId=" + matchId, () -> {
            String actionKey = "sendOutreach:" + matchId;
            if (!approvalGate.isApproved(actionKey)) {
                String pendingId = approvalGate.requestApproval(actionKey,
                        "Send an outreach email for match " + matchId,
                        "sendOutreach", emailBody, runIdOf(toolContext));
                return "PENDING_APPROVAL — outreach queued for recruiter sign-off. Approval ID: " + pendingId
                        + ". Retry once approved.";
            }
            try {
                // Send the text the recruiter read and approved, not whatever the agent wrote this time round.
                String approvedBody = approvalGate.approvedPayload(actionKey).orElse(emailBody);
                sourcingAgentService.sendOutreach(matchId, approvedBody);
                approvalGate.consume(actionKey);
                return "Outreach sent successfully for match " + matchId + ".";
            } catch (Exception e) {
                return error("sendOutreach", e);
            }
        });
    }

    @Tool(description = "Email a candidate a link to book an interview time from the published calendar: a shortlisted "
            + "candidate's first interview, or the next round for a candidate whose previous round went well. "
            + "interviewType is PHONE_SCREEN, TECHNICAL or FINAL, in that order. Always requires recruiter approval: the tool queues it and "
            + "returns PENDING_APPROVAL, so stop and report that it is waiting. If it returns an ERROR (no times published, "
            + "not shortlisted, already invited) do not retry; explain it in your final answer.")
    public String sendBookingLink(Long requisitionId, Long candidateId, String interviewType, ToolContext toolContext) {
        String type = interviewType == null || interviewType.isBlank() ? "PHONE_SCREEN" : interviewType.trim().toUpperCase();
        var check = orchestratorService.checkBookingLink(requisitionId, candidateId, type);
        if (check.problem() != null) {
            return "ERROR in sendBookingLink: " + check.problem();
        }
        String actionKey = "bookingLink:" + requisitionId + ":" + candidateId + ":" + type;
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Send " + candidateLabel(candidateId) + " a link to book a " + type.replace("_", " ").toLowerCase()
                            + " interview (requisition " + requisitionId + ")",
                    "sendBookingLink", check.emailText() + "\n\n[A personal link to the available calendar follows]",
                    runIdOf(toolContext));
            return "PENDING_APPROVAL — booking link queued for recruiter review. Approval ID: " + pendingId
                    + ". Retry this tool call once the recruiter approves.";
        }
        try {
            var request = new com.talentai.orchestrator.dto.OrchestratorDtos.ScheduleInterviewRequest();
            request.setInterviewType(type);
            String result = toJson(orchestratorService.scheduleInterview(requisitionId, candidateId, request, AGENT_ACTOR));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("sendBookingLink", e);
        }
    }

    @Tool(description = "Send a status notification to a candidate via EMAIL or SMS. Always requires human approval. "
            + "Call requestApproval first if not yet approved.")
    public String sendCandidateStatusUpdate(Long candidateId, String message, String channel, ToolContext toolContext) {
        return traced("sendCandidateStatusUpdate", "candidateId=" + candidateId + ", channel=" + channel, () -> {
            String actionKey = "statusUpdate:" + candidateId + ":" + channel;
            if (!approvalGate.isApproved(actionKey)) {
                String pendingId = approvalGate.requestApproval(actionKey,
                        "Send a " + channel + " status update to " + candidateLabel(candidateId),
                        "sendCandidateStatusUpdate", message, runIdOf(toolContext));
                return "PENDING_APPROVAL — status update queued for recruiter sign-off. Approval ID: " + pendingId
                        + ". Retry once approved.";
            }
            try {
                String approvedMessage = approvalGate.approvedPayload(actionKey).orElse(message);
                String result = toJson(adminAgentService.sendStatusUpdate(candidateId, approvedMessage, channel));
                approvalGate.consume(actionKey);
                return result;
            } catch (Exception e) {
                return error("sendCandidateStatusUpdate", e);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The orchestrator run this tool call belongs to, or null when called outside one (the free-form agent). */
    private Long runIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        return toolContext.getContext().get(RUN_ID_KEY) instanceof Number n ? n.longValue() : null;
    }

    private String candidateLabel(Long candidateId) {
        return candidateRepository.findById(candidateId)
                .map(c -> c.getFullName() + " (candidate " + candidateId + ")")
                .orElse("candidate " + candidateId);
    }

    private boolean isHighRiskStage(String stage) {
        return stage != null && (stage.equalsIgnoreCase("OFFER") || stage.equalsIgnoreCase("HIRED")
                || stage.equalsIgnoreCase("REJECTED"));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    private String error(String tool, Exception e) {
        log.error("Tool '{}' failed: {}", tool, e.getMessage());
        return "ERROR in " + tool + ": " + e.getMessage();
    }

    private String truncate(String s, int max) {
        return (s == null) ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
