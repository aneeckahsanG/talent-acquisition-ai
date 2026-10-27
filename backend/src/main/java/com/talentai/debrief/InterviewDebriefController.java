package com.talentai.debrief;

import com.talentai.debrief.InterviewDebriefService.DebriefView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/** The AI debrief of a candidate's interview scorecards for one role. */
@RestController
@RequestMapping("/api/orchestrator/pipeline/{requisitionId}/candidates/{candidateId}/debrief")
@RequiredArgsConstructor
public class InterviewDebriefController {

    private final InterviewDebriefService debriefService;

    /** The saved debrief, or just the scorecard counts when none has been generated yet. */
    @GetMapping
    public ResponseEntity<DebriefView> get(@PathVariable Long requisitionId, @PathVariable Long candidateId) {
        return ResponseEntity.ok(debriefService.get(candidateId, requisitionId));
    }

    /** Asks the AI for a new debrief, replacing the saved one. */
    @PostMapping
    public ResponseEntity<DebriefView> generate(@PathVariable Long requisitionId, @PathVariable Long candidateId,
                                                Principal principal) {
        return ResponseEntity.ok(debriefService.generate(candidateId, requisitionId, principal != null ? principal.getName() : null));
    }
}
