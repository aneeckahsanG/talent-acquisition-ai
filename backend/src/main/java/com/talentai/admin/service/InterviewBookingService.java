package com.talentai.admin.service;

import com.talentai.admin.entity.AvailabilityWindow;
import com.talentai.admin.entity.InterviewSlot;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.admin.repository.InterviewSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Central rules for the interview calendar: fixed length per type, and bookings that never overlap for the
 * same interviewer. Availability belongs to an interviewer, or to nobody when it is open to all of them.
 * An interview with an interviewer can use that person's windows and the open ones; an interview with no
 * interviewer can use only the open ones.
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
            if (!usableBy(w, interviewerId)) continue;
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

    /** A window is usable when it is open to everyone or belongs to this interviewer. */
    private static boolean usableBy(AvailabilityWindow w, Long interviewerId) {
        return w.getInterviewerId() == null || w.getInterviewerId().equals(interviewerId);
    }

    /**
     * Publishes one window per day from {@code from} to {@code to}, for this interviewer (null = open to all).
     * Days that already have an overlapping window for the same owner are skipped.
     * @return how many days were published
     */
    public int publish(Long interviewerId, LocalDate from, LocalDate to, boolean weekdaysOnly,
                       LocalTime dayStart, LocalTime dayEnd) {
        if (to.isBefore(from) || from.plusDays(60).isBefore(to)) {
            throw new IllegalArgumentException("Date range must be valid and at most 60 days.");
        }
        if (!dayEnd.isAfter(dayStart)) throw new IllegalArgumentException("End time must be after start time.");

        int created = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (weekdaysOnly && (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)) continue;
            LocalDateTime s = d.atTime(dayStart);
            LocalDateTime e = d.atTime(dayEnd);
            boolean taken = windowRepository.findOverlapping(s, e).stream()
                    .anyMatch(w -> Objects.equals(w.getInterviewerId(), interviewerId));
            if (taken) continue;
            windowRepository.save(AvailabilityWindow.builder().startTime(s).endTime(e).interviewerId(interviewerId).build());
            created++;
        }
        return created;
    }

    /**
     * Removes a window unless interviews are booked inside it. A window that belongs to an interviewer is blocked
     * by that interviewer's bookings; an open window is blocked by any booking.
     * @param onlyOwnedBy when not null, the window must belong to this interviewer
     * @return empty when the window does not exist (or is not theirs); otherwise whether it was removed
     */
    public Optional<Boolean> removeWindow(Long windowId, Long onlyOwnedBy) {
        AvailabilityWindow w = windowRepository.findById(windowId).orElse(null);
        if (w == null || (onlyOwnedBy != null && !onlyOwnedBy.equals(w.getInterviewerId()))) return Optional.empty();
        boolean booked = bookingRepository.findOverlapping(w.getStartTime(), w.getEndTime()).stream()
                .anyMatch(b -> w.getInterviewerId() == null || w.getInterviewerId().equals(b.getInterviewerId()));
        if (booked) return Optional.of(false);
        windowRepository.delete(w);
        return Optional.of(true);
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
