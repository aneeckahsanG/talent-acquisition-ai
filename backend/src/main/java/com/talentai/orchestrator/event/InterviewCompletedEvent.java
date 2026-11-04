package com.talentai.orchestrator.event;

/** Published when an interviewer's scorecard completes an interview round, so the next step can be decided. */
public record InterviewCompletedEvent(Long requisitionId, Long candidateId, String interviewType) {}
