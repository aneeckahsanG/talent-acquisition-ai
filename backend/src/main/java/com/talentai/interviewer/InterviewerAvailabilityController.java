package com.talentai.interviewer;

import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.admin.service.InterviewBookingService;
import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** An interviewer publishes and removes their own availability. They can never touch anyone else's. */
@RestController
@RequestMapping("/api/interviewer/availability")
@RequiredArgsConstructor
public class InterviewerAvailabilityController {

    private final AvailabilityWindowRepository windowRepository;
    private final InterviewBookingService bookingService;
    private final AppUserRepository users;

    @Data
    public static class PublishRequest {
        private String fromDate;        // yyyy-MM-dd
        private String toDate;          // yyyy-MM-dd
        private boolean weekdaysOnly = true;
        private String startTime = "10:00";
        private String endTime = "17:00";
    }

    /** My upcoming windows. */
    @GetMapping
    public List<Map<String, Object>> mine(Principal principal) {
        Long me = currentUser(principal).getId();
        return windowRepository.findByEndTimeAfterOrderByStartTimeAsc(LocalDateTime.now()).stream()
                .filter(w -> me.equals(w.getInterviewerId()))
                .map(w -> Map.<String, Object>of("id", w.getId(), "start", w.getStartTime().toString(), "end", w.getEndTime().toString()))
                .toList();
    }

    @PostMapping
    public Map<String, Object> publish(@RequestBody PublishRequest req, Principal principal) {
        Long me = currentUser(principal).getId();
        int created = bookingService.publish(me, LocalDate.parse(req.getFromDate()), LocalDate.parse(req.getToDate()),
                req.isWeekdaysOnly(), LocalTime.parse(req.getStartTime()), LocalTime.parse(req.getEndTime()));
        return Map.of("created", created);
    }

    /** Someone else's window looks the same as a missing one, so ids cannot be probed. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable Long id, Principal principal) {
        var removed = bookingService.removeWindow(id, currentUser(principal).getId());
        if (removed.isEmpty()) return ResponseEntity.notFound().build();
        if (!removed.get()) {
            return ResponseEntity.status(409).body(Map.of("message", "You have an interview booked in this window. Ask the recruiting team to reschedule it first."));
        }
        return ResponseEntity.noContent().build();
    }

    private AppUser currentUser(Principal principal) {
        return users.findByUsername(principal.getName()).orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }
}
