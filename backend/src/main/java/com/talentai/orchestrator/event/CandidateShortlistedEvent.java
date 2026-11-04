package com.talentai.orchestrator.event;

/** A recruiter moved a candidate to Shortlisted. The orchestrator agent decides the next step from this. */
public record CandidateShortlistedEvent(Long requisitionId, Long candidateId) {}
