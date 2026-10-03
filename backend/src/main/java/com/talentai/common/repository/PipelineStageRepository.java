package com.talentai.common.repository;

import com.talentai.common.entity.PipelineStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, Long> {
    @Query("SELECT COUNT(DISTINCT p.candidateId) FROM PipelineStage p WHERE p.stage NOT IN ('REJECTED', 'HIRED', 'NO_SHOW')")
    long countDistinctActiveCandidates();

    @Query("SELECT COUNT(p) FROM PipelineStage p WHERE p.enteredAt < :cutoff AND p.stage NOT IN ('REJECTED', 'HIRED', 'NO_SHOW')")
    long countStalledCandidates(@Param("cutoff") LocalDateTime cutoff);
    List<PipelineStage> findByRequisitionId(Long requisitionId);
    List<PipelineStage> findByCandidateId(Long candidateId);
    Optional<PipelineStage> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
    List<PipelineStage> findByStage(String stage);
}
