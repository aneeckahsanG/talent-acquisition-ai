package com.talentai.admin.controller;

import com.talentai.admin.entity.Offer;
import com.talentai.admin.service.OfferResponseService;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public (no JWT) endpoints behind the Accept / Negotiate / Decline buttons in the offer email.
 * The buttons open a confirmation page; only the POST endpoints below change anything, so a
 * mail scanner that opens the links cannot accept or decline an offer.
 *
 * All paths are under /api/public/offer-respond which is whitelisted in SecurityConfig.
 */
@RestController
@RequestMapping("/api/public/offer-respond")
@RequiredArgsConstructor
public class PublicOfferController {

    private final OfferResponseService offerResponseService;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;

    @Value("${app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /**
     * Links in offer emails sent before the confirmation page existed point here. They no longer act:
     * they just forward to the confirmation page.
     */
    @GetMapping
    public ResponseEntity<Void> legacyLink(@RequestParam String token, @RequestParam String action) {
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        String target = base + "/offer-respond?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&action=" + URLEncoder.encode(action, StandardCharsets.UTF_8);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }

    /** Candidate confirms acceptance by typing their full name. */
    @PostMapping("/accept")
    public ResponseEntity<OfferActionResult> accept(@RequestBody AcceptRequest body) {
        try {
            offerResponseService.accept(body.getToken(), body.getFullName());
            return ResponseEntity.ok(new OfferActionResult(true, "ACCEPT",
                    "Congratulations! Your offer has been accepted. The recruiting team will be in touch shortly.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(new OfferActionResult(false, "ACCEPT", null, e.getMessage()));
        }
    }

    /** Candidate confirms they are declining the offer. */
    @PostMapping("/reject")
    public ResponseEntity<OfferActionResult> reject(@RequestBody TokenRequest body) {
        try {
            offerResponseService.reject(body.getToken());
            return ResponseEntity.ok(new OfferActionResult(true, "REJECT",
                    "Thank you for letting us know. Your response has been recorded.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(new OfferActionResult(false, "REJECT", null, e.getMessage()));
        }
    }

    /** POST /api/public/offer-respond/negotiate
     *  Called when the candidate submits their counter-offer form. */
    @PostMapping("/negotiate")
    public ResponseEntity<OfferActionResult> negotiate(@RequestBody NegotiateRequest body) {
        try {
            offerResponseService.negotiate(body.getToken(), body.getCounterSalary(),
                    body.getCounterStartDate(), body.getNotes());
            return ResponseEntity.ok(new OfferActionResult(true, "NEGOTIATE",
                    "Your counter-offer has been submitted. The recruiting team will review and get back to you.", null));
        } catch (Exception e) {
            return ResponseEntity.ok(new OfferActionResult(false, "NEGOTIATE", null, e.getMessage()));
        }
    }

    /** GET /api/public/offer-respond/info?token=XYZ — the offer summary shown on the confirmation page. */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(@RequestParam String token) {
        try {
            var lookup = offerResponseService.validate(token);
            Offer o = lookup.offer();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("offerId", o.getId());
            body.put("candidateName", candidateRepository.findById(o.getCandidateId()).map(c -> c.getFullName()).orElse(""));
            body.put("jobTitle", jobRequisitionRepository.findById(o.getRequisitionId()).map(r -> r.getTitle()).orElse(""));
            body.put("salaryAmount", o.getSalaryAmount() != null ? o.getSalaryAmount() : "");
            body.put("currency", o.getCurrency() != null ? o.getCurrency() : "MYR");
            body.put("startDate", o.getStartDate() != null ? o.getStartDate().toString() : "");
            body.put("expiryDate", o.getExpiryDate() != null ? o.getExpiryDate().toString() : "");
            body.put("status", o.getStatus());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Data
    public static class TokenRequest {
        private String token;
    }

    @Data
    public static class AcceptRequest {
        private String token;
        private String fullName;
    }

    @Data
    public static class NegotiateRequest {
        private String token;
        private BigDecimal counterSalary;
        private LocalDate counterStartDate;
        private String notes;
    }

    public record OfferActionResult(boolean success, String action, String message, String error) {}
}
