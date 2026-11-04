package com.talentai.orchestrator;

import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import com.talentai.orchestrator.dto.OrchestratorDtos.PipelineCandidateResponse;
import com.talentai.orchestrator.event.CandidateShortlistedEvent;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Asking for the stage a candidate is already in must not take the move over from whoever made it. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MoveStageSameStageTest {

    @Mock private CandidateRepository candidateRepository;
    @Mock private JobRequisitionRepository jobRequisitionRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private EmailService emailService;
    @Mock private SourcingMatchRepository sourcingMatchRepository;
    @Mock private ScreeningResultRepository screeningResultRepository;
    @Mock private ApplicationEventPublisher events;
    @Mock private InterviewScheduleRepository interviewScheduleRepository;
    @InjectMocks private OrchestratorService service;

    private final LocalDateTime screenedAt = LocalDateTime.of(2026, 11, 3, 10, 0);
    private PipelineStage row;

    @BeforeEach
    void setUp() {
        row = PipelineStage.builder().id(1L).candidateId(5L).requisitionId(3L)
                .stage("SCREENED").updatedByAgent("SCREENING").enteredAt(screenedAt).build();
        when(pipelineStageRepository.findByCandidateIdAndRequisitionId(5L, 3L)).thenReturn(Optional.of(row));
        when(candidateRepository.findById(5L)).thenReturn(Optional.of(
                Candidate.builder().id(5L).fullName("Syazwan").email("s@example.com").build()));
        when(jobRequisitionRepository.findById(3L)).thenReturn(Optional.of(JobRequisition.builder().id(3L).title("Backend").build()));
    }

    @Test
    @DisplayName("the agent confirming SCREENED leaves the Screening Agent as the actor and the time unchanged")
    void sameStageIsANoOp() {
        PipelineCandidateResponse r = service.moveStage(3L, 5L, "SCREENED", "ORCHESTRATOR");

        assertEquals("SCREENING", row.getUpdatedByAgent());
        assertEquals(screenedAt, row.getEnteredAt());
        assertEquals("SCREENING", r.getUpdatedByAgent());
        verify(pipelineStageRepository, never()).save(any());
    }

    @Test
    @DisplayName("a real move is still recorded against whoever made it")
    void aRealMoveIsRecorded() {
        PipelineCandidateResponse r = service.moveStage(3L, 5L, "SHORTLISTED", "ORCHESTRATOR");

        assertEquals("SHORTLISTED", row.getStage());
        assertEquals("ORCHESTRATOR", row.getUpdatedByAgent());
        assertEquals("ORCHESTRATOR", r.getUpdatedByAgent());
        verify(pipelineStageRepository).save(row);
    }

    @Test
    @DisplayName("a recruiter shortlisting a candidate tells the orchestrator agent")
    void aRecruitersShortlistPublishesAnEvent() {
        service.moveStage(3L, 5L, "SHORTLISTED", "HUMAN");

        verify(events).publishEvent(new CandidateShortlistedEvent(3L, 5L));
    }

    @Test
    @DisplayName("the agent's own shortlist, a move that changes nothing, and other stages do not")
    void otherMovesPublishNothing() {
        service.moveStage(3L, 5L, "SHORTLISTED", "ORCHESTRATOR");
        service.moveStage(3L, 5L, "REJECTED", "HUMAN");
        row.setStage("SHORTLISTED");
        service.moveStage(3L, 5L, "SHORTLISTED", "HUMAN");

        verify(events, never()).publishEvent(any(Object.class));
    }
}
