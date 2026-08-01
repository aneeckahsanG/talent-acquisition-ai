package com.talentai.sourcing.repository;

import com.talentai.sourcing.entity.MockApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MockApplicationRepository extends JpaRepository<MockApplication, Long> {
    List<MockApplication> findAllByOrderByCreatedAtDesc();
    boolean existsByEmail(String email);
}
