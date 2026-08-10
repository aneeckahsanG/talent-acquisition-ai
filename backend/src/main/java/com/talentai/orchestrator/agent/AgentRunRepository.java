package com.talentai.orchestrator.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    List<AgentRun> findByRequisitionIdOrderByCreatedAtDesc(Long requisitionId);
    List<AgentRun> findByStatusOrderByCreatedAtDesc(String status);
}
