package com.talentai.admin.controller;

import com.talentai.admin.entity.Offer;
import com.talentai.admin.service.OfferResponseService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Public (no JWT) endpoints the candidate hits when they click Accept / Reject /
 * Negotiate inside the offer letter email.
 *
 * All paths are under /api/public/offer-respond which is whitelisted in SecurityConfig.
 */
@RestController
@RequestMapping("/api/public/offer-respond")
@RequiredArgsConstructor
public class PublicOfferController {

    private final OfferResponseService offerResponseService;

    /** GET /api/public/offer-respond?token=XYZ&action=accept|reject
     *  Simple one-click for accept and reject — no body needed. */
    @GetMapping
    public ResponseEntity<OfferActionResult> handleAction(
            @RequestParam String token,
            @RequestParam String action) {
        try {
            Offer offer = switch (action.toLowerCase()) {
                case "accept" -> offerResponseService.accept(token);
                case "reject" -> offerResponseService.reject(token);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };
            String message = "accept".equalsIgnoreCase(action)
                    ? "Congratulations! Your offer has been accepted. The recruiting team will be in touch shortly."
                    : "Thank you for letting us know. Your response has been recorded.";
            return ResponseEntity.ok(new OfferActionResult(true, action.toUpperCase(), message, null));
        } catch (Exception e) {
            return ResponseEntity.ok(new OfferActionResult(false, action.toUpperCase(), null, e.getMessage()));
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

    /** GET /api/public/offer-respond/info?token=XYZ — returns offer details for the negotiate form. */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info(@RequestParam String token) {
        try {
            var lookup = offerResponseService.validate(token);
            Offer o = lookup.offer();
            return ResponseEntity.ok(Map.of(
                    "offerId", o.getId(),
                    "salaryAmount", o.getSalaryAmount() != null ? o.getSalaryAmount() : "",
                    "currency", o.getCurrency() != null ? o.getCurrency() : "MYR",
                    "startDate", o.getStartDate() != null ? o.getStartDate().toString() : "",
                    "status", o.getStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
