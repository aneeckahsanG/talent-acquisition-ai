package com.talentai.screening.service;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Screens direct applicants who were saved but never screened because the AI was unavailable or the
 * AI budget was used up. Runs on its own timer, waits while AI use is paused, and does a few per round
 * so catching up never causes a spike in spend.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnscreenedApplicantRetrier {

    private static final int MAX_PER_ROUND = 5;
    private static final int LOOKBACK_DAYS = 14;

    private final SourcingMatchRepository matchRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final ScreeningResultRepository screeningRepository;
    private final ScreeningAgentService screeningAgentService;
    private final AiBudgetGuard budgetGuard;

    @Value("${app.ai.retry-unscreened:true}")
    private boolean enabled;

    @Value("${app.ai.retry-interval-minutes:10}")
    private long intervalMinutes;

    private ScheduledExecutorService executor;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "unscreened-applicant-retrier");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runOnce, 2, intervalMinutes, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    /** One round. Public so it can be triggered directly (for example from a test). Returns how many were screened. */
    public int runOnce() {
        try {
            if (budgetGuard.isBlocked()) return 0;
            LocalDateTime since = LocalDateTime.now().minusDays(LOOKBACK_DAYS);
            List<SourcingMatch> waiting = matchRepository.findAll().stream()
                    .filter(m -> Boolean.FALSE.equals(m.getIsProactive()) && "APPLIED".equals(m.getStatus())
                            && m.getCreatedAt() != null && m.getCreatedAt().isAfter(since))
                    .filter(m -> screeningRepository.findByCandidateIdAndRequisitionId(m.getCandidateId(), m.getRequisitionId()).isEmpty())
                    .limit(MAX_PER_ROUND).toList();
            int done = 0;
            for (SourcingMatch m : waiting) {
                if (budgetGuard.isBlocked()) break;
                var candidate = candidateRepository.findById(m.getCandidateId()).orElse(null);
                var requisition = requisitionRepository.findById(m.getRequisitionId()).orElse(null);
                if (candidate == null || requisition == null || !"OPEN".equals(requisition.getStatus())) continue;
                if (candidate.getResumeText() == null || candidate.getResumeText().isBlank()) continue;
                screeningAgentService.runScreeningInternal(candidate, requisition);
                if (screeningRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId()).isPresent()) done++;
            }
            if (done > 0) log.info("Screened {} previously unscreened applicant(s)", done);
            return done;
        } catch (Exception e) {
            log.warn("Retry of unscreened applicants failed: {}", e.getMessage());
            return 0;
        }
    }
}
