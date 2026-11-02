package com.talentai.screening;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.NotificationLogRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import com.talentai.common.service.PromptLoader;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.screening.dto.ScreeningDtos.ScreenByTextRequest;
import com.talentai.screening.dto.ScreeningDtos.ScreeningResultResponse;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Asking for a candidate's screening result must not screen them again or overwrite what was saved. */
class ScreeningReuseTest {

    private static final String CLAUDE_JSON = """
            {"overallScore":58,"skillsScore":60,"experienceScore":55,"cultureFitScore":60,
             "strengths":"Java","gaps":"AWS","rationale":"Borderline.","recommendation":"REVIEW","isEdgeCase":true}""";

    private ChatClient chat;
    private CandidateRepository candidates;
    private JobRequisitionRepository requisitions;
    private ScreeningResultRepository results;
    private ScreeningAgentService service;

    private final Candidate priya = Candidate.builder().id(12L).fullName("Priya Sharma").email("priya@example.com")
            .resumeText("Backend engineer, 6 years Java.").build();
    private final JobRequisition role = JobRequisition.builder().id(3L).title("AI/ML Engineer").build();

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        candidates = mock(CandidateRepository.class);
        requisitions = mock(JobRequisitionRepository.class);
        results = mock(ScreeningResultRepository.class);
        PromptLoader prompts = mock(PromptLoader.class);
        when(prompts.load(anyString())).thenReturn("system prompt");

        service = new ScreeningAgentService(chat, prompts, mock(PdfTextExtractor.class), candidates, requisitions, results,
                mock(PipelineStageRepository.class), mock(AgentActivityLogRepository.class), mock(EmailService.class),
                mock(NotificationLogRepository.class), mock(SourcingMatchRepository.class));

        when(candidates.findById(12L)).thenReturn(Optional.of(priya));
        when(requisitions.findById(3L)).thenReturn(Optional.of(role));
        when(results.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ScreeningResult saved(int score, String recommendation) {
        return ScreeningResult.builder().candidateId(12L).requisitionId(3L)
                .overallScore(BigDecimal.valueOf(score)).recommendation(recommendation).build();
    }

    private void claudeReturnsReview() {
        ChatResponse reply = new ChatResponse(List.of(new Generation(new AssistantMessage(CLAUDE_JSON))));
        when(chat.prompt().system(anyString()).user(anyString()).call().chatResponse()).thenReturn(reply);
    }

    @Test
    @DisplayName("a candidate who already has a result gets it back, with no Claude call and nothing overwritten")
    void existingResultIsReturnedAsIs() {
        when(results.findByCandidateIdAndRequisitionId(12L, 3L)).thenReturn(Optional.of(saved(91, "ADVANCE")));

        ScreeningResultResponse response = service.screenExistingCandidate(12L, 3L);

        assertThat(response.getRecommendation()).isEqualTo("ADVANCE");
        assertThat(response.getOverallScore()).isEqualByComparingTo("91");
        verifyNoInteractions(chat);
        verify(results, never()).save(any());
    }

    @Test
    @DisplayName("a candidate with no result yet is screened once and the result saved")
    void missingResultIsScreened() {
        when(results.findByCandidateIdAndRequisitionId(12L, 3L)).thenReturn(Optional.empty());
        claudeReturnsReview();

        ScreeningResultResponse response = service.screenExistingCandidate(12L, 3L);

        assertThat(response.getRecommendation()).isEqualTo("REVIEW");
        verify(results).save(any(ScreeningResult.class));
    }

    @Test
    @DisplayName("looking a candidate up by id never creates another candidate record")
    void doesNotCreateACandidate() {
        when(results.findByCandidateIdAndRequisitionId(12L, 3L)).thenReturn(Optional.of(saved(91, "ADVANCE")));

        service.screenExistingCandidate(12L, 3L);

        verify(candidates, never()).save(any());
    }

    @Test
    @DisplayName("an unknown candidate or role is reported, not screened")
    void unknownCandidateOrRole() {
        when(candidates.findById(99L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.screenExistingCandidate(99L, 3L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Candidate not found");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.screenExistingCandidate(12L, 77L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requisition not found");
        verifyNoInteractions(chat);
    }

    @Test
    @DisplayName("screening by text also returns the saved result instead of screening the same candidate again")
    void textScreeningReusesTheResult() {
        when(candidates.findFirstByEmailOrderByIdAsc("priya@example.com")).thenReturn(Optional.of(priya));
        when(candidates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(results.findByCandidateIdAndRequisitionId(12L, 3L)).thenReturn(Optional.of(saved(91, "ADVANCE")));

        ScreeningResultResponse response = service.screenCandidate(
                new ScreenByTextRequest("Priya Sharma", "priya@example.com", "Backend engineer, 6 years Java.", 3L));

        assertThat(response.getRecommendation()).isEqualTo("ADVANCE");
        verifyNoInteractions(chat);
        verify(results, never()).save(any());
    }
}
