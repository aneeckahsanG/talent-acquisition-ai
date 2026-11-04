package com.talentai.screening.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Screens an uploaded resume in the background. The request is checked and answered at once; the AI
 * call, which takes a while, runs afterwards and shows on the Screening page as "in progress" through
 * {@link ScreeningProgressTracker} until its result row exists (or until it fails, which the page shows too).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadScreeningService {

    private final ScreeningAgentService screeningAgentService;
    private final ScreeningProgressTracker progressTracker;

    /**
     * @param requisitionId one role, or null for every open role
     * @return how many role screenings were started
     * @throws IllegalArgumentException when the file or request is not usable (nothing is started then)
     */
    public int start(String candidateName, String candidateEmail, Long requisitionId, MultipartFile resume) throws IOException {
        var prepared = screeningAgentService.prepareUpload(candidateName, candidateEmail, requisitionId, resume);
        var roles = prepared.roles();
        var keys = roles.stream().map(r -> progressTracker.start(candidateName, r.getId(), r.getTitle())).toList();

        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < roles.size(); i++) {
                var role = roles.get(i);
                try {
                    screeningAgentService.screenPrepared(prepared.candidate(), role);
                    progressTracker.finish(keys.get(i));
                } catch (Exception e) {
                    log.warn("Screening {} for role {} failed: {}", candidateName, role.getId(), e.getMessage());
                    progressTracker.fail(keys.get(i), e.getMessage() == null ? "Screening failed." : e.getMessage());
                }
            }
        });
        return roles.size();
    }
}
