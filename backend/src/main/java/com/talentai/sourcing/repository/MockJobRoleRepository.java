package com.talentai.sourcing.repository;

import com.talentai.sourcing.entity.MockJobRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MockJobRoleRepository extends JpaRepository<MockJobRole, Long> {
    List<MockJobRole> findByActiveTrueOrderByCreatedAtDesc();
}
