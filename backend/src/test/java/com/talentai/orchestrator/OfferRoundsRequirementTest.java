package com.talentai.orchestrator;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.orchestrator.service.OrchestratorService;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A candidate cannot be moved to OFFER before the role's required interview rounds are completed. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfferRoundsRequirementTest {

    @Mock private CandidateRepository candidateRepository;
    @Mock private JobRequisitionRepository jobRequisitionRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private InterviewScheduleRepository interviewScheduleRepository;
    @Mock private com.talentai.screening.repository.ScreeningResultRepository screeningResultRepository;
    @Mock private com.talentai.sourcing.repository.SourcingMatchRepository sourcingMatchRepository;
    @Mock private com.talentai.admin.repository.InterviewScorecardRepository scorecardRepository;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private OrchestratorService service;

    private JobRequisition role;
    private PipelineStage row;

    @BeforeEach
    void setUp() {
        role = JobRequisition.builder().id(3L).title("Backend Engineer").build();
        row = PipelineStage.builder().id(1L).candidateId(5L).requisitionId(3L).stage("INTERVIEW_SCHEDULED").build();
        when(jobRequisitionRepository.findById(3L)).thenReturn(Optional.of(role));
        when(candidateRepository.findById(5L)).thenReturn(Optional.of(Candidate.builder().id(5L).fullName("Kevin").email("k@example.com").build()));
        when(pipelineStageRepository.findByCandidateIdAndRequisitionId(5L, 3L)).thenReturn(Optional.of(row));
        when(pipelineStageRepository.findByRequisitionIdOrderByEnteredAtDesc(3L)).thenReturn(List.of(row));
    }

    private void interviews(String... statuses) {
        var list = new java.util.ArrayList<InterviewSchedule>();
        for (int i = 0; i < statuses.length; i++) {
            list.add(InterviewSchedule.builder().id(10L + i).candidateId(5L).requisitionId(3L)
                    .interviewType(i == 0 ? "PHONE_SCREEN" : "TECHNICAL").status(statuses[i]).build());
        }
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(list);
    }

    @Test
    @DisplayName("one completed round is not enough when the role needs two (the default)")
    void refusedBelowTheRequirement() {
        interviews("COMPLETED");

        assertThatThrownBy(() -> service.moveStage(3L, 5L, "OFFER", "HUMAN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 of 2 required interview rounds completed");

        assertThat(row.getStage()).isEqualTo("INTERVIEW_SCHEDULED");
        verify(pipelineStageRepository, never()).save(any());
    }

    @Test
    @DisplayName("a booked but unfinished round does not count")
    void onlyCompletedRoundsCount() {
        interviews("COMPLETED", "CONFIRMED");

        assertThatThrownBy(() -> service.moveStage(3L, 5L, "OFFER", "HUMAN"))
                .hasMessageContaining("1 of 2");
    }

    @Test
    @DisplayName("the move goes through once the required rounds are completed")
    void allowedAtTheRequirement() {
        interviews("COMPLETED", "COMPLETED");

        service.moveStage(3L, 5L, "OFFER", "HUMAN");

        assertThat(row.getStage()).isEqualTo("OFFER");
    }

    @Test
    @DisplayName("a role can ask for more rounds than the default")
    void rolesOwnRequirementApplies() {
        role.setRequiredInterviewRounds(3);
        interviews("COMPLETED", "COMPLETED");

        assertThatThrownBy(() -> service.moveStage(3L, 5L, "OFFER", "HUMAN"))
                .hasMessageContaining("2 of 3");
    }

    @Test
    @DisplayName("the board tells the screen how many rounds each candidate's role needs")
    void boardCarriesTheRequirement() {
        interviews("COMPLETED");

        var board = service.getPipelineBoard(3L);
        assertThat(board.getStages().get("INTERVIEW_SCHEDULED")).hasSize(1);
        assertThat(board.getStages().get("INTERVIEW_SCHEDULED").get(0).getRequiredInterviewRounds()).isEqualTo(2);

        role.setRequiredInterviewRounds(3);
        assertThat(service.getPipelineBoard(3L).getStages().get("INTERVIEW_SCHEDULED").get(0).getRequiredInterviewRounds()).isEqualTo(3);
    }
}
