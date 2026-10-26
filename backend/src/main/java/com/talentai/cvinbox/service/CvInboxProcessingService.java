package com.talentai.cvinbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.admin.service.LetterFingerprint;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.cvinbox.dto.CvInboxDtos.ProcessNowResponse;
import com.talentai.cvinbox.dto.CvInboxDtos.RoleMatchDetail;
import com.talentai.cvinbox.entity.CvInboxItem;
import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.repository.CvInboxItemRepository;
import com.talentai.cvinbox.repository.CvInboxSettingsRepository;
import com.talentai.cvinbox.service.ExternalSftpClientService.RemoteCv;
import com.talentai.cvinbox.service.KeywordMatchScoringService.RoleScore;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads whatever CVs are sitting in the SFTP inbox, scores each one against every open
 * requisition using {@link KeywordMatchScoringService} (plain keyword/string matching — no
 * LLM call, since this feature needs to work without a configured/funded AI provider; swap
 * in a real AI-based scorer later without touching anything else here), records the
 * judgment for the dashboard, and moves the file into .CONSIDERED/ or .REJECTED/ depending
 * on the outcome.
 *
 * Unlike the shared Talent Pool / Sourcing Agent flow (which touches every open role for
 * every candidate), CV Inbox only ever writes a SourcingMatch/ScreeningResult/PipelineStage
 * for the single best-matching role — the other roles' scores are still shown in the
 * dashboard's per-role breakdown, they just don't clutter Pipeline/Screening with entries
 * for roles that aren't actually the candidate's best fit.
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

    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final KeywordMatchScoringService keywordMatchScoringService;
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

    private record ScoredRole(JobRequisition role, RoleScore score) {}

    /** @return true if the best-matching role cleared the "high fit" bar (CONSIDERED). */
    private boolean processOne(String fileName, byte[] content) throws IOException {
        String resumeText = pdfTextExtractor.extractText(fileName, content);

        if (resumeText == null || resumeText.isBlank()) {
            throw new IOException("Could not extract any text from the file.");
        }

        String email = CvIdentityExtractor.extractEmail(resumeText);
        String name = CvIdentityExtractor.extractName(resumeText);

        Candidate candidate = findExistingCandidate(email, name);
        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(name != null ? name : fileName)
                    .email(email)
                    .resumeText(resumeText)
                    .resumeFilename(fileName)
                    .sourceChannel("SFTP_INBOX")
                    .build();
        } else {
            // Same person: keep the CV on file current, but never rename them. Their name was set by
            // whoever created the record (often the candidate themselves) and may be correct where a
            // guess from the CV text is not.
            candidate.setResumeText(resumeText);
            candidate.setResumeFilename(fileName);
        }
        candidate = candidateRepository.save(candidate);

        List<ScoredRole> scored = jobRequisitionRepository.findByStatus("OPEN").stream()
                .map(role -> new ScoredRole(role, keywordMatchScoringService.score(resumeText, role)))
                .toList();

        ScoredRole best = scored.stream()
                .max(Comparator.comparing(s -> s.score().getOverallScore()))
                .orElse(null);

        boolean considered = best != null
                && best.score().getOverallScore().compareTo(SourcingAgentService.HIGH_FIT_THRESHOLD) >= 0;

        // Only the single best-matching role gets an actual SourcingMatch/ScreeningResult/
        // PipelineStage entry — the other roles' scores still show in the breakdown below for
        // transparency, they just don't clutter Pipeline/Screening with every role compared.
        String bestPipelineStage = (best != null)
                ? recordBestRoleMatch(candidate, best.role(), best.score())
                : null;

        final ScoredRole finalBest = best;
        List<RoleMatchDetail> details = scored.stream()
                .map(s -> RoleMatchDetail.builder()
                        .requisitionId(s.role().getId())
                        .requisitionTitle(s.role().getTitle())
                        .matchScore(s.score().getOverallScore())
                        .rationale(s.score().getRationale())
                        .pipelineStage(finalBest != null && s.role().getId().equals(finalBest.role().getId())
                                ? bestPipelineStage : null)
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
                .bestMatchScore(best != null ? best.score().getOverallScore() : null)
                .bestRationale(best != null ? best.score().getRationale() : null)
                .matchDetailsJson(toJson(details))
                .processedAt(LocalDateTime.now())
                .build());

        return considered;
    }

    /** An existing candidate with this email who is the same person (same words in the name), else null.
     *  Two different people who share an address (a shared mailbox, a referee's address) stay separate. */
    private Candidate findExistingCandidate(String email, String name) {
        if (email == null) return null;
        List<Candidate> sameEmail = candidateRepository.findAllByEmailIgnoreCaseOrderByIdAsc(email);
        if (sameEmail.isEmpty()) return null;
        if (name == null) return sameEmail.get(0);   // nothing to tell them apart by: assume the earliest record
        return sameEmail.stream().filter(c -> LetterFingerprint.sameName(c.getFullName(), name)).findFirst().orElse(null);
    }

    /**
     * Creates a SourcingMatch + ScreeningResult + PipelineStage for the best-matching role, but only
     * where none exists yet. A candidate who is already matched, screened, reviewed or further along
     * keeps exactly what they have: a keyword score must never overwrite an AI or human decision, and a
     * re-uploaded CV must never move someone backwards in the pipeline.
     *  @return the candidate's pipeline stage for this role, for the per-role breakdown. */
    private String recordBestRoleMatch(Candidate candidate, JobRequisition role, RoleScore score) {
        if (sourcingMatchRepository.findByCandidateIdAndRequisitionId(candidate.getId(), role.getId()).isEmpty()) {
            sourcingMatchRepository.save(SourcingMatch.builder()
                    .candidateId(candidate.getId()).requisitionId(role.getId())
                    .matchScore(score.getOverallScore()).matchRationale(score.getRationale())
                    .isProactive(true).status("NEW").build());
        }

        String recommendation = score.getOverallScore().compareTo(SourcingAgentService.HIGH_FIT_THRESHOLD) >= 0
                ? "ADVANCE"
                : score.getOverallScore().compareTo(BigDecimal.valueOf(40)) < 0 ? "REJECT" : "REVIEW";

        if (screeningResultRepository.findByCandidateIdAndRequisitionId(candidate.getId(), role.getId()).isEmpty()) {
            screeningResultRepository.save(ScreeningResult.builder()
                    .candidateId(candidate.getId()).requisitionId(role.getId())
                    .overallScore(score.getOverallScore()).skillsScore(score.getSkillsScore())
                    .experienceScore(score.getExperienceScore())
                    .strengths(score.getStrengths()).gaps(score.getGaps()).rationale(score.getRationale())
                    .recommendation(recommendation).isEdgeCase("REVIEW".equals(recommendation))
                    .build());
        }

        PipelineStage stage = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidate.getId(), role.getId())
                .orElse(null);
        if (stage == null) {
            stage = pipelineStageRepository.save(PipelineStage.builder()
                    .candidateId(candidate.getId()).requisitionId(role.getId())
                    .stage("SCREENED").updatedByAgent("SCREENING")
                    .notes("CV Inbox keyword match — recommendation: " + recommendation + " (score: " + score.getOverallScore() + ")")
                    .build());
        }
        return stage.getStage();
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
