package com.talentai.common.repository;

import com.talentai.common.entity.AgentActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentActivityLogRepository extends JpaRepository<AgentActivityLog, Long> {
    List<AgentActivityLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AgentActivityLog> findByAgentNameOrderByCreatedAtDesc(String agentName, Pageable pageable);
}
