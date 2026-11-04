package com.talentai.common.controller;

import com.talentai.admin.entity.CandidateStatusUpdate;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.CandidateStatusUpdateRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.referral.entity.Referral;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.entity.PipelineStageHistory;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageHistoryRepository;
import com.talentai.common.repository.PipelineStageRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateRepository candidateRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final CandidateStatusUpdateRepository candidateStatusUpdateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final InterviewScheduleRepository interviewScheduleRepository;
    private final com.talentai.admin.repository.InterviewScorecardRepository scorecardRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final ReferralRepository referralRepository;
    private final PipelineStageHistoryRepository stageHistoryRepository;

    @GetMapping
    public ResponseEntity<List<CandidateSummary>> getAll() {
        return ResponseEntity.ok(candidateRepository.findAll().stream()
                .map(this::toSummary)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CandidateSummary> getById(@PathVariable Long id) {
        Candidate candidate = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));
        return ResponseEntity.ok(toSummary(candidate));
    }

    /** Corrects a candidate's contact email (e.g. a typo on application). */
    @PatchMapping("/{id}")
    public ResponseEntity<CandidateSummary> update(@PathVariable Long id, @RequestBody UpdateCandidateRequest request) {
        Candidate candidate = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));

        if (request.getEmail() != null) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (newEmail.isBlank()) {
                throw new IllegalArgumentException("Email cannot be blank.");
            }
            if (!newEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new IllegalArgumentException("Not a valid email address.");
            }
            if (!newEmail.equals(candidate.getEmail())) {
                candidateRepository.findFirstByEmailOrderByIdAsc(newEmail).ifPresent(existing -> {
                    if (!existing.getId().equals(id)) {
                        throw new IllegalArgumentException(
                                "Another candidate (" + existing.getFullName() + ") already uses this email.");
                    }
                });
                candidate.setEmail(newEmail);
            }
        }

        candidate = candidateRepository.save(candidate);
        return ResponseEntity.ok(toSummary(candidate));
    }

    private CandidateSummary toSummary(Candidate c) {
        return CandidateSummary.builder()
                .id(c.getId())
                .fullName(c.getFullName())
                .email(c.getEmail())
                .phone(c.getPhone())
                .sourceChannel(c.getSourceChannel())
                .headline(c.getHeadline())
                .skills(c.getSkills())
                .yearsExperience(c.getYearsExperience())
                .resumeFilename(c.getResumeFilename())
                .profileUrl(c.getProfileUrl())
                .createdAt(c.getCreatedAt())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateSummary {
        private Long id;
        private String fullName;
        private String email;
        private String phone;
        private String sourceChannel;
        private String headline;
        private String skills;
        private BigDecimal yearsExperience;
        private String resumeFilename;
        private String profileUrl;
        private LocalDateTime createdAt;
    }

    /** Returns full application timeline for a candidate, oldest first:
     *  every pipeline stage change, referrals, interviews and notification messages. */
    @GetMapping("/{id}/history")
    public ResponseEntity<CandidateHistory> getHistory(@PathVariable Long id) {
        candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));

        // Build requisition title lookup
        Map<Long, String> reqTitles = pipelineStageRepository.findByCandidateId(id).stream()
                .map(PipelineStage::getRequisitionId)
                .distinct()
                .collect(Collectors.toMap(rid -> rid,
                        rid -> jobRequisitionRepository.findById(rid)
                                .map(r -> r.getTitle()).orElse("Unknown Role")));

        List<HistoryEvent> events = new ArrayList<>();

        // Every stage change, as it happened. The pipeline row only holds the current stage, so the log is the record.
        List<PipelineStageHistory> stageLog = stageHistoryRepository.findByCandidateIdOrderByOccurredAtAscIdAsc(id);
        stageLog.forEach(h -> events.add(HistoryEvent.builder()
                .type("STAGE")
                .timestamp(h.getOccurredAt())
                .stage(h.getToStage())
                .fromStage(h.getFromStage())
                .requisitionId(h.getRequisitionId())
                .requisitionTitle(reqTitles.getOrDefault(h.getRequisitionId(), "Unknown Role"))
                .agent(h.getChangedBy())
                .notes(h.getNotes())
                .build()));

        // A role with no log yet (it predates the log) still shows where the candidate is now
        pipelineStageRepository.findByCandidateId(id).stream()
                .filter(ps -> stageLog.stream().noneMatch(h -> h.getRequisitionId().equals(ps.getRequisitionId())))
                .forEach(ps -> events.add(HistoryEvent.builder()
                        .type("STAGE")
                        .timestamp(ps.getEnteredAt())
                        .stage(ps.getStage())
                        .requisitionId(ps.getRequisitionId())
                        .requisitionTitle(reqTitles.getOrDefault(ps.getRequisitionId(), "Unknown Role"))
                        .agent(ps.getUpdatedByAgent())
                        .notes(ps.getNotes())
                        .build()));

        // Referrals. The pipeline row for a referred candidate moves on to SCREENED as soon as the match is
        // screened, so the referral itself is the only record of how the candidate arrived.
        referralRepository.findByCandidateId(id).forEach(r -> events.add(referralEvent(r)));

        // Interview schedules
        interviewScheduleRepository.findByCandidateId(id).forEach(iv -> {
            String reqTitle = reqTitles.getOrDefault(iv.getRequisitionId(),
                    jobRequisitionRepository.findById(iv.getRequisitionId())
                            .map(r -> r.getTitle()).orElse("Unknown Role"));
            var sc = scorecardRepository.findByInterviewId(iv.getId()).orElse(null);
            String label = iv.getInterviewType() + " interview — " + iv.getStatus();
            if (iv.getConfirmedSlot() != null) {
                label += " on " + iv.getConfirmedSlot().toLocalDate();
            }
            events.add(HistoryEvent.builder()
                    .type("INTERVIEW")
                    .timestamp(iv.getCreatedAt())
                    .requisitionId(iv.getRequisitionId())
                    .requisitionTitle(reqTitle)
                    .interviewType(iv.getInterviewType())
                    .interviewStatus(iv.getStatus())
                    .confirmedSlot(iv.getConfirmedSlot())
                    .notes(iv.getNotes())
                    .verdict(sc != null ? sc.getVerdict() : null)
                    .averageRating(sc != null ? sc.averageRating() : null)
                    .feedbackNotes(sc != null ? sc.getNotes() : null)
                    .build());
        });

        // Status notification messages
        candidateStatusUpdateRepository.findByCandidateIdOrderBySentAtDesc(id).forEach(su ->
                events.add(HistoryEvent.builder()
                        .type("NOTIFICATION")
                        .timestamp(su.getSentAt())
                        .channel(su.getChannel())
                        .message(su.getMessage())
                        .build()));

        // Oldest first; the sort is stable, so events at the same instant keep the order they were added in
        events.sort(Comparator.comparing(HistoryEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));

        return ResponseEntity.ok(CandidateHistory.builder()
                .candidateId(id)
                .events(events)
                .build());
    }

    private HistoryEvent referralEvent(Referral r) {
        Long roleId = r.getMatchedRequisitionId() != null ? r.getMatchedRequisitionId() : r.getRequisitionId();
        String roleTitle = roleId == null ? null
                : jobRequisitionRepository.findById(roleId).map(x -> x.getTitle()).orElse("Unknown Role");

        String by = r.getReferrerName() != null && !r.getReferrerName().isBlank() ? r.getReferrerName() : "an employee";
        StringBuilder notes = new StringBuilder("Referred by ").append(by);
        if (r.getReferrerPosition() != null && !r.getReferrerPosition().isBlank()) {
            notes.append(" (").append(r.getReferrerPosition()).append(")");
        }
        if (r.getMatchedRequisitionId() != null) {
            // The referrer's own choice, or the role the Referral Agent picked. Scores live in the screening.
            boolean chosenByReferrer = r.getMatchedRequisitionId().equals(r.getRequisitionId());
            notes.append(chosenByReferrer ? ". Referred for " : ". Routed to ").append(roleTitle);
        } else {
            notes.append(". No open role matched yet");
        }
        if (r.getRelationshipNotes() != null && !r.getRelationshipNotes().isBlank()) {
            notes.append(". \"").append(r.getRelationshipNotes().trim()).append("\"");
        }

        return HistoryEvent.builder()
                .type("REFERRAL")
                .timestamp(r.getSubmittedAt())
                .requisitionId(roleId)
                .requisitionTitle(roleTitle)
                .notes(notes.toString())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateHistory {
        private Long candidateId;
        private List<HistoryEvent> events;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryEvent {
        private String type;           // STAGE | REFERRAL | INTERVIEW | NOTIFICATION
        private LocalDateTime timestamp;
        // STAGE fields
        private String stage;
        private String fromStage;
        private Long requisitionId;
        private String requisitionTitle;
        private String agent;
        private String notes;
        // NOTIFICATION fields
        private String channel;
        private String message;
        // INTERVIEW fields
        private String interviewType;
        private String interviewStatus;
        private LocalDateTime confirmedSlot;
        private String verdict;
        private Double averageRating;
        private String feedbackNotes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateCandidateRequest {
        private String email;
    }
}
