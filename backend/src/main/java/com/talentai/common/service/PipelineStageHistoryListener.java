package com.talentai.common.service;

import com.talentai.common.entity.PipelineStage;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Records every change of a pipeline stage, whichever part of the app made it. The stage is remembered when the row
 * is loaded and compared when it is written, so a stage move is logged once with where it came from, who made it and
 * the note that went with it, while a note-only edit is not. It writes with plain JDBC because a JPA lifecycle
 * callback must not use the entity manager.
 */
@Component
@RequiredArgsConstructor
public class PipelineStageHistoryListener {

    private final JdbcTemplate jdbc;

    @PostLoad
    public void remember(PipelineStage stage) {
        stage.setLoadedStage(stage.getStage());
        stage.setLoadedNotes(stage.getNotes());
    }

    @PostPersist
    @PostUpdate
    public void record(PipelineStage stage) {
        if (Objects.equals(stage.getLoadedStage(), stage.getStage())) return;
        // A move that leaves the old note in place has no note of its own
        String note = Objects.equals(stage.getLoadedNotes(), stage.getNotes()) ? null : stage.getNotes();
        jdbc.update("""
                INSERT INTO pipeline_stage_history
                    (candidate_id, requisition_id, from_stage, to_stage, changed_by, notes, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                stage.getCandidateId(), stage.getRequisitionId(), stage.getLoadedStage(), stage.getStage(),
                stage.getUpdatedByAgent(), note, Timestamp.valueOf(LocalDateTime.now()));
        stage.setLoadedStage(stage.getStage());
        stage.setLoadedNotes(stage.getNotes());
    }
}
