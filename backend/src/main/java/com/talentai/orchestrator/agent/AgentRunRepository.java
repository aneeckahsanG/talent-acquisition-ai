package com.talentai.orchestrator.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    List<AgentRun> findByRequisitionIdOrderByCreatedAtDesc(Long requisitionId);
    List<AgentRun> findByStatusOrderByCreatedAtDesc(String status);
    boolean existsByCandidateIdAndRequisitionIdAndStatusIn(Long candidateId, Long requisitionId, Collection<String> statuses);
}
