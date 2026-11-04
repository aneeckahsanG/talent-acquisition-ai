package com.talentai.orchestrator.agent;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.event.InterviewCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * When an interviewer's scorecard completes a round, hands that to the orchestrator agent to decide the
 * next step (invite to the next round, queue a rejection, or leave it to a recruiter). Runs after the
 * scorecard has committed so the agent sees it, and never fails the interviewer's submission: every
 * problem is only logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpListener {

    private static final List<String> OPEN_RUN_STATUSES = List.of("RUNNING", "PAUSED_FOR_APPROVAL");

    private final RecruitingOrchestratorService orchestratorService;
    private final AgentRunRepository runRepository;
    private final CandidateRepository candidateRepository;
    private final AiBudgetGuard budgetGuard;

    @Value("${app.agent.interview-follow-up:true}")
    private boolean enabled;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewCompleted(InterviewCompletedEvent event) {
        try {
            if (!enabled) return;
            if (budgetGuard.isBlocked()) {
                log.info("Not starting a follow-up run for candidate {}: AI use is paused", event.candidateId());
                return;
            }
            // One run at a time per candidate and role: a second scorecard must not queue a second invitation
            if (runRepository.existsByCandidateIdAndRequisitionIdAndStatusIn(
                    event.candidateId(), event.requisitionId(), OPEN_RUN_STATUSES)) {
                return;
            }
            String name = candidateRepository.findById(event.candidateId()).map(c -> c.getFullName()).orElse("candidate");
            orchestratorService.startInterviewFollowUpRun(event.candidateId(), event.requisitionId(), name, event.interviewType());
        } catch (Exception e) {
            log.warn("Could not start the follow-up run for candidate {} after an interview: {}", event.candidateId(), e.getMessage());
        }
    }
}
