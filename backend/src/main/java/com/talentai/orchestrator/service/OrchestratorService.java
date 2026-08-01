package com.talentai.orchestrator.service;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.Offer;
import com.talentai.admin.entity.OfferNegotiationHistory;
import com.talentai.common.client.ClaudeApiClient;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.OfferNegotiationHistoryRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.orchestrator.dto.OrchestratorDtos.*;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * ORCHESTRATOR AGENT
 *
 * Responsibilities:
 *  - Provide a unified view of each candidate's progress across the
 *    Sourcing -> Screening -> Referral -> Admin pipeline for a given
 *    requisition (kanban-style board).
 *  - Aggregate cross-agent activity into a single audit/activity feed.
 *  - Surface dashboard-level KPIs for recruiters and leadership.
 *
 * This service does not call Claude directly - it reads state written
 * by the other agents and presents it cohesively. Stage transitions
 * themselves are driven by the individual agents (and human reviewers).
 */
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private static final List<String> STAGE_ORDER = List.of(
            "SOURCED", "SCREENED", "SHORTLISTED", "REFERRAL_MATCHED",
            "INTERVIEW_SCHEDULED", "OFFER", "HIRED", "REJECTED"
    );

    private final PipelineStageRepository pipelineStageRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final ReferralRepository referralRepository;
    private final InterviewScheduleRepository interviewScheduleRepository;
    private final OfferRepository offerRepository;
    private final OfferNegotiationHistoryRepository negotiationHistoryRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final ClaudeApiClient claudeApiClient;
    private final com.talentai.common.service.EmailService emailService;

    private static final List<String> INTERVIEW_OR_LATER = List.of(
            "INTERVIEW_SCHEDULED", "OFFER", "HIRED", "REJECTED"
    );

    public PipelineBoardResponse getPipelineBoard(Long requisitionId) {
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));

        List<PipelineStage> stages = pipelineStageRepository.findByRequisitionId(requisitionId);

        Map<String, List<PipelineCandidateResponse>> board = new LinkedHashMap<>();
        for (String stageName : STAGE_ORDER) {
            board.put(stageName, new java.util.ArrayList<>());
        }

        for (PipelineStage stage : stages) {
            Candidate candidate = candidateRepository.findById(stage.getCandidateId()).orElse(null);
            if (candidate == null) continue;

            var screening = screeningResultRepository
                    .findByCandidateIdAndRequisitionId(stage.getCandidateId(), requisitionId).orElse(null);
            var sourcingMatch = sourcingMatchRepository
                    .findByCandidateIdAndRequisitionId(stage.getCandidateId(), requisitionId).orElse(null);

            // Attach interview rounds for any stage at or after INTERVIEW_SCHEDULED
            List<InterviewRoundResponse> rounds = null;
            if (INTERVIEW_OR_LATER.contains(stage.getStage())) {
                var interviews = interviewScheduleRepository
                        .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(stage.getCandidateId(), requisitionId);
                if (!interviews.isEmpty()) {
                    rounds = new java.util.ArrayList<>();
                    for (int i = 0; i < interviews.size(); i++) {
                        var iv = interviews.get(i);
                        rounds.add(InterviewRoundResponse.builder()
                                .id(iv.getId())
                                .interviewType(iv.getInterviewType())
                                .confirmedSlot(iv.getConfirmedSlot())
                                .status(iv.getStatus())
                                .notes(iv.getNotes())
                                .roundNumber(i + 1)
                                .invitationEmail(iv.getInvitationEmail())
                                .candidateReply(iv.getCandidateReply())
                                .candidateRepliedAt(iv.getCandidateRepliedAt())
                                .build());
                    }
                }
            }

            // Attach offer for OFFER / HIRED stages
            OfferResponse offerResp = null;
            if ("OFFER".equals(stage.getStage()) || "HIRED".equals(stage.getStage())) {
                offerResp = offerRepository
                        .findByCandidateIdAndRequisitionId(stage.getCandidateId(), requisitionId)
                        .map(this::toOfferResponse)
                        .orElse(null);
            }

            PipelineCandidateResponse entry = PipelineCandidateResponse.builder()
                    .candidateId(candidate.getId())
                    .candidateName(candidate.getFullName())
                    .candidateEmail(candidate.getEmail())
                    .sourceChannel(candidate.getSourceChannel())
                    .requisitionId(requisitionId)
                    .requisitionTitle(requisition.getTitle())
                    .stage(stage.getStage())
                    .updatedByAgent(stage.getUpdatedByAgent())
                    .notes(stage.getNotes())
                    .enteredAt(stage.getEnteredAt())
                    .screeningScore(screening != null ? screening.getOverallScore() : null)
                    .screeningRecommendation(screening != null ? screening.getRecommendation() : null)
                    .sourcingMatchScore(sourcingMatch != null ? sourcingMatch.getMatchScore() : null)
                    .interviewRounds(rounds)
                    .offer(offerResp)
                    .build();

            board.computeIfAbsent(stage.getStage(), k -> new java.util.ArrayList<>()).add(entry);
        }

        return PipelineBoardResponse.builder()
                .requisitionId(requisitionId)
                .requisitionTitle(requisition.getTitle())
                .stages(board)
                .build();
    }

    public PipelineCandidateResponse moveStage(Long requisitionId, Long candidateId, String newStage) {
        PipelineStage ps = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline entry not found"));
        ps.setStage(newStage);
        ps.setUpdatedByAgent("HUMAN");
        ps.setEnteredAt(java.time.LocalDateTime.now());
        pipelineStageRepository.save(ps);

        Candidate candidate = candidateRepository.findById(candidateId).orElse(null);
        JobRequisition req = jobRequisitionRepository.findById(requisitionId).orElse(null);

        // Enforce required interview rounds before advancing to OFFER
        if ("OFFER".equals(newStage) && req != null) {
            int required = req.getRequiredInterviewRounds() != null ? req.getRequiredInterviewRounds() : 2;
            long completed = interviewScheduleRepository
                    .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(candidateId, requisitionId)
                    .stream().filter(i -> "COMPLETED".equals(i.getStatus())).count();
            if (completed < required) {
                throw new IllegalStateException("Cannot advance to OFFER: " + completed + " of " + required + " required interview rounds completed.");
            }
        }

        // Notify candidate on key stage changes
        if (candidate != null && candidate.getEmail() != null && req != null) {
            if ("SHORTLISTED".equals(newStage)) {
                String body = "Dear " + candidate.getFullName().split(" ")[0] + ",\n\n"
                        + "We are pleased to inform you that you have been shortlisted for the "
                        + req.getTitle() + " position.\n\n"
                        + "Our recruiting team will be in touch shortly with next steps.\n\n"
                        + "Best regards,\nHuman Resources\nTalentAcquisition AI";
                emailService.sendHtml(candidate.getEmail(), "You've been shortlisted – " + req.getTitle(),
                        emailService.wrapInTemplate(body, "TA"));
            } else if ("REJECTED".equals(newStage)) {
                String body = "Dear " + candidate.getFullName().split(" ")[0] + ",\n\n"
                        + "Thank you for your interest in the " + req.getTitle() + " position. "
                        + "After careful consideration, we have decided to move forward with other candidates at this time.\n\n"
                        + "We appreciate the time you invested and encourage you to apply for future openings.\n\n"
                        + "Best regards,\nHuman Resources\nTalentAcquisition AI";
                emailService.sendHtml(candidate.getEmail(), "Application Update – " + req.getTitle(),
                        emailService.wrapInTemplate(body, "TA"));
            }
        }
        var screening = screeningResultRepository.findByCandidateIdAndRequisitionId(candidateId, requisitionId).orElse(null);
        var match = sourcingMatchRepository.findByCandidateIdAndRequisitionId(candidateId, requisitionId).orElse(null);

        return PipelineCandidateResponse.builder()
                .candidateId(candidateId)
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .candidateEmail(candidate != null ? candidate.getEmail() : null)
                .requisitionId(requisitionId)
                .requisitionTitle(req != null ? req.getTitle() : null)
                .stage(newStage)
                .updatedByAgent("HUMAN")
                .enteredAt(ps.getEnteredAt())
                .screeningScore(screening != null ? screening.getOverallScore() : null)
                .screeningRecommendation(screening != null ? screening.getRecommendation() : null)
                .sourcingMatchScore(match != null ? match.getMatchScore() : null)
                .build();
    }

    private OfferResponse toOfferResponse(Offer o) {
        var history = negotiationHistoryRepository.findByOfferIdOrderByRoundNumberAsc(o.getId())
                .stream().map(h -> NegotiationHistoryEntry.builder()
                        .roundNumber(h.getRoundNumber())
                        .salaryAmount(h.getSalaryAmount())
                        .currency(h.getCurrency())
                        .startDate(h.getStartDate())
                        .notes(h.getNotes())
                        .changedBy(h.getChangedBy())
                        .createdAt(h.getCreatedAt())
                        .build())
                .toList();
        return OfferResponse.builder()
                .id(o.getId()).salaryAmount(o.getSalaryAmount()).currency(o.getCurrency())
                .startDate(o.getStartDate()).expiryDate(o.getExpiryDate())
                .status(o.getStatus()).notes(o.getNotes()).history(history).build();
    }

    public OfferResponse updateOfferTerms(Long offerId, UpdateOfferTermsRequest req) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found: " + offerId));

        // Snapshot current terms as a history entry before updating
        int nextRound = negotiationHistoryRepository.countByOfferId(offerId) + 1;
        negotiationHistoryRepository.save(OfferNegotiationHistory.builder()
                .offerId(offerId)
                .roundNumber(nextRound)
                .salaryAmount(req.getSalaryAmount() != null ? req.getSalaryAmount() : offer.getSalaryAmount())
                .currency(offer.getCurrency())
                .startDate(req.getStartDate() != null ? LocalDate.parse(req.getStartDate()) : offer.getStartDate())
                .notes(req.getNotes())
                .changedBy(req.getChangedBy() != null ? req.getChangedBy() : "RECRUITER")
                .build());

        // Update the offer with new terms
        if (req.getSalaryAmount() != null) offer.setSalaryAmount(req.getSalaryAmount());
        if (req.getStartDate() != null) offer.setStartDate(LocalDate.parse(req.getStartDate()));
        if (req.getNotes() != null) offer.setNotes(req.getNotes());
        if (req.getCurrency() != null) offer.setCurrency(req.getCurrency());
        offer.setStatus("NEGOTIATING");
        offerRepository.save(offer);

        return toOfferResponse(offer);
    }

    public OfferLetterResponse generateOfferLetter(Long offerId) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found: " + offerId));

        Candidate candidate = candidateRepository.findById(offer.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));

        JobRequisition requisition = jobRequisitionRepository.findById(offer.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        String salary = offer.getSalaryAmount() != null
                ? offer.getCurrency() + " " + offer.getSalaryAmount().toPlainString() + " per month"
                : "as discussed";

        String startDate = offer.getStartDate() != null ? offer.getStartDate().toString() : "to be confirmed";
        String expiryDate = offer.getExpiryDate() != null ? offer.getExpiryDate().toString() : "within 7 days";

        String system = """
                You are an HR professional writing formal employment offer letters on behalf of Arvato Systems Malaysia Sdn. Bhd.
                Write a professional, warm, and complete offer letter. Use proper business letter formatting with clear sections.
                Do NOT use markdown — plain text only with line breaks.
                Sign off as "Human Resources Department, Arvato Systems Malaysia Sdn. Bhd.".
                """;

        String prompt = String.format("""
                Generate an employment offer letter on behalf of Arvato Systems Malaysia Sdn. Bhd. with the following details:

                Company Details:
                  Arvato Systems Malaysia Sdn. Bhd.
                  Suite 26-10, Level 26, GTower
                  199 Jalan Tun Razak
                  50400 Kuala Lumpur, Malaysia

                Candidate Details:
                - Candidate Name: %s
                - Job Title: %s
                - Department: %s
                - Work Location: %s
                - Monthly Salary: %s
                - Start Date: %s
                - Offer Expiry: %s
                - Additional Notes: %s

                Include: company letterhead block (name + address), date, candidate address block, greeting,
                congratulations paragraph, role and department details, compensation, start date,
                offer expiry deadline, a brief note about next steps (signing and returning the letter),
                and a warm professional closing.
                """,
                candidate.getFullName(),
                requisition.getTitle(),
                requisition.getDepartment() != null ? requisition.getDepartment() : "N/A",
                requisition.getLocation() != null ? requisition.getLocation() : "N/A",
                salary, startDate, expiryDate,
                offer.getNotes() != null ? offer.getNotes() : "None"
        );

        String letter = claudeApiClient.sendPrompt(system, prompt);

        return OfferLetterResponse.builder()
                .candidateName(candidate.getFullName())
                .candidateEmail(candidate.getEmail())
                .letterText(letter)
                .build();
    }

    public OfferResponse createOffer(Long requisitionId, Long candidateId, CreateOfferRequest req) {
        // Move pipeline stage to OFFER
        PipelineStage ps = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline entry not found"));
        ps.setStage("OFFER");
        ps.setUpdatedByAgent("HUMAN");
        ps.setEnteredAt(LocalDateTime.now());
        pipelineStageRepository.save(ps);

        Offer offer = Offer.builder()
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .salaryAmount(req.getSalaryAmount())
                .currency(req.getCurrency() != null ? req.getCurrency() : "MYR")
                .startDate(req.getStartDate() != null ? LocalDate.parse(req.getStartDate()) : null)
                .expiryDate(req.getExpiryDate() != null ? LocalDate.parse(req.getExpiryDate()) : null)
                .notes(req.getNotes())
                .status("PENDING")
                .build();
        offer = offerRepository.save(offer);

        // Record initial offer as round 1 in history
        OfferNegotiationHistory initial = OfferNegotiationHistory.builder()
                .offerId(offer.getId())
                .roundNumber(1)
                .salaryAmount(offer.getSalaryAmount())
                .currency(offer.getCurrency())
                .startDate(offer.getStartDate())
                .notes(offer.getNotes())
                .changedBy("RECRUITER")
                .createdAt(LocalDateTime.now())
                .build();
        negotiationHistoryRepository.save(initial);

        return toOfferResponse(offer);
    }

    public void sendOfferEmail(Long offerId, String letterText) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found: " + offerId));
        Candidate candidate = candidateRepository.findById(offer.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(offer.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        if (candidate.getEmail() == null || candidate.getEmail().isBlank())
            throw new IllegalStateException("Candidate has no email address on file");

        String body = letterText != null && !letterText.isBlank() ? letterText
                : "Dear " + candidate.getFullName().split(" ")[0] + ",\n\n"
                + "Congratulations! We are delighted to extend an offer of employment for the "
                + requisition.getTitle() + " position.\n\n"
                + "Please review the details and revert with your acceptance.\n\n"
                + "Best regards,\nHuman Resources\nTalentAcquisition AI";

        emailService.sendHtml(candidate.getEmail(), "Job Offer – " + requisition.getTitle(),
                emailService.wrapInTemplate(body, "TA"));

        offer.setStatus("SENT");
        offerRepository.save(offer);
    }

    public OfferResponse updateOfferStatus(Long offerId, String newStatus) {
        Offer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found: " + offerId));
        offer.setStatus(newStatus);
        offerRepository.save(offer);

        // If accepted, move pipeline to HIRED
        if ("ACCEPTED".equals(newStatus)) {
            pipelineStageRepository
                    .findByCandidateIdAndRequisitionId(offer.getCandidateId(), offer.getRequisitionId())
                    .ifPresent(ps -> {
                        ps.setStage("HIRED");
                        ps.setUpdatedByAgent("HUMAN");
                        pipelineStageRepository.save(ps);
                    });
        }

        return toOfferResponse(offer);
    }

    public InterviewRoundResponse completeInterviewRound(Long interviewId) {
        InterviewSchedule iv = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        iv.setStatus("COMPLETED");
        interviewScheduleRepository.save(iv);
        var all = interviewScheduleRepository
                .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(iv.getCandidateId(), iv.getRequisitionId());
        int roundNumber = 1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(interviewId)) { roundNumber = i + 1; break; }
        }
        return InterviewRoundResponse.builder()
                .id(iv.getId()).interviewType(iv.getInterviewType())
                .confirmedSlot(iv.getConfirmedSlot()).status("COMPLETED")
                .notes(iv.getNotes()).roundNumber(roundNumber)
                .invitationEmail(iv.getInvitationEmail())
                .candidateReply(iv.getCandidateReply())
                .candidateRepliedAt(iv.getCandidateRepliedAt())
                .build();
    }

    public InterviewScheduledResponse scheduleInterview(Long requisitionId, Long candidateId, ScheduleInterviewRequest req) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found: " + requisitionId));

        LocalDateTime slot = req.getConfirmedSlot() != null
                ? LocalDateTime.parse(req.getConfirmedSlot())
                : null;

        String interviewType = req.getInterviewType() != null ? req.getInterviewType() : "TECHNICAL";

        // Generate invitation email via Claude
        String slotDisplay = slot != null ? slot.toString().replace("T", " at ") : "TBD";
        String invitationEmail = null;
        try {
            String prompt = """
                    You are an HR coordinator at Arvato Systems Malaysia Sdn. Bhd. Write a professional interview invitation email to a candidate.
                    Candidate name: %s
                    Candidate email: %s
                    Role: %s
                    Interview type: %s
                    Scheduled date/time: %s
                    Write only the email body (no subject line). Start with "Dear %s," and end with a sign-off from "Human Resources, Arvato Systems Malaysia Sdn. Bhd."
                    Keep it concise and professional.
                    """.formatted(candidate.getFullName(), candidate.getEmail(), requisition.getTitle(),
                    interviewType, slotDisplay, candidate.getFullName().split(" ")[0]);
            invitationEmail = claudeApiClient.sendPrompt("You are an HR coordinator writing professional interview invitation emails.", prompt);
        } catch (Exception e) {
            invitationEmail = "Dear " + candidate.getFullName() + ",\n\nWe are pleased to invite you for a " + interviewType + " interview for the " + requisition.getTitle() + " position on " + slotDisplay + ".\n\nPlease confirm your availability.\n\nBest regards,\nHuman Resources\nArvato Systems Malaysia Sdn. Bhd.";
        }

        InterviewSchedule interview = InterviewSchedule.builder()
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .interviewType(interviewType)
                .confirmedSlot(slot)
                .status(slot != null ? "CONFIRMED" : "PROPOSED")
                .notes(req.getNotes())
                .invitationEmail(invitationEmail)
                .build();
        interview = interviewScheduleRepository.save(interview);

        // Move pipeline stage
        PipelineStage ps = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElseGet(() -> PipelineStage.builder()
                        .candidateId(candidateId).requisitionId(requisitionId).build());
        ps.setStage("INTERVIEW_SCHEDULED");
        ps.setUpdatedByAgent("HUMAN");
        ps.setEnteredAt(LocalDateTime.now());
        pipelineStageRepository.save(ps);

        return InterviewScheduledResponse.builder()
                .interviewId(interview.getId())
                .candidateName(candidate.getFullName())
                .candidateEmail(candidate.getEmail())
                .requisitionTitle(requisition.getTitle())
                .interviewType(interview.getInterviewType())
                .confirmedSlot(interview.getConfirmedSlot())
                .status(interview.getStatus())
                .pipelineStage("INTERVIEW_SCHEDULED")
                .invitationEmail(interview.getInvitationEmail())
                .build();
    }

    public InterviewScheduledResponse rescheduleInterview(Long interviewId, String newSlot) {
        InterviewSchedule interview = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        Candidate candidate = candidateRepository.findById(interview.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(interview.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        LocalDateTime slot = LocalDateTime.parse(newSlot);
        String slotDisplay = slot.toString().replace("T", " at ");

        String invitationEmail;
        try {
            String prompt = """
                    You are an HR coordinator at Arvato Systems Malaysia Sdn. Bhd. Write a professional interview rescheduling email to a candidate.
                    Candidate name: %s
                    Candidate email: %s
                    Role: %s
                    Interview type: %s
                    New scheduled date/time: %s
                    Mention that this is a rescheduled interview. Write only the email body (no subject line).
                    Start with "Dear %s," and end with a sign-off from "Human Resources, Arvato Systems Malaysia Sdn. Bhd."
                    Keep it concise and professional.
                    """.formatted(candidate.getFullName(), candidate.getEmail(), requisition.getTitle(),
                    interview.getInterviewType(), slotDisplay, candidate.getFullName().split(" ")[0]);
            invitationEmail = claudeApiClient.sendPrompt("You are an HR coordinator writing professional interview emails.", prompt);
        } catch (Exception e) {
            invitationEmail = "Dear " + candidate.getFullName() + ",\n\nWe would like to reschedule your " + interview.getInterviewType() + " interview for the " + requisition.getTitle() + " position to " + slotDisplay + ".\n\nPlease confirm your availability.\n\nBest regards,\nHuman Resources\nArvato Systems Malaysia Sdn. Bhd.";
        }

        interview.setConfirmedSlot(slot);
        interview.setStatus("CONFIRMED");
        interview.setInvitationEmail(invitationEmail);
        interview.setCandidateReply(null);
        interview.setCandidateRepliedAt(null);
        interviewScheduleRepository.save(interview);

        return InterviewScheduledResponse.builder()
                .interviewId(interview.getId())
                .candidateName(candidate.getFullName())
                .candidateEmail(candidate.getEmail())
                .requisitionTitle(requisition.getTitle())
                .interviewType(interview.getInterviewType())
                .confirmedSlot(interview.getConfirmedSlot())
                .status(interview.getStatus())
                .pipelineStage("INTERVIEW_SCHEDULED")
                .invitationEmail(invitationEmail)
                .build();
    }

    public void sendInvitationEmail(Long interviewId) {
        InterviewSchedule interview = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        Candidate candidate = candidateRepository.findById(interview.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(interview.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        if (candidate.getEmail() == null || candidate.getEmail().isBlank())
            throw new IllegalStateException("Candidate has no email address on file");
        if (interview.getInvitationEmail() == null || interview.getInvitationEmail().isBlank())
            throw new IllegalStateException("No invitation email draft found — schedule the interview first");

        String subject = "Interview Invitation – " + requisition.getTitle();
        emailService.sendHtml(candidate.getEmail(), subject,
                emailService.wrapInTemplate(interview.getInvitationEmail(), "TA"));
    }

    public void updateInvitationEmail(Long interviewId, String invitationEmail) {
        if (invitationEmail == null || invitationEmail.isBlank())
            throw new IllegalArgumentException("Invitation email cannot be empty");
        InterviewSchedule interview = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        interview.setInvitationEmail(invitationEmail.trim());
        interviewScheduleRepository.save(interview);
    }

    public InterviewRoundResponse recordCandidateResponse(Long interviewId, String reply) {
        if (reply == null || reply.isBlank()) throw new IllegalArgumentException("Reply text cannot be empty");
        InterviewSchedule interview = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        interview.setCandidateReply(reply.trim());
        interview.setCandidateRepliedAt(LocalDateTime.now());
        interviewScheduleRepository.save(interview);

        List<InterviewSchedule> rounds = interviewScheduleRepository
                .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(interview.getCandidateId(), interview.getRequisitionId());
        int roundNumber = 0;
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).getId().equals(interview.getId())) { roundNumber = i + 1; break; }
        }
        return InterviewRoundResponse.builder()
                .id(interview.getId()).interviewType(interview.getInterviewType())
                .confirmedSlot(interview.getConfirmedSlot()).status(interview.getStatus())
                .notes(interview.getNotes()).roundNumber(roundNumber)
                .invitationEmail(interview.getInvitationEmail())
                .candidateReply(interview.getCandidateReply())
                .candidateRepliedAt(interview.getCandidateRepliedAt())
                .build();
    }

    public InterviewRoundResponse simulateCandidateResponse(Long interviewId) {
        InterviewSchedule interview = interviewScheduleRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));
        Candidate candidate = candidateRepository.findById(interview.getCandidateId()).orElse(null);
        JobRequisition requisition = jobRequisitionRepository.findById(interview.getRequisitionId()).orElse(null);

        String slotDisplay = interview.getConfirmedSlot() != null
                ? interview.getConfirmedSlot().toString().replace("T", " at ") : "TBD";
        String reply;
        try {
            String prompt = """
                    You are simulating a job candidate replying to an interview invitation email.
                    Candidate name: %s
                    Role applied for: %s
                    Interview type: %s
                    Scheduled date/time: %s
                    Write a short, realistic candidate reply email confirming attendance (or politely asking to reschedule if the time is inconvenient).
                    Start with "Dear Hiring Team," and sign off with the candidate's name.
                    Keep it to 3-4 sentences.
                    """.formatted(
                    candidate != null ? candidate.getFullName() : "Candidate",
                    requisition != null ? requisition.getTitle() : "the role",
                    interview.getInterviewType(),
                    slotDisplay);
            reply = claudeApiClient.sendPrompt("You are simulating a job candidate replying to a recruitment email. Write realistic, natural responses.", prompt);
        } catch (Exception e) {
            reply = "Dear Hiring Team,\n\nThank you for the invitation. I am happy to confirm my availability for the " + interview.getInterviewType() + " interview on " + slotDisplay + ".\n\nLooking forward to speaking with you.\n\nBest regards,\n" + (candidate != null ? candidate.getFullName() : "Candidate");
        }

        interview.setCandidateReply(reply);
        interview.setCandidateRepliedAt(LocalDateTime.now());
        interviewScheduleRepository.save(interview);

        List<InterviewSchedule> rounds = interviewScheduleRepository
                .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(interview.getCandidateId(), interview.getRequisitionId());
        int roundNumber = 0;
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).getId().equals(interview.getId())) { roundNumber = i + 1; break; }
        }

        return InterviewRoundResponse.builder()
                .id(interview.getId())
                .interviewType(interview.getInterviewType())
                .confirmedSlot(interview.getConfirmedSlot())
                .status(interview.getStatus())
                .notes(interview.getNotes())
                .roundNumber(roundNumber)
                .invitationEmail(interview.getInvitationEmail())
                .candidateReply(interview.getCandidateReply())
                .candidateRepliedAt(interview.getCandidateRepliedAt())
                .build();
    }

    public List<ActivityLogEntryResponse> getRecentActivity(String agentName, int limit) {
        List<AgentActivityLog> logs = (agentName != null && !agentName.isBlank())
                ? activityLogRepository.findByAgentNameOrderByCreatedAtDesc(agentName.toUpperCase(), PageRequest.of(0, limit))
                : activityLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));

        return logs.stream().map(this::toActivityResponse).toList();
    }

    private ActivityLogEntryResponse toActivityResponse(AgentActivityLog log) {
        String candidateName = null;
        if (log.getCandidateId() != null) {
            candidateName = candidateRepository.findById(log.getCandidateId())
                    .map(Candidate::getFullName).orElse(null);
        }
        String requisitionTitle = null;
        if (log.getRequisitionId() != null) {
            requisitionTitle = jobRequisitionRepository.findById(log.getRequisitionId())
                    .map(JobRequisition::getTitle).orElse(null);
        }

        return ActivityLogEntryResponse.builder()
                .id(log.getId())
                .agentName(log.getAgentName())
                .candidateId(log.getCandidateId())
                .candidateName(candidateName)
                .requisitionId(log.getRequisitionId())
                .requisitionTitle(requisitionTitle)
                .action(log.getAction())
                .details(log.getDetails())
                .createdAt(log.getCreatedAt())
                .build();
    }

    public DashboardSummaryResponse getDashboardSummary() {
        long openRequisitions = jobRequisitionRepository.findByStatus("OPEN").size();
        long totalCandidates = candidateRepository.count();

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long screenedToday = screeningResultRepository.countScreenedSince(startOfDay);
        long edgeCases = screeningResultRepository.countUnresolvedEdgeCases();
        long referralsInProgress = referralRepository.countInProgress();
        long interviewsScheduled = interviewScheduleRepository.countActiveInterviews();

        return DashboardSummaryResponse.builder()
                .openRequisitions(openRequisitions)
                .totalCandidates(totalCandidates)
                .candidatesScreenedToday(screenedToday)
                .edgeCasesAwaitingReview(edgeCases)
                .referralsInProgress(referralsInProgress)
                .interviewsScheduled(interviewsScheduled)
                .recentActivity(getRecentActivity(null, 10))
                .build();
    }
}
