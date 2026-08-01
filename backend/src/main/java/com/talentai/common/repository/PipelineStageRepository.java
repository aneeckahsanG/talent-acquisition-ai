package com.talentai.common.repository;

import com.talentai.common.entity.PipelineStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, Long> {
    List<PipelineStage> findByRequisitionId(Long requisitionId);
    List<PipelineStage> findByCandidateId(Long candidateId);
    Optional<PipelineStage> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
    List<PipelineStage> findByStage(String stage);
}
