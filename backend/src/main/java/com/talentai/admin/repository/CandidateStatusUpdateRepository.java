package com.talentai.admin.repository;

import com.talentai.admin.entity.CandidateStatusUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateStatusUpdateRepository extends JpaRepository<CandidateStatusUpdate, Long> {
    List<CandidateStatusUpdate> findByCandidateIdOrderBySentAtDesc(Long candidateId);
}
