package com.talentai.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.admin.dto.AdminDtos.ProposeInterviewRequest;
import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.screening.dto.ScreeningDtos.ScreenByTextRequest;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final ScreeningAgentService screeningAgentService;
    private final SourcingAgentService sourcingAgentService;
    private final AdminAgentService adminAgentService;
    private final OrchestratorService orchestratorService;
    private final HumanApprovalGate approvalGate;
    private final CandidateRepository candidateRepository;
    private final ObjectMapper objectMapper;

    // ── LOW RISK — read ───────────────────────────────────────────────────────

    @Tool(description = "Get the pipeline board for a job requisition, showing every candidate, their current stage, screening score, and interview rounds completed.")
    public String getPipelineBoard(Long requisitionId) {
        try {
            return toJson(orchestratorService.getPipelineBoard(requisitionId));
        } catch (Exception e) {
            return error("getPipelineBoard", e);
        }
    }

    @Tool(description = "Get a high-level dashboard summary: open requisitions, total screenings, edge cases awaiting review, referrals, and scheduled interviews.")
    public String getDashboardSummary() {
        try {
            return toJson(orchestratorService.getDashboardSummary());
        } catch (Exception e) {
            return error("getDashboardSummary", e);
        }
    }

    @Tool(description = "List all sourcing matches for a job requisition, including match scores and current status.")
    public String getMatchesForRequisition(Long requisitionId) {
        try {
            return toJson(sourcingAgentService.getMatchesForRequisition(requisitionId));
        } catch (Exception e) {
            return error("getMatchesForRequisition", e);
        }
    }

    @Tool(description = "List screening edge cases (HOLD results) that are awaiting human review for a given requisition.")
    public String getEdgeCases(Long requisitionId) {
        try {
            return toJson(screeningAgentService.getEdgeCases(requisitionId));
        } catch (Exception e) {
            return error("getEdgeCases", e);
        }
    }

    // ── MEDIUM RISK — reversible writes ──────────────────────────────────────

    @Tool(description = "Screen a candidate against a job requisition. Returns a score (0-100) and ADVANCE / HOLD / REJECT recommendation.")
    public String screenCandidate(Long candidateId, Long requisitionId) {
        try {
            Candidate c = candidateRepository.findById(candidateId)
                    .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
            ScreenByTextRequest req = new ScreenByTextRequest(
                    c.getFullName(), c.getEmail(), c.getResumeText(), requisitionId);
            return toJson(screeningAgentService.screenCandidate(req));
        } catch (Exception e) {
            return error("screenCandidate", e);
        }
    }

    @Tool(description = "Match an existing candidate in the talent pool against all currently open job requisitions and store the best matches.")
    public String matchCandidateToOpenRoles(Long candidateId) {
        try {
            return toJson(sourcingAgentService.matchExistingCandidateToOpenRoles(candidateId));
        } catch (Exception e) {
            return error("matchCandidateToOpenRoles", e);
        }
    }

    @Tool(description = "Draft an outreach email for a sourcing match. Returns the draft text for recruiter review; does NOT send it.")
    public String draftOutreach(Long matchId) {
        try {
            return toJson(sourcingAgentService.draftOutreach(matchId));
        } catch (Exception e) {
            return error("draftOutreach", e);
        }
    }

    @Tool(description = "Propose up to 3 interview time slots for a candidate and generate a draft invitation message. interviewType must be one of: SCREENING, TECHNICAL, CULTURAL, FINAL.")
    public String proposeInterview(Long candidateId, Long requisitionId, String interviewType) {
        try {
            ProposeInterviewRequest req = new ProposeInterviewRequest();
            req.setCandidateId(candidateId);
            req.setRequisitionId(requisitionId);
            req.setInterviewType(interviewType);
            return toJson(adminAgentService.proposeInterview(req));
        } catch (Exception e) {
            return error("proposeInterview", e);
        }
    }

    @Tool(description = "Move a candidate to a new pipeline stage. Valid stages: SCREENED, SHORTLISTED, INTERVIEW_SCHEDULED, OFFER, HIRED, REJECTED. "
            + "Stages OFFER, HIRED and REJECTED require recruiter approval (moving to REJECTED also emails the candidate): "
            + "the tool queues the move and returns PENDING_APPROVAL, so stop and report that it is waiting.")
    public String moveCandidateStage(Long requisitionId, Long candidateId, String newStage) {
        String actionKey = "moveStage:" + requisitionId + ":" + candidateId + ":" + newStage;
        if (isHighRiskStage(newStage) && !approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Move candidate " + candidateId + " to stage " + newStage
                            + " on requisition " + requisitionId);
            return "PENDING_APPROVAL — action queued for recruiter review. Approval ID: " + pendingId
                    + ". Retry this tool call once the recruiter approves.";
        }
        try {
            return toJson(orchestratorService.moveStage(requisitionId, candidateId, newStage, AGENT_ACTOR));
        } catch (Exception e) {
            return error("moveCandidateStage", e);
        }
    }

    // ── HIGH RISK — candidate-facing / irreversible ───────────────────────────

    @Tool(description = "Send an outreach email to a sourced candidate. Always requires human approval. "
            + "Pass the matchId and the email body to send. Call requestApproval first if not yet approved.")
    public String sendOutreach(Long matchId, String emailBody) {
        String actionKey = "sendOutreach:" + matchId;
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Send outreach email for match " + matchId + ": " + truncate(emailBody, 120));
            return "PENDING_APPROVAL — outreach queued for recruiter sign-off. Approval ID: " + pendingId
                    + ". Retry once approved.";
        }
        try {
            sourcingAgentService.sendOutreach(matchId, emailBody);
            return "Outreach sent successfully for match " + matchId + ".";
        } catch (Exception e) {
            return error("sendOutreach", e);
        }
    }

    @Tool(description = "Send a status notification to a candidate via EMAIL or SMS. Always requires human approval. "
            + "Call requestApproval first if not yet approved.")
    public String sendCandidateStatusUpdate(Long candidateId, String message, String channel) {
        String actionKey = "statusUpdate:" + candidateId + ":" + channel;
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Send " + channel + " status update to candidate " + candidateId + ": " + truncate(message, 120));
            return "PENDING_APPROVAL — status update queued for recruiter sign-off. Approval ID: " + pendingId
                    + ". Retry once approved.";
        }
        try {
            return toJson(adminAgentService.sendStatusUpdate(candidateId, message, channel));
        } catch (Exception e) {
            return error("sendCandidateStatusUpdate", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
