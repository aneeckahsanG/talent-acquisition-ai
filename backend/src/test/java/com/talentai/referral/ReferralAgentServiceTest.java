package com.talentai.referral;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.PromptLoader;
import com.talentai.referral.dto.ReferralDtos.ClaudeReferralRoleResponse;
import com.talentai.referral.dto.ReferralDtos.SubmitReferralRequest;
import com.talentai.referral.entity.Referral;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.referral.service.ReferralAgentService;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** A referred candidate whose email several records already share must not fail the referral. */
class ReferralAgentServiceTest {

    private static final String SHARED_EMAIL = "shared@example.com";

    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final ReferralRepository referrals = mock(ReferralRepository.class);
    private final JobRequisitionRepository requisitions = mock(JobRequisitionRepository.class);
    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final PipelineStageRepository stages = mock(PipelineStageRepository.class);
    private final ScreeningAgentService screening = mock(ScreeningAgentService.class);
    private final PromptLoader prompts = mock(PromptLoader.class);

    private ReferralAgentService service;

    @BeforeEach
    void setUp() {
        service = new ReferralAgentService(chat, prompts, candidates, requisitions,
                referrals, stages, mock(AgentActivityLogRepository.class),
                mock(AppUserRepository.class), screening, mock(ScreeningResultRepository.class));
        when(prompts.load("referral-agent")).thenReturn("system prompt");
        when(candidates.save(any(Candidate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(referrals.save(any(Referral.class))).thenAnswer(inv -> inv.getArgument(0));
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of()); // no open roles, so no AI matching here
    }

    private SubmitReferralRequest request() {
        SubmitReferralRequest r = new SubmitReferralRequest();
        r.setCandidateName("Aisha Rahman");
        r.setCandidateEmail(SHARED_EMAIL);
        r.setResumeText("Java developer");
        r.setReferrerName("Ben Tan");
        return r;
    }

    @Test
    void demoModeCreatesASeparateCandidateInsteadOfLookingUpTheSharedEmail() {
        ReflectionTestUtils.setField(service, "demoMode", true);

        service.submitPublicReferral(request());

        verify(candidates, never()).findFirstByEmailOrderByIdAsc(anyString());
        ArgumentCaptor<Candidate> saved = ArgumentCaptor.forClass(Candidate.class);
        verify(candidates).save(saved.capture());
        assertNull(saved.getValue().getId());
        assertEquals("REFERRAL", saved.getValue().getSourceChannel());
    }

    @Test
    void outsideDemoModeTheEarliestRecordWithThatEmailIsReused() {
        ReflectionTestUtils.setField(service, "demoMode", false);
        Candidate earliest = Candidate.builder().id(7L).fullName("Aisha Rahman").email(SHARED_EMAIL).build();
        when(candidates.findFirstByEmailOrderByIdAsc(SHARED_EMAIL)).thenReturn(Optional.of(earliest));

        service.submitPublicReferral(request());

        ArgumentCaptor<Candidate> saved = ArgumentCaptor.forClass(Candidate.class);
        verify(candidates).save(saved.capture());
        assertEquals(7L, saved.getValue().getId(), "reused, not duplicated");
        assertEquals("Java developer", saved.getValue().getResumeText(), "resume refreshed from the referral");
    }

    @Test
    void aBlankEmailNeverTriggersALookup() {
        ReflectionTestUtils.setField(service, "demoMode", false);
        SubmitReferralRequest r = request();
        r.setCandidateEmail(" ");

        service.submitPublicReferral(r);

        verify(candidates, never()).findFirstByEmailOrderByIdAsc(anyString());
    }

    // ---- routing: the referrer's role, or Claude's pick; screening is then the only score ----

    private final JobRequisition analyst = JobRequisition.builder().id(3L).title("Data Analyst").status("OPEN").build();
    private final JobRequisition engineer = JobRequisition.builder().id(4L).title("Backend Engineer").status("OPEN").build();

    private void claudeReplies(ClaudeReferralRoleResponse reply) {
        when(chat.prompt().system(anyString()).user(anyString()).call().entity(ClaudeReferralRoleResponse.class)).thenReturn(reply);
    }

    private void newCandidateGetsId() {
        when(candidates.save(any(Candidate.class))).thenAnswer(inv -> { Candidate c = inv.getArgument(0); c.setId(9L); return c; });
    }

    @Test
    void aRoleTheReferrerChoseIsUsedWithoutAskingClaudeAndIsThenScreened() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of(analyst, engineer));
        newCandidateGetsId();
        when(stages.findByCandidateIdAndRequisitionId(9L, 3L)).thenReturn(Optional.empty());
        when(requisitions.findById(3L)).thenReturn(Optional.of(analyst));
        SubmitReferralRequest r = request();
        r.setRequisitionId(3L);

        service.submitPublicReferral(r);

        verify(chat, never()).prompt();
        ArgumentCaptor<Referral> saved = ArgumentCaptor.forClass(Referral.class);
        verify(referrals, atLeastOnce()).save(saved.capture());
        Referral last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(3L, last.getMatchedRequisitionId());
        assertNull(last.getMatchScore(), "no separate referral score");
        ArgumentCaptor<PipelineStage> stage = ArgumentCaptor.forClass(PipelineStage.class);
        verify(stages).save(stage.capture());
        assertEquals("REFERRAL_MATCHED", stage.getValue().getStage());
        assertEquals("Referred for this role by Ben Tan", stage.getValue().getNotes());
        verify(screening).runScreeningInternal(any(Candidate.class), eq(analyst));
    }

    @Test
    void withNoRoleChosenClaudePicksOneOpenRoleAndThatRoleIsScreened() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of(analyst, engineer));
        newCandidateGetsId();
        when(stages.findByCandidateIdAndRequisitionId(9L, 4L)).thenReturn(Optional.empty());
        when(requisitions.findById(4L)).thenReturn(Optional.of(engineer));
        claudeReplies(new ClaudeReferralRoleResponse(4L, "Strong Java background."));

        service.submitPublicReferral(request());

        ArgumentCaptor<PipelineStage> stage = ArgumentCaptor.forClass(PipelineStage.class);
        verify(stages).save(stage.capture());
        assertEquals(4L, stage.getValue().getRequisitionId());
        assertEquals("Routed to this role by the Referral Agent. Strong Java background.", stage.getValue().getNotes());
        verify(screening).runScreeningInternal(any(Candidate.class), eq(engineer));
    }

    @Test
    void ifClaudeNamesNoRoleOrOneThatIsNotOpenNothingIsMatchedOrScreened() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of(analyst));
        newCandidateGetsId();

        claudeReplies(new ClaudeReferralRoleResponse(null, "Nothing fits."));
        service.submitPublicReferral(request());
        claudeReplies(new ClaudeReferralRoleResponse(99L, "Invented role."));
        service.submitPublicReferral(request());

        verify(stages, never()).save(any(PipelineStage.class));
        verify(screening, never()).runScreeningInternal(any(), any());
    }

    @Test
    void aChosenRoleThatIsNoLongerOpenFallsBackToClaudesPick() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of(engineer));       // role 3 has closed
        newCandidateGetsId();
        when(stages.findByCandidateIdAndRequisitionId(9L, 4L)).thenReturn(Optional.empty());
        when(requisitions.findById(4L)).thenReturn(Optional.of(engineer));
        claudeReplies(new ClaudeReferralRoleResponse(4L, "Closest open role."));
        SubmitReferralRequest r = request();
        r.setRequisitionId(3L);

        service.submitPublicReferral(r);

        verify(screening).runScreeningInternal(any(Candidate.class), eq(engineer));
    }

    @Test
    void ifClaudeFailsTheReferralIsKeptForARecruiterToRoute() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of(analyst));
        newCandidateGetsId();
        when(chat.prompt().system(anyString()).user(anyString()).call().entity(ClaudeReferralRoleResponse.class))
                .thenThrow(new IllegalStateException("AI budget reached"));

        service.submitPublicReferral(request());

        verify(referrals, atLeastOnce()).save(any(Referral.class));
        verify(screening, never()).runScreeningInternal(any(), any());
    }
}
