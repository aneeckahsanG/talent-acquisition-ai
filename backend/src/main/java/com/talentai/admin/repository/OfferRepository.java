package com.talentai.admin.repository;

import com.talentai.admin.entity.Offer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {
    Optional<Offer> findByCandidateIdAndRequisitionId(Long candidateId, Long requisitionId);
}
