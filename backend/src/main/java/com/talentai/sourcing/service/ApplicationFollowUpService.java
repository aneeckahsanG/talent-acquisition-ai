package com.talentai.sourcing.service;

import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.EmailService;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * What happens once a direct application (careers page or job-board webhook) has been saved and
 * screened: tell HR, then hand the application to the orchestrator agent, which decides the next
 * steps with human approval on anything candidate-facing. Both doors call this so an applicant is
 * treated the same whichever way they came in.
 *
 * Nothing here may fail the application: it is already saved, so every step is best-effort.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationFollowUpService {

    private final JobRequisitionRepository jobRequisitionRepository;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final CandidateRepository candidateRepository;
    private final ScreeningAgentService screeningAgentService;
    private final RecruitingOrchestratorService orchestratorService;
    private final EmailService emailService;

    public void afterApplication(Long matchId, Long requisitionId, String fullName, String email,
                                 String resumeText, String source) {
        notifyHr(requisitionId, fullName, email, resumeText, source);
        triggerAgentRun(matchId, fullName, requisitionId);
    }

    private void notifyHr(Long requisitionId, String fullName, String email, String resumeText, String source) {
        try {
            String roleName = jobRequisitionRepository.findById(requisitionId)
                    .map(r -> r.getTitle()).orElse("Requisition #" + requisitionId);
            emailService.notifyHrOfApplication(fullName, email, roleName, resumeText, source);
        } catch (Exception e) {
            log.warn("Could not notify HR of the application from '{}': {}", fullName, e.getMessage());
        }
    }

    private void triggerAgentRun(Long matchId, String fullName, Long requisitionId) {
        try {
            var match = sourcingMatchRepository.findById(matchId).orElse(null);
            if (match == null) return;
            var run = orchestratorService.startRunAsync(matchId, match.getCandidateId(), requisitionId, fullName);
            log.info("Agent run {} started for applicant '{}' → requisition {}", run.getId(), fullName, requisitionId);
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
}
