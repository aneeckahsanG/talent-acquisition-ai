package com.talentai.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.admin.dto.AdminDtos.ProposeInterviewRequest;
import com.talentai.admin.dto.AdminDtos.InterviewScheduleResponse;
import com.talentai.admin.dto.AdminDtos.ConfirmSlotRequest;
import com.talentai.admin.entity.AvailabilityWindow;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.dto.JobRequisitionDtos.CreateRequisitionRequest;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.RequisitionService;
import com.talentai.directory.CandidateDirectoryService;
import com.talentai.cvinbox.service.CvInboxProcessingService;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.dto.OrchestratorDtos.CreateOfferRequest;
import com.talentai.referral.dto.ReferralDtos.SubmitReferralRequest;
import com.talentai.referral.service.ReferralAgentService;
import com.talentai.screening.dto.ScreeningDtos.ReviewDecisionRequest;
import com.talentai.screening.dto.ScreeningDtos.ScreenByTextRequest;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.dto.SourcingDtos.AddTalentPoolCandidateRequest;
import com.talentai.sourcing.entity.MockJobRole;
import com.talentai.sourcing.repository.MockJobRoleRepository;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final JobRequisitionRepository jobRequisitionRepository;
    private final MockJobRoleRepository mockJobRoleRepository;
    private final CandidateDirectoryService candidateDirectoryService;
    private final RequisitionService requisitionService;
    private final ReferralAgentService referralAgentService;
    private final CvInboxProcessingService cvInboxProcessingService;
    private final AvailabilityWindowRepository availabilityWindowRepository;
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

    @Value("${server.port:8080}")
    private String serverPort;

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
        return traced("getInterviewFeedback", "requisitionId=" + requisitionId + ", candidateId=" + candidateId, () -> {
            try {
                return toJson(orchestratorService.interviewFeedback(requisitionId, candidateId));
            } catch (Exception e) {
                return error("getInterviewFeedback", e);
            }
        });
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

    @Tool(description = "List the latest 25 candidates in the talent pool, including their name, contact details, latest role, and current pipeline stage.")
    public String listCandidates() {
        try {
            return toJson(candidateDirectoryService.list(null));
        } catch (Exception e) {
            return error("listCandidates", e);
        }
    }

    @Tool(description = "List all currently open job requisitions with their exact live title, department, location, experience level, required skills, description, interview rounds, status, and ID.")
    public String listOpenRoles() {
        try {
            return toJson(requisitionService.getAll("OPEN"));
        } catch (Exception e) {
            return error("listOpenRoles", e);
        }
    }

    @Tool(description = "Search candidates by name, email, phone, skills, or headline. Use this before showing a candidate profile when the user gives a name or other text.")
    public String searchCandidates(String query) {
        try {
            return toJson(candidateDirectoryService.search(query, "ai-assistant"));
        } catch (Exception e) {
            return error("searchCandidates", e);
        }
    }

    @Tool(description = "Get the full recruiter profile for one candidate, including contact details, applications, screening results, interviews, offers, and sent emails.")
    public String getCandidateProfile(Long candidateId) {
        try {
            return toJson(candidateDirectoryService.profile(candidateId, "ai-assistant", false));
        } catch (Exception e) {
            return error("getCandidateProfile", e);
        }
    }

    @Tool(description = "Create a new open role. Required fields are title, department, location, experienceLevel "
            + "(JUNIOR, MID, SENIOR, or LEAD), requiredSkills, description, and requiredInterviewRounds. "
            + "Requires recruiter approval before creation.")
    public String createRole(String title, String department, String location, String experienceLevel,
                             String requiredSkills, String description, Integer requiredInterviewRounds,
                             ToolContext toolContext) {
        String actionKey = "createRole:" + title + ":" + department;
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Create the " + title + " role in " + department,
                    "createRole", description, runIdOf(toolContext));
            return "PENDING_APPROVAL — role creation queued for recruiter review. Approval ID: " + pendingId
                    + ". Retry this tool call once the recruiter approves.";
        }
        try {
            CreateRequisitionRequest request = new CreateRequisitionRequest();
            request.setTitle(title);
            request.setDepartment(department);
            request.setLocation(location);
            request.setExperienceLevel(experienceLevel);
            request.setRequiredSkills(requiredSkills);
            request.setDescription(description);
            request.setRequiredInterviewRounds(requiredInterviewRounds);
            String result = toJson(requisitionService.create(request, "admin"));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("createRole", e);
        }
    }

    @Tool(description = "Publish an existing open job requisition to the mock job board. Requires recruiter approval.")
    public String publishRoleToJobBoard(Long requisitionId, ToolContext toolContext) {
        String actionKey = "publishRole:" + requisitionId;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Publish requisition " + requisitionId + " to the job board",
                    "publishRoleToJobBoard", toolContext);
        }
        try {
            JobRequisition req = jobRequisitionRepository.findById(requisitionId)
                    .orElseThrow(() -> new IllegalArgumentException("Requisition not found: " + requisitionId));
            if (!"OPEN".equalsIgnoreCase(req.getStatus())) {
                throw new IllegalArgumentException("Only OPEN requisitions can be published");
            }
            MockJobRole role = mockJobRoleRepository.save(MockJobRole.builder()
                    .title(req.getTitle()).department(req.getDepartment()).location(req.getLocation())
                    .description(req.getDescription()).requirements(req.getRequiredSkills())
                    .company("TalentAI").requisitionId(req.getId())
                    .webhookUrl("http://localhost:" + serverPort + "/api/public/applications")
                    .active(true).build());
            approvalGate.consume(actionKey);
            return toJson(Map.of("status", "PUBLISHED", "requisitionId", requisitionId,
                    "jobRoleId", role.getId(), "message", "Role published to the job board."));
        } catch (Exception e) {
            return error("publishRoleToJobBoard", e);
        }
    }

    @Tool(description = "Edit an existing job requisition. Null or empty fields are left unchanged. Requires recruiter approval.")
    public String editRole(Long requisitionId, String title, String department, String location,
                           String description, String requiredSkills, String experienceLevel,
                           Integer requiredInterviewRounds, ToolContext toolContext) {
        String actionKey = "editRole:" + requisitionId;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Edit requisition " + requisitionId, "editRole", toolContext);
        }
        try {
            CreateRequisitionRequest request = new CreateRequisitionRequest();
            request.setTitle(title); request.setDepartment(department); request.setLocation(location);
            request.setDescription(description); request.setRequiredSkills(requiredSkills);
            request.setExperienceLevel(experienceLevel); request.setRequiredInterviewRounds(requiredInterviewRounds);
            String result = toJson(requisitionService.update(requisitionId, request));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("editRole", e);
        }
    }

    @Tool(description = "Archive a job requisition by closing it. This is reversible and requires recruiter approval.")
    public String archiveRole(Long requisitionId, ToolContext toolContext) {
        String actionKey = "archiveRole:" + requisitionId;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Archive requisition " + requisitionId, "archiveRole", toolContext);
        }
        try {
            String result = toJson(requisitionService.updateStatus(requisitionId, "CLOSED"));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("archiveRole", e);
        }
    }

    @Tool(description = "Create a candidate in the talent pool and match them to open roles. Requires recruiter approval.")
    public String createCandidate(String fullName, String email, String headline, String resumeText,
                                  String skills, String yearsExperience, String profileUrl,
                                  String sourceChannel, Boolean hasResume, Long targetRequisitionId,
                                  ToolContext toolContext) {
        String actionKey = "createCandidate:" + email;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Create candidate " + fullName, "createCandidate", toolContext);
        }
        try {
            AddTalentPoolCandidateRequest request = new AddTalentPoolCandidateRequest(fullName, email, headline,
                    resumeText, skills, yearsExperience == null ? null : new java.math.BigDecimal(yearsExperience),
                    profileUrl, sourceChannel, hasResume, targetRequisitionId);
            String result = toJson(sourcingAgentService.addCandidateAndMatch(request));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("createCandidate", e);
        }
    }

    @Tool(description = "Submit a candidate referral. Requires recruiter approval.")
    public String createReferral(String candidateName, String candidateEmail, String resumeText,
                                 Long requisitionId, String referrerName, String referrerEmail,
                                 String referrerPosition, String relationshipNotes, ToolContext toolContext) {
        String actionKey = "createReferral:" + candidateEmail + ":" + requisitionId;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Submit a referral for " + candidateName, "createReferral", toolContext);
        }
        try {
            String result = toJson(referralAgentService.submitPublicReferral(new SubmitReferralRequest(
                    candidateName, candidateEmail, resumeText, requisitionId, referrerName, referrerEmail,
                    referrerPosition, relationshipNotes)));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("createReferral", e);
        }
    }

    @Tool(description = "Create an employment offer for a candidate. Requires recruiter approval.")
    public String createOffer(Long requisitionId, Long candidateId, String salaryAmount,
                              String currency, String startDate, String expiryDate, String notes,
                              ToolContext toolContext) {
        String actionKey = "createOffer:" + requisitionId + ":" + candidateId;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Create an employment offer for candidate " + candidateId,
                    "createOffer", toolContext);
        }
        try {
            CreateOfferRequest request = new CreateOfferRequest(new java.math.BigDecimal(salaryAmount),
                    currency, startDate, expiryDate, notes);
            String result = toJson(orchestratorService.createOffer(requisitionId, candidateId, request));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("createOffer", e);
        }
    }

    @Tool(description = "Generate interview availability windows for weekdays between two dates. Requires recruiter approval.")
    public String manageInterviewAvailability(String fromDate, String toDate, String startTime, String endTime,
                                              ToolContext toolContext) {
        String actionKey = "availability:" + fromDate + ":" + toDate + ":" + startTime + ":" + endTime;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Generate interview availability from " + fromDate + " to " + toDate,
                    "manageInterviewAvailability", toolContext);
        }
        try {
            LocalDate from = LocalDate.parse(fromDate), to = LocalDate.parse(toDate);
            LocalTime start = LocalTime.parse(startTime), end = LocalTime.parse(endTime);
            List<AvailabilityWindow> windows = new ArrayList<>();
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    windows.add(AvailabilityWindow.builder().startTime(LocalDateTime.of(day, start))
                            .endTime(LocalDateTime.of(day, end)).build());
                }
            }
            approvalGate.consume(actionKey);
            return toJson(availabilityWindowRepository.saveAll(windows));
        } catch (Exception e) {
            return error("manageInterviewAvailability", e);
        }
    }

    @Tool(description = "Process all pending Resume Inbox files through the existing parser and matching workflow. Requires recruiter approval.")
    public String processResumeInbox(ToolContext toolContext) {
        String actionKey = "processResumeInbox";
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Process pending Resume Inbox files", "processResumeInbox", toolContext);
        }
        try {
            String result = toJson(cvInboxProcessingService.processNow());
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("processResumeInbox", e);
        }
    }

    @Tool(description = "Review and approve or reject a screening decision. decision must be ADVANCE or REJECT. Requires recruiter approval.")
    public String reviewScreening(Long screeningResultId, String decision, String notes, ToolContext toolContext) {
        String actionKey = "reviewScreening:" + screeningResultId + ":" + decision;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Record screening decision " + decision + " for result " + screeningResultId,
                    "reviewScreening", toolContext);
        }
        try {
            String result = toJson(screeningAgentService.recordReviewerDecision(screeningResultId,
                    new ReviewDecisionRequest(decision, notes), null));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("reviewScreening", e);
        }
    }

    @Tool(description = "Confirm an existing proposed interview slot. Requires recruiter approval.")
    public String confirmInterview(Long scheduleId, String confirmedSlot, ToolContext toolContext) {
        String actionKey = "confirmInterview:" + scheduleId + ":" + confirmedSlot;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Confirm interview " + scheduleId + " at " + confirmedSlot,
                    "confirmInterview", toolContext);
        }
        try {
            String result = toJson(adminAgentService.confirmSlot(scheduleId,
                    new ConfirmSlotRequest(LocalDateTime.parse(confirmedSlot))));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("confirmInterview", e);
        }
    }

    @Tool(description = "Reschedule an existing interview to a new ISO local date-time. Requires recruiter approval.")
    public String rescheduleInterview(Long interviewId, String newSlot, ToolContext toolContext) {
        String actionKey = "rescheduleInterview:" + interviewId + ":" + newSlot;
        if (!approvalGate.isApproved(actionKey)) {
            return pending(actionKey, "Reschedule interview " + interviewId + " to " + newSlot,
                    "rescheduleInterview", toolContext);
        }
        try {
            String result = toJson(orchestratorService.rescheduleInterview(interviewId, newSlot));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("rescheduleInterview", e);
        }
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

    @Tool(description = "Schedule or reschedule a candidate interview at an exact future local date/time. "
            + "confirmedSlot must be ISO local date-time, for example 2026-11-05T17:00:00. "
            + "Requires recruiter approval before booking.")
    public String scheduleInterviewAt(Long candidateId, Long requisitionId, String interviewType,
                                      String confirmedSlot, ToolContext toolContext) {
        String actionKey = "scheduleInterview:" + candidateId + ":" + requisitionId + ":" + interviewType + ":" + confirmedSlot;
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Schedule a " + interviewType + " interview for " + candidateLabel(candidateId)
                            + " on " + confirmedSlot,
                    "scheduleInterviewAt", confirmedSlot, runIdOf(toolContext));
            return "PENDING_APPROVAL — interview schedule queued for recruiter review. Approval ID: " + pendingId
                    + ". Retry this tool call once the recruiter approves.";
        }
        try {
            InterviewScheduleResponse response = adminAgentService.scheduleInterviewAt(
                    candidateId, requisitionId, interviewType, LocalDateTime.parse(confirmedSlot));
            approvalGate.consume(actionKey);
            return toJson(response);
        } catch (DateTimeParseException e) {
            return error("scheduleInterviewAt", new IllegalArgumentException(
                    "confirmedSlot must be ISO local date-time, for example 2026-11-05T17:00:00", e));
        } catch (Exception e) {
            return error("scheduleInterviewAt", e);
        }
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

    @Tool(description = "Override an AI REJECT screening decision and shortlist the candidate. Use only when the recruiter explicitly asks to override the rejection. Requires recruiter approval.")
    public String overrideScreeningDecision(Long candidateId, Long requisitionId, ToolContext toolContext) {
        String actionKey = "overrideScreening:" + requisitionId + ":" + candidateId + ":SHORTLISTED";
        if (!approvalGate.isApproved(actionKey)) {
            String pendingId = approvalGate.requestApproval(actionKey,
                    "Override the AI Reject and shortlist " + candidateLabel(candidateId)
                            + " on requisition " + requisitionId,
                    "overrideScreeningDecision", null, runIdOf(toolContext));
            return "PENDING_APPROVAL — action queued for recruiter review. Approval ID: " + pendingId
                    + ". Retry this tool call once the recruiter approves.";
        }
        try {
            String result = toJson(orchestratorService.moveStage(
                    requisitionId, candidateId, "SHORTLISTED", AGENT_ACTOR));
            approvalGate.consume(actionKey);
            return result;
        } catch (Exception e) {
            return error("overrideScreeningDecision", e);
        }
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
        return traced("sendBookingLink",
                "requisitionId=" + requisitionId + ", candidateId=" + candidateId + ", interviewType=" + type, () -> {
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
        });
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

    private String pending(String actionKey, String description, String toolName, ToolContext toolContext) {
        String pendingId = approvalGate.requestApproval(actionKey, description, toolName, null, runIdOf(toolContext));
        return "PENDING_APPROVAL — " + description + ". Approval ID: " + pendingId
                + ". Retry this tool call after the recruiter approves it.";
    }

    private String truncate(String s, int max) {
        return (s == null) ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
