package com.talentai.cvinbox.service;

import com.talentai.common.entity.JobRequisition;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, no-LLM scorer for the CV Inbox — compares a resume's text against a
 * requisition's required skills, title, and experience level using plain keyword/string
 * matching. Stands in for the AI-based scoring used elsewhere in the app (which needs a
 * configured, funded Claude API key) so CV Inbox gives varied, explainable scores instead
 * of a flat fallback value. Swap this out for a real LLM-based scorer later without
 * touching the caller — same {@link RoleScore} shape either way.
 */
@Service
public class KeywordMatchScoringService {

    private static final Pattern YEARS_PATTERN =
            Pattern.compile("(\\d{1,2})\\+?\\s*(?:years|yrs|yr)\\b", Pattern.CASE_INSENSITIVE);

    @Data
    @Builder
    public static class RoleScore {
        private BigDecimal overallScore;
        private BigDecimal skillsScore;
        private BigDecimal experienceScore;
        private String rationale;
        private String strengths; // matched skills, comma-separated
        private String gaps;      // missing skills, comma-separated
    }

    public RoleScore score(String resumeText, JobRequisition role) {
        String resumeLower = resumeText.toLowerCase();

        List<String> requiredSkills = splitSkills(role.getRequiredSkills());
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : requiredSkills) {
            if (resumeLower.contains(skill.toLowerCase())) matched.add(skill);
            else missing.add(skill);
        }
        BigDecimal skillsScore = requiredSkills.isEmpty()
                ? BigDecimal.valueOf(50)
                : BigDecimal.valueOf(matched.size()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(requiredSkills.size()), 0, RoundingMode.HALF_UP);

        Integer candidateYears = extractMaxYearsOfExperience(resumeText);
        BigDecimal experienceScore = scoreExperience(candidateYears, role.getExperienceLevel());

        // Small bonus if the role's own title words show up in the resume (e.g. "Backend", "Analyst").
        boolean titleOverlap = titleWordsPresent(resumeLower, role.getTitle());

        BigDecimal overall = skillsScore.multiply(BigDecimal.valueOf(0.7))
                .add(experienceScore.multiply(BigDecimal.valueOf(0.3)))
                .add(titleOverlap ? BigDecimal.valueOf(5) : BigDecimal.ZERO);
        overall = clamp(overall);

        String rationale = "Keyword match: %d/%d required skills found (%s). Estimated experience: %s vs role's %s level."
                .formatted(
                        matched.size(), requiredSkills.size(),
                        matched.isEmpty() ? "none" : String.join(", ", matched),
                        candidateYears != null ? candidateYears + " yrs" : "not detected",
                        role.getExperienceLevel() != null ? role.getExperienceLevel() : "unspecified");

        return RoleScore.builder()
                .overallScore(overall)
                .skillsScore(skillsScore)
                .experienceScore(experienceScore)
                .rationale(rationale)
                .strengths(matched.isEmpty() ? "No specific keyword matches found" : String.join(", ", matched))
                .gaps(missing.isEmpty() ? "None" : String.join(", ", missing))
                .build();
    }

    private List<String> splitSkills(String requiredSkills) {
        if (requiredSkills == null || requiredSkills.isBlank()) return List.of();
        List<String> skills = new ArrayList<>();
        for (String s : requiredSkills.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) skills.add(trimmed);
        }
        return skills;
    }

    private Integer extractMaxYearsOfExperience(String resumeText) {
        Matcher m = YEARS_PATTERN.matcher(resumeText);
        Integer max = null;
        while (m.find()) {
            int years = Integer.parseInt(m.group(1));
            if (max == null || years > max) max = years;
        }
        return max;
    }

    private BigDecimal scoreExperience(Integer candidateYears, String experienceLevel) {
        if (candidateYears == null || experienceLevel == null) {
            return BigDecimal.valueOf(50); // unknown — neutral, don't penalize or reward
        }
        int expectedMin = switch (experienceLevel.toUpperCase()) {
            case "JUNIOR" -> 0;
            case "MID" -> 2;
            case "SENIOR" -> 5;
            case "LEAD", "PRINCIPAL" -> 8;
            default -> 2;
        };
        if (expectedMin == 0) return BigDecimal.valueOf(100);
        if (candidateYears >= expectedMin) return BigDecimal.valueOf(100);
        BigDecimal ratio = BigDecimal.valueOf(candidateYears).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(expectedMin), 0, RoundingMode.HALF_UP);
        return clamp(ratio);
    }

    private boolean titleWordsPresent(String resumeLower, String title) {
        if (title == null || title.isBlank()) return false;
        for (String word : title.split("\\s+")) {
            String w = word.replaceAll("[()]", "").trim().toLowerCase();
            if (w.length() > 3 && resumeLower.contains(w)) return true;
        }
        return false;
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.valueOf(100)) > 0) return BigDecimal.valueOf(100);
        return value;
    }
}
