package com.talentai.common.repository;

import com.talentai.common.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByStatusOrderByCreatedAtDesc(String status);
    List<NotificationLog> findByCandidateIdOrderByCreatedAtDesc(Long candidateId);
    List<NotificationLog> findByRequisitionIdAndStatusOrderByCreatedAtDesc(Long requisitionId, String status);
    boolean existsByCandidateIdAndRequisitionIdAndNotificationTypeAndStatus(
            Long candidateId, Long requisitionId, String notificationType, String status);
    java.util.Optional<NotificationLog> findTopByCandidateIdAndRequisitionIdAndNotificationTypeOrderByCreatedAtDesc(
            Long candidateId, Long requisitionId, String notificationType);
}
