package com.talentai.directory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateAccessLogRepository extends JpaRepository<CandidateAccessLog, Long> {
    List<CandidateAccessLog> findTop10ByCandidateIdAndActionOrderByCreatedAtDesc(Long candidateId, String action);
}
