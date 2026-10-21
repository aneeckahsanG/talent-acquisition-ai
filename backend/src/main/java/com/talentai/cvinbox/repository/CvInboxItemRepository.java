package com.talentai.cvinbox.repository;

import com.talentai.cvinbox.entity.CvInboxItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CvInboxItemRepository extends JpaRepository<CvInboxItem, Long> {
    List<CvInboxItem> findByDecisionAndArchivedFalseOrderByProcessedAtDesc(String decision);
}
