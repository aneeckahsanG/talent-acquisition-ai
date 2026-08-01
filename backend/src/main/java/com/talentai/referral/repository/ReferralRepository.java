package com.talentai.referral.repository;

import com.talentai.referral.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReferralRepository extends JpaRepository<Referral, Long> {
    List<Referral> findByReferredByOrderBySubmittedAtDesc(Long referredBy);
    List<Referral> findAllByOrderBySubmittedAtDesc();
    List<Referral> findByStatus(String status);

    @Query("SELECT COUNT(r) FROM Referral r WHERE r.status IN ('SUBMITTED', 'MATCHED', 'SCREENING', 'ADVANCED')")
    long countInProgress();
}
