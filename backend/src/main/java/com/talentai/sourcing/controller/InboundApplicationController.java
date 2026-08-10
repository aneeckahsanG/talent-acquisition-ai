package com.talentai.sourcing.controller;

import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.dto.SourcingDtos.DirectApplyRequest;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Public inbound webhook endpoint — receives job applications POSTed by external
 * job boards (e.g. TalentBoard, LinkedIn, JobStreet) and routes them into TalentAI
 * as direct applicants for the specified requisition.
 *
 * This is the equivalent of Greenhouse's /v1/boards/{token}/jobs/{id}/applications
 * or Workday's Apply API endpoint.
 */
@RestController
@RequestMapping("/api/inbound")
@RequiredArgsConstructor
@Slf4j
public class InboundApplicationController {

    private final SourcingAgentService sourcingAgentService;
    private final ScreeningAgentService screeningAgentService;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final RecruitingOrchestratorService orchestratorService;

    /**
     * Receives an application from an external job board webhook.
     *
     * Expected payload:
     * {
     *   "fullName":        "Ahmad Farid",
     *   "email":           "ahmad@email.com",
     *   "headline":        "Senior Engineer at Grab",
     *   "skills":          "Java, Spring Boot, AWS",
     *   "yearsExperience": 5,
     *   "resumeText":      "...",
     *   "source":          "TALENTBOARD",
     *   "jobTitle":        "Senior Software Engineer",    (optional, for logging)
     *   "company":         "TechCorp Malaysia"            (optional, for logging)
     * }
     */
    @PostMapping("/applications/{requisitionId}")
    public ResponseEntity<Map<String, Object>> receiveApplication(
            @PathVariable Long requisitionId,
            @RequestBody Map<String, Object> payload) {

        String source = str(payload, "source", "JOBBOARD");
        String fullName = str(payload, "fullName", "Unknown");
        String jobTitle = str(payload, "jobTitle", "");
        String company = str(payload, "company", "");

        log.info("Inbound application received: '{}' from '{}' via {} webhook → requisition {}",
                fullName, company.isBlank() ? "unknown company" : company, source, requisitionId);

        try {
            DirectApplyRequest req = new DirectApplyRequest();
            req.setFullName(fullName);
            req.setEmail(str(payload, "email", null));
            req.setHeadline(str(payload, "headline", null));
            req.setSkills(str(payload, "skills", null));
            req.setResumeText(str(payload, "resumeText", null));
            req.setCoverLetter(str(payload, "coverLetter", null));
            if (payload.get("yearsExperience") != null) {
                req.setYearsExperience(new java.math.BigDecimal(payload.get("yearsExperience").toString()));
            }

            var result = sourcingAgentService.directApply(requisitionId, req);
            Long matchId = result.getMatchId();

            log.info("Inbound application accepted: '{}' → matchId {}", fullName, matchId);

            // Hand the application to the agentic orchestrator — Claude decides the steps
            // (screen, check pipeline, shortlist/reject, draft outreach) with human approval gates.
            triggerAgentRun(matchId, fullName, requisitionId);

            return ResponseEntity.ok(Map.of(
                    "status", "received",
                    "matchId", matchId,
                    "message", result.getMessage()
            ));

        } catch (IllegalStateException e) {
            log.info("Duplicate inbound application from '{}' for requisition {} — skipped", fullName, requisitionId);
            return ResponseEntity.status(409).body(Map.of(
                    "status", "duplicate",
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid inbound application for requisition {}: {}", requisitionId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Failed to process inbound application from '{}': {}", fullName, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to process application"
            ));
        }
    }

    private void triggerAgentRun(Long matchId, String fullName, Long requisitionId) {
        try {
            var match = sourcingMatchRepository.findById(matchId).orElse(null);
            if (match == null) return;
            var run = orchestratorService.startRunAsync(matchId, match.getCandidateId(), requisitionId, fullName);
            log.info("Agent run {} started for inbound applicant '{}' → requisition {}", run.getId(), fullName, requisitionId);
        } catch (Exception e) {
            // Fallback: if the orchestrator cannot start, still screen the candidate directly
            log.warn("Agent run failed to start for matchId {} ({}), falling back to direct screening", matchId, e.getMessage());
            CompletableFuture.runAsync(() -> {
                var match = sourcingMatchRepository.findById(matchId).orElse(null);
                if (match == null) return;
                var candidate = candidateRepository.findById(match.getCandidateId()).orElse(null);
                var requisition = jobRequisitionRepository.findById(match.getRequisitionId()).orElse(null);
                if (candidate != null && requisition != null) {
                    screeningAgentService.runScreeningInternal(candidate, requisition);
                }
            });
        }
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : defaultValue;
    }
}
