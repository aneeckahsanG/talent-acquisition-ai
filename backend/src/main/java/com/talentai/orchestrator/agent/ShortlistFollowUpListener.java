package com.talentai.orchestrator.agent;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.event.CandidateShortlistedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * When a recruiter shortlists a candidate, hands that to the orchestrator agent to decide the next step
 * (today: invite them to book an interview, with recruiter approval). Runs after the stage change has
 * committed so the agent sees it, and never fails the recruiter's click: every problem is only logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShortlistFollowUpListener {

    private static final List<String> OPEN_RUN_STATUSES = List.of("RUNNING", "PAUSED_FOR_APPROVAL");

    private final RecruitingOrchestratorService orchestratorService;
    private final AgentRunRepository runRepository;
    private final CandidateRepository candidateRepository;
    private final AiBudgetGuard budgetGuard;

    @Value("${app.agent.shortlist-follow-up:true}")
    private boolean enabled;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShortlisted(CandidateShortlistedEvent event) {
        try {
            if (!enabled) return;
            if (budgetGuard.isBlocked()) {
                log.info("Not starting a follow-up run for candidate {}: AI use is paused", event.candidateId());
                return;
            }
            // One run at a time per candidate and role: shortlisting twice must not queue two invitations
            boolean alreadyOpen = runRepository.existsByCandidateIdAndRequisitionIdAndStatusIn(
                    event.candidateId(), event.requisitionId(), OPEN_RUN_STATUSES);
            if (alreadyOpen) return;

            String name = candidateRepository.findById(event.candidateId()).map(c -> c.getFullName()).orElse("candidate");
            orchestratorService.startShortlistRun(event.candidateId(), event.requisitionId(), name);
        } catch (Exception e) {
            log.warn("Could not start the follow-up run for shortlisted candidate {}: {}", event.candidateId(), e.getMessage());
        }
    }
}
