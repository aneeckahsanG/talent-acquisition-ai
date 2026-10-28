package com.talentai.interviewer;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.interviewer.InterviewerDtos.Criterion;
import com.talentai.interviewer.InterviewerDtos.InterviewDetail;
import com.talentai.interviewer.InterviewerDtos.InterviewSummary;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardRequest;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardSummary;
import com.talentai.orchestrator.service.OrchestratorService;
import com.talentai.orchestrator.service.ScorecardCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The interviewer's own view: the interviews assigned to them, and submitting their scorecard. */
@Service
@RequiredArgsConstructor
public class InterviewerPortalService {

    private final InterviewScheduleRepository interviews;
    private final InterviewScorecardRepository scorecards;
    private final CandidateRepository candidates;
    private final JobRequisitionRepository requisitions;
    private final AppUserRepository users;
    private final OrchestratorService orchestratorService;

    public List<InterviewSummary> myInterviews(String username) {
        AppUser me = currentUser(username);
        return interviews.findByInterviewerId(me.getId()).stream()
                .filter(iv -> !"CANCELLED".equals(iv.getStatus()))
                .sorted(Comparator
                        .comparing(InterviewSchedule::getConfirmedSlot, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(InterviewSchedule::getId))
                .map(this::toSummary)
                .toList();
    }

    public InterviewDetail myInterview(Long interviewId, String username) {
        return toDetail(ownedInterview(interviewId, username));
    }

    /** Records the scorecard (which also completes the round) after checking the interview is the caller's. */
    public InterviewDetail submitScorecard(Long interviewId, ScorecardRequest request, String username) {
        InterviewSchedule iv = ownedInterview(interviewId, username);
        orchestratorService.submitScorecard(iv.getId(), request, username);
        return toDetail(interviews.findById(iv.getId()).orElse(iv));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AppUser currentUser(String username) {
        return users.findByUsername(username).orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }

    /** Missing and not-yours look the same to the caller, so ids cannot be probed. */
    private InterviewSchedule ownedInterview(Long interviewId, String username) {
        AppUser me = currentUser(username);
        return interviews.findById(interviewId)
                .filter(iv -> me.getId().equals(iv.getInterviewerId()))
                .orElseThrow(() -> new AccessDeniedException("That interview is not assigned to you."));
    }

    private int roundNumber(InterviewSchedule iv) {
        List<InterviewSchedule> rounds = interviews
                .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(iv.getCandidateId(), iv.getRequisitionId());
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).getId().equals(iv.getId())) return i + 1;
        }
        return 1;
    }

    private InterviewSummary toSummary(InterviewSchedule iv) {
        Candidate c = candidates.findById(iv.getCandidateId()).orElse(null);
        JobRequisition r = requisitions.findById(iv.getRequisitionId()).orElse(null);
        return InterviewSummary.builder()
                .id(iv.getId())
                .interviewType(iv.getInterviewType())
                .status(iv.getStatus())
                .confirmedSlot(iv.getConfirmedSlot())
                .roundNumber(roundNumber(iv))
                .candidateName(c != null ? c.getFullName() : "Candidate")
                .roleTitle(r != null ? r.getTitle() : "Role")
                .scorecardSubmitted(scorecards.findByInterviewId(iv.getId()).isPresent())
                .build();
    }

    private InterviewDetail toDetail(InterviewSchedule iv) {
        Candidate c = candidates.findById(iv.getCandidateId()).orElse(null);
        JobRequisition r = requisitions.findById(iv.getRequisitionId()).orElse(null);
        List<Criterion> criteria = ScorecardCriteria.forType(iv.getInterviewType()).stream()
                .map(k -> Criterion.builder().key(k).label(ScorecardCriteria.LABELS.getOrDefault(k, k)).build())
                .toList();
        return InterviewDetail.builder()
                .id(iv.getId())
                .interviewType(iv.getInterviewType())
                .status(iv.getStatus())
                .confirmedSlot(iv.getConfirmedSlot())
                .roundNumber(roundNumber(iv))
                .candidateName(c != null ? c.getFullName() : "Candidate")
                .candidateHeadline(c != null ? c.getHeadline() : null)
                .candidateSkills(c != null ? c.getSkills() : null)
                .candidateYearsExperience(c != null ? c.getYearsExperience() : null)
                .resumeText(c != null ? c.getResumeText() : null)
                .roleTitle(r != null ? r.getTitle() : "Role")
                .department(r != null ? r.getDepartment() : null)
                .location(r != null ? r.getLocation() : null)
                .experienceLevel(r != null ? r.getExperienceLevel() : null)
                .requiredSkills(r != null ? r.getRequiredSkills() : null)
                .roleDescription(r != null ? r.getDescription() : null)
                .criteria(criteria)
                .submittedScorecard(scorecards.findByInterviewId(iv.getId()).map(this::toScorecardSummary).orElse(null))
                .build();
    }

    private ScorecardSummary toScorecardSummary(InterviewScorecard sc) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        ScorecardCriteria.LABELS.keySet().forEach(k -> {
            if (sc.getRatings().containsKey(k)) ordered.put(k, sc.getRatings().get(k));
        });
        return ScorecardSummary.builder()
                .ratings(ordered)
                .averageRating(sc.averageRating())
                .verdict(sc.getVerdict())
                .notes(sc.getNotes())
                .noticePeriod(sc.getNoticePeriod())
                .expectedSalary(sc.getExpectedSalary())
                .submittedAt(sc.getSubmittedAt())
                .build();
    }
}
