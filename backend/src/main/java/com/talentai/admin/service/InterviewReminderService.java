package com.talentai.admin.service;

import com.talentai.admin.entity.InterviewReminder;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.InterviewReminderRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The interview coordinator: chases candidates who have not booked, reminds both sides before an
 * interview, and nudges interviewers whose scorecard is overdue. Each message goes out once
 * (see {@link InterviewReminder}) and is logged to the Coordination agent's activity feed.
 *
 * Runs on its own single-thread executor rather than @EnableScheduling, which would also switch on
 * the resume-source sync that is annotated @Scheduled but has never run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewReminderService {

    private static final String AGENT_NAME = "ADMIN";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM 'at' HH:mm", Locale.ENGLISH);
    /** Once a candidate is here the interview no longer needs chasing, reminding or scoring. */
    private static final Set<String> CLOSED_STAGES = Set.of("REJECTED", "HIRED", "NO_SHOW");

    private final InterviewScheduleRepository scheduleRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final InterviewReminderRepository reminderRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AppUserRepository appUserRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final InterviewBookingService bookingService;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String appBaseUrl;
    @Value("${app.interview-automation.enabled:true}")
    private boolean enabled;
    @Value("${app.interview-automation.interval-minutes:15}")
    private long intervalMinutes;
    @Value("${app.interview-automation.first-chase-hours:48}")
    private long firstChaseHours;
    @Value("${app.interview-automation.second-chase-hours:120}")
    private long secondChaseHours;
    @Value("${app.interview-automation.stalled-hours:168}")
    private long stalledHours;
    @Value("${app.interview-automation.reminder-hours-before:24}")
    private long reminderHoursBefore;
    @Value("${app.interview-automation.scorecard-nudge-hours:24}")
    private long scorecardNudgeHours;
    @Value("${app.interview-automation.scorecard-escalate-hours:72}")
    private long scorecardEscalateHours;

    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Interview automation is disabled");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "interview-coordinator");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::run, 1, intervalMinutes, TimeUnit.MINUTES);
        log.info("Interview automation runs every {} minutes", intervalMinutes);
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    private void run() {
        try {
            runAt(LocalDateTime.now());
        } catch (Throwable t) {
            // an uncaught exception would cancel every later run
            log.error("Interview automation run failed", t);
        }
    }

    public void runAt(LocalDateTime now) {
        for (InterviewSchedule iv : scheduleRepository.findByStatus("PROPOSED")) {
            guarded(iv, () -> chaseBooking(iv, now));
        }
        for (InterviewSchedule iv : scheduleRepository.findByStatus("CONFIRMED")) {
            guarded(iv, () -> {
                remind(iv, now);
                nudgeScorecard(iv, now);
            });
        }
    }

    private void guarded(InterviewSchedule iv, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.warn("Interview automation skipped interview {}: {}", iv.getId(), e.getMessage());
        }
    }

    // ---- Candidate has not booked ---------------------------------------------------------------

    private void chaseBooking(InterviewSchedule iv, LocalDateTime now) {
        Ctx c = context(iv);
        if (c == null || iv.getCreatedAt() == null) return;
        long hours = Duration.between(iv.getCreatedAt(), now).toHours();

        if (hours >= stalledHours) {
            if (claim(iv, "BOOKING_STALLED", "", null)) {
                String days = (hours / 24) + " days";
                alertHr(iv, "Interview not booked after " + days + " – " + c.candidate.getFullName(),
                        c.candidate.getFullName() + " has not booked their " + typeLabel(iv) + " interview for the "
                                + c.requisition.getTitle() + " position after " + days + ". "
                                + "Consider calling them, or withdrawing the invitation from the Pipeline.",
                        "INTERVIEW_NOT_BOOKED", "No booking after " + days + " – HR alerted");
            }
            return;
        }
        boolean second = hours >= secondChaseHours;
        if (!second && hours < firstChaseHours) return;
        String kind = second ? "BOOKING_CHASE_2" : "BOOKING_CHASE_1";
        if (reminderRepository.existsByInterviewIdAndKindAndRef(iv.getId(), kind, "")) return;

        if (bookingService.availableStarts(iv.getInterviewType(), iv.getInterviewerId(), iv.getId()).isEmpty()) {
            // nothing for the candidate to pick, so chasing them would only frustrate them
            if (claim(iv, "NO_SLOTS_ALERT", "", null)) {
                alertHr(iv, "No open interview times – " + c.candidate.getFullName(),
                        c.candidate.getFullName() + " cannot book their " + typeLabel(iv) + " interview for the "
                                + c.requisition.getTitle() + " position because no free time is published"
                                + (iv.getInterviewerId() != null ? " for the assigned interviewer" : "")
                                + ". Publish availability on the Calendar and the candidate will be reminded.",
                        "INTERVIEW_NO_SLOTS", "No free times to offer – HR alerted");
            }
            return;
        }
        if (!claim(iv, kind, "", c.candidate.getEmail())) return;

        String firstName = firstName(c.candidate.getFullName());
        String body = "Dear " + firstName + ",\n\n"
                + (second
                ? "We have not heard back about your " + typeLabel(iv) + " interview for the " + c.requisition.getTitle()
                  + " position, and we would still like to speak with you."
                : "A quick reminder to choose a time for your " + typeLabel(iv) + " interview for the "
                  + c.requisition.getTitle() + " position.")
                + "\n\nIt takes less than a minute, and you can pick any time that suits you:";
        String extra = "<div style='margin:16px 0;'><a href='" + bookUrl(iv) + "' "
                + "style='display:inline-block;padding:12px 24px;background:#6366F1;color:#fff;border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;'>"
                + "Choose Your Interview Time</a></div>"
                + "<p style='margin-top:24px;'>Best regards,<br/>Human Resources<br/>Arvato Systems Malaysia Sdn. Bhd.</p>";
        emailService.sendHtml(c.candidate.getEmail(), "Reminder: choose your interview time – " + c.requisition.getTitle(),
                emailService.wrapInTemplateWithExtra(body, "TA", extra));
        logActivity(iv, "BOOKING_CHASED", "Reminded " + c.candidate.getFullName() + " to book their " + typeLabel(iv)
                + " interview (" + (hours / 24) + " days without a booking)");
    }

    // ---- Interview is coming up -----------------------------------------------------------------

    private void remind(InterviewSchedule iv, LocalDateTime now) {
        LocalDateTime slot = iv.getConfirmedSlot();
        if (slot == null || !slot.isAfter(now) || slot.isAfter(now.plusHours(reminderHoursBefore))) return;
        Ctx c = context(iv);
        if (c == null) return;
        String ref = slot.toString();
        String when = slot.format(DISPLAY);

        if (c.candidate.getEmail() != null && !c.candidate.getEmail().isBlank()
                && claim(iv, "REMINDER_CANDIDATE", ref, c.candidate.getEmail())) {
            String body = "Dear " + firstName(c.candidate.getFullName()) + ",\n\n"
                    + "This is a reminder of your " + typeLabel(iv) + " interview for the " + c.requisition.getTitle()
                    + " position:\n\n" + when + " (" + bookingService.durationFor(iv.getInterviewType()) + " minutes)\n\n"
                    + "If you can no longer make it, please reply to this email as soon as possible.\n\n"
                    + "Best regards,\nHuman Resources\nArvato Systems Malaysia Sdn. Bhd.";
            emailService.sendHtml(c.candidate.getEmail(), "Reminder: your interview " + slot.format(SHORT)
                    + " – " + c.requisition.getTitle(), emailService.wrapInTemplate(body, "TA"));
            logActivity(iv, "INTERVIEW_REMINDER_SENT", "Reminded " + c.candidate.getFullName() + " of their " + typeLabel(iv)
                    + " interview on " + when);
        }

        AppUser interviewer = interviewer(iv);
        if (interviewer != null && interviewer.getEmail() != null && !interviewer.getEmail().isBlank()
                && claim(iv, "REMINDER_INTERVIEWER", ref, interviewer.getEmail())) {
            String body = "Hi " + firstName(interviewer.getFullName()) + ",\n\n"
                    + "You are interviewing " + c.candidate.getFullName() + " for the " + c.requisition.getTitle()
                    + " position.\n\nWhen: " + when + "\nType: " + typeLabel(iv) + " ("
                    + bookingService.durationFor(iv.getInterviewType()) + " minutes)\n"
                    + (iv.getNotes() != null && !iv.getNotes().isBlank() ? "Notes from recruiter: " + iv.getNotes() + "\n" : "")
                    + "\nYou can open the candidate's profile and resume from the Pipeline. "
                    + "After the interview, mark the round Done and submit your scorecard.";
            emailService.sendHtml(interviewer.getEmail(), "Reminder: interview " + slot.format(SHORT)
                    + " – " + c.candidate.getFullName(), emailService.wrapInTemplate(body, "TA"));
            logActivity(iv, "INTERVIEW_REMINDER_SENT", "Reminded " + interviewer.getFullName() + " of the interview with "
                    + c.candidate.getFullName() + " on " + when);
        }
    }

    // ---- Interview happened but no scorecard ----------------------------------------------------

    private void nudgeScorecard(InterviewSchedule iv, LocalDateTime now) {
        LocalDateTime slot = iv.getConfirmedSlot();
        if (slot == null) return;
        LocalDateTime ended = slot.plusMinutes(bookingService.durationFor(iv.getInterviewType()));
        long hours = Duration.between(ended, now).toHours();
        if (hours < scorecardNudgeHours) return;
        if (scorecardRepository.findByInterviewId(iv.getId()).isPresent()) return;
        Ctx c = context(iv);
        if (c == null) return;
        String ref = slot.toString();

        if (hours >= scorecardEscalateHours) {
            if (claim(iv, "SCORECARD_ESCALATION", ref, null)) {
                AppUser who = interviewer(iv);
                alertHr(iv, "Scorecard overdue – " + c.candidate.getFullName(),
                        "The " + typeLabel(iv) + " interview with " + c.candidate.getFullName() + " for the "
                                + c.requisition.getTitle() + " position ended " + (hours / 24) + " days ago and "
                                + (who != null ? who.getFullName() + " has" : "the interviewer has") + " not submitted a scorecard. "
                                + "The candidate cannot move forward until it is submitted.",
                        "SCORECARD_ESCALATED", "Scorecard overdue by " + (hours / 24) + " days – HR alerted");
            }
            return;
        }
        if (reminderRepository.existsByInterviewIdAndKindAndRef(iv.getId(), "SCORECARD_NUDGE", ref)) return;
        AppUser interviewer = interviewer(iv);
        boolean toInterviewer = interviewer != null && interviewer.getEmail() != null && !interviewer.getEmail().isBlank();
        if (!claim(iv, "SCORECARD_NUDGE", ref, toInterviewer ? interviewer.getEmail() : null)) return;

        String body = (toInterviewer ? "Hi " + firstName(interviewer.getFullName()) + ",\n\n" : "Hello,\n\n")
                + "Your " + typeLabel(iv) + " interview with " + c.candidate.getFullName() + " for the "
                + c.requisition.getTitle() + " position took place on " + slot.format(DISPLAY) + ", and the scorecard is still missing.\n\n"
                + "Please open the candidate in the Pipeline, choose Done on the round and submit your scorecard "
                + "while the interview is fresh. The candidate cannot move forward until it is in.";
        String subject = "Scorecard needed – " + c.candidate.getFullName() + " (" + c.requisition.getTitle() + ")";
        if (toInterviewer) {
            emailService.sendHtml(interviewer.getEmail(), subject, emailService.wrapInTemplate(body, "TA"));
        } else {
            emailService.notifyHr(subject, body + "\n\nNo interviewer is assigned to this round.", "");
        }
        logActivity(iv, "SCORECARD_NUDGED", "Asked " + (toInterviewer ? interviewer.getFullName() : "HR")
                + " for the missing scorecard on " + c.candidate.getFullName() + "'s " + typeLabel(iv) + " interview");
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private record Ctx(Candidate candidate, JobRequisition requisition) {}

    /** The candidate and role behind an interview, or null when it no longer needs automation. */
    private Ctx context(InterviewSchedule iv) {
        Candidate candidate = candidateRepository.findById(iv.getCandidateId()).orElse(null);
        JobRequisition requisition = requisitionRepository.findById(iv.getRequisitionId()).orElse(null);
        if (candidate == null || requisition == null) return null;
        String stage = pipelineStageRepository.findByCandidateIdAndRequisitionId(iv.getCandidateId(), iv.getRequisitionId())
                .map(PipelineStage::getStage).orElse(null);
        if (stage != null && CLOSED_STAGES.contains(stage)) return null;
        return new Ctx(candidate, requisition);
    }

    private AppUser interviewer(InterviewSchedule iv) {
        return iv.getInterviewerId() == null ? null : appUserRepository.findById(iv.getInterviewerId()).orElse(null);
    }

    /** Records the message before it is sent; false when it was already sent (or another run just claimed it). */
    private boolean claim(InterviewSchedule iv, String kind, String ref, String recipient) {
        if (reminderRepository.existsByInterviewIdAndKindAndRef(iv.getId(), kind, ref)) return false;
        try {
            reminderRepository.save(InterviewReminder.builder().interviewId(iv.getId()).kind(kind).ref(ref)
                    .recipientEmail(recipient).sentAt(LocalDateTime.now()).build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private void alertHr(InterviewSchedule iv, String subject, String body, String action, String details) {
        emailService.notifyHr(subject, body, "");
        logActivity(iv, action, details);
    }

    private void logActivity(InterviewSchedule iv, String action, String details) {
        activityLogRepository.save(AgentActivityLog.builder().agentName(AGENT_NAME)
                .candidateId(iv.getCandidateId()).requisitionId(iv.getRequisitionId())
                .action(action).details(details).build());
    }

    private String bookUrl(InterviewSchedule iv) {
        return (appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl)
                + "/interview-book?interviewId=" + iv.getId();
    }

    private static String typeLabel(InterviewSchedule iv) {
        return iv.getInterviewType().replace("_", " ").toLowerCase();
    }

    private static String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
    }
}
