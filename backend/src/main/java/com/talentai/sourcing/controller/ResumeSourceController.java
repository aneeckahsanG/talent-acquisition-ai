package com.talentai.sourcing.controller;

import com.talentai.sourcing.dto.SourcingDtos.*;
import com.talentai.sourcing.entity.ResumeSource;
import com.talentai.sourcing.repository.ResumeSourceRepository;
import com.talentai.sourcing.service.ResumeSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sourcing/resume-sources")
@RequiredArgsConstructor
public class ResumeSourceController {

    private final ResumeSourceRepository resumeSourceRepository;
    private final ResumeSyncService resumeSyncService;

    @GetMapping
    public ResponseEntity<List<ResumeSourceResponse>> getAll() {
        return ResponseEntity.ok(resumeSourceRepository.findAll().stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<ResumeSourceResponse> create(@RequestBody CreateResumeSourceRequest request) {
        ResumeSource source = ResumeSource.builder()
                .name(request.getName())
                .url(request.getUrl())
                .sourceType(request.getSourceType() != null ? request.getSourceType() : "CUSTOM")
                .active(true)
                .build();
        return ResponseEntity.ok(toResponse(resumeSourceRepository.save(source)));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ResumeSourceResponse> toggle(@PathVariable Long id) {
        ResumeSource source = resumeSourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Source not found: " + id));
        source.setActive(!Boolean.TRUE.equals(source.getActive()));
        return ResponseEntity.ok(toResponse(resumeSourceRepository.save(source)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        resumeSourceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Trigger an immediate sync for one source. */
    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, String>> syncOne(@PathVariable Long id) {
        resumeSyncService.syncSource(id);
        return ResponseEntity.ok(Map.of("status", "sync started"));
    }

    /** Trigger sync for all active sources. */
    @PostMapping("/sync-all")
    public ResponseEntity<Map<String, String>> syncAll() {
        resumeSourceRepository.findByActiveTrue()
                .forEach(s -> resumeSyncService.syncSource(s.getId()));
        return ResponseEntity.ok(Map.of("status", "sync started for all active sources"));
    }

    private ResumeSourceResponse toResponse(ResumeSource s) {
        return ResumeSourceResponse.builder()
                .id(s.getId())
                .name(s.getName())
                .url(s.getUrl())
                .sourceType(s.getSourceType())
                .active(s.getActive())
                .lastSyncAt(s.getLastSyncAt())
                .lastSyncCount(s.getLastSyncCount())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
