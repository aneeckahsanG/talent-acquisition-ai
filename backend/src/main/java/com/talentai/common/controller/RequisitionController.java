package com.talentai.common.controller;

import com.talentai.common.dto.JobRequisitionDtos.*;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.RequisitionService;
import com.talentai.sourcing.entity.MockJobRole;
import com.talentai.sourcing.repository.MockJobRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requisitions")
@RequiredArgsConstructor
public class RequisitionController {

    private final RequisitionService requisitionService;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final MockJobRoleRepository mockJobRoleRepository;

    @Value("${server.port:8080}")
    private String serverPort;

    @GetMapping
    public ResponseEntity<List<RequisitionResponse>> getAll(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(requisitionService.getAll(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequisitionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(requisitionService.getById(id));
    }

    @PostMapping
    public ResponseEntity<RequisitionResponse> create(@RequestBody CreateRequisitionRequest request, Authentication authentication) {
        return ResponseEntity.ok(requisitionService.create(request, authentication.getName()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<RequisitionResponse> update(@PathVariable Long id, @RequestBody CreateRequisitionRequest request) {
        return ResponseEntity.ok(requisitionService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<RequisitionResponse> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(requisitionService.updateStatus(id, body.get("status")));
    }

    @PostMapping("/parse-jd")
    public ResponseEntity<CreateRequisitionRequest> parseJd(@RequestBody ParseJdRequest body) {
        return ResponseEntity.ok(requisitionService.parseJd(body.getText()));
    }

    @PostMapping("/bulk-parse")
    public ResponseEntity<List<CreateRequisitionRequest>> bulkParse(@RequestBody ParseJdRequest body) {
        return ResponseEntity.ok(requisitionService.bulkParseJd(body.getText()));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<RequisitionResponse>> bulkCreate(@RequestBody List<CreateRequisitionRequest> requests, Authentication authentication) {
        return ResponseEntity.ok(requisitionService.bulkCreate(requests, authentication.getName()));
    }

    @PostMapping("/{id}/publish-to-jobboard")
    public ResponseEntity<Map<String, Object>> publishToJobBoard(@PathVariable Long id) {
        return jobRequisitionRepository.findById(id)
                .map(req -> ResponseEntity.ok(publishOne(req)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/publish-all-to-jobboard")
    public ResponseEntity<Map<String, Object>> publishAllToJobBoard() {
        List<JobRequisition> open = jobRequisitionRepository.findByStatus("OPEN");
        List<Map<String, Object>> published = new ArrayList<>();
        for (JobRequisition req : open) {
            published.add(publishOne(req));
        }
        return ResponseEntity.ok(Map.of("published", published.size(), "roles", published));
    }

    private Map<String, Object> publishOne(JobRequisition req) {
        // Skip if already linked to a mock job board role
        boolean alreadyLinked = mockJobRoleRepository.findAll().stream()
                .anyMatch(r -> req.getId().equals(r.getRequisitionId()) && Boolean.TRUE.equals(r.getActive()));
        if (alreadyLinked) {
            return Map.of("id", req.getId(), "title", req.getTitle(), "status", "already_published");
        }

        String requirements = req.getRequiredSkills() != null ? req.getRequiredSkills() : "";
        if (req.getExperienceLevel() != null) {
            requirements = req.getExperienceLevel() + " level. " + requirements;
        }

        MockJobRole role = MockJobRole.builder()
                .title(req.getTitle())
                .department(req.getDepartment())
                .location(req.getLocation() != null ? req.getLocation() : "Kuala Lumpur, Malaysia")
                .description(req.getDescription())
                .requirements(requirements)
                .company("TalentAI Company")
                .requisitionId(req.getId())
                .webhookUrl("http://localhost:" + serverPort + "/api/inbound/applications/" + req.getId())
                .build();

        MockJobRole saved = mockJobRoleRepository.save(role);
        return Map.of("id", req.getId(), "title", req.getTitle(), "mockJobRoleId", saved.getId(), "status", "published");
    }
}
