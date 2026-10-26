package com.talentai.intelligence;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intelligence")
@RequiredArgsConstructor
public class IntelligenceController {

    private final IntelligenceService intelligenceService;
    private final ScreeningQualityService screeningQualityService;
    private final com.talentai.aiusage.AiUsageService aiUsageService;
    private final com.talentai.aiusage.AiBudgetGuard aiBudgetGuard;
    private final com.talentai.screening.service.UnscreenedApplicantRetrier unscreenedApplicantRetrier;

    public record BudgetRequest(java.math.BigDecimal dailyLimitUsd, java.math.BigDecimal monthlyLimitUsd, Integer warnPercent, Boolean hardStop) {}

    /** Sets the daily and monthly AI spending limits. Admins only. A blank limit means no limit. */
    @org.springframework.web.bind.annotation.PutMapping("/ai-budget")
    public org.springframework.http.ResponseEntity<?> saveBudget(@org.springframework.web.bind.annotation.RequestBody BudgetRequest r,
                                                                 org.springframework.security.core.Authentication auth) {
        boolean admin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            return org.springframework.http.ResponseEntity.status(403).body(java.util.Map.of("message", "Only an admin can change the AI spending limits."));
        }
        if ((r.dailyLimitUsd() != null && r.dailyLimitUsd().signum() < 0) || (r.monthlyLimitUsd() != null && r.monthlyLimitUsd().signum() < 0)) {
            throw new IllegalArgumentException("Limits cannot be negative.");
        }
        int warn = r.warnPercent() != null ? r.warnPercent() : 80;
        if (warn < 1 || warn > 100) throw new IllegalArgumentException("The warning level must be between 1 and 100 percent.");
        return org.springframework.http.ResponseEntity.ok(aiBudgetGuard.save(r.dailyLimitUsd(), r.monthlyLimitUsd(), warn, r.hardStop() == null || r.hardStop()));
    }

    /** Screens applicants who were saved but not screened (AI was down or paused). Returns how many were screened. */
    @org.springframework.web.bind.annotation.PostMapping("/retry-unscreened")
    public java.util.Map<String, Integer> retryUnscreened() {
        return java.util.Map.of("screened", unscreenedApplicantRetrier.runOnce());
    }

    /** What the AI is costing: by period, by feature, by day, and per candidate and hire. */
    @GetMapping("/ai-usage")
    public com.talentai.aiusage.AiUsageService.Report aiUsage() {
        return aiUsageService.report();
    }

    /** How consistent screening is, and how often recruiters agree with the AI. */
    @GetMapping("/screening-quality")
    public ScreeningQualityService.Quality screeningQuality() {
        return screeningQualityService.overview();
    }

    /** The Dashboard's "needs your attention" figures and the most urgent warnings. */
    @GetMapping("/attention")
    public IntelligenceService.Attention attention() {
        return intelligenceService.attention();
    }

    /** Everything the Talent Intelligence page shows, computed from current data. */
    @GetMapping("/overview")
    public IntelligenceService.Overview overview() {
        return intelligenceService.overview();
    }
}
