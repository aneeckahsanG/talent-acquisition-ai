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
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What the AI agent may do to a candidate's stage on its own, and who the app records as having done it. */
class RecruitingToolsStageMoveTest {

    private OrchestratorService orchestrator;
    private HumanApprovalGate gate;
    private RecruitingTools tools;

    @BeforeEach
    void setUp() {
        orchestrator = mock(OrchestratorService.class);
        gate = new HumanApprovalGate();
        tools = new RecruitingTools(mock(ScreeningAgentService.class), mock(SourcingAgentService.class),
                mock(AdminAgentService.class), orchestrator, gate, mock(CandidateRepository.class),
                mock(JobRequisitionRepository.class), mock(MockJobRoleRepository.class),
                mock(CandidateDirectoryService.class), mock(RequisitionService.class),
                mock(ReferralAgentService.class), mock(CvInboxProcessingService.class),
                mock(AvailabilityWindowRepository.class), new ObjectMapper(), NoOpAgentTrace.create());
    }

    @Test
    @DisplayName("shortlisting happens straight away and is recorded as the AI agent, not a recruiter")
    void shortlistIsRecordedAsTheAgent() {
        tools.moveCandidateStage(1L, 9L, "SHORTLISTED", null);

        verify(orchestrator).moveStage(1L, 9L, "SHORTLISTED", "ORCHESTRATOR");
        assertThat(gate.listPending()).isEmpty();
    }

    @Test
    @DisplayName("a rejection waits for a recruiter and nothing is emailed or moved until then")
    void rejectionWaitsForApproval() {
        String result = tools.moveCandidateStage(1L, 9L, "REJECTED", null);

        assertThat(result).contains("PENDING_APPROVAL");
        assertThat(gate.listPending()).hasSize(1);
        verify(orchestrator, never()).moveStage(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("once a recruiter approves, the rejection goes through as the AI agent's move")
    void approvedRejectionGoesThrough() {
        tools.moveCandidateStage(1L, 9L, "REJECTED", null);
        gate.approve(gate.listPending().get(0).approvalId());

        tools.moveCandidateStage(1L, 9L, "REJECTED", null);

        verify(orchestrator).moveStage(1L, 9L, "REJECTED", "ORCHESTRATOR");
    }

    @Test
    @DisplayName("offers and hires still need approval")
    void offerAndHireStillNeedApproval() {
        assertThat(tools.moveCandidateStage(1L, 9L, "OFFER", null)).contains("PENDING_APPROVAL");
        assertThat(tools.moveCandidateStage(1L, 9L, "HIRED", null)).contains("PENDING_APPROVAL");
        verify(orchestrator, never()).moveStage(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("moving back to Screened needs no approval")
    void screenedNeedsNoApproval() {
        tools.moveCandidateStage(1L, 9L, "SCREENED", null);

        verify(orchestrator).moveStage(1L, 9L, "SCREENED", "ORCHESTRATOR");
    }
}
