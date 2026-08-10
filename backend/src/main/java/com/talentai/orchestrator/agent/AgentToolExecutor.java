package com.talentai.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Executes the tools the orchestrator agent can call, and declares their
 * JSON Schema definitions for the Claude Messages API `tools` parameter.
 *
 * Tools marked as requiring approval are NOT executed until a human recruiter
 * approves them — the orchestrator pauses the run instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentToolExecutor {

    private final ScreeningAgentService screeningAgentService;
    private final SourcingAgentService sourcingAgentService;
    private final ScreeningResultRepository screeningResultRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Tools whose execution has real-world side effects and require human approval first. */
    public boolean requiresApproval(String toolName) {
        return "send_outreach_email".equals(toolName) || "reject_candidate".equals(toolName);
    }

    /** Tool definitions in Claude Messages API format. */
    public ArrayNode toolDefinitions() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("get_job_description",
                "Get the full job requisition details: title, description, required skills, experience level.",
                props(p -> p.putObject("requisitionId").put("type", "integer")), "requisitionId"));
        tools.add(tool("get_candidate_profile",
                "Get the candidate's profile: name, headline, skills, years of experience, and resume text.",
                props(p -> p.putObject("candidateId").put("type", "integer")), "candidateId"));
        tools.add(tool("screen_candidate",
                "Run AI screening of the candidate's resume against the job requisition. Returns overall score (0-100), skills/experience/culture-fit scores, strengths, gaps, and a recommendation (ADVANCE, REVIEW, REJECT). If the candidate was already screened, returns the existing result.",
                props(p -> {
                    p.putObject("candidateId").put("type", "integer");
                    p.putObject("requisitionId").put("type", "integer");
                }), "candidateId", "requisitionId"));
        tools.add(tool("check_pipeline",
                "Check how many candidates are already in the interview pipeline for this requisition, grouped by stage.",
                props(p -> p.putObject("requisitionId").put("type", "integer")), "requisitionId"));
        tools.add(tool("update_pipeline_stage",
                "Move the candidate to a pipeline stage. Valid stages: SOURCED, SCREENED, SHORTLISTED, INTERVIEW, OFFER, REJECTED.",
                props(p -> {
                    p.putObject("candidateId").put("type", "integer");
                    p.putObject("requisitionId").put("type", "integer");
                    p.putObject("stage").put("type", "string");
                }), "candidateId", "requisitionId", "stage"));
        tools.add(tool("draft_outreach_email",
                "Draft a personalized outreach/next-steps email for the candidate. Returns the draft text; it is NOT sent.",
                props(p -> p.putObject("matchId").put("type", "integer")), "matchId"));
        tools.add(tool("send_outreach_email",
                "Send the outreach email to the candidate. REQUIRES HUMAN APPROVAL — calling this pauses the run until a recruiter approves.",
                props(p -> {
                    p.putObject("matchId").put("type", "integer");
                    p.putObject("emailBody").put("type", "string");
                }), "matchId", "emailBody"));
        tools.add(tool("reject_candidate",
                "Mark the candidate as rejected for this requisition. REQUIRES HUMAN APPROVAL — calling this pauses the run until a recruiter approves.",
                props(p -> {
                    p.putObject("matchId").put("type", "integer");
                    p.putObject("reason").put("type", "string");
                }), "matchId", "reason"));
        return tools;
    }

    /** Execute a tool and return its result as a string for the tool_result block. */
    public String execute(String toolName, JsonNode input) {
        try {
            return switch (toolName) {
                case "get_job_description" -> getJobDescription(input.path("requisitionId").asLong());
                case "get_candidate_profile" -> getCandidateProfile(input.path("candidateId").asLong());
                case "screen_candidate" -> screenCandidate(input.path("candidateId").asLong(), input.path("requisitionId").asLong());
                case "check_pipeline" -> checkPipeline(input.path("requisitionId").asLong());
                case "update_pipeline_stage" -> updatePipelineStage(
                        input.path("candidateId").asLong(), input.path("requisitionId").asLong(), input.path("stage").asText());
                case "draft_outreach_email" -> draftOutreach(input.path("matchId").asLong());
                case "send_outreach_email" -> sendOutreach(input.path("matchId").asLong(), input.path("emailBody").asText());
                case "reject_candidate" -> rejectCandidate(input.path("matchId").asLong(), input.path("reason").asText());
                default -> "ERROR: unknown tool " + toolName;
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", toolName, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ---- tool implementations ----

    private String getJobDescription(Long requisitionId) {
        JobRequisition r = jobRequisitionRepository.findById(requisitionId).orElse(null);
        if (r == null) return "ERROR: requisition not found";
        return "Title: %s\nDepartment: %s\nLocation: %s\nExperience level: %s\nRequired skills: %s\nStatus: %s\nDescription: %s"
                .formatted(r.getTitle(), r.getDepartment(), r.getLocation(), r.getExperienceLevel(),
                        r.getRequiredSkills(), r.getStatus(), r.getDescription());
    }

    private String getCandidateProfile(Long candidateId) {
        Candidate c = candidateRepository.findById(candidateId).orElse(null);
        if (c == null) return "ERROR: candidate not found";
        String resume = c.getResumeText() == null ? "(no resume)" :
                c.getResumeText().length() > 3000 ? c.getResumeText().substring(0, 3000) + "…" : c.getResumeText();
        return "Name: %s\nHeadline: %s\nSkills: %s\nYears experience: %s\nSource: %s\nResume:\n%s"
                .formatted(c.getFullName(), c.getHeadline(), c.getSkills(), c.getYearsExperience(),
                        c.getSourceChannel(), resume);
    }

    private String screenCandidate(Long candidateId, Long requisitionId) {
        Candidate c = candidateRepository.findById(candidateId).orElse(null);
        JobRequisition r = jobRequisitionRepository.findById(requisitionId).orElse(null);
        if (c == null || r == null) return "ERROR: candidate or requisition not found";
        screeningAgentService.runScreeningInternal(c, r); // no-op if already screened
        return screeningResultRepository.findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .map(sr -> "Overall score: %s/100\nSkills: %s, Experience: %s, Culture fit: %s\nRecommendation: %s\nStrengths: %s\nGaps: %s\nRationale: %s"
                        .formatted(sr.getOverallScore(), sr.getSkillsScore(), sr.getExperienceScore(),
                                sr.getCultureFitScore(), sr.getRecommendation(), sr.getStrengths(),
                                sr.getGaps(), sr.getRationale()))
                .orElse("ERROR: screening produced no result");
    }

    private String checkPipeline(Long requisitionId) {
        var stages = pipelineStageRepository.findByRequisitionId(requisitionId);
        if (stages.isEmpty()) return "Pipeline is empty — no candidates in any stage yet.";
        var counts = new java.util.LinkedHashMap<String, Integer>();
        stages.forEach(s -> counts.merge(s.getStage(), 1, Integer::sum));
        StringBuilder sb = new StringBuilder("Pipeline for requisition " + requisitionId + ":\n");
        counts.forEach((stage, n) -> sb.append("- ").append(stage).append(": ").append(n).append("\n"));
        return sb.toString();
    }

    private String updatePipelineStage(Long candidateId, Long requisitionId, String stage) {
        PipelineStage ps = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElseGet(() -> {
                    PipelineStage np = new PipelineStage();
                    np.setCandidateId(candidateId);
                    np.setRequisitionId(requisitionId);
                    return np;
                });
        ps.setStage(stage);
        ps.setEnteredAt(LocalDateTime.now());
        ps.setUpdatedByAgent("ORCHESTRATOR");
        pipelineStageRepository.save(ps);
        return "Candidate moved to stage " + stage;
    }

    private String draftOutreach(Long matchId) {
        return sourcingAgentService.draftOutreach(matchId).getDraft();
    }

    private String sendOutreach(Long matchId, String emailBody) {
        sourcingAgentService.sendOutreach(matchId, emailBody);
        return "Outreach email sent to candidate.";
    }

    private String rejectCandidate(Long matchId, String reason) {
        sourcingAgentService.updateMatchStatus(matchId, "DISMISSED");
        return "Candidate rejected. Reason recorded: " + reason;
    }

    // ---- schema helpers ----

    private ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode req = schema.putArray("required");
        for (String r : required) req.add(r);
        return t;
    }

    private ObjectNode props(java.util.function.Consumer<ObjectNode> builder) {
        ObjectNode p = mapper.createObjectNode();
        builder.accept(p);
        return p;
    }
}
