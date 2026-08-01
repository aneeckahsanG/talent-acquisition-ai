package com.talentai.admin.repository;

import com.talentai.admin.entity.InterviewSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InterviewScheduleRepository extends JpaRepository<InterviewSchedule, Long> {
    List<InterviewSchedule> findByRequisitionId(Long requisitionId);
    List<InterviewSchedule> findByCandidateId(Long candidateId);
    List<InterviewSchedule> findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(Long candidateId, Long requisitionId);
    List<InterviewSchedule> findByStatus(String status);

    @Query("SELECT COUNT(i) FROM InterviewSchedule i WHERE i.status IN ('PROPOSED', 'CONFIRMED')")
    long countActiveInterviews();
}
