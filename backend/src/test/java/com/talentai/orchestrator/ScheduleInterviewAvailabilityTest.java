package com.talentai.orchestrator;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScheduleInterviewRequest;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A booking link is only sent when the candidate will have times to choose from. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleInterviewAvailabilityTest {

    @Mock private CandidateRepository candidateRepository;
    @Mock private JobRequisitionRepository jobRequisitionRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private InterviewScheduleRepository interviewScheduleRepository;
    @Mock private PipelineStageRepository pipelineStageRepository;
    @Mock private InterviewBookingService interviewBookingService;
    @Mock private EmailService emailService;
    @Mock private org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks private OrchestratorService service;

    private ScheduleInterviewRequest request;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:5173");
        when(candidateRepository.findById(5L)).thenReturn(Optional.of(
                Candidate.builder().id(5L).fullName("Priya Sharma").email("priya@example.com").build()));
        when(jobRequisitionRepository.findById(3L)).thenReturn(Optional.of(JobRequisition.builder().id(3L).title("AI/ML Engineer").build()));
        when(appUserRepository.existsById(9L)).thenReturn(true);
        when(interviewScheduleRepository.save(any(InterviewSchedule.class))).thenAnswer(i -> {
            InterviewSchedule iv = i.getArgument(0);
            if (iv.getId() == null) iv.setId(40L);
            return iv;
        });
        when(pipelineStageRepository.findByCandidateIdAndRequisitionId(5L, 3L)).thenReturn(Optional.empty());

        request = new ScheduleInterviewRequest();
        request.setInterviewType("TECHNICAL");
        request.setInterviewerId(9L);
    }

    @Test
    @DisplayName("with no free times published, nothing is saved, emailed or moved, and the recruiter is told why")
    void refusesWhenNothingIsPublished() {
        when(interviewBookingService.availableStarts("TECHNICAL", 9L, null)).thenReturn(List.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.scheduleInterview(3L, 5L, request));

        assertTrue(e.getMessage().contains("Publish availability on the Calendar"), e.getMessage());
        verify(interviewScheduleRepository, never()).save(any());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
        verify(pipelineStageRepository, never()).save(any());
    }

    @Test
    @DisplayName("with a free time published, the link is emailed and the candidate moves to Interview")
    void sendsWhenATimeIsFree() {
        when(interviewBookingService.availableStarts("TECHNICAL", 9L, null))
                .thenReturn(List.of(LocalDateTime.now().plusDays(1)));

        service.scheduleInterview(3L, 5L, request);

        verify(emailService).sendHtml(eq("priya@example.com"), anyString(), any());
        verify(pipelineStageRepository).save(any());
    }

    @Test
    @DisplayName("without an interviewer the shared pool is checked")
    void checksTheSharedPoolWithoutAnInterviewer() {
        request.setInterviewerId(null);
        when(interviewBookingService.availableStarts(eq("TECHNICAL"), isNull(), isNull())).thenReturn(List.of());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.scheduleInterview(3L, 5L, request));

        assertTrue(!e.getMessage().contains("for this interviewer"), e.getMessage());
    }
}
