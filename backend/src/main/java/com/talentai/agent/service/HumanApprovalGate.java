package com.talentai.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process HITL gate for the agentic loop.
 *
 * Flow:
 *  1. A @Tool method calls requestApproval(actionKey, description) — stores a
 *     PENDING record and returns an approvalId.
 *  2. The tool returns "PENDING_APPROVAL — Approval ID: <id>" to the LLM, which
 *     surfaces it to the recruiter via the API response.
 *  3. The recruiter calls POST /api/agent/approvals/{id}/approve (or /deny).
 *  4. The agentic loop is re-submitted; isApproved(actionKey) returns true and
 *     the tool executes normally.
 *
 * Approvals are keyed by actionKey (not approvalId) so the same logical action
 * requested twice in one loop run is not double-gated.  Approvals expire after
 * 10 minutes to prevent stale grants leaking into a future run.
 */
@Service
@Slf4j
public class HumanApprovalGate {

    public enum Status { PENDING, APPROVED, DENIED }

    public record PendingApproval(
            String approvalId,
            String actionKey,
            String description,
            Status status,
            Instant requestedAt,
            Instant resolvedAt) {}

    private final Map<String, PendingApproval> byId  = new ConcurrentHashMap<>();
    private final Map<String, String>          keyToId = new ConcurrentHashMap<>();

    private static final long EXPIRY_SECONDS = 600;

    /** Returns true if actionKey has a live APPROVED grant. */
    public boolean isApproved(String actionKey) {
        String id = keyToId.get(actionKey);
        if (id == null) return false;
        PendingApproval a = byId.get(id);
        if (a == null) return false;
        if (isExpired(a)) { evict(a); return false; }
        return a.status() == Status.APPROVED;
    }

    /** Creates a PENDING record and returns its approvalId. Idempotent for the same actionKey. */
    public String requestApproval(String actionKey, String description) {
        String existingId = keyToId.get(actionKey);
        if (existingId != null) {
            PendingApproval existing = byId.get(existingId);
            if (existing != null && !isExpired(existing)) return existingId;
        }
        String id = UUID.randomUUID().toString();
        PendingApproval record = new PendingApproval(id, actionKey, description, Status.PENDING,
                Instant.now(), null);
        byId.put(id, record);
        keyToId.put(actionKey, id);
        log.info("HITL approval requested [{}]: {}", id, description);
        return id;
    }

    public boolean approve(String approvalId) {
        return resolve(approvalId, Status.APPROVED);
    }

    public boolean deny(String approvalId) {
        return resolve(approvalId, Status.DENIED);
    }

    public List<PendingApproval> listPending() {
        return byId.values().stream()
                .filter(a -> a.status() == Status.PENDING && !isExpired(a))
                .sorted((a, b) -> a.requestedAt().compareTo(b.requestedAt()))
                .toList();
    }

    public List<PendingApproval> listAll() {
        return new ArrayList<>(byId.values());
    }

    private boolean resolve(String approvalId, Status status) {
        PendingApproval a = byId.get(approvalId);
        if (a == null) return false;
        PendingApproval resolved = new PendingApproval(
                a.approvalId(), a.actionKey(), a.description(), status,
                a.requestedAt(), Instant.now());
        byId.put(approvalId, resolved);
        if (status == Status.DENIED) keyToId.remove(a.actionKey());
        log.info("HITL approval {} [{}]: {}", status, approvalId, a.description());
        return true;
    }

    private boolean isExpired(PendingApproval a) {
        return a.requestedAt().plusSeconds(EXPIRY_SECONDS).isBefore(Instant.now());
    }

    private void evict(PendingApproval a) {
        byId.remove(a.approvalId());
        keyToId.remove(a.actionKey());
    }
}
