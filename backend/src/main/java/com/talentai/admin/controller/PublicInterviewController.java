package com.talentai.admin.controller;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Public booking endpoints: the candidate picks a free slot from the shared calendar. */
@RestController
@RequestMapping("/api/public/interview")
@RequiredArgsConstructor
@Slf4j
public class PublicInterviewController {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);

    private final InterviewScheduleRepository interviewScheduleRepository;
    private final InterviewBookingService bookingService;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final EmailService emailService;
    private final com.talentai.common.repository.AppUserRepository appUserRepository;

    @GetMapping("/{interviewId}/slots")
    public ResponseEntity<Map<String, Object>> slots(@PathVariable Long interviewId) {
        InterviewSchedule iv = interviewScheduleRepository.findById(interviewId).orElse(null);
        if (iv == null) return ResponseEntity.status(404).body(Map.of("error", "This booking link is not valid."));

        Candidate candidate = candidateRepository.findById(iv.getCandidateId()).orElse(null);
        JobRequisition req = jobRequisitionRepository.findById(iv.getRequisitionId()).orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateName", candidate != null ? candidate.getFullName() : "");
        body.put("roleTitle", req != null ? req.getTitle() : "");
        body.put("interviewType", iv.getInterviewType());
        body.put("status", iv.getStatus());
        if (iv.getInterviewerId() != null) {
            appUserRepository.findById(iv.getInterviewerId()).ifPresent(u -> body.put("interviewerName", u.getFullName()));
        }
        if (iv.getConfirmedSlot() != null) body.put("confirmedSlot", iv.getConfirmedSlot().toString());

        int dur = bookingService.durationFor(iv.getInterviewType());
        body.put("durationMinutes", dur);
        List<Map<String, Object>> open = new ArrayList<>();
        if (!"CONFIRMED".equals(iv.getStatus()) && !"COMPLETED".equals(iv.getStatus())) {
            for (LocalDateTime t : bookingService.availableStarts(iv.getInterviewType(), iv.getInterviewerId(), interviewId)) {
                open.add(Map.of("start", t.toString(), "end", t.plusMinutes(dur).toString()));
            }
        }
        body.put("slots", open);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{interviewId}/book")
    public ResponseEntity<Map<String, Object>> book(@PathVariable Long interviewId, @RequestBody Map<String, Object> payload) {
        InterviewSchedule iv = interviewScheduleRepository.findById(interviewId).orElse(null);
        if (iv == null) return ResponseEntity.status(404).body(Map.of("error", "This booking link is not valid."));
        if ("CONFIRMED".equals(iv.getStatus()) || "COMPLETED".equals(iv.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", "Your interview is already booked."));
        }

        LocalDateTime start;
        try {
            start = LocalDateTime.parse(String.valueOf(payload.get("start")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please select a time."));
        }
        java.util.Optional<com.talentai.admin.entity.InterviewSlot> booked;
        try {
            booked = bookingService.book(interviewId, iv.getInterviewType(), iv.getInterviewerId(), start);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // lost a race for the same interviewer and time
            booked = java.util.Optional.empty();
        }
        if (booked.isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("error", "That time is no longer available. Please choose another."));
        }

        iv.setConfirmedSlot(start);
        iv.setStatus("CONFIRMED");
        interviewScheduleRepository.save(iv);

        pipelineStageRepository.findByCandidateIdAndRequisitionId(iv.getCandidateId(), iv.getRequisitionId())
                .ifPresent(ps -> {
                    ps.setStage("INTERVIEW_SCHEDULED");
                    ps.setUpdatedByAgent("HUMAN");
                    ps.setEnteredAt(LocalDateTime.now());
                    pipelineStageRepository.save(ps);
                });

        Candidate candidate = candidateRepository.findById(iv.getCandidateId()).orElse(null);
        JobRequisition req = jobRequisitionRepository.findById(iv.getRequisitionId()).orElse(null);
        String roleName = req != null ? req.getTitle() : "the role";
        String display = start.format(DISPLAY);

        if (candidate != null && candidate.getEmail() != null && !candidate.getEmail().isBlank()) {
            String body = "Dear " + candidate.getFullName().trim().split("\\s+")[0] + ",\n\n"
                    + "Your " + iv.getInterviewType().replace("_", " ").toLowerCase() + " interview for the " + roleName
                    + " position is confirmed for:\n\n" + display + "\n\n"
                    + "We look forward to speaking with you.\n\n"
                    + "Best regards,\nHuman Resources\nArvato Systems Malaysia Sdn. Bhd.";
            emailService.sendHtml(candidate.getEmail(), "Interview Confirmed – " + roleName,
                    emailService.wrapInTemplate(body, "TA"));
        }

        // Tell the interviewer, with enough context to prepare
        if (iv.getInterviewerId() != null) {
            appUserRepository.findById(iv.getInterviewerId()).ifPresent(u -> {
                if (u.getEmail() == null || u.getEmail().isBlank()) return;
                String cv = candidate != null && candidate.getResumeText() != null ? candidate.getResumeText() : "";
                if (cv.length() > 600) cv = cv.substring(0, 600) + "…";
                String body = "Hi " + u.getFullName().trim().split("\\s+")[0] + ",\n\n"
                        + "You have an interview booked.\n\n"
                        + "Candidate: " + (candidate != null ? candidate.getFullName() : "Unknown") + "\n"
                        + "Role: " + roleName + "\n"
                        + "Type: " + iv.getInterviewType().replace("_", " ").toLowerCase() + " (" + bookingService.durationFor(iv.getInterviewType()) + " minutes)\n"
                        + "When: " + display + "\n"
                        + (iv.getNotes() != null && !iv.getNotes().isBlank() ? "Notes from recruiter: " + iv.getNotes() + "\n" : "")
                        + (cv.isBlank() ? "" : "\nCV summary:\n" + cv);
                emailService.sendHtml(u.getEmail(), "Interview booked – " + (candidate != null ? candidate.getFullName() : "candidate") + " (" + roleName + ")",
                        emailService.wrapInTemplate(body, "TA"));
            });
        }

        log.info("Interview {} booked for {}", interviewId, start);
        return ResponseEntity.ok(Map.of("success", true, "slot", start.toString(), "display", display));
    }
}
