package com.talentai.common;

import com.talentai.admin.repository.CandidateStatusUpdateRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.common.controller.CandidateController;
import com.talentai.common.controller.CandidateController.HistoryEvent;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.entity.PipelineStageHistory;
import com.talentai.common.repository.PipelineStageHistoryRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.referral.entity.Referral;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A referred candidate's history shows the referral, even after the pipeline row has moved on to SCREENED. */
class CandidateHistoryReferralTest {

    private PipelineStageRepository stages;
    private ReferralRepository referrals;
    private PipelineStageHistoryRepository stageLog;
    private JobRequisitionRepository requisitions;
    private CandidateController controller;

    @BeforeEach
    void setUp() {
        CandidateRepository candidates = mock(CandidateRepository.class);
        stages = mock(PipelineStageRepository.class);
        referrals = mock(ReferralRepository.class);
        stageLog = mock(PipelineStageHistoryRepository.class);
        requisitions = mock(JobRequisitionRepository.class);
        controller = new CandidateController(candidates, stages, mock(CandidateStatusUpdateRepository.class),
                requisitions, mock(InterviewScheduleRepository.class), mock(InterviewScorecardRepository.class),
                mock(ScreeningResultRepository.class), referrals, stageLog);

        when(candidates.findById(5L)).thenReturn(Optional.of(Candidate.builder().id(5L).fullName("Divya Menon").build()));
        when(requisitions.findById(3L)).thenReturn(Optional.of(JobRequisition.builder().id(3L).title("AI/ML Engineer").build()));
    }

    private List<HistoryEvent> history() {
        return controller.getHistory(5L).getBody().getEvents();
    }

    @Test
    @DisplayName("a referral the agent routed appears with who referred them and the role, but no score")
    void matchedReferralIsListed() {
        when(stages.findByCandidateId(5L)).thenReturn(List.of(PipelineStage.builder()
                .candidateId(5L).requisitionId(3L).stage("SCREENED").enteredAt(LocalDateTime.now()).build()));
        when(referrals.findByCandidateId(5L)).thenReturn(List.of(Referral.builder()
                .candidateId(5L).matchedRequisitionId(3L)
                .referrerName("Sam Lee").referrerPosition("Staff Engineer")
                .submittedAt(LocalDateTime.now().minusMinutes(5)).build()));

        HistoryEvent referral = history().stream().filter(e -> "REFERRAL".equals(e.getType())).findFirst().orElseThrow();

        assertEquals("AI/ML Engineer", referral.getRequisitionTitle());
        assertEquals("Referred by Sam Lee (Staff Engineer). Routed to AI/ML Engineer", referral.getNotes());
        assertEquals(2, history().size());
    }

    @Test
    @DisplayName("every stage change is listed, oldest first, with where it came from and who made it")
    void stageChangesInOrder() {
        LocalDateTime t = LocalDateTime.now().minusHours(3);
        when(stageLog.findByCandidateIdOrderByOccurredAtAscIdAsc(5L)).thenReturn(List.of(
                step(null, "REFERRAL_MATCHED", "REFERRAL", "Matched via referral", t),
                step("REFERRAL_MATCHED", "SCREENED", "SCREENING", "Screening recommendation: ADVANCE (overall score: 88)", t.plusHours(1)),
                step("SCREENED", "SHORTLISTED", "HUMAN", null, t.plusHours(2)),
                step("SHORTLISTED", "INTERVIEW_SCHEDULED", "CANDIDATE", null, t.plusHours(3))));
        when(stages.findByCandidateId(5L)).thenReturn(List.of(PipelineStage.builder()
                .candidateId(5L).requisitionId(3L).stage("INTERVIEW_SCHEDULED").enteredAt(t.plusHours(3)).build()));

        List<HistoryEvent> events = history();

        assertEquals(List.of("REFERRAL_MATCHED", "SCREENED", "SHORTLISTED", "INTERVIEW_SCHEDULED"),
                events.stream().map(HistoryEvent::getStage).toList());
        assertEquals("SCREENED", events.get(2).getFromStage());
        assertEquals("CANDIDATE", events.get(3).getAgent());
        assertEquals("Screening recommendation: ADVANCE (overall score: 88)", events.get(1).getNotes());
    }

    @Test
    @DisplayName("a role with no stage log still shows its current stage")
    void roleWithoutALogFallsBackToCurrentStage() {
        when(stageLog.findByCandidateIdOrderByOccurredAtAscIdAsc(5L)).thenReturn(List.of());
        when(stages.findByCandidateId(5L)).thenReturn(List.of(PipelineStage.builder()
                .candidateId(5L).requisitionId(3L).stage("SHORTLISTED").enteredAt(LocalDateTime.now()).build()));

        assertEquals(List.of("SHORTLISTED"), history().stream().map(HistoryEvent::getStage).toList());
    }

    private PipelineStageHistory step(String from, String to, String by, String notes, LocalDateTime at) {
        return PipelineStageHistory.builder().candidateId(5L).requisitionId(3L).fromStage(from).toStage(to)
                .changedBy(by).notes(notes).occurredAt(at).build();
    }

    @Test
    @DisplayName("a referral that matched no role is still listed, with no role")
    void unmatchedReferralIsListed() {
        when(stages.findByCandidateId(5L)).thenReturn(List.of());
        when(referrals.findByCandidateId(5L)).thenReturn(List.of(Referral.builder()
                .candidateId(5L).referrerName("Sam Lee").submittedAt(LocalDateTime.now()).build()));

        List<HistoryEvent> events = history();

        assertEquals(1, events.size());
        assertNull(events.get(0).getRequisitionTitle());
        assertNotNull(events.get(0).getNotes());
        assertEquals("Referred by Sam Lee. No open role matched yet", events.get(0).getNotes());
    }
}
