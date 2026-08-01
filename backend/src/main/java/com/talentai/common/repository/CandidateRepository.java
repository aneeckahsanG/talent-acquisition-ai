package com.talentai.common.repository;

import com.talentai.common.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
    Optional<Candidate> findByEmail(String email);
    Optional<Candidate> findFirstByEmailOrderByIdAsc(String email);
    List<Candidate> findBySourceChannel(String sourceChannel);
}
