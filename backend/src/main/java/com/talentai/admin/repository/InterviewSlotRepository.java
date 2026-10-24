package com.talentai.admin.repository;

import com.talentai.admin.entity.InterviewSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** InterviewSlot rows are bookings: one per scheduled interview, spanning start..end. */
public interface InterviewSlotRepository extends JpaRepository<InterviewSlot, Long> {

    @Query("select s from InterviewSlot s where s.startTime < :to and s.endTime > :from order by s.startTime")
    List<InterviewSlot> findOverlapping(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<InterviewSlot> findByInterviewId(Long interviewId);

    List<InterviewSlot> findByEndTimeAfter(LocalDateTime after);
}
