# Agentic pipeline: summary

A one-page walk-through of how a candidate moves through the pipeline with the orchestrator agent. For the full detail (triggers, tools, the checks made in code, configuration, and where the code lives), see [agentic-pipeline-workflow.md](agentic-pipeline-workflow.md).

## Stage 1: application and screening

1. A candidate applies on the careers page or a job board.
2. One Claude call screens them and saves a score and a recommendation. Code, not the model, sets the stage:
   - A clear REJECT becomes `REJECTED`.
   - Anything else becomes `SCREENED`.
3. The orchestrator agent then starts a run. It reads the saved result and branches:

| Screening result | What the agent does |
|---|---|
| ADVANCE (75 or more) | Shortlists the candidate itself, then queues a phone-screen booking link for approval |
| REVIEW or HOLD | Leaves the candidate at SCREENED and says a recruiter should review |
| REJECT | Queues a rejection email for approval |

## Stage 2: a recruiter shortlists by hand (alternative entry)

1. A recruiter drags a card to SHORTLISTED. Only a human move triggers this.
2. That publishes `CandidateShortlistedEvent`, and the listener starts a run.
3. The agent skips screening and queues the phone-screen booking link for approval.

## Stage 3: approval

1. The run pauses as `PAUSED_FOR_APPROVAL` and shows the exact email in Agent Activity.
2. When the recruiter approves, the run resumes and sends the link. The candidate moves to `INTERVIEW_SCHEDULED`, shown as "Moved by AI agent".
3. Before queuing anything, the code checks:
   - the candidate has an email address;
   - there are free times on the calendar;
   - there is no open interview already;
   - the round is the right one.
4. If a check fails, nothing is queued. The run ends with a note saying what a recruiter needs to do.

## Stage 4: the candidate books

1. The candidate picks a slot on the booking page, and the interview becomes `CONFIRMED`.
2. The candidate and the interviewer get emails.
3. The agent does nothing here. This step doesn't trigger a run.

## Stage 5: the interviewer scores the round

1. The interviewer submits a scorecard with ratings, a verdict and notes. The round becomes `COMPLETED`.
2. That publishes `InterviewCompletedEvent`, and a new run starts.
3. The agent calls `getInterviewFeedback` and decides from the latest verdict:

| Verdict | What the agent does |
|---|---|
| `STRONG_YES`, or `YES` with average 3.5 or more | Queues the next round's link for approval (technical after phone screen, final after technical) |
| `YES` below 3.5 | Does nothing and leaves it to a recruiter |
| `NO` or `STRONG_NO` | Queues a rejection email for approval |
| Passed the final round | Sends nothing and notes that a recruiter should consider an offer |

4. After a recruiter approves a link, the flow goes back to stage 4 for the next round.

## What stays human

- Approving every email and rejection.
- Publishing calendar availability.
- Deciding borderline candidates.
- Making offers. The agent never creates or sends one.

## Safeguards on every run

- Only one open run per candidate and role.
- Each trigger has its own config flag, and runs stop while the AI budget is paused.
- A failure to start a run is logged and never blocks the recruiter's or interviewer's action.

## A note on the events

`CandidateShortlistedEvent` and `InterviewCompletedEvent` are published on Spring's in-process event bus. They are not stored and do not leave the running backend, so a restart between a commit and its listener loses the trigger, and with several backend instances only the one that handled the request would react. See the "Triggers" section of the [full workflow](agentic-pipeline-workflow.md#triggers).
