package com.talentai.common.controller;

import com.talentai.common.dto.JobRequisitionDtos.*;
import com.talentai.common.service.RequisitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requisitions")
@RequiredArgsConstructor
public class RequisitionController {

    private final RequisitionService requisitionService;

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

    @PatchMapping("/{id}/status")
    public ResponseEntity<RequisitionResponse> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(requisitionService.updateStatus(id, body.get("status")));
    }

    @PostMapping("/parse-jd")
    public ResponseEntity<CreateRequisitionRequest> parseJd(@RequestBody ParseJdRequest body) {
        return ResponseEntity.ok(requisitionService.parseJd(body.getText()));
    }
}
