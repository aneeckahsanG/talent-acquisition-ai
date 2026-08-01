package com.talentai.referral.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.client.ClaudeApiClient;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.referral.dto.ReferralDtos.*;
import com.talentai.referral.entity.Referral;
import com.talentai.referral.repository.ReferralRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * REFERRAL AGENT
 *
 * Responsibilities:
 *  - Accept employee referral submissions.
 *  - Automatically match the referred candidate against open requisitions
 *    using Claude (if the referrer didn't target a specific role, or to
 *    validate/improve on the targeted role).
 *  - Track referral status from submission through to hire, and notify
 *    (simulated) the referring employee of status changes.
 *
 * Human-in-the-loop: matched referrals still flow through the Screening
 * Agent and recruiter review before any hiring decision is made; this
 * agent only accelerates intake and routing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferralAgentService {

    private static final String AGENT_NAME = "REFERRAL";

    private final ClaudeApiClient claudeApiClient;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ReferralRepository referralRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are the Referral Agent within an Agentic AI Talent Sourcing platform.

            An employee has referred a candidate. Your task is to identify which OPEN
            job requisition (from the list provided) is the BEST fit for this
            candidate, and provide a match score and rationale.

            If none of the requisitions are a reasonable fit (matchScore below 40 for
            all), set bestRequisitionId to null.

            Provide:
            - bestRequisitionId: the id of the best-fit requisition, or null if none fit well.
            - matchScore: 0-100, fit score for the bestRequisitionId (or 0 if null).
            - rationale: a concise 1-2 sentence explanation.

            IMPORTANT: Respond with ONLY a single JSON object, no markdown fences, no
            preamble, no commentary. The JSON must exactly match this shape:

            {
              "bestRequisitionId": number or null,
              "matchScore": number,
              "rationale": "string"
            }
            """;

    /** Public submission — no portal account required. Referrer identity comes from form fields. */
    @Transactional
    public ReferralResponse submitPublicReferral(SubmitReferralRequest request) {
        Candidate candidate = candidateRepository.findByEmail(request.getCandidateEmail()).orElse(null);
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(request.getCandidateName())
                    .email(request.getCandidateEmail())
                    .resumeText(request.getResumeText())
                    .sourceChannel("REFERRAL")
                    .build();
            candidate = candidateRepository.save(candidate);
        }

        Referral referral = Referral.builder()
                .candidateId(candidate.getId())
                .requisitionId(request.getRequisitionId())
                .referredBy(null)
                .referrerName(request.getReferrerName())
                .referrerEmail(request.getReferrerEmail())
                .referrerPosition(request.getReferrerPosition())
                .relationshipNotes(request.getRelationshipNotes())
                .status("SUBMITTED")
                .build();
        referral = referralRepository.save(referral);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(request.getRequisitionId())
                .action("REFERRAL_SUBMITTED")
                .details("Public referral by " + request.getReferrerName())
                .build());

        referral = runMatching(referral, candidate);
        return toResponse(referral, candidate, null);
    }

    @Transactional
    public ReferralResponse submitReferral(SubmitReferralRequest request, String referrerUsername) {
        AppUser referrer = appUserRepository.findByUsername(referrerUsername)
                .orElseThrow(() -> new IllegalStateException("Referring user not found"));

        Candidate candidate = candidateRepository.findByEmail(request.getCandidateEmail()).orElse(null);
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(request.getCandidateName())
                    .email(request.getCandidateEmail())
                    .resumeText(request.getResumeText())
                    .sourceChannel("REFERRAL")
                    .build();
            candidate = candidateRepository.save(candidate);
        }

        Referral referral = Referral.builder()
                .candidateId(candidate.getId())
                .requisitionId(request.getRequisitionId())
                .referredBy(referrer.getId())
                .referrerName(request.getReferrerName() != null && !request.getReferrerName().isBlank()
                        ? request.getReferrerName() : referrer.getFullName())
                .referrerPosition(request.getReferrerPosition())
                .relationshipNotes(request.getRelationshipNotes())
                .status("SUBMITTED")
                .build();
        referral = referralRepository.save(referral);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(request.getRequisitionId())
                .action("REFERRAL_SUBMITTED")
                .details("Referred by " + referrer.getFullName())
                .build());

        // Auto-match against open roles
        referral = runMatching(referral, candidate);

        return toResponse(referral, candidate, referrer);
    }

    private Referral runMatching(Referral referral, Candidate candidate) {
        List<JobRequisition> openRequisitions = jobRequisitionRepository.findByStatus("OPEN");

        if (openRequisitions.isEmpty()) {
            return referral;
        }

        String requisitionList = openRequisitions.stream()
                .map(r -> "ID %d: %s | Skills: %s | Level: %s".formatted(
                        r.getId(), r.getTitle(), nullToDash(r.getRequiredSkills()), nullToDash(r.getExperienceLevel())))
                .reduce("", (a, b) -> a + b + "\n");

        String userPrompt = """
                ## Open Job Requisitions
                %s

                ## Referred Candidate
                Name: %s
                Background / Resume Summary:
                %s
                """.formatted(requisitionList, candidate.getFullName(), nullToDash(candidate.getResumeText()));

        String rawResponse = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
        String json = claudeApiClient.stripJsonFences(rawResponse);

        ClaudeReferralMatchResponse parsed;
        try {
            parsed = objectMapper.readValue(json, ClaudeReferralMatchResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse Referral Agent response for referral {}: {}", referral.getId(), json, e);
            // Non-fatal: leave referral in SUBMITTED state for manual matching
            return referral;
        }

        if (parsed.getBestRequisitionId() != null && parsed.getMatchScore() != null
                && parsed.getMatchScore().compareTo(BigDecimal.valueOf(40)) >= 0) {

            referral.setMatchedRequisitionId(parsed.getBestRequisitionId());
            referral.setMatchScore(parsed.getMatchScore());
            referral.setStatus("MATCHED");
            referral = referralRepository.save(referral);

            // Move candidate into the pipeline for the matched requisition
            pipelineStageRepository.findByCandidateIdAndRequisitionId(candidate.getId(), parsed.getBestRequisitionId())
                    .orElseGet(() -> pipelineStageRepository.save(PipelineStage.builder()
                            .candidateId(candidate.getId())
                            .requisitionId(parsed.getBestRequisitionId())
                            .stage("REFERRAL_MATCHED")
                            .updatedByAgent(AGENT_NAME)
                            .notes("Matched via referral, score: " + parsed.getMatchScore() + ". " + parsed.getRationale())
                            .build()));

            activityLogRepository.save(AgentActivityLog.builder()
                    .agentName(AGENT_NAME)
                    .candidateId(candidate.getId())
                    .requisitionId(parsed.getBestRequisitionId())
                    .action("REFERRAL_MATCHED")
                    .details("Match score: " + parsed.getMatchScore() + " - " + parsed.getRationale())
                    .build());
        }

        return referral;
    }

    public List<ReferralResponse> getAllReferrals() {
        return referralRepository.findAllByOrderBySubmittedAtDesc().stream()
                .map(this::toResponseLookup)
                .toList();
    }

    public List<ReferralResponse> getMyReferrals(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        return referralRepository.findByReferredByOrderBySubmittedAtDesc(user.getId()).stream()
                .map(this::toResponseLookup)
                .toList();
    }

    @Transactional
    public ReferralResponse updateStatus(Long referralId, String status) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new IllegalArgumentException("Referral not found: " + referralId));

        String normalized = status.toUpperCase();
        if (!List.of("SUBMITTED", "MATCHED", "SCREENING", "ADVANCED", "REJECTED", "HIRED").contains(normalized)) {
            throw new IllegalArgumentException("Invalid referral status: " + status);
        }
        referral.setStatus(normalized);
        referral = referralRepository.save(referral);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(referral.getCandidateId())
                .requisitionId(referral.getMatchedRequisitionId() != null ? referral.getMatchedRequisitionId() : referral.getRequisitionId())
                .action("REFERRAL_STATUS_UPDATED")
                .details("Status set to " + normalized)
                .build());

        return toResponseLookup(referral);
    }

    private ReferralResponse toResponseLookup(Referral referral) {
        Candidate candidate = candidateRepository.findById(referral.getCandidateId()).orElse(null);
        AppUser referrer = appUserRepository.findById(referral.getReferredBy()).orElse(null);
        return toResponse(referral, candidate, referrer);
    }

    private ReferralResponse toResponse(Referral referral, Candidate candidate, AppUser referrer) {
        JobRequisition requisition = referral.getRequisitionId() != null
                ? jobRequisitionRepository.findById(referral.getRequisitionId()).orElse(null) : null;
        JobRequisition matchedRequisition = referral.getMatchedRequisitionId() != null
                ? jobRequisitionRepository.findById(referral.getMatchedRequisitionId()).orElse(null) : null;

        return ReferralResponse.builder()
                .id(referral.getId())
                .candidateId(referral.getCandidateId())
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .requisitionId(referral.getRequisitionId())
                .requisitionTitle(requisition != null ? requisition.getTitle() : null)
                .matchedRequisitionId(referral.getMatchedRequisitionId())
                .matchedRequisitionTitle(matchedRequisition != null ? matchedRequisition.getTitle() : null)
                .matchScore(referral.getMatchScore())
                .referredByUsername(referrer != null ? referrer.getUsername() : null)
                .referrerName(referral.getReferrerName())
                .referrerEmail(referral.getReferrerEmail())
                .referrerPosition(referral.getReferrerPosition())
                .relationshipNotes(referral.getRelationshipNotes())
                .status(referral.getStatus())
                .submittedAt(referral.getSubmittedAt())
                .lastUpdatedAt(referral.getLastUpdatedAt())
                .build();
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "Not specified" : value;
    }
}
