package com.talentai.admin.repository;

import com.talentai.admin.entity.OfferResponseToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OfferResponseTokenRepository extends JpaRepository<OfferResponseToken, Long> {
    Optional<OfferResponseToken> findByToken(String token);
    void deleteByOfferId(Long offerId);
}
