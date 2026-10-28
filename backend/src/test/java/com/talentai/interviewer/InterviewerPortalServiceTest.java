package com.talentai.interviewer;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.interviewer.InterviewerDtos.InterviewDetail;
import com.talentai.interviewer.InterviewerDtos.InterviewSummary;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardRequest;
import com.talentai.orchestrator.service.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InterviewerPortalServiceTest {

    private static final Long ME = 7L;
    private static final Long SOMEONE_ELSE = 8L;

    private final InterviewScheduleRepository interviews = mock(InterviewScheduleRepository.class);
    private final InterviewScorecardRepository scorecards = mock(InterviewScorecardRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final JobRequisitionRepository requisitions = mock(JobRequisitionRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final OrchestratorService orchestrator = mock(OrchestratorService.class);

    private InterviewerPortalService portal;

    @BeforeEach
    void setUp() {
        portal = new InterviewerPortalService(interviews, scorecards, candidates, requisitions, users, orchestrator);
        when(users.findByUsername("priya")).thenReturn(Optional.of(AppUser.builder().id(ME).username("priya").build()));
        when(candidates.findById(anyLong())).thenReturn(Optional.of(Candidate.builder()
                .id(1L).fullName("Aisha Rahman").email("aisha@secret.com").phone("012-3456789")
                .headline("Java developer").skills("Java, SQL").resumeText("Ten years of Java.").build()));
        when(requisitions.findById(anyLong())).thenReturn(Optional.of(JobRequisition.builder()
                .id(2L).title("Senior Backend Engineer").department("Engineering").requiredSkills("Java").build()));
        when(scorecards.findByInterviewId(anyLong())).thenReturn(Optional.empty());
    }

    private InterviewSchedule interview(long id, Long interviewer, String status, LocalDateTime slot) {
        InterviewSchedule iv = InterviewSchedule.builder().id(id).candidateId(1L).requisitionId(2L)
                .interviewerId(interviewer).interviewType("TECHNICAL").status(status).confirmedSlot(slot).build();
        when(interviews.findById(id)).thenReturn(Optional.of(iv));
        return iv;
    }

    @Test
    void listsOnlyMyInterviewsSkipsCancelledOnesAndOrdersBySlot() {
        InterviewSchedule later = interview(1, ME, "CONFIRMED", LocalDateTime.now().plusDays(3));
        InterviewSchedule sooner = interview(2, ME, "CONFIRMED", LocalDateTime.now().plusDays(1));
        InterviewSchedule cancelled = interview(3, ME, "CANCELLED", LocalDateTime.now());
        InterviewSchedule unbooked = interview(4, ME, "PROPOSED", null);
        when(interviews.findByInterviewerId(ME)).thenReturn(List.of(later, unbooked, cancelled, sooner));
        when(interviews.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(1L, 2L)).thenReturn(List.of(later, sooner));

        List<InterviewSummary> mine = portal.myInterviews("priya");

        assertEquals(List.of(2L, 1L, 4L), mine.stream().map(InterviewSummary::getId).toList(),
                "soonest first, unbooked last, cancelled hidden");
        assertEquals("Aisha Rahman", mine.get(0).getCandidateName());
        assertEquals("Senior Backend Engineer", mine.get(0).getRoleTitle());
    }

    @Test
    void anInterviewAssignedToSomeoneElseIsRefused() {
        interview(10, SOMEONE_ELSE, "CONFIRMED", LocalDateTime.now());

        assertThrows(AccessDeniedException.class, () -> portal.myInterview(10L, "priya"));
    }

    @Test
    void anUnassignedOrMissingInterviewIsRefusedTheSameWay() {
        interview(11, null, "CONFIRMED", LocalDateTime.now());
        when(interviews.findById(404L)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> portal.myInterview(11L, "priya"));
        assertThrows(AccessDeniedException.class, () -> portal.myInterview(404L, "priya"));
    }

    @Test
    void theDetailCarriesWhatIsNeededToPrepareAndTheScorecardCriteria() {
        InterviewSchedule iv = interview(12, ME, "CONFIRMED", LocalDateTime.now().plusDays(1));
        when(interviews.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(1L, 2L)).thenReturn(List.of(iv));

        InterviewDetail d = portal.myInterview(12L, "priya");

        assertEquals("Ten years of Java.", d.getResumeText());
        assertEquals("Senior Backend Engineer", d.getRoleTitle());
        assertEquals(1, d.getRoundNumber());
        assertEquals(4, d.getCriteria().size(), "technical interviews are scored on four criteria");
        assertEquals("TECHNICAL_SKILLS", d.getCriteria().get(0).getKey());
        assertNull(d.getSubmittedScorecard());
    }

    @Test
    void theDetailNeverExposesContactDetailsScoresOrOtherInterviewersFeedback() {
        List<String> fields = Arrays.stream(InterviewDetail.class.getDeclaredFields()).map(Field::getName).toList();

        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("email")), fields.toString());
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("phone")), fields.toString());
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("screening")), fields.toString());
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("debrief")), fields.toString());
    }

    @Test
    void submittingForSomeoneElsesInterviewIsRefusedAndNothingIsSaved() {
        interview(13, SOMEONE_ELSE, "CONFIRMED", LocalDateTime.now());

        assertThrows(AccessDeniedException.class, () -> portal.submitScorecard(13L, new ScorecardRequest(), "priya"));
        verify(orchestrator, never()).submitScorecard(anyLong(), any(), anyString());
    }

    @Test
    void submittingForMyOwnInterviewUsesTheSharedScorecardRules() {
        interview(14, ME, "CONFIRMED", LocalDateTime.now());
        ScorecardRequest request = new ScorecardRequest();

        portal.submitScorecard(14L, request, "priya");

        verify(orchestrator).submitScorecard(14L, request, "priya");
    }
}
