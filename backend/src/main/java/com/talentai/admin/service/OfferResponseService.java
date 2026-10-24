package com.talentai.admin.service;

import com.talentai.admin.entity.Offer;
import com.talentai.admin.entity.OfferNegotiationHistory;
import com.talentai.admin.entity.OfferResponseToken;
import com.talentai.admin.repository.OfferNegotiationHistoryRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.admin.repository.OfferResponseTokenRepository;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OfferResponseService {

    private static final int TOKEN_VALID_DAYS = 30;

    private final OfferResponseTokenRepository tokenRepository;
    private final OfferRepository offerRepository;
    private final OfferNegotiationHistoryRepository negotiationHistoryRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final EmailService emailService;

    @Value("${app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Generate a fresh single-use token for this offer (replaces any prior token). */
    @Transactional
    public String generateToken(Long offerId) {
        tokenRepository.deleteByOfferId(offerId);
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenRepository.save(OfferResponseToken.builder()
                .offerId(offerId)
                .token(token)
                .expiresAt(LocalDateTime.now().plusDays(TOKEN_VALID_DAYS))
                .build());
        return token;
    }

    public record TokenLookup(OfferResponseToken tokenEntity, Offer offer) {}

    /** Validate a token string, returning entity + offer or throwing a descriptive exception. */
    public TokenLookup validate(String token) {
        OfferResponseToken t = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unrecognised link."));
        if (t.getUsedAt() != null)
            throw new IllegalStateException("This link has already been used (action: " + t.getUsedAction() + ").");
        if (LocalDateTime.now().isAfter(t.getExpiresAt()))
            throw new IllegalStateException("This link has expired. Please contact the recruiting team.");
        Offer offer = offerRepository.findById(t.getOfferId())
                .orElseThrow(() -> new IllegalStateException("Offer no longer exists."));
        return new TokenLookup(t, offer);
    }

    /** Mark the token used and set the offer status. Returns the updated offer. */
    @Transactional
    public Offer accept(String token) {
        var lookup = validate(token);
        markUsed(lookup.tokenEntity(), "ACCEPT");
        Offer offer = lookup.offer();
        offer.setStatus("ACCEPTED");
        offerRepository.save(offer);
        // Advance pipeline to HIRED
        pipelineStageRepository
                .findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                .ifPresent(ps -> {
                    ps.setStage("HIRED");
                    ps.setUpdatedByAgent("CANDIDATE");
                    pipelineStageRepository.save(ps);
                });
        log.info("Offer {} accepted by candidate via email link", offer.getId());
        notifyHr(offer, "Offer Accepted", "has ACCEPTED the offer",
                "They have been moved to Hired. You can start onboarding.", false);
        return offer;
    }

    @Transactional
    public Offer reject(String token) {
        var lookup = validate(token);
        markUsed(lookup.tokenEntity(), "REJECT");
        Offer offer = lookup.offer();
        offer.setStatus("DECLINED");
        offerRepository.save(offer);
        pipelineStageRepository
                .findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                .ifPresent(ps -> {
                    ps.setStage("REJECTED");
                    ps.setUpdatedByAgent("CANDIDATE");
                    pipelineStageRepository.save(ps);
                });
        log.info("Offer {} declined by candidate via email link", offer.getId());
        notifyHr(offer, "Offer Declined", "has DECLINED the offer",
                "They have been moved to Rejected.", false);
        return offer;
    }

    @Transactional
    public Offer negotiate(String token, BigDecimal counterSalary, LocalDate counterStartDate, String notes) {
        var lookup = validate(token);
        markUsed(lookup.tokenEntity(), "NEGOTIATE");
        Offer offer = lookup.offer();
        offer.setStatus("NEGOTIATING");
        offerRepository.save(offer);

        String summary = buildNegotiateSummary(counterSalary, counterStartDate, notes);
        negotiationHistoryRepository.save(OfferNegotiationHistory.builder()
                .offerId(offer.getId())
                .roundNumber(negotiationHistoryRepository.countByOfferId(offer.getId()) + 1)
                .currency(offer.getCurrency())
                .changedBy("CANDIDATE")
                .salaryAmount(counterSalary != null ? counterSalary : offer.getSalaryAmount())
                .startDate(counterStartDate != null ? counterStartDate : offer.getStartDate())
                .notes("Candidate counter-offer via email link: " + summary)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("Offer {} entering negotiation — candidate counter: {}", offer.getId(), summary);

        String cur = offer.getCurrency() != null ? offer.getCurrency() : "MYR";
        String details = "Current offer: " + (offer.getSalaryAmount() != null ? cur + " " + String.format("%,.0f", offer.getSalaryAmount()) : "salary not set")
                + ", start " + (offer.getStartDate() != null ? offer.getStartDate() : "not set") + "\n"
                + "Candidate's counter: "
                + (counterSalary != null ? cur + " " + String.format("%,.0f", counterSalary) : "salary unchanged")
                + ", start " + (counterStartDate != null ? counterStartDate : "unchanged") + "\n"
                + (notes != null && !notes.isBlank() ? "Notes: " + notes + "\n" : "")
                + "\nPlease review and send a revised offer from the Offer column of the Pipeline board.";
        notifyHr(offer, "Action needed: Counter-offer", "has sent a COUNTER-OFFER", details, true);
        return offer;
    }

    /** Tells the HR inbox about a candidate's offer response. Never lets a mail problem affect the response itself. */
    private void notifyHr(Offer offer, String subjectPrefix, String headline, String details, boolean withButton) {
        try {
            String name = candidateRepository.findById(offer.getCandidateId()).map(c -> c.getFullName()).orElse("A candidate");
            String role = jobRequisitionRepository.findById(offer.getRequisitionId()).map(r -> r.getTitle()).orElse("the role");
            String body = name + " " + headline + " for the " + role + " position.\n\n" + details;
            String button = "";
            if (withButton) {
                String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
                button = "<div style='margin:20px 0;'><a href='" + base + "' "
                        + "style='display:inline-block;padding:12px 24px;background:#6366F1;color:#fff;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;'>"
                        + "Open Pipeline</a></div>";
            }
            emailService.notifyHr(subjectPrefix + " – " + name + " (" + role + ")", body, button);
        } catch (Exception e) {
            log.warn("Could not send HR notification for offer {}: {}", offer.getId(), e.getMessage());
        }
    }

    private void markUsed(OfferResponseToken t, String action) {
        t.setUsedAt(LocalDateTime.now());
        t.setUsedAction(action);
        tokenRepository.save(t);
    }

    private String buildNegotiateSummary(BigDecimal salary, LocalDate start, String notes) {
        StringBuilder sb = new StringBuilder();
        if (salary != null) sb.append("salary=").append(salary);
        if (start != null) { if (sb.length() > 0) sb.append(", "); sb.append("startDate=").append(start); }
        if (notes != null && !notes.isBlank()) { if (sb.length() > 0) sb.append(", "); sb.append("notes=").append(notes); }
        return sb.length() > 0 ? sb.toString() : "(no details)";
    }
}
