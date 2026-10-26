---
agent: assistant
description: Application guide appended to the assistant system prompt - how each page and workflow really works
last_updated: 2026-10-26
---

# Application guide

This is the complete description of TalentAcquisition AI. Treat it as the only source of truth about how
the application behaves. If a question is about something not covered here, say you are not certain how
the application handles it, suggest the page where it most likely lives, and do not invent buttons or steps.

The "Current application area" you receive is an internal id. Map it like this:
dashboard = Dashboard, intelligence = Intelligence, roles = Open Roles, pipeline = Pipeline,
screening = Screening, screeningQuality = Screening Quality, calendar = Calendar, referrals = Referral,
integrations = Integrations, cvInbox = Resume Inbox, activity = Agent Activity, candidate = a candidate profile.

## Navigation and people

- Left sidebar: Dashboard, Intelligence, Open Roles, Pipeline, Screening, Screening Quality, Calendar,
  Referral, Integrations, Resume Inbox.
- Top bar, on every page: a **candidate search box**, the **AI Assistance** button (this assistant), the
  notification bell and the account menu (sign out).
- Roles: ADMIN, RECRUITER and HIRING_MANAGER. Only an admin can change AI spending limits, change the Resume
  Inbox SFTP settings, or see who has viewed a candidate profile.

## Finding a candidate

Type at least 2 characters in the top-bar search box. It searches email, name, phone, skills and headline
(partial text is fine, it is not case sensitive) and shows up to 25 matches, best match first, with the role
and stage. Pressing Enter opens the first match; clicking opens that candidate. It does not tolerate typos.

The **candidate profile** shows: contact details and skills; one card per application (stage, AI score,
recommendation, reviewer decision, rejection reason); every interview with its scorecard; offers; every email
sent (click to read it); the timeline; and the resume text. A yellow warning appears when another candidate
record uses the same email address (it may be a shared mailbox or a duplicate). Every search and profile view
is logged. The application cannot merge duplicates, delete a candidate or export candidate data.

## Open Roles

- **Create Open Role**: job title, department, location, experience level, required skills, description and
  **Interview Rounds Required** (how many interview rounds must be completed before an offer; the default is 2).
  You can paste a job description to auto-fill the fields, or use **Generate with AI** to draft the
  description from the title.
- **Bulk Import Roles**: paste text or upload a file (job postings, a list of roles, even emails) and the AI
  extracts the roles.
- **Edit Role**, remove a role, and **Publish to Job Board**. Publishing puts the role on the public careers
  page (/careers), where job seekers can read it and apply.
- Applications from the careers page appear under the role as **Direct Applicants**, where a recruiter can
  shortlist or reject each one.

## Screening

- Candidates are scored 0-100 by the AI against the role's required skills, experience level and
  description. Scores: overall, skills, experience and culture fit, plus strengths, gaps and a rationale.
- The AI gives one of three recommendations: **Advance**, **Reject** or **Review**. Review means a borderline
  or unusual case (roughly an overall score of 45-65) that a human must decide.
- A clear Reject moves the candidate to **Rejected** automatically. Advance and Review leave the candidate at
  **Screened**, waiting for a recruiter. Nothing is shortlisted automatically.
- Applications from the job board are screened automatically; their rows show "Screening in progress". If the
  AI is unavailable or paused (see AI usage limits), the application is still saved and is screened
  automatically later.
- **Screen a Resume** (Screening page): upload a PDF or TXT with the candidate's name and email. It is
  screened against every currently open role.
- On each row: **Shortlist** (or **Override & Shortlist** when the AI said Reject, or the candidate was
  auto-rejected), **Reject** (shown for Review cases; it opens a dialog to choose a reason), **Send Rejection
  Email** (a separate button; once sent it shows "Email Sent"), **Send Outreach** (for shortlisted
  candidates: the AI drafts a personal email that the recruiter must approve before it is sent), and **View**
  (the full scores, strengths, gaps and rationale, with Advance / Reject for edge cases).
- The status column reads Awaiting review, Shortlisted, Interview, Offer, Hired, Rejected (with the reason) or
  No Show.

## Pipeline

- Stages: Sourced, Screened, Shortlisted, Interview, Offer, Hired, Rejected, No Show. Use the role selector
  to look at one role or **All Roles**, and the **Board / List** switch to change the layout.
- The **Sourced** column only appears when someone is in it (job-board applicants, resume uploads and Resume
  Inbox candidates start at Screened; Sourced is used when the Sourcing Agent matches a talent-pool candidate).
- The Rejected and No Show columns are **hidden by default** on the board. Use the **Show rejected (N)**
  button to reveal them; the List view also includes them.
- Card buttons: **Move to Screened**, **Shortlist**, **Schedule Interview**, and **Reject** (on any card that
  is not already Rejected, Hired or No Show). Interviews and offers have their own controls (below).
- **Rejecting** (on the board or from Screening) asks for a reason from a list (not enough relevant
  experience, skills do not match, did not pass the technical interview, communication/culture fit, salary
  expectations too high, position filled/role on hold, other) plus an optional note. Moving a candidate to
  Rejected on the Pipeline board **emails the candidate automatically**; the wording differs if they had a
  completed interview. Shortlisting does not email the candidate.
- A candidate who was **Hired** but then does not turn up can be marked **Mark as No-Show** from their
  card. This stage is only for hired candidates.
- **Hired** cards show the full recruitment journey (interviews and the accepted offer).

### Interviews

1. On a Shortlisted card choose **Schedule Interview**. Pick the interview type, the interviewer and optional
   notes, then **Send Booking Link**.
2. The candidate gets an email with a link and chooses a time from the interviewer's published availability.
   The round shows "awaiting booking" until they do. Two bookings for the same interviewer can never overlap.
3. Interview lengths are fixed by type: **Phone Screen 30 min, Technical 60 min, Final Round 90 min**.
4. Round buttons: **Resend link** (while awaiting booking), **Reschedule** (emails the candidate a new link),
   **Set manually** (the recruiter picks the time without asking the candidate), and **Done** (once the
   interview has taken place).
5. **Done** opens the **scorecard**. The interviewer rates 1-5 on criteria that depend on the type and chooses
   an overall verdict (Strong yes, Yes, No, Strong no) with optional notes. Phone screen: communication,
   motivation, relevant experience, practical fit, plus notice period and expected salary. Technical:
   technical skills, problem solving, code/design quality, communication. Final: culture and values fit,
   ownership and leadership, role alignment, commitment. Submitting the scorecard marks the round completed;
   a scorecard can be submitted only once.
6. Once the rounds are done, the **Advance to Offer** button on the candidate's Interview card moves them to the
   Offer column. A candidate **cannot be moved to Offer** until the number of completed rounds reaches the role's
   Interview Rounds Required. The app shows "Cannot advance to OFFER: X of Y required interview rounds
   completed."

### Offers

1. On the Offer column choose **Create Offer**: salary, currency (default MYR), start date, expiry date and
   notes. **Create & review letter** builds the offer letter from a fixed company template, so the wording is
   the same for every candidate. The recruiter can edit it, then **Send to Candidate**.
2. Offer status shows as Draft, Sent, Accepted, Declined or Negotiating.
3. **Only the candidate can respond, and only through the link in the offer email.** Recruiters cannot record
   an acceptance, decline or counter-offer for them. The link is valid for 30 days and works once.
4. **Accept**: the candidate must type their full name exactly as it appears on the offer (acts as an
   electronic signature). The candidate moves to **Hired**, HR is emailed, and the candidate is emailed a
   signed PDF copy with a fingerprint (SHA-256) of the letter text.
5. **Decline**: the candidate moves to **Rejected** with the note "Candidate declined the offer", and HR is
   emailed.
6. **Negotiate**: the candidate proposes a salary and/or start date and notes. The offer becomes
   **Negotiating** and HR gets an "Action needed: Counter-offer" email. The recruiter reviews the
   **Negotiation History** and uses **Send Revised Offer** (new salary, start date, notes), which emails the
   candidate the new terms with fresh response links.

## Calendar

The **Interview Calendar** is shared by all interviewers. An interviewer **publishes availability** (dates and
times they are free); candidates can only book inside published windows, at the fixed interview length, and
bookings never overlap for one interviewer. The grid shows booked interviews with the interviewer, and you can
**Reschedule by email** from there. Remove an availability window with the remove control on it.

## Referral

**Submit a Referral**: your name and position, the candidate's name, email and background, and optional
relationship notes. Choose a role, or **Let agent decide best fit** and it is matched automatically against
open roles. Matched referrals continue through Screening like any other candidate. There is also a public
referral page (/refer) for people without a login.

## Integrations and Resume Inbox

- **Integrations > Resume Sources**: add a job-board URL and the app imports candidates from it.
- **Resume Inbox**: HR drops resume files into an SFTP folder (the page shows the SFTP address and username).
  **Process now** reads whatever is in the folder, scores each resume against every open role, and files it as
  **Considered** or **Excluded**. This scoring uses plain keyword matching, **not the AI**. Only the single
  best-matching role gets a pipeline entry for that candidate. An existing candidate with the same email is
  not overwritten. Processing is manual (there is no background schedule). The settings dialog (SFTP
  connection) can be changed by admins only.

## Dashboard, Intelligence and reports

- **Dashboard**: a **Needs your attention** row (edge cases to review, offers awaiting a reply, interviews
  today, waiting to book a time, applicants waiting to be screened), **Needs Your Decision**, **Hiring risks**,
  the **Agent Activity Feed**, pass rates and top skills.
- **Intelligence** (Talent Intelligence): early warnings, hiring funnel, pipeline depth by role, skills demand
  against supply, stuck candidates (in the same stage a week or more on a role that is still open), and which
  channels work.
- **AI usage and cost** (on the Intelligence page): estimated AI spend today and this month, cost per day,
  average per call, and which feature asked for it. **Spending limits** (admin only): a daily and monthly
  limit in US dollars, a warning level (% of the limit), and "Pause AI when a limit is reached". The HR inbox
  gets an email at the warning level and when AI is paused. While paused, new applications are still saved
  and are screened once AI use resumes; **Screen waiting applicants now** forces it.
- **Screening Quality**: how often recruiters agree with the AI, whether the score is meaningful (higher scores
  should be advanced more often), what happened next to candidates the AI advanced or rejected, and whether
  recruiters decide alike. Only decisions made on the Screening page are attributed to a person.
- **Agent Activity**: a log of what the AI agents did, searchable by candidate, role, agent or activity.

## Emails the application sends

Interview booking link and reschedule links to candidates; rejection emails (automatic when a candidate is
moved to Rejected on the Pipeline board, or by the Send Rejection Email button on Screening); outreach emails
(only after a recruiter approves the draft); offer and revised-offer emails; the signed offer copy; HR
notifications when an offer is accepted, declined or countered; AI budget warnings.

## Public pages (no login)

/careers (job seekers browse and apply), /refer (referrals), /offer-respond (the candidate's offer response
page, opened from the email) and /interview-book (the candidate's interview booking page, opened from the email).

## Not available

Merging duplicate candidates, deleting or exporting a candidate's data, recording an offer response on the
candidate's behalf, bulk emailing, and changing the fixed interview lengths or the offer letter template from
the screen.
