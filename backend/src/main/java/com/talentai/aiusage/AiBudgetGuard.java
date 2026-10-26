package com.talentai.aiusage;

import com.talentai.common.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Keeps a running total of today's and this month's AI spend, warns when a limit is nearly used up,
 * and (when "hard stop" is on) refuses further AI calls once a limit is reached.
 *
 * Totals are held in memory and reloaded from the usage log at startup and at each new day or month.
 * Calls already in flight when the limit is crossed still complete, so spend can overshoot slightly.
 * With more than one server instance each would track its own total: move the totals to a shared store first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiBudgetGuard {

    private static final long BUDGET_ROW = 1L;

    private final AiBudgetRepository budgetRepository;
    private final AiUsageLogRepository usageRepository;
    private final EmailService emailService;

    private double daySpend;
    private double monthSpend;
    private LocalDate dayKey;
    private YearMonth monthKey;
    private final java.util.Set<String> notified = new java.util.HashSet<>();

    public record Status(Double dailyLimit, Double monthlyLimit, double dailySpent, double monthlySpent,
                         Double dailyPercent, Double monthlyPercent, int warnPercent, boolean hardStop, String state) {}

    /** Throws if a limit has been reached and hard stop is on. Called before every AI call. */
    public synchronized void checkAllowed() {
        roll();
        AiBudget b = budget();
        if (!b.isHardStop()) return;
        if (b.getDailyLimitUsd() != null && daySpend >= b.getDailyLimitUsd().doubleValue()) {
            notifyOnce("blocked-day-" + dayKey, "AI paused: daily budget reached",
                    "AI calls are paused for today because the daily limit of US$" + b.getDailyLimitUsd() + " has been reached. "
                            + "New applications are still saved; they are screened once AI use resumes tomorrow, or when an admin raises the limit.");
            throw new AiBudgetExceededException("The daily AI budget of US$" + b.getDailyLimitUsd().stripTrailingZeros().toPlainString() + " has been reached.");
        }
        if (b.getMonthlyLimitUsd() != null && monthSpend >= b.getMonthlyLimitUsd().doubleValue()) {
            notifyOnce("blocked-month-" + monthKey, "AI paused: monthly budget reached",
                    "AI calls are paused for the rest of the month because the monthly limit of US$" + b.getMonthlyLimitUsd() + " has been reached. "
                            + "New applications are still saved; an admin can raise the limit to resume sooner.");
            throw new AiBudgetExceededException("The monthly AI budget of US$" + b.getMonthlyLimitUsd().stripTrailingZeros().toPlainString() + " has been reached.");
        }
    }

    /** True when AI calls would currently be refused. Used by background work to wait instead of failing. */
    public synchronized boolean isBlocked() {
        try {
            checkAllowed();
            return false;
        } catch (AiBudgetExceededException e) {
            return true;
        }
    }

    /** Adds the cost of a finished call to the running totals and sends a warning when a threshold is crossed. */
    public synchronized void addSpend(double cost) {
        roll();
        daySpend += cost;
        monthSpend += cost;
        AiBudget b = budget();
        warnIfNeeded(b.getDailyLimitUsd(), daySpend, b.getWarnPercent(), "day-" + dayKey, "daily");
        warnIfNeeded(b.getMonthlyLimitUsd(), monthSpend, b.getWarnPercent(), "month-" + monthKey, "monthly");
    }

    public synchronized Status status() {
        roll();
        AiBudget b = budget();
        Double dl = b.getDailyLimitUsd() != null ? b.getDailyLimitUsd().doubleValue() : null;
        Double ml = b.getMonthlyLimitUsd() != null ? b.getMonthlyLimitUsd().doubleValue() : null;
        Double dp = dl != null && dl > 0 ? round(100.0 * daySpend / dl) : null;
        Double mp = ml != null && ml > 0 ? round(100.0 * monthSpend / ml) : null;
        String state;
        boolean over = (dp != null && dp >= 100) || (mp != null && mp >= 100);
        boolean warn = (dp != null && dp >= b.getWarnPercent()) || (mp != null && mp >= b.getWarnPercent());
        if (dl == null && ml == null) state = "NO_LIMIT";
        else if (over) state = b.isHardStop() ? "BLOCKED" : "OVER_LIMIT";
        else if (warn) state = "WARNING";
        else state = "OK";
        return new Status(dl, ml, round(daySpend), round(monthSpend), dp, mp, b.getWarnPercent(), b.isHardStop(), state);
    }

    public synchronized Status save(BigDecimal daily, BigDecimal monthly, int warnPercent, boolean hardStop) {
        AiBudget b = budget();
        b.setDailyLimitUsd(daily);
        b.setMonthlyLimitUsd(monthly);
        b.setWarnPercent(warnPercent);
        b.setHardStop(hardStop);
        b.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(b);
        notified.clear();   // a changed limit deserves fresh warnings
        return status();
    }

    private AiBudget budget() {
        return budgetRepository.findById(BUDGET_ROW).orElseGet(() ->
                AiBudget.builder().id(BUDGET_ROW).warnPercent(80).hardStop(true).build());
    }

    /** Reloads the totals from the usage log at startup and whenever the day or month changes. */
    private void roll() {
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.from(today);
        if (!today.equals(dayKey) || !month.equals(monthKey)) {
            dayKey = today;
            monthKey = month;
            daySpend = usageRepository.sumCostSince(today.atStartOfDay()).doubleValue();
            monthSpend = usageRepository.sumCostSince(month.atDay(1).atStartOfDay()).doubleValue();
            notified.removeIf(k -> !k.endsWith(today.toString()) && !k.endsWith(month.toString()));
        }
    }

    private void warnIfNeeded(BigDecimal limit, double spend, int warnPercent, String key, String label) {
        if (limit == null || limit.doubleValue() <= 0) return;
        double percent = 100.0 * spend / limit.doubleValue();
        if (percent >= warnPercent && percent < 100) {
            notifyOnce("warn-" + key, "AI budget " + Math.round(percent) + "% used (" + label + ")",
                    "AI spend has reached " + Math.round(percent) + "% of the " + label + " limit of US$" + limit.stripTrailingZeros().toPlainString()
                            + ". Calls will pause when it reaches 100%.");
        }
    }

    private void notifyOnce(String key, String subject, String body) {
        if (!notified.add(key)) return;
        try {
            emailService.notifyHr(subject, body, "");
        } catch (Exception e) {
            log.warn("Could not send budget notice '{}': {}", subject, e.getMessage());
        }
        log.warn("AI budget: {}", subject);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
