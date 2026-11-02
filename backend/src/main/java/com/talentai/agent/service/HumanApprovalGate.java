package com.talentai.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process HITL gate for the agentic loop.
 *
 * Flow:
 *  1. A @Tool method calls requestApproval(actionKey, description, ...) — stores a
 *     PENDING record and returns an approvalId.
 *  2. The tool returns "PENDING_APPROVAL — Approval ID: <id>" to the LLM, which
 *     surfaces it to the recruiter via the API response.
 *  3. The recruiter approves it, either one entry at a time (POST /api/agent/approvals/{id}/approve)
 *     or, for an orchestrator run, everything the run is waiting on at once (approveRun).
 *  4. The agentic loop is re-submitted; isApproved(actionKey) returns true and
 *     the tool executes normally, using approvedPayload(actionKey) for anything the recruiter
 *     reviewed (an email body), then consume(actionKey) so the grant cannot be used twice.
 *
 * Approvals are keyed by actionKey (not approvalId) so the same logical action
 * requested twice in one loop run is not double-gated. A request waits up to a day
 * for a recruiter; once approved, the grant is good for 10 minutes — long enough for
 * the resumed run to use it, short enough that it cannot leak into a later run.
 */
@Service
@Slf4j
public class HumanApprovalGate {

    public enum Status { PENDING, APPROVED, DENIED }

    /**
     * @param toolName name of the tool that asked (e.g. "sendOutreach"), or null for a plain request
     * @param payload  the exact content the recruiter is approving (an email body), or null
     * @param runId    the orchestrator run waiting on this, or null outside an orchestrator run
     */
    public record PendingApproval(
            String approvalId,
            String actionKey,
            String description,
            Status status,
            Instant requestedAt,
            Instant resolvedAt,
            String toolName,
            String payload,
            Long runId) {}

    private final Map<String, PendingApproval> byId  = new ConcurrentHashMap<>();
    private final Map<String, String>          keyToId = new ConcurrentHashMap<>();

    private static final long PENDING_EXPIRY_SECONDS = 24 * 60 * 60;
    private static final long GRANT_EXPIRY_SECONDS = 600;

    /** Returns true if actionKey has a live APPROVED grant. */
    public boolean isApproved(String actionKey) {
        return live(actionKey).map(a -> a.status() == Status.APPROVED).orElse(false);
    }

    /** The content the recruiter approved for this action, if it has a live grant and carried any. */
    public Optional<String> approvedPayload(String actionKey) {
        return live(actionKey)
                .filter(a -> a.status() == Status.APPROVED)
                .map(PendingApproval::payload);
    }

    /** Creates a PENDING record and returns its approvalId. Idempotent for the same actionKey. */
    public String requestApproval(String actionKey, String description) {
        return requestApproval(actionKey, description, null, null, null);
    }

    public String requestApproval(String actionKey, String description, String toolName, String payload, Long runId) {
        Optional<PendingApproval> existing = live(actionKey);
        if (existing.isPresent()) {
            PendingApproval e = existing.get();
            // The same action asked for again by a newer run: that run is the one waiting now.
            if (runId != null && !runId.equals(e.runId())) {
                byId.put(e.approvalId(), new PendingApproval(e.approvalId(), e.actionKey(), e.description(), e.status(),
                        e.requestedAt(), e.resolvedAt(), e.toolName(), e.payload(), runId));
            }
            return e.approvalId();
        }
        String id = UUID.randomUUID().toString();
        PendingApproval record = new PendingApproval(id, actionKey, description, Status.PENDING,
                Instant.now(), null, toolName, payload, runId);
        byId.put(id, record);
        keyToId.put(actionKey, id);
        log.info("HITL approval requested [{}]: {}", id, description);
        return id;
    }

    /**
     * Records an already-decided grant, for a recruiter approval that outlived the in-memory entry
     * (a restart, or the request aged out) but is still saved on the paused run.
     */
    public void grant(String actionKey, String description, String toolName, String payload, Long runId) {
        String id = live(actionKey).map(PendingApproval::approvalId).orElseGet(() -> UUID.randomUUID().toString());
        Instant now = Instant.now();
        byId.put(id, new PendingApproval(id, actionKey, description, Status.APPROVED, now, now, toolName, payload, runId));
        keyToId.put(actionKey, id);
        log.info("HITL approval granted [{}]: {}", id, description);
    }

    public boolean approve(String approvalId) {
        return resolve(approvalId, Status.APPROVED);
    }

    public boolean deny(String approvalId) {
        return resolve(approvalId, Status.DENIED);
    }

    /** Everything an orchestrator run is waiting on a recruiter for. */
    public List<PendingApproval> pendingForRun(Long runId) {
        if (runId == null) return List.of();
        return listPending().stream().filter(a -> runId.equals(a.runId())).toList();
    }

    /** Approves everything the run is waiting on; returns how many entries that was. */
    public int approveRun(Long runId) {
        List<PendingApproval> pending = pendingForRun(runId);
        pending.forEach(a -> approve(a.approvalId()));
        return pending.size();
    }

    public void denyRun(Long runId) {
        pendingForRun(runId).forEach(a -> deny(a.approvalId()));
    }

    /** Uses up a grant after the approved action has run, so the same approval cannot fire it again. */
    public void consume(String actionKey) {
        String id = keyToId.remove(actionKey);
        if (id != null) byId.remove(id);
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

    private Optional<PendingApproval> live(String actionKey) {
        String id = keyToId.get(actionKey);
        if (id == null) return Optional.empty();
        PendingApproval a = byId.get(id);
        if (a == null) return Optional.empty();
        if (isExpired(a)) { evict(a); return Optional.empty(); }
        return Optional.of(a);
    }

    private boolean resolve(String approvalId, Status status) {
        PendingApproval a = byId.get(approvalId);
        if (a == null) return false;
        PendingApproval resolved = new PendingApproval(
                a.approvalId(), a.actionKey(), a.description(), status,
                a.requestedAt(), Instant.now(), a.toolName(), a.payload(), a.runId());
        byId.put(approvalId, resolved);
        if (status == Status.DENIED) keyToId.remove(a.actionKey());
        log.info("HITL approval {} [{}]: {}", status, approvalId, a.description());
        return true;
    }

    private boolean isExpired(PendingApproval a) {
        if (a.status() == Status.APPROVED && a.resolvedAt() != null) {
            return a.resolvedAt().plusSeconds(GRANT_EXPIRY_SECONDS).isBefore(Instant.now());
        }
        return a.requestedAt().plusSeconds(PENDING_EXPIRY_SECONDS).isBefore(Instant.now());
    }

    private void evict(PendingApproval a) {
        byId.remove(a.approvalId());
        keyToId.remove(a.actionKey());
    }
}
