package com.talentai.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.client.ClaudeApiClient;
import com.talentai.common.dto.JobRequisitionDtos.*;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RequisitionService {

    private final JobRequisitionRepository jobRequisitionRepository;
    private final AppUserRepository appUserRepository;
    private final ClaudeApiClient claudeApiClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<RequisitionResponse> getAll(String status) {
        List<JobRequisition> requisitions = (status != null && !status.isBlank())
                ? jobRequisitionRepository.findByStatus(status.toUpperCase())
                : jobRequisitionRepository.findAll();

        return requisitions.stream().map(this::toResponse).toList();
    }

    public RequisitionResponse getById(Long id) {
        JobRequisition req = jobRequisitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + id));
        return toResponse(req);
    }

    @Transactional
    public RequisitionResponse create(CreateRequisitionRequest request, String username) {
        Long userId = appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);

        JobRequisition req = JobRequisition.builder()
                .title(request.getTitle())
                .department(request.getDepartment())
                .location(request.getLocation())
                .description(request.getDescription())
                .requiredSkills(request.getRequiredSkills())
                .experienceLevel(request.getExperienceLevel())
                .requiredInterviewRounds(request.getRequiredInterviewRounds() != null ? request.getRequiredInterviewRounds() : 2)
                .status("OPEN")
                .createdBy(userId)
                .build();

        JobRequisition saved = jobRequisitionRepository.save(req);
        // Publish event — listener fires AFTER this transaction commits so the row is visible
        eventPublisher.publishEvent(new RequisitionCreatedEvent(saved.getId()));
        return toResponse(saved);
    }

    @Transactional
    public RequisitionResponse updateStatus(Long id, String status) {
        JobRequisition req = jobRequisitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + id));

        String normalized = status.toUpperCase();
        if (!List.of("OPEN", "ON_HOLD", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("Status must be one of OPEN, ON_HOLD, CLOSED");
        }
        req.setStatus(normalized);
        JobRequisition saved = jobRequisitionRepository.save(req);
        if ("OPEN".equals(normalized)) {
            eventPublisher.publishEvent(new RequisitionCreatedEvent(saved.getId()));
        }
        return toResponse(saved);
    }

    public CreateRequisitionRequest parseJd(String rawText) {
        String system = """
                You are a structured data extractor. Given a raw job description, extract the following fields and respond with ONLY valid JSON, no markdown fences.
                Fields:
                  title        - job title (string)
                  department   - department or team (string, may be null)
                  location     - location or "Remote" (string, may be null)
                  experienceLevel - one of: JUNIOR, MID, SENIOR, LEAD (infer from content)
                  requiredSkills  - comma-separated list of key skills/technologies (string)
                  description  - a concise 2-4 sentence summary of the role (string)
                Return exactly: {"title":"...","department":"...","location":"...","experienceLevel":"...","requiredSkills":"...","description":"..."}
                """;

        String raw = claudeApiClient.sendPrompt(system, "Job description:\n\n" + rawText);
        String json = claudeApiClient.stripJsonFences(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            CreateRequisitionRequest req = new CreateRequisitionRequest();
            req.setTitle(node.path("title").asText(""));
            req.setDepartment(node.path("department").asText(""));
            req.setLocation(node.path("location").asText(""));
            req.setExperienceLevel(node.path("experienceLevel").asText("MID"));
            req.setRequiredSkills(node.path("requiredSkills").asText(""));
            req.setDescription(node.path("description").asText(""));
            return req;
        } catch (Exception e) {
            log.error("Failed to parse Claude JD response: {}", json, e);
            throw new IllegalStateException("Could not parse job description: " + e.getMessage());
        }
    }

    private RequisitionResponse toResponse(JobRequisition req) {
        return RequisitionResponse.builder()
                .id(req.getId())
                .title(req.getTitle())
                .department(req.getDepartment())
                .location(req.getLocation())
                .description(req.getDescription())
                .requiredSkills(req.getRequiredSkills())
                .experienceLevel(req.getExperienceLevel())
                .status(req.getStatus())
                .requiredInterviewRounds(req.getRequiredInterviewRounds() != null ? req.getRequiredInterviewRounds() : 2)
                .createdAt(req.getCreatedAt())
                .build();
    }

    /** Simple event payload carrying the new requisition's ID. */
    public record RequisitionCreatedEvent(Long requisitionId) {}
}
