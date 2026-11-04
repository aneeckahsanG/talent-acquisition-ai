# Agentic pipeline workflow

How a candidate moves through the pipeline now that the orchestrator agent takes part, from application to the last interview round. For why the agent loop runs asynchronously, see [agentic-orchestrator-async-loop.md](agentic-orchestrator-async-loop.md). For the tables involved, see [database-schema.md](database-schema.md).

## Contents

- [Principles](#principles)
- [Overview](#overview)
- [Triggers](#triggers)
- [Stage 1: application and screening](#stage-1-application-and-screening)
- [Stage 2: a recruiter shortlists by hand](#stage-2-a-recruiter-shortlists-by-hand)
- [Stage 3: approval](#stage-3-approval)
- [Stage 4: the candidate books](#stage-4-the-candidate-books)
- [Stage 5: the interviewer scores the round](#stage-5-the-interviewer-scores-the-round)
- [Tools the agent has](#tools-the-agent-has)
- [Checks made in code](#checks-made-in-code)
- [Safeguards](#safeguards)
- [Configuration](#configuration)
- [What stays human](#what-stays-human)
- [Not covered yet](#not-covered-yet)
- [Where the code is](#where-the-code-is)

## Principles

- **The agent proposes, a recruiter approves.** Every outward action (an email, a rejection) is queued behind the human approval gate. Nothing reaches a candidate until a recruiter approves it in Agent Activity.
- **Code decides what must always be true; the model decides what is a judgement.** Screening stages, round order, who may be invited and when are enforced in Java. The model chooses between the options the code allows.
- **Triggers are events, not polling.** A recruiter's shortlist and an interviewer's scorecard each publish a Spring event. A listener starts the run after the change has committed.
- **The agent never blocks a person.** If a run cannot start, the problem is logged and the recruiter's or interviewer's action still succeeds.

## Overview

```
 Application ──► Screening ──► Agent run ──► shortlist ──► booking link ──► approval ──► sent
 (careers page,   (one Claude    (reads the    (if ADVANCE)    (phone screen)    (recruiter)   │
  job board)       call, saved    saved result)                                                 ▼
                   score)                                                           Candidate books a slot
                                                                                                │
 Recruiter shortlists by hand ──► Agent run ──► booking link ──► approval            (no agent run here)
                                                                                                │
                                                                                                ▼
 Interviewer submits scorecard ──► Agent run ──► next-round link / rejection ──► approval ──► sent
                                       ▲                                                        │
                                       └──────────────── repeats for each round ◄───────────────┘
```

Rounds run in this order: `PHONE_SCREEN` → `TECHNICAL` → `FINAL`.

## Triggers

An agent run (`agent_run` row, statuses `RUNNING`, `PAUSED_FOR_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`, at most 20 steps) starts from exactly these places:

| Trigger | Started by | Run goal |
|---|---|---|
| A candidate applies (careers page or job board) | `ApplicationFollowUpService` → `startRunAsync` | "A new application from … arrived … Handle it." |
| A recruiter moves a candidate to SHORTLISTED | `CandidateShortlistedEvent` → `ShortlistFollowUpListener` → `startShortlistRun` | "A recruiter shortlisted … Decide the next step." |
| An interviewer submits a scorecard | `InterviewCompletedEvent` → `InterviewFollowUpListener` → `startInterviewFollowUpRun` | "The <type> interview for … has just been completed … Decide the next step." |

A recruiter approving a paused run resumes that run; it does not start a new one. A candidate booking a slot starts nothing.

**The events are in-process.** Both events go through Spring's `ApplicationEventPublisher`, a publish/subscribe bus inside the running backend, not a message broker. Dispatch is a plain method call on the same thread, and the run itself then starts on another thread (`CompletableFuture.runAsync`), so the person's request returns without waiting for Claude. The events are not stored: if the app stops between the commit and the listener running, the trigger is lost and nothing retries it, and with several backend instances only the one that handled the request reacts. That is fine for a single instance; surviving restarts or scaling out would need an outbox table or a broker.

Only a **human** move into SHORTLISTED publishes the shortlist event. The agent's own shortlist does not, so the agent never triggers itself.

## Stage 1: application and screening

1. A candidate applies on the careers page or a job board, and `ApplicationFollowUpService` saves the candidate and the sourcing match.
2. `ScreeningAgentService.runScreening` makes one Claude call and saves a `screening_result` (score and recommendation). This is not an agent loop. Code, not the model, sets the stage:
   - a clear REJECT outside the edge band → `REJECTED`
   - anything else → `SCREENED` (actor `SCREENING`)

   `UnscreenedApplicantRetrier` retries unscreened applicants every 10 minutes.
3. `startRunAsync` creates the run. The agent calls `screenCandidate`, which returns the saved result at no cost, then branches:

| Screening result | What the agent does |
|---|---|
| ADVANCE (score 75 or more) | Calls `moveCandidateStage(SHORTLISTED)` (actor `ORCHESTRATOR`), then `sendBookingLink(PHONE_SCREEN)`. The link is the invitation, so no separate outreach email is drafted |
| REVIEW or HOLD | Confirms `SCREENED` (a no-op) and finishes, saying a recruiter should review |
| REJECT | Calls `moveCandidateStage(REJECTED)`, which is approval-gated and emails the candidate once approved |

`moveCandidateStage` is a no-op when the candidate is already in the requested stage: it does not change the actor or time, and sends no emails. This is why a candidate screened into SCREENED is not relabelled "Moved by AI agent" when the agent confirms the same stage.

## Stage 2: a recruiter shortlists by hand

1. A recruiter drags the card to SHORTLISTED. `OrchestratorService.moveStage(..., "HUMAN")` runs.
2. If the stage really changed, it publishes `CandidateShortlistedEvent(requisitionId, candidateId)`. A move to the stage the candidate is already in publishes nothing.
3. `ShortlistFollowUpListener` receives it **after the transaction commits** (`AFTER_COMMIT`), so a rolled-back move never starts a run, and the agent sees the new stage.
4. The agent skips screening and moves nothing. It calls `sendBookingLink(PHONE_SCREEN)` and pauses for approval.

Using events here also avoids a circular dependency between `OrchestratorService`, `RecruitingOrchestratorService` and `RecruitingTools`.

## Stage 3: approval

1. `sendBookingLink` first runs `checkBookingLink` (see [Checks made in code](#checks-made-in-code)). If a check fails, the tool returns an `ERROR`, nothing is queued, and the run finishes with a note saying what a recruiter needs to do (for example publish availability on the Calendar).
2. If the checks pass, `HumanApprovalGate.requestApproval` queues the action under the key `bookingLink:<requisitionId>:<candidateId>:<type>` and the tool returns `PENDING_APPROVAL`. The run is saved as `PAUSED_FOR_APPROVAL`, with the pending approval stored as JSON on the run, including the **exact email text**.
3. A recruiter reviews it in Agent Activity and approves. `approve(runId)` grants the key (re-granting from the saved run if the app restarted, because the gate is in memory), and the loop resumes.
4. The agent calls `sendBookingLink` again. This time the grant is present, so it calls `scheduleInterview(..., "ORCHESTRATOR")` and consumes the grant. The grant is per interview type: approving a phone screen does not allow a technical round.
5. `scheduleInterview` creates the `interview_schedule` row (`PROPOSED`), moves the candidate to `INTERVIEW_SCHEDULED` (shown as "Moved by AI agent"), and emails the candidate a button to `/interview-book?interviewId=…`.
6. The run becomes `COMPLETED`.

Declining an approval cancels the pending action with a recorded reason.

## Stage 4: the candidate books

1. The candidate opens the link. Free slots are computed live from the published availability windows (per interviewer; a window with no interviewer is open to everyone).
2. They pick a slot. `PublicInterviewController.book` books it, sets the interview to `CONFIRMED`, and stamps the pipeline row `INTERVIEW_SCHEDULED` with actor `CANDIDATE`.
3. The candidate and the interviewer both get a confirmation email.

No event is published and no agent run starts here.

## Stage 5: the interviewer scores the round

1. The interviewer submits a scorecard (a 1 to 5 rating per criterion, an overall verdict of `STRONG_YES`, `YES`, `NO` or `STRONG_NO`, and notes). `OrchestratorService.submitScorecard` saves it and marks the round `COMPLETED`.
2. It then publishes `InterviewCompletedEvent(requisitionId, candidateId, interviewType)`. A scorecard that is refused (for example the round is not booked, or already scored) publishes nothing.
3. `InterviewFollowUpListener` starts a run after commit.
4. The agent calls `getInterviewFeedback` and acts on the verdict of the latest completed round:

| Verdict | What the agent does |
|---|---|
| `STRONG_YES`, or `YES` with an average rating of 3.5 or more | If `nextRound` is set, calls `sendBookingLink` for that round and pauses for approval. If it is null (the final round was passed), sends nothing and says the candidate has passed every round and a recruiter should consider an offer |
| `YES` with an average rating below 3.5 | Does nothing and says a recruiter should review the scorecard |
| `NO` or `STRONG_NO` | Calls `moveCandidateStage(REJECTED)`, approval-gated, which emails the candidate once approved |
| No verdict yet, or an `ERROR` (a round still open, no times published, already invited) | Does not retry. Finishes and says what a recruiter needs to do |

5. After a recruiter approves the next-round link, the flow continues at [Stage 3](#stage-3-approval) step 4 and then [Stage 4](#stage-4-the-candidate-books) for that round.

## Tools the agent has

| Tool | Kind | Notes |
|---|---|---|
| `screenCandidate` | read | Returns the saved screening result; screens only if none exists |
| `getPipelineBoard` | read | The board, including score and rounds completed |
| `getInterviewFeedback` | read | Each round's type, status, verdict, average rating, ratings, notes; plus `nextRound` (the round after the latest completed one, or null) |
| `moveCandidateStage` | write | `REJECTED` (and `OFFER`, `HIRED`) are approval-gated; a same-stage request is a no-op |
| `sendBookingLink` | write, approval-gated | Queues the exact email, then schedules the interview as `ORCHESTRATOR` once approved |
| `sendOutreach`, `sendCandidateStatusUpdate` | write, approval-gated | Not used by the current playbook for these flows |

The playbook the model follows is `backend/src/main/resources/prompts/orchestrator-agent.md`.

## Checks made in code

`OrchestratorService.checkBookingLink` runs before any approval is requested, so a recruiter is never asked to approve something that cannot work. It refuses when:

- the interview type is not `PHONE_SCREEN`, `TECHNICAL` or `FINAL`;
- the candidate or requisition is missing, or the candidate has no email address;
- the candidate is not in `SHORTLISTED` or `INTERVIEW_SCHEDULED` (for example `OFFER`, `REJECTED`, `SCREENED`);
- the candidate already has an open (`PROPOSED` or `CONFIRMED`) interview, of the same type or any other;
- the candidate is between rounds and any of these is true:
  - no round has been completed yet;
  - the latest completed round is the last one (`FINAL`);
  - the requested type is not the round after the latest completed one;
  - the latest completed round has no scorecard, or its verdict is `NO` or `STRONG_NO`;
- no free interview times are published. Only availability open to everyone counts, because the agent does not choose an interviewer.

A candidate who is `SHORTLISTED` keeps the original rule: any valid type is allowed as long as the checks above pass, and a completed round of the same type does not block a new invitation.

## Safeguards

- **One open run per candidate and role.** The listeners skip the trigger when a run is `RUNNING` or `PAUSED_FOR_APPROVAL` for the same candidate and requisition (`AgentRunRepository.existsByCandidateIdAndRequisitionIdAndStatusIn`). Shortlisting twice, or two scorecards in quick succession, cannot queue two invitations.
- **AI budget.** No run starts while `AiBudgetGuard` reports AI use as paused.
- **Failures are contained.** Listeners catch and log every exception.
- **No self-triggering.** The agent's own moves publish no events.
- **Audit trail.** Every run and step is saved (`agent_run`, `agent_step`), and each stage change is recorded in the candidate's history with who made it (`SCREENING`, `ORCHESTRATOR`, `CANDIDATE`, or a person).

## Configuration

| Property | Default | Effect |
|---|---|---|
| `app.agent.shortlist-follow-up` | `true` | Whether a recruiter's shortlist starts a run |
| `app.agent.interview-follow-up` | `true` | Whether a submitted scorecard starts a run |

## What stays human

- Approving every email and every rejection.
- Publishing calendar availability.
- Deciding borderline candidates (`REVIEW`/`HOLD` screening results, and a `YES` below 3.5).
- Making offers. The agent never creates or sends one, and says only that a recruiter should consider it.
- Moving a candidate by hand, which always works and is unchanged.

## Not covered yet

- The agent reacting to a booking, for example to prepare a briefing for the interviewer.
- Offers and what follows them.
- Choosing an interviewer. The agent only offers times open to everyone.
- The Applicants panel's Shortlist button does not move the pipeline stage, so it does not start a run.

## Where the code is

| Piece | File |
|---|---|
| Agent loop, run starters, approval and resume | `backend/src/main/java/com/talentai/orchestrator/agent/RecruitingOrchestratorService.java` |
| Shortlist trigger | `.../orchestrator/agent/ShortlistFollowUpListener.java`, `.../orchestrator/event/CandidateShortlistedEvent.java` |
| Interview trigger | `.../orchestrator/agent/InterviewFollowUpListener.java`, `.../orchestrator/event/InterviewCompletedEvent.java` |
| Tools | `backend/src/main/java/com/talentai/agent/tool/RecruitingTools.java` |
| Approval gate | `backend/src/main/java/com/talentai/agent/service/HumanApprovalGate.java` |
| Stage moves, scheduling, booking-link checks, scorecards, feedback | `.../orchestrator/service/OrchestratorService.java` |
| Round order | `.../orchestrator/service/InterviewRounds.java` |
| Candidate booking | `backend/src/main/java/com/talentai/admin/controller/PublicInterviewController.java` |
| Agent playbook | `backend/src/main/resources/prompts/orchestrator-agent.md` |
