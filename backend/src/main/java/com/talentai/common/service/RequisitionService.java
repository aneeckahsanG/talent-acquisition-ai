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

    /** Generates a job description from whatever fields the recruiter has already filled in —
     *  the reverse of {@link #parseJd}, which extracts fields from a pasted description. */
    public GenerateJdResponse generateJd(GenerateJdRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("A job title is required to generate a description.");
        }

        String system = """
                You are a recruiting copywriter who writes professional, LinkedIn-style job
                postings. You will be given a job title and whatever other details happen to be
                filled in (department, location, experience level, key skills) — some may be
                missing. The job title is the primary signal: when other fields are sparse or
                absent, infer realistic, conventional responsibilities and requirements for that
                kind of role from the title and experience level alone, the way a seasoned
                recruiter would when drafting a posting from just a title.

                Write a thorough, segmented job description with these exact plain-text section
                headers, each on its own line, each followed by a blank line:

                About the Role
                2-3 sentences on the role's purpose and impact on the team/product.

                Key Responsibilities
                5-7 bullet points, each starting with "• ", concrete and specific to the title
                and seniority level (not generic filler).

                What We're Looking For
                5-7 bullet points starting with "• " — required skills/experience. Incorporate
                every provided skill explicitly; add other realistic requirements implied by the
                title/level to round it out.

                Nice to Have
                2-4 bullet points starting with "• " for complementary skills that aren't strictly
                required.

                Do not invent a company name. Do not wrap anything in markdown (no #, no **, no
                markdown bullets like "-" or "*" — use "• " for every bullet). Respond with ONLY
                the formatted description text, no preamble, no closing remarks.
                """;

        String userPrompt = """
                Job Title: %s
                Department: %s
                Location: %s
                Experience Level: %s
                Key Skills: %s
                """.formatted(
                request.getTitle(),
                nullToDash(request.getDepartment()),
                nullToDash(request.getLocation()),
                nullToDash(request.getExperienceLevel()),
                nullToDash(request.getRequiredSkills()));

        try {
            String description = chatClient.prompt()
                    .system(system)
                    .user(userPrompt)
                    .call()
                    .content();
            return new GenerateJdResponse(description != null ? description.trim() : "");
        } catch (Exception e) {
            log.error("Failed to generate job description for title '{}'", request.getTitle(), e);
            throw new IllegalStateException(friendlyAiErrorMessage(e));
        }
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "Not specified" : value;
    }

    /** Translates a raw Claude/Spring-AI exception (often a multi-line JSON error body from the
     *  Anthropic API) into a short, human-readable message — the full detail is already logged
     *  server-side via log.error above, so the client never needs to see the raw payload. */
    private String friendlyAiErrorMessage(Exception e) {
        String msg = e.getMessage();
        String lower = msg != null ? msg.toLowerCase() : "";
        if (lower.contains("credit balance") || lower.contains("credit_balance")) {
            return "AI is unavailable right now — the Anthropic account has run out of API credits. Please top up billing and try again.";
        }
        if (lower.contains("authentication_error") || lower.contains("invalid x-api-key") || lower.contains("invalid api key")) {
            return "AI is unavailable — the configured Anthropic API key appears to be missing or invalid.";
        }
        if (lower.contains("overloaded_error") || lower.contains("rate_limit")) {
            return "AI is temporarily overloaded. Please try again in a moment.";
        }
        if (lower.contains("timeout") || lower.contains("connect")) {
            return "Could not reach the AI service. Please check your connection and try again.";
        }
        return "AI is currently unavailable. Please try again later.";
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
            throw new IllegalStateException(friendlyAiErrorMessage(e));
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
