package com.talentai.orchestrator.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single source of truth for what each interview type is scored on. */
public final class ScorecardCriteria {

    private ScorecardCriteria() {}

    /** Criteria (in display order) required for each interview type. */
    public static final Map<String, List<String>> BY_TYPE = Map.of(
            "PHONE_SCREEN", List.of("COMMUNICATION", "MOTIVATION", "RELEVANT_EXPERIENCE", "PRACTICAL_FIT"),
            "TECHNICAL", List.of("TECHNICAL_SKILLS", "PROBLEM_SOLVING", "CODE_DESIGN_QUALITY", "COMMUNICATION"),
            "FINAL", List.of("CULTURE_VALUES_FIT", "OWNERSHIP_LEADERSHIP", "ROLE_ALIGNMENT", "COMMITMENT"));

    /** Display labels, including the keys used by scorecards saved before criteria varied by type. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("COMMUNICATION", "Communication");
        LABELS.put("MOTIVATION", "Motivation / interest in the role");
        LABELS.put("RELEVANT_EXPERIENCE", "Relevant experience (CV holds up)");
        LABELS.put("PRACTICAL_FIT", "Practical fit (availability, salary)");
        LABELS.put("TECHNICAL_SKILLS", "Technical skills");
        LABELS.put("PROBLEM_SOLVING", "Problem solving");
        LABELS.put("CODE_DESIGN_QUALITY", "Code / design quality");
        LABELS.put("CULTURE_VALUES_FIT", "Culture and values fit");
        LABELS.put("OWNERSHIP_LEADERSHIP", "Ownership and leadership");
        LABELS.put("ROLE_ALIGNMENT", "Role alignment");
        LABELS.put("COMMITMENT", "Motivation / commitment");
        // legacy keys from the original fixed scorecard
        LABELS.put("TECHNICAL", "Technical skills");
        LABELS.put("CULTURE_FIT", "Culture fit");
    }

    public static List<String> forType(String interviewType) {
        return BY_TYPE.getOrDefault(interviewType, BY_TYPE.get("TECHNICAL"));
    }
}
