package com.talentai.common.controller;

import com.talentai.common.entity.NotificationLog;
import com.talentai.common.repository.NotificationLogRepository;
import com.talentai.common.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Recruiter-facing visibility into candidate notification emails that
 * failed to send. The triggering pipeline/status change is never rolled
 * back on a send failure — this is purely a "notice and retry" surface.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;
    private final EmailService emailService;

    /** All failed notifications, optionally scoped to one requisition. */
    @GetMapping("/failed")
    public ResponseEntity<List<NotificationLog>> failed(@RequestParam(required = false) Long requisitionId) {
        List<NotificationLog> results = requisitionId != null
                ? notificationLogRepository.findByRequisitionIdAndStatusOrderByCreatedAtDesc(requisitionId, "FAILED")
                : notificationLogRepository.findByStatusOrderByCreatedAtDesc("FAILED");
        return ResponseEntity.ok(results);
    }

    /** Re-attempts sending a previously logged notification, verbatim. */
    @PostMapping("/{id}/resend")
    public ResponseEntity<Map<String, String>> resend(@PathVariable Long id) {
        if (!notificationLogRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        emailService.resendLoggedNotification(id);
        return ResponseEntity.ok(Map.of("status", "resend triggered"));
    }
}
