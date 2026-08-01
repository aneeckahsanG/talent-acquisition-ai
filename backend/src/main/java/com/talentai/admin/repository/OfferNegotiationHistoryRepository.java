package com.talentai.admin.repository;

import com.talentai.admin.entity.OfferNegotiationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OfferNegotiationHistoryRepository extends JpaRepository<OfferNegotiationHistory, Long> {
    List<OfferNegotiationHistory> findByOfferIdOrderByRoundNumberAsc(Long offerId);
    int countByOfferId(Long offerId);
}
