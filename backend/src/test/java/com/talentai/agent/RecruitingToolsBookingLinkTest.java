package com.talentai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScheduleInterviewRequest;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.service.OrchestratorService.BookingLinkCheck;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The agent may only send a booking link after a recruiter has approved it, and only when it makes sense. */
class RecruitingToolsBookingLinkTest {

    private static final ToolContext RUN_8 = new ToolContext(Map.<String, Object>of(RecruitingTools.RUN_ID_KEY, 8L));

    private OrchestratorService orchestrator;
    private HumanApprovalGate gate;
    private RecruitingTools tools;

    @BeforeEach
    void setUp() {
        orchestrator = mock(OrchestratorService.class);
        gate = new HumanApprovalGate();
        tools = new RecruitingTools(mock(ScreeningAgentService.class), mock(SourcingAgentService.class),
                mock(AdminAgentService.class), orchestrator, gate, mock(CandidateRepository.class), new ObjectMapper());
        when(orchestrator.checkBookingLink(1L, 9L, "PHONE_SCREEN"))
                .thenReturn(new BookingLinkCheck(null, "Dear Priya, we would like to invite you"));
    }

    @Test
    @DisplayName("nothing is sent: the link is queued with the exact email, for the run that asked")
    void queuesForApprovalWithoutSending() {
        String result = tools.sendBookingLink(1L, 9L, "phone_screen", RUN_8);

        assertThat(result).contains("PENDING_APPROVAL");
        var pending = gate.pendingForRun(8L);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolName()).isEqualTo("sendBookingLink");
        assertThat(pending.get(0).payload()).contains("Dear Priya, we would like to invite you");
        verify(orchestrator, never()).scheduleInterview(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("once a recruiter approves, the link is sent and recorded as the AI agent, and the grant is used up")
    void approvedLinkIsSentAsTheAgent() {
        tools.sendBookingLink(1L, 9L, "PHONE_SCREEN", RUN_8);
        gate.approve(gate.listPending().get(0).approvalId());

        tools.sendBookingLink(1L, 9L, "PHONE_SCREEN", RUN_8);

        ArgumentCaptor<ScheduleInterviewRequest> sent = ArgumentCaptor.forClass(ScheduleInterviewRequest.class);
        verify(orchestrator).scheduleInterview(eq(1L), eq(9L), sent.capture(), eq("ORCHESTRATOR"));
        assertThat(sent.getValue().getInterviewType()).isEqualTo("PHONE_SCREEN");
        assertThat(sent.getValue().getInterviewerId()).isNull();
        assertThat(gate.isApproved("bookingLink:1:9:PHONE_SCREEN")).isFalse();
    }

    @Test
    @DisplayName("a blank interview type means a phone screen")
    void defaultsToPhoneScreen() {
        tools.sendBookingLink(1L, 9L, null, RUN_8);

        verify(orchestrator).checkBookingLink(1L, 9L, "PHONE_SCREEN");
        assertThat(gate.pendingForRun(8L)).hasSize(1);
    }

    @Test
    @DisplayName("when the check finds a problem the recruiter is not asked to approve anything")
    void aProblemIsReportedWithoutAskingForApproval() {
        when(orchestrator.checkBookingLink(1L, 9L, "TECHNICAL"))
                .thenReturn(new BookingLinkCheck("No free interview times are published", null));

        String result = tools.sendBookingLink(1L, 9L, "TECHNICAL", RUN_8);

        assertThat(result).startsWith("ERROR in sendBookingLink").contains("No free interview times");
        assertThat(gate.listPending()).isEmpty();
        verify(orchestrator, never()).scheduleInterview(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("the feedback tool hands the agent the verdict, the notes and the round that comes next")
    void feedbackToolReturnsTheScorecard() {
        when(orchestrator.interviewFeedback(1L, 9L)).thenReturn(new OrchestratorService.InterviewFeedback("INTERVIEW_SCHEDULED",
                java.util.List.of(new OrchestratorService.RoundFeedback(11L, "PHONE_SCREEN", "COMPLETED", null, "YES", 4.5,
                        Map.of("COMMUNICATION", 4), "Clear and keen", null, null)), "TECHNICAL"));

        String result = tools.getInterviewFeedback(1L, 9L);

        assertThat(result).contains("\"verdict\":\"YES\"").contains("Clear and keen").contains("\"nextRound\":\"TECHNICAL\"");
    }

    @Test
    @DisplayName("an approved next-round link is sent for that round, and not before approval")
    void nextRoundLinkNeedsApproval() {
        when(orchestrator.checkBookingLink(1L, 9L, "TECHNICAL")).thenReturn(new BookingLinkCheck(null, "Dear Priya, technical round"));

        assertThat(tools.sendBookingLink(1L, 9L, "TECHNICAL", RUN_8)).contains("PENDING_APPROVAL");
        verify(orchestrator, never()).scheduleInterview(anyLong(), anyLong(), any(), anyString());

        gate.approve(gate.listPending().get(0).approvalId());
        tools.sendBookingLink(1L, 9L, "TECHNICAL", RUN_8);

        ArgumentCaptor<ScheduleInterviewRequest> sent = ArgumentCaptor.forClass(ScheduleInterviewRequest.class);
        verify(orchestrator).scheduleInterview(eq(1L), eq(9L), sent.capture(), eq("ORCHESTRATOR"));
        assertThat(sent.getValue().getInterviewType()).isEqualTo("TECHNICAL");
    }

    @Test
    @DisplayName("an approval given for one interview type does not let another type through")
    void approvalIsPerInterviewType() {
        when(orchestrator.checkBookingLink(1L, 9L, "FINAL")).thenReturn(new BookingLinkCheck(null, "Final round"));
        tools.sendBookingLink(1L, 9L, "PHONE_SCREEN", RUN_8);
        gate.approve(gate.listPending().get(0).approvalId());

        String result = tools.sendBookingLink(1L, 9L, "FINAL", RUN_8);

        assertThat(result).contains("PENDING_APPROVAL");
        verify(orchestrator, never()).scheduleInterview(anyLong(), anyLong(), any(), anyString());
    }
}
