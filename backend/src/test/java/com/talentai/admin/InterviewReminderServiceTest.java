package com.talentai.admin;

import com.talentai.admin.entity.InterviewReminder;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewReminderRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.admin.service.InterviewReminderService;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewReminderServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    private InterviewScheduleRepository schedules;
    private InterviewScorecardRepository scorecards;
    private PipelineStageRepository stages;
    private InterviewBookingService booking;
    private EmailService email;
    private AgentActivityLogRepository activity;
    private InterviewReminderService service;
    private final Set<String> sent = new HashSet<>();

    @BeforeEach
    void setUp() {
        schedules = mock(InterviewScheduleRepository.class);
        scorecards = mock(InterviewScorecardRepository.class);
        stages = mock(PipelineStageRepository.class);
        booking = mock(InterviewBookingService.class);
        email = mock(EmailService.class);
        when(email.wrapInTemplate(anyString(), anyString())).thenReturn("<html/>");
        when(email.wrapInTemplateWithExtra(anyString(), anyString(), anyString())).thenReturn("<html/>");
        activity = mock(AgentActivityLogRepository.class);
        CandidateRepository candidates = mock(CandidateRepository.class);
        JobRequisitionRepository requisitions = mock(JobRequisitionRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        InterviewReminderRepository reminders = mock(InterviewReminderRepository.class);

        // in-memory stand-in for the unique (interview, kind, ref) key
        when(reminders.existsByInterviewIdAndKindAndRef(anyLong(), anyString(), anyString()))
                .thenAnswer(i -> sent.contains(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2)));
        when(reminders.save(any(InterviewReminder.class))).thenAnswer(i -> {
            InterviewReminder r = i.getArgument(0);
            sent.add(r.getInterviewId() + "|" + r.getKind() + "|" + r.getRef());
            return r;
        });

        Candidate candidate = new Candidate();
        candidate.setId(7L);
        candidate.setFullName("Chen Yu Ming");
        candidate.setEmail("chen@example.com");
        when(candidates.findById(7L)).thenReturn(Optional.of(candidate));
        JobRequisition requisition = new JobRequisition();
        requisition.setId(3L);
        requisition.setTitle("Backend Engineer");
        when(requisitions.findById(3L)).thenReturn(Optional.of(requisition));
        AppUser interviewer = new AppUser();
        interviewer.setId(11L);
        interviewer.setFullName("Sarah Lim");
        interviewer.setEmail("sarah@example.com");
        when(users.findById(11L)).thenReturn(Optional.of(interviewer));
        setStage("INTERVIEW_SCHEDULED");

        when(booking.durationFor("TECHNICAL")).thenReturn(60);
        when(booking.availableStarts(any(), any(), any())).thenReturn(List.of(NOW.plusDays(1)));

        service = new InterviewReminderService(schedules, scorecards, reminders, candidates, requisitions,
                stages, users, activity, booking, email);
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(service, "firstChaseHours", 48L);
        ReflectionTestUtils.setField(service, "secondChaseHours", 120L);
        ReflectionTestUtils.setField(service, "stalledHours", 168L);
        ReflectionTestUtils.setField(service, "reminderHoursBefore", 24L);
        ReflectionTestUtils.setField(service, "scorecardNudgeHours", 24L);
        ReflectionTestUtils.setField(service, "scorecardEscalateHours", 72L);
    }

    private void setStage(String stage) {
        PipelineStage ps = new PipelineStage();
        ps.setStage(stage);
        when(stages.findByCandidateIdAndRequisitionId(7L, 3L)).thenReturn(Optional.of(ps));
    }

    private InterviewSchedule interview(String status, LocalDateTime createdAt, LocalDateTime slot) {
        return InterviewSchedule.builder().id(1L).candidateId(7L).requisitionId(3L).interviewerId(11L)
                .interviewType("TECHNICAL").status(status).createdAt(createdAt).confirmedSlot(slot).build();
    }

    @Test
    @DisplayName("a candidate who has not booked is chased once, then not again on the next run")
    void chasesUnbookedCandidateOnce() {
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of(interview("PROPOSED", NOW.minusDays(3), null)));
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of());

        service.runAt(NOW);
        service.runAt(NOW.plusMinutes(15));

        verify(email, times(1)).sendHtml(eq("chen@example.com"), contains("Reminder: choose your interview time"), anyString());
    }

    @Test
    @DisplayName("a candidate booked recently is left alone")
    void doesNotChaseTooEarly() {
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of(interview("PROPOSED", NOW.minusHours(5), null)));
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of());

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("with no free times published, HR is told instead of the candidate being chased")
    void alertsHrWhenNoSlots() {
        when(booking.availableStarts(any(), any(), any())).thenReturn(List.of());
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of(interview("PROPOSED", NOW.minusDays(3), null)));
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of());

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
        verify(email, times(1)).notifyHr(contains("No open interview times"), anyString(), anyString());
    }

    @Test
    @DisplayName("after a week without a booking HR is alerted instead of a third chase")
    void escalatesStalledBooking() {
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of(interview("PROPOSED", NOW.minusDays(8), null)));
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of());

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
        verify(email, times(1)).notifyHr(contains("Interview not booked"), anyString(), anyString());
    }

    @Test
    @DisplayName("both sides are reminded the day before, and a reschedule earns fresh reminders")
    void remindsBothSides() {
        InterviewSchedule iv = interview("CONFIRMED", NOW.minusDays(2), NOW.plusHours(5));
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of());
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of(iv));

        service.runAt(NOW);
        service.runAt(NOW.plusMinutes(15));
        verify(email, times(1)).sendHtml(eq("chen@example.com"), contains("Reminder: your interview"), anyString());
        verify(email, times(1)).sendHtml(eq("sarah@example.com"), contains("Reminder: interview"), anyString());

        iv.setConfirmedSlot(NOW.plusHours(10));
        service.runAt(NOW.plusMinutes(30));
        verify(email, times(2)).sendHtml(eq("chen@example.com"), contains("Reminder: your interview"), anyString());
    }

    @Test
    @DisplayName("an interview more than a day away is not reminded yet")
    void noReminderTooEarly() {
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of());
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of(interview("CONFIRMED", NOW.minusDays(1), NOW.plusDays(3))));

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a missing scorecard is nudged after a day, then escalated to HR after three")
    void nudgesThenEscalatesScorecard() {
        InterviewSchedule iv = interview("CONFIRMED", NOW.minusDays(9), NOW.minusDays(2));
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of());
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of(iv));
        when(scorecards.findByInterviewId(1L)).thenReturn(Optional.empty());

        service.runAt(NOW);
        verify(email, times(1)).sendHtml(eq("sarah@example.com"), contains("Scorecard needed"), anyString());
        verify(email, never()).notifyHr(anyString(), anyString(), anyString());

        service.runAt(NOW.plusDays(2));
        verify(email, times(1)).notifyHr(contains("Scorecard overdue"), anyString(), anyString());
        verify(email, times(1)).sendHtml(eq("sarah@example.com"), contains("Scorecard needed"), anyString());
    }

    @Test
    @DisplayName("nothing is sent once the scorecard is in")
    void noNudgeWhenScorecardSubmitted() {
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of());
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of(interview("CONFIRMED", NOW.minusDays(9), NOW.minusDays(2))));
        when(scorecards.findByInterviewId(1L)).thenReturn(Optional.of(new InterviewScorecard()));

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
        verify(email, never()).notifyHr(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a rejected candidate gets no chasers or reminders")
    void skipsClosedCandidates() {
        setStage("REJECTED");
        when(schedules.findByStatus("PROPOSED")).thenReturn(List.of(interview("PROPOSED", NOW.minusDays(3), null)));
        when(schedules.findByStatus("CONFIRMED")).thenReturn(List.of(interview("CONFIRMED", NOW.minusDays(2), NOW.plusHours(5))));

        service.runAt(NOW);

        verify(email, never()).sendHtml(anyString(), anyString(), anyString());
        verify(email, never()).notifyHr(anyString(), anyString(), anyString());
    }
}
