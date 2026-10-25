package com.talentai.common.controller;

import com.talentai.admin.entity.CandidateStatusUpdate;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.CandidateStatusUpdateRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
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

    /** Returns full application timeline for a candidate:
     *  all pipeline stage transitions + notification messages, chronologically. */
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

        // Stage transitions — for SCREENED stages, enrich notes with score from ScreeningResult
        pipelineStageRepository.findByCandidateId(id).forEach(ps -> {
            String notes = ps.getNotes();
            if ("SCREENED".equals(ps.getStage())) {
                var sr = screeningResultRepository.findByCandidateIdAndRequisitionId(ps.getCandidateId(), ps.getRequisitionId());
                if (sr.isPresent()) {
                    notes = "Score: " + sr.get().getOverallScore() + " — " + sr.get().getRecommendation();
                }
            }
            events.add(HistoryEvent.builder()
                    .type("STAGE")
                    .timestamp(ps.getEnteredAt())
                    .stage(ps.getStage())
                    .requisitionId(ps.getRequisitionId())
                    .requisitionTitle(reqTitles.getOrDefault(ps.getRequisitionId(), "Unknown Role"))
                    .agent(ps.getUpdatedByAgent())
                    .notes(notes)
                    .build());
        });

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

        events.sort(Comparator.comparing(HistoryEvent::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return ResponseEntity.ok(CandidateHistory.builder()
                .candidateId(id)
                .events(events)
                .build());
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
        private String type;           // STAGE | NOTIFICATION
        private LocalDateTime timestamp;
        // STAGE fields
        private String stage;
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
