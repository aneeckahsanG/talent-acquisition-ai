package com.talentai.orchestrator;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewScheduleRepository;
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

    @Test
    @DisplayName("no email address, or an unknown interview type, is reported rather than attempted")
    void otherProblems() {
        priya.setEmail(" ");
        assertThat(service.checkBookingLink(3L, 5L, "PHONE_SCREEN").problem()).contains("no email address");

        priya.setEmail("priya@example.com");
        assertThat(service.checkBookingLink(3L, 5L, "COFFEE_CHAT").problem()).contains("Unknown interview type");
    }
}
