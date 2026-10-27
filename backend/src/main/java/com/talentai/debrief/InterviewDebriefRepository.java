package com.talentai.debrief;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterviewDebriefRepository extends JpaRepository<InterviewDebrief, Long> {
    Optional<InterviewDebrief> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
}
