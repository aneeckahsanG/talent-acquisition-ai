package com.talentai.common.repository;

import com.talentai.common.entity.JobRequisition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRequisitionRepository extends JpaRepository<JobRequisition, Long> {
    List<JobRequisition> findByStatus(String status);
}
