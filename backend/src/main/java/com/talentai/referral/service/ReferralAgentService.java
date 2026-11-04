package com.talentai.referral.service;

import org.springframework.ai.chat.client.ChatClient;
import com.talentai.common.service.PromptLoader;
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
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * REFERRAL AGENT
 *
 * Responsibilities:
 *  - Accept employee referral submissions.
 *  - Route the referred candidate to a role: the one the referrer chose, or,
 *    if they left it open, the open requisition Claude picks as the best fit.
 *    The agent does not score; the Screening Agent does, against that role.
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

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ReferralRepository referralRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final AppUserRepository appUserRepository;
    private final ScreeningAgentService screeningAgentService;
    private final ScreeningResultRepository screeningResultRepository;

    @org.springframework.beans.factory.annotation.Value("${app.demo-mode:false}")
    private boolean demoMode;

    /** The earliest candidate with this email, or null. Several records can share an email, so this is
     *  never a single-result lookup; in demo mode it is skipped so shared emails stay separate people. */
    private Candidate findExistingCandidate(String email) {
        if (demoMode || email == null || email.isBlank()) return null;
        return candidateRepository.findFirstByEmailOrderByIdAsc(email).orElse(null);
    }

    /** Public submission — no portal account required. Referrer identity comes from form fields. */
    @Transactional
    public ReferralResponse submitPublicReferral(SubmitReferralRequest request) {
        Candidate candidate = findExistingCandidate(request.getCandidateEmail());
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(request.getCandidateName())
                    .email(request.getCandidateEmail())
                    .resumeText(request.getResumeText())
                    .sourceChannel("REFERRAL")
                    .build();
            candidate = candidateRepository.save(candidate);
        } else if (request.getResumeText() != null && !request.getResumeText().isBlank()) {
            candidate.setResumeText(request.getResumeText());
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

        referral = routeToRole(referral, candidate);
        autoScreen(referral, candidate);
        return toResponse(referral, candidate, null);
    }

    @Transactional
    public ReferralResponse submitReferral(SubmitReferralRequest request, String referrerUsername) {
        AppUser referrer = appUserRepository.findByUsername(referrerUsername)
                .orElseThrow(() -> new IllegalStateException("Referring user not found"));

        Candidate candidate = findExistingCandidate(request.getCandidateEmail());
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(request.getCandidateName())
                    .email(request.getCandidateEmail())
                    .resumeText(request.getResumeText())
                    .sourceChannel("REFERRAL")
                    .build();
            candidate = candidateRepository.save(candidate);
        } else if (request.getResumeText() != null && !request.getResumeText().isBlank()) {
            candidate.setResumeText(request.getResumeText());
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

        // Route to a role, then auto-screen
        referral = routeToRole(referral, candidate);
        autoScreen(referral, candidate);

        return toResponse(referral, candidate, referrer);
    }

    private void autoScreen(Referral referral, Candidate candidate) {
        Long reqId = referral.getMatchedRequisitionId();
        if (reqId == null || candidate.getResumeText() == null || candidate.getResumeText().isBlank()) return;
        JobRequisition req = jobRequisitionRepository.findById(reqId).orElse(null);
        if (req == null) return;
        try {
            screeningAgentService.runScreeningInternal(candidate, req);
            referral.setStatus("SCREENING");
            referralRepository.save(referral);
        } catch (Exception e) {
            log.warn("Auto-screening failed for referral {} candidate {}: {}", referral.getId(), candidate.getId(), e.getMessage());
        }
    }

    /**
     * Sends the referral to a role. The referrer's own choice is used as it is, with no AI call; only when they left
     * it open (or the role they chose is no longer open) does Claude pick one of the open roles. Either way the
     * candidate is then screened against that role, and that screening is the only score.
     */
    private Referral routeToRole(Referral referral, Candidate candidate) {
        List<JobRequisition> open = jobRequisitionRepository.findByStatus("OPEN");
        if (open.isEmpty()) return referral;

        JobRequisition chosen = referral.getRequisitionId() == null ? null
                : open.stream().filter(r -> r.getId().equals(referral.getRequisitionId())).findFirst().orElse(null);
        String rationale = null;
        if (chosen == null) {
            Optional<ClaudeReferralRoleResponse> pick = pickRole(open, candidate, referral.getId());
            if (pick.isEmpty()) return referral;
            Long pickedId = pick.get().getBestRequisitionId();
            chosen = open.stream().filter(r -> r.getId().equals(pickedId)).findFirst().orElse(null);
            if (chosen == null) return referral;
            rationale = pick.get().getRationale();
        }
        return attachToRole(referral, candidate, chosen, rationale);
    }

    /** Claude's choice among the open roles, or empty when it names none, names one that is not open, or fails. */
    private Optional<ClaudeReferralRoleResponse> pickRole(List<JobRequisition> open, Candidate candidate, Long referralId) {
        String requisitionList = open.stream()
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

        try {
            ClaudeReferralRoleResponse parsed = chatClient.prompt()
                    .system(promptLoader.load("referral-agent"))
                    .user(userPrompt)
                    .call()
                    .entity(ClaudeReferralRoleResponse.class);
            if (parsed == null || parsed.getBestRequisitionId() == null) return Optional.empty();
            boolean isOpen = open.stream().anyMatch(r -> r.getId().equals(parsed.getBestRequisitionId()));
            return isOpen ? Optional.of(parsed) : Optional.empty();
        } catch (Exception e) {
            // Non-fatal: leave the referral SUBMITTED for a recruiter to route by hand
            log.error("Failed to get or parse Referral Agent response for referral {}", referralId, e);
            return Optional.empty();
        }
    }

    private Referral attachToRole(Referral referral, Candidate candidate, JobRequisition role, String rationale) {
        referral.setMatchedRequisitionId(role.getId());
        referral.setStatus("MATCHED");
        referral = referralRepository.save(referral);

        String note = rationale == null
                ? "Referred for this role by " + nameOf(referral)
                : "Routed to this role by the Referral Agent. " + rationale;
        pipelineStageRepository.findByCandidateIdAndRequisitionId(candidate.getId(), role.getId())
                .orElseGet(() -> pipelineStageRepository.save(PipelineStage.builder()
                        .candidateId(candidate.getId())
                        .requisitionId(role.getId())
                        .stage("REFERRAL_MATCHED")
                        .updatedByAgent(AGENT_NAME)
                        .notes(note)
                        .build()));

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(role.getId())
                .action("REFERRAL_MATCHED")
                .details(rationale == null ? "Referrer chose " + role.getTitle() : "Routed to " + role.getTitle() + " - " + rationale)
                .build());
        return referral;
    }

    private String nameOf(Referral referral) {
        return referral.getReferrerName() != null && !referral.getReferrerName().isBlank()
                ? referral.getReferrerName() : "an employee";
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
        // Public referrals have no signed-in referrer
        AppUser referrer = referral.getReferredBy() == null ? null
                : appUserRepository.findById(referral.getReferredBy()).orElse(null);
        return toResponse(referral, candidate, referrer);
    }

    private ReferralResponse toResponse(Referral referral, Candidate candidate, AppUser referrer) {
        JobRequisition requisition = referral.getRequisitionId() != null
                ? jobRequisitionRepository.findById(referral.getRequisitionId()).orElse(null) : null;
        JobRequisition matchedRequisition = referral.getMatchedRequisitionId() != null
                ? jobRequisitionRepository.findById(referral.getMatchedRequisitionId()).orElse(null) : null;

        Long scoredRoleId = referral.getMatchedRequisitionId() != null ? referral.getMatchedRequisitionId() : referral.getRequisitionId();
        Optional<com.talentai.screening.entity.ScreeningResult> screening = scoredRoleId == null ? Optional.empty()
                : screeningResultRepository.findByCandidateIdAndRequisitionId(referral.getCandidateId(), scoredRoleId);

        return ReferralResponse.builder()
                .id(referral.getId())
                .candidateId(referral.getCandidateId())
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .requisitionId(referral.getRequisitionId())
                .requisitionTitle(requisition != null ? requisition.getTitle() : null)
                .matchedRequisitionId(referral.getMatchedRequisitionId())
                .matchedRequisitionTitle(matchedRequisition != null ? matchedRequisition.getTitle() : null)
                .screeningScore(screening.map(r -> r.getOverallScore()).orElse(null))
                .screeningRecommendation(screening.map(r -> r.getRecommendation()).orElse(null))
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
