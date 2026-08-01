package com.talentai.screening.repository;

import com.talentai.screening.entity.ScreeningResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, Long> {
    List<ScreeningResult> findByRequisitionId(Long requisitionId);
    List<ScreeningResult> findByCandidateId(Long candidateId);
    Optional<ScreeningResult> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
    List<ScreeningResult> findByIsEdgeCaseTrue();
    List<ScreeningResult> findByRequisitionIdAndIsEdgeCaseTrue(Long requisitionId);

    @Query("SELECT COUNT(s) FROM ScreeningResult s WHERE s.createdAt >= :since")
    long countScreenedSince(LocalDateTime since);

    @Query("SELECT COUNT(s) FROM ScreeningResult s WHERE s.isEdgeCase = true AND s.reviewerDecision IS NULL")
    long countUnresolvedEdgeCases();
}
