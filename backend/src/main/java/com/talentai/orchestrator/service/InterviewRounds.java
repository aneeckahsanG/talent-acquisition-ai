package com.talentai.orchestrator.service;

import java.util.List;

/** The order interview rounds run in. The agent never picks a round out of order. */
public final class InterviewRounds {

    private InterviewRounds() {}

    public static final List<String> ORDER = List.of("PHONE_SCREEN", "TECHNICAL", "FINAL");

    /** The round that follows {@code interviewType}, or null after the last round or for an unknown type. */
    public static String next(String interviewType) {
        int i = ORDER.indexOf(interviewType);
        return i < 0 || i == ORDER.size() - 1 ? null : ORDER.get(i + 1);
    }
}
