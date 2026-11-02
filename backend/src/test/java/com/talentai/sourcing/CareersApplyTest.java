package com.talentai.sourcing;

import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.sourcing.controller.PublicJobController;
import com.talentai.sourcing.dto.SourcingDtos.DirectApplyRequest;
import com.talentai.sourcing.dto.SourcingDtos.DirectApplyResponse;
import com.talentai.sourcing.service.ApplicationFollowUpService;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Applying on the careers page gets the same follow-up as a job-board application. */
class CareersApplyTest {

    private SourcingAgentService sourcing;
    private ApplicationFollowUpService followUp;
    private MockMvc mvc;

    private static final String BODY = """
            {"fullName":"Priya Sharma","email":"priya@example.com","resumeText":"Backend engineer, 6 years Java."}""";

    @BeforeEach
    void setUp() {
        sourcing = mock(SourcingAgentService.class);
        followUp = mock(ApplicationFollowUpService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicJobController(mock(JobRequisitionRepository.class), sourcing,
                followUp, mock(PdfTextExtractor.class), mock(ChatClient.class))).build();
    }

    @Test
    @DisplayName("a saved careers-page application is handed to the follow-up, labelled as coming from the careers page")
    void savedApplicationIsFollowedUp() throws Exception {
        when(sourcing.directApply(anyLong(), any(DirectApplyRequest.class)))
                .thenReturn(DirectApplyResponse.builder().matchId(7L).message("ok").build());

        mvc.perform(post("/api/public/jobs/3/apply").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        verify(followUp).afterApplication(7L, 3L, "Priya Sharma", "priya@example.com",
                "Backend engineer, 6 years Java.", "CAREERS_PAGE");
    }

    @Test
    @DisplayName("an application that is refused, such as a repeat, triggers nothing")
    void refusedApplicationIsNotFollowedUp() throws Exception {
        when(sourcing.directApply(anyLong(), any(DirectApplyRequest.class)))
                .thenThrow(new IllegalStateException("You have already applied for this role."));

        mvc.perform(post("/api/public/jobs/3/apply").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        verify(followUp, never()).afterApplication(anyLong(), anyLong(), any(), any(), any(), any());
    }
}
