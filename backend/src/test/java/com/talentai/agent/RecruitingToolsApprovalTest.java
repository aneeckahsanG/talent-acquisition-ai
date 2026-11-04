package com.talentai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.directory.CandidateDirectoryService;
import com.talentai.common.service.RequisitionService;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.sourcing.repository.MockJobRoleRepository;
import com.talentai.referral.service.ReferralAgentService;
import com.talentai.cvinbox.service.CvInboxProcessingService;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.agent.AgentTrace;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The approval gate on the agent's candidate-facing tools: what the recruiter is shown, and what then goes out. */
class RecruitingToolsApprovalTest {

    private static final ToolContext RUN_5 = new ToolContext(Map.<String, Object>of(RecruitingTools.RUN_ID_KEY, 5L));

    private SourcingAgentService sourcing;
    private OrchestratorService orchestrator;
    private CandidateRepository candidates;
    private HumanApprovalGate gate;
    private RecruitingTools tools;

    @BeforeEach
    void setUp() {
        sourcing = mock(SourcingAgentService.class);
        orchestrator = mock(OrchestratorService.class);
        candidates = mock(CandidateRepository.class);
        gate = new HumanApprovalGate();
        tools = new RecruitingTools(mock(ScreeningAgentService.class), sourcing, mock(AdminAgentService.class),
                orchestrator, gate, candidates, mock(JobRequisitionRepository.class),
                mock(MockJobRoleRepository.class), mock(CandidateDirectoryService.class),
                mock(RequisitionService.class), mock(ReferralAgentService.class),
                mock(CvInboxProcessingService.class), mock(AvailabilityWindowRepository.class),
                new ObjectMapper(), mock(AgentTrace.class));
    }

    @Test
    @DisplayName("outreach waits for approval, keeping the exact email and the run that asked")
    void outreachQueuesTheEmailForThatRun() {
        String result = tools.sendOutreach(7L, "Hi Priya, ...", RUN_5);

        assertThat(result).contains("PENDING_APPROVAL");
        var pending = gate.pendingForRun(5L);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolName()).isEqualTo("sendOutreach");
        assertThat(pending.get(0).payload()).isEqualTo("Hi Priya, ...");
        verify(sourcing, never()).sendOutreach(anyLong(), anyString());
    }

    @Test
    @DisplayName("after approval the email the recruiter read is the one sent, even if the agent words it differently")
    void approvedEmailIsSentVerbatim() {
        tools.sendOutreach(7L, "Hi Priya, the version you reviewed", RUN_5);
        gate.approveRun(5L);

        String result = tools.sendOutreach(7L, "Hi Priya, a different rewrite", RUN_5);

        assertThat(result).contains("sent successfully");
        verify(sourcing).sendOutreach(7L, "Hi Priya, the version you reviewed");
        verify(sourcing, never()).sendOutreach(7L, "Hi Priya, a different rewrite");
    }

    @Test
    @DisplayName("one approval sends one email")
    void approvalCannotBeReused() {
        tools.sendOutreach(7L, "Hi Priya", RUN_5);
        gate.approveRun(5L);
        tools.sendOutreach(7L, "Hi Priya", RUN_5);

        String again = tools.sendOutreach(7L, "Hi Priya", RUN_5);

        assertThat(again).contains("PENDING_APPROVAL");
        verify(sourcing, times(1)).sendOutreach(anyLong(), anyString());
    }

    @Test
    @DisplayName("a rejection names the candidate so the recruiter knows who it is about")
    void rejectionDescribesTheCandidate() {
        when(candidates.findById(9L)).thenReturn(Optional.of(Candidate.builder().fullName("Priya Sharma").build()));

        tools.moveCandidateStage(1L, 9L, "REJECTED", RUN_5);

        var pending = gate.pendingForRun(5L);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).description()).contains("Priya Sharma").contains("REJECTED");
        assertThat(pending.get(0).toolName()).isEqualTo("moveCandidateStage");
    }

    @Test
    @DisplayName("an approved rejection goes through once")
    void approvedRejectionRunsOnce() {
        tools.moveCandidateStage(1L, 9L, "REJECTED", RUN_5);
        gate.approveRun(5L);

        tools.moveCandidateStage(1L, 9L, "REJECTED", RUN_5);
        String again = tools.moveCandidateStage(1L, 9L, "REJECTED", RUN_5);

        verify(orchestrator, times(1)).moveStage(1L, 9L, "REJECTED", "ORCHESTRATOR");
        assertThat(again).contains("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("called outside an orchestrator run there is no run to attach the request to")
    void noContextMeansNoRun() {
        tools.sendOutreach(7L, "Hi Priya", null);

        assertThat(gate.listPending()).hasSize(1);
        assertThat(gate.listPending().get(0).runId()).isNull();
    }
}
