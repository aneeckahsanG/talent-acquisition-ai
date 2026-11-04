package com.talentai.orchestrator;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.agent.AgentRunRepository;
import com.talentai.orchestrator.agent.InterviewFollowUpListener;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import com.talentai.orchestrator.event.InterviewCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A scored interview round starts one agent run, unless that would be wasteful or unsafe, and never breaks the submission. */
class InterviewFollowUpListenerTest {

    private final RecruitingOrchestratorService orchestrator = mock(RecruitingOrchestratorService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final AiBudgetGuard budget = mock(AiBudgetGuard.class);
    private InterviewFollowUpListener listener;
    private final InterviewCompletedEvent event = new InterviewCompletedEvent(3L, 5L, "PHONE_SCREEN");

    @BeforeEach
    void setUp() {
        listener = new InterviewFollowUpListener(orchestrator, runs, candidates, budget);
        ReflectionTestUtils.setField(listener, "enabled", true);
        when(candidates.findById(5L)).thenReturn(Optional.of(Candidate.builder().id(5L).fullName("Priya Sharma").build()));
    }

    @Test
    @DisplayName("a completed round starts a run for that candidate, role and interview type")
    void startsARun() {
        listener.onInterviewCompleted(event);

        verify(orchestrator).startInterviewFollowUpRun(5L, 3L, "Priya Sharma", "PHONE_SCREEN");
    }

    @Test
    @DisplayName("a run already running or waiting for approval for the same candidate and role is not duplicated")
    void noSecondRunWhileOneIsOpen() {
        when(runs.existsByCandidateIdAndRequisitionIdAndStatusIn(eq(5L), eq(3L), any())).thenReturn(true);

        listener.onInterviewCompleted(event);

        verify(orchestrator, never()).startInterviewFollowUpRun(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("nothing starts while AI use is paused, or when the setting is off")
    void respectsBudgetAndSwitch() {
        when(budget.isBlocked()).thenReturn(true);
        listener.onInterviewCompleted(event);
        when(budget.isBlocked()).thenReturn(false);
        ReflectionTestUtils.setField(listener, "enabled", false);
        listener.onInterviewCompleted(event);

        verify(orchestrator, never()).startInterviewFollowUpRun(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("a failure to start is swallowed so the interviewer's scorecard still goes through")
    void failuresAreNotPropagated() {
        when(orchestrator.startInterviewFollowUpRun(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        listener.onInterviewCompleted(event);

        verify(orchestrator).startInterviewFollowUpRun(any(), any(), any(), any());
    }
}
