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
 * In-memory only: an entry exists exactly as long as the work is running.
 */
@Component
public class ScreeningProgressTracker {

    public record Entry(String candidateName, Long requisitionId, String requisitionTitle, LocalDateTime startedAt) {}

    private static final long STALE_MINUTES = 15;

    private final Map<String, Entry> running = new ConcurrentHashMap<>();

    public String start(String candidateName, Long requisitionId, String requisitionTitle) {
        String key = UUID.randomUUID().toString();
        running.put(key, new Entry(candidateName, requisitionId, requisitionTitle, LocalDateTime.now()));
        return key;
    }

    public void finish(String key) {
        if (key != null) running.remove(key);
    }

    public List<Entry> list() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        running.values().removeIf(e -> e.startedAt().isBefore(cutoff));
        return running.values().stream().sorted(Comparator.comparing(Entry::startedAt)).toList();
    }
}
