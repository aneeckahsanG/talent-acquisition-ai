package com.talentai.cvinbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.cvinbox.dto.CvInboxDtos.ProcessNowResponse;
import com.talentai.cvinbox.dto.CvInboxDtos.RoleMatchDetail;
import com.talentai.cvinbox.entity.CvInboxItem;
import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.repository.CvInboxItemRepository;
import com.talentai.cvinbox.repository.CvInboxSettingsRepository;
import com.talentai.cvinbox.service.ExternalSftpClientService.RemoteCv;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.dto.SourcingDtos.ClaudeMatchResponse;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads whatever CVs are sitting in the SFTP inbox, scores each one against every open
 * requisition using the real Sourcing Agent LLM call ({@link
 * SourcingAgentService#scoreCandidateAgainstRequisition}) — the same prompt/model used for a
 * manual talent-pool upload — then, for the single best-matching role only, persists a
 * SourcingMatch and runs the real Screening Agent ({@link
 * ScreeningAgentService#runScreeningInternal}), exactly like the auto-screen step that follows
 * a manual match. Records the judgment for the dashboard and moves the file into .CONSIDERED/
 * or .REJECTED/ depending on the outcome.
 *
 * Only ever invoked by {@code processNow()} — there is no background scheduler. Note:
 * individual repository/service calls below each manage their own transaction boundary
 * already; this class deliberately does not wrap per-file processing in its own
 * @Transactional method, since calling such a method via plain internal self-invocation
 * (this.processOne(...)) would silently bypass Spring's proxy-based transaction advice anyway.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CvInboxProcessingService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final SourcingAgentService sourcingAgentService;
    private final ScreeningAgentService screeningAgentService;
    private final CvInboxItemRepository cvInboxItemRepository;
    private final CvInboxSettingsRepository cvInboxSettingsRepository;
    private final ExternalSftpClientService externalSftpClientService;
    private final PdfTextExtractor pdfTextExtractor;
    private final ObjectMapper objectMapper;

    @Value("${app.cv-inbox.root-path}")
    private String rootPath;

    public ProcessNowResponse processNow() {
        CvInboxSettings settings = cvInboxSettingsRepository.findById(1L).orElse(null);
        if (settings != null && "EXTERNAL".equals(settings.getMode())) {
            return processExternal(settings);
        }
        return processEmbedded();
    }

    private ProcessNowResponse processEmbedded() {
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        Path consideredDir = root.resolve(".CONSIDERED");
        Path rejectedDir = root.resolve(".REJECTED");

        List<Path> files;
        try {
            Files.createDirectories(root);
            Files.createDirectories(consideredDir);
            Files.createDirectories(rejectedDir);
            try (Stream<Path> stream = Files.list(root)) {
                files = stream.filter(Files::isRegularFile).toList();
            }
        } catch (IOException e) {
            log.error("Failed to list CV inbox folder {}", rootPath, e);
            return ProcessNowResponse.builder().filesFound(0).processed(0).considered(0).excluded(0).failed(0).build();
        }

        int considered = 0, excluded = 0, failed = 0;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            boolean wasConsidered = false;
            try {
                byte[] content = Files.readAllBytes(file);
                wasConsidered = processOne(fileName, content);
                if (wasConsidered) considered++; else excluded++;
            } catch (Exception e) {
                log.error("Failed to process CV inbox file {}", fileName, e);
                recordFailure(fileName, e.getMessage());
                failed++;
            } finally {
                moveProcessedFile(file, wasConsidered ? consideredDir : rejectedDir);
            }
        }

        log.info("CV Inbox processNow (embedded): {} files found, {} considered, {} excluded, {} failed",
                files.size(), considered, excluded, failed);

        return ProcessNowResponse.builder()
                .filesFound(files.size())
                .processed(considered + excluded)
                .considered(considered)
                .excluded(excluded)
                .failed(failed)
                .build();
    }

    private ProcessNowResponse processExternal(CvInboxSettings settings) {
        List<RemoteCv> files;
        try {
            files = externalSftpClientService.listAndFetch(settings);
        } catch (Exception e) {
            log.error("Failed to list/fetch files from external SFTP {}:{}", settings.getExternalHost(), settings.getExternalPort(), e);
            return ProcessNowResponse.builder().filesFound(0).processed(0).considered(0).excluded(0).failed(0).build();
        }

        int considered = 0, excluded = 0, failed = 0;
        for (RemoteCv file : files) {
            boolean wasConsidered = false;
            try {
                wasConsidered = processOne(file.getFileName(), file.getContent());
                if (wasConsidered) considered++; else excluded++;
            } catch (Exception e) {
                log.error("Failed to process external CV inbox file {}", file.getFileName(), e);
                recordFailure(file.getFileName(), e.getMessage());
                failed++;
            } finally {
                externalSftpClientService.moveRemoteFile(settings, file.getFileName(), wasConsidered);
            }
        }

        log.info("CV Inbox processNow (external): {} files found, {} considered, {} excluded, {} failed",
                files.size(), considered, excluded, failed);

        return ProcessNowResponse.builder()
                .filesFound(files.size())
                .processed(considered + excluded)
                .considered(considered)
                .excluded(excluded)
                .failed(failed)
                .build();
    }

    private record ScoredRole(JobRequisition role, ClaudeMatchResponse score) {}

    /** @return true if the best-matching role cleared the "high fit" bar (CONSIDERED). */
    private boolean processOne(String fileName, byte[] content) throws IOException {
        String resumeText = pdfTextExtractor.extractText(fileName, content);

        if (resumeText == null || resumeText.isBlank()) {
            throw new IOException("Could not extract any text from the file.");
        }

        String email = extractEmail(resumeText);
        String name = extractHeuristicName(resumeText);

        Candidate candidate = (email != null) ? candidateRepository.findByEmail(email).orElse(null) : null;
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(name != null ? name : fileName)
                    .email(email)
                    .resumeText(resumeText)
                    .resumeFilename(fileName)
                    .sourceChannel("SFTP_INBOX")
                    .build();
        } else {
            // Refresh identity from the newly uploaded file too — otherwise a stale name
            // from whichever CV first claimed this email lingers on every later match
            // (matches the existing-candidate update pattern in SourcingAgentService).
            if (name != null) candidate.setFullName(name);
            candidate.setResumeText(resumeText);
            candidate.setResumeFilename(fileName);
        }
        final Candidate finalCandidate = candidateRepository.save(candidate);

        // Score against every open role with the real Sourcing Agent LLM call, purely to rank
        // and pick the best fit — nothing is persisted here. A role that fails to score (AI
        // hiccup) is just skipped rather than failing the whole file.
        List<ScoredRole> scored = jobRequisitionRepository.findByStatus("OPEN").stream()
                .<ScoredRole>mapMulti((role, consumer) -> {
                    try {
                        consumer.accept(new ScoredRole(role, sourcingAgentService.scoreCandidateAgainstRequisition(finalCandidate, role)));
                    } catch (Exception e) {
                        log.warn("Skipping role {} for candidate {} — scoring failed: {}", role.getId(), finalCandidate.getId(), e.getMessage());
                    }
                })
                .toList();

        ScoredRole best = scored.stream()
                .max(Comparator.comparing(s -> s.score().getMatchScore()))
                .orElse(null);

        boolean considered = best != null
                && best.score().getMatchScore().compareTo(SourcingAgentService.HIGH_FIT_THRESHOLD) >= 0;

        // Only the single best-matching role gets an actual SourcingMatch/ScreeningResult/
        // PipelineStage entry — the other roles' scores still show in the breakdown below for
        // transparency, they just don't clutter Sourcing/Screening/Pipeline with every role
        // compared. Screening mirrors the same auto-screen step a manual talent-pool upload
        // triggers after a match (SourcingAgentService#matchCandidateToRequisition).
        String bestPipelineStage = null;
        if (best != null) {
            sourcingAgentService.persistMatch(finalCandidate, best.role(), best.score());
            try {
                screeningAgentService.runScreeningInternal(finalCandidate, best.role());
            } catch (Exception e) {
                log.warn("Auto-screening failed for candidate {} / requisition {}: {}",
                        finalCandidate.getId(), best.role().getId(), e.getMessage());
            }
            bestPipelineStage = pipelineStageRepository
                    .findByCandidateIdAndRequisitionId(finalCandidate.getId(), best.role().getId())
                    .map(stage -> stage.getStage())
                    .orElse(null);
        }

        final ScoredRole finalBest = best;
        final String finalBestPipelineStage = bestPipelineStage;
        List<RoleMatchDetail> details = scored.stream()
                .map(s -> RoleMatchDetail.builder()
                        .requisitionId(s.role().getId())
                        .requisitionTitle(s.role().getTitle())
                        .matchScore(s.score().getMatchScore())
                        .rationale(s.score().getRationale())
                        .pipelineStage(finalBest != null && s.role().getId().equals(finalBest.role().getId())
                                ? finalBestPipelineStage : null)
                        .build())
                .toList();

        cvInboxItemRepository.save(CvInboxItem.builder()
                .fileName(fileName)
                .candidateId(candidate.getId())
                .candidateName(candidate.getFullName())
                .candidateEmail(candidate.getEmail())
                .decision(considered ? "CONSIDERED" : "EXCLUDED")
                .status("PROCESSED")
                .bestRequisitionId(best != null ? best.role().getId() : null)
                .bestRequisitionTitle(best != null ? best.role().getTitle() : null)
                .bestMatchScore(best != null ? best.score().getMatchScore() : null)
                .bestRationale(best != null ? best.score().getRationale() : null)
                .matchDetailsJson(toJson(details))
                .processedAt(LocalDateTime.now())
                .build());

        return considered;
    }

    private void recordFailure(String fileName, String errorMessage) {
        cvInboxItemRepository.save(CvInboxItem.builder()
                .fileName(fileName)
                .decision("EXCLUDED")
                .status("FAILED")
                .errorMessage(errorMessage != null ? errorMessage : "Unknown processing error")
                .processedAt(LocalDateTime.now())
                .build());
    }

    private void moveProcessedFile(Path file, Path targetDir) {
        try {
            Path target = targetDir.resolve(file.getFileName());
            if (Files.exists(target)) {
                // Don't clobber an identically-named file processed on a previous run
                target = targetDir.resolve(System.currentTimeMillis() + "_" + file.getFileName());
            }
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to move processed file {} to {}", file.getFileName(), targetDir.getFileName(), e);
        }
    }

    private String extractEmail(String resumeText) {
        Matcher m = EMAIL_PATTERN.matcher(resumeText);
        return m.find() ? m.group() : null;
    }

    /** First non-blank line that looks like a person's name (2-5 title-cased words). */
    private String extractHeuristicName(String resumeText) {
        for (String line : resumeText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            String[] words = trimmed.split("\\s+");
            if (words.length >= 2 && words.length <= 5 && trimmed.matches("[A-Za-z .'-]+")) {
                StringBuilder sb = new StringBuilder();
                for (String word : words) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
                }
                return sb.toString();
            }
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
