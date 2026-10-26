package com.talentai.aiusage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {
    List<AiUsageLog> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime after);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(l.estimatedCost), 0) from AiUsageLog l where l.createdAt >= :since")
    java.math.BigDecimal sumCostSince(@org.springframework.data.repository.query.Param("since") LocalDateTime since);
}
