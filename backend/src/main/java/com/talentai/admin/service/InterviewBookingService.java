package com.talentai.admin.service;

import com.talentai.admin.entity.AvailabilityWindow;
import com.talentai.admin.entity.InterviewSlot;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.admin.repository.InterviewSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Central rules for the shared interview calendar: fixed length per type, and bookings
 * that never overlap for the same interviewer. Interviews with no interviewer share one pool.
 */
@Service
@RequiredArgsConstructor
public class InterviewBookingService {

    /** Fixed length in minutes per interview type. Unknown types default to 30. */
    public static final Map<String, Integer> DURATIONS = new LinkedHashMap<>(Map.of(
            "PHONE_SCREEN", 30,
            "TECHNICAL", 60,
            "FINAL", 90));

    /** Start times are offered on this grid, counted from the start of each window. */
    private static final int STEP_MINUTES = 30;

    private final AvailabilityWindowRepository windowRepository;
    private final InterviewSlotRepository bookingRepository;

    public int durationFor(String interviewType) {
        return DURATIONS.getOrDefault(interviewType, 30);
    }

    /** Start times where a full interview fits inside a window and clashes with none of this interviewer's bookings. */
    public List<LocalDateTime> availableStarts(String interviewType, Long interviewerId, Long ignoreInterviewId) {
        LocalDateTime now = LocalDateTime.now();
        int dur = durationFor(interviewType);
        List<InterviewSlot> bookings = bookingRepository.findByEndTimeAfter(now).stream()
                .filter(b -> Objects.equals(b.getInterviewerId(), interviewerId))
                .filter(b -> !Objects.equals(b.getInterviewId(), ignoreInterviewId)).toList();

        List<LocalDateTime> starts = new ArrayList<>();
        for (AvailabilityWindow w : windowRepository.findByEndTimeAfterOrderByStartTimeAsc(now)) {
            for (LocalDateTime t = w.getStartTime(); !t.plusMinutes(dur).isAfter(w.getEndTime()); t = t.plusMinutes(STEP_MINUTES)) {
                if (!t.isAfter(now)) continue;
                LocalDateTime end = t.plusMinutes(dur);
                final LocalDateTime start = t;
                boolean clash = bookings.stream().anyMatch(b -> b.getStartTime().isBefore(end) && b.getEndTime().isAfter(start));
                if (!clash && !starts.contains(t)) starts.add(t);
            }
        }
        Collections.sort(starts);
        return starts;
    }

    /** Books the interview if the start is still free. Synchronized so two candidates can't take overlapping times. */
    public synchronized Optional<InterviewSlot> book(Long interviewId, String interviewType, Long interviewerId, LocalDateTime start) {
        if (!availableStarts(interviewType, interviewerId, interviewId).contains(start)) return Optional.empty();
        bookingRepository.deleteAll(bookingRepository.findByInterviewId(interviewId));
        return Optional.of(bookingRepository.save(InterviewSlot.builder()
                .startTime(start).endTime(start.plusMinutes(durationFor(interviewType)))
                .interviewId(interviewId).interviewerId(interviewerId).build()));
    }

    /** Recruiter override (reschedule): books any time not clashing with another of this interviewer's interviews. */
    public synchronized void forceBook(Long interviewId, String interviewType, Long interviewerId, LocalDateTime start) {
        LocalDateTime end = start.plusMinutes(durationFor(interviewType));
        boolean clash = bookingRepository.findOverlapping(start, end).stream()
                .filter(b -> Objects.equals(b.getInterviewerId(), interviewerId))
                .anyMatch(b -> !Objects.equals(b.getInterviewId(), interviewId));
        if (clash) throw new IllegalStateException("That time overlaps another interview for the same interviewer.");
        bookingRepository.deleteAll(bookingRepository.findByInterviewId(interviewId));
        bookingRepository.save(InterviewSlot.builder().startTime(start).endTime(end)
                .interviewId(interviewId).interviewerId(interviewerId).build());
    }

    public void release(Long interviewId) {
        bookingRepository.deleteAll(bookingRepository.findByInterviewId(interviewId));
    }
}
