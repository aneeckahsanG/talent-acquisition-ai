package com.talentai.sourcing.repository;

import com.talentai.sourcing.entity.ResumeSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResumeSourceRepository extends JpaRepository<ResumeSource, Long> {
    List<ResumeSource> findByActiveTrue();
}
