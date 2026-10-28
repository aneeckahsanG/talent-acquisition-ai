package com.talentai.referral;

import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.PromptLoader;
import com.talentai.referral.dto.ReferralDtos.SubmitReferralRequest;
import com.talentai.referral.entity.Referral;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.referral.service.ReferralAgentService;
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

    private ReferralAgentService service;

    @BeforeEach
    void setUp() {
        service = new ReferralAgentService(mock(ChatClient.class), mock(PromptLoader.class), candidates, requisitions,
                referrals, mock(PipelineStageRepository.class), mock(AgentActivityLogRepository.class),
                mock(AppUserRepository.class), mock(ScreeningAgentService.class));
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
}
