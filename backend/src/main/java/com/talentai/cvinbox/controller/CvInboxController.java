package com.talentai.cvinbox.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.talentai.cvinbox.config.SftpServerConfig;
import com.talentai.cvinbox.dto.CvInboxDtos.ArchiveRequest;
import com.talentai.cvinbox.dto.CvInboxDtos.CvInboxItemResponse;
import com.talentai.cvinbox.dto.CvInboxDtos.CvInboxSettingsResponse;
import com.talentai.cvinbox.dto.CvInboxDtos.ProcessNowResponse;
import com.talentai.cvinbox.dto.CvInboxDtos.RoleMatchDetail;
import com.talentai.cvinbox.dto.CvInboxDtos.SaveSettingsRequest;
import com.talentai.cvinbox.dto.CvInboxDtos.SftpConnectionInfo;
import com.talentai.cvinbox.dto.CvInboxDtos.TestConnectionRequest;
import com.talentai.cvinbox.dto.CvInboxDtos.TestConnectionResponse;
import com.talentai.cvinbox.entity.CvInboxItem;
import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.repository.CvInboxItemRepository;
import com.talentai.cvinbox.repository.CvInboxSettingsRepository;
import com.talentai.cvinbox.service.CvInboxProcessingService;
import com.talentai.cvinbox.service.ExternalSftpClientService;
import com.talentai.cvinbox.service.ExternalSftpClientService.TestConnectionResult;
import com.talentai.cvinbox.service.GoogleDriveClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cv-inbox")
@RequiredArgsConstructor
public class CvInboxController {

    private static final long SETTINGS_ID = 1L;

    private final CvInboxItemRepository cvInboxItemRepository;
    private final CvInboxSettingsRepository cvInboxSettingsRepository;
    private final CvInboxProcessingService processingService;
    private final ExternalSftpClientService externalSftpClientService;
    private final GoogleDriveClientService googleDriveClientService;
    private final SftpServerConfig sftpServerConfig;
    private final ObjectMapper objectMapper;
    private final com.talentai.screening.repository.ScreeningResultRepository screeningResultRepository;

    @GetMapping("/items")
    public ResponseEntity<List<CvInboxItemResponse>> getItems(@RequestParam String decision) {
        List<CvInboxItem> items = cvInboxItemRepository.findByDecisionAndArchivedFalseOrderByProcessedAtDesc(decision);
        return ResponseEntity.ok(items.stream().map(this::toResponse).toList());
    }

    /** Archives one or more items (an individual row, or every id currently shown for "select all")
     *  so they stop appearing in the Considered/Excluded tables without deleting the audit record. */
    @PostMapping("/items/archive")
    public ResponseEntity<Map<String, Integer>> archiveItems(@RequestBody ArchiveRequest request) {
        List<CvInboxItem> items = cvInboxItemRepository.findAllById(
                request.getIds() != null ? request.getIds() : List.of());
        items.forEach(item -> item.setArchived(true));
        cvInboxItemRepository.saveAll(items);
        return ResponseEntity.ok(Map.of("archived", items.size()));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<CvInboxItemResponse> getItem(@PathVariable Long id) {
        return cvInboxItemRepository.findById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/process-now")
    public ResponseEntity<ProcessNowResponse> processNow() {
        return ResponseEntity.ok(processingService.processNow());
    }

    /** Reflects whichever source (embedded or external) is currently active. */
    @GetMapping("/connection-info")
    public ResponseEntity<SftpConnectionInfo> connectionInfo() {
        CvInboxSettings settings = currentSettings();
        if ("GOOGLE_DRIVE".equals(settings.getMode())) {
            return ResponseEntity.ok(SftpConnectionInfo.builder()
                    .mode("GOOGLE_DRIVE")
                    .googleFolderId(settings.getGoogleFolderId())
                    .googleServiceAccountEmail(googleDriveClientService.serviceAccountEmail(settings.getGoogleServiceAccountJson()))
                    .build());
        }
        if ("EXTERNAL".equals(settings.getMode())) {
            return ResponseEntity.ok(SftpConnectionInfo.builder()
                    .mode("EXTERNAL")
                    .host(settings.getExternalHost())
                    .port(settings.getExternalPort() != null ? settings.getExternalPort() : 0)
                    .username(settings.getExternalUsername())
                    .build());
        }
        return ResponseEntity.ok(SftpConnectionInfo.builder()
                .mode("EMBEDDED")
                .host("localhost")
                .port(settings.getEmbeddedPort() != null ? settings.getEmbeddedPort() : 0)
                .username(settings.getEmbeddedUsername())
                .build());
    }

    @GetMapping("/settings")
    public ResponseEntity<CvInboxSettingsResponse> getSettings() {
        CvInboxSettings s = currentSettings();
        return ResponseEntity.ok(CvInboxSettingsResponse.builder()
                .mode(s.getMode())
                .embeddedPort(s.getEmbeddedPort())
                .embeddedUsername(s.getEmbeddedUsername())
                .embeddedPasswordSet(s.getEmbeddedPassword() != null && !s.getEmbeddedPassword().isBlank())
                .externalHost(s.getExternalHost())
                .externalPort(s.getExternalPort())
                .externalUsername(s.getExternalUsername())
                .externalPasswordSet(s.getExternalPassword() != null && !s.getExternalPassword().isBlank())
                .externalRootPath(s.getExternalRootPath())
                .proxyEnabled(Boolean.TRUE.equals(s.getProxyEnabled()))
                .proxyHost(s.getProxyHost())
                .proxyPort(s.getProxyPort())
                .googleFolderId(s.getGoogleFolderId())
                .googleServiceAccountEmail(googleDriveClientService.serviceAccountEmail(s.getGoogleServiceAccountJson()))
                .googleKeySet(s.getGoogleServiceAccountJson() != null && !s.getGoogleServiceAccountJson().isBlank())
                .build());
    }

    /** Saves the settings and, for EMBEDDED mode, restarts the SFTP server immediately with the
     *  new port/credentials; for EXTERNAL mode, stops the embedded server since HR is no longer
     *  pushing into us. A blank password in the request means "keep the stored password". */
    @PutMapping("/settings")
    public ResponseEntity<CvInboxSettingsResponse> saveSettings(@RequestBody SaveSettingsRequest request) {
        // Only overwrite a field when the request actually provided it — a request that only
        // carries one mode's fields (e.g. a client switching modes without resending the other
        // mode's values) must not wipe out the settings for the mode it isn't editing right now.
        CvInboxSettings s = currentSettings();
        if (request.getMode() != null) s.setMode(request.getMode());
        if (request.getEmbeddedPort() != null) s.setEmbeddedPort(request.getEmbeddedPort());
        if (request.getEmbeddedUsername() != null) s.setEmbeddedUsername(request.getEmbeddedUsername());
        if (request.getEmbeddedPassword() != null && !request.getEmbeddedPassword().isBlank()) {
            s.setEmbeddedPassword(request.getEmbeddedPassword());
        }
        if (request.getExternalHost() != null) s.setExternalHost(request.getExternalHost());
        if (request.getExternalPort() != null) s.setExternalPort(request.getExternalPort());
        if (request.getExternalUsername() != null) s.setExternalUsername(request.getExternalUsername());
        if (request.getExternalPassword() != null && !request.getExternalPassword().isBlank()) {
            s.setExternalPassword(request.getExternalPassword());
        }
        if (request.getExternalRootPath() != null) s.setExternalRootPath(request.getExternalRootPath());
        s.setProxyEnabled(request.isProxyEnabled());
        if (request.getProxyHost() != null) s.setProxyHost(request.getProxyHost());
        if (request.getProxyPort() != null) s.setProxyPort(request.getProxyPort());
        if (request.getGoogleFolderId() != null) {
            s.setGoogleFolderId(GoogleDriveClientService.extractFolderId(request.getGoogleFolderId()));
        }
        if (request.getGoogleServiceAccountJson() != null && !request.getGoogleServiceAccountJson().isBlank()) {
            googleDriveClientService.validateKey(request.getGoogleServiceAccountJson());
            s.setGoogleServiceAccountJson(request.getGoogleServiceAccountJson());
        }
        s = cvInboxSettingsRepository.save(s);

        // Only the embedded mode hosts an SFTP server; the other two pull from somewhere else.
        if (!"EMBEDDED".equals(s.getMode())) {
            sftpServerConfig.stop();
        } else {
            sftpServerConfig.restart(s);
        }

        return getSettings();
    }

    /** A blank password here means the same thing it does in {@link #saveSettings}: "use whatever
     *  is already stored" — otherwise testing an untouched, already-working saved config (the
     *  most common case: open the drawer, click Test Connection without retyping anything) would
     *  falsely report an auth failure against an empty password. */
    @PostMapping("/settings/test-connection")
    public ResponseEntity<TestConnectionResponse> testConnection(@RequestBody TestConnectionRequest request) {
        CvInboxSettings stored = currentSettings();

        if ("GOOGLE_DRIVE".equals(request.getMode())) {
            String key = (request.getGoogleServiceAccountJson() != null && !request.getGoogleServiceAccountJson().isBlank())
                    ? request.getGoogleServiceAccountJson() : stored.getGoogleServiceAccountJson();
            var result = googleDriveClientService.testConnection(key,
                    GoogleDriveClientService.extractFolderId(request.getGoogleFolderId()));
            return ResponseEntity.ok(TestConnectionResponse.builder()
                    .success(result.isSuccess()).message(result.getMessage()).build());
        }

        if ("EXTERNAL".equals(request.getMode())) {
            String password = (request.getExternalPassword() != null && !request.getExternalPassword().isBlank())
                    ? request.getExternalPassword() : stored.getExternalPassword();
            TestConnectionResult result = externalSftpClientService.testConnection(
                    request.getExternalHost(), request.getExternalPort() != null ? request.getExternalPort() : 22,
                    request.getExternalUsername(), password,
                    request.isProxyEnabled(), request.getProxyHost(), request.getProxyPort());
            return ResponseEntity.ok(TestConnectionResponse.builder()
                    .success(result.isSuccess()).message(result.getMessage()).build());
        }

        // EMBEDDED: test by connecting to our own running server as a client, same as HR would.
        String password = (request.getEmbeddedPassword() != null && !request.getEmbeddedPassword().isBlank())
                ? request.getEmbeddedPassword() : stored.getEmbeddedPassword();
        TestConnectionResult result = externalSftpClientService.testConnection(
                "localhost", request.getEmbeddedPort() != null ? request.getEmbeddedPort() : 22,
                request.getEmbeddedUsername(), password,
                false, null, null);
        return ResponseEntity.ok(TestConnectionResponse.builder()
                .success(result.isSuccess()).message(result.getMessage()).build());
    }

    private CvInboxSettings currentSettings() {
        return cvInboxSettingsRepository.findById(SETTINGS_ID)
                .orElseGet(() -> cvInboxSettingsRepository.save(CvInboxSettings.builder()
                        .id(SETTINGS_ID).mode("EMBEDDED").proxyEnabled(false).build()));
    }

    private CvInboxItemResponse toResponse(CvInboxItem item) {
        List<RoleMatchDetail> roleMatches = Collections.emptyList();
        if (item.getMatchDetailsJson() != null) {
            try {
                roleMatches = objectMapper.readValue(item.getMatchDetailsJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                // Leave empty — the summary fields (bestRequisitionTitle etc.) still render fine.
            }
        }

        // The stored bestMatchScore/bestRationale are a frozen Sourcing Agent snapshot from
        // ingestion time. Once the Screening Agent has actually evaluated the best-matching role,
        // its rubric score is the more current, authoritative one — the same one the Pipeline and
        // Screening Agent boards show — so surface that instead of the stale sourcing estimate.
        java.math.BigDecimal bestMatchScore = item.getBestMatchScore();
        String bestRationale = item.getBestRationale();
        if (item.getCandidateId() != null && item.getBestRequisitionId() != null) {
            var screening = screeningResultRepository
                    .findByCandidateIdAndRequisitionId(item.getCandidateId(), item.getBestRequisitionId())
                    .orElse(null);
            if (screening != null) {
                bestMatchScore = screening.getOverallScore();
                bestRationale = screening.getRationale();
                for (RoleMatchDetail rm : roleMatches) {
                    if (item.getBestRequisitionId().equals(rm.getRequisitionId())) {
                        rm.setMatchScore(screening.getOverallScore());
                        rm.setRationale(screening.getRationale());
                    }
                }
            }
        }

        return CvInboxItemResponse.builder()
                .id(item.getId())
                .fileName(item.getFileName())
                .candidateId(item.getCandidateId())
                .candidateName(item.getCandidateName())
                .candidateEmail(item.getCandidateEmail())
                .decision(item.getDecision())
                .status(item.getStatus())
                .bestRequisitionId(item.getBestRequisitionId())
                .bestRequisitionTitle(item.getBestRequisitionTitle())
                .bestMatchScore(bestMatchScore)
                .bestRationale(bestRationale)
                .errorMessage(item.getErrorMessage())
                .roleMatches(roleMatches)
                .processedAt(item.getProcessedAt())
                .build();
    }
}
