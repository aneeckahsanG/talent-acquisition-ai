package com.talentai.aiusage;

/** Thrown instead of making an AI call when the daily or monthly spending limit has been reached. */
public class AiBudgetExceededException extends RuntimeException {
    public AiBudgetExceededException(String message) {
        super(message);
    }
}
