package com.talentai.orchestrator;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.service.OrchestratorService.BookingLinkCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** The checks made before a recruiter is asked to approve an agent's booking link. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingLinkCheckTest {

    @Mock private CandidateRepository candidateRepository;
    @Mock private JobRequisitionRepository jobRequisitionRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private InterviewScheduleRepository interviewScheduleRepository;
    @Mock private InterviewBookingService interviewBookingService;
    @Mock private InterviewScorecardRepository scorecardRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks private OrchestratorService service;

    private Candidate priya;
    private PipelineStage row;

    @BeforeEach
    void setUp() {
        priya = Candidate.builder().id(5L).fullName("Priya Sharma").email("priya@example.com").build();
        row = PipelineStage.builder().id(1L).candidateId(5L).requisitionId(3L).stage("SHORTLISTED").build();
        when(candidateRepository.findById(5L)).thenReturn(Optional.of(priya));
        when(jobRequisitionRepository.findById(3L)).thenReturn(Optional.of(JobRequisition.builder().id(3L).title("Backend Engineer").build()));
        when(pipelineStageRepository.findByCandidateIdAndRequisitionId(5L, 3L)).thenReturn(Optional.of(row));
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of());
        when(interviewBookingService.availableStarts(org.mockito.ArgumentMatchers.eq("PHONE_SCREEN"), isNull(), isNull()))
                .thenReturn(List.of(LocalDateTime.now().plusDays(1)));
    }

    @Test
    @DisplayName("a shortlisted candidate with free times gets the invitation text and no problem")
    void okWhenShortlistedAndTimesExist() {
        BookingLinkCheck check = service.checkBookingLink(3L, 5L, "PHONE_SCREEN");

        assertThat(check.problem()).isNull();
        assertThat(check.emailText()).startsWith("Dear Priya,").contains("Backend Engineer").contains("phone screen");
    }

    @Test
    @DisplayName("no published times: a recruiter is told to publish availability, and nothing is offered for approval")
    void problemWhenNoTimes() {
        when(interviewBookingService.availableStarts(org.mockito.ArgumentMatchers.eq("PHONE_SCREEN"), isNull(), isNull()))
                .thenReturn(List.of());

        BookingLinkCheck check = service.checkBookingLink(3L, 5L, "PHONE_SCREEN");

        assertThat(check.emailText()).isNull();
        assertThat(check.problem()).contains("No free interview times are published").contains("publish availability");
    }

    @Test
    @DisplayName("a candidate who is not shortlisted is not sent a link")
    void problemWhenNotShortlisted() {
        row.setStage("SCREENED");

        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).contains("SCREENED").contains("not SHORTLISTED");
    }

    @Test
    @DisplayName("a live invitation for the same interview type blocks a second one")
    void problemWhenAlreadyInvited() {
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().candidateId(5L).requisitionId(3L).interviewType("PHONE_SCREEN").status("PROPOSED").build()));

        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).contains("already has");
    }

    @Test
    @DisplayName("a completed round of the same type does not stop the next invitation")
    void completedRoundDoesNotBlock() {
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().candidateId(5L).requisitionId(3L).interviewType("PHONE_SCREEN").status("COMPLETED").build()));

        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).isNull();
    }

    // ── between rounds ───────────────────────────────────────────────────────

    private void betweenRounds(String lastType, String verdict) {
        row.setStage("INTERVIEW_SCHEDULED");
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().id(11L).candidateId(5L).requisitionId(3L).interviewType(lastType).status("COMPLETED").build()));
        when(scorecardRepository.findByInterviewId(11L)).thenReturn(verdict == null ? Optional.empty()
                : Optional.of(InterviewScorecard.builder().interviewId(11L).verdict(verdict).build()));
        when(interviewBookingService.availableStarts(org.mockito.ArgumentMatchers.anyString(), isNull(), isNull()))
                .thenReturn(List.of(LocalDateTime.now().plusDays(1)));
    }

    @Test
    @DisplayName("after a good phone screen the technical round can be offered")
    void nextRoundAfterAGoodRound() {
        betweenRounds("PHONE_SCREEN", "YES");

        BookingLinkCheck check = service.checkBookingLink(3L, 5L, "TECHNICAL");

        assertThat(check.problem()).isNull();
        assertThat(check.emailText()).contains("technical");
    }

    @Test
    @DisplayName("a round out of order, or the same round again, is refused")
    void onlyTheNextRoundInOrder() {
        betweenRounds("PHONE_SCREEN", "STRONG_YES");

        assertThat(service.checkBookingLink(3L, 5L, "FINAL").problem()).contains("next one is TECHNICAL");
        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).contains("next one is TECHNICAL");
    }

    @Test
    @DisplayName("a NO or STRONG_NO verdict, or a round with no scorecard, offers nothing")
    void noNextRoundAfterAWeakRound() {
        betweenRounds("PHONE_SCREEN", "NO");
        assertThat(service.checkBookingLink(3L, 5L, "TECHNICAL").problem()).contains("verdict was NO");

        betweenRounds("PHONE_SCREEN", "STRONG_NO");
        assertThat(service.checkBookingLink(3L, 5L, "TECHNICAL").problem()).contains("STRONG_NO");

        betweenRounds("PHONE_SCREEN", null);
        assertThat(service.checkBookingLink(3L, 5L, "TECHNICAL").problem()).contains("no scorecard");
    }

    @Test
    @DisplayName("after the final round there is nothing left to offer")
    void nothingAfterTheFinalRound() {
        betweenRounds("FINAL", "STRONG_YES");

        assertThat(service.checkBookingLink(3L, 5L, "FINAL").problem()).contains("last one");
    }

    @Test
    @DisplayName("while another round is still booked or proposed, the next one waits")
    void nextRoundWaitsForAnOpenOne() {
        betweenRounds("PHONE_SCREEN", "YES");
        when(interviewScheduleRepository.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(5L, 3L)).thenReturn(List.of(
                InterviewSchedule.builder().id(11L).interviewType("PHONE_SCREEN").status("COMPLETED").build(),
                InterviewSchedule.builder().id(12L).interviewType("TECHNICAL").status("PROPOSED").build()));

        assertThat(service.checkBookingLink(3L, 5L, "FINAL").problem()).contains("already has a TECHNICAL interview that is not completed");
    }

    @Test
    @DisplayName("a candidate at another stage, such as an offer, is not sent a link")
    void otherStagesAreRefused() {
        row.setStage("OFFER");

        assertThat(service.checkBookingLink(3L, 5L, "TECHNICAL").problem()).contains("OFFER").contains("not SHORTLISTED");
    }

    @Test
    @DisplayName("no email address, or an unknown interview type, is reported rather than attempted")
    void otherProblems() {
        priya.setEmail(" ");
        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).contains("no email address");

        priya.setEmail("priya@example.com");
        assertThat(service.checkBookingLink(3L, 5L, "COFFEE_CHAT").problem()).contains("Unknown interview type");
    }
}
