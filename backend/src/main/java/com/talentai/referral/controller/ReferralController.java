package com.talentai.referral.controller;

import com.talentai.referral.dto.ReferralDtos.*;
import com.talentai.referral.service.ReferralAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralAgentService referralAgentService;

    @PostMapping
    public ResponseEntity<ReferralResponse> submitReferral(@RequestBody SubmitReferralRequest request, Authentication authentication) {
        return ResponseEntity.ok(referralAgentService.submitReferral(request, authentication.getName()));
    }

    /** Public endpoint — no login required. For employee self-service referrals. */
    @PostMapping("/public")
    public ResponseEntity<ReferralResponse> submitPublicReferral(@RequestBody SubmitReferralRequest request) {
        return ResponseEntity.ok(referralAgentService.submitPublicReferral(request));
    }

    @GetMapping
    public ResponseEntity<List<ReferralResponse>> getAll() {
        return ResponseEntity.ok(referralAgentService.getAllReferrals());
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ReferralResponse>> getMine(Authentication authentication) {
        return ResponseEntity.ok(referralAgentService.getMyReferrals(authentication.getName()));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ReferralResponse> updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(referralAgentService.updateStatus(id, request.getStatus()));
    }
}
