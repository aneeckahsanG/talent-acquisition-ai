package com.talentai.common.repository;

import com.talentai.common.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByStatusOrderByCreatedAtDesc(String status);
    List<NotificationLog> findByRequisitionIdAndStatusOrderByCreatedAtDesc(Long requisitionId, String status);
}
