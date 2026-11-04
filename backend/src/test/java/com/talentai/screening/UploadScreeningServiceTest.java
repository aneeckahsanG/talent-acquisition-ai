package com.talentai.screening;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.screening.service.ScreeningAgentService.PreparedUpload;
import com.talentai.screening.service.ScreeningProgressTracker;
import com.talentai.screening.service.UploadScreeningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An uploaded resume is checked and accepted at once; the slow AI screening runs behind it. */
class UploadScreeningServiceTest {

    private final ScreeningAgentService screening = mock(ScreeningAgentService.class);
    private final ScreeningProgressTracker tracker = new ScreeningProgressTracker();
    private final MultipartFile file = mock(MultipartFile.class);
    private UploadScreeningService service;
    private final Candidate danny = Candidate.builder().id(5L).fullName("Danny").build();
    private final JobRequisition java = JobRequisition.builder().id(3L).title("Senior Backend Engineer (Java)").build();

    @BeforeEach
    void setUp() throws Exception {
        service = new UploadScreeningService(screening, tracker);
        when(screening.prepareUpload("Danny", "d@example.com", 3L, file)).thenReturn(new PreparedUpload(danny, List.of(java)));
    }

    @Test
    @DisplayName("the request returns before the AI is done, with the screening showing as in progress")
    void returnsBeforeTheAiFinishes() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        when(screening.screenPrepared(danny, java)).thenAnswer(i -> { release.await(); return null; });

        int started = service.start("Danny", "d@example.com", 3L, file);

        assertThat(started).isEqualTo(1);
        assertThat(tracker.list()).hasSize(1);
        assertThat(tracker.list().get(0).candidateName()).isEqualTo("Danny");
        assertThat(tracker.list().get(0).error()).isNull();

        release.countDown();
        await().untilAsserted(() -> assertThat(tracker.list()).isEmpty());
        verify(screening).screenPrepared(danny, java);
    }

    @Test
    @DisplayName("a failed screening stays listed with its reason instead of vanishing")
    void aFailureIsShown() throws Exception {
        when(screening.screenPrepared(danny, java)).thenThrow(new IllegalStateException("AI budget reached"));

        service.start("Danny", "d@example.com", 3L, file);

        await().untilAsserted(() -> {
            assertThat(tracker.list()).hasSize(1);
            assertThat(tracker.list().get(0).error()).isEqualTo("AI budget reached");
        });
    }

    @Test
    @DisplayName("a bad request is refused up front and nothing is started")
    void badRequestsStartNothing() throws Exception {
        when(screening.prepareUpload("Danny", "d@example.com", 3L, file))
                .thenThrow(new IllegalArgumentException("This candidate has already been screened for this role."));

        assertThatThrownBy(() -> service.start("Danny", "d@example.com", 3L, file))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(tracker.list()).isEmpty();
        verify(screening, never()).screenPrepared(any(), any());
    }

    @Test
    @DisplayName("every open role gets its own in-progress entry when no role is chosen")
    void oneEntryPerRole() throws Exception {
        var react = JobRequisition.builder().id(4L).title("Frontend Engineer").build();
        when(screening.prepareUpload("Danny", "d@example.com", null, file)).thenReturn(new PreparedUpload(danny, List.of(java, react)));
        var release = new java.util.concurrent.CountDownLatch(1);
        when(screening.screenPrepared(any(), any())).thenAnswer(i -> { release.await(); return null; });

        assertThat(service.start("Danny", "d@example.com", null, file)).isEqualTo(2);
        assertThat(tracker.list()).hasSize(2);
        release.countDown();
    }
}
