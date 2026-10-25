package com.talentai.admin.service;

import com.talentai.admin.entity.Offer;
import com.talentai.admin.entity.OfferLetter;
import com.talentai.admin.entity.OfferNegotiationHistory;
import com.talentai.admin.entity.OfferResponseToken;
import com.talentai.admin.repository.OfferLetterRepository;
import com.talentai.admin.repository.OfferNegotiationHistoryRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.admin.repository.OfferResponseTokenRepository;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
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
    private final SourcingMatchRepository sourcingMatchRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final EmailService emailService;
    private final OfferLetterRepository letterRepository;
    private final OfferCopyPdfService pdfService;

    @Value("${app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Where a response came from; kept as part of the acceptance record. */
    public record Origin(String ip, String userAgent) {}

    /** Generate a fresh single-use token for this offer (replaces any prior token), tied to the letter that was sent. */
    @Transactional
    public String generateToken(Long offerId, Long letterId) {
        tokenRepository.deleteByOfferId(offerId);
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenRepository.save(OfferResponseToken.builder()
                .offerId(offerId)
                .letterId(letterId)
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
    public Offer accept(String token, String signedName, Origin origin) {
        String name = signedName == null ? "" : signedName.trim().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            throw new IllegalArgumentException("Please type your full name to accept the offer.");
        }
        var lookup = validate(token);
        String onOffer = candidateRepository.findById(lookup.offer().getCandidateId()).map(c -> c.getFullName()).orElse(null);
        if (onOffer != null && !LetterFingerprint.sameName(name, onOffer)) {
            throw new IllegalArgumentException("Please type your full name exactly as it appears on your offer: " + onOffer);
        }
        lookup.tokenEntity().setSignedName(name);
        markUsed(lookup.tokenEntity(), "ACCEPT", origin);
        Offer offer = lookup.offer();
        offer.setStatus("ACCEPTED");
        offerRepository.save(offer);
        // Advance pipeline to HIRED
        pipelineStageRepository
                .findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                .ifPresent(ps -> {
                    ps.setStage("HIRED");
                    ps.setUpdatedByAgent("CANDIDATE");
                    ps.setEnteredAt(LocalDateTime.now());
                    pipelineStageRepository.save(ps);
                });
        log.info("Offer {} accepted by candidate via email link", offer.getId());
        notifyHr(offer, "Offer Accepted", "has ACCEPTED the offer",
                "Electronic acceptance: they typed \"" + name + "\" and confirmed.\n"
                        + "They have been moved to Hired. You can start onboarding.\n"
                        + "A signed copy is on the offer card and has been emailed to the candidate.", false);
        emailCopyToCandidate(offer, lookup.tokenEntity());
        return offer;
    }

    @Transactional
    public Offer reject(String token, Origin origin) {
        var lookup = validate(token);
        markUsed(lookup.tokenEntity(), "REJECT", origin);
        Offer offer = lookup.offer();
        offer.setStatus("DECLINED");
        offerRepository.save(offer);
        pipelineStageRepository
                .findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                .ifPresent(ps -> {
                    ps.setStage("REJECTED");
                    ps.setUpdatedByAgent("CANDIDATE");
                    ps.setEnteredAt(LocalDateTime.now());
                    ps.setNotes("Candidate declined the offer");
                    pipelineStageRepository.save(ps);
                });
        // Keep the sourcing/applicants view in step with the pipeline stage
        sourcingMatchRepository.findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                .ifPresent(m -> {
                    m.setStatus("REJECTED");
                    sourcingMatchRepository.save(m);
                });
        log.info("Offer {} declined by candidate via email link", offer.getId());
        notifyHr(offer, "Offer Declined", "has DECLINED the offer",
                "They have been moved to Rejected.", false);
        return offer;
    }

    @Transactional
    public Offer negotiate(String token, BigDecimal counterSalary, LocalDate counterStartDate, String notes, Origin origin) {
        var lookup = validate(token);
        markUsed(lookup.tokenEntity(), "NEGOTIATE", origin);
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

    /** The recorded acceptance for an offer, if the candidate accepted. */
    public Optional<OfferResponseToken> acceptance(Long offerId) {
        return tokenRepository.findFirstByOfferIdAndUsedActionOrderByUsedAtDesc(offerId, "ACCEPT");
    }

    /** PDF copy for the candidate: only available once the offer behind this link has been accepted. */
    public byte[] copyForToken(String token) {
        OfferResponseToken t = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unrecognised link."));
        if (!"ACCEPT".equals(t.getUsedAction())) {
            throw new IllegalStateException("A copy is only available once the offer has been accepted.");
        }
        return buildSignedCopy(t);
    }

    /** PDF copy for the recruiter. */
    public byte[] signedCopyForOffer(Long offerId) {
        OfferResponseToken t = acceptance(offerId)
                .orElseThrow(() -> new IllegalStateException("This offer has not been accepted yet."));
        return buildSignedCopy(t);
    }

    private byte[] buildSignedCopy(OfferResponseToken t) {
        Offer offer = offerRepository.findById(t.getOfferId())
                .orElseThrow(() -> new IllegalStateException("Offer no longer exists."));
        List<OfferLetter> sent = letterRepository.findByOfferIdOrderBySentAtAsc(offer.getId());
        OfferLetter letter = sent.stream().filter(l -> l.getId().equals(t.getLetterId())).findFirst().orElse(null);

        String letterText = letter != null ? letter.getLetterText()
                : "(The original letter text was not stored for this offer.)\n\nAccepted terms: "
                + (offer.getSalaryAmount() != null ? offer.getCurrency() + " " + offer.getSalaryAmount().toPlainString() + " per month" : "salary as discussed")
                + ", start date " + (offer.getStartDate() != null ? offer.getStartDate() : "to be confirmed") + ".";

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss");
        List<String> record = new java.util.ArrayList<>();
        record.add("Accepted by: " + t.getSignedName() + " (typed full name)");
        record.add("Date and time: " + (t.getUsedAt() != null ? t.getUsedAt().format(fmt) : "unknown") + " (server time)");
        record.add("Device address: " + (t.getUsedIp() != null ? t.getUsedIp() : "not recorded"));
        record.add("Browser: " + (t.getUsedUserAgent() != null ? t.getUsedUserAgent() : "not recorded"));
        if (letter != null) {
            record.add("Letter version: " + (sent.indexOf(letter) + 1) + " of " + sent.size()
                    + ", sent " + (letter.getSentAt() != null ? letter.getSentAt().format(fmt) : "unknown"));
            String current = LetterFingerprint.sha256(letter.getLetterText());
            if (letter.getLetterHash() == null) {
                record.add("Letter fingerprint (SHA-256): " + current + " (calculated now; not recorded when the letter was sent)");
            } else if (letter.getLetterHash().equals(current)) {
                record.add("Letter fingerprint (SHA-256): " + letter.getLetterHash());
                record.add("Integrity check: the letter text above matches the fingerprint recorded when it was sent.");
            } else {
                record.add("Letter fingerprint recorded when sent: " + letter.getLetterHash());
                record.add("WARNING: the stored letter text no longer matches that fingerprint, so it may have been altered.");
            }
        }
        record.add("Offer reference: #" + offer.getId());
        return pdfService.render("Offer of Employment", letterText, record);
    }

    /** Emails the candidate their signed copy. A mail problem never undoes the acceptance. */
    private void emailCopyToCandidate(Offer offer, OfferResponseToken t) {
        try {
            var candidate = candidateRepository.findById(offer.getCandidateId()).orElse(null);
            if (candidate == null || candidate.getEmail() == null || candidate.getEmail().isBlank()) return;
            String role = jobRequisitionRepository.findById(offer.getRequisitionId()).map(r -> r.getTitle()).orElse("the role");
            String body = "Dear " + candidate.getFullName().trim().split("\\s+")[0] + ",\n\n"
                    + "Thank you for accepting our offer for the " + role + " position. "
                    + "A copy of your offer letter and your acceptance record is attached for your files.\n\n"
                    + "Our team will be in touch shortly about next steps.\n\n"
                    + "Best regards,\nHuman Resources\nArvato Systems Malaysia Sdn. Bhd.";
            emailService.sendHtmlWithAttachment(candidate.getEmail(), "Your offer acceptance – " + role,
                    emailService.wrapInTemplate(body, "TA"), "offer-acceptance.pdf", buildSignedCopy(t));
        } catch (Exception e) {
            log.warn("Could not email the signed copy for offer {}: {}", offer.getId(), e.getMessage());
        }
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

    private void markUsed(OfferResponseToken t, String action, Origin origin) {
        t.setUsedAt(LocalDateTime.now());
        t.setUsedAction(action);
        if (origin != null) {
            t.setUsedIp(origin.ip() != null && origin.ip().length() > 64 ? origin.ip().substring(0, 64) : origin.ip());
            t.setUsedUserAgent(origin.userAgent() != null && origin.userAgent().length() > 255
                    ? origin.userAgent().substring(0, 255) : origin.userAgent());
        }
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
