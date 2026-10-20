package com.talentai.common.service;

import org.springframework.ai.chat.client.ChatClient;
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
    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    private record ParsedJd(
            String title, String department, String location,
            String experienceLevel, String requiredSkills, String description) {}

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
    public RequisitionResponse update(Long id, CreateRequisitionRequest request) {
        JobRequisition req = jobRequisitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + id));

        if (request.getTitle() != null)           req.setTitle(request.getTitle());
        if (request.getDepartment() != null)      req.setDepartment(request.getDepartment());
        if (request.getLocation() != null)        req.setLocation(request.getLocation());
        if (request.getDescription() != null)     req.setDescription(request.getDescription());
        if (request.getRequiredSkills() != null)  req.setRequiredSkills(request.getRequiredSkills());
        if (request.getExperienceLevel() != null) req.setExperienceLevel(request.getExperienceLevel());
        if (request.getRequiredInterviewRounds() != null) req.setRequiredInterviewRounds(request.getRequiredInterviewRounds());

        return toResponse(jobRequisitionRepository.save(req));
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

    /** Parse multiple roles from one block of unstructured text (newline or header-separated JDs). */
    public List<CreateRequisitionRequest> bulkParseJd(String rawText) {
        String system = """
                You are a structured data extractor. Given a block of text that describes one or more job roles (possibly separated by blank lines, horizontal rules, or numbered sections), extract each distinct role and return a JSON array.
                Each element must be an object with these fields:
                  title          - job title (string, required)
                  department     - department or team (string, may be null)
                  location       - location or "Remote" (string, may be null)
                  experienceLevel - one of: JUNIOR, MID, SENIOR, LEAD (infer from content; default MID)
                  requiredSkills  - comma-separated list of key skills/technologies (string, may be null)
                  description    - concise 2-4 sentence summary of the role (string, may be null)
                Return ONLY a valid JSON array, no markdown fences, no explanation.
                Example: [{"title":"...","department":"...","location":"...","experienceLevel":"...","requiredSkills":"...","description":"..."}]
                """;
        try {
            ParsedJd[] parsed = chatClient.prompt()
                    .system(system)
                    .user("Text:\n\n" + rawText)
                    .call()
                    .entity(ParsedJd[].class);
            if (parsed == null || parsed.length == 0) return List.of();
            return java.util.Arrays.stream(parsed).map(p -> {
                CreateRequisitionRequest req = new CreateRequisitionRequest();
                req.setTitle(p.title() != null ? p.title() : "");
                req.setDepartment(p.department() != null ? p.department() : "");
                req.setLocation(p.location() != null ? p.location() : "");
                req.setExperienceLevel(p.experienceLevel() != null ? p.experienceLevel() : "MID");
                req.setRequiredSkills(p.requiredSkills() != null ? p.requiredSkills() : "");
                req.setDescription(p.description() != null ? p.description() : "");
                return req;
            }).toList();
        } catch (Exception e) {
            log.error("Failed to bulk-parse JD text", e);
            throw new IllegalStateException("Could not parse job descriptions: " + e.getMessage());
        }
    }

    /** Create multiple requisitions in one transaction. */
    @Transactional
    public List<RequisitionResponse> bulkCreate(List<CreateRequisitionRequest> requests, String username) {
        Long userId = appUserRepository.findByUsername(username).map(AppUser::getId).orElse(null);
        return requests.stream()
                .filter(r -> r.getTitle() != null && !r.getTitle().isBlank())
                .map(request -> {
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
                    eventPublisher.publishEvent(new RequisitionCreatedEvent(saved.getId()));
                    return toResponse(saved);
                })
                .toList();
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

        try {
            ParsedJd parsed = chatClient.prompt()
                    .system(system)
                    .user("Job description:\n\n" + rawText)
                    .call()
                    .entity(ParsedJd.class);
            CreateRequisitionRequest req = new CreateRequisitionRequest();
            req.setTitle(parsed.title() != null ? parsed.title() : "");
            req.setDepartment(parsed.department() != null ? parsed.department() : "");
            req.setLocation(parsed.location() != null ? parsed.location() : "");
            req.setExperienceLevel(parsed.experienceLevel() != null ? parsed.experienceLevel() : "MID");
            req.setRequiredSkills(parsed.requiredSkills() != null ? parsed.requiredSkills() : "");
            req.setDescription(parsed.description() != null ? parsed.description() : "");
            return req;
        } catch (Exception e) {
            log.error("Failed to get or parse Claude JD response", e);
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
