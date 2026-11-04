package com.talentai.orchestrator;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.agent.AgentRun;
import com.talentai.orchestrator.agent.AgentRunRepository;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import com.talentai.orchestrator.agent.ShortlistFollowUpListener;
import com.talentai.orchestrator.event.CandidateShortlistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A recruiter's shortlist starts one agent run, unless that would be wasteful or unsafe, and never breaks the click. */
class ShortlistFollowUpListenerTest {

    private final RecruitingOrchestratorService orchestrator = mock(RecruitingOrchestratorService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final AiBudgetGuard budget = mock(AiBudgetGuard.class);
    private ShortlistFollowUpListener listener;
    private final CandidateShortlistedEvent event = new CandidateShortlistedEvent(3L, 5L);

    @BeforeEach
    void setUp() {
        listener = new ShortlistFollowUpListener(orchestrator, runs, candidates, budget);
        ReflectionTestUtils.setField(listener, "enabled", true);
        when(candidates.findById(5L)).thenReturn(Optional.of(Candidate.builder().id(5L).fullName("Priya Sharma").build()));
        when(runs.findByStatusOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("a shortlist starts a run for that candidate and role")
    void startsARun() {
        listener.onShortlisted(event);

        verify(orchestrator).startShortlistRun(5L, 3L, "Priya Sharma");
    }

    @Test
    @DisplayName("a run already running or waiting for approval for the same candidate and role is not duplicated")
    void noSecondRunWhileOneIsOpen() {
        when(runs.findByStatusOrderByCreatedAtDesc("PAUSED_FOR_APPROVAL"))
                .thenReturn(List.of(AgentRun.builder().id(1L).candidateId(5L).requisitionId(3L).status("PAUSED_FOR_APPROVAL").build()));

        listener.onShortlisted(event);

        verify(orchestrator, never()).startShortlistRun(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a run for a different candidate does not block this one")
    void otherCandidatesRunDoesNotBlock() {
        when(runs.findByStatusOrderByCreatedAtDesc("RUNNING"))
                .thenReturn(List.of(AgentRun.builder().id(1L).candidateId(6L).requisitionId(3L).status("RUNNING").build()));

        listener.onShortlisted(event);

        verify(orchestrator).startShortlistRun(5L, 3L, "Priya Sharma");
    }

    @Test
    @DisplayName("nothing starts while AI use is paused, or when the setting is off")
    void respectsBudgetAndSwitch() {
        when(budget.isBlocked()).thenReturn(true);
        listener.onShortlisted(event);
        when(budget.isBlocked()).thenReturn(false);
        ReflectionTestUtils.setField(listener, "enabled", false);
        listener.onShortlisted(event);

        verify(orchestrator, never()).startShortlistRun(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a failure to start is swallowed so the recruiter's shortlist still succeeds")
    void failuresAreNotPropagated() {
        when(orchestrator.startShortlistRun(anyLong(), anyLong(), anyString())).thenThrow(new IllegalStateException("boom"));

        listener.onShortlisted(event);

        verify(orchestrator).startShortlistRun(any(), any(), any());
    }
}
