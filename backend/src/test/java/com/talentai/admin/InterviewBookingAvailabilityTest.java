package com.talentai.admin;

import com.talentai.admin.entity.AvailabilityWindow;
import com.talentai.admin.entity.InterviewSlot;
import com.talentai.admin.repository.AvailabilityWindowRepository;
import com.talentai.admin.repository.InterviewSlotRepository;
import com.talentai.admin.service.InterviewBookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Availability belongs to an interviewer, or to nobody when it is open to everyone. */
class InterviewBookingAvailabilityTest {

    private static final long PRIYA = 8L;
    private static final long SAM = 9L;

    private AvailabilityWindowRepository windows;
    private InterviewSlotRepository bookings;
    private InterviewBookingService service;

    private final LocalDateTime day = LocalDate.now().plusDays(3).atStartOfDay();

    @BeforeEach
    void setUp() {
        windows = mock(AvailabilityWindowRepository.class);
        bookings = mock(InterviewSlotRepository.class);
        service = new InterviewBookingService(windows, bookings);
    }

    private AvailabilityWindow window(Long id, Long interviewerId, int fromHour, int toHour) {
        return AvailabilityWindow.builder().id(id).interviewerId(interviewerId)
                .startTime(day.plusHours(fromHour)).endTime(day.plusHours(toHour)).build();
    }

    private InterviewSlot booking(long interviewerId, int fromHour, int toHour) {
        return InterviewSlot.builder().interviewId(77L).interviewerId(interviewerId)
                .startTime(day.plusHours(fromHour)).endTime(day.plusHours(toHour)).build();
    }

    @Test
    @DisplayName("an interviewer is offered their own windows and the open ones, not another interviewer's")
    void ownAndOpenWindowsOnly() {
        when(windows.findByEndTimeAfterOrderByStartTimeAsc(any())).thenReturn(List.of(
                window(1L, PRIYA, 9, 10),     // Priya's
                window(2L, SAM, 11, 12),      // Sam's
                window(3L, null, 14, 15)));   // open to all

        List<LocalDateTime> forPriya = service.availableStarts("PHONE_SCREEN", PRIYA, null);

        assertEquals(List.of(day.plusHours(9), day.plusHours(9).plusMinutes(30),
                day.plusHours(14), day.plusHours(14).plusMinutes(30)), forPriya);
    }

    @Test
    @DisplayName("an interview with no interviewer is offered only the open windows")
    void noInterviewerGetsOnlyOpenWindows() {
        when(windows.findByEndTimeAfterOrderByStartTimeAsc(any())).thenReturn(List.of(
                window(1L, PRIYA, 9, 10), window(3L, null, 14, 15)));

        assertEquals(List.of(day.plusHours(14), day.plusHours(14).plusMinutes(30)),
                service.availableStarts("PHONE_SCREEN", null, null));
    }

    @Test
    @DisplayName("when only someone else has published, there is nothing to offer")
    void nothingWhenOnlyOthersPublished() {
        when(windows.findByEndTimeAfterOrderByStartTimeAsc(any())).thenReturn(List.of(window(2L, SAM, 9, 17)));

        assertTrue(service.availableStarts("TECHNICAL", PRIYA, null).isEmpty());
    }

    @Test
    @DisplayName("a booking blocks that time for its own interviewer but not for another using the same open window")
    void bookingsBlockOnlyTheirInterviewer() {
        when(windows.findByEndTimeAfterOrderByStartTimeAsc(any())).thenReturn(List.of(window(3L, null, 9, 10)));
        when(bookings.findByEndTimeAfter(any())).thenReturn(List.of(booking(PRIYA, 9, 10)));

        assertTrue(service.availableStarts("PHONE_SCREEN", PRIYA, null).isEmpty());
        assertEquals(2, service.availableStarts("PHONE_SCREEN", SAM, null).size());
    }

    @Test
    @DisplayName("publishing for an interviewer skips only days they already have, not days others have")
    void publishSkipsOnlyTheSameOwner() {
        LocalDate d = LocalDate.now().plusDays(3);
        when(windows.findOverlapping(any(), any())).thenReturn(List.of(window(2L, SAM, 10, 17)));

        int forPriya = service.publish(PRIYA, d, d, false, LocalTime.of(10, 0), LocalTime.of(17, 0));
        int forSam = service.publish(SAM, d, d, false, LocalTime.of(10, 0), LocalTime.of(17, 0));

        assertEquals(1, forPriya);
        assertEquals(0, forSam);
        ArgumentCaptor<AvailabilityWindow> saved = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows, times(1)).save(saved.capture());
        assertEquals(PRIYA, saved.getValue().getInterviewerId());
    }

    @Test
    @DisplayName("publishing with no interviewer makes an open window")
    void publishOpenWindow() {
        LocalDate d = LocalDate.now().plusDays(3);
        when(windows.findOverlapping(any(), any())).thenReturn(List.of());

        service.publish(null, d, d, false, LocalTime.of(10, 0), LocalTime.of(12, 0));

        ArgumentCaptor<AvailabilityWindow> saved = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows).save(saved.capture());
        assertEquals(null, saved.getValue().getInterviewerId());
    }

    @Test
    @DisplayName("a bad date range or day length is refused")
    void publishValidates() {
        LocalDate d = LocalDate.now().plusDays(3);
        assertThrows(IllegalArgumentException.class, () -> service.publish(PRIYA, d, d.minusDays(1), false, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.publish(PRIYA, d, d, false, LocalTime.of(17, 0), LocalTime.of(9, 0)));
    }

    @Test
    @DisplayName("an interviewer can remove only their own window")
    void removeOnlyOwn() {
        when(windows.findById(1L)).thenReturn(Optional.of(window(1L, PRIYA, 9, 17)));
        when(windows.findById(2L)).thenReturn(Optional.of(window(2L, SAM, 9, 17)));
        when(windows.findById(3L)).thenReturn(Optional.of(window(3L, null, 9, 17)));
        when(bookings.findOverlapping(any(), any())).thenReturn(List.of());

        assertEquals(Optional.of(true), service.removeWindow(1L, PRIYA));
        assertEquals(Optional.empty(), service.removeWindow(2L, PRIYA));   // Sam's
        assertEquals(Optional.empty(), service.removeWindow(3L, PRIYA));   // open to all
        assertEquals(Optional.empty(), service.removeWindow(99L, PRIYA));  // missing
        verify(windows, times(1)).delete(any());
    }

    @Test
    @DisplayName("a window is kept while its interviewer has a booking in it; another's booking does not hold it")
    void removeBlockedByOwnBooking() {
        AvailabilityWindow priyas = window(1L, PRIYA, 9, 17);
        when(windows.findById(1L)).thenReturn(Optional.of(priyas));
        when(bookings.findOverlapping(any(), any())).thenReturn(List.of(booking(SAM, 10, 11)));
        assertEquals(Optional.of(true), service.removeWindow(1L, null));

        when(windows.findById(1L)).thenReturn(Optional.of(priyas));
        when(bookings.findOverlapping(any(), any())).thenReturn(List.of(booking(PRIYA, 10, 11)));
        assertEquals(Optional.of(false), service.removeWindow(1L, null));
    }

    @Test
    @DisplayName("an open window is kept while anyone has a booking in it")
    void openWindowBlockedByAnyBooking() {
        when(windows.findById(3L)).thenReturn(Optional.of(window(3L, null, 9, 17)));
        when(bookings.findOverlapping(any(), any())).thenReturn(List.of(booking(SAM, 10, 11)));

        assertFalse(service.removeWindow(3L, null).orElseThrow());
        verify(windows, never()).delete(any());
    }
}
