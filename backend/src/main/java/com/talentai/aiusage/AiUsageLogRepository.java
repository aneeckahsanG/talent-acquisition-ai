package com.talentai.aiusage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {
    List<AiUsageLog> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime after);
}
