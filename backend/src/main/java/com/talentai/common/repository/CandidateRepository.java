package com.talentai.common.repository;

import com.talentai.common.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
    Optional<Candidate> findByEmail(String email);
    Optional<Candidate> findFirstByEmailOrderByIdAsc(String email);
    List<Candidate> findAllByEmailIgnoreCaseOrderByIdAsc(String email);
    List<Candidate> findBySourceChannel(String sourceChannel);

    /** Case-insensitive "contains" over the fields a recruiter would search by. The caller escapes %, _ and \\. */
    @org.springframework.data.jpa.repository.Query("""
            select c from Candidate c
            where lower(c.email) like lower(concat('%', :q, '%')) escape '\\'
               or lower(c.fullName) like lower(concat('%', :q, '%')) escape '\\'
               or lower(coalesce(c.phone, '')) like lower(concat('%', :q, '%')) escape '\\'
               or lower(coalesce(c.skills, '')) like lower(concat('%', :q, '%')) escape '\\'
               or lower(coalesce(c.headline, '')) like lower(concat('%', :q, '%')) escape '\\'
            """)
    List<Candidate> search(@org.springframework.data.repository.query.Param("q") String q);
}
