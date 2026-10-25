package com.talentai.admin.repository;

import com.talentai.admin.entity.OfferLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OfferLetterRepository extends JpaRepository<OfferLetter, Long> {
    List<OfferLetter> findByOfferIdOrderBySentAtAsc(Long offerId);
}
