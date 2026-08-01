package com.talentai.sourcing.repository;

import com.talentai.sourcing.entity.SourcingMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourcingMatchRepository extends JpaRepository<SourcingMatch, Long> {
    List<SourcingMatch> findByRequisitionIdOrderByMatchScoreDesc(Long requisitionId);
    Optional<SourcingMatch> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
    List<SourcingMatch> findByRequisitionIdAndIsProactiveFalseOrderByCreatedAtDesc(Long requisitionId);
    Optional<SourcingMatch> findByCandidateIdAndRequisitionIdAndIsProactiveFalse(Long candidateId, Long requisitionId);
}
