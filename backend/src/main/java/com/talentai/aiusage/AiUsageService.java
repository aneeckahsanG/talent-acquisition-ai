package com.talentai.aiusage;

import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Turns the raw usage log into figures a person can act on. */
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageLogRepository repository;
    private final ScreeningResultRepository screeningRepository;
    private final PipelineStageRepository stageRepository;

    @Value("${app.ai.price-input-per-million-usd:3.0}")
    private double inputPrice;
    @Value("${app.ai.price-output-per-million-usd:15.0}")
    private double outputPrice;

    public record Report(LocalDateTime generatedAt, Period today, Period last7Days, Period last30Days,
                         Double projectedMonthlyCost, List<FeatureCost> byFeature, List<DayCost> byDay,
                         UnitCosts unitCosts, List<Failure> recentFailures, String pricing) {}

    public record Failure(LocalDateTime at, String feature, String error) {}

    public record Period(int calls, int failedCalls, long inputTokens, long outputTokens, double cost) {}

    public record FeatureCost(String feature, int calls, int failedCalls, long avgInputTokens, long avgOutputTokens,
                              double totalCost, double avgCostPerCall, double shareOfCost) {}

    public record DayCost(String date, int calls, double cost) {}

    public record UnitCosts(Double costPerScreenedCandidate, int screenedCandidates, Double costPerHire, int hires) {}

    public Report report() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        List<AiUsageLog> logs = repository.findByCreatedAtAfterOrderByCreatedAtAsc(now.minusDays(30));

        Period today = period(logs.stream().filter(l -> !l.getCreatedAt().isBefore(startOfToday)).toList());
        Period week = period(logs.stream().filter(l -> l.getCreatedAt().isAfter(now.minusDays(7))).toList());
        Period month = period(logs);
        Double projected = week.calls() > 0 ? round(week.cost() / 7.0 * 30.0) : null;

        List<FeatureCost> byFeature = new ArrayList<>();
        for (var e : logs.stream().collect(Collectors.groupingBy(AiUsageLog::getFeature)).entrySet()) {
            List<AiUsageLog> of = e.getValue();
            double total = of.stream().mapToDouble(l -> l.getEstimatedCost().doubleValue()).sum();
            byFeature.add(new FeatureCost(e.getKey(), of.size(), (int) of.stream().filter(l -> !l.isSuccess()).count(),
                    Math.round(of.stream().mapToInt(AiUsageLog::getInputTokens).average().orElse(0)),
                    Math.round(of.stream().mapToInt(AiUsageLog::getOutputTokens).average().orElse(0)),
                    round(total), round(total / of.size()), month.cost() > 0 ? round(100.0 * total / month.cost()) : 0));
        }
        byFeature.sort(Comparator.comparingDouble(FeatureCost::totalCost).reversed());

        List<DayCost> byDay = new ArrayList<>();
        Map<LocalDate, List<AiUsageLog>> perDay = logs.stream().collect(Collectors.groupingBy(l -> l.getCreatedAt().toLocalDate()));
        for (int i = 13; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            List<AiUsageLog> of = perDay.getOrDefault(d, List.of());
            byDay.add(new DayCost(d.toString(), of.size(), round(of.stream().mapToDouble(l -> l.getEstimatedCost().doubleValue()).sum())));
        }

        // unit costs over the same 30 days
        LocalDateTime since = now.minusDays(30);
        double screeningCost = logs.stream().filter(l -> l.getFeature().startsWith("ScreeningAgentService"))
                .mapToDouble(l -> l.getEstimatedCost().doubleValue()).sum();
        int screened = (int) screeningRepository.findAll().stream().filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(since)).count();
        int hires = (int) stageRepository.findAll().stream()
                .filter(s -> "HIRED".equals(s.getStage()) && s.getEnteredAt() != null && s.getEnteredAt().isAfter(since)).count();
        UnitCosts unit = new UnitCosts(screened > 0 && screeningCost > 0 ? round(screeningCost / screened) : null, screened,
                hires > 0 && month.cost() > 0 ? round(month.cost() / hires) : null, hires);

        String pricing = String.format("Estimated at US$%.2f per million input tokens and US$%.2f per million output tokens. These are list prices: set your contract rates in app.ai.price-input-per-million-usd and app.ai.price-output-per-million-usd.", inputPrice, outputPrice);
        List<Failure> failures = logs.stream().filter(l -> !l.isSuccess())
                .sorted(Comparator.comparing(AiUsageLog::getCreatedAt).reversed()).limit(5)
                .map(l -> new Failure(l.getCreatedAt(), l.getFeature(), l.getError())).toList();
        return new Report(now, today, week, month, projected, byFeature, byDay, unit, failures, pricing);
    }

    private Period period(List<AiUsageLog> logs) {
        return new Period(logs.size(), (int) logs.stream().filter(l -> !l.isSuccess()).count(),
                logs.stream().mapToLong(AiUsageLog::getInputTokens).sum(), logs.stream().mapToLong(AiUsageLog::getOutputTokens).sum(),
                round(logs.stream().map(AiUsageLog::getEstimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue()));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
