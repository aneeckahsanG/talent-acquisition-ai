package com.talentai.sourcing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.sourcing.dto.SourcingDtos.AddTalentPoolCandidateRequest;
import com.talentai.sourcing.dto.SourcingDtos.DirectApplyRequest;
import com.talentai.sourcing.entity.ResumeSource;
import com.talentai.sourcing.repository.ResumeSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeSyncService {

    private final ResumeSourceRepository resumeSourceRepository;
    private final SourcingAgentService sourcingAgentService;
    private final ObjectMapper objectMapper;

    /** Scheduled sync — runs every 6 hours automatically. */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000)
    public void scheduledSync() {
        log.info("Running scheduled resume source sync...");
        resumeSourceRepository.findByActiveTrue().forEach(source -> syncSource(source.getId()));
    }

    /** Manual sync for a single source — runs async so API returns immediately. */
    @Async
    public void syncSource(Long sourceId) {
        ResumeSource source = resumeSourceRepository.findById(sourceId).orElse(null);
        if (source == null || !Boolean.TRUE.equals(source.getActive())) return;

        log.info("Syncing resume source '{}' from {}", source.getName(), source.getUrl());
        int count = 0;
        int skipped = 0;

        try {
            String json = WebClient.create()
                    .get()
                    .uri(source.getUrl())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<Map<String, Object>> candidates = objectMapper.readValue(json, new TypeReference<>() {});

            for (Map<String, Object> c : candidates) {
                try {
                    Object reqIdObj = c.get("requisitionId");
                    if (reqIdObj != null && !reqIdObj.toString().isBlank()) {
                        // Linked to a specific TalentAI requisition — route as direct applicant
                        Long requisitionId = Long.valueOf(reqIdObj.toString());
                        DirectApplyRequest req = new DirectApplyRequest();
                        req.setFullName(str(c, "fullName"));
                        req.setEmail(str(c, "email"));
                        req.setHeadline(str(c, "headline"));
                        req.setSkills(str(c, "skills"));
                        req.setResumeText(str(c, "resumeText"));
                        if (c.get("yearsExperience") != null) {
                            req.setYearsExperience(new BigDecimal(c.get("yearsExperience").toString()));
                        }
                        sourcingAgentService.directApply(requisitionId, req);
                        log.info("Synced direct applicant '{}' → requisition {}", req.getFullName(), requisitionId);
                    } else {
                        // No linked requisition — add to general talent pool
                        AddTalentPoolCandidateRequest req = new AddTalentPoolCandidateRequest();
                        req.setFullName(str(c, "fullName"));
                        req.setEmail(str(c, "email"));
                        req.setHeadline(str(c, "headline"));
                        req.setSkills(str(c, "skills"));
                        req.setResumeText(str(c, "resumeText"));
                        req.setSourceChannel(str(c, "sourceChannel") != null ? str(c, "sourceChannel") : source.getSourceType());
                        req.setProfileUrl(str(c, "profileUrl"));
                        req.setHasResume(true);
                        if (c.get("yearsExperience") != null) {
                            req.setYearsExperience(new BigDecimal(c.get("yearsExperience").toString()));
                        }
                        sourcingAgentService.addCandidateAndMatch(req);
                        log.info("Synced talent pool candidate '{}'", str(c, "fullName"));
                    }
                    count++;
                } catch (IllegalStateException e) {
                    // Duplicate direct application — skip silently
                    skipped++;
                } catch (IllegalArgumentException e) {
                    // Duplicate candidate or closed role — skip silently
                    skipped++;
                } catch (Exception e) {
                    log.warn("Failed to sync candidate '{}' from source '{}': {}", c.get("fullName"), source.getName(), e.getMessage());
                }
            }

            source.setLastSyncAt(LocalDateTime.now());
            source.setLastSyncCount(count);
            resumeSourceRepository.save(source);
            log.info("Sync complete for '{}': {} added, {} skipped (duplicates)", source.getName(), count, skipped);

        } catch (Exception e) {
            log.error("Failed to sync source '{}': {}", source.getName(), e.getMessage());
            source.setLastSyncAt(LocalDateTime.now());
            source.setLastSyncCount(-1); // -1 signals error
            resumeSourceRepository.save(source);
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
