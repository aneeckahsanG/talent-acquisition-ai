package com.talentai.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.dto.AdminDtos.*;
import com.talentai.admin.entity.CandidateStatusUpdate;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.repository.CandidateStatusUpdateRepository;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.common.client.ClaudeApiClient;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ADMINISTRATIVE / COORDINATION AGENT
 *
 * Responsibilities:
 *  - Propose interview time slots for shortlisted candidates, optionally
 *    using Claude to draft a candidate-facing scheduling message.
 *  - Track interview lifecycle (proposed -> confirmed -> completed).
 *  - Log simulated candidate status update notifications.
 *
 * Human-in-the-loop: candidate-facing messages are generated as DRAFTS
 * for recruiter approval before being marked as sent. Confirming an
 * interview slot is a recruiter action.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAgentService {

    private static final String AGENT_NAME = "ADMIN";
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ClaudeApiClient claudeApiClient;
    private final InterviewScheduleRepository interviewScheduleRepository;
    private final CandidateStatusUpdateRepository candidateStatusUpdateRepository;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are the Administrative / Coordination Agent within an Agentic AI Talent
            Sourcing platform.

            Given a candidate, a job requisition, an interview type, and a set of
            candidate-provided availability windows, your task is to:

            1. Select up to 3 concrete interview slot suggestions (ISO 8601 datetime
               strings, e.g. "2026-06-18T10:00:00") from within the provided
               availability windows, spaced sensibly (e.g. different days/times).
            2. Draft a short, professional candidate-facing message proposing these
               slots for the interview. This message is a DRAFT for recruiter review
               before sending - keep tone warm and clear.

            IMPORTANT: Respond with ONLY a single JSON object, no markdown fences, no
            preamble, no commentary. The JSON must exactly match this shape:

            {
              "proposedSlots": ["ISO datetime string", "..."],
              "candidateMessage": "string"
            }
            """;

    @Transactional
    public InterviewScheduleResponse proposeInterview(ProposeInterviewRequest request) {
        Candidate candidate = candidateRepository.findById(request.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + request.getCandidateId()));
        JobRequisition requisition = jobRequisitionRepository.findById(request.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + request.getRequisitionId()));

        String interviewType = (request.getInterviewType() == null || request.getInterviewType().isBlank())
                ? "SCREENING" : request.getInterviewType().toUpperCase();

        List<String> availability = (request.getAvailabilityWindow() != null && !request.getAvailabilityWindow().isEmpty())
                ? request.getAvailabilityWindow()
                : generateDefaultAvailability();

        ClaudeSchedulingResponse parsed = generateProposal(candidate, requisition, interviewType, availability);

        InterviewSchedule schedule = InterviewSchedule.builder()
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .interviewType(interviewType)
                .proposedSlots(toJsonArray(parsed.getProposedSlots()))
                .status("PROPOSED")
                .notes("Draft outreach message: " + parsed.getCandidateMessage())
                .build();
        schedule = interviewScheduleRepository.save(schedule);

        // Update pipeline stage
        PipelineStage stage = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId())
                .orElse(PipelineStage.builder()
                        .candidateId(candidate.getId())
                        .requisitionId(requisition.getId())
                        .build());
        stage.setStage("INTERVIEW_SCHEDULED");
        stage.setUpdatedByAgent(AGENT_NAME);
        stage.setNotes("Interview slots proposed (" + interviewType + ")");
        pipelineStageRepository.save(stage);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("INTERVIEW_PROPOSED")
                .details("Proposed " + parsed.getProposedSlots().size() + " slot(s) for " + interviewType + " interview")
                .build());

        return toResponse(schedule, candidate, requisition);
    }

    private ClaudeSchedulingResponse generateProposal(Candidate candidate, JobRequisition requisition, String interviewType, List<String> availability) {
        String userPrompt = """
                ## Candidate
                Name: %s

                ## Job Requisition
                Title: %s

                ## Interview Type
                %s

                ## Candidate Availability Windows
                %s
                """.formatted(
                candidate.getFullName(),
                requisition.getTitle(),
                interviewType,
                String.join("\n", availability)
        );

        String rawResponse = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
        String json = claudeApiClient.stripJsonFences(rawResponse);

        try {
            return objectMapper.readValue(json, ClaudeSchedulingResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse Admin Agent scheduling response: {}", json, e);
            // Fallback: propose the first 3 availability windows as-is
            List<String> fallbackSlots = availability.size() > 3 ? availability.subList(0, 3) : availability;
            return new ClaudeSchedulingResponse(fallbackSlots,
                    "We'd like to invite " + candidate.getFullName() + " to schedule a " + interviewType.toLowerCase()
                            + " interview for the " + requisition.getTitle() + " role. Please let us know which slot works best.");
        }
    }

    private List<String> generateDefaultAvailability() {
        // Generate 3 business-hour slots (10:00, 14:00) over the next 5 business days
        List<String> slots = new ArrayList<>();
        LocalDateTime cursor = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        int added = 0;
        while (added < 6) {
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                slots.add(cursor.format(ISO_FORMAT));
                slots.add(cursor.withHour(14).format(ISO_FORMAT));
                added += 2;
            }
            cursor = cursor.plusDays(1);
        }
        return slots;
    }

    @Transactional
    public InterviewScheduleResponse confirmSlot(Long scheduleId, ConfirmSlotRequest request) {
        InterviewSchedule schedule = interviewScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Interview schedule not found: " + scheduleId));

        schedule.setConfirmedSlot(request.getConfirmedSlot());
        schedule.setStatus("CONFIRMED");
        schedule = interviewScheduleRepository.save(schedule);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(schedule.getCandidateId())
                .requisitionId(schedule.getRequisitionId())
                .action("INTERVIEW_CONFIRMED")
                .details("Confirmed slot: " + request.getConfirmedSlot())
                .build());

        Candidate candidate = candidateRepository.findById(schedule.getCandidateId()).orElse(null);
        JobRequisition requisition = jobRequisitionRepository.findById(schedule.getRequisitionId()).orElse(null);
        return toResponse(schedule, candidate, requisition);
    }

    /**
     * Sends (logs) a status update notification to a candidate.
     * In production this would integrate with an email/SMS provider;
     * here it is logged for audit/demo purposes. Recruiter-approved.
     */
    @Transactional
    public StatusUpdateResponse sendStatusUpdate(Long candidateId, String message, String channel) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        CandidateStatusUpdate update = CandidateStatusUpdate.builder()
                .candidateId(candidateId)
                .message(message)
                .channel(channel != null ? channel.toUpperCase() : "EMAIL")
                .build();
        update = candidateStatusUpdateRepository.save(update);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidateId)
                .action("STATUS_UPDATE_SENT")
                .details("Channel: " + update.getChannel())
                .build());

        return StatusUpdateResponse.builder()
                .id(update.getId())
                .candidateId(candidateId)
                .candidateName(candidate.getFullName())
                .message(update.getMessage())
                .channel(update.getChannel())
                .sentAt(update.getSentAt())
                .build();
    }

    public List<InterviewScheduleResponse> getSchedulesForRequisition(Long requisitionId) {
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));

        return interviewScheduleRepository.findByRequisitionId(requisitionId).stream()
                .map(s -> {
                    Candidate candidate = candidateRepository.findById(s.getCandidateId()).orElse(null);
                    return toResponse(s, candidate, requisition);
                })
                .toList();
    }

    public List<StatusUpdateResponse> getStatusUpdatesForCandidate(Long candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        return candidateStatusUpdateRepository.findByCandidateIdOrderBySentAtDesc(candidateId).stream()
                .map(u -> StatusUpdateResponse.builder()
                        .id(u.getId())
                        .candidateId(candidateId)
                        .candidateName(candidate.getFullName())
                        .message(u.getMessage())
                        .channel(u.getChannel())
                        .sentAt(u.getSentAt())
                        .build())
                .toList();
    }

    private InterviewScheduleResponse toResponse(InterviewSchedule schedule, Candidate candidate, JobRequisition requisition) {
        List<String> slots;
        try {
            slots = objectMapper.readValue(schedule.getProposedSlots(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            slots = List.of();
        }

        return InterviewScheduleResponse.builder()
                .id(schedule.getId())
                .candidateId(schedule.getCandidateId())
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .requisitionId(schedule.getRequisitionId())
                .requisitionTitle(requisition != null ? requisition.getTitle() : null)
                .interviewType(schedule.getInterviewType())
                .proposedSlots(slots)
                .confirmedSlot(schedule.getConfirmedSlot())
                .status(schedule.getStatus())
                .notes(schedule.getNotes())
                .createdAt(schedule.getCreatedAt())
                .build();
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }
}
