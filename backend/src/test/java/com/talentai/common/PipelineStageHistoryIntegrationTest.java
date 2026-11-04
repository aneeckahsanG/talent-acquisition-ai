package com.talentai.common;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.entity.PipelineStageHistory;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageHistoryRepository;
import com.talentai.common.repository.PipelineStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every change of a pipeline stage is logged by the listener, whichever way the code saved it: a brand new row,
 * a row loaded in one call and saved in another (as the async agents do), or one changed inside a transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class PipelineStageHistoryIntegrationTest {

    @Autowired CandidateRepository candidates;
    @Autowired JobRequisitionRepository requisitions;
    @Autowired PipelineStageRepository stages;
    @Autowired PipelineStageHistoryRepository log;
    @Autowired TransactionTemplate tx;

    private Long candidateId;
    private Long requisitionId;

    @BeforeEach
    void setUp() {
        candidateId = candidates.save(Candidate.builder().fullName("History Test").sourceChannel("MANUAL").build()).getId();
        requisitionId = requisitions.findAll().stream().findFirst()
                .orElseGet(() -> requisitions.save(JobRequisition.builder().title("Role").description("Role").build())).getId();
    }

    private List<PipelineStageHistory> steps() {
        return log.findByCandidateIdOrderByOccurredAtAscIdAsc(candidateId);
    }

    private PipelineStage current() {
        return stages.findByCandidateIdAndRequisitionId(candidateId, requisitionId).orElseThrow();
    }

    @Test
    @DisplayName("a new stage, then a move saved from a separate load, are both logged in order")
    void newThenMoved() {
        stages.save(PipelineStage.builder().candidateId(candidateId).requisitionId(requisitionId)
                .stage("SCREENED").updatedByAgent("SCREENING").notes("Screening recommendation: ADVANCE (overall score: 88)").build());

        PipelineStage loaded = current();                       // detached, like an async agent holding it
        loaded.setStage("SHORTLISTED");
        loaded.setUpdatedByAgent("HUMAN");      // the screening note is left in place
        stages.save(loaded);

        List<PipelineStageHistory> steps = steps();
        assertEquals(2, steps.size());
        assertEquals(null, steps.get(0).getFromStage());
        assertEquals("SCREENED", steps.get(0).getToStage());
        assertEquals("SCREENING", steps.get(0).getChangedBy());
        assertEquals("Screening recommendation: ADVANCE (overall score: 88)", steps.get(0).getNotes());
        assertEquals("SCREENED", steps.get(1).getFromStage());
        assertEquals("SHORTLISTED", steps.get(1).getToStage());
        assertEquals("HUMAN", steps.get(1).getChangedBy());
        assertEquals(null, steps.get(1).getNotes());          // the old screening note is not this step's
    }

    @Test
    @DisplayName("editing only the note or the actor is not a stage change")
    void noteOnlyEditIsNotLogged() {
        stages.save(PipelineStage.builder().candidateId(candidateId).requisitionId(requisitionId).stage("SCREENED").build());

        PipelineStage loaded = current();
        loaded.setNotes("Re-screened, same result");
        stages.save(loaded);

        assertEquals(1, steps().size());
    }

    @Test
    @DisplayName("a move made inside a transaction is logged too, and a second move continues from the first")
    void movedInsideATransaction() {
        stages.save(PipelineStage.builder().candidateId(candidateId).requisitionId(requisitionId).stage("SHORTLISTED").build());

        tx.executeWithoutResult(s -> {
            PipelineStage managed = current();
            managed.setStage("INTERVIEW_SCHEDULED");
            managed.setUpdatedByAgent("CANDIDATE");
            stages.save(managed);
        });
        PipelineStage again = current();
        again.setStage("OFFER");
        stages.save(again);

        List<PipelineStageHistory> steps = steps();
        assertEquals(List.of("SHORTLISTED", "INTERVIEW_SCHEDULED", "OFFER"), steps.stream().map(PipelineStageHistory::getToStage).toList());
        assertEquals(List.of("SHORTLISTED", "INTERVIEW_SCHEDULED"),
                steps.stream().skip(1).map(PipelineStageHistory::getFromStage).toList());
        assertEquals("CANDIDATE", steps.get(1).getChangedBy());
    }
}
