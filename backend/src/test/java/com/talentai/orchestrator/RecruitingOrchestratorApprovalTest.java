package com.talentai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.service.PromptLoader;
import com.talentai.orchestrator.agent.AgentRun;
import com.talentai.orchestrator.agent.AgentRunRepository;
import com.talentai.orchestrator.agent.AgentStepRepository;
import com.talentai.orchestrator.agent.RecruitingOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Approving or declining a paused run: the recruiter's click has to reach the gate the tools check. */
class RecruitingOrchestratorApprovalTest {

    private static final String SAVED = """
            {"approvals":[{"actionKey":"sendOutreach:7","tool":"sendOutreach",
              "description":"Send an outreach email for match 7","payload":"Hi Priya, the reviewed text"}]}""";

    private HumanApprovalGate gate;
    private AgentRunRepository runs;
    private RecruitingOrchestratorService service;

    @BeforeEach
    void setUp() {
        gate = new HumanApprovalGate();
        runs = mock(AgentRunRepository.class);
        service = new RecruitingOrchestratorService(
                mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS), mock(RecruitingTools.class), gate,
                mock(PromptLoader.class), runs, mock(AgentStepRepository.class), new ObjectMapper());
    }

    private AgentRun pausedRun(String pendingInput) {
        AgentRun run = AgentRun.builder().id(5L).goal("Handle it").status("PAUSED_FOR_APPROVAL")
                .pendingTool("sendOutreach").pendingInput(pendingInput).build();
        when(runs.findById(5L)).thenReturn(Optional.of(run));
        return run;
    }

    @Test
    @DisplayName("approving a run approves what the tools are waiting on, so the resumed run can act")
    void approveGrantsTheGateEntries() {
        pausedRun(SAVED);
        gate.requestApproval("sendOutreach:7", "Send an outreach email for match 7", "sendOutreach", "Hi Priya, the reviewed text", 5L);

        service.approve(5L);

        assertThat(gate.isApproved("sendOutreach:7")).isTrue();
        assertThat(gate.approvedPayload("sendOutreach:7")).contains("Hi Priya, the reviewed text");
    }

    @Test
    @DisplayName("approving still works after a restart, from what the run saved")
    void approveRestoresFromTheSavedRun() {
        AgentRun run = pausedRun(SAVED);

        service.approve(5L);

        assertThat(gate.isApproved("sendOutreach:7")).isTrue();
        assertThat(gate.approvedPayload("sendOutreach:7")).contains("Hi Priya, the reviewed text");
        assertThat(run.getPendingTool()).isNull();
        assertThat(run.getPendingInput()).isNull();
    }

    @Test
    @DisplayName("approving a run with nothing waiting says so instead of pausing again")
    void approveWithNothingWaitingIsRefused() {
        pausedRun(null);

        assertThatThrownBy(() -> service.approve(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nothing");
    }

    @Test
    @DisplayName("only a paused run can be approved")
    void approveRequiresAPausedRun() {
        AgentRun run = pausedRun(SAVED);
        run.setStatus("COMPLETED");

        assertThatThrownBy(() -> service.approve(5L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("declining a run cancels what it was waiting on, so a later run cannot reuse it")
    void declineClearsTheGate() {
        AgentRun run = pausedRun(SAVED);
        gate.requestApproval("sendOutreach:7", "Send", "sendOutreach", "Hi", 5L);

        service.reject(5L, "Not this candidate");

        assertThat(gate.pendingForRun(5L)).isEmpty();
        assertThat(gate.isApproved("sendOutreach:7")).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getPendingInput()).isNull();
    }
}
