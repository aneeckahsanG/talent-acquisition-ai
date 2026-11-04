package com.talentai.orchestrator;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardRequest;
import com.talentai.orchestrator.event.InterviewCompletedEvent;
import com.talentai.orchestrator.service.InterviewRounds;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.service.OrchestratorService.InterviewFeedback;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the agent reads about earlier rounds, the order rounds run in, and the event a scorecard publishes. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewFeedbackTest {

    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private InterviewScheduleRepository interviewScheduleRepository;
    @Mock private InterviewScorecardRepository scorecardRepository;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private OrchestratorService service;

    @Test
    @DisplayName("rounds run phone screen, then technical, then final; nothing follows the final or an unknown type")
    void roundOrder() {
        assertThat(InterviewRounds.next("PHONE_SCREEN")).isEqualTo("TECHNICAL");
        assertThat(InterviewRounds.next("TECHNICAL")).isEqualTo("FINAL");
        assertThat(InterviewRounds.next("FINAL")).isNull();
        assertThat(InterviewRounds.next("COFFEE_CHAT")).isNull();
    }

    @Test
    @DisplayName("feedback lists each round with its verdict and names the round after the latest completed one")
    void feedbackNamesTheNextRound() {
        when(pipelineStageRepository.findByCandidateIdAndRequisitionId(5L, 3L))
                .thenReturn(Optional.of(PipelineStage.builder().stage("INTERVIEW_SCHEDULED").build()));
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().id(11L).interviewType("PHONE_SCREEN").status("COMPLETED").build()));
        when(scorecardRepository.findByInterviewId(11L)).thenReturn(Optional.of(InterviewScorecard.builder()
                .interviewId(11L).verdict("YES").notes("Clear and keen").ratings(Map.of("COMMUNICATION", 4, "MOTIVATION", 5)).build()));

        InterviewFeedback feedback = service.interviewFeedback(3L, 5L);

        assertThat(feedback.stage()).isEqualTo("INTERVIEW_SCHEDULED");
        assertThat(feedback.nextRound()).isEqualTo("TECHNICAL");
        assertThat(feedback.rounds()).hasSize(1);
        assertThat(feedback.rounds().get(0).verdict()).isEqualTo("YES");
        assertThat(feedback.rounds().get(0).averageRating()).isEqualTo(4.5);
        assertThat(feedback.rounds().get(0).notes()).isEqualTo("Clear and keen");
    }

    @Test
    @DisplayName("with no completed round there is no next round, and an unscored round shows no verdict")
    void noNextRoundBeforeAnyIsCompleted() {
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().id(11L).interviewType("PHONE_SCREEN").status("CONFIRMED").build()));
        when(scorecardRepository.findByInterviewId(11L)).thenReturn(Optional.empty());

        InterviewFeedback feedback = service.interviewFeedback(3L, 5L);

        assertThat(feedback.nextRound()).isNull();
        assertThat(feedback.rounds().get(0).verdict()).isNull();
    }

    @Test
    @DisplayName("submitting a scorecard tells the orchestrator the round is complete")
    void aScorecardPublishesAnEvent() {
        when(interviewScheduleRepository.findById(11L)).thenReturn(Optional.of(InterviewSchedule.builder()
                .id(11L).candidateId(5L).requisitionId(3L).interviewType("PHONE_SCREEN").status("CONFIRMED").build()));
        when(scorecardRepository.findByInterviewId(11L)).thenReturn(Optional.empty());
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of());

        service.submitScorecard(11L, scorecard("YES"), null);

        verify(events).publishEvent(new InterviewCompletedEvent(3L, 5L, "PHONE_SCREEN"));
    }

    @Test
    @DisplayName("a scorecard that is refused publishes nothing")
    void aRefusedScorecardPublishesNothing() {
        when(interviewScheduleRepository.findById(11L)).thenReturn(Optional.of(InterviewSchedule.builder()
                .id(11L).candidateId(5L).requisitionId(3L).interviewType("PHONE_SCREEN").status("COMPLETED").build()));

        assertThatThrownBy(() -> service.submitScorecard(11L, scorecard("YES"), null))
                .isInstanceOf(IllegalStateException.class);

        verify(events, never()).publishEvent(any(Object.class));
    }

    private static ScorecardRequest scorecard(String verdict) {
        ScorecardRequest req = new ScorecardRequest();
        req.setVerdict(verdict);
        req.setRatings(Map.of("COMMUNICATION", 4, "MOTIVATION", 4, "RELEVANT_EXPERIENCE", 4, "PRACTICAL_FIT", 4));
        return req;
    }
}
