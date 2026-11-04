package com.talentai.common.repository;

import com.talentai.common.entity.PipelineStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineStageHistoryRepository extends JpaRepository<PipelineStageHistory, Long> {

    List<PipelineStageHistory> findByCandidateIdOrderByOccurredAtAscIdAsc(Long candidateId);
}
