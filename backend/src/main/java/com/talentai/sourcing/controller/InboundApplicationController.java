package com.talentai.sourcing.controller;

import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
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

            // Trigger AI screening asynchronously so the webhook response returns immediately
            triggerScreeningAsync(matchId, fullName, requisitionId);

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

    private void triggerScreeningAsync(Long matchId, String fullName, Long requisitionId) {
        CompletableFuture.runAsync(() -> {
            try {
                var match = sourcingMatchRepository.findById(matchId).orElse(null);
                if (match == null) return;

                var candidate = candidateRepository.findById(match.getCandidateId()).orElse(null);
                var requisition = jobRequisitionRepository.findById(match.getRequisitionId()).orElse(null);
                if (candidate == null || requisition == null) return;

                log.info("Auto-screening inbound applicant '{}' for requisition '{}'", fullName, requisition.getTitle());
                screeningAgentService.runScreeningInternal(candidate, requisition);
                log.info("Auto-screening complete for '{}'", fullName);
            } catch (Exception e) {
                log.warn("Auto-screening failed for matchId {}: {}", matchId, e.getMessage());
            }
        });
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : defaultValue;
    }
}
