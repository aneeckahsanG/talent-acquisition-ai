package com.talentai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.directory.CandidateDirectoryService;
import com.talentai.common.service.RequisitionService;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.sourcing.repository.MockJobRoleRepository;
import com.talentai.referral.service.ReferralAgentService;
import com.talentai.cvinbox.service.CvInboxProcessingService;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.screening.dto.ScreeningDtos.ScreeningResultResponse;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The agent's screening tool works from the candidate it was given, never from a lookup by name or email. */
class RecruitingToolsScreenTest {

    private ScreeningAgentService screening;
    private RecruitingTools tools;

    @BeforeEach
    void setUp() {
        screening = mock(ScreeningAgentService.class);
        tools = new RecruitingTools(screening, mock(SourcingAgentService.class), mock(AdminAgentService.class),
                mock(OrchestratorService.class), new HumanApprovalGate(), mock(CandidateRepository.class),
                mock(JobRequisitionRepository.class), mock(MockJobRoleRepository.class),
                mock(CandidateDirectoryService.class), mock(RequisitionService.class),
                mock(ReferralAgentService.class), mock(CvInboxProcessingService.class),
                mock(AvailabilityWindowRepository.class), new ObjectMapper(), NoOpAgentTrace.create());
    }

    @Test
    @DisplayName("the tool asks for the result by candidate id, so no second candidate can be created")
    void usesTheCandidateId() {
        ScreeningResultResponse saved = new ScreeningResultResponse();
        saved.setRecommendation("ADVANCE");
        when(screening.screenExistingCandidate(12L, 3L)).thenReturn(saved);

        String result = tools.screenCandidate(12L, 3L);

        assertThat(result).contains("ADVANCE");
        verify(screening).screenExistingCandidate(12L, 3L);
        verify(screening, never()).screenCandidate(any());
    }

    @Test
    @DisplayName("a failure comes back to the agent as an ERROR it can react to")
    void failureIsReportedToTheAgent() {
        when(screening.screenExistingCandidate(12L, 3L)).thenThrow(new IllegalStateException("AI budget reached"));

        assertThat(tools.screenCandidate(12L, 3L)).startsWith("ERROR in screenCandidate").contains("AI budget reached");
    }
}
