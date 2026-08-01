package com.talentai.common.controller;

import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateRepository candidateRepository;

    @GetMapping
    public ResponseEntity<List<CandidateSummary>> getAll() {
        return ResponseEntity.ok(candidateRepository.findAll().stream()
                .map(this::toSummary)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CandidateSummary> getById(@PathVariable Long id) {
        Candidate candidate = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));
        return ResponseEntity.ok(toSummary(candidate));
    }

    private CandidateSummary toSummary(Candidate c) {
        return CandidateSummary.builder()
                .id(c.getId())
                .fullName(c.getFullName())
                .email(c.getEmail())
                .phone(c.getPhone())
                .sourceChannel(c.getSourceChannel())
                .headline(c.getHeadline())
                .skills(c.getSkills())
                .yearsExperience(c.getYearsExperience())
                .resumeFilename(c.getResumeFilename())
                .profileUrl(c.getProfileUrl())
                .createdAt(c.getCreatedAt())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateSummary {
        private Long id;
        private String fullName;
        private String email;
        private String phone;
        private String sourceChannel;
        private String headline;
        private String skills;
        private BigDecimal yearsExperience;
        private String resumeFilename;
        private String profileUrl;
        private LocalDateTime createdAt;
    }
}
