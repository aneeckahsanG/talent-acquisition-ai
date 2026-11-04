package com.talentai.screening.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks applications that are currently being processed (AI matching + screening),
 * so the Screening page can show them as "in progress" before a result row exists.
 * In-memory only: an entry exists exactly as long as the work is running, except that a
 * failed one stays for a few minutes with its error so the failure is not silent.
 */
@Component
public class ScreeningProgressTracker {

    /** @param error null while running; why it failed once it has */
    public record Entry(String candidateName, Long requisitionId, String requisitionTitle, LocalDateTime startedAt, String error) {}

    private static final long STALE_MINUTES = 15;
    private static final long FAILURE_MINUTES = 5;

    private final Map<String, Entry> running = new ConcurrentHashMap<>();

    public String start(String candidateName, Long requisitionId, String requisitionTitle) {
        String key = UUID.randomUUID().toString();
        running.put(key, new Entry(candidateName, requisitionId, requisitionTitle, LocalDateTime.now(), null));
        return key;
    }

    public void finish(String key) {
        if (key != null) running.remove(key);
    }

    /** Keeps the entry, marked as failed, so the Screening page can say what went wrong. */
    public void fail(String key, String error) {
        if (key == null) return;
        running.computeIfPresent(key, (k, e) ->
                new Entry(e.candidateName(), e.requisitionId(), e.requisitionTitle(), LocalDateTime.now(), error));
    }

    public List<Entry> list() {
        LocalDateTime now = LocalDateTime.now();
        running.values().removeIf(e -> e.startedAt().isBefore(e.error() == null
                ? now.minusMinutes(STALE_MINUTES) : now.minusMinutes(FAILURE_MINUTES)));
        return running.values().stream().sorted(Comparator.comparing(Entry::startedAt)).toList();
    }
}
