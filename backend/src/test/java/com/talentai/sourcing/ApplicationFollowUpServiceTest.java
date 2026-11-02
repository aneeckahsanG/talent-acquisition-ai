package com.talentai.sourcing;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.EmailService;
import com.talentai.orchestrator.agent.AgentRun;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import com.talentai.sourcing.service.ApplicationFollowUpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What follows a saved application, the same for the careers page and a job-board webhook. */
class ApplicationFollowUpServiceTest {

    private JobRequisitionRepository requisitions;
    private SourcingMatchRepository matches;
    private CandidateRepository candidates;
    private ScreeningAgentService screening;
    private RecruitingOrchestratorService orchestrator;
    private EmailService email;
    private ApplicationFollowUpService service;

    private final JobRequisition role = JobRequisition.builder().id(3L).title("AI/ML Engineer").build();
    private final SourcingMatch match = SourcingMatch.builder().id(7L).candidateId(12L).requisitionId(3L).build();
    private final Candidate priya = Candidate.builder().id(12L).fullName("Priya Sharma").build();

    @BeforeEach
    void setUp() {
        requisitions = mock(JobRequisitionRepository.class);
        matches = mock(SourcingMatchRepository.class);
        candidates = mock(CandidateRepository.class);
        screening = mock(ScreeningAgentService.class);
        orchestrator = mock(RecruitingOrchestratorService.class);
        email = mock(EmailService.class);
        service = new ApplicationFollowUpService(requisitions, matches, candidates, screening, orchestrator, email);

        when(requisitions.findById(3L)).thenReturn(Optional.of(role));
        when(matches.findById(7L)).thenReturn(Optional.of(match));
        when(candidates.findById(12L)).thenReturn(Optional.of(priya));
        when(orchestrator.startRunAsync(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(AgentRun.builder().id(1L).build());
    }

    @Test
    @DisplayName("HR is told about the application and the orchestrator agent starts for that candidate")
    void notifiesHrAndStartsTheAgent() {
        service.afterApplication(7L, 3L, "Priya Sharma", "priya@example.com", "CV text", "CAREERS_PAGE");

        verify(email).notifyHrOfApplication("Priya Sharma", "priya@example.com", "AI/ML Engineer", "CV text", "CAREERS_PAGE");
        verify(orchestrator).startRunAsync(7L, 12L, 3L, "Priya Sharma");
    }

    @Test
    @DisplayName("a failing HR email does not stop the agent")
    void hrEmailFailureIsNotFatal() {
        doThrow(new IllegalStateException("mail down")).when(email)
                .notifyHrOfApplication(anyString(), anyString(), anyString(), anyString(), anyString());

        service.afterApplication(7L, 3L, "Priya Sharma", "priya@example.com", "CV text", "CAREERS_PAGE");

        verify(orchestrator).startRunAsync(7L, 12L, 3L, "Priya Sharma");
    }

    @Test
    @DisplayName("if the agent cannot start, the candidate is still screened directly")
    void fallsBackToScreening() {
        when(orchestrator.startRunAsync(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("could not start"));

        service.afterApplication(7L, 3L, "Priya Sharma", "priya@example.com", "CV text", "CAREERS_PAGE");

        verify(screening, timeout(3000)).runScreeningInternal(priya, role);
    }

    @Test
    @DisplayName("an application that cannot be found starts nothing and raises nothing")
    void unknownMatchIsIgnored() {
        service.afterApplication(99L, 3L, "Priya Sharma", "priya@example.com", "CV text", "CAREERS_PAGE");

        verify(orchestrator, never()).startRunAsync(anyLong(), anyLong(), anyLong(), anyString());
        verify(email).notifyHrOfApplication(eq("Priya Sharma"), anyString(), anyString(), anyString(), anyString());
    }
}
