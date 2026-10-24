package com.talentai.admin.controller;

import com.talentai.admin.entity.AvailabilityWindow;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewSlot;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewSlotRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.common.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/** Recruiter-facing shared interview calendar: publish availability windows, see bookings, release them. */
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final AvailabilityWindowRepository windowRepository;
    private final InterviewSlotRepository bookingRepository;
    private final InterviewScheduleRepository interviewRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final InterviewBookingService bookingService;
    private final EmailService emailService;

    @Value("${app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    @Data
    public static class GenerateRequest {
        private String fromDate;        // yyyy-MM-dd
        private String toDate;          // yyyy-MM-dd
        private boolean weekdaysOnly = true;
        private String startTime = "10:00";
        private String endTime = "17:00";
    }

    @GetMapping("/durations")
    public Map<String, Integer> durations() {
        return InterviewBookingService.DURATIONS;
    }

    @GetMapping("/slots")
    public Map<String, Object> slots(@RequestParam String from, @RequestParam String to) {
        LocalDateTime start = LocalDate.parse(from).atStartOfDay();
        LocalDateTime end = LocalDate.parse(to).plusDays(1).atStartOfDay();

        List<Map<String, Object>> windows = new ArrayList<>();
        for (AvailabilityWindow w : windowRepository.findOverlapping(start, end)) {
            windows.add(Map.of("id", w.getId(), "start", w.getStartTime().toString(), "end", w.getEndTime().toString()));
        }

        List<Map<String, Object>> bookings = new ArrayList<>();
        for (InterviewSlot b : bookingRepository.findOverlapping(start, end)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("start", b.getStartTime().toString());
            m.put("end", b.getEndTime().toString());
            InterviewSchedule iv = interviewRepository.findById(b.getInterviewId()).orElse(null);
            if (iv != null) {
                m.put("interviewId", iv.getId());
                m.put("interviewType", iv.getInterviewType());
                m.put("candidateName", candidateRepository.findById(iv.getCandidateId())
                        .map(c -> c.getFullName()).orElse("Unknown"));
                m.put("roleTitle", requisitionRepository.findById(iv.getRequisitionId())
                        .map(r -> r.getTitle()).orElse("Unknown Role"));
            }
            bookings.add(m);
        }
        return Map.of("windows", windows, "bookings", bookings);
    }

    @PostMapping("/windows/generate")
    public Map<String, Object> generate(@RequestBody GenerateRequest req) {
        LocalDate from = LocalDate.parse(req.getFromDate());
        LocalDate to = LocalDate.parse(req.getToDate());
        if (to.isBefore(from) || from.plusDays(60).isBefore(to)) {
            throw new IllegalArgumentException("Date range must be valid and at most 60 days.");
        }
        LocalTime dayStart = LocalTime.parse(req.getStartTime());
        LocalTime dayEnd = LocalTime.parse(req.getEndTime());
        if (!dayEnd.isAfter(dayStart)) throw new IllegalArgumentException("End time must be after start time.");

        int created = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (req.isWeekdaysOnly() && (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)) continue;
            LocalDateTime s = d.atTime(dayStart);
            LocalDateTime e = d.atTime(dayEnd);
            if (!windowRepository.findOverlapping(s, e).isEmpty()) continue;
            windowRepository.save(AvailabilityWindow.builder().startTime(s).endTime(e).build());
            created++;
        }
        return Map.of("created", created);
    }

    @DeleteMapping("/windows/{id}")
    public ResponseEntity<?> deleteWindow(@PathVariable Long id) {
        AvailabilityWindow w = windowRepository.findById(id).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();
        if (!bookingRepository.findOverlapping(w.getStartTime(), w.getEndTime()).isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("message", "This day has booked interviews. Release them first."));
        }
        windowRepository.delete(w);
        return ResponseEntity.noContent().build();
    }

    /** Calendar page: reschedule the interview behind this booking. */
    @PostMapping("/bookings/{id}/release")
    public ResponseEntity<?> release(@PathVariable Long id) {
        InterviewSlot b = bookingRepository.findById(id).orElse(null);
        if (b == null) return ResponseEntity.notFound().build();
        InterviewSchedule iv = interviewRepository.findById(b.getInterviewId()).orElse(null);
        if (iv == null) {
            bookingRepository.delete(b);
            return ResponseEntity.noContent().build();
        }
        rescheduleAndEmail(iv);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pipeline card: if a time is booked, free it and email the candidate to pick a new one;
     * if nothing is booked yet, just resend the booking link as a reminder.
     */
    @PostMapping("/interviews/{interviewId}/reschedule")
    public ResponseEntity<?> rescheduleInterview(@PathVariable Long interviewId) {
        InterviewSchedule iv = interviewRepository.findById(interviewId).orElse(null);
        if (iv == null) return ResponseEntity.notFound().build();
        if ("COMPLETED".equals(iv.getStatus()) || "CANCELLED".equals(iv.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("message", "This interview is already " + iv.getStatus().toLowerCase() + "."));
        }
        rescheduleAndEmail(iv);
        return ResponseEntity.noContent().build();
    }

    private void rescheduleAndEmail(InterviewSchedule iv) {
        List<InterviewSlot> bookings = bookingRepository.findByInterviewId(iv.getId());
        LocalDateTime oldStart = !bookings.isEmpty() ? bookings.get(0).getStartTime() : iv.getConfirmedSlot();
        bookingRepository.deleteAll(bookings);
        iv.setConfirmedSlot(null);
        iv.setStatus("PROPOSED");
        interviewRepository.save(iv);

        var candidate = candidateRepository.findById(iv.getCandidateId()).orElse(null);
        if (candidate == null || candidate.getEmail() == null || candidate.getEmail().isBlank()) return;

        String role = requisitionRepository.findById(iv.getRequisitionId()).map(r -> r.getTitle()).orElse("the role");
        String type = iv.getInterviewType().replace("_", " ").toLowerCase();
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        String bookUrl = base + "/interview-book?interviewId=" + iv.getId();
        String first = candidate.getFullName().trim().split("\\s+")[0];

        String body;
        String subject;
        String label;
        if (oldStart != null) {
            String when = oldStart.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH));
            body = "Dear " + first + ",\n\nWe are sorry, but we need to reschedule your " + type + " interview for the "
                    + role + " position that was booked for " + when + ".\n\nPlease choose a new time that suits you:";
            subject = "Interview Rescheduling – Please Choose a New Time – " + role;
            label = "Choose a New Time";
        } else {
            body = "Dear " + first + ",\n\nThis is a reminder to choose a time for your " + type + " interview for the "
                    + role + " position:";
            subject = "Reminder – Please Choose Your Interview Time – " + role;
            label = "Choose Your Interview Time";
        }
        String button = "<div style='margin:16px 0;'><a href='" + bookUrl + "' "
                + "style='display:inline-block;padding:12px 24px;background:#6366F1;color:#fff;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;'>"
                + label + "</a></div>"
                + "<p style='margin-top:24px;'>Best regards,<br/>Human Resources<br/>Arvato Systems Malaysia Sdn. Bhd.</p>";
        emailService.sendHtml(candidate.getEmail(), subject, emailService.wrapInTemplateWithExtra(body, "TA", button));
    }
}
