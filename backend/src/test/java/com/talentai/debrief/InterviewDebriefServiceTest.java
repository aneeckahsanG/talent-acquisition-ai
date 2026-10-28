package com.talentai.debrief;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.aiusage.AiBudgetExceededException;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.PromptLoader;
import com.talentai.debrief.InterviewDebriefService.AiOutput;
import com.talentai.debrief.InterviewDebriefService.DebriefView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewDebriefServiceTest {

    private InterviewScheduleRepository schedules;
    private InterviewScorecardRepository scorecards;
    private ChatClient chat;
    private InterviewDebriefService service;
    private InterviewDebrief stored;
    private JobRequisition requisition;
    private final List<InterviewSchedule> interviews = new ArrayList<>();

    @BeforeEach
    void setUp() {
        schedules = mock(InterviewScheduleRepository.class);
        scorecards = mock(InterviewScorecardRepository.class);
        chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        InterviewDebriefRepository debriefs = mock(InterviewDebriefRepository.class);
        JobRequisitionRepository requisitions = mock(JobRequisitionRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        PromptLoader prompts = mock(PromptLoader.class);

        when(prompts.load("interview-debrief")).thenReturn("system prompt");
        requisition = new JobRequisition();
        requisition.setId(3L);
        requisition.setTitle("Backend Engineer");
        requisition.setRequiredSkills("Java, Spring");
        requisition.setRequiredInterviewRounds(2);
        when(requisitions.findById(3L)).thenReturn(Optional.of(requisition));
        AppUser sarah = new AppUser();
        sarah.setId(11L);
        sarah.setFullName("Sarah Lim");
        when(users.findById(11L)).thenReturn(Optional.of(sarah));
        when(schedules.findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(7L, 3L)).thenReturn(interviews);

        // in-memory stand-in for the saved debrief
        when(debriefs.findByCandidateIdAndRequisitionId(7L, 3L)).thenAnswer(i -> Optional.ofNullable(stored));
        when(debriefs.save(any(InterviewDebrief.class))).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });

        service = new InterviewDebriefService(debriefs, schedules, scorecards, requisitions, users,
                mock(AgentActivityLogRepository.class), chat, prompts, new ObjectMapper());
    }

    private void addRound(long id, String type, String status, String verdict, String notes) {
        InterviewSchedule iv = InterviewSchedule.builder().id(id).candidateId(7L).requisitionId(3L).interviewerId(11L)
                .interviewType(type).status(status).createdAt(LocalDateTime.of(2026, 10, 1, 9, 0).plusDays(id)).build();
        interviews.add(iv);
        if (verdict != null) {
            when(scorecards.findByInterviewId(id)).thenReturn(Optional.of(InterviewScorecard.builder().interviewId(id)
                    .ratings(Map.of("TECHNICAL_SKILLS", 4, "COMMUNICATION", 5)).verdict(verdict).notes(notes)
                    .submittedAt(LocalDateTime.of(2026, 10, 10, 12, 0)).build()));
        }
    }

    private void aiReturns(String recommendation) {
        when(chat.prompt().system(anyString()).user(anyString()).call().entity(AiOutput.class))
                .thenReturn(new AiOutput(recommendation, "high", "Solid across the board.", List.of("Strong skills"),
                        List.of(), List.of("Salary may be high"), "Send the offer."));
    }

    @Test
    @DisplayName("rules stop an offer recommendation when required rounds are unfinished")
    void guardBlocksOfferWithUnfinishedRounds() {
        var g = InterviewDebriefService.guard("ADVANCE_TO_OFFER", "HIGH", 1, 2, List.of("YES"));
        assertThat(g.recommendation()).isEqualTo("ADD_ROUND");
        assertThat(g.note()).contains("1 of 2");
    }

    @Test
    @DisplayName("rules stop an offer recommendation when an interviewer said No")
    void guardBlocksOfferWithANo() {
        var g = InterviewDebriefService.guard("ADVANCE_TO_OFFER", "HIGH", 2, 2, List.of("YES", "NO"));
        assertThat(g.recommendation()).isEqualTo("HOLD_FOR_DISCUSSION");
        assertThat(g.note()).isNotBlank();
    }

    @Test
    @DisplayName("rules send an all-positive panel's rejection recommendation to a person")
    void guardReviewsRejectionOfPositivePanel() {
        var g = InterviewDebriefService.guard("DO_NOT_PROCEED", "HIGH", 2, 2, List.of("YES", "STRONG_YES"));
        assertThat(g.recommendation()).isEqualTo("HOLD_FOR_DISCUSSION");
    }

    @Test
    @DisplayName("rules send a low-confidence rejection recommendation to a person, unless an interviewer said Strong no")
    void guardReviewsLowConfidenceRejection() {
        var g = InterviewDebriefService.guard("DO_NOT_PROCEED", "LOW", 1, 2, List.of("NO"));
        assertThat(g.recommendation()).isEqualTo("HOLD_FOR_DISCUSSION");
        assertThat(g.note()).contains("low confidence");
        assertThat(InterviewDebriefService.guard("DO_NOT_PROCEED", "LOW", 1, 2, List.of("STRONG_NO")).recommendation())
                .isEqualTo("DO_NOT_PROCEED");
        assertThat(InterviewDebriefService.guard("DO_NOT_PROCEED", "MEDIUM", 1, 2, List.of("NO")).recommendation())
                .isEqualTo("DO_NOT_PROCEED");
    }

    @Test
    @DisplayName("rules leave a consistent recommendation alone")
    void guardLeavesConsistentAdviceAlone() {
        assertThat(InterviewDebriefService.guard("ADVANCE_TO_OFFER", "HIGH", 2, 2, List.of("YES", "STRONG_YES")).note()).isNull();
        assertThat(InterviewDebriefService.guard("ADD_ROUND", "HIGH", 1, 2, List.of("NO")).recommendation()).isEqualTo("ADD_ROUND");
        assertThat(InterviewDebriefService.guard("DO_NOT_PROCEED", "HIGH", 2, 2, List.of("NO", "STRONG_NO")).note()).isNull();
    }

    @Test
    @DisplayName("generating saves the debrief and applies the rules")
    void generateSavesAndGuards() {
        addRound(1, "PHONE_SCREEN", "COMPLETED", "YES", "Clear and motivated.");
        addRound(2, "TECHNICAL", "CONFIRMED", null, null);
        aiReturns("ADVANCE_TO_OFFER");

        DebriefView v = service.generate(7L, 3L, "recruiter1");

        assertThat(v.recommendation()).isEqualTo("ADD_ROUND");
        assertThat(v.guardrailNote()).contains("1 of 2");
        assertThat(v.confidence()).isEqualTo("HIGH");
        assertThat(v.agreements()).containsExactly("Strong skills");
        assertThat(v.generatedBy()).isEqualTo("recruiter1");
        assertThat(v.stale()).isFalse();
        assertThat(stored).isNotNull();
    }

    @Test
    @DisplayName("the prompt carries the scorecards and the role but no candidate identity")
    void promptHasScorecardsOnly() {
        addRound(1, "TECHNICAL", "COMPLETED", "STRONG_YES", "Excellent system design answer.");
        aiReturns("ADD_ROUND");

        service.generate(7L, 3L, "recruiter1");

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(chat.prompt().system(anyString()), atLeastOnce()).user(user.capture());
        assertThat(user.getValue()).contains("Backend Engineer", "Round 1 (technical)", "Sarah Lim", "Strong yes",
                "Technical skills 4/5", "Excellent system design answer.");
    }

    @Test
    @DisplayName("the prompt says when some completed rounds have no scorecard")
    void promptFlagsMissingScorecards() {
        addRound(1, "PHONE_SCREEN", "COMPLETED", null, null);
        addRound(2, "TECHNICAL", "COMPLETED", "YES", "Solid.");
        aiReturns("ADD_ROUND");

        service.generate(7L, 3L, "recruiter1");

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(chat.prompt().system(anyString()), atLeastOnce()).user(user.capture());
        assertThat(user.getValue()).contains("Only 1 of the 2 completed rounds have a scorecard");
    }

    @Test
    @DisplayName("completed rounds without scorecards are counted but not debriefable")
    void completedWithoutScorecardsIsNotAvailable() {
        addRound(1, "PHONE_SCREEN", "COMPLETED", null, null);
        addRound(2, "TECHNICAL", "COMPLETED", null, null);

        DebriefView v = service.get(7L, 3L);

        assertThat(v.available()).isFalse();
        assertThat(v.completedRounds()).isEqualTo(2);
        assertThat(v.scorecardCount()).isZero();
    }

    @Test
    @DisplayName("there is nothing to debrief before a scorecard exists")
    void refusesWithoutScorecards() {
        addRound(1, "PHONE_SCREEN", "CONFIRMED", null, null);

        assertThatThrownBy(() -> service.generate(7L, 3L, "recruiter1")).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(7L, 3L).available()).isFalse();
        verify(chat, never()).prompt();
    }

    @Test
    @DisplayName("an answer that is not one of the four recommendations is rejected, not saved")
    void rejectsUnusableAnswer() {
        addRound(1, "TECHNICAL", "COMPLETED", "YES", null);
        aiReturns("HIRE_IMMEDIATELY");

        assertThatThrownBy(() -> service.generate(7L, 3L, "recruiter1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unusable");
        assertThat(stored).isNull();
    }

    @Test
    @DisplayName("the spending limit error is passed on as it is")
    void passesBudgetErrorThrough() {
        addRound(1, "TECHNICAL", "COMPLETED", "YES", null);
        when(chat.prompt().system(anyString()).user(anyString()).call().entity(AiOutput.class))
                .thenThrow(new AiBudgetExceededException("Daily AI limit reached."));

        assertThatThrownBy(() -> service.generate(7L, 3L, "recruiter1")).isInstanceOf(AiBudgetExceededException.class);
    }

    @Test
    @DisplayName("a new scorecard marks the saved debrief out of date")
    void newScorecardMakesDebriefStale() {
        addRound(1, "PHONE_SCREEN", "COMPLETED", "YES", null);
        aiReturns("ADD_ROUND");
        service.generate(7L, 3L, "recruiter1");
        assertThat(service.get(7L, 3L).stale()).isFalse();

        addRound(2, "TECHNICAL", "COMPLETED", "NO", "Struggled with concurrency.");

        DebriefView v = service.get(7L, 3L);
        assertThat(v.stale()).isTrue();
        assertThat(v.generated()).isTrue();
        assertThat(v.scorecardCount()).isEqualTo(2);
    }
}
