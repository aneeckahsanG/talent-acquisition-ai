package com.talentai.admin.repository;

import com.talentai.admin.entity.InterviewScorecard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterviewScorecardRepository extends JpaRepository<InterviewScorecard, Long> {
    Optional<InterviewScorecard> findByInterviewId(Long interviewId);
}
