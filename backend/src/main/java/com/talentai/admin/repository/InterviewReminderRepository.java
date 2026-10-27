package com.talentai.admin.repository;

import com.talentai.admin.entity.InterviewReminder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewReminderRepository extends JpaRepository<InterviewReminder, Long> {
    boolean existsByInterviewIdAndKindAndRef(Long interviewId, String kind, String ref);
}
