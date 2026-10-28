import React, { useState, useEffect, useCallback, useRef } from "react";
import OfferRespondPage from "./OfferRespondPage.jsx";
import InterviewBookPage from "./InterviewBookPage.jsx";
import {
  LayoutGrid, Search, FileCheck, UserPlus, Calendar, Activity,
  ChevronRight, ChevronUp, ChevronDown, Sparkles, CheckCircle2, XCircle, AlertCircle, Clock,
  Briefcase, Mail, X, ArrowRight, Bell, LogOut, Loader2, UploadCloud,
  RefreshCw, Plus, Globe, List, ArrowUp, ArrowDown, ArrowUpDown, TrendingUp, Pencil, Inbox, Settings,History, FileText, Trash2, Maximize2,
} from "lucide-react";

// ============================================================
// CONFIG — change this to point at your running Spring Boot instance
// ============================================================
const API_BASE = import.meta.env.VITE_API_BASE ?? "/api";

// ============================================================
// API LAYER
// ============================================================
function getToken() { return sessionStorage.getItem("jwt"); }
function setToken(t) { sessionStorage.setItem("jwt", t); }
function clearToken() { sessionStorage.removeItem("jwt"); }

async function apiFetch(path, options = {}) {
  const token = getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.body && !(options.body instanceof FormData)
        ? { "Content-Type": "application/json" }
        : {}),
      ...options.headers,
    },
  });
  if (res.status === 401) {
    // Only treat this as "session expired" if we actually had a session.
    // Reloading when there was never a token (e.g. a public page hitting a
    // protected endpoint by mistake) just loops the page forever.
    if (token) {
      clearToken();
      window.location.reload();
    }
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(err.message || res.statusText);
  }
  if (res.status === 204) return null;
  return res.json();
}

// ============================================================
// HOOK: generic data fetcher
// ============================================================
function useApi(path, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    if (!path) return;
    // Don't clear data during reload — keeps previous board visible while fetching
    setLoading(true);
    setError(null);
    try {
      const d = await apiFetch(path);
      setData(d);
    } catch (e) {
      setError(e.message);
      setData(null);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, ...deps]);

  useEffect(() => { load(); }, [load]);
  return { data, loading, error, reload: load };
}

// Fetches multiple paths in parallel — used to power "All Roles" views that
// merge per-requisition endpoints client-side. `paths` is an array of URL
// strings, or null/empty to skip fetching.
function useApiMulti(paths, deps = []) {
  const [data, setData] = useState(null); // array of results aligned with paths, or null
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const pathsKey = paths ? paths.join("|") : "";

  const load = useCallback(async () => {
    if (!paths || paths.length === 0) { setData(null); setLoading(false); return; }
    setLoading(true);
    setError(null);
    try {
      const results = await Promise.all(paths.map(p => apiFetch(p)));
      setData(results);
    } catch (e) {
      setError(e.message);
      setData(null);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathsKey, ...deps]);

  useEffect(() => { load(); }, [load]);
  return { data, loading, error, reload: load };
}

// ============================================================
// DESIGN SYSTEM
// ============================================================
const C = {
  sidebar: "#248EC2",
  accent: "#248EC2",
  success: "#10B981",
  warning: "#F59E0B",
  danger: "#EF4444",
  bg: "#F1F5F9",
  surface: "#FFFFFF",
  border: "#E2E8F0",
  text: "#0F172A",
  muted: "#64748B",
};

const AGENT_META = {
  SOURCING:     { label: "Sourcing",     color: "#F59E0B", bg: "#FEF3C7", icon: Search },
  SCREENING:    { label: "Screening",    color: "#10B981", bg: "#D1FAE5", icon: FileCheck },
  REFERRAL:     { label: "Referral",     color: "#8B5CF6", bg: "#EDE9FE", icon: UserPlus },
  ADMIN:        { label: "Coordination", color: "#3B82F6", bg: "#DBEAFE", icon: Calendar },
  ORCHESTRATOR: { label: "Orchestrator", color: "#6366F1", bg: "#E0E7FF", icon: LayoutGrid },
  APPLICATIONS: { label: "Applications", color: "#0EA5E9", bg: "#E0F2FE", icon: Inbox },
  OUTREACH:     { label: "Outreach",     color: "#0D9488", bg: "#CCFBF1", icon: Mail },
};

// Who made a stage move. HUMAN, CANDIDATE and ORCHESTRATOR are stored on the pipeline stage; agents fall back to their label.
const ACTOR_LABELS = { HUMAN: "Recruiter", CANDIDATE: "Candidate", ORCHESTRATOR: "AI agent" };
const actorLabel = agent => ACTOR_LABELS[agent] || AGENT_META[agent]?.label || agent;

// The Sourcing service also handles careers-page applications and outreach emails, so it logs them as SOURCING.
// Label each entry by what happened instead, so the Sourcing filter only shows real sourcing.
const APPLICATION_ACTIONS = ["DIRECT_APPLICATION_RECEIVED", "APPLICANT_SHORTLISTED", "APPLICANT_REJECTED"];
const OUTREACH_ACTIONS = ["OUTREACH_DRAFTED", "OUTREACH_SENT"];
function activityAgent(entry) {
  if (entry.agentName === "SOURCING") {
    if (APPLICATION_ACTIONS.includes(entry.action)) return "APPLICATIONS";
    if (OUTREACH_ACTIONS.includes(entry.action)) return "OUTREACH";
  }
  return entry.agentName;
}

const STAGE_LABELS = {
  SOURCED: "Sourced", SCREENED: "Screened", SHORTLISTED: "Shortlisted",
  INTERVIEW_SCHEDULED: "Interview", OFFER: "Offer", HIRED: "Hired", REJECTED: "Rejected",
  NO_SHOW: "No Show",
};

// Linear progression — REFERRAL_MATCHED is a source tag, not a stage
const PIPELINE_STAGES = [
  "SOURCED","SCREENED","SHORTLISTED","INTERVIEW_SCHEDULED","OFFER","HIRED","REJECTED","NO_SHOW",
];

// Merges per-requisition pipeline board responses into one "All Roles" board.
// Each PipelineCandidateResponse already carries requisitionId/requisitionTitle,
// so cards remain traceable to their role without extra tagging.
function mergePipelineBoards(boards) {
  const stages = {};
  for (const s of PIPELINE_STAGES) stages[s] = [];
  for (const b of boards || []) {
    for (const s of PIPELINE_STAGES) {
      const list = b?.stages?.[s] || [];
      stages[s].push(...list);
    }
  }
  // Each role's list already comes back newest-first; merging roles in role order would
  // interleave them out of date order, so re-sort by enteredAt once combined.
  for (const s of PIPELINE_STAGES) {
    stages[s].sort((a, b) => new Date(b.enteredAt || 0) - new Date(a.enteredAt || 0));
  }
  return { requisitionId: null, requisitionTitle: "All Roles", stages };
}

// What action buttons appear on a card given its current stage
// INTERVIEW_SCHEDULED and OFFER are handled separately
const STAGE_ACTIONS = {
  SOURCED:    [{ label: "Move to Screened",   next: "SCREENED" }],
  SCREENED:   [{ label: "Shortlist",          next: "SHORTLISTED" }],
  SHORTLISTED:[{ label: "Schedule Interview", next: "INTERVIEW_SCHEDULED" }],
  HIRED:      [],
  REJECTED:   [],
};

const OFFER_STATUS_META = {
  PENDING:     { color: "#F59E0B", label: "Draft" },
  SENT:        { color: "#6366F1", label: "Sent" },
  ACCEPTED:    { color: "#10B981", label: "Accepted" },
  DECLINED:    { color: "#EF4444", label: "Declined" },
  NEGOTIATING: { color: "#F97316", label: "Negotiating" },
};

const INTERVIEW_ROUND_TYPES = ["PHONE_SCREEN", "TECHNICAL", "FINAL"];
const ROUND_LABELS = { PHONE_SCREEN: "Phone Screen", TECHNICAL: "Technical", FINAL: "Final Round" };
const ROUND_COLORS = { COMPLETED: "#10B981", CONFIRMED: "#6366F1", PROPOSED: "#F59E0B", CANCELLED: "#94A3B8" };

const REJECT_REASONS = ["Not enough relevant experience", "Skills do not match the role", "Did not pass the technical interview",
  "Communication / culture fit", "Salary expectations too high", "Position filled / role on hold", "Other"];
const joinRejectReason = form => form.preset + (form.note.trim() ? ` — ${form.note.trim()}` : "");

const VERDICT_LABELS = { STRONG_YES: "Strong yes", YES: "Yes", NO: "No", STRONG_NO: "Strong no" };
const verdictColor = v => (v === "STRONG_YES" || v === "YES" ? C.success : C.danger);

const DEBRIEF_REC = {
  ADVANCE_TO_OFFER:    { label: "Ready for offer",   color: C.success },
  ADD_ROUND:           { label: "Add another round", color: C.accent },
  HOLD_FOR_DISCUSSION: { label: "Discuss as a team", color: C.warning },
  DO_NOT_PROCEED:      { label: "Do not proceed",    color: C.danger },
};
const DEBRIEF_CONFIDENCE = { HIGH: "High confidence", MEDIUM: "Medium confidence", LOW: "Low confidence" };

function DebriefButton({ onClick }) {
  return (
    <button onClick={onClick}
      className="w-full text-[10px] font-semibold py-1 rounded-lg flex items-center justify-center gap-1"
      style={{ color: C.accent, border: `1px solid ${C.accent}55`, background: `${C.accent}0D` }}>
      <Sparkles size={10} /> AI Debrief
    </button>
  );
}

function DebriefList({ title, items, color }) {
  if (!items?.length) return null;
  return (
    <div>
      <p className="text-[11px] font-bold uppercase tracking-wide mb-1.5" style={{ color }}>{title}</p>
      <ul className="space-y-1">
        {items.map((t, i) => (
          <li key={i} className="text-xs leading-relaxed flex gap-2" style={{ color: C.text }}>
            <span style={{ color }}>•</span><span>{t}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// Loads the stored debrief and lets the caller (re)generate it. Reading never calls the AI; only generate() does.
function useDebrief(requisitionId, candidateId) {
  const path = `/orchestrator/pipeline/${requisitionId}/candidates/${candidateId}/debrief`;
  const { data, loading, error, reload } = useApi(path, [requisitionId, candidateId]);
  const [fresh, setFresh] = useState(null);
  const [generating, setGenerating] = useState(false);
  const [genError, setGenError] = useState(null);

  async function generate() {
    setGenerating(true); setGenError(null);
    try {
      setFresh(await apiFetch(path, { method: "POST" }));
    } catch (e) {
      setGenError(e.message);
    } finally {
      setGenerating(false);
    }
  }

  return { d: fresh || data, loading, error, reload, generate, generating, genError };
}

// Compact debrief shown inside the Create Offer dialog, so the scorecards are weighed before the offer goes out.
// Advisory only: it never blocks the offer.
function OfferDebriefPanel({ requisitionId, candidateId }) {
  const { d, loading, error, reload, generate, generating, genError } = useDebrief(requisitionId, candidateId);
  const rec = d?.generated ? DEBRIEF_REC[d.recommendation] : null;
  const concerning = rec && d.recommendation !== "ADVANCE_TO_OFFER";

  return (
    <div className="rounded-xl border p-3 mb-4"
      style={{ borderColor: concerning ? `${rec.color}66` : C.border, backgroundColor: concerning ? `${rec.color}0D` : "#F8FAFC" }}>
      <p className="text-[11px] font-bold uppercase tracking-wide flex items-center gap-1 mb-1.5" style={{ color: C.accent }}>
        <Sparkles size={11} /> AI Debrief before you offer
      </p>

      {loading && !d && <p className="text-xs" style={{ color: C.muted }}>Loading the scorecard review…</p>}
      {error && !d && <ErrorBanner message={error} onRetry={reload} />}

      {d && !d.available && (
        <p className="text-xs leading-relaxed" style={{ color: C.muted }}>
          No interview scorecards on file, so there is nothing for the AI to review. You can still create the offer.
        </p>
      )}

      {d && d.available && !d.generated && (
        <div className="flex items-center justify-between gap-3">
          <p className="text-xs" style={{ color: C.muted }}>
            {d.scorecardCount} scorecard{d.scorecardCount === 1 ? "" : "s"} not yet reviewed by the AI.
          </p>
          <Btn onClick={generate} disabled={generating}>{generating ? "Generating…" : "Generate"}</Btn>
        </div>
      )}

      {rec && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-xs font-bold px-2.5 py-0.5 rounded-full"
              style={{ color: rec.color, backgroundColor: `${rec.color}1A`, border: `1px solid ${rec.color}55` }}>
              {rec.label}
            </span>
            <span className="text-[11px]" style={{ color: C.muted }}>{DEBRIEF_CONFIDENCE[d.confidence] || d.confidence}</span>
          </div>
          {d.stale && (
            <p className="text-[11px]" style={{ color: "#92400E" }}>Scorecards changed since this was generated. Regenerate before relying on it.</p>
          )}
          <p className="text-xs leading-relaxed" style={{ color: C.text }}>{d.summary}</p>
          {concerning && d.suggestedNextStep && (
            <p className="text-xs leading-relaxed" style={{ color: C.text }}>
              <span className="font-semibold" style={{ color: rec.color }}>Suggested: </span>{d.suggestedNextStep}
            </p>
          )}
          <button onClick={generate} disabled={generating} className="text-[11px] underline" style={{ color: C.muted }}>
            {generating ? "Regenerating…" : "Regenerate"}
          </button>
        </div>
      )}

      {genError && <p className="text-xs mt-1" style={{ color: C.danger }}>{genError}</p>}
    </div>
  );
}

// AI debrief of a candidate's interview scorecards for one role. Advisory only: it never moves the candidate.
function DebriefModal({ requisitionId, candidateId, candidateName, onClose }) {
  const { d, loading, error, reload, generate, generating, genError } = useDebrief(requisitionId, candidateId);
  const rec = d?.generated ? DEBRIEF_REC[d.recommendation] : null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-lg p-6 max-h-[90vh] overflow-y-auto">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="font-bold text-base flex items-center gap-1.5" style={{ color: C.text }}>
              <Sparkles size={15} style={{ color: C.accent }} /> AI Debrief · {candidateName}
            </h2>
            <p className="text-xs mt-1" style={{ color: C.muted }}>
              Combines the interview scorecards into one view. The AI advises; the decision is yours.
            </p>
          </div>
          <button onClick={onClose} className="shrink-0 p-1 rounded-lg hover:bg-slate-100" aria-label="Close">
            <XCircle size={18} style={{ color: C.muted }} />
          </button>
        </div>

        <div className="mt-4 space-y-4">
          {loading && !d && <LoadingCard rows={3} />}
          {error && !d && <ErrorBanner message={error} onRetry={reload} />}

          {d && !d.available && (
            <p className="text-sm leading-relaxed" style={{ color: C.muted }}>
              {d.completedRounds > 0
                ? `${d.completedRounds} interview round${d.completedRounds === 1 ? " was" : "s were"} completed without a scorecard, so there is nothing for the AI to read. A debrief is built from scorecards, which are submitted when an upcoming round is marked Done.`
                : "There is nothing to debrief yet. Complete an interview round and submit its scorecard first."}
            </p>
          )}

          {d && d.available && !d.generated && (
            <div className="rounded-xl border p-4" style={{ borderColor: C.border, backgroundColor: "#F8FAFC" }}>
              <p className="text-sm" style={{ color: C.text }}>
                {d.scorecardCount} scorecard{d.scorecardCount === 1 ? "" : "s"} ready to review.
              </p>
              <p className="text-xs mt-1" style={{ color: C.muted }}>
                Interview rounds completed: {d.completedRounds} of {d.requiredRounds} required for an offer.
              </p>
            </div>
          )}

          {rec && (
            <>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-sm font-bold px-3 py-1 rounded-full"
                  style={{ color: rec.color, backgroundColor: `${rec.color}1A`, border: `1px solid ${rec.color}55` }}>
                  {rec.label}
                </span>
                <span className="text-xs" style={{ color: C.muted }}>{DEBRIEF_CONFIDENCE[d.confidence] || d.confidence}</span>
                <span className="text-xs" style={{ color: C.muted }}>
                  · {d.scorecardCount} scorecard{d.scorecardCount === 1 ? "" : "s"} · {d.completedRounds} of {d.requiredRounds} rounds
                </span>
              </div>

              {d.stale && (
                <div className="p-3 rounded-xl border text-xs flex items-start gap-2"
                  style={{ backgroundColor: "#FFFBEB", borderColor: "#FDE68A", color: "#92400E" }}>
                  <AlertCircle size={14} className="mt-0.5 shrink-0" />
                  <span>The scorecards have changed since this debrief was generated. Regenerate it before relying on it.</span>
                </div>
              )}

              <p className="text-sm leading-relaxed" style={{ color: C.text }}>{d.summary}</p>

              <DebriefList title="Where interviewers agree" items={d.agreements} color={C.success} />
              <DebriefList title="Where they differ" items={d.disagreements} color={C.warning} />
              <DebriefList title="Concerns to weigh" items={d.concerns} color={C.danger} />

              {d.suggestedNextStep && (
                <div className="rounded-xl border p-3" style={{ borderColor: `${rec.color}55`, backgroundColor: `${rec.color}0D` }}>
                  <p className="text-[11px] font-bold uppercase tracking-wide mb-1" style={{ color: rec.color }}>Suggested next step</p>
                  <p className="text-xs leading-relaxed" style={{ color: C.text }}>{d.suggestedNextStep}</p>
                </div>
              )}

              {d.guardrailNote && (
                <p className="text-xs leading-relaxed p-3 rounded-xl" style={{ backgroundColor: "#F1F5F9", color: C.muted }}>
                  <span className="font-semibold" style={{ color: C.text }}>Adjusted by a rule: </span>{d.guardrailNote}
                </p>
              )}

              <p className="text-[11px]" style={{ color: C.muted }}>
                AI-generated from the scorecards only, {formatFullDateTime(d.generatedAt)}{d.generatedBy ? ` by ${d.generatedBy}` : ""}.
                It does not change the candidate's stage.
              </p>
            </>
          )}

          {genError && <p className="text-xs" style={{ color: C.danger }}>{genError}</p>}
        </div>

        <div className="flex justify-end gap-2 mt-5">
          <Btn variant="secondary" onClick={onClose}>Close</Btn>
          {d?.available && (
            <Btn onClick={generate} disabled={generating}>
              {generating ? "Generating…" : d.generated ? "Regenerate" : "Generate AI debrief"}
            </Btn>
          )}
        </div>
      </Card>
    </div>
  );
}

function ScorecardModal({ title, criteria, isPhone, form, setForm, onSubmit, onCancel, submitting, error }) {
  const complete = criteria.length > 0 && criteria.every(([k]) => form.ratings[k] > 0) && form.verdict;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-md p-6 max-h-[90vh] overflow-y-auto">
        <h2 className="font-bold text-base" style={{ color: C.text }}>{title}</h2>
        <p className="text-xs mt-1 mb-4" style={{ color: C.muted }}>
          Record how the interview went. Submitting this marks the round as completed.
        </p>
        <div className="space-y-3">
          {criteria.map(([key, label]) => (
            <div key={key} className="flex items-center justify-between gap-3">
              <span className="text-xs font-semibold" style={{ color: C.text }}>{label}</span>
              <div className="flex gap-1 shrink-0">
                {[1, 2, 3, 4, 5].map(n => (
                  <button key={n} type="button" onClick={() => setForm(f => ({ ...f, ratings: { ...f.ratings, [key]: n } }))}
                    className="w-7 h-7 rounded-lg text-xs font-bold border shrink-0"
                    style={{
                      borderColor: form.ratings[key] === n ? C.accent : C.border,
                      backgroundColor: form.ratings[key] === n ? C.accent : "transparent",
                      color: form.ratings[key] === n ? "#fff" : C.muted,
                    }}>{n}</button>
                ))}
              </div>
            </div>
          ))}
          <p className="text-[10px]" style={{ color: C.muted }}>1 = poor, 5 = excellent</p>
          {isPhone && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Notice period</label>
                <input value={form.noticePeriod} onChange={e => setForm(f => ({ ...f, noticePeriod: e.target.value }))}
                  placeholder="e.g. 1 month"
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Expected salary</label>
                <input value={form.expectedSalary} onChange={e => setForm(f => ({ ...f, expectedSalary: e.target.value }))}
                  placeholder="e.g. MYR 9,000"
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
            </div>
          )}
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Overall verdict</label>
            <div className="grid grid-cols-4 gap-1.5">
              {Object.entries(VERDICT_LABELS).map(([v, label]) => (
                <button key={v} type="button" onClick={() => setForm(f => ({ ...f, verdict: v }))}
                  className="py-1.5 rounded-lg text-[11px] font-semibold border"
                  style={{
                    borderColor: form.verdict === v ? verdictColor(v) : C.border,
                    backgroundColor: form.verdict === v ? verdictColor(v) : "transparent",
                    color: form.verdict === v ? "#fff" : C.muted,
                  }}>{label}</button>
              ))}
            </div>
          </div>
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Notes <span style={{ color: C.border }}>(optional)</span></label>
            <textarea rows={3} value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))}
              placeholder="Strengths, concerns, what you asked, anything the next interviewer should know"
              className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
              style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
          </div>
        </div>
        {error && <p className="text-xs mt-3" style={{ color: C.danger }}>{error}</p>}
        <div className="flex gap-2 mt-5">
          <Btn variant="primary" onClick={onSubmit} disabled={!complete || submitting}>{submitting ? "Saving…" : "Submit & complete round"}</Btn>
          <Btn variant="secondary" onClick={onCancel}>Cancel</Btn>
        </div>
      </Card>
    </div>
  );
}

async function openSignedCopy(offerId) {
  const res = await fetch(`${API_BASE}/orchestrator/offers/${offerId}/signed-copy`, { headers: { Authorization: `Bearer ${getToken()}` } });
  if (!res.ok) { alert("Could not open the signed copy."); return; }
  window.open(URL.createObjectURL(await res.blob()), "_blank");
}

function SignedCopyLink({ offer }) {
  if (offer?.status !== "ACCEPTED") return null;
  return (
    <div className="space-y-0.5">
      {offer.acceptedName && (
        <p className="text-[9px]" style={{ color: C.muted }}>
          Accepted by {offer.acceptedName}
          {offer.acceptedAt ? ` · ${new Date(offer.acceptedAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}` : ""}
        </p>
      )}
      <button onClick={() => openSignedCopy(offer.id)} className="text-[9px] font-semibold underline" style={{ color: C.accent }}>
        Download signed copy (PDF)
      </button>
    </div>
  );
}

function RejectReasonModal({ candidateName, form, setForm, onConfirm, onCancel }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-sm p-6">
        <h2 className="font-bold text-base" style={{ color: C.text }}>Reject {candidateName}?</h2>
        <p className="text-xs mt-1 mb-4" style={{ color: C.muted }}>
          Record why, for your team's reference. The candidate does not see this.
        </p>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Reason</label>
            <Select value={form.preset} onChange={e => setForm(f => ({ ...f, preset: e.target.value }))}>
              <option value="">Select a reason…</option>
              {REJECT_REASONS.map(r => <option key={r} value={r}>{r}</option>)}
            </Select>
          </div>
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Note <span style={{ color: C.border }}>(optional)</span></label>
            <textarea rows={2} value={form.note}
              onChange={e => setForm(f => ({ ...f, note: e.target.value }))}
              placeholder="e.g. Strong coding, but no production ML experience"
              className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
              style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
          </div>
        </div>
        <div className="flex gap-2 mt-5">
          <Btn variant="primary" onClick={onConfirm} disabled={!form.preset}>Reject candidate</Btn>
          <Btn variant="secondary" onClick={onCancel}>Cancel</Btn>
        </div>
      </Card>
    </div>
  );
}

function timeAgo(iso) {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function formatDateTime(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-MY", {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
  });
}

// ============================================================
// ATOMS
// ============================================================
function ScoreRing({ score, size = 48 }) {
  const r = (size - 6) / 2;
  const circ = 2 * Math.PI * r;
  const fill = (score / 100) * circ;
  const color = score >= 75 ? C.success : score >= 50 ? C.warning : C.danger;
  return (
    <div className="relative shrink-0 flex items-center justify-center"
      style={{ width: size, height: size }}>
      <svg width={size} height={size} style={{ transform: "rotate(-90deg)" }}>
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke="#E2E8F0" strokeWidth={3} />
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={color} strokeWidth={3}
          strokeDasharray={`${fill} ${circ}`} strokeLinecap="round" />
      </svg>
      <span className="absolute text-xs font-bold font-mono"
        style={{ color, fontSize: size < 44 ? 10 : 12 }}>
        {Math.round(score)}
      </span>
    </div>
  );
}

function Badge({ children, color = C.accent, bg }) {
  return (
    <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold"
      style={{ color, backgroundColor: bg || `${color}18` }}>
      {children}
    </span>
  );
}

function RecBadge({ rec }) {
  const map = {
    ADVANCE: { color: C.success, icon: CheckCircle2, label: "Advance" },
    REJECT:  { color: C.danger,  icon: XCircle,      label: "Reject"  },
    REVIEW:  { color: C.warning, icon: AlertCircle,  label: "Review"  },
  };
  const m = map[rec] || map.REVIEW;
  const Icon = m.icon;
  return <Badge color={m.color}><Icon size={11} />{m.label}</Badge>;
}

function AgentPill({ agent }) {
  const meta = AGENT_META[agent] || AGENT_META.ORCHESTRATOR;
  const Icon = meta.icon;
  return (
    <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold"
      style={{ color: meta.color, backgroundColor: meta.bg }}>
      <Icon size={11} />{meta.label}
    </span>
  );
}

function Avatar({ name = "?", size = 36 }) {
  const colors = ["#6366F1","#8B5CF6","#0EA5E9","#F59E0B","#10B981","#3B82F6","#64748B"];
  const idx = (name || "?").charCodeAt(0) % colors.length;
  const initials = (name || "?").split(" ").map(w => w[0]).slice(0, 2).join("").toUpperCase();
  return (
    <div className="rounded-full flex items-center justify-center font-bold text-white shrink-0"
      style={{ width: size, height: size, fontSize: size * 0.35, backgroundColor: colors[idx] }}>
      {initials}
    </div>
  );
}

function Card({ children, className = "", style = {}, onClick }) {
  return (
    <div className={`bg-white rounded-2xl border ${className}`}
      style={{ borderColor: C.border, boxShadow: "0 1px 3px 0 rgb(0 0 0/0.04),0 1px 2px -1px rgb(0 0 0/0.04)", ...style }}
      onClick={onClick}>
      {children}
    </div>
  );
}

function Btn({ children, variant = "primary", onClick, className = "", disabled = false, type = "button" }) {
  const styles = {
    primary:   { background: "#248EC2", color: "#fff", border: "none" },
    secondary: { background: "#fff", color: C.text, border: `1px solid ${C.border}` },
    danger:    { background: "#FEF2F2", color: C.danger,   border: "1px solid #FECACA" },
    success:   { background: "#ECFDF5", color: C.success,  border: "1px solid #A7F3D0" },
  };
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className={`inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-sm font-semibold transition-opacity
        hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed ${className}`}
      style={styles[variant]}>
      {children}
    </button>
  );
}

function Input({ label, ...props }) {
  return (
    <div>
      {label && <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>{label}</label>}
      <input
        className="w-full text-sm px-4 py-2.5 rounded-xl border outline-none focus:ring-2 focus:ring-blue-200 transition-shadow"
        style={{ borderColor: C.border }}
        {...props}
      />
    </div>
  );
}

function Spinner({ size = 16 }) {
  return <Loader2 size={size} className="animate-spin" style={{ color: C.accent }} />;
}

function LoadingCard({ rows = 3 }) {
  return (
    <Card className="p-5">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className={`h-4 rounded-full bg-slate-100 animate-pulse ${i < rows - 1 ? "mb-3" : ""}`}
          style={{ width: `${60 + (i * 13) % 35}%` }} />
      ))}
    </Card>
  );
}

function ErrorBanner({ message, onRetry }) {
  return (
    <div className="flex items-center justify-between p-4 rounded-2xl border"
      style={{ backgroundColor: "#FEF2F2", borderColor: "#FECACA" }}>
      <div className="flex items-center gap-2">
        <AlertCircle size={16} style={{ color: C.danger }} />
        <p className="text-sm" style={{ color: C.danger }}>{message}</p>
      </div>
      {onRetry && (
        <button onClick={onRetry} className="flex items-center gap-1 text-xs font-semibold" style={{ color: C.danger }}>
          <RefreshCw size={12} /> Retry
        </button>
      )}
    </div>
  );
}

function Select({ value, onChange, children }) {
  return (
    <select value={value} onChange={onChange}
      className="text-sm font-medium px-3 py-2 rounded-xl border outline-none bg-white cursor-pointer"
      style={{ borderColor: C.border, color: C.text }}>
      {children}
    </select>
  );
}

function TopBar({ title, subtitle, action }) {
  return (
    <div className="flex items-start justify-between mb-7">
      <div>
        <h1 className="text-2xl font-bold tracking-tight" style={{ color: C.text }}>{title}</h1>
        {subtitle && <p className="text-sm mt-1" style={{ color: C.muted }}>{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

function AssistantMessage({ content }) {
  const parts = content.split(/(\*\*[^*]+\*\*)/g);
  return parts.map((part, index) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      return <strong key={index}>{part.slice(2, -2)}</strong>;
    }
    return <React.Fragment key={index}>{part}</React.Fragment>;
  });
}

const ASSISTANT_PAGE_NAMES = {
  dashboard: "the Dashboard", intelligence: "Intelligence", activity: "Agent Activity", roles: "Open Roles",
  pipeline: "the Pipeline", screening: "Screening", screeningQuality: "Screening Quality", calendar: "the Calendar",
  referrals: "Referrals", integrations: "Integrations", cvInbox: "the Resume Drop Zone", candidate: "a candidate profile",
};

function AssistantPanel({ active, open, onClose }) {
  const [messages, setMessages] = useState([
    {
      role: "assistant",
      content: "Hi! I can guide you through TalentAcquisition AI or help design a recruitment role.",
    },
  ]);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);

  async function ask(e) {
    e.preventDefault();
    const message = question.trim();
    if (!message || loading) return;

    setQuestion("");
    setMessages(current => [...current, { role: "user", content: message }]);
    setLoading(true);
    try {
      const result = await apiFetch("/assistant/chat", {
        method: "POST",
        body: JSON.stringify({ message, currentPage: active }),
      });
      setMessages(current => [...current, { role: "assistant", content: result.answer }]);
    } catch (error) {
      setMessages(current => [...current, {
        role: "assistant",
        content: error.message || "The assistant is temporarily unavailable.",
      }]);
    } finally {
      setLoading(false);
    }
  }

  return (
    <aside
      className="h-full shrink-0 overflow-hidden rounded-l-2xl border-l shadow-xl"
      style={{
        width: open ? 380 : 0,
        opacity: open ? 1 : 0,
        pointerEvents: open ? "auto" : "none",
        background: "linear-gradient(180deg, #102A43 0%, #0B1F33 100%)",
        borderColor: "#28506B",
        transition: "width 280ms ease, opacity 180ms ease",
      }}
    >
      <div className="w-[380px] h-full flex flex-col">
        <div className="flex items-center justify-between p-4 border-b" style={{ borderColor: "#28506B" }}>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl flex items-center justify-center" style={{ backgroundColor: "#183B56" }}>
              <Sparkles size={16} style={{ color: "#67D5FF" }} />
            </div>
            <div>
              <h2 className="text-sm font-bold" style={{ color: "#F4FAFF" }}>AI Assistance</h2>
              <p className="text-[11px]" style={{ color: "#A8C4D8" }}>Guidance for {ASSISTANT_PAGE_NAMES[active] || active}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-slate-800" aria-label="Close AI assistance">
            <X size={16} style={{ color: "#A8C4D8" }} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto space-y-3 p-4">
          {messages.map((message, index) => (
            <div key={`${message.role}-${index}`} className={`flex ${message.role === "user" ? "justify-end" : "justify-start"}`}>
              <div
                className="max-w-[88%] rounded-2xl px-3 py-2.5 text-sm whitespace-pre-wrap"
                style={{
                  backgroundColor: message.role === "user" ? "#248EC2" : "#183B56",
                  color: "#F4FAFF",
                }}
              >
                <AssistantMessage content={message.content} />
              </div>
            </div>
          ))}
          {loading && (
            <div className="flex items-center gap-2 text-xs" style={{ color: "#A8C4D8" }}>
              <Spinner size={14} /> Thinking…
            </div>
          )}
        </div>

        <form onSubmit={ask} className="p-3 border-t" style={{ borderColor: "#28506B" }}>
          <div className="flex items-end gap-2">
            <textarea
              value={question}
              onChange={e => setQuestion(e.target.value)}
              onKeyDown={e => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  e.currentTarget.form.requestSubmit();
                }
              }}
              rows={2}
              maxLength={4000}
              placeholder="Ask how to use the system or design a role…"
              className="flex-1 resize-none rounded-xl border px-3 py-2 text-sm outline-none focus:ring-2"
              style={{ borderColor: "#28506B", backgroundColor: "#0B1F33", color: "#F4FAFF" }}
            />
            <button
              type="submit"
              disabled={loading || !question.trim()}
              className="h-10 w-10 rounded-xl flex items-center justify-center text-white disabled:opacity-40"
              style={{ backgroundColor: C.accent }}
              aria-label="Ask AI assistant"
            >
              {loading ? <Spinner size={15} /> : <ArrowRight size={16} />}
            </button>
          </div>
        </form>
      </div>
    </aside>
  );
}

// ============================================================
// LOGIN SCREEN
// ============================================================
function LoginScreen({ onLogin }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError("");
    try {
      const data = await apiFetch("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      setToken(data.token);
      onLogin({ username: data.username, fullName: data.fullName, role: data.role });
    } catch (e) {
      setError(e.message || "Invalid credentials");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-4"
      style={{ backgroundColor: C.bg }}>
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-3 justify-center mb-8">
          <div className="w-10 h-10 rounded-2xl flex items-center justify-center"
            style={{ background: "#248EC2" }}>
            <Sparkles size={20} color="#fff" />
          </div>
          <div>
            <p className="font-bold text-xl tracking-tight" style={{ color: C.text }}>TalentAcquisition AI</p>
            <p className="text-xs" style={{ color: C.muted }}>Agentic Talent Sourcing Platform</p>
          </div>
        </div>

        <Card className="p-8">
          <h2 className="text-lg font-bold mb-6" style={{ color: C.text }}>Sign in</h2>
          {error && (
            <div className="mb-4 p-3 rounded-xl text-sm" style={{ backgroundColor: "#FEF2F2", color: C.danger }}>
              {error}
            </div>
          )}
          <form onSubmit={handleSubmit} className="space-y-4">
            <Input label="Username" value={username} onChange={e => setUsername(e.target.value)}
              placeholder="recruiter1" autoFocus />
            <Input label="Password" type="password" value={password}
              onChange={e => setPassword(e.target.value)} placeholder="password123" />
            <Btn type="submit" variant="primary" className="w-full justify-center py-3" disabled={loading}>
              {loading ? <Spinner size={15} /> : null} Sign in
            </Btn>
          </form>
          <p className="text-xs mt-4 text-center" style={{ color: C.muted }}>
            Demo: recruiter1 / password123
          </p>
        </Card>
      </div>
    </div>
  );
}

// ============================================================
// CALENDAR — shared interview calendar (availability + bookings)
// ============================================================
const ymdLocal = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const addDaysStr = (s, n) => { const d = new Date(s + "T00:00"); d.setDate(d.getDate() + n); return ymdLocal(d); };
const mondayOf = d => { const x = new Date(d); x.setHours(0, 0, 0, 0); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return ymdLocal(x); };
const clockTime = d => new Date(d).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

function CalendarWeekGrid({ data, weekStart, onSelect, selectedId }) {
  const PX = 76;
  const days = Array.from({ length: 7 }, (_, i) => { const d = new Date(weekStart + "T00:00"); d.setDate(d.getDate() + i); return d; });
  const windows = data?.windows || [];
  const bookings = data?.bookings || [];
  const mins = d => { const t = new Date(d); return t.getHours() * 60 + t.getMinutes(); };
  let startH = 8, endH = 18;
  [...windows, ...bookings].forEach(i => { startH = Math.min(startH, Math.floor(mins(i.start) / 60)); endH = Math.max(endH, Math.ceil(mins(i.end) / 60)); });
  const hours = Array.from({ length: endH - startH }, (_, i) => startH + i);
  const top = d => (mins(d) - startH * 60) / 60 * PX;
  const todayKey = ymdLocal(new Date());
  const height = hours.length * PX;

  return (
    <Card className="p-3 overflow-x-auto">
      <div style={{ minWidth: 760 }}>
        <div className="flex" style={{ paddingLeft: 44 }}>
          {days.map(d => (
            <div key={ymdLocal(d)} className="flex-1 text-center text-[11px] font-bold py-1.5"
              style={{ color: ymdLocal(d) === todayKey ? C.accent : C.text }}>
              {d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" })}
            </div>
          ))}
        </div>
        <div className="flex">
          <div className="relative shrink-0" style={{ width: 44, height }}>
            {hours.map((h, i) => (
              <div key={h} className="absolute text-[10px]" style={{ top: i * PX - 6, right: 6, color: C.muted }}>{String(h).padStart(2, "0")}:00</div>
            ))}
          </div>
          {days.map(d => {
            const key = ymdLocal(d);
            return (
              <div key={key} className="flex-1 relative"
                style={{
                  height, borderLeft: `1px solid ${C.border}`,
                  backgroundImage: `repeating-linear-gradient(to bottom, transparent 0, transparent ${PX - 1}px, ${C.border} ${PX - 1}px, ${C.border} ${PX}px)`,
                  backgroundColor: key === todayKey ? `${C.accent}08` : "transparent",
                }}>
                {windows.filter(w => w.start.slice(0, 10) === key).map(w => (
                  <div key={`w${w.id}`} className="absolute left-0 right-0" title={`Open ${clockTime(w.start)}–${clockTime(w.end)}`}
                    style={{ top: top(w.start), height: top(w.end) - top(w.start), backgroundColor: `${C.success}1f` }} />
                ))}
                {bookings.filter(b => b.start.slice(0, 10) === key).map(b => (
                  <button key={`b${b.id}`} onClick={() => onSelect(b)}
                    title={`${b.candidateName} · ${clockTime(b.start)}–${clockTime(b.end)}`}
                    className="absolute left-1 right-1 rounded-lg text-left px-1.5 py-1 overflow-hidden text-white"
                    style={{
                      top: top(b.start) + 1, height: Math.max(top(b.end) - top(b.start) - 2, 20),
                      backgroundColor: b.status === "COMPLETED" ? C.success : C.accent, outline: selectedId === b.id ? `2px solid ${C.text}` : "none",
                    }}>
                    <div className="text-[10px] font-bold leading-tight truncate">{b.status === "COMPLETED" ? "✓ " : ""}{clockTime(b.start)} {b.candidateName}</div>
                    <div className="text-[9px] leading-tight opacity-90 truncate">{ROUND_LABELS[b.interviewType] || b.interviewType} · {b.roleTitle}</div>
                  </button>
                ))}
              </div>
            );
          })}
        </div>
        <div className="flex items-center gap-4 mt-3 text-[10px]" style={{ color: C.muted, paddingLeft: 44 }}>
          <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded" style={{ backgroundColor: `${C.success}40` }} /> Open for booking</span>
          <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded" style={{ backgroundColor: C.accent }} /> Booked interview</span>
          <span className="flex items-center gap-1.5"><span className="w-3 h-3 rounded" style={{ backgroundColor: C.success }} /> Completed</span>
        </div>
      </div>
    </Card>
  );
}

function CalendarView() {
  const today = ymdLocal(new Date());
  const [view, setView] = useState("week");           // week | list
  const [weekStart, setWeekStart] = useState(mondayOf(new Date()));
  const [range, setRange] = useState({ from: today, to: addDaysStr(today, 13) });
  const [data, setData] = useState(null);
  const [durations, setDurations] = useState({});
  const [selected, setSelected] = useState(null);
  const [form, setForm] = useState({ fromDate: addDaysStr(today, 1), toDate: addDaysStr(today, 5), weekdaysOnly: true, startTime: "10:00", endTime: "17:00" });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);

  useEffect(() => { if (view === "week") setRange({ from: weekStart, to: addDaysStr(weekStart, 6) }); }, [view, weekStart]);

  const load = useCallback(async () => {
    try { setData(await apiFetch(`/calendar/slots?from=${range.from}&to=${range.to}`)); }
    catch (e) { setMsg({ err: true, text: e.message }); }
  }, [range]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { apiFetch("/calendar/durations").then(setDurations).catch(() => {}); }, []);

  async function generate() {
    setBusy(true); setMsg(null);
    try {
      const r = await apiFetch("/calendar/windows/generate", { method: "POST", body: JSON.stringify(form) });
      setMsg({ text: `${r.created} day${r.created === 1 ? "" : "s"} published.` });
      if (view === "week") setWeekStart(mondayOf(new Date(form.fromDate + "T00:00")));
      else if (form.toDate > range.to) setRange(rg => ({ ...rg, to: form.toDate }));
      else load();
    } catch (e) { setMsg({ err: true, text: e.message }); }
    finally { setBusy(false); }
  }
  async function act(path, method) { try { await apiFetch(path, { method }); setSelected(null); load(); } catch (e) { setMsg({ err: true, text: e.message }); } }
  function rescheduleBooking(id) {
    if (window.confirm("Free this time and email the candidate to choose a new one?")) act(`/calendar/bookings/${id}/release`, "POST");
  }

  const days = {};
  (data?.windows || []).forEach(w => { (days[w.start.slice(0, 10)] ??= { windows: [], bookings: [] }).windows.push(w); });
  (data?.bookings || []).forEach(b => { (days[b.start.slice(0, 10)] ??= { windows: [], bookings: [] }).bookings.push(b); });
  const inputCls = "w-full px-3 py-2 rounded-xl border text-sm";
  const inputSty = { borderColor: C.border, color: C.text, backgroundColor: C.bg };
  const lbl = t => <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>{t}</label>;
  const dayKeys = Object.keys(days).sort();
  const tabBtn = (id, label) => (
    <button key={id} onClick={() => { setView(id); setSelected(null); }}
      className="px-3 py-1.5 rounded-lg text-xs font-semibold border"
      style={{ borderColor: view === id ? C.accent : C.border, backgroundColor: view === id ? `${C.accent}12` : "transparent", color: view === id ? C.accent : C.muted }}>
      {label}
    </button>
  );
  const weekLabel = `${new Date(weekStart + "T00:00").toLocaleDateString("en-GB", { day: "numeric", month: "short" })} – ${new Date(addDaysStr(weekStart, 6) + "T00:00").toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}`;

  return (
    <div>
      <TopBar title="Interview Calendar" subtitle="Publish when you are free — candidates book a time that fits their interview length, and bookings never overlap" />
      <div className="grid gap-6" style={{ gridTemplateColumns: "280px 1fr" }}>
        <div className="space-y-4 self-start">
          <Card className="p-5 space-y-3">
            <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Publish availability</p>
            <div className="grid grid-cols-2 gap-2">
              <div>{lbl("From")}<input type="date" className={inputCls} style={inputSty} value={form.fromDate} onChange={e => setForm(f => ({ ...f, fromDate: e.target.value }))} /></div>
              <div>{lbl("To")}<input type="date" className={inputCls} style={inputSty} value={form.toDate} onChange={e => setForm(f => ({ ...f, toDate: e.target.value }))} /></div>
              <div>{lbl("Day starts")}<input type="time" className={inputCls} style={inputSty} value={form.startTime} onChange={e => setForm(f => ({ ...f, startTime: e.target.value }))} /></div>
              <div>{lbl("Day ends")}<input type="time" className={inputCls} style={inputSty} value={form.endTime} onChange={e => setForm(f => ({ ...f, endTime: e.target.value }))} /></div>
            </div>
            <label className="flex items-center gap-2 text-xs" style={{ color: C.text }}>
              <input type="checkbox" checked={form.weekdaysOnly} onChange={e => setForm(f => ({ ...f, weekdaysOnly: e.target.checked }))} /> Weekdays only
            </label>
            <Btn variant="primary" onClick={generate} disabled={busy}>{busy ? <><Spinner size={13} /> Publishing…</> : <><Plus size={13} /> Publish availability</>}</Btn>
            {msg && <p className="text-xs" style={{ color: msg.err ? C.danger : C.success }}>{msg.text}</p>}
          </Card>
          <Card className="p-5 space-y-2">
            <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Interview lengths</p>
            {Object.entries(durations).map(([t, m]) => (
              <div key={t} className="flex items-center justify-between text-xs">
                <span style={{ color: C.text }}>{ROUND_LABELS[t] || t}</span>
                <span className="font-bold" style={{ color: C.accent }}>{m} min</span>
              </div>
            ))}
          </Card>
        </div>

        <div className="space-y-4 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            {tabBtn("week", "Week")}
            {tabBtn("list", "List")}
            {view === "week" ? (
              <>
                <button onClick={() => setWeekStart(addDaysStr(weekStart, -7))} className="px-2 py-1.5 rounded-lg border text-xs" style={{ borderColor: C.border, color: C.text }}>‹</button>
                <button onClick={() => setWeekStart(mondayOf(new Date()))} className="px-3 py-1.5 rounded-lg border text-xs font-semibold" style={{ borderColor: C.border, color: C.text }}>Today</button>
                <button onClick={() => setWeekStart(addDaysStr(weekStart, 7))} className="px-2 py-1.5 rounded-lg border text-xs" style={{ borderColor: C.border, color: C.text }}>›</button>
                <span className="text-xs font-semibold" style={{ color: C.text }}>{weekLabel}</span>
              </>
            ) : (
              <>
                <input type="date" className="px-3 py-1.5 rounded-xl border text-xs" style={inputSty} value={range.from} onChange={e => setRange(r => ({ ...r, from: e.target.value }))} />
                <span className="text-xs" style={{ color: C.muted }}>to</span>
                <input type="date" className="px-3 py-1.5 rounded-xl border text-xs" style={inputSty} value={range.to} onChange={e => setRange(r => ({ ...r, to: e.target.value }))} />
              </>
            )}
            <span className="text-xs ml-auto" style={{ color: C.muted }}>{data ? `${data.bookings.length} booked` : ""}</span>
          </div>

          {data === null && <div className="py-10 flex justify-center"><Spinner size={20} /></div>}

          {data && view === "week" && (
            <>
              <CalendarWeekGrid data={data} weekStart={weekStart} onSelect={setSelected} selectedId={selected?.id} />
              {selected && (
                <Card className="p-4 flex items-center justify-between gap-4 flex-wrap">
                  <div className="text-xs space-y-0.5">
                    <p className="text-sm font-bold" style={{ color: C.text }}>{selected.candidateName}</p>
                    <p style={{ color: C.muted }}>{ROUND_LABELS[selected.interviewType] || selected.interviewType} · {selected.roleTitle}</p>
                    {selected.interviewerName && <p style={{ color: C.muted }}>Interviewer: <span className="font-semibold" style={{ color: C.text }}>{selected.interviewerName}</span></p>}
                    <p className="font-semibold" style={{ color: selected.status === "COMPLETED" ? C.success : C.accent }}>
                      {selected.status === "COMPLETED" ? "✓ Interview completed" : "Confirmed"}
                    </p>
                    <p style={{ color: C.accent }} className="font-semibold">
                      {new Date(selected.start).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" })}, {clockTime(selected.start)}–{clockTime(selected.end)}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    {selected.status !== "COMPLETED" && (
                      <Btn variant="secondary" onClick={() => rescheduleBooking(selected.id)}>Reschedule by email</Btn>
                    )}
                    <Btn variant="secondary" onClick={() => setSelected(null)}>Close</Btn>
                  </div>
                </Card>
              )}
            </>
          )}

          {data && view === "list" && dayKeys.length === 0 && (
            <Card className="p-8 text-center text-sm" style={{ color: C.muted }}>Nothing in this range yet. Publish availability on the left.</Card>
          )}
          {data && view === "list" && dayKeys.map(day => (
            <Card key={day} className="p-4 space-y-3">
              <div className="flex items-center gap-2 flex-wrap">
                <p className="text-xs font-bold" style={{ color: C.text }}>
                  {new Date(day + "T00:00").toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" })}
                </p>
                {days[day].windows.map(w => (
                  <span key={w.id} className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-semibold"
                    style={{ border: `1px solid ${C.success}66`, color: C.success }}>
                    Open {clockTime(w.start)}–{clockTime(w.end)}
                    <button onClick={() => act(`/calendar/windows/${w.id}`, "DELETE")} title="Remove availability"><X size={11} /></button>
                  </span>
                ))}
              </div>
              {days[day].bookings.length === 0 ? (
                <p className="text-xs" style={{ color: C.muted }}>No interviews booked.</p>
              ) : (
                <div className="space-y-2">
                  {days[day].bookings.map(b => (
                    <div key={b.id} className="flex items-center justify-between gap-3 px-3 py-2 rounded-xl text-xs"
                      style={{ backgroundColor: `${C.accent}12`, border: `1px solid ${C.accent}44` }}>
                      <div>
                        <span className="font-bold" style={{ color: C.accent }}>{clockTime(b.start)}–{clockTime(b.end)}</span>
                        <span className="ml-2 font-semibold" style={{ color: C.text }}>{b.candidateName}</span>
                        <span className="ml-2" style={{ color: C.muted }}>{(ROUND_LABELS[b.interviewType] || b.interviewType)} · {b.roleTitle}{b.interviewerName ? ` · with ${b.interviewerName}` : ""}</span>
                      </div>
                      {b.status === "COMPLETED"
                        ? <span className="shrink-0 font-semibold" style={{ color: C.success }}>✓ Completed</span>
                        : <button onClick={() => rescheduleBooking(b.id)} className="underline shrink-0" style={{ color: C.danger }}>Reschedule</button>}
                    </div>
                  ))}
                </div>
              )}
            </Card>
          ))}
        </div>
      </div>
    </div>
  );
}

// ============================================================
// TALENT INTELLIGENCE — pipeline depth, funnel, supply vs demand, early warnings
// ============================================================
const COVERAGE_META = {
  CRITICAL: { label: "Critical", color: "#EF4444" },
  AT_RISK:  { label: "At risk",  color: "#F59E0B" },
  HEALTHY:  { label: "Healthy",  color: "#10B981" },
  FILLED:   { label: "Filled",   color: "#6366F1" },
};
const SKILL_META = {
  SHORTAGE: { label: "Shortage", color: "#EF4444" },
  TIGHT:    { label: "Tight",    color: "#F59E0B" },
  OK:       { label: "Covered",  color: "#10B981" },
};
const SEVERITY_COLOR = { HIGH: "#EF4444", MEDIUM: "#F59E0B", LOW: "#64748B" };

function IntelPill({ meta }) {
  return (
    <span className="text-[10px] font-bold px-2 py-0.5 rounded-full text-white whitespace-nowrap" style={{ backgroundColor: meta.color }}>
      {meta.label}
    </span>
  );
}

function IntelSection({ title, hint, children }) {
  return (
    <Card className="p-5">
      <div className="mb-3">
        <h3 className="text-sm font-bold" style={{ color: C.text }}>{title}</h3>
        {hint && <p className="text-xs mt-0.5" style={{ color: C.muted }}>{hint}</p>}
      </div>
      {children}
    </Card>
  );
}

const BUDGET_STATE = {
  NO_LIMIT:   { label: "No limit set",            color: "#64748B" },
  OK:         { label: "Within budget",           color: "#10B981" },
  WARNING:    { label: "Nearly used up",          color: "#F59E0B" },
  BLOCKED:    { label: "AI paused",               color: "#EF4444" },
  OVER_LIMIT: { label: "Over limit, not stopping", color: "#F59E0B" },
};

function BudgetPanel({ budget, blockedToday, onChange }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    daily: budget.dailyLimit ?? "", monthly: budget.monthlyLimit ?? "", warn: budget.warnPercent, hardStop: budget.hardStop,
  });
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState(null);
  const money = v => `$${Number(v).toFixed(v < 1 ? 4 : 2)}`;
  const meta = BUDGET_STATE[budget.state] || BUDGET_STATE.NO_LIMIT;
  const bar = (label, spent, limit, percent) => (
    <div>
      <div className="flex items-center justify-between text-xs mb-1">
        <span style={{ color: C.text }}>{label}</span>
        <span style={{ color: C.muted }}>
          <strong style={{ color: C.text }}>{money(spent)}</strong>{limit != null ? ` of ${money(limit)} (${Math.round(percent)}%)` : " · no limit"}
        </span>
      </div>
      <div className="h-2 rounded-full" style={{ backgroundColor: C.border }}>
        <div className="h-2 rounded-full" style={{
          width: limit != null ? `${Math.min(100, percent)}%` : "0%",
          backgroundColor: percent >= 100 ? "#EF4444" : percent >= budget.warnPercent ? "#F59E0B" : C.success }} />
      </div>
    </div>
  );

  async function save() {
    setSaving(true); setMsg(null);
    try {
      await apiFetch("/intelligence/ai-budget", {
        method: "PUT",
        body: JSON.stringify({
          dailyLimitUsd: form.daily === "" ? null : Number(form.daily),
          monthlyLimitUsd: form.monthly === "" ? null : Number(form.monthly),
          warnPercent: Number(form.warn), hardStop: form.hardStop,
        }),
      });
      setMsg({ text: "Saved." }); setOpen(false); onChange();
    } catch (e) { setMsg({ err: true, text: e.message }); }
    finally { setSaving(false); }
  }

  async function screenWaiting() {
    setMsg(null);
    try {
      const r = await apiFetch("/intelligence/retry-unscreened", { method: "POST" });
      setMsg({ text: r.screened > 0 ? `${r.screened} waiting applicant${r.screened === 1 ? "" : "s"} screened.` : "Nobody was waiting, or AI use is still paused." });
      onChange();
    } catch (e) { setMsg({ err: true, text: e.message }); }
  }

  const inputCls = "w-full px-3 py-2 rounded-xl border text-sm";
  const inputSty = { borderColor: C.border, color: C.text, backgroundColor: C.bg };
  return (
    <div className="mb-5 p-4 rounded-xl" style={{ border: `1px solid ${meta.color}55`, backgroundColor: `${meta.color}08` }}>
      <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
        <div className="flex items-center gap-2">
          <p className="text-sm font-bold" style={{ color: C.text }}>Spending limits</p>
          <span className="text-[10px] font-bold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: meta.color }}>{meta.label}</span>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={screenWaiting} className="text-xs underline" style={{ color: C.accent }}>Screen waiting applicants now</button>
          <button onClick={() => setOpen(o => !o)} className="text-xs font-semibold px-3 py-1.5 rounded-lg border" style={{ borderColor: C.border, color: C.text }}>
            {open ? "Close" : "Change limits"}
          </button>
        </div>
      </div>
      <div className="space-y-2.5">
        {bar("Today", budget.dailySpent, budget.dailyLimit, budget.dailyPercent ?? 0)}
        {bar("This month", budget.monthlySpent, budget.monthlyLimit, budget.monthlyPercent ?? 0)}
      </div>
      {budget.state === "BLOCKED" && (
        <p className="text-xs mt-3" style={{ color: C.text }}>
          AI calls are paused. New applications are still saved and are screened automatically once AI use resumes
          {blockedToday > 0 ? ` (${blockedToday} call${blockedToday === 1 ? "" : "s"} refused today)` : ""}.
        </p>
      )}
      {msg && <p className="text-xs mt-2" style={{ color: msg.err ? C.danger : C.success }}>{msg.text}</p>}
      {open && (
        <div className="mt-4 grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))" }}>
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Daily limit (US$)</label>
            <input type="number" min="0" step="0.01" className={inputCls} style={inputSty} placeholder="No limit"
              value={form.daily} onChange={e => setForm(f => ({ ...f, daily: e.target.value }))} />
          </div>
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Monthly limit (US$)</label>
            <input type="number" min="0" step="0.01" className={inputCls} style={inputSty} placeholder="No limit"
              value={form.monthly} onChange={e => setForm(f => ({ ...f, monthly: e.target.value }))} />
          </div>
          <div>
            <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Warn at (% of limit)</label>
            <input type="number" min="1" max="100" className={inputCls} style={inputSty}
              value={form.warn} onChange={e => setForm(f => ({ ...f, warn: e.target.value }))} />
          </div>
          <div className="flex flex-col justify-end gap-2">
            <label className="flex items-center gap-2 text-xs" style={{ color: C.text }}>
              <input type="checkbox" checked={form.hardStop} onChange={e => setForm(f => ({ ...f, hardStop: e.target.checked }))} />
              Pause AI when a limit is reached
            </label>
            <Btn variant="primary" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save limits"}</Btn>
          </div>
          <p className="text-[11px]" style={{ gridColumn: "1 / -1", color: C.muted }}>
            Only an admin can save changes. Leave a limit blank for no limit. The HR inbox gets an email when a warning level is reached and when AI is paused.
          </p>
        </div>
      )}
    </div>
  );
}

function AiUsageSection() {
  const { data, loading, error, reload } = useApi("/intelligence/ai-usage");
  const money = v => (v == null ? "—" : `$${v.toFixed(v < 1 ? 4 : 2)}`);
  const num = v => Number(v).toLocaleString();
  const th = "text-left px-3 py-2 text-xs font-semibold";

  if (loading && !data) return <IntelSection title="AI usage and cost"><Spinner size={18} /></IntelSection>;
  if (error) return <IntelSection title="AI usage and cost"><ErrorBanner message={error} onRetry={reload} /></IntelSection>;
  if (!data) return null;

  const { today, last7Days, last30Days, projectedMonthlyCost, byFeature, byDay, unitCosts, recentFailures, pricing, budget, blockedCallsToday } = data;
  const maxDay = Math.max(0.0001, ...byDay.map(d => d.cost));
  const tiles = [
    ["Today", money(today.cost), `${today.calls} calls`],
    ["Last 7 days", money(last7Days.cost), `${last7Days.calls} calls`],
    ["Last 30 days", money(last30Days.cost), `${num(last30Days.inputTokens + last30Days.outputTokens)} tokens`],
    ["Projected this month", money(projectedMonthlyCost), "at the last 7 days' pace"],
    ["Per screened candidate", money(unitCosts.costPerScreenedCandidate), `${unitCosts.screenedCandidates} screened`],
    ["Per hire", money(unitCosts.costPerHire), `${unitCosts.hires} hire${unitCosts.hires === 1 ? "" : "s"}, all AI use`],
  ];

  return (
    <IntelSection title="AI usage and cost" hint="What the AI is costing, and where. These are estimates from token counts.">
      <BudgetPanel key={`${budget.dailyLimit}-${budget.monthlyLimit}-${budget.warnPercent}-${budget.hardStop}`} budget={budget} blockedToday={blockedCallsToday} onChange={reload} />
      {last30Days.calls === 0 ? (
        <p className="text-sm" style={{ color: C.muted }}>No AI calls recorded yet. They appear here as soon as the AI is used.</p>
      ) : (
        <div className="space-y-5">
          <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))" }}>
            {tiles.map(([label, value, sub]) => (
              <div key={label} className="p-3 rounded-xl" style={{ backgroundColor: C.bg }}>
                <p className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>{label}</p>
                <p className="text-xl font-bold mt-1" style={{ color: C.text }}>{value}</p>
                <p className="text-[11px] mt-0.5" style={{ color: C.muted }}>{sub}</p>
              </div>
            ))}
          </div>

          <div>
            <p className="text-xs font-semibold mb-2" style={{ color: C.text }}>Cost per day, last 14 days</p>
            <div className="flex items-end gap-1" style={{ height: 70 }}>
              {byDay.map(d => (
                <div key={d.date} className="flex-1 rounded-t" title={`${d.date}: ${money(d.cost)} (${d.calls} calls)`}
                  style={{ height: `${Math.max(3, (d.cost / maxDay) * 100)}%`, backgroundColor: d.cost > 0 ? C.accent : C.border }} />
              ))}
            </div>
            <div className="flex justify-between text-[10px] mt-1" style={{ color: C.muted }}>
              <span>{byDay[0].date.slice(5)}</span><span>{byDay[byDay.length - 1].date.slice(5)}</span>
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                  <th className={th}>What asked for it</th><th className={th}>Calls</th><th className={th}>Failed</th>
                  <th className={th}>Avg tokens in / out</th><th className={th}>Avg per call</th><th className={th}>Total</th><th className={th}>Share</th>
                </tr>
              </thead>
              <tbody className="divide-y" style={{ borderColor: C.border }}>
                {byFeature.map(f => (
                  <tr key={f.feature}>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{f.feature}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{f.calls}</td>
                    <td className="px-3 py-2.5" style={{ color: f.failedCalls > 0 ? C.danger : C.muted }}>{f.failedCalls}</td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: C.muted }}>{num(f.avgInputTokens)} / {num(f.avgOutputTokens)}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{money(f.avgCostPerCall)}</td>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{money(f.totalCost)}</td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: C.muted }}>{Math.round(f.shareOfCost)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {recentFailures.length > 0 && (
            <div>
              <p className="text-xs font-semibold mb-2" style={{ color: C.danger }}>Recent failed calls ({last30Days.failedCalls} in 30 days)</p>
              <div className="space-y-1">
                {recentFailures.map((f, i) => (
                  <p key={i} className="text-xs" style={{ color: C.muted }}>
                    {new Date(f.at).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })} · <strong style={{ color: C.text }}>{f.feature}</strong> · {(f.error || "").slice(0, 120)}
                  </p>
                ))}
              </div>
            </div>
          )}
          <p className="text-[11px]" style={{ color: C.muted }}>{pricing}</p>
        </div>
      )}
    </IntelSection>
  );
}

function IntelligenceView() {
  const { data, loading, error, reload } = useApi("/intelligence/overview");

  if (loading && !data) return <div className="py-20 flex justify-center"><Spinner size={24} /></div>;
  if (error) return <ErrorBanner message={error} onRetry={reload} />;
  if (!data) return null;

  const { kpis, alerts, roleCoverage, funnel, skills, channels, stalled, timeToFill } = data;
  const maxFunnel = Math.max(1, ...funnel.map(f => f.count));
  const maxSkill = Math.max(1, ...skills.map(s => Math.max(s.candidatesWithSkill, s.openRolesNeeding)));
  const th = "text-left px-3 py-2 text-xs font-semibold";
  const fmtDate = d => new Date(d).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  const tiles = [
    ["Open roles", kpis.openRoles],
    ["Qualified candidates", kpis.qualifiedCandidates],
    ["In process", kpis.candidatesInProcess],
    ["Avg days to fill", kpis.avgDaysToFill != null ? Math.round(kpis.avgDaysToFill) : "—"],
    ["Offer acceptance", kpis.offerAcceptanceRate != null ? `${Math.round(kpis.offerAcceptanceRate)}%` : "—"],
    ["Hires", kpis.hires],
  ];

  return (
    <div>
      <TopBar title="Talent Intelligence" subtitle="Where hiring is healthy, where it is at risk, and what to do about it"
        action={
          <button onClick={reload} className="w-9 h-9 rounded-xl border flex items-center justify-center" style={{ borderColor: C.border }}>
            <RefreshCw size={13} style={{ color: C.muted }} />
          </button>
        } />

      <div className="space-y-5">
        {/* Early warnings */}
        <IntelSection title="Early warnings" hint="Things that need attention, most urgent first">
          {alerts.length === 0 ? (
            <p className="text-sm" style={{ color: C.success }}>No risks detected right now.</p>
          ) : (
            <div className="space-y-2">
              {alerts.map((a, i) => (
                <div key={i} className="px-3 py-2.5 rounded-xl" style={{ borderLeft: `4px solid ${SEVERITY_COLOR[a.severity]}`, backgroundColor: `${SEVERITY_COLOR[a.severity]}0d` }}>
                  <p className="text-sm font-semibold" style={{ color: C.text }}>{a.title}</p>
                  <p className="text-xs mt-0.5" style={{ color: C.muted }}>{a.detail}</p>
                </div>
              ))}
            </div>
          )}
        </IntelSection>

        {/* KPI tiles */}
        <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))" }}>
          {tiles.map(([label, value]) => (
            <Card key={label} className="p-4">
              <p className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>{label}</p>
              <p className="text-2xl font-bold mt-1" style={{ color: C.text }}>{value}</p>
            </Card>
          ))}
        </div>

        {/* Role coverage */}
        <IntelSection title="Pipeline depth by role" hint="Each open role is one opening. Healthy means 3 or more qualified candidates.">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                  <th className={th}>Role</th><th className={th}>Days open</th><th className={th}>Applicants</th>
                  <th className={th}>Qualified</th><th className={th}>Interviewing</th><th className={th}>Offers</th>
                  <th className={th}>Status</th><th className={th}>Expected fill</th>
                </tr>
              </thead>
              <tbody className="divide-y" style={{ borderColor: C.border }}>
                {roleCoverage.map(r => (
                  <tr key={r.requisitionId}>
                    <td className="px-3 py-2.5"><p className="font-semibold" style={{ color: C.text }}>{r.title}</p>
                      {r.department && <p className="text-xs" style={{ color: C.muted }}>{r.department}</p>}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.daysOpen}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.applicants}</td>
                    <td className="px-3 py-2.5">
                      <div className="flex items-center gap-2">
                        <div className="h-1.5 w-16 rounded-full" style={{ backgroundColor: C.border }}>
                          <div className="h-1.5 rounded-full" style={{ width: `${Math.min(100, (r.qualified / 3) * 100)}%`, backgroundColor: COVERAGE_META[r.status].color }} />
                        </div>
                        <span className="text-xs font-semibold" style={{ color: C.text }}>{r.qualified}/3</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.interviewing}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.offers}</td>
                    <td className="px-3 py-2.5"><IntelPill meta={COVERAGE_META[r.status]} /></td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: C.muted }}>{r.expectedFillDate ? fmtDate(r.expectedFillDate) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {roleCoverage.length === 0 && <p className="text-sm py-6 text-center" style={{ color: C.muted }}>No open roles.</p>}
          </div>
          <p className="text-xs mt-3" style={{ color: C.muted }}>
            Time to fill: {timeToFill.averageDays != null ? `${Math.round(timeToFill.averageDays)} days on average. ` : ""}{timeToFill.note}
          </p>
        </IntelSection>

        <div className="grid gap-5" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(340px, 1fr))" }}>
          {/* Funnel */}
          <IntelSection title="Hiring funnel" hint="How far candidates get, and the share who move on from each step">
            <div className="space-y-2">
              {funnel.map(f => (
                <div key={f.step}>
                  <div className="flex items-center justify-between text-xs mb-1">
                    <span style={{ color: C.text }}>{f.step}</span>
                    <span style={{ color: C.muted }}>
                      <strong style={{ color: C.text }}>{f.count}</strong>
                      {f.conversionFromPrevious != null && ` · ${Math.round(f.conversionFromPrevious)}% of previous`}
                    </span>
                  </div>
                  <div className="h-2 rounded-full" style={{ backgroundColor: C.border }}>
                    <div className="h-2 rounded-full" style={{ width: `${(f.count / maxFunnel) * 100}%`, backgroundColor: C.accent }} />
                  </div>
                </div>
              ))}
            </div>
          </IntelSection>

          {/* Skills */}
          <IntelSection title="Skills: demand vs supply" hint="Open roles that need a skill, against candidates in the system who have it">
            {skills.length === 0 ? <p className="text-sm" style={{ color: C.muted }}>No skills listed on open roles yet.</p> : (
              <div className="space-y-2.5">
                {skills.map(s => (
                  <div key={s.skill}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-xs font-semibold capitalize" style={{ color: C.text }}>{s.skill}</span>
                      <IntelPill meta={SKILL_META[s.status]} />
                    </div>
                    <div className="space-y-0.5">
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] w-14" style={{ color: C.muted }}>Roles</span>
                        <div className="h-1.5 rounded-full" style={{ width: `${(s.openRolesNeeding / maxSkill) * 70}%`, minWidth: 4, backgroundColor: C.warning }} />
                        <span className="text-[10px]" style={{ color: C.text }}>{s.openRolesNeeding}</span>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] w-14" style={{ color: C.muted }}>Candidates</span>
                        <div className="h-1.5 rounded-full" style={{ width: `${(s.candidatesWithSkill / maxSkill) * 70}%`, minWidth: 4, backgroundColor: C.success }} />
                        <span className="text-[10px]" style={{ color: C.text }}>{s.candidatesWithSkill}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </IntelSection>
        </div>

        {/* Channels */}
        <IntelSection title="Which channels work" hint="Where candidates come from, and how many go on to be advanced or hired">
          {channels.length === 0 ? <p className="text-sm" style={{ color: C.muted }}>No candidates yet.</p> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                    <th className={th}>Channel</th><th className={th}>Candidates</th><th className={th}>Avg score</th>
                    <th className={th}>Advanced</th><th className={th}>Hired</th>
                  </tr>
                </thead>
                <tbody className="divide-y" style={{ borderColor: C.border }}>
                  {channels.map(c => (
                    <tr key={c.channel}>
                      <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{c.channel.replace(/_/g, " ")}</td>
                      <td className="px-3 py-2.5" style={{ color: C.text }}>{c.candidates}</td>
                      <td className="px-3 py-2.5" style={{ color: C.text }}>{c.avgScore != null ? Math.round(c.avgScore) : "—"}</td>
                      <td className="px-3 py-2.5" style={{ color: C.text }}>{c.advanced} <span className="text-xs" style={{ color: C.muted }}>({Math.round(c.advanceRate)}%)</span></td>
                      <td className="px-3 py-2.5" style={{ color: C.text }}>{c.hired} <span className="text-xs" style={{ color: C.muted }}>({Math.round(c.hireRate)}%)</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </IntelSection>

        {/* Stalled */}
        <IntelSection title="Stuck candidates" hint="In the same stage for a week or more on a role that is still open">
          {stalled.length === 0 ? <p className="text-sm" style={{ color: C.success }}>Nobody is stuck.</p> : (
            <div className="divide-y" style={{ borderColor: C.border }}>
              {stalled.map(s => (
                <div key={`${s.candidateId}-${s.requisitionId}`} className="flex items-center justify-between py-2 text-sm">
                  <div>
                    <span className="font-semibold" style={{ color: C.text }}>{s.candidateName}</span>
                    <span className="text-xs ml-2" style={{ color: C.muted }}>{s.roleTitle}</span>
                  </div>
                  <span className="text-xs" style={{ color: C.muted }}>{s.stage.replace(/_/g, " ").toLowerCase()} · <strong style={{ color: C.warning }}>{s.daysInStage} days</strong></span>
                </div>
              ))}
            </div>
          )}
        </IntelSection>

        <AiUsageSection />
      </div>
    </div>
  );
}

// ============================================================
// SCREENING QUALITY — do recruiters agree with the AI, and do recruiters agree with each other?
// ============================================================
function ScreeningQualityView() {
  const { data, loading, error, reload } = useApi("/intelligence/screening-quality");

  if (loading && !data) return <div className="py-20 flex justify-center"><Spinner size={24} /></div>;
  if (error) return <ErrorBanner message={error} onRetry={reload} />;
  if (!data) return null;

  const { totals, agreement, matrix, scoreBands, scoreSeparation, reviewers, roles, outcomes, insights, confidence } = data;
  const th = "text-left px-3 py-2 text-xs font-semibold";
  const pct = v => (v != null ? `${Math.round(v)}%` : "—");
  const rateColor = v => (v == null ? C.muted : v >= 80 ? C.success : v >= 60 ? C.warning : C.danger);
  const AI_LABEL = { ADVANCE: "AI said advance", REJECT: "AI said reject", REVIEW: "AI asked for review" };
  const tiles = [
    ["AI and recruiters agree", pct(agreement.rate), `${agreement.agreed} of ${agreement.compared} decisions`, rateColor(agreement.rate)],
    ["Human decisions", totals.humanDecided, `of ${totals.screened} screened`, C.text],
    ["AI rejections overturned", pct(agreement.aiRejectOverturnRate), `${agreement.aiRejectOverturned} of ${agreement.aiRejectsWithHumanDecision}`, C.text],
    ["Avg score: advanced", scoreSeparation.avgScoreHumanAdvance != null ? Math.round(scoreSeparation.avgScoreHumanAdvance) : "—", `rejected: ${scoreSeparation.avgScoreHumanReject != null ? Math.round(scoreSeparation.avgScoreHumanReject) : "—"}`, C.text],
    ["Waiting for a decision", totals.awaitingReview, "left for review", totals.awaitingReview > 0 ? C.warning : C.text],
  ];

  return (
    <div>
      <TopBar title="Screening Quality" subtitle="How often recruiters agree with the AI, and whether screening is consistent"
        action={
          <button onClick={reload} className="w-9 h-9 rounded-xl border flex items-center justify-center" style={{ borderColor: C.border }}>
            <RefreshCw size={13} style={{ color: C.muted }} />
          </button>
        } />
      <p className="text-xs mb-4 -mt-2" style={{ color: C.muted }}>{confidence}</p>

      <div className="space-y-5">
        <IntelSection title="What to look at" hint="Findings drawn from the figures below">
          {insights.length === 0 ? (
            <p className="text-sm" style={{ color: C.success }}>Nothing unusual found.</p>
          ) : (
            <div className="space-y-2">
              {insights.map((a, i) => (
                <div key={i} className="px-3 py-2.5 rounded-xl" style={{ borderLeft: `4px solid ${SEVERITY_COLOR[a.severity]}`, backgroundColor: `${SEVERITY_COLOR[a.severity]}0d` }}>
                  <p className="text-sm font-semibold" style={{ color: C.text }}>{a.title}</p>
                  <p className="text-xs mt-0.5" style={{ color: C.muted }}>{a.detail}</p>
                </div>
              ))}
            </div>
          )}
        </IntelSection>

        <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))" }}>
          {tiles.map(([label, value, sub, color]) => (
            <Card key={label} className="p-4">
              <p className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>{label}</p>
              <p className="text-2xl font-bold mt-1" style={{ color }}>{value}</p>
              <p className="text-xs mt-0.5" style={{ color: C.muted }}>{sub}</p>
            </Card>
          ))}
        </div>

        <div className="grid gap-5" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(340px, 1fr))" }}>
          <IntelSection title="AI against recruiters" hint="What recruiters did with each kind of AI recommendation">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                  <th className={th}></th><th className={th}>Advanced</th><th className={th}>Rejected</th><th className={th}>No decision yet</th>
                </tr>
              </thead>
              <tbody className="divide-y" style={{ borderColor: C.border }}>
                {matrix.map(r => (
                  <tr key={r.aiRecommendation}>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{AI_LABEL[r.aiRecommendation]}</td>
                    <td className="px-3 py-2.5" style={{ color: r.aiRecommendation === "REJECT" && r.humanAdvance > 0 ? C.danger : C.text }}>{r.humanAdvance}</td>
                    <td className="px-3 py-2.5" style={{ color: r.aiRecommendation === "ADVANCE" && r.humanReject > 0 ? C.danger : C.text }}>{r.humanReject}</td>
                    <td className="px-3 py-2.5" style={{ color: C.muted }}>{r.noHumanDecision}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-xs mt-3" style={{ color: C.muted }}>Red numbers are cases where the recruiter went against the AI.</p>
          </IntelSection>

          <IntelSection title="Does the score mean anything?" hint="Share of candidates recruiters advanced, by the AI's score">
            <div className="space-y-2.5">
              {scoreBands.map(b => (
                <div key={b.band}>
                  <div className="flex items-center justify-between text-xs mb-1">
                    <span style={{ color: C.text }}>{b.band}</span>
                    <span style={{ color: C.muted }}>
                      {b.decided === 0 ? "no decisions" : <><strong style={{ color: C.text }}>{pct(b.humanAdvanceRate)}</strong> advanced ({b.humanAdvanced} of {b.decided})</>}
                    </span>
                  </div>
                  <div className="h-2 rounded-full" style={{ backgroundColor: C.border }}>
                    <div className="h-2 rounded-full" style={{ width: `${b.humanAdvanceRate ?? 0}%`, backgroundColor: C.accent }} />
                  </div>
                </div>
              ))}
            </div>
            <p className="text-xs mt-3" style={{ color: C.muted }}>A useful score climbs from left to right: the higher the score, the more often recruiters advance the candidate.</p>
          </IntelSection>
        </div>

        <IntelSection title="Do recruiters decide alike?" hint="Decisions made on the Screening page. Moves made on the Pipeline board are not attributed to a person.">
          {reviewers.length === 0 ? <p className="text-sm" style={{ color: C.muted }}>No reviewed decisions yet.</p> : (
            <table className="w-full text-sm">
              <thead>
                <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                  <th className={th}>Recruiter</th><th className={th}>Decisions</th><th className={th}>Advances</th><th className={th}>Agrees with AI</th>
                </tr>
              </thead>
              <tbody className="divide-y" style={{ borderColor: C.border }}>
                {reviewers.map(r => (
                  <tr key={r.reviewer}>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{r.reviewer}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.decisions}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{pct(r.advanceRate)}</td>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: rateColor(r.agreementWithAi) }}>{pct(r.agreementWithAi)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </IntelSection>

        <IntelSection title="By role" hint="Where the AI and recruiters line up, and where they do not">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                  <th className={th}>Role</th><th className={th}>Screened</th><th className={th}>Avg score</th>
                  <th className={th}>AI advances</th><th className={th}>Human decisions</th><th className={th}>Agreement</th>
                </tr>
              </thead>
              <tbody className="divide-y" style={{ borderColor: C.border }}>
                {roles.map(r => (
                  <tr key={r.requisitionId}>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{r.title}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.screened}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.avgScore != null ? Math.round(r.avgScore) : "—"}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{pct(r.aiAdvanceRate)}</td>
                    <td className="px-3 py-2.5" style={{ color: C.text }}>{r.humanDecided}</td>
                    <td className="px-3 py-2.5 font-semibold" style={{ color: rateColor(r.agreementRate) }}>{pct(r.agreementRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {roles.length === 0 && <p className="text-sm py-6 text-center" style={{ color: C.muted }}>Nothing screened yet.</p>}
          </div>
        </IntelSection>

        <IntelSection title="What happened next" hint="How far candidates got, grouped by what the AI recommended">
          <table className="w-full text-sm">
            <thead>
              <tr style={{ backgroundColor: C.bg, color: C.muted }}>
                <th className={th}>AI recommendation</th><th className={th}>Candidates</th><th className={th}>Shortlisted</th>
                <th className={th}>Interviewed</th><th className={th}>Hired</th>
              </tr>
            </thead>
            <tbody className="divide-y" style={{ borderColor: C.border }}>
              {outcomes.map(o => (
                <tr key={o.aiRecommendation}>
                  <td className="px-3 py-2.5 font-semibold" style={{ color: C.text }}>{AI_LABEL[o.aiRecommendation]}</td>
                  <td className="px-3 py-2.5" style={{ color: C.text }}>{o.results}</td>
                  <td className="px-3 py-2.5" style={{ color: C.text }}>{o.shortlisted}</td>
                  <td className="px-3 py-2.5" style={{ color: C.text }}>{o.interviewed}</td>
                  <td className="px-3 py-2.5" style={{ color: C.text }}>{o.hired}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-xs mt-3" style={{ color: C.muted }}>If the AI is useful, candidates it advances should get further than candidates it rejects.</p>
        </IntelSection>
      </div>
    </div>
  );
}

// ============================================================
// SIDEBAR
// ============================================================
function Sidebar({ active, setActive, user }) {
  const nav = [
    { id: "dashboard",    label: "Dashboard",         icon: LayoutGrid },
    { id: "intelligence", label: "Intelligence",      icon: TrendingUp },
    { id: "roles",        label: "Open Roles",         icon: Briefcase  },
    { id: "pipeline",     label: "Pipeline",           icon: Activity   },
    { id: "screening",    label: "Screening",          icon: FileCheck  },
    { id: "screeningQuality", label: "Screening Quality", icon: CheckCircle2 },
    { id: "calendar",     label: "Calendar",           icon: Calendar   },
    { id: "referrals",    label: "Referral",           icon: UserPlus   },
    { id: "integrations", label: "Integrations",       icon: Globe      },
    { id: "cvInbox",      label: "Resume Drop Zone", icon: Inbox      },
  ];

  return (
    <aside className="w-64 shrink-0 flex flex-col" style={{ backgroundColor: C.sidebar }}>
      <div className="px-6 py-6 flex items-center gap-3">
        <div className="w-8 h-8 rounded-xl flex items-center justify-center"
          style={{ background: "rgba(255,255,255,0.2)" }}>
          <Sparkles size={16} color="#fff" />
        </div>
        <div>
          <p className="text-white font-bold text-sm tracking-tight">TalentAcquisition AI</p>
          <p className="text-xs" style={{ color: "rgba(255,255,255,0.75)" }}>Agentic recruiting</p>
        </div>
      </div>

      <nav className="flex-1 px-3 space-y-0.5">
        {nav.map(({ id, label, icon: Icon }) => {
          const active_ = active === id;
          return (
            <button key={id} onClick={() => setActive(id)}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all text-left"
              style={{
                backgroundColor: active_ ? "rgba(255,255,255,0.2)" : "transparent",
                color: "#fff",
              }}>
              <Icon size={16} style={{ color: active_ ? "#4FC3FF" : "#fff" }} />
              {label}
              {active_ && <ChevronRight size={14} className="ml-auto" style={{ color: "#4FC3FF" }} />}
            </button>
          );
        })}
      </nav>

      <div className="px-4 py-4 border-t" style={{ borderColor: "rgba(255,255,255,0.15)" }}>
        <div className="flex items-center gap-3 px-2 mb-3">
          <Avatar name={user?.fullName || "User"} size={34} />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-white truncate">{user?.fullName}</p>
            <p className="text-xs truncate" style={{ color: "rgba(255,255,255,0.75)" }}>{user?.role}</p>
          </div>
        </div>
      </div>
    </aside>
  );
}

// ============================================================
// DASHBOARD
// ============================================================
function Dashboard({ setActive }) {
  const { data: summary, loading, error, reload } = useApi("/orchestrator/dashboard");
  const { data: insights, reload: reloadInsights } = useApi("/orchestrator/insights");
  const { data: attention, reload: reloadAttention } = useApi("/intelligence/attention");

  const stats = summary ? [
    { label: "Open Requisitions",    value: summary.openRequisitions,       icon: Briefcase,  color: C.accent,   nav: "roles"      },
    { label: "Candidates in Pipeline",value: summary.candidatesInPipeline,   icon: Activity,   color: "#8B5CF6",  nav: "pipeline"   },
    { label: "Screened This Week",    value: summary.candidatesScreenedThisWeek,icon: FileCheck, color: "#0EA5E9",  nav: "screening"  },
    { label: "Edge Cases to Review", value: summary.edgeCasesAwaitingReview,icon: AlertCircle,color: C.warning,  nav: "screening"  },
    { label: "Interviews Scheduled", value: summary.interviewsScheduled,    icon: Calendar,   color: C.success,  nav: "pipeline"   },
  ] : [];

  return (
    <div>
      <TopBar title="Dashboard"
        subtitle="Real-time view of agent activity across talent acquisition"
        action={
          <button onClick={() => { reload(); reloadInsights(); reloadAttention(); }} className="flex items-center gap-1.5 text-sm font-medium px-3 py-2 rounded-xl border"
            style={{ borderColor: C.border, color: C.muted }}>
            <RefreshCw size={13} /> Refresh
          </button>
        }
      />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {/* What needs a person's action today */}
      {attention && (() => {
        const items = [
          ["Edge cases to review", attention.edgeCasesToReview, "screening"],
          ["Offers awaiting a reply", attention.offersAwaitingReply, "pipeline"],
          ["Interviews today", attention.interviewsToday, "calendar"],
          ["Waiting to book a time", attention.waitingToBook, "pipeline"],
          ["Applicants waiting to be screened", attention.applicantsWaitingScreening, "screening"],
        ];
        const total = items.reduce((n, [, v]) => n + v, 0);
        return (
          <Card className="p-5 mb-6">
            <div className="flex items-center justify-between mb-3">
              <h2 className="font-semibold text-sm" style={{ color: C.text }}>Needs your attention</h2>
              {total === 0 && <span className="text-xs font-semibold" style={{ color: C.success }}>Nothing is waiting on you right now.</span>}
            </div>
            <div className="grid gap-3" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))" }}>
              {items.map(([label, value, nav]) => (
                <button key={label} onClick={() => setActive(nav)} className="text-left p-3 rounded-xl border hover:shadow-sm transition-shadow"
                  style={{ borderColor: value > 0 ? `${C.warning}66` : C.border, backgroundColor: value > 0 ? `${C.warning}0d` : "transparent" }}>
                  <p className="text-2xl font-bold font-mono" style={{ color: value > 0 ? C.warning : C.muted }}>{value}</p>
                  <p className="text-xs mt-0.5" style={{ color: C.text }}>{label}</p>
                </button>
              ))}
            </div>
          </Card>
        );
      })()}

      {/* KPI cards */}
      <div className="grid grid-cols-5 gap-4 mb-6">
        {loading
          ? Array.from({ length: 6 }).map((_, i) => (
              <Card key={i} className="p-5">
                <div className="h-8 w-16 bg-slate-100 rounded-lg animate-pulse mb-2" />
                <div className="h-3 w-24 bg-slate-100 rounded animate-pulse" />
              </Card>
            ))
          : stats.map((s) => {
              const Icon = s.icon;
              return (
                <Card key={s.label} className="p-5 cursor-pointer hover:shadow-md transition-shadow"
                  onClick={() => s.nav && setActive(s.nav)}>
                  <div className="flex items-start justify-between mb-3">
                    <div className="w-9 h-9 rounded-xl flex items-center justify-center"
                      style={{ backgroundColor: `${s.color}18` }}>
                      <Icon size={17} style={{ color: s.color }} />
                    </div>
                    <ChevronRight size={13} style={{ color: C.muted }} className="mt-1 opacity-50" />
                  </div>
                  <p className="text-3xl font-bold font-mono mb-1" style={{ color: C.text }}>{s.value}</p>
                  <p className="text-xs font-medium" style={{ color: C.muted }}>{s.label}</p>
                </Card>
              );
            })}
      </div>

      <div className="grid grid-cols-3 gap-6">
        {/* Activity feed */}
        <Card className="col-span-2 overflow-hidden">
          <div className="px-5 py-4 border-b flex items-center gap-2" style={{ borderColor: C.border }}>
            <Activity size={16} style={{ color: C.accent }} />
            <h2 className="font-semibold text-sm" style={{ color: C.text }}>Agent Activity Feed</h2>
            <div className="ml-auto flex items-center gap-3">
              <span className="flex items-center gap-1.5 text-xs font-medium" style={{ color: C.success }}>
                <span className="w-1.5 h-1.5 rounded-full animate-pulse" style={{ backgroundColor: C.success }} />
                Live
              </span>
              <button
                onClick={() => setActive("activity")}
                className="text-xs font-semibold hover:underline"
                style={{ color: C.accent }}
              >
                View detail
              </button>
            </div>
          </div>
          <div className="divide-y" style={{ borderColor: C.border }}>
            {loading
              ? Array.from({ length: 5 }).map((_, i) => (
                  <div key={i} className="px-5 py-3.5 flex items-center gap-3">
                    <div className="h-5 w-20 bg-slate-100 rounded-full animate-pulse" />
                    <div className="flex-1 h-3 bg-slate-100 rounded animate-pulse" />
                  </div>
                ))
              : (summary?.recentActivity || []).length === 0
                ? <p className="text-sm text-center py-10" style={{ color: C.muted }}>No recent activity</p>
                : (summary?.recentActivity || []).map((entry) => (
                    <div key={entry.id}
                      className="px-5 py-3.5 flex items-start gap-3 hover:bg-slate-50 transition-colors">
                      <div className="mt-0.5"><AgentPill agent={activityAgent(entry)} /></div>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm" style={{ color: C.text }}>
                          <span className="font-semibold">{entry.candidateName || "—"}</span>
                          {entry.requisitionTitle && (
                            <span style={{ color: C.muted }}> · {entry.requisitionTitle}</span>
                          )}
                        </p>
                        <p className="text-xs mt-0.5" style={{ color: C.muted }}>{entry.details}</p>
                      </div>
                      <span className="text-xs font-mono shrink-0 mt-0.5" style={{ color: C.muted }}>
                        {timeAgo(entry.createdAt)}
                      </span>
                    </div>
                  ))}
          </div>
        </Card>

        {/* Right column */}
        <div className="flex flex-col gap-6">
          {/* Edge cases */}
          <Card className="overflow-hidden">
            <div className="px-5 py-4 border-b flex items-center gap-2" style={{ borderColor: C.border }}>
              <AlertCircle size={16} style={{ color: C.warning }} />
              <h2 className="font-semibold text-sm" style={{ color: C.text }}>Needs Your Decision</h2>
            </div>
            <EdgeCasesList onViewAll={() => setActive("screening")} />
          </Card>

          {/* Most urgent hiring risks; the full analysis lives on Intelligence */}
          <Card className="overflow-hidden">
            <div className="px-5 py-4 border-b flex items-center justify-between" style={{ borderColor: C.border }}>
              <h2 className="font-semibold text-sm" style={{ color: C.text }}>Hiring risks</h2>
              <button onClick={() => setActive("intelligence")} className="text-xs font-semibold hover:underline" style={{ color: C.accent }}>
                Full analysis
              </button>
            </div>
            <div className="p-4 space-y-2">
              {!attention ? <p className="text-xs" style={{ color: C.muted }}>Loading…</p>
                : attention.topWarnings.length === 0 ? <p className="text-sm" style={{ color: C.success }}>No risks detected right now.</p>
                : attention.topWarnings.map((a, i) => (
                  <div key={i} className="px-3 py-2 rounded-xl" style={{
                    borderLeft: `4px solid ${a.severity === "HIGH" ? "#EF4444" : a.severity === "MEDIUM" ? "#F59E0B" : "#64748B"}`,
                    backgroundColor: C.bg }}>
                    <p className="text-xs font-semibold" style={{ color: C.text }}>{a.title}</p>
                    <p className="text-[11px] mt-0.5" style={{ color: C.muted }}>{a.detail}</p>
                  </div>
                ))}
            </div>
          </Card>
        </div>
      </div>

      {/* Talent Market Insights */}
      {insights && (
        <div className="mt-6">
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp size={16} style={{ color: C.accent }} />
            <h2 className="font-semibold text-sm" style={{ color: C.text }}>Talent Market Insights</h2>
          </div>
          <div className="grid grid-cols-3 gap-4">
            {/* Overall pass rate */}
            <Card className="p-5">
              <p className="text-xs font-medium mb-1" style={{ color: C.muted }}>AI Advance Rate</p>
              <p className="text-3xl font-bold font-mono tabular-nums mb-0.5" style={{ color: C.text }}>
                {insights.overallPassRate}%
              </p>
              <p className="text-xs" style={{ color: C.muted }}>
                of {insights.totalScreened} screened, the share the AI recommended to advance
              </p>
              <div className="mt-3 flex gap-2 text-xs">
                {[
                  { label: "Advance", key: "ADVANCE", color: C.success },
                  { label: "Review",  key: "REVIEW",  color: C.warning },
                  { label: "Reject",  key: "REJECT",  color: C.danger  },
                ].map(({ label, key, color }) => (
                  <div key={key} className="flex-1 rounded-lg p-2 text-center"
                    style={{ backgroundColor: `${color}14` }}>
                    <p className="font-bold font-mono" style={{ color }}>{insights.recommendationBreakdown?.[key] ?? 0}</p>
                    <p style={{ color: C.muted }}>{label}</p>
                  </div>
                ))}
              </div>
            </Card>

            {/* Screening by role */}
            <Card className="p-5 col-span-2">
              <p className="text-xs font-medium mb-3" style={{ color: C.muted }}>AI Advance Rate by Role</p>
              {insights.screeningByRequisition?.length === 0 && (
                <p className="text-xs text-center py-4" style={{ color: C.muted }}>
                  No screening data yet — run the sourcing &amp; screening agents to populate.
                </p>
              )}
              <div className="space-y-3">
                {(insights.screeningByRequisition || []).map((row) => (
                  <div key={row.requisitionId}>
                    <div className="flex justify-between text-xs mb-1">
                      <span className="truncate max-w-[60%]" style={{ color: C.text }}>{row.requisitionTitle}</span>
                      <span className="font-mono tabular-nums" style={{ color: C.muted }}>
                        {row.advanced}/{row.total} · <span style={{ color: C.success }}>{row.passRate}%</span>
                      </span>
                    </div>
                    <div className="h-1.5 rounded-full overflow-hidden" style={{ backgroundColor: `${C.success}20` }}>
                      <div className="h-full rounded-full transition-all duration-500"
                        style={{ width: `${Math.max(row.passRate, row.total > 0 ? 2 : 0)}%`, backgroundColor: C.success }} />
                    </div>
                  </div>
                ))}
              </div>
              {insights.topRequiredSkills?.length > 0 && (
                <div className="mt-4 pt-4 border-t" style={{ borderColor: C.border }}>
                  <p className="text-xs font-medium mb-2" style={{ color: C.muted }}>Top Required Skills (Open Roles)</p>
                  <div className="flex flex-wrap gap-1.5">
                    {insights.topRequiredSkills.map((skill) => (
                      <span key={skill} className="text-xs px-2 py-0.5 rounded-full font-medium"
                        style={{ backgroundColor: `${C.accent}18`, color: C.accent }}>
                        {skill}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </Card>
          </div>
        </div>
      )}

    </div>
  );
}

function ActivityView({ setActive }) {
  const { data: activity, loading, error, reload } = useApi("/orchestrator/activity?limit=100");
  const [agentFilter, setAgentFilter] = useState("ALL");
  const [search, setSearch] = useState("");
  const entries = activity || [];
  const agents = [...new Set(entries.map(activityAgent).filter(Boolean))].sort();
  const filtered = entries.filter(entry => {
    const matchesAgent = agentFilter === "ALL" || activityAgent(entry) === agentFilter;
    const query = search.trim().toLowerCase();
    const matchesSearch = !query || [
      activityAgent(entry), AGENT_META[activityAgent(entry)]?.label, entry.candidateName, entry.requisitionTitle, entry.details,
    ].some(value => value?.toLowerCase().includes(query));
    return matchesAgent && matchesSearch;
  });

  return (
    <div>
      <TopBar
        title="Agent Activity"
        subtitle="Full activity history across the recruitment agents"
        action={
          <div className="flex items-center gap-2">
            <button onClick={() => setActive("dashboard")}
              className="text-sm font-semibold px-3 py-2 rounded-xl border hover:bg-slate-50"
              style={{ borderColor: C.border, color: C.muted }}>
              Back to Dashboard
            </button>
            <button onClick={reload}
              className="text-sm font-semibold px-3 py-2 rounded-xl border hover:bg-slate-50"
              style={{ borderColor: C.border, color: C.accent }}>
              <RefreshCw size={13} className="inline mr-1" /> Refresh
            </button>
          </div>
        }
      />
      <Card className="overflow-hidden">
        <div className="p-4 border-b flex flex-wrap items-center gap-3" style={{ borderColor: C.border }}>
          <select value={agentFilter} onChange={e => setAgentFilter(e.target.value)}
            className="text-sm px-3 py-2 rounded-xl border bg-white outline-none"
            style={{ borderColor: C.border, color: C.text }}>
            <option value="ALL">All agents</option>
            {agents.map(agent => <option key={agent} value={agent}>{AGENT_META[agent]?.label || agent}</option>)}
          </select>
          <input value={search} onChange={e => setSearch(e.target.value)}
            className="flex-1 min-w-52 text-sm px-3 py-2 rounded-xl border outline-none"
            style={{ borderColor: C.border, color: C.text }}
            placeholder="Search candidate, role, agent, or activity…" />
          <span className="text-xs font-medium" style={{ color: C.muted }}>
            {filtered.length} of {entries.length} activities
          </span>
        </div>
        {error && <div className="p-4"><ErrorBanner message={error} onRetry={reload} /></div>}
        {loading ? (
          <div className="p-5 space-y-3"><LoadingCard rows={2} /><LoadingCard rows={2} /></div>
        ) : filtered.length === 0 ? (
          <p className="text-sm text-center py-16" style={{ color: C.muted }}>No matching activity found</p>
        ) : (
          <div className="divide-y" style={{ borderColor: C.border }}>
            {filtered.map(entry => (
              <div key={entry.id} className="px-5 py-4 flex items-start gap-4 hover:bg-slate-50 transition-colors">
                <AgentPill agent={activityAgent(entry)} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm" style={{ color: C.text }}>
                    <span className="font-semibold">{entry.candidateName || "—"}</span>
                    {entry.requisitionTitle && <span style={{ color: C.muted }}> · {entry.requisitionTitle}</span>}
                  </p>
                  <p className="text-sm mt-1" style={{ color: C.muted }}>{entry.details || entry.action}</p>
                </div>
                <div className="text-right shrink-0">
                  <p className="text-xs font-semibold" style={{ color: C.text }}>{entry.action}</p>
                  <p className="text-xs font-mono mt-1" style={{ color: C.muted }}>{timeAgo(entry.createdAt)}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}

function EdgeCasesList({ onViewAll }) {
  const { data: edgeCases, loading } = useApi("/screening/edge-cases");

  if (loading) return (
    <div className="p-4 space-y-3">
      {[1,2].map(i => <LoadingCard key={i} rows={2} />)}
    </div>
  );

  const list = edgeCases || [];

  return (
    <div className="p-4 space-y-3">
      {list.length === 0 && (
        <p className="text-sm text-center py-6" style={{ color: C.muted }}>
          No pending reviews 🎉
        </p>
      )}
      {list.map((r) => (
        <div key={r.id} className="p-3 rounded-xl border" style={{ borderColor: C.border, backgroundColor: "#FFFBEB" }}>
          <div className="flex items-center gap-2 mb-1.5">
            <ScoreRing score={r.overallScore} size={36} />
            <div className="min-w-0">
              <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{r.candidateName}</p>
              <p className="text-xs truncate" style={{ color: C.muted }}>{r.requisitionTitle}</p>
            </div>
          </div>
          <button onClick={onViewAll}
            className="text-xs font-semibold flex items-center gap-1 mt-1" style={{ color: C.warning }}>
            Review now <ArrowRight size={11} />
          </button>
        </div>
      ))}
    </div>
  );
}

// ============================================================
// ROLES VIEW
// ============================================================
const EXP_LABEL = { JUNIOR: "Junior", MID: "Mid-Level", SENIOR: "Senior", LEAD: "Lead / Principal" };
const STATUS_STYLE = {
  OPEN:    { bg: "#ECFDF5", color: "#059669", label: "Open" },
  ON_HOLD: { bg: "#FEF9C3", color: "#92400E", label: "On Hold" },
  CLOSED:  { bg: "#F1F5F9", color: "#64748B", label: "Closed" },
};

/** Large editable viewer for a job description — the inline textarea in the Create/Edit Role
 *  forms is too small for the longer, segmented JDs the AI now generates (About the Role /
 *  Key Responsibilities / etc.), so this gives a proper full-size view with the same
 *  whitespace-preserving rendering used on the role detail page, while staying editable. */
function JobDescriptionDialog({ value, onChange, onClose }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-2xl max-h-[85vh] flex flex-col">
        <div className="px-6 py-4 border-b flex items-center justify-between shrink-0" style={{ borderColor: C.border }}>
          <h2 className="text-lg font-bold" style={{ color: C.text }}>Job Description</h2>
          <button onClick={onClose} className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
            <X size={16} style={{ color: C.muted }} />
          </button>
        </div>
        <div className="p-6 overflow-y-auto flex-1">
          <textarea
            value={value}
            onChange={e => onChange(e.target.value)}
            placeholder="Responsibilities, team context, what success looks like…"
            className="w-full h-full min-h-[50vh] px-4 py-3 rounded-xl border text-sm leading-relaxed resize-none"
            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
        </div>
        <div className="px-6 py-4 border-t flex justify-end shrink-0" style={{ borderColor: C.border }}>
          <Btn onClick={onClose}>Done</Btn>
        </div>
      </Card>
    </div>
  );
}

function RolesView({ setActive }) {
  const { data: roles, loading, error, reload } = useApi("/requisitions");
  const [filter, setFilter] = useState("ALL");
  const [detail, setDetail] = useState(null);        // RequisitionResponse | null
  const [newRoleModal, setNewRoleModal] = useState(false);
  const [roleForm, setRoleForm]   = useState({ title: "", department: "", location: "", experienceLevel: "MID", description: "", requiredSkills: "", requiredInterviewRounds: 2 });
  const [savingRole, setSavingRole] = useState(false);
  const [jdText, setJdText]       = useState("");
  const [parsing, setParsing]     = useState(false);
  const [parseMode, setParseMode] = useState(false);  // toggle paste vs manual
  const [generatingJd, setGeneratingJd] = useState(false);
  const [generateJdError, setGenerateJdError] = useState(null);
  const [jdDialogOpen, setJdDialogOpen] = useState(false);
  const [updatingStatus, setUpdatingStatus] = useState(null);
  const [editModal, setEditModal] = useState(null);   // RequisitionResponse | null
  const [editForm, setEditForm]   = useState({});
  const [savingEdit, setSavingEdit] = useState(false);
  const [bulkModal, setBulkModal] = useState(false);
  const [bulkText, setBulkText]   = useState("");
  const [bulkInputMode, setBulkInputMode] = useState("upload"); // "upload" | "paste"
  const [bulkParsing, setBulkParsing] = useState(false);
  const [bulkParsed, setBulkParsed]   = useState(null);  // CreateRequisitionRequest[] | null
  const [bulkSaving, setBulkSaving]   = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishedIds, setPublishedIds] = useState(new Set());

  const filtered = (roles || []).filter(r => filter === "ALL" || r.status === filter);

  function openEditModal(e, r) {
    e.stopPropagation();
    setEditForm({
      title: r.title || "",
      department: r.department || "",
      location: r.location || "",
      experienceLevel: r.experienceLevel || "MID",
      requiredSkills: r.requiredSkills || "",
      description: r.description || "",
      requiredInterviewRounds: r.requiredInterviewRounds ?? 2,
    });
    setEditModal(r);
  }

  async function handleSaveEdit() {
    if (!editForm.title.trim()) return;
    setSavingEdit(true);
    try {
      await apiFetch(`/requisitions/${editModal.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(editForm),
      });
      setEditModal(null);
      reload();
    } finally {
      setSavingEdit(false);
    }
  }

  async function handleParseJd() {
    if (!jdText.trim()) return;
    setParsing(true);
    try {
      const parsed = await apiFetch("/requisitions/parse-jd", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: jdText }),
      });
      setRoleForm({
        title: parsed.title || "",
        department: parsed.department || "",
        location: parsed.location || "",
        experienceLevel: parsed.experienceLevel || "MID",
        requiredSkills: parsed.requiredSkills || "",
        description: parsed.description || "",
      });
      setParseMode(false);
    } finally {
      setParsing(false);
    }
  }

  async function handleGenerateJd() {
    if (!roleForm.title.trim()) return;
    setGeneratingJd(true);
    setGenerateJdError(null);
    try {
      const result = await apiFetch("/requisitions/generate-jd", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: roleForm.title,
          department: roleForm.department,
          location: roleForm.location,
          experienceLevel: roleForm.experienceLevel,
          requiredSkills: roleForm.requiredSkills,
        }),
      });
      setRoleForm(f => ({ ...f, description: result.description || "" }));
    } catch (e) {
      setGenerateJdError(e.message || "Could not generate a description.");
    } finally {
      setGeneratingJd(false);
    }
  }

  async function handleCreateRole() {
    if (!roleForm.title.trim()) return;
    setSavingRole(true);
    try {
      await apiFetch("/requisitions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(roleForm),
      });
      setNewRoleModal(false);
      setRoleForm({ title: "", department: "", location: "", experienceLevel: "MID", description: "", requiredSkills: "", requiredInterviewRounds: 2 });
      setJdText(""); setParseMode(false);
      reload();
    } finally {
      setSavingRole(false);
    }
  }

  async function handleStatusChange(id, status) {
    setUpdatingStatus(id);
    try {
      await apiFetch(`/requisitions/${id}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
      });
      reload();
      if (detail?.id === id) setDetail(d => ({ ...d, status }));
    } finally {
      setUpdatingStatus(null);
    }
  }

  async function handlePublishToJobBoard(id) {
    setPublishing(true);
    try {
      const res = await apiFetch(`/requisitions/${id}/publish-to-jobboard`, { method: "POST" });
      if (res.status === "already_published") {
        alert("This role is already published to the job board.");
      } else {
        setPublishedIds(prev => new Set([...prev, id]));
        alert(`Role published to the mock job board! (Job Board ID: ${res.mockJobRoleId})`);
      }
    } catch (e) {
      alert("Failed to publish role: " + (e.message || "Unknown error"));
    } finally {
      setPublishing(false);
    }
  }

  async function handleBulkParse() {
    if (!bulkText.trim()) return;
    setBulkParsing(true);
    setBulkParsed(null);
    try {
      const parsed = await apiFetch("/requisitions/bulk-parse", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: bulkText }),
      });
      setBulkParsed(parsed.map((r, i) => ({ ...r, _key: i })));
    } finally {
      setBulkParsing(false);
    }
  }

  async function handleBulkCreate() {
    if (!bulkParsed?.length) return;
    setBulkSaving(true);
    try {
      await apiFetch("/requisitions/bulk", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(bulkParsed.map(({ _key, ...r }) => r)),
      });
      setBulkModal(false);
      setBulkText(""); setBulkParsed(null);
      reload();
    } finally {
      setBulkSaving(false);
    }
  }

  const skills = r => r.requiredSkills ? r.requiredSkills.split(/,\s*/).filter(Boolean) : [];

  // ── Detail page (replaces list) ──
  if (detail) {
    const st = STATUS_STYLE[detail.status] || STATUS_STYLE.CLOSED;
    const sk = skills(detail);
    return (
      <div>
        <TopBar
          title={detail.title}
          subtitle={[detail.department, detail.location, EXP_LABEL[detail.experienceLevel]].filter(Boolean).join(" · ")}
          action={
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold px-2.5 py-1 rounded-full"
                style={{ backgroundColor: st.bg, color: st.color }}>{st.label}</span>
              <Btn variant="secondary" onClick={() => setDetail(null)}>← Back to Roles</Btn>
            </div>
          }
        />
        <div className="grid grid-cols-3 gap-6">
          <div className="col-span-2 space-y-6">
            {detail.description && (
              <Card className="p-6">
                <p className="text-xs font-bold uppercase tracking-wide mb-3" style={{ color: C.muted }}>Description</p>
                <p className="text-sm leading-relaxed" style={{ color: C.text, whiteSpace: "pre-line" }}>{detail.description}</p>
              </Card>
            )}
            {sk.length > 0 && (
              <Card className="p-6">
                <p className="text-xs font-bold uppercase tracking-wide mb-3" style={{ color: C.muted }}>Required Skills</p>
                <div className="flex flex-wrap gap-2">
                  {sk.map(s => (
                    <span key={s} className="text-sm px-3 py-1.5 rounded-lg font-medium"
                      style={{ backgroundColor: `${C.accent}10`, color: C.accent }}>{s}</span>
                  ))}
                </div>
              </Card>
            )}
            <ApplicantsPanel requisitionId={detail.id} />
            <AgentActivityPanel requisitionId={detail.id} />
          </div>
          <div className="space-y-4">
            <Card className="p-5 space-y-3">
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Role Settings</p>
              <div className="flex items-center justify-between">
                <span className="text-xs" style={{ color: C.muted }}>Interview Rounds</span>
                <span className="text-sm font-bold" style={{ color: C.accent }}>{detail.requiredInterviewRounds ?? 2}</span>
              </div>
            </Card>
            <WebhookUrlCard requisitionId={detail.id} />
            <Card className="p-5 space-y-3">
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Actions</p>
              <button onClick={() => { setActive("pipeline"); setDetail(null); }}
                className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-white"
                style={{ backgroundColor: C.accent }}>
                <Activity size={14} /> View Pipeline
              </button>
              <button
                disabled={publishing || publishedIds.has(detail.id)}
                onClick={() => handlePublishToJobBoard(detail.id)}
                className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-white"
                style={{ backgroundColor: publishedIds.has(detail.id) ? C.success : "#7c3aed", opacity: publishing ? 0.5 : 1 }}>
                {publishing ? "Publishing…" : publishedIds.has(detail.id) ? "✓ Published to Job Board" : "Publish to Job Board"}
              </button>
              {detail.status !== "OPEN" && (
                <button disabled={!!updatingStatus} onClick={() => handleStatusChange(detail.id, "OPEN")}
                  className="w-full px-4 py-2.5 rounded-xl text-sm font-semibold text-white"
                  style={{ backgroundColor: C.success, opacity: updatingStatus ? 0.5 : 1 }}>
                  Reopen Role
                </button>
              )}
              {detail.status === "OPEN" && (
                <button disabled={!!updatingStatus} onClick={() => handleStatusChange(detail.id, "ON_HOLD")}
                  className="w-full px-4 py-2.5 rounded-xl text-sm font-semibold text-white"
                  style={{ backgroundColor: C.warning, opacity: updatingStatus ? 0.5 : 1 }}>
                  Put On Hold
                </button>
              )}
              {detail.status !== "CLOSED" && (
                <button disabled={!!updatingStatus} onClick={() => handleStatusChange(detail.id, "CLOSED")}
                  className="w-full px-4 py-2.5 rounded-xl border text-sm font-semibold"
                  style={{ borderColor: C.border, color: C.muted, opacity: updatingStatus ? 0.5 : 1 }}>
                  Close Role
                </button>
              )}
            </Card>
            {detail.createdAt && (
              <p className="text-xs px-1" style={{ color: C.muted }}>
                Created {new Date(detail.createdAt).toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" })}
              </p>
            )}
          </div>
        </div>
      </div>
    );
  }

  // ── List page ──
  return (
    <div>
      <TopBar title="Open Roles" subtitle="Manage job requisitions and create new positions"
        action={
          <div className="flex items-center gap-2">
            {["ALL","OPEN","ON_HOLD","CLOSED"].map(s => (
              <button key={s} onClick={() => setFilter(s)}
                className="px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors"
                style={{
                  borderColor: filter === s ? C.accent : C.border,
                  backgroundColor: filter === s ? `${C.accent}12` : "transparent",
                  color: filter === s ? C.accent : C.muted,
                }}>
                {s === "ALL" ? "All" : STATUS_STYLE[s]?.label}
              </button>
            ))}
            <Btn variant="secondary" onClick={() => { setBulkModal(true); setBulkParsed(null); setBulkText(""); setBulkInputMode("upload"); }}>
              <FileText size={13} /> Bulk Import
            </Btn>
            <Btn variant="primary" onClick={() => setNewRoleModal(true)}>
              <Plus size={13} /> New Role
            </Btn>
          </div>
        }
      />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {loading ? (
        <div className="grid grid-cols-3 gap-4">
          {[1,2,3].map(i => <Card key={i} className="p-5 h-40 animate-pulse bg-slate-50" />)}
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-4">
          {filtered.map(r => {
            const st = STATUS_STYLE[r.status] || STATUS_STYLE.CLOSED;
            const sk = skills(r);
            return (
              <Card key={r.id} className="p-5 cursor-pointer hover:shadow-md transition-shadow"
                onClick={() => setDetail(r)}>
                <div className="flex items-start justify-between mb-3">
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center"
                    style={{ backgroundColor: `${C.accent}12` }}>
                    <Briefcase size={16} style={{ color: C.accent }} />
                  </div>
                  <div className="flex items-center gap-1.5">
                    <button onClick={e => openEditModal(e, r)}
                      className="w-6 h-6 rounded-lg flex items-center justify-center hover:bg-slate-100 transition-colors"
                      title="Edit role">
                      <Pencil size={12} style={{ color: C.muted }} />
                    </button>
                    <span className="text-[10px] font-bold px-2 py-0.5 rounded-full"
                      style={{ backgroundColor: st.bg, color: st.color }}>{st.label}</span>
                  </div>
                </div>
                <h3 className="font-bold text-sm mb-1" style={{ color: C.text }}>{r.title}</h3>
                <p className="text-xs mb-1" style={{ color: C.muted }}>
                  {[r.department, r.location].filter(Boolean).join(" · ")}
                  {r.experienceLevel && <span> · {EXP_LABEL[r.experienceLevel] || r.experienceLevel}</span>}
                </p>
                {sk.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-3">
                    {sk.slice(0, 4).map(s => (
                      <span key={s} className="text-[10px] px-1.5 py-0.5 rounded-md font-medium"
                        style={{ backgroundColor: `${C.accent}10`, color: C.accent }}>{s}</span>
                    ))}
                    {sk.length > 4 && <span className="text-[10px]" style={{ color: C.muted }}>+{sk.length - 4}</span>}
                  </div>
                )}
              </Card>
            );
          })}
          {filtered.length === 0 && (
            <div className="col-span-3 py-16 text-center">
              <p className="text-sm" style={{ color: C.muted }}>No roles found. Create one to get started.</p>
            </div>
          )}
        </div>
      )}

      {/* Bulk Import Modal */}
      {bulkModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-3xl p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex items-start justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Bulk Import Roles</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>Paste any unstructured text — job postings, a list of roles, emails — Claude will extract all roles</p>
              </div>
              <button onClick={() => { setBulkModal(false); setBulkParsed(null); setBulkText(""); setBulkInputMode("upload"); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            {!bulkParsed ? (
              <div className="space-y-3">
                {/* Upload vs Paste tabs */}
                <div className="flex gap-1 p-1 rounded-xl w-fit mb-1" style={{ backgroundColor: C.surface }}>
                  {[["upload","Upload File"],["paste","Paste Text"]].map(([id, label]) => (
                    <button key={id}
                      onClick={() => setBulkInputMode(id)}
                      className="px-4 py-1.5 rounded-lg text-xs font-semibold transition-colors"
                      style={{ backgroundColor: bulkInputMode === id ? C.accent : "transparent", color: bulkInputMode === id ? "#fff" : C.muted }}>
                      {label}
                    </button>
                  ))}
                </div>

                {bulkInputMode === "upload" ? (
                  <label className="flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed cursor-pointer transition-colors hover:border-blue-400"
                    style={{ borderColor: C.border, minHeight: 200 }}>
                    <input type="file" accept=".txt,.csv,.md,.doc,.docx" className="hidden"
                      onChange={e => {
                        const file = e.target.files?.[0];
                        if (!file) return;
                        const reader = new FileReader();
                        reader.onload = ev => { setBulkText(ev.target.result); setBulkInputMode("paste"); };
                        reader.readAsText(file);
                        e.target.value = "";
                      }} />
                    <FileText size={32} style={{ color: C.muted }} />
                    <div className="text-center">
                      <p className="text-sm font-semibold" style={{ color: C.text }}>Click to upload a file</p>
                      <p className="text-xs mt-0.5" style={{ color: C.muted }}>.txt · .csv · .md · Supports any plain-text format</p>
                    </div>
                    {bulkText && (
                      <p className="text-xs font-semibold" style={{ color: C.success }}>✓ File loaded — {bulkText.length} characters</p>
                    )}
                  </label>
                ) : (
                  <textarea rows={12} placeholder={"Paste multiple job descriptions here. They can be separated by blank lines, dashes, or numbered. Example:\n\n1. Senior React Developer – Engineering, KL\n\n2. Data Engineer – Analytics, Remote\n\n---\nProduct Manager, Mid-Level, Kuala Lumpur…"}
                    value={bulkText} onChange={e => setBulkText(e.target.value)}
                    className="w-full px-3 py-2 rounded-xl border text-sm resize-none font-mono"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                )}

                <div className="flex gap-2">
                  <Btn variant="primary" onClick={handleBulkParse} disabled={bulkParsing || !bulkText.trim()}>
                    {bulkParsing ? <><Spinner size={13} /> Extracting roles…</> : <><Sparkles size={13} /> Extract Roles with AI</>}
                  </Btn>
                  <Btn variant="secondary" onClick={() => { setBulkModal(false); setBulkText(""); }}>Cancel</Btn>
                </div>
              </div>
            ) : (
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <p className="text-sm font-semibold" style={{ color: C.success }}>
                    ✓ {bulkParsed.length} role{bulkParsed.length !== 1 ? "s" : ""} extracted — review and edit before importing
                  </p>
                  <button onClick={() => { setBulkParsed(null); }}
                    className="text-xs font-semibold px-2.5 py-1 rounded-lg border"
                    style={{ color: C.muted, borderColor: C.border }}>
                    ← Re-parse
                  </button>
                </div>

                <div className="space-y-3 max-h-[50vh] overflow-y-auto pr-1">
                  {bulkParsed.map((r, idx) => (
                    <div key={r._key} className="p-4 rounded-xl border" style={{ borderColor: C.border }}>
                      <div className="flex items-start justify-between mb-3">
                        <span className="text-xs font-bold px-2 py-0.5 rounded-full"
                          style={{ backgroundColor: `${C.accent}12`, color: C.accent }}>Role {idx + 1}</span>
                        <button onClick={() => setBulkParsed(p => p.filter(x => x._key !== r._key))}
                          className="w-6 h-6 rounded-lg flex items-center justify-center hover:bg-red-50 transition-colors"
                          title="Remove this role">
                          <Trash2 size={12} style={{ color: C.danger }} />
                        </button>
                      </div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <div className="col-span-2">
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Title *</label>
                          <input value={r.title || ""} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, title: e.target.value } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: r.title ? C.border : `${C.danger}88`, color: C.text, backgroundColor: C.bg }} />
                        </div>
                        <div>
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Department</label>
                          <input value={r.department || ""} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, department: e.target.value } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                        </div>
                        <div>
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Location</label>
                          <input value={r.location || ""} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, location: e.target.value } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                        </div>
                        <div>
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Experience</label>
                          <select value={r.experienceLevel || "MID"} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, experienceLevel: e.target.value } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}>
                            {[["JUNIOR","Junior"],["MID","Mid-Level"],["SENIOR","Senior"],["LEAD","Lead / Principal"]].map(([v,l]) => (
                              <option key={v} value={v}>{l}</option>
                            ))}
                          </select>
                        </div>
                        <div>
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Interview Rounds</label>
                          <select value={r.requiredInterviewRounds ?? 2} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, requiredInterviewRounds: Number(e.target.value) } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}>
                            {[1,2,3,4].map(n => <option key={n} value={n}>{n}</option>)}
                          </select>
                        </div>
                        <div className="col-span-2">
                          <label className="block font-semibold mb-1" style={{ color: C.muted }}>Required Skills</label>
                          <input value={r.requiredSkills || ""} onChange={e => setBulkParsed(p => p.map(x => x._key === r._key ? { ...x, requiredSkills: e.target.value } : x))}
                            className="w-full px-2.5 py-1.5 rounded-lg border text-xs"
                            style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>

                <div className="flex gap-2 pt-1">
                  <Btn variant="primary" onClick={handleBulkCreate} disabled={bulkSaving || bulkParsed.every(r => !r.title?.trim())}>
                    {bulkSaving ? <><Spinner size={13} /> Creating…</> : <><Plus size={13} /> Create {bulkParsed.length} Role{bulkParsed.length !== 1 ? "s" : ""}</>}
                  </Btn>
                  <Btn variant="secondary" onClick={() => { setBulkModal(false); setBulkParsed(null); setBulkText(""); }}>Cancel</Btn>
                </div>
              </div>
            )}
          </Card>
        </div>
      )}

      {/* Edit Role Modal */}
      {editModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex items-start justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Edit Role</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>{editModal.title}</p>
              </div>
              <button onClick={() => setEditModal(null)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Job Title <span style={{ color: C.danger }}>*</span></label>
                <input type="text" value={editForm.title}
                  onChange={e => setEditForm(f => ({ ...f, title: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: editForm.title ? C.border : `${C.danger}88`, color: C.text, backgroundColor: C.bg }} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Department</label>
                  <input type="text" value={editForm.department}
                    onChange={e => setEditForm(f => ({ ...f, department: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Location</label>
                  <input type="text" value={editForm.location}
                    onChange={e => setEditForm(f => ({ ...f, location: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
              </div>
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: C.muted }}>Experience Level</label>
                <div className="flex gap-2 flex-wrap">
                  {[["JUNIOR","Junior"],["MID","Mid-Level"],["SENIOR","Senior"],["LEAD","Lead / Principal"]].map(([v,l]) => (
                    <button key={v} onClick={() => setEditForm(f => ({ ...f, experienceLevel: v }))}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors"
                      style={{ borderColor: editForm.experienceLevel === v ? C.accent : C.border, backgroundColor: editForm.experienceLevel === v ? `${C.accent}15` : "transparent", color: editForm.experienceLevel === v ? C.accent : C.muted }}>{l}</button>
                  ))}
                </div>
              </div>
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Required Skills <span style={{ color: C.border }}>(comma-separated)</span></label>
                <input type="text" value={editForm.requiredSkills}
                  onChange={e => setEditForm(f => ({ ...f, requiredSkills: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Interview Rounds Required</label>
                <div className="flex gap-2">
                  {[1,2,3,4].map(n => (
                    <button key={n} onClick={() => setEditForm(f => ({ ...f, requiredInterviewRounds: n }))}
                      className="px-4 py-1.5 rounded-xl border text-sm font-semibold transition-colors"
                      style={{ borderColor: editForm.requiredInterviewRounds === n ? C.accent : C.border, backgroundColor: editForm.requiredInterviewRounds === n ? `${C.accent}15` : "transparent", color: editForm.requiredInterviewRounds === n ? C.accent : C.muted }}>
                      {n}
                    </button>
                  ))}
                </div>
              </div>
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Job Description <span style={{ color: C.border }}>(optional)</span></label>
                <textarea rows={3} value={editForm.description}
                  onChange={e => setEditForm(f => ({ ...f, description: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
              <div className="flex gap-2 pt-1">
                <Btn variant="primary" onClick={handleSaveEdit} disabled={savingEdit || !editForm.title.trim()}>
                  {savingEdit ? <><Spinner size={13} /> Saving…</> : "Save Changes"}
                </Btn>
                <Btn variant="secondary" onClick={() => setEditModal(null)}>Cancel</Btn>
              </div>
            </div>
          </Card>
        </div>
      )}

      {/* New Role Modal */}
      {newRoleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex items-start justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Create Open Role</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>Fill manually or paste a job description to auto-fill</p>
              </div>
              <button onClick={() => { setNewRoleModal(false); setParseMode(false); setJdText(""); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            <div className="flex gap-2 mb-5">
              <button onClick={() => setParseMode(false)}
                className="flex-1 py-2 rounded-xl text-xs font-semibold border transition-colors"
                style={{ borderColor: !parseMode ? C.accent : C.border, backgroundColor: !parseMode ? `${C.accent}10` : "transparent", color: !parseMode ? C.accent : C.muted }}>
                Manual Entry
              </button>
              <button onClick={() => setParseMode(true)}
                className="flex-1 py-2 rounded-xl text-xs font-semibold border transition-colors flex items-center justify-center gap-1"
                style={{ borderColor: parseMode ? C.accent : C.border, backgroundColor: parseMode ? `${C.accent}10` : "transparent", color: parseMode ? C.accent : C.muted }}>
                <Sparkles size={11} /> Parse with AI
              </button>
            </div>

            {parseMode ? (
              <div className="space-y-3">
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Paste Job Description</label>
                <textarea rows={10} placeholder="Paste the full job description here — Claude will extract the title, department, skills, and more…"
                  value={jdText} onChange={e => setJdText(e.target.value)}
                  className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                <Btn variant="primary" onClick={handleParseJd} disabled={parsing || !jdText.trim()}>
                  {parsing ? <><Spinner size={13} /> Parsing…</> : <><Sparkles size={13} /> Extract Fields</>}
                </Btn>
                {!parsing && roleForm.title && (
                  <p className="text-xs font-semibold" style={{ color: C.success }}>
                    ✓ Fields extracted — switch to Manual Entry to review and save
                  </p>
                )}
              </div>
            ) : (
              <div className="space-y-4">
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Job Title <span style={{ color: C.danger }}>*</span></label>
                  <input type="text" placeholder="e.g. Senior Backend Engineer" value={roleForm.title}
                    onChange={e => setRoleForm(f => ({ ...f, title: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: roleForm.title ? C.border : `${C.danger}88`, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Department</label>
                    <input type="text" placeholder="e.g. Engineering" value={roleForm.department}
                      onChange={e => setRoleForm(f => ({ ...f, department: e.target.value }))}
                      className="w-full px-3 py-2 rounded-xl border text-sm"
                      style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                  </div>
                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Location</label>
                    <input type="text" placeholder="e.g. Kuala Lumpur / Remote" value={roleForm.location}
                      onChange={e => setRoleForm(f => ({ ...f, location: e.target.value }))}
                      className="w-full px-3 py-2 rounded-xl border text-sm"
                      style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-2" style={{ color: C.muted }}>Experience Level</label>
                  <div className="flex gap-2 flex-wrap">
                    {[["JUNIOR","Junior"],["MID","Mid-Level"],["SENIOR","Senior"],["LEAD","Lead / Principal"]].map(([v,l]) => (
                      <button key={v} onClick={() => setRoleForm(f => ({ ...f, experienceLevel: v }))}
                        className="px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors"
                        style={{ borderColor: roleForm.experienceLevel === v ? C.accent : C.border, backgroundColor: roleForm.experienceLevel === v ? `${C.accent}15` : "transparent", color: roleForm.experienceLevel === v ? C.accent : C.muted }}>{l}</button>
                    ))}
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Required Skills <span style={{ color: C.border }}>(comma-separated)</span></label>
                  <input type="text" placeholder="e.g. Java, Spring Boot, PostgreSQL, AWS" value={roleForm.requiredSkills}
                    onChange={e => setRoleForm(f => ({ ...f, requiredSkills: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Interview Rounds Required</label>
                  <div className="flex gap-2">
                    {[1,2,3,4].map(n => (
                      <button key={n} onClick={() => setRoleForm(f => ({ ...f, requiredInterviewRounds: n }))}
                        className="px-4 py-1.5 rounded-xl border text-sm font-semibold transition-colors"
                        style={{ borderColor: roleForm.requiredInterviewRounds === n ? C.accent : C.border, backgroundColor: roleForm.requiredInterviewRounds === n ? `${C.accent}15` : "transparent", color: roleForm.requiredInterviewRounds === n ? C.accent : C.muted }}>
                        {n}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="flex items-center justify-between mb-1.5">
                    <label className="block text-xs font-semibold" style={{ color: C.muted }}>Job Description <span style={{ color: C.border }}>(optional)</span></label>
                    <div className="flex items-center gap-3">
                      <button type="button" onClick={handleGenerateJd}
                        disabled={generatingJd || !roleForm.title.trim()}
                        className="flex items-center gap-1 text-xs font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
                        style={{ color: C.accent }}
                        title={!roleForm.title.trim() ? "Enter a job title first" : "Generate with AI"}>
                        {generatingJd ? <Spinner size={12} /> : <Sparkles size={12} />}
                        {generatingJd ? "Generating…" : "Generate with AI"}
                      </button>
                      {roleForm.description && (
                        <button type="button" onClick={() => setJdDialogOpen(true)}
                          className="flex items-center gap-1 text-xs font-semibold" style={{ color: C.muted }}
                          title="Open full view">
                          <Maximize2 size={12} /> Expand
                        </button>
                      )}
                    </div>
                  </div>
                  <textarea rows={6} placeholder="Responsibilities, team context, what success looks like…" value={roleForm.description}
                    onChange={e => setRoleForm(f => ({ ...f, description: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                  {generateJdError && (
                    <p className="text-xs mt-1" style={{ color: C.danger }}>{generateJdError}</p>
                  )}
                </div>
                {jdDialogOpen && (
                  <JobDescriptionDialog
                    value={roleForm.description}
                    onChange={v => setRoleForm(f => ({ ...f, description: v }))}
                    onClose={() => setJdDialogOpen(false)}
                  />
                )}
                <div className="flex gap-2 pt-1">
                  <Btn variant="primary" onClick={handleCreateRole} disabled={savingRole || !roleForm.title.trim()}>
                    {savingRole ? <><Spinner size={13} /> Creating…</> : "Create Role"}
                  </Btn>
                  <Btn variant="secondary" onClick={() => { setNewRoleModal(false); setParseMode(false); setJdText(""); }}>Cancel</Btn>
                </div>
              </div>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// PIPELINE (ORCHESTRATOR)
// ============================================================
// WEBHOOK URL CARD
// ============================================================
function WebhookUrlCard({ requisitionId }) {
  const webhookUrl = `${window.location.protocol}//${window.location.hostname}:8080/api/inbound/applications/${requisitionId}`;
  const [copied, setCopied] = useState(false);

  function copyUrl() {
    navigator.clipboard.writeText(webhookUrl).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  return (
    <Card className="p-5 space-y-3">
      <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Inbound Webhook URL</p>
      <p className="text-xs leading-relaxed" style={{ color: C.muted }}>
        Paste this URL into any external job board to forward applications directly into TalentAI.
      </p>
      <div className="rounded-lg p-3 text-xs font-mono break-all" style={{ backgroundColor: `${C.accent}10`, color: C.accent }}>
        {webhookUrl}
      </div>
      <button
        onClick={copyUrl}
        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold border transition-colors"
        style={{ borderColor: copied ? C.success : C.accent, color: copied ? C.success : C.accent, backgroundColor: copied ? `${C.success}10` : `${C.accent}10` }}
      >
        {copied ? "✓ Copied!" : "Copy URL"}
      </button>
    </Card>
  );
}

// ============================================================
// AGENT ACTIVITY PANEL — agentic orchestrator runs, traces, and approvals
// ============================================================
function AgentActivityPanel({ requisitionId }) {
  const { data: runs, loading, reload } = useApi(`/agent/requisition/${requisitionId}/runs`, [requisitionId]);
  const [openRun, setOpenRun] = useState(null);
  const [steps, setSteps] = useState({});
  const [acting, setActing] = useState(null);

  const RUN_STYLE = {
    RUNNING:             { bg: "#dbeafe", color: "#1d4ed8", label: "Running" },
    PAUSED_FOR_APPROVAL: { bg: "#fef9c3", color: "#ca8a04", label: "Awaiting Approval" },
    COMPLETED:           { bg: "#dcfce7", color: "#16a34a", label: "Completed" },
    FAILED:              { bg: "#fee2e2", color: "#dc2626", label: "Failed" },
    CANCELLED:           { bg: "#f1f5f9", color: "#64748b", label: "Cancelled" },
  };

  const STEP_ICON = {
    THOUGHT: "💭", TOOL_CALL: "🔧", TOOL_RESULT: "📄",
    APPROVAL_REQUEST: "✋", APPROVAL_DECISION: "👤", FINAL: "✅", ERROR: "⚠️",
  };

  async function toggleRun(runId) {
    if (openRun === runId) { setOpenRun(null); return; }
    setOpenRun(runId);
    if (!steps[runId]) {
      const s = await apiFetch(`/agent/runs/${runId}/steps`);
      setSteps(prev => ({ ...prev, [runId]: s }));
    }
  }

  async function decide(runId, action) {
    setActing(runId);
    try {
      await apiFetch(`/agent/runs/${runId}/${action}`, { method: "POST" });
      setSteps(prev => ({ ...prev, [runId]: undefined }));
      setOpenRun(null);
      reload();
    } finally { setActing(null); }
  }

  const runList = runs || [];
  if (!loading && runList.length === 0) return null;

  return (
    <Card className="p-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Agent Activity</p>
          <p className="text-xs mt-0.5" style={{ color: C.muted }}>
            AI orchestrator runs for inbound applications · {runList.length} run{runList.length !== 1 ? "s" : ""}
          </p>
        </div>
        <button onClick={reload} className="p-1.5 rounded-lg hover:bg-gray-100 text-xs font-semibold" style={{ color: C.accent }} title="Refresh">
          ↻ Refresh
        </button>
      </div>

      <div className="space-y-3">
        {runList.map(run => {
          const st = RUN_STYLE[run.status] || RUN_STYLE.CANCELLED;
          return (
            <div key={run.id} className="border rounded-xl overflow-hidden" style={{ borderColor: C.border }}>
              <button onClick={() => toggleRun(run.id)}
                className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-gray-50">
                <div className="flex items-center gap-3 min-w-0">
                  <span className="text-xs font-bold px-2.5 py-1 rounded-full shrink-0" style={{ backgroundColor: st.bg, color: st.color }}>
                    {st.label}
                  </span>
                  <span className="text-xs truncate" style={{ color: C.text }}>{run.goal}</span>
                </div>
                <span className="text-xs shrink-0 ml-2" style={{ color: C.muted }}>
                  {openRun === run.id ? "▲" : "▼"}
                </span>
              </button>

              {run.status === "PAUSED_FOR_APPROVAL" && (() => {
                let pendingInput = null;
                try { pendingInput = run.pendingInput ? JSON.parse(run.pendingInput) : null; } catch {}
                const emailBody = run.pendingTool === "send_outreach_email" && pendingInput?.input?.emailBody;
                const rejectReason = run.pendingTool === "reject_candidate" && pendingInput?.input?.reason;
                return (
                  <div className="border-t" style={{ borderColor: C.border, backgroundColor: "#fefce8" }}>
                    <div className="px-4 py-3 flex items-center justify-between gap-3">
                      <p className="text-xs font-medium" style={{ color: "#854d0e" }}>
                        Agent wants to run <span className="font-mono font-bold">{run.pendingTool}</span> — your approval is required.
                      </p>
                      <div className="flex gap-2 shrink-0">
                        <button disabled={acting === run.id} onClick={() => decide(run.id, "approve")}
                          className="px-3 py-1.5 rounded-lg text-xs font-bold text-white"
                          style={{ backgroundColor: "#16a34a", opacity: acting === run.id ? 0.5 : 1 }}>
                          ✓ Approve
                        </button>
                        <button disabled={acting === run.id} onClick={() => decide(run.id, "reject")}
                          className="px-3 py-1.5 rounded-lg text-xs font-bold text-white"
                          style={{ backgroundColor: "#dc2626", opacity: acting === run.id ? 0.5 : 1 }}>
                          ✕ Decline
                        </button>
                      </div>
                    </div>
                    {emailBody && (
                      <div className="px-4 pb-3">
                        <p className="text-xs font-semibold mb-1" style={{ color: "#854d0e" }}>Email to be sent:</p>
                        <pre className="text-xs whitespace-pre-wrap rounded-lg p-3 leading-relaxed"
                          style={{ backgroundColor: "#fef3c7", color: "#78350f", fontFamily: "inherit" }}>
                          {emailBody}
                        </pre>
                      </div>
                    )}
                    {rejectReason && (
                      <div className="px-4 pb-3">
                        <p className="text-xs font-semibold mb-1" style={{ color: "#854d0e" }}>Rejection reason:</p>
                        <p className="text-xs rounded-lg p-3" style={{ backgroundColor: "#fef3c7", color: "#78350f" }}>
                          {rejectReason}
                        </p>
                      </div>
                    )}
                  </div>
                );
              })()}

              {openRun === run.id && (
                <div className="border-t px-4 py-3 space-y-2" style={{ borderColor: C.border, backgroundColor: "#fafafa" }}>
                  {!steps[run.id] ? (
                    <p className="text-xs" style={{ color: C.muted }}>Loading trace…</p>
                  ) : steps[run.id].map(step => (
                    <div key={step.id} className="flex gap-2 text-xs">
                      <span className="shrink-0">{STEP_ICON[step.stepType] || "•"}</span>
                      <div className="min-w-0">
                        <span className="font-bold" style={{ color: C.text }}>
                          {step.stepType}{step.toolName ? ` · ${step.toolName}` : ""}
                        </span>
                        <pre className="whitespace-pre-wrap font-sans mt-0.5 max-h-40 overflow-y-auto" style={{ color: C.muted }}>
                          {step.content}
                        </pre>
                      </div>
                    </div>
                  ))}
                  {run.resultSummary && (
                    <div className="mt-2 pt-2 border-t text-xs" style={{ borderColor: C.border }}>
                      <span className="font-bold" style={{ color: C.text }}>Final summary: </span>
                      <span style={{ color: C.muted }}>{run.resultSummary}</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </Card>
  );
}

// ============================================================
// ============================================================
// APPLICANTS PANEL — direct applications from public careers page
// ============================================================
function ApplicantsPanel({ requisitionId }) {
  const { data, loading, reload } = useApi(`/requisitions/${requisitionId}/applicants`, [requisitionId]);
  const [acting, setActing] = useState(null);
  const [expanded, setExpanded] = useState({});
  const [outreach, setOutreach] = useState(null);

  // Candidate id currently being email-edited, and that edit's local state
  const [editingEmailFor, setEditingEmailFor] = useState(null);
  const [emailDraft, setEmailDraft] = useState("");
  const [savingEmail, setSavingEmail] = useState(false);
  const [emailError, setEmailError] = useState(null);

  const applicants = data || [];

  function startEditEmail(a) {
    setEditingEmailFor(a.candidateId);
    setEmailDraft(a.candidateEmail || "");
    setEmailError(null);
  }

  async function saveEmail(candidateId) {
    setSavingEmail(true);
    setEmailError(null);
    try {
      await apiFetch(`/candidates/${candidateId}`, {
        method: "PATCH",
        body: JSON.stringify({ email: emailDraft }),
      });
      setEditingEmailFor(null);
      reload();
    } catch (e) {
      setEmailError(e.message);
    } finally {
      setSavingEmail(false);
    }
  }

  async function shortlist(matchId) {
    setActing(matchId);
    try {
      await apiFetch(`/requisitions/${requisitionId}/applicants/${matchId}/shortlist`, { method: "POST" });
      reload();
    } finally { setActing(null); }
  }

  async function reject(matchId) {
    setActing(matchId);
    try {
      await apiFetch(`/requisitions/${requisitionId}/applicants/${matchId}/reject`, { method: "POST" });
      reload();
    } finally { setActing(null); }
  }

  async function draftOutreach(a) {
    setOutreach({ loading: true, matchId: a.id, candidateName: a.candidateName, requisitionTitle: null, candidateEmail: a.candidateEmail });
    try {
      const result = await apiFetch(`/sourcing/matches/${a.id}/draft-outreach`, { method: "POST" });
      setOutreach({ loading: false, ...result });
    } catch { setOutreach(null); }
  }

  const REC_STYLE = {
    ADVANCE:  { bg: "#dcfce7", color: "#16a34a", label: "Advance" },
    REJECT:   { bg: "#fee2e2", color: "#dc2626", label: "Reject" },
    REVIEW:   { bg: "#fef9c3", color: "#ca8a04", label: "Review" },
  };

  const STATUS_BADGE = {
    APPLIED:     { bg: "#dbeafe", color: "#1d4ed8", label: "Pending Review" },
    SHORTLISTED: { bg: "#dcfce7", color: "#16a34a", label: "Shortlisted" },
    REJECTED:    { bg: "#fee2e2", color: "#dc2626", label: "Rejected" },
  };

  return (
    <Card className="p-6">
      <div className="flex items-center justify-between mb-4">
        <div>
          <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Direct Applicants</p>
          <p className="text-xs mt-0.5" style={{ color: C.muted }}>
            From public careers page · {applicants.length} application{applicants.length !== 1 ? "s" : ""}
          </p>
        </div>
        <button onClick={reload} className="p-1.5 rounded-lg hover:bg-gray-100" title="Refresh">
          <RefreshCw size={14} style={{ color: C.muted }} />
        </button>
      </div>

      {loading && <p className="text-sm py-4 text-center" style={{ color: C.muted }}>Loading…</p>}

      {!loading && applicants.length === 0 && (
        <div className="text-center py-8 space-y-2">
          <UserPlus size={28} style={{ color: C.muted, margin: "0 auto" }} />
          <p className="text-sm font-medium" style={{ color: C.muted }}>No applications yet</p>
          <p className="text-xs" style={{ color: C.muted }}>
            Share <span className="font-mono">/careers</span> with candidates to start receiving applications
          </p>
        </div>
      )}

      {!loading && applicants.length > 0 && (
        <div className="space-y-3">
          {applicants.map(a => {
            const st = STATUS_BADGE[a.status] || STATUS_BADGE.APPLIED;
            const rec = a.screeningRecommendation ? REC_STYLE[a.screeningRecommendation] : null;
            const isPending = a.status === "APPLIED";
            return (
              <div key={a.id} className="rounded-xl border p-4 space-y-3"
                style={{ borderColor: C.border, backgroundColor: isPending ? "#fafafa" : "transparent" }}>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-semibold" style={{ color: C.text }}>{a.candidateName}</span>
                      <span className="text-xs px-2 py-0.5 rounded-full font-medium"
                        style={{ backgroundColor: st.bg, color: st.color }}>{st.label}</span>
                    </div>
                    {a.candidateHeadline && (
                      <p className="text-xs mt-0.5 truncate" style={{ color: C.muted }}>{a.candidateHeadline}</p>
                    )}
                    {editingEmailFor === a.candidateId ? (
                      <div className="mt-1 flex items-center gap-1.5">
                        <input type="email" value={emailDraft} autoFocus
                          onChange={e => setEmailDraft(e.target.value)}
                          onKeyDown={e => { if (e.key === "Enter") saveEmail(a.candidateId); if (e.key === "Escape") setEditingEmailFor(null); }}
                          className="text-xs px-2 py-1 rounded-lg border flex-1 min-w-0"
                          style={{ borderColor: C.border }} />
                        <button disabled={savingEmail} onClick={() => saveEmail(a.candidateId)}
                          className="text-xs font-semibold px-2 py-1 rounded-lg text-white"
                          style={{ backgroundColor: C.success, opacity: savingEmail ? 0.5 : 1 }}>
                          {savingEmail ? "…" : "Save"}
                        </button>
                        <button disabled={savingEmail} onClick={() => setEditingEmailFor(null)}
                          className="text-xs font-semibold px-2 py-1 rounded-lg border" style={{ borderColor: C.border, color: C.muted }}>
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <div className="flex items-center gap-1.5 mt-0.5 group">
                        <p className="text-xs truncate" style={{ color: C.muted }}>{a.candidateEmail || "No email on record"}</p>
                        <button onClick={() => startEditEmail(a)}
                          className="text-xs opacity-0 group-hover:opacity-100 transition-opacity" style={{ color: C.accent }}>
                          Edit
                        </button>
                      </div>
                    )}
                    {editingEmailFor === a.candidateId && emailError && (
                      <p className="text-xs mt-1" style={{ color: C.danger }}>{emailError}</p>
                    )}
                    <p className="text-xs mt-1" style={{ color: C.muted }}>
                      Applied {a.createdAt ? new Date(a.createdAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "—"}
                    </p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    {a.matchScore != null && (
                      <div className="text-lg font-bold" style={{ color: C.accent }}>{Math.round(a.matchScore)}</div>
                    )}
                    {a.matchScore != null && <div className="text-xs" style={{ color: C.muted }}>match</div>}
                  </div>
                </div>

                {rec && (
                  <div>
                    <button
                      onClick={() => setExpanded(e => ({ ...e, [a.id]: !e[a.id] }))}
                      className="w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg text-xs font-medium"
                      style={{ backgroundColor: rec.bg, color: rec.color }}>
                      <span className="flex items-center gap-2">
                        <Sparkles size={12} />
                        AI Screening: <strong>{rec.label}</strong>
                        {a.screeningScore != null && <span className="opacity-70">({Math.round(a.screeningScore)}/100)</span>}
                      </span>
                      <span style={{ fontSize: "10px" }}>{expanded[a.id] ? "▲ Hide" : "▼ Report"}</span>
                    </button>

                    {expanded[a.id] && (
                      <div className="mt-2 rounded-lg border p-4 space-y-3 text-xs" style={{ borderColor: C.border }}>
                        {/* Score breakdown */}
                        {(a.screeningSkillsScore != null || a.screeningExperienceScore != null || a.screeningCultureFitScore != null) && (
                          <div className="grid grid-cols-3 gap-2">
                            {[
                              { label: "Skills", val: a.screeningSkillsScore },
                              { label: "Experience", val: a.screeningExperienceScore },
                              { label: "Culture Fit", val: a.screeningCultureFitScore },
                            ].map(({ label, val }) => val != null && (
                              <div key={label} className="text-center rounded-lg p-2" style={{ backgroundColor: `${C.accent}08` }}>
                                <div className="text-lg font-bold" style={{ color: C.accent }}>{Math.round(val)}</div>
                                <div style={{ color: C.muted }}>{label}</div>
                              </div>
                            ))}
                          </div>
                        )}
                        {/* Strengths */}
                        {a.screeningStrengths && (
                          <div>
                            <p className="font-semibold mb-1" style={{ color: "#16a34a" }}>✓ Strengths</p>
                            <p style={{ color: C.text, lineHeight: "1.5" }}>{a.screeningStrengths}</p>
                          </div>
                        )}
                        {/* Gaps */}
                        {a.screeningGaps && (
                          <div>
                            <p className="font-semibold mb-1" style={{ color: "#dc2626" }}>✗ Gaps</p>
                            <p style={{ color: C.text, lineHeight: "1.5" }}>{a.screeningGaps}</p>
                          </div>
                        )}
                        {/* Rationale */}
                        {a.screeningRationale && (
                          <div>
                            <p className="font-semibold mb-1" style={{ color: C.muted }}>Recommendation Rationale</p>
                            <p style={{ color: C.text, lineHeight: "1.5" }}>{a.screeningRationale}</p>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}

                {isPending && (
                  <div className="flex gap-2 pt-1">
                    <button
                      disabled={acting === a.id}
                      onClick={() => shortlist(a.id)}
                      className="flex-1 py-2 rounded-lg text-xs font-semibold text-white transition-opacity"
                      style={{ backgroundColor: C.success, opacity: acting === a.id ? 0.5 : 1 }}>
                      ✓ Shortlist
                    </button>
                    <button
                      disabled={acting === a.id}
                      onClick={() => reject(a.id)}
                      className="flex-1 py-2 rounded-lg text-xs font-semibold border transition-opacity"
                      style={{ borderColor: "#fca5a5", color: "#dc2626", opacity: acting === a.id ? 0.5 : 1 }}>
                      ✕ Reject
                    </button>
                  </div>
                )}

                {a.status === "SHORTLISTED" && (
                  <div className="flex items-center justify-between">
                    <p className="text-xs" style={{ color: C.success }}>✓ Added to pipeline at Screening stage</p>
                    <button
                      onClick={() => draftOutreach(a)}
                      className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1"
                      style={{ color: C.accent, border: `1px solid ${C.accent}55`, backgroundColor: `${C.accent}08` }}>
                      <Mail size={11} /> Send Outreach
                    </button>
                  </div>
                )}
                {a.status === "REJECTED" && (
                  <p className="text-xs" style={{ color: "#dc2626" }}>✕ Rejected — email notification sent</p>
                )}
              </div>
            );
          })}
        </div>
      )}
      <OutreachModal outreach={outreach} onClose={() => setOutreach(null)} onSent={reload} />
    </Card>
  );
}

// Surfaces candidate notification emails that failed to send (the triggering
// pipeline/status change already happened and is never rolled back) so a
// recruiter can notice and resend rather than the failure disappearing silently.
function FailedNotificationsBanner() {
  const { data, loading, reload } = useApi("/notifications/failed");
  const [resendingId, setResendingId] = useState(null);
  const [expanded, setExpanded] = useState(false);
  const failures = data || [];

  async function resend(id) {
    setResendingId(id);
    try {
      await apiFetch(`/notifications/${id}/resend`, { method: "POST" });
      // Give the async send a moment before refreshing the list
      setTimeout(reload, 2000);
    } finally {
      setResendingId(null);
    }
  }

  if (loading || failures.length === 0) return null;

  return (
    <Card className="p-4 mb-4" style={{ borderColor: "#FCA5A5", background: "#FEF2F2" }}>
      <div className="flex items-center justify-between">
        <button onClick={() => setExpanded(e => !e)} className="flex items-center gap-2 text-left">
          <AlertCircle size={16} style={{ color: C.danger }} />
          <span className="text-sm font-semibold" style={{ color: C.danger }}>
            {failures.length} notification email{failures.length !== 1 ? "s" : ""} failed to send
          </span>
          <span className="text-xs" style={{ color: C.danger }}>{expanded ? "▲ Hide" : "▼ Show"}</span>
        </button>
        <button onClick={reload} className="p-1.5 rounded-lg hover:bg-white/50" title="Refresh">
          <RefreshCw size={13} style={{ color: C.danger }} />
        </button>
      </div>
      {expanded && (
        <div className="mt-3 space-y-2">
          {failures.map(f => (
            <div key={f.id} className="flex items-center justify-between gap-3 p-2.5 rounded-lg bg-white border" style={{ borderColor: "#FECACA" }}>
              <div className="min-w-0">
                <p className="text-xs font-semibold truncate" style={{ color: C.text }}>{f.recipientEmail}</p>
                <p className="text-xs truncate" style={{ color: C.muted }}>{f.subject}</p>
                <p className="text-[10px] mt-0.5 truncate" style={{ color: C.danger }}>{f.errorMessage}</p>
              </div>
              <button disabled={resendingId === f.id} onClick={() => resend(f.id)}
                className="text-xs font-semibold px-3 py-1.5 rounded-lg text-white shrink-0"
                style={{ backgroundColor: C.danger, opacity: resendingId === f.id ? 0.5 : 1 }}>
                {resendingId === f.id ? "Resending…" : "Resend"}
              </button>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

// Sortable, filterable table view of the pipeline — same underlying data as
// the Kanban board, flattened into rows. Complements the board rather than
// replacing it: bulk scanning/sorting/filtering is easier here (including
// surfacing REJECTED/NO_SHOW candidates the board hides by default), while
// multi-round interview scheduling and offer creation stay board-only since
// they need more room than a table row can give them.
function PipelineListView({
  board, isAllRoles, stageFilter, setStageFilter, sortBy, setSortBy, sortDir, setSortDir,
  movingId, handleMove, setScheduleModal, setScheduleForm, setOfferModal, onDraftOutreach, onViewHistory, onReject,
}) {
  const allCandidates = Object.values(board.stages || {}).flat();
  const filtered = stageFilter === "ALL" ? allCandidates : allCandidates.filter(c => c.stage === stageFilter);

  const sorted = [...filtered].sort((a, b) => {
    let av, bv;
    if (sortBy === "name") { av = (a.candidateName || "").toLowerCase(); bv = (b.candidateName || "").toLowerCase(); }
    else if (sortBy === "score") { av = a.screeningScore ?? a.sourcingMatchScore ?? -1; bv = b.screeningScore ?? b.sourcingMatchScore ?? -1; }
    else if (sortBy === "stage") { av = PIPELINE_STAGES.indexOf(a.stage); bv = PIPELINE_STAGES.indexOf(b.stage); }
    else { av = a.enteredAt || ""; bv = b.enteredAt || ""; }
    if (av < bv) return sortDir === "asc" ? -1 : 1;
    if (av > bv) return sortDir === "asc" ? 1 : -1;
    return 0;
  });

  function toggleSort(col) {
    if (sortBy === col) setSortDir(d => d === "asc" ? "desc" : "asc");
    else { setSortBy(col); setSortDir(col === "name" || col === "stage" ? "asc" : "desc"); }
  }

  function SortHeader({ col, children }) {
    const active = sortBy === col;
    return (
      <th className="text-left px-4 py-3 font-semibold cursor-pointer select-none" style={{ color: C.muted }}
        onClick={() => toggleSort(col)}>
        <span className="inline-flex items-center gap-1">
          {children}
          {active ? (sortDir === "asc" ? <ArrowUp size={11} /> : <ArrowDown size={11} />) : <ArrowUpDown size={11} style={{ opacity: 0.35 }} />}
        </span>
      </th>
    );
  }

  return (
    <div>
      <div className="mb-3 flex items-center gap-2">
        <label className="text-xs font-semibold" style={{ color: C.muted }}>Stage</label>
        <Select value={stageFilter} onChange={e => setStageFilter(e.target.value)}>
          <option value="ALL">All Stages ({allCandidates.length})</option>
          {PIPELINE_STAGES.map(s => {
            const n = allCandidates.filter(c => c.stage === s).length;
            return <option key={s} value={s}>{STAGE_LABELS[s]} ({n})</option>;
          })}
        </Select>
      </div>

      <Card className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ backgroundColor: C.bg }}>
              <SortHeader col="name">Candidate</SortHeader>
              {isAllRoles && <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Role</th>}
              <SortHeader col="stage">Stage</SortHeader>
              <SortHeader col="score">Score</SortHeader>
              <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Updated By</th>
              <SortHeader col="enteredAt">Entered</SortHeader>
              <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}></th>
            </tr>
          </thead>
          <tbody className="divide-y" style={{ borderColor: C.border }}>
            {sorted.map(c => {
              const isMoving = movingId === c.candidateId;
              const actions = STAGE_ACTIONS[c.stage] || [];
              const score = c.screeningScore ?? c.sourcingMatchScore;
              return (
                <tr key={`${c.candidateId}-${c.requisitionId}`} className="hover:bg-slate-50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2.5">
                      <Avatar name={c.candidateName} size={30} />
                      <span className="font-semibold" style={{ color: C.text }}>{c.candidateName}</span>
                    </div>
                  </td>
                  {isAllRoles && (
                    <td className="px-4 py-3 text-xs" style={{ color: C.muted }}>{c.requisitionTitle}</td>
                  )}
                  <td className="px-4 py-3">
                    <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                      style={{ backgroundColor: `${C.accent}15`, color: C.accent }}>
                      {STAGE_LABELS[c.stage] || c.stage}
                    </span>
                  </td>
                  <td className="px-4 py-3">{score != null && <ScoreRing score={score} size={30} />}</td>
                  <td className="px-4 py-3 text-xs font-semibold"
                    style={{ color: AGENT_META[c.updatedByAgent]?.color || C.muted }}>
                    {actorLabel(c.updatedByAgent) || "—"}
                  </td>
                  <td className="px-4 py-3 text-xs" style={{ color: C.muted }}>
                    {c.enteredAt ? new Date(c.enteredAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      {c.stage === "INTERVIEW_SCHEDULED" || c.stage === "OFFER" ? (
                        <span className="text-xs" style={{ color: C.muted }}>Manage in Board view</span>
                      ) : (
                        <>
                          {actions.map(({ label, next }) => (
                            <button key={next} disabled={isMoving}
                              onClick={() => next === "INTERVIEW_SCHEDULED"
                                ? setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId, nextType: "PHONE_SCREEN" })
                                : handleMove(c.candidateId, next, c.requisitionId)
                              }
                              className="text-xs font-semibold px-2.5 py-1 rounded-lg text-white"
                              style={{ backgroundColor: C.accent, opacity: isMoving ? 0.5 : 1 }}>
                              {label}
                            </button>
                          ))}
                          {c.stage === "HIRED" && (
                            <button disabled={isMoving}
                              onClick={() => handleMove(c.candidateId, "NO_SHOW", c.requisitionId)}
                              className="text-xs font-semibold px-2.5 py-1 rounded-lg"
                              style={{ color: C.warning, border: `1px solid ${C.warning}55`, opacity: isMoving ? 0.5 : 1 }}>
                              Mark as No-Show
                            </button>
                          )}
                          {c.stage !== "REJECTED" && c.stage !== "HIRED" && c.stage !== "NO_SHOW" && (
                            <button disabled={isMoving}
                              onClick={() => onReject(c)}
                              className="text-xs font-semibold px-2.5 py-1 rounded-lg"
                              style={{ color: C.danger, border: `1px solid ${C.danger}33`, opacity: isMoving ? 0.5 : 1 }}>
                              Reject
                            </button>
                          )}
                          {c.stage === "SHORTLISTED" && onDraftOutreach && (
                            <button onClick={() => onDraftOutreach(c)}
                              className="text-xs font-semibold px-2.5 py-1 rounded-lg flex items-center gap-1"
                              style={{ color: C.accent, border: `1px solid ${C.accent}55` }}>
                              <Mail size={11} /> Send Outreach
                            </button>
                          )}
                        </>
                      )}
                      {onViewHistory && (
                        <button onClick={() => onViewHistory(c.candidateId, c.candidateName)}
                          className="w-6 h-6 rounded-lg flex items-center justify-center hover:bg-slate-100 transition-colors"
                          title="View application history">
                          <History size={12} style={{ color: C.muted }} />
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {sorted.length === 0 && (
          <p className="text-sm text-center py-10" style={{ color: C.muted }}>No candidates in this stage.</p>
        )}
      </Card>
    </div>
  );
}

function PipelineView() {
  const { data: reqs } = useApi("/requisitions");
  const [reqId, setReqId] = useState("ALL"); // "ALL" or a numeric requisition id
  const [viewMode, setViewMode] = useState("board"); // "board" | "list"
  const [stageFilter, setStageFilter] = useState("ALL");
  const { data: interviewers } = useApi("/calendar/interviewers");
  const [showClosed, setShowClosed] = useState(false); // show Rejected / No-Show columns on the board
  const [rejectModal, setRejectModal] = useState(null);  // { candidateId, requisitionId, candidateName }
  const [rejectForm, setRejectForm] = useState({ preset: "", note: "" });
  const { data: scorecardCriteria } = useApi("/orchestrator/scorecard-criteria");
  const emptyScorecard = { ratings: {}, verdict: "", notes: "", noticePeriod: "", expectedSalary: "" };
  const [scorecardModal, setScorecardModal] = useState(null); // { interviewId, title }
  const [debriefModal, setDebriefModal] = useState(null); // { candidateId, candidateName, requisitionId }
  const [scorecardForm, setScorecardForm] = useState(emptyScorecard);
  const [scorecardSaving, setScorecardSaving] = useState(false);
  const [scorecardError, setScorecardError] = useState(null);
  const openScorecard = (c, r) => {
    setScorecardForm(emptyScorecard); setScorecardError(null);
    setScorecardModal({ interviewId: r.id, interviewType: r.interviewType, title: `${ROUND_LABELS[r.interviewType] || r.interviewType} — ${c.candidateName}` });
  };
  async function submitScorecard() {
    setScorecardSaving(true); setScorecardError(null);
    try {
      await apiFetch(`/orchestrator/interviews/${scorecardModal.interviewId}/scorecard`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(scorecardForm),
      });
      setScorecardModal(null);
      reload();
    } catch (e) { setScorecardError(e.message); }
    finally { setScorecardSaving(false); }
  }
  const requestReject = c => { setRejectForm({ preset: "", note: "" }); setRejectModal({ candidateId: c.candidateId, requisitionId: c.requisitionId, candidateName: c.candidateName }); };
  async function confirmReject() {
    const reason = joinRejectReason(rejectForm);
    const m = rejectModal;
    setRejectModal(null);
    await handleMove(m.candidateId, "REJECTED", m.requisitionId, reason);
  }
  const [sortBy, setSortBy] = useState("enteredAt"); // "name" | "score" | "stage" | "enteredAt"
  const [sortDir, setSortDir] = useState("desc");
  const [movingId, setMovingId] = useState(null);
  const [completingRoundId, setCompletingRoundId] = useState(null);
  const [simulatingResponseId, setSimulatingResponseId] = useState(null);
  const [emailModal, setEmailModal] = useState(null); // { title, body, candidateEmail, interviewId? }
  const [emailBodyCopied, setEmailBodyCopied] = useState(false);
  const [emailAddrCopied, setEmailAddrCopied] = useState(false);
  const [emailEditing, setEmailEditing] = useState(false);
  const [emailEditText, setEmailEditText] = useState("");
  const [emailSaving, setEmailSaving] = useState(false);
  const [emailSaved, setEmailSaved] = useState(false);
  const [emailSaveError, setEmailSaveError] = useState(null);
  const [sendingInvite, setSendingInvite] = useState(false);
  const [inviteSent, setInviteSent] = useState(false);
  const [reschedulingId, setReschedulingId] = useState(null);
  const [scheduleModal, setScheduleModal] = useState(null);
  const [scheduleForm, setScheduleForm] = useState({ interviewType: "PHONE_SCREEN", interviewerId: "", notes: "" });
  const [scheduling, setScheduling] = useState(false);
  const [rescheduleModal, setRescheduleModal] = useState(null); // { interviewId, roundNumber, interviewType }
  const [rescheduleSlot, setRescheduleSlot] = useState("");
  const [rescheduling, setRescheduling] = useState(false);
  const [recordResponseModal, setRecordResponseModal] = useState(null); // { interviewId, roundNumber }
  const [recordReplyText, setRecordReplyText] = useState("");
  const [recordingResponse, setRecordingResponse] = useState(false);
  const [offerModal, setOfferModal] = useState(null);
  const [outreach, setOutreach] = useState(null);
  const [offerForm, setOfferForm] = useState({ salaryAmount: "", currency: "MYR", startDate: "", expiryDate: "", notes: "" });
  const [submittingOffer, setSubmittingOffer] = useState(false);
  const [offerError, setOfferError] = useState(null);
  useEffect(() => { setOfferError(null); }, [offerModal]);
  const [updatingOfferId, setUpdatingOfferId] = useState(null);
  const [negotiateForm, setNegotiateForm] = useState({}); // offerId -> { salaryAmount, startDate, notes, changedBy }
  const [savingTerms, setSavingTerms] = useState(null);
  const [letterModal, setLetterModal] = useState(null); // null | { offerId, candidateName, candidateEmail, letterText }
  const [letterEditing, setLetterEditing] = useState(false);
  const [letterEditText, setLetterEditText] = useState("");
  const [generatingLetter, setGeneratingLetter] = useState(null); // offerId being generated
  const [sendingOfferEmail, setSendingOfferEmail] = useState(false);
  const [offerEmailSent, setOfferEmailSent] = useState(false);
  const [copied, setCopied] = useState(false);
  const [newRoleModal, setNewRoleModal] = useState(false);
  const [roleForm, setRoleForm] = useState({ title: "", department: "", location: "", experienceLevel: "MID", description: "", requiredSkills: "", requiredInterviewRounds: 2 });
  const [savingRole, setSavingRole] = useState(false);
  const [historyModal, setHistoryModal] = useState(null);  // { candidateId, candidateName }
  const [historyData, setHistoryData] = useState(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [generatingJd, setGeneratingJd] = useState(false);
  const [generateJdError, setGenerateJdError] = useState(null);
  const [jdDialogOpen, setJdDialogOpen] = useState(false);

  async function handleGenerateJd() {
    if (!roleForm.title.trim()) return;
    setGeneratingJd(true);
    setGenerateJdError(null);
    try {
      const result = await apiFetch("/requisitions/generate-jd", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: roleForm.title,
          department: roleForm.department,
          location: roleForm.location,
          experienceLevel: roleForm.experienceLevel,
          requiredSkills: roleForm.requiredSkills,
        }),
      });
      setRoleForm(f => ({ ...f, description: result.description || "" }));
    } catch (e) {
      setGenerateJdError(e.message || "Could not generate a description.");
    } finally {
      setGeneratingJd(false);
    }
  }

  async function handleCreateRole() {
    if (!roleForm.title.trim()) return;
    setSavingRole(true);
    try {
      const created = await apiFetch("/requisitions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(roleForm),
      });
      setNewRoleModal(false);
      setRoleForm({ title: "", department: "", location: "", experienceLevel: "MID", description: "", requiredSkills: "", requiredInterviewRounds: 2 });
      // Switch pipeline to the newly created role
      if (created?.id) setReqId(created.id);
    } finally {
      setSavingRole(false);
    }
  }

  async function openHistory(candidateId, candidateName) {
    setHistoryModal({ candidateId, candidateName });
    setHistoryData(null);
    setHistoryLoading(true);
    try {
      const data = await apiFetch(`/candidates/${candidateId}/history`);
      setHistoryData(data);
    } finally {
      setHistoryLoading(false);
    }
  }

  const isAllRoles = reqId === "ALL";
  const singleBoardApi = useApi(!isAllRoles && reqId ? `/orchestrator/pipeline/${reqId}` : null, [reqId]);
  const allPaths = isAllRoles && reqs ? reqs.map(r => `/orchestrator/pipeline/${r.id}`) : null;
  const allBoardsApi = useApiMulti(allPaths, [isAllRoles, reqs?.length]);

  const board = isAllRoles ? (allBoardsApi.data ? mergePipelineBoards(allBoardsApi.data) : null) : singleBoardApi.data;
  const loading = isAllRoles ? allBoardsApi.loading : singleBoardApi.loading;
  const error = isAllRoles ? allBoardsApi.error : singleBoardApi.error;
  const reload = isAllRoles ? allBoardsApi.reload : singleBoardApi.reload;

  async function handleMove(candidateId, nextStage, candidateReqId, reason) {
    setMovingId(candidateId);
    try {
      await apiFetch(`/orchestrator/pipeline/${candidateReqId ?? reqId}/candidates/${candidateId}/stage`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stage: nextStage, ...(reason ? { reason } : {}) }),
      });
      reload();
    } finally {
      setMovingId(null);
    }
  }

  async function handleEmailReschedule(interviewId, hasBooking) {
    const ask = hasBooking
      ? "Free this time and email the candidate to choose a new one?"
      : "Resend the booking link to the candidate?";
    if (!window.confirm(ask)) return;
    setReschedulingId(interviewId);
    try {
      await apiFetch(`/calendar/interviews/${interviewId}/reschedule`, { method: "POST" });
      reload();
    } catch (e) {
      window.alert(e.message);
    } finally {
      setReschedulingId(null);
    }
  }

  async function handleSimulateResponse(interviewId) {
    setSimulatingResponseId(interviewId);
    try {
      await apiFetch(`/orchestrator/interviews/${interviewId}/simulate-response`, { method: "POST" });
      reload();
    } finally {
      setSimulatingResponseId(null);
    }
  }

  async function handleRecordResponse() {
    if (!recordReplyText.trim()) return;
    setRecordingResponse(true);
    try {
      await apiFetch(`/orchestrator/interviews/${recordResponseModal.interviewId}/record-response`, {
        method: "PATCH",
        body: JSON.stringify({ reply: recordReplyText }),
      });
      setRecordResponseModal(null);
      setRecordReplyText("");
      reload();
    } finally {
      setRecordingResponse(false);
    }
  }

  async function handleReschedule() {
    if (!rescheduleSlot) return;
    setRescheduling(true);
    try {
      const result = await apiFetch(`/orchestrator/interviews/${rescheduleModal.interviewId}/reschedule`, {
        method: "PATCH",
        body: JSON.stringify({ confirmedSlot: rescheduleSlot }),
      });
      setRescheduleModal(null);
      setRescheduleSlot("");
      // Show the new invitation email
      setEmailModal({ title: `Rescheduled — R${rescheduleModal.roundNumber} Invitation`, body: result.invitationEmail, candidateEmail: result.candidateEmail, interviewId: result.interviewId });
      setEmailEditing(false); setEmailEditText(result.invitationEmail); setEmailSaved(false);
      reload();
    } finally {
      setRescheduling(false);
    }
  }

  async function handleCreateOffer() {
    setSubmittingOffer(true);
    setOfferError(null);
    let created;
    try {
      created = await apiFetch(`/orchestrator/pipeline/${offerModal.requisitionId ?? reqId}/candidates/${offerModal.candidateId}/offer`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...offerForm, salaryAmount: offerForm.salaryAmount ? Number(offerForm.salaryAmount) : null }),
      });
    } catch (e) {
      setOfferError(e.message || "The offer could not be created.");
      setSubmittingOffer(false);
      return;
    }
    setOfferModal(null);
    setOfferForm({ salaryAmount: "", currency: "MYR", startDate: "", expiryDate: "", notes: "" });
    reload();
    setSubmittingOffer(false);
    // Go straight to reviewing the letter; nothing is sent until "Send to Candidate" is pressed there
    if (created?.id) {
      try { await handleGenerateLetter(created.id); }
      catch (e) { alert("The offer was saved, but the letter could not be opened: " + (e.message || "unknown error") + ". Use 'Review & send' on the card."); }
    }
  }

  async function handleOfferStatus(offerId, status) {
    setUpdatingOfferId(offerId);
    try {
      await apiFetch(`/orchestrator/offers/${offerId}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
      });
      reload();
    } finally {
      setUpdatingOfferId(null);
    }
  }

  async function handleUpdateTerms(offerId) {
    setSavingTerms(offerId);
    try {
      const form = negotiateForm[offerId] || {};
      await apiFetch(`/orchestrator/offers/${offerId}/terms`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, salaryAmount: form.salaryAmount ? Number(form.salaryAmount) : null }),
      });
      setNegotiateForm(f => ({ ...f, [offerId]: {} }));
      reload();
    } finally {
      setSavingTerms(null);
    }
  }

  async function handleGenerateLetter(offerId) {
    setGeneratingLetter(offerId);
    try {
      const res = await apiFetch(`/orchestrator/offers/${offerId}/letter`);
      setLetterModal({ offerId, ...res });
    } finally {
      setGeneratingLetter(null);
    }
  }

  async function handleConfirmSent(offerId) {
    await handleOfferStatus(offerId, "SENT");
    setLetterModal(null);
  }

  async function handleScheduleInterview() {
    setScheduling(true);
    try {
      const result = await apiFetch(`/orchestrator/pipeline/${scheduleModal.requisitionId ?? reqId}/candidates/${scheduleModal.candidateId}/schedule-interview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...scheduleForm, interviewerId: scheduleForm.interviewerId ? Number(scheduleForm.interviewerId) : null }),
      });
      setScheduleForm({ interviewType: "PHONE_SCREEN", interviewerId: "", notes: "" });
      // Stay in modal to show the invitation email that was sent
      setScheduleModal(prev => ({ ...prev, confirmed: true, invitationEmail: result.invitationEmail, candidateEmail: result.candidateEmail }));
      reload();
    } finally {
      setScheduling(false);
    }
  }

  async function handleDraftOutreach(c) {
    setOutreach({ loading: true, matchId: c.matchId, candidateName: c.candidateName, requisitionTitle: c.requisitionTitle, candidateEmail: c.candidateEmail });
    try {
      const url = c.matchId
        ? `/sourcing/matches/${c.matchId}/draft-outreach`
        : `/sourcing/outreach/draft-by-candidate?candidateId=${c.candidateId}&requisitionId=${c.requisitionId}`;
      const result = await apiFetch(url, { method: "POST" });
      setOutreach({ loading: false, ...result });
    } catch { setOutreach(null); }
  }

  // Applicants and imports start at Screened, so Sourced only fills when the Sourcing Agent matches someone
  const sourcedCount = (board?.stages?.SOURCED || []).length;
  const visibleStages = PIPELINE_STAGES.filter(s =>
    (s !== "SOURCED" || sourcedCount > 0) && (showClosed || (s !== "REJECTED" && s !== "NO_SHOW")));
  const closedCount = ["REJECTED", "NO_SHOW"].reduce((n, st) => n + ((board?.stages?.[st]) || []).length, 0);

  return (
    <div>
      <TopBar title="Pipeline Orchestrator"
        subtitle="Unified candidate flow across all agents"
        action={
          <div className="flex items-center gap-2">
            {reqs && (
              <Select value={reqId} onChange={e => setReqId(e.target.value === "ALL" ? "ALL" : Number(e.target.value))}>
                <option value="ALL">All Roles</option>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
            {viewMode === "board" && (
              <button onClick={() => setShowClosed(v => !v)}
                className="px-3 py-2 rounded-xl text-xs font-semibold border"
                style={{
                  borderColor: showClosed ? C.danger : C.border,
                  color: showClosed ? C.danger : C.muted,
                  backgroundColor: showClosed ? `${C.danger}10` : "transparent",
                }}>
                {showClosed ? "Hide" : "Show"} rejected ({closedCount})
              </button>
            )}
            <div className="flex p-1 rounded-xl" style={{ background: C.surface, border: `1px solid ${C.border}` }}>
              <button onClick={() => setViewMode("board")}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors"
                style={{ background: viewMode === "board" ? C.accent : "transparent", color: viewMode === "board" ? "#fff" : C.muted }}>
                <LayoutGrid size={13} /> Board
              </button>
              <button onClick={() => setViewMode("list")}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors"
                style={{ background: viewMode === "list" ? C.accent : "transparent", color: viewMode === "list" ? "#fff" : C.muted }}>
                <List size={13} /> List
              </button>
            </div>
            <Btn variant="primary" onClick={() => setNewRoleModal(true)}>
              <Plus size={13} /> New Role
            </Btn>
            <button onClick={reload}
              className="w-9 h-9 rounded-xl border flex items-center justify-center"
              style={{ borderColor: C.border }}>
              <RefreshCw size={13} style={{ color: C.muted }} />
            </button>
          </div>
        }
      />

      <FailedNotificationsBanner />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {loading && (
        <div className="flex items-center justify-center py-20">
          <Spinner size={24} />
        </div>
      )}

      {board && viewMode === "list" && (
        <PipelineListView board={board} isAllRoles={isAllRoles}
          stageFilter={stageFilter} setStageFilter={setStageFilter}
          sortBy={sortBy} setSortBy={setSortBy} sortDir={sortDir} setSortDir={setSortDir}
          movingId={movingId} handleMove={handleMove}
          setScheduleModal={setScheduleModal} setScheduleForm={setScheduleForm}
          setOfferModal={setOfferModal} onDraftOutreach={handleDraftOutreach}
          onViewHistory={openHistory} onReject={requestReject} />
      )}

      {board && viewMode === "board" && (
        <div className="flex gap-3 overflow-x-auto pb-4" style={{ minHeight: 0 }}>
          {visibleStages.map((stage) => {
            const candidates = (board.stages?.[stage]) || [];
            return (
              <div key={stage} className="rounded-2xl border flex flex-col shrink-0"
                style={{ backgroundColor: C.surface, borderColor: C.border, width: 300, minHeight: 400 }}>
                <div className="px-3 py-3 border-b flex items-center justify-between"
                  style={{ borderColor: C.border }}>
                  <h3 className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>
                    {STAGE_LABELS[stage]}
                  </h3>
                  <span className="text-xs font-mono font-bold w-5 h-5 rounded-full flex items-center justify-center"
                    style={{ backgroundColor: `${C.accent}10`, color: C.accent }}>
                    {candidates.length}
                  </span>
                </div>
                <div className="p-2 space-y-2 flex-1">
                  {candidates.map((c) => {
                    const actions = STAGE_ACTIONS[stage] || [];
                    const isMoving = movingId === c.candidateId;
                    return (
                      <div key={`${c.candidateId}-${c.requisitionId}`} className="p-3 rounded-xl border"
                        style={{ borderColor: C.border, backgroundColor: "#F8FAFC" }}>
                        <div className="flex items-center gap-2 mb-1.5">
                          <Avatar name={c.candidateName} size={28} />
                          <div className="min-w-0 flex-1">
                            <p className="text-xs font-semibold truncate" style={{ color: C.text }}>
                              {c.candidateName}
                            </p>
                            {isAllRoles && c.requisitionTitle && (
                              <p className="text-[9px] truncate" style={{ color: C.muted }}>{c.requisitionTitle}</p>
                            )}
                          </div>
                          <button onClick={() => openHistory(c.candidateId, c.candidateName)}
                            className="w-5 h-5 rounded-md flex items-center justify-center hover:bg-slate-200 flex-shrink-0"
                            title="View history">
                            <History size={10} style={{ color: C.muted }} />
                          </button>
                        </div>
                        {(c.screeningScore ?? c.sourcingMatchScore) != null && (
                          <ScoreRing score={c.screeningScore ?? c.sourcingMatchScore} size={30} />
                        )}
                        {c.updatedByAgent && (
                          <p className="text-[10px] font-semibold mt-1"
                            style={{ color: AGENT_META[c.updatedByAgent]?.color || C.muted }}>
                            Moved by {actorLabel(c.updatedByAgent)}
                          </p>
                        )}
                        {/* Why/when a candidate was rejected or did not show */}
                        {(stage === "REJECTED" || stage === "NO_SHOW") && (
                          <div className="mt-2 p-2 rounded-lg space-y-0.5" style={{ background: `${C.danger}08`, border: `1px solid ${C.danger}33` }}>
                            <p className="text-[9px] font-bold uppercase tracking-wide" style={{ color: C.danger }}>
                              {stage === "NO_SHOW" ? "No-Show" : "Rejected"}
                              {c.enteredAt && <span className="font-normal normal-case" style={{ color: C.muted }}> · {new Date(c.enteredAt).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}</span>}
                            </p>
                            {c.notes && <p className="text-[10px] leading-snug" style={{ color: C.text }}>{c.notes}</p>}
                          </div>
                        )}

                        {/* The debrief informs the offer decision: Interview cards carry the button and the Create Offer dialog shows it.
                            Offer/Hired cards have already made that decision, so only closed-out cards keep it for the record. */}
                        {["REJECTED", "NO_SHOW"].includes(stage) && c.interviewRounds?.some(r => r.status === "COMPLETED") && (
                          <div className="mt-2">
                            <DebriefButton onClick={() => setDebriefModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId })} />
                          </div>
                        )}

                        {/* Interview history summary — shown on OFFER and closed (rejected / no-show) cards */}
                        {(stage === "OFFER" || stage === "REJECTED" || stage === "NO_SHOW") && c.interviewRounds?.length > 0 && (
                          <div className="mt-2 p-2 rounded-lg space-y-1" style={{ background: `${C.accent}08`, border: `1px solid ${C.border}` }}>
                            <p className="text-[9px] font-bold uppercase tracking-wide mb-1" style={{ color: C.muted }}>Interview History</p>
                            {c.interviewRounds.map(r => (
                              <div key={r.id} className="flex items-center gap-1.5">
                                <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: ROUND_COLORS[r.status] }} />
                                <p className="text-[9px]" style={{ color: C.text }}>
                                  R{r.roundNumber} {ROUND_LABELS[r.interviewType] || r.interviewType}
                                  {r.confirmedSlot && <span style={{ color: C.muted }}> · {new Date(r.confirmedSlot).toLocaleDateString("en-GB",{day:"numeric",month:"short"})}</span>}
                                  <span className="ml-1 font-semibold" style={{ color: ROUND_COLORS[r.status] }}>
                                    {r.status === "COMPLETED" ? "✓" : r.status.toLowerCase()}
                                  </span>
                                </p>
                              </div>
                            ))}
                          </div>
                        )}

                        {/* Full recruitment journey — shown only on HIRED cards */}
                        {stage === "HIRED" && (
                          <div className="mt-2 p-2 rounded-lg space-y-3" style={{ background: `${C.success}08`, border: `1px solid ${C.success}33` }}>
                            <p className="text-[9px] font-bold uppercase tracking-wide" style={{ color: C.success }}>Recruitment Journey</p>

                            {/* Interviews */}
                            {c.interviewRounds?.length > 0 && (
                              <div className="space-y-1">
                                <p className="text-[9px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>Interviews</p>
                                {c.interviewRounds.map(r => (
                                  <div key={r.id} className="flex gap-1.5 items-start">
                                    <div className="w-3.5 h-3.5 rounded-full flex items-center justify-center shrink-0 mt-0.5 text-[7px] font-bold text-white"
                                      style={{ backgroundColor: ROUND_COLORS[r.status] || C.muted }}>
                                      {r.roundNumber}
                                    </div>
                                    <div>
                                      <p className="text-[9px] font-semibold" style={{ color: C.text }}>
                                        {ROUND_LABELS[r.interviewType] || r.interviewType}
                                        <span className="ml-1 font-normal" style={{ color: ROUND_COLORS[r.status] }}>
                                          {r.status === "COMPLETED" ? "✓ Completed" : r.status.toLowerCase()}
                                        </span>
                                      </p>
                                      {r.confirmedSlot && (
                                        <p className="text-[8px]" style={{ color: C.muted }}>
                                          {new Date(r.confirmedSlot).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric",hour:"2-digit",minute:"2-digit"})}
                                        </p>
                                      )}
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}

                            {/* Offer */}
                            {c.offer && (
                              <div className="space-y-1">
                                <p className="text-[9px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>Offer</p>
                                <div className="flex items-center justify-between">
                                  <p className="text-[10px] font-bold" style={{ color: C.text }}>
                                    {c.offer.currency} {Number(c.offer.salaryAmount).toLocaleString()}
                                  </p>
                                  <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full text-white"
                                    style={{ backgroundColor: C.success }}>Accepted</span>
                                </div>
                                {c.offer.startDate && (
                                  <p className="text-[9px]" style={{ color: C.muted }}>
                                    Start: {new Date(c.offer.startDate).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"})}
                                  </p>
                                )}
                                <SignedCopyLink offer={c.offer} />
                              </div>
                            )}

                            {/* Negotiation history */}
                            {c.offer?.history?.length > 0 && (
                              <div className="space-y-1">
                                <p className="text-[9px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>Negotiation History</p>
                                {c.offer.history.map((h, i) => (
                                  <div key={i} className="flex gap-1.5 items-start">
                                    <div className="w-3.5 h-3.5 rounded-full flex items-center justify-center shrink-0 mt-0.5 text-[7px] font-bold text-white"
                                      style={{ backgroundColor: h.changedBy === "CANDIDATE" ? C.accent : C.warning }}>
                                      {i + 1}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <p className="text-[9px] font-semibold" style={{ color: C.text }}>
                                        {h.changedBy === "CANDIDATE" ? "Candidate" : "Recruiter"}
                                        {h.salaryAmount && <> · {h.currency} {Number(h.salaryAmount).toLocaleString()}</>}
                                        {h.startDate && <> · Start {new Date(h.startDate).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"})}</>}
                                      </p>
                                      {h.notes && <p className="text-[9px]" style={{ color: C.muted }}>{h.notes}</p>}
                                      <p className="text-[8px]" style={{ color: C.border }}>
                                        {new Date(h.createdAt).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric",hour:"2-digit",minute:"2-digit"})}
                                      </p>
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        )}

                        {/* Offer card — full lifecycle */}
                        {stage === "OFFER" && (() => {
                          const offer = c.offer;
                          const statusMeta = offer ? OFFER_STATUS_META[offer.status] : null;
                          const isUpdating = updatingOfferId === offer?.id;
                          return (
                            <div className="mt-2">
                              {offer ? (
                                <div className="p-2 rounded-lg space-y-2" style={{ background: `${C.success}08`, border: `1px solid ${C.success}33` }}>
                                  {/* Offer summary */}
                                  <div className="flex items-center justify-between">
                                    <p className="text-[10px] font-bold" style={{ color: C.text }}>
                                      {offer.salaryAmount ? `${offer.currency} ${Number(offer.salaryAmount).toLocaleString()}` : "Offer extended"}
                                    </p>
                                    <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full text-white"
                                      style={{ backgroundColor: statusMeta?.color }}>
                                      {statusMeta?.label}
                                    </span>
                                  </div>
                                  {offer.startDate && <p className="text-[9px]" style={{ color: C.muted }}>Start: {new Date(offer.startDate).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"})}</p>}
                                  {offer.expiryDate && <p className="text-[9px]" style={{ color: C.muted }}>Expires: {new Date(offer.expiryDate).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"})}</p>}
                                  <SignedCopyLink offer={offer} />

                                  {/* Status actions */}
                                  {offer.status === "PENDING" && (
                                    <button
                                      disabled={generatingLetter === offer.id}
                                      onClick={() => handleGenerateLetter(offer.id)}
                                      className="w-full text-[10px] font-semibold py-1 rounded-lg text-white flex items-center justify-center gap-1"
                                      style={{ backgroundColor: C.accent, opacity: generatingLetter === offer.id ? 0.5 : 1 }}>
                                      {generatingLetter === offer.id ? <><Spinner size={10} /> Generating…</> : <><Sparkles size={10} /> Review & send</>}
                                    </button>
                                  )}
                                  {offer.status === "SENT" && (
                                    <p className="text-[10px] leading-snug px-2 py-1.5 rounded-lg"
                                      style={{ color: C.muted, backgroundColor: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
                                      Awaiting the candidate's response. They accept, negotiate or decline using the buttons in the offer email.
                                    </p>
                                  )}
                                  {/* Negotiation history — always visible when history exists */}
                                  {offer.history?.length > 0 && (
                                    <div className="space-y-1 pt-1" style={{ borderTop: `1px solid ${C.border}` }}>
                                      <p className="text-[9px] font-bold uppercase tracking-wide" style={{ color: C.muted }}>Negotiation History</p>
                                      {offer.history.map((h, i) => (
                                        <div key={i} className="flex gap-1.5 items-start">
                                          <div className="w-3.5 h-3.5 rounded-full flex items-center justify-center shrink-0 mt-0.5 text-[7px] font-bold text-white"
                                            style={{ backgroundColor: h.changedBy === "CANDIDATE" ? C.accent : C.warning }}>
                                            {i + 1}
                                          </div>
                                          <div className="flex-1 min-w-0">
                                            <p className="text-[9px] font-semibold" style={{ color: C.text }}>
                                              {h.changedBy === "CANDIDATE" ? "Candidate" : "Recruiter"}
                                              {h.salaryAmount && <> · {h.currency} {Number(h.salaryAmount).toLocaleString()}</>}
                                              {h.startDate && <> · Start {new Date(h.startDate).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"})}</>}
                                            </p>
                                            {h.notes && <p className="text-[9px]" style={{ color: C.muted }}>{h.notes}</p>}
                                            <p className="text-[8px]" style={{ color: C.border }}>
                                              {new Date(h.createdAt).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric",hour:"2-digit",minute:"2-digit"})}
                                            </p>
                                          </div>
                                        </div>
                                      ))}
                                    </div>
                                  )}

                                  {offer.status === "NEGOTIATING" && (() => {
                                    const nf = negotiateForm[offer.id] || {};
                                    const setNf = (patch) => setNegotiateForm(f => ({ ...f, [offer.id]: { ...(f[offer.id] || {}), ...patch } }));
                                    return (
                                      <div className="space-y-2">
                                        <p className="text-[9px] font-bold uppercase tracking-wide" style={{ color: C.warning }}>Under Negotiation</p>

                                        {/* Update terms form */}
                                        <div className="space-y-1.5 p-2 rounded-lg" style={{ backgroundColor: `${C.warning}08`, border: `1px solid ${C.warning}22` }}>
                                          <p className="text-[9px] font-semibold" style={{ color: C.muted }}>Revise offer — the candidate is emailed the new terms to respond to</p>
                                          <div className="flex gap-1">
                                            <input type="number" placeholder="New salary"
                                              value={nf.salaryAmount || ""}
                                              onChange={e => setNf({ salaryAmount: e.target.value })}
                                              className="flex-1 text-[9px] px-1.5 py-1 rounded-md border min-w-0"
                                              style={{ borderColor: C.border, color: C.text }} />
                                            <select value={nf.currency || offer.currency || "MYR"}
                                              onChange={e => setNf({ currency: e.target.value })}
                                              className="text-[9px] px-1 py-1 rounded-md border"
                                              style={{ borderColor: C.border, color: C.text }}>
                                              {["MYR","USD","SGD","GBP","EUR"].map(c => <option key={c}>{c}</option>)}
                                            </select>
                                          </div>
                                          <input type="date" value={nf.startDate || ""}
                                            onChange={e => setNf({ startDate: e.target.value })}
                                            className="w-full text-[9px] px-1.5 py-1 rounded-md border"
                                            style={{ borderColor: C.border, color: C.text }} />
                                          <input type="text" placeholder="Negotiation notes…"
                                            value={nf.notes || ""}
                                            onChange={e => setNf({ notes: e.target.value })}
                                            className="w-full text-[9px] px-1.5 py-1 rounded-md border"
                                            style={{ borderColor: C.border, color: C.text }} />
                                          <button disabled={savingTerms === offer.id} onClick={() => handleUpdateTerms(offer.id)}
                                            className="w-full text-[9px] font-semibold py-1 rounded-md text-white"
                                            style={{ backgroundColor: C.warning, opacity: savingTerms === offer.id ? 0.5 : 1 }}>
                                            {savingTerms === offer.id ? "Sending…" : "Send Revised Offer"}
                                          </button>
                                        </div>

                                      </div>
                                    );
                                  })()}
                                </div>
                              ) : (
                                <button onClick={() => setOfferModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId })}
                                  className="w-full text-[10px] font-semibold py-1 rounded-lg text-white"
                                  style={{ backgroundColor: C.success }}>
                                  + Create Offer
                                </button>
                              )}
                            </div>
                          );
                        })()}

                        {/* Multi-round interview timeline */}
                        {stage === "INTERVIEW_SCHEDULED" && (() => {
                          const rounds = c.interviewRounds || [];
                          const activeRound = rounds.find(r => r.status === "CONFIRMED" || r.status === "PROPOSED");
                          const allCompleted = rounds.length > 0 && rounds.every(r => r.status === "COMPLETED");
                          const usedTypes = rounds.map(r => r.interviewType);
                          const nextType = INTERVIEW_ROUND_TYPES.find(t => !usedTypes.includes(t)) || "FINAL";

                          return (
                            <div className="mt-2">
                              {/* Rounds timeline */}
                              {rounds.length > 0 && (
                                <div className="space-y-1.5 mb-2">
                                  {rounds.map(r => (
                                    <div key={r.id} className="rounded-lg overflow-hidden"
                                      style={{ border: `1px solid ${ROUND_COLORS[r.status]}33` }}>
                                      {/* Round header row */}
                                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1.5 p-2"
                                        style={{ background: `${ROUND_COLORS[r.status]}15` }}>
                                        <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: ROUND_COLORS[r.status] }} />
                                        <div className="flex-1 min-w-[110px]">
                                          <p className="text-[10px] font-semibold" style={{ color: C.text }}>
                                            R{r.roundNumber} · {ROUND_LABELS[r.interviewType] || r.interviewType}
                                          </p>
                                          {r.interviewerName && <p className="text-[9px]" style={{ color: C.muted }}>with {r.interviewerName}</p>}
                                          {r.scorecard && (
                                            <p className="text-[9px] font-semibold" title={r.scorecard.notes || ""} style={{ color: verdictColor(r.scorecard.verdict) }}>
                                              {VERDICT_LABELS[r.scorecard.verdict]} · {r.scorecard.averageRating}/5
                                            </p>
                                          )}
                                          {r.scorecard && (r.scorecard.noticePeriod || r.scorecard.expectedSalary) && (
                                            <p className="text-[9px]" style={{ color: C.muted }}>
                                              {r.scorecard.noticePeriod && <>Notice: {r.scorecard.noticePeriod}</>}
                                              {r.scorecard.noticePeriod && r.scorecard.expectedSalary && " · "}
                                              {r.scorecard.expectedSalary && <>Expects: {r.scorecard.expectedSalary}</>}
                                            </p>
                                          )}
                                          {r.confirmedSlot && (
                                            <p className="text-[9px]" style={{ color: C.muted }}>
                                              {new Date(r.confirmedSlot).toLocaleDateString("en-GB", { day:"numeric", month:"short", hour:"2-digit", minute:"2-digit" })}
                                            </p>
                                          )}
                                        </div>
                                        <span className="text-[9px] font-bold uppercase text-right" style={{ color: ROUND_COLORS[r.status] }}>
                                          {r.status === "COMPLETED" ? "✓ Done" : r.status === "CANCELLED" ? "✗" : r.status === "PROPOSED" ? "awaiting booking" : r.status.toLowerCase()}
                                        </span>
                                        {(r.status === "CONFIRMED" || r.status === "PROPOSED") && (
                                          <div className="flex gap-1.5 items-center flex-wrap w-full pl-3.5">
                                            {r.status === "CONFIRMED" && (
                                              <button
                                                disabled={completingRoundId === r.id}
                                                onClick={() => openScorecard(c, r)}
                                                className="text-[9px] font-semibold px-1.5 py-0.5 rounded-md text-white"
                                                style={{ backgroundColor: C.success, opacity: completingRoundId === r.id ? 0.5 : 1 }}>
                                                {completingRoundId === r.id ? "…" : "Done"}
                                              </button>
                                            )}
                                            <button
                                              disabled={reschedulingId === r.id}
                                              onClick={() => handleEmailReschedule(r.id, r.status === "CONFIRMED")}
                                              className="text-[9px] font-semibold px-1.5 py-0.5 rounded-md"
                                              style={{ color: C.warning, border: `1px solid ${C.warning}55`, background: `${C.warning}10`, opacity: reschedulingId === r.id ? 0.5 : 1 }}>
                                              {reschedulingId === r.id ? "…" : r.status === "CONFIRMED" ? "Reschedule" : "Resend link"}
                                            </button>
                                            <button
                                              onClick={() => { setRescheduleModal({ interviewId: r.id, roundNumber: r.roundNumber, interviewType: r.interviewType }); setRescheduleSlot(""); }}
                                              title="Set a time yourself without asking the candidate"
                                              className="text-[9px] underline px-0.5"
                                              style={{ color: C.muted }}>
                                              Set manually
                                            </button>
                                          </div>
                                        )}
                                      </div>
                                      {/* Email / reply action row */}
                                      {r.invitationEmail && (
                                        <div className="px-2 py-1.5 border-t flex flex-wrap gap-1.5 items-center" style={{ borderColor: `${ROUND_COLORS[r.status]}22`, background: C.surface }}>
                                          <button
                                            onClick={() => { setEmailModal({ title: `Invitation Email — R${r.roundNumber}`, body: r.invitationEmail, candidateEmail: c.candidateEmail, interviewId: r.id }); setEmailEditing(false); setEmailEditText(r.invitationEmail); setEmailSaved(false); }}
                                            className="text-[9px] font-semibold px-2 py-0.5 rounded flex items-center gap-1"
                                            style={{ color: C.success, background: `${C.success}15`, border: `1px solid ${C.success}33` }}>
                                            ✉ View invite
                                          </button>
                                          {r.candidateReply ? (
                                            <button
                                              onClick={() => setEmailModal({ title: `Candidate Reply — R${r.roundNumber}`, body: r.candidateReply, candidateEmail: c.candidateEmail })}
                                              className="text-[9px] font-semibold px-2 py-0.5 rounded flex items-center gap-1"
                                              style={{ color: C.accent, background: `${C.accent}15`, border: `1px solid ${C.accent}33` }}>
                                              ↩ View reply
                                            </button>
                                          ) : (r.status === "CONFIRMED" || r.status === "PROPOSED") && (<>
                                            <button
                                              onClick={() => { setRecordResponseModal({ interviewId: r.id, roundNumber: r.roundNumber }); setRecordReplyText(""); }}
                                              className="text-[9px] font-semibold px-2 py-0.5 rounded"
                                              style={{ color: C.success, border: `1px solid ${C.success}55`, background: `${C.success}10` }}>
                                              Record reply
                                            </button>
                                            <button
                                              onClick={() => handleSimulateResponse(r.id)}
                                              disabled={simulatingResponseId === r.id}
                                              className="text-[9px] font-semibold px-2 py-0.5 rounded"
                                              style={{ color: C.muted, border: `1px solid ${C.border}`, background: C.surface }}>
                                              {simulatingResponseId === r.id ? "Simulating…" : "Simulate"}
                                            </button>
                                          </>)}
                                        </div>
                                      )}
                                    </div>
                                  ))}
                                </div>
                              )}

                              {/* Action buttons */}
                              <div className="space-y-1">
                                {rounds.some(r => r.status === "COMPLETED") && (
                                  <DebriefButton onClick={() => setDebriefModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId })} />
                                )}
                                {/* Schedule next round — only if there's a round type left and no active round, OR no rounds yet */}
                                {(!activeRound || rounds.length === 0) && !allCompleted && (
                                  <button
                                    onClick={() => {
                                      setScheduleForm(f => ({ ...f, interviewType: nextType, notes: "" }));
                                      setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId, nextType });
                                    }}
                                    className="w-full text-[10px] font-semibold py-1 rounded-lg text-white"
                                    style={{ backgroundColor: C.accent }}>
                                    {rounds.length === 0 ? "Schedule Round 1" : `Schedule Round ${rounds.length + 1}`}
                                  </button>
                                )}
                                {allCompleted && (
                                  <>
                                    {nextType && (
                                      <button
                                        onClick={() => {
                                          setScheduleForm(f => ({ ...f, interviewType: nextType, notes: "" }));
                                          setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId, nextType });
                                        }}
                                        className="w-full text-[10px] font-semibold py-1 rounded-lg"
                                        style={{ color: C.accent, border: `1px solid ${C.accent}55` }}>
                                        + Add Round {rounds.length + 1}
                                      </button>
                                    )}
                                    <button
                                      onClick={() => setOfferModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId })}
                                      className="w-full text-[10px] font-semibold py-1 rounded-lg text-white"
                                      style={{ backgroundColor: C.success }}>
                                      Advance to Offer
                                    </button>
                                  </>
                                )}
                                <button disabled={isMoving}
                                  onClick={() => requestReject(c)}
                                  className="w-full text-[10px] font-semibold py-1 rounded-lg transition-opacity"
                                  style={{ color: C.danger, border: `1px solid ${C.danger}33`, opacity: isMoving ? 0.5 : 1 }}>
                                  Reject
                                </button>
                              </div>
                            </div>
                          );
                        })()}

                        {/* Stage action buttons for non-interview stages */}
                        {stage !== "INTERVIEW_SCHEDULED" && (actions.length > 0 || stage === "HIRED") && (
                          <div className="mt-2 space-y-1">
                            {actions.map(({ label, next }) => (
                              <button key={next} disabled={isMoving}
                                onClick={() => next === "INTERVIEW_SCHEDULED"
                                  ? setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, requisitionId: c.requisitionId, nextType: "PHONE_SCREEN" })
                                  : handleMove(c.candidateId, next, c.requisitionId)
                                }
                                className="w-full text-[10px] font-semibold py-1 rounded-lg text-white transition-opacity"
                                style={{ backgroundColor: C.accent, opacity: isMoving ? 0.5 : 1 }}>
                                {isMoving ? "Moving…" : label}
                              </button>
                            ))}
                            {stage === "HIRED" && (
                              <button disabled={isMoving}
                                onClick={() => handleMove(c.candidateId, "NO_SHOW", c.requisitionId)}
                                className="w-full text-[10px] font-semibold py-1 rounded-lg transition-opacity"
                                style={{ color: C.warning, border: `1px solid ${C.warning}55`, background: `${C.warning}10`, opacity: isMoving ? 0.5 : 1 }}>
                                Mark as No-Show
                              </button>
                            )}
                            {stage !== "REJECTED" && stage !== "HIRED" && (
                              <button disabled={isMoving}
                                onClick={() => requestReject(c)}
                                className="w-full text-[10px] font-semibold py-1 rounded-lg transition-opacity"
                                style={{ color: C.danger, border: `1px solid ${C.danger}33`, opacity: isMoving ? 0.5 : 1 }}>
                                Reject
                              </button>
                            )}
                            {stage === "SHORTLISTED" && (
                              <button onClick={() => handleDraftOutreach(c)}
                                className="w-full text-[10px] font-semibold py-1 rounded-lg flex items-center justify-center gap-1"
                                style={{ color: C.accent, border: `1px solid ${C.accent}55` }}>
                                <Mail size={10} /> Send Outreach
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                  {candidates.length === 0 && (
                    <p className="text-center text-xs py-6" style={{ color: C.border }}>—</p>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* New Role Modal */}
      {newRoleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6">
            <div className="flex items-start justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Create Open Role</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>New job requisition will be live immediately</p>
              </div>
              <button onClick={() => setNewRoleModal(false)} className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            <div className="space-y-4">
              {/* Title */}
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>
                  Job Title <span style={{ color: C.danger }}>*</span>
                </label>
                <input type="text" placeholder="e.g. Senior Backend Engineer"
                  value={roleForm.title}
                  onChange={e => setRoleForm(f => ({ ...f, title: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: roleForm.title ? C.border : C.danger + "88", color: C.text, backgroundColor: C.bg }} />
              </div>

              {/* Department + Location */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Department</label>
                  <input type="text" placeholder="e.g. Engineering"
                    value={roleForm.department}
                    onChange={e => setRoleForm(f => ({ ...f, department: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Location</label>
                  <input type="text" placeholder="e.g. Kuala Lumpur / Remote"
                    value={roleForm.location}
                    onChange={e => setRoleForm(f => ({ ...f, location: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
              </div>

              {/* Experience Level */}
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: C.muted }}>Experience Level</label>
                <div className="flex gap-2 flex-wrap">
                  {[["JUNIOR","Junior"],["MID","Mid-Level"],["SENIOR","Senior"],["LEAD","Lead / Principal"]].map(([v, l]) => (
                    <button key={v} onClick={() => setRoleForm(f => ({ ...f, experienceLevel: v }))}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors"
                      style={{
                        borderColor: roleForm.experienceLevel === v ? C.accent : C.border,
                        backgroundColor: roleForm.experienceLevel === v ? `${C.accent}15` : "transparent",
                        color: roleForm.experienceLevel === v ? C.accent : C.muted,
                      }}>
                      {l}
                    </button>
                  ))}
                </div>
              </div>

              {/* Required Skills */}
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>
                  Required Skills <span style={{ color: C.border }}>(comma-separated)</span>
                </label>
                <input type="text" placeholder="e.g. Java, Spring Boot, PostgreSQL, AWS"
                  value={roleForm.requiredSkills}
                  onChange={e => setRoleForm(f => ({ ...f, requiredSkills: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>

              {/* Interview Rounds */}
              <div>
                <label className="block text-xs font-semibold mb-2" style={{ color: C.muted }}>Interview Rounds Required</label>
                <div className="flex gap-2">
                  {[1,2,3,4].map(n => (
                    <button key={n} onClick={() => setRoleForm(f => ({ ...f, requiredInterviewRounds: n }))}
                      className="px-4 py-1.5 rounded-xl border text-sm font-semibold transition-colors"
                      style={{ borderColor: roleForm.requiredInterviewRounds === n ? C.accent : C.border, backgroundColor: roleForm.requiredInterviewRounds === n ? `${C.accent}15` : "transparent", color: roleForm.requiredInterviewRounds === n ? C.accent : C.muted }}>
                      {n}
                    </button>
                  ))}
                </div>
              </div>

              {/* Description */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="block text-xs font-semibold" style={{ color: C.muted }}>
                    Job Description <span style={{ color: C.border }}>(optional)</span>
                  </label>
                  <div className="flex items-center gap-3">
                    <button type="button" onClick={handleGenerateJd}
                      disabled={generatingJd || !roleForm.title.trim()}
                      className="flex items-center gap-1 text-xs font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
                      style={{ color: C.accent }}
                      title={!roleForm.title.trim() ? "Enter a job title first" : "Generate with AI"}>
                      {generatingJd ? <Spinner size={12} /> : <Sparkles size={12} />}
                      {generatingJd ? "Generating…" : "Generate with AI"}
                    </button>
                    {roleForm.description && (
                      <button type="button" onClick={() => setJdDialogOpen(true)}
                        className="flex items-center gap-1 text-xs font-semibold" style={{ color: C.muted }}
                        title="Open full view">
                        <Maximize2 size={12} /> Expand
                      </button>
                    )}
                  </div>
                </div>
                <textarea rows={6} placeholder="Responsibilities, team context, what success looks like…"
                  value={roleForm.description}
                  onChange={e => setRoleForm(f => ({ ...f, description: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                {generateJdError && (
                  <p className="text-xs mt-1" style={{ color: C.danger }}>{generateJdError}</p>
                )}
              </div>
              {jdDialogOpen && (
                <JobDescriptionDialog
                  value={roleForm.description}
                  onChange={v => setRoleForm(f => ({ ...f, description: v }))}
                  onClose={() => setJdDialogOpen(false)}
                />
              )}
            </div>

            <div className="flex gap-2 mt-5">
              <Btn variant="primary" onClick={handleCreateRole} disabled={savingRole || !roleForm.title.trim()}>
                {savingRole ? <><Spinner size={13} /> Creating…</> : "Create Role"}
              </Btn>
              <Btn variant="secondary" onClick={() => setNewRoleModal(false)}>Cancel</Btn>
            </div>
          </Card>
        </div>
      )}

      {/* Email preview Modal (invite / candidate reply) */}
      {emailModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.6)", backdropFilter: "blur(4px)" }}
          onClick={() => { if (!emailEditing) { setEmailModal(null); setEmailBodyCopied(false); setEmailAddrCopied(false); } }}>
          <Card className="w-full max-w-xl p-6 max-h-[85vh] flex flex-col" onClick={e => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between mb-3 shrink-0">
              <h2 className="font-bold text-base" style={{ color: C.text }}>{emailModal.title}</h2>
              <div className="flex items-center gap-2">
                {emailModal.interviewId && !emailEditing && (
                  <button
                    onClick={() => { setEmailEditing(true); setEmailEditText(emailModal.body); setEmailSaved(false); }}
                    className="text-xs font-semibold px-2.5 py-1 rounded-lg"
                    style={{ color: C.accent, background: `${C.accent}15`, border: `1px solid ${C.accent}33` }}>
                    Edit
                  </button>
                )}
                <button onClick={() => { setEmailModal(null); setEmailBodyCopied(false); setEmailAddrCopied(false); setEmailEditing(false); }}
                  className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                  <X size={16} style={{ color: C.muted }} />
                </button>
              </div>
            </div>

            {/* To: row */}
            {emailModal.candidateEmail && (
              <div className="flex items-center gap-2 px-3 py-2 rounded-xl mb-3 shrink-0"
                style={{ background: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
                <Mail size={13} style={{ color: C.accent }} />
                <span className="text-xs" style={{ color: C.muted }}>To:</span>
                <span className="text-xs font-semibold flex-1" style={{ color: C.text }}>{emailModal.candidateEmail}</span>
                <button
                  onClick={async () => {
                    try { await navigator.clipboard.writeText(emailModal.candidateEmail); }
                    catch { const ta = document.createElement("textarea"); ta.value = emailModal.candidateEmail; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); }
                    setEmailAddrCopied(true); setTimeout(() => setEmailAddrCopied(false), 2000);
                  }}
                  className="text-[10px] font-semibold px-2 py-0.5 rounded"
                  style={{ color: emailAddrCopied ? C.success : C.accent, background: `${C.accent}15` }}>
                  {emailAddrCopied ? "Copied!" : "Copy"}
                </button>
              </div>
            )}

            {/* Body — view or edit */}
            {emailEditing ? (
              <textarea
                className="flex-1 w-full px-4 py-3 rounded-xl border text-sm resize-none leading-relaxed"
                style={{ borderColor: C.accent, color: C.text, backgroundColor: C.bg, minHeight: 240 }}
                value={emailEditText}
                onChange={e => setEmailEditText(e.target.value)}
              />
            ) : (
              <div className="overflow-y-auto flex-1 rounded-xl p-4" style={{ background: C.surface, border: `1px solid ${C.border}` }}>
                <pre className="text-sm whitespace-pre-wrap leading-relaxed" style={{ color: C.text, fontFamily: "inherit" }}>
                  {emailModal.body}
                </pre>
              </div>
            )}

            {/* Footer actions */}
            <div className="flex items-center gap-2 mt-3 shrink-0">
              {emailEditing ? (
                <>
                  <Btn variant="primary" disabled={emailSaving} onClick={async () => {
                    setEmailSaving(true);
                    setEmailSaveError(null);
                    try {
                      await apiFetch(`/orchestrator/interviews/${emailModal.interviewId}/invitation-email`, {
                        method: "PATCH",
                        body: JSON.stringify({ invitationEmail: emailEditText }),
                      });
                      setEmailModal(prev => ({ ...prev, body: emailEditText }));
                      setEmailSaved(true);
                      setEmailEditing(false);
                    } catch (e) {
                      setEmailSaveError(e.message || "Failed to save");
                    } finally { setEmailSaving(false); }
                  }}>
                    {emailSaving ? <><Spinner size={13} /> Saving…</> : "Save changes"}
                  </Btn>
                  <Btn variant="secondary" onClick={() => { setEmailEditing(false); setEmailSaveError(null); }}>Cancel</Btn>
                  {emailSaveError && <span className="text-xs" style={{ color: C.danger }}>{emailSaveError}</span>}
                </>
              ) : (
                <>
                  {emailSaved && <span className="text-xs font-semibold" style={{ color: C.success }}>✓ Saved</span>}
                  {emailModal.interviewId && (
                    <Btn variant="primary" disabled={sendingInvite || inviteSent}
                      onClick={async () => {
                        setSendingInvite(true);
                        try {
                          await apiFetch(`/orchestrator/interviews/${emailModal.interviewId}/send-invitation`, { method: "POST" });
                          setInviteSent(true);
                          setTimeout(() => { setEmailModal(null); setInviteSent(false); }, 1800);
                        } catch (err) {
                          alert("Failed to send: " + err.message);
                        } finally { setSendingInvite(false); }
                      }}>
                      {inviteSent ? <CheckCircle2 size={14} /> : sendingInvite ? <Spinner size={14} /> : <Mail size={14} />}
                      {inviteSent ? "Sent!" : sendingInvite ? "Sending…" : "Send to Candidate"}
                    </Btn>
                  )}
                  <button
                    onClick={async () => {
                      try { await navigator.clipboard.writeText(emailModal.body); }
                      catch { const ta = document.createElement("textarea"); ta.value = emailModal.body; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); }
                      setEmailBodyCopied(true); setTimeout(() => setEmailBodyCopied(false), 2000);
                    }}
                    className="ml-auto text-xs font-semibold px-3 py-1.5 rounded-lg"
                    style={{ color: emailBodyCopied ? C.success : C.accent, background: `${C.accent}15`, border: `1px solid ${C.accent}33` }}>
                    {emailBodyCopied ? "Copied!" : "Copy email body"}
                  </button>
                </>
              )}
            </div>
          </Card>
        </div>
      )}

      {letterModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.6)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-2xl p-6 max-h-[90vh] flex flex-col">
            {/* Header */}
            <div className="flex items-start justify-between mb-4 shrink-0">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Offer Letter</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>
                  Generated for <strong>{letterModal.candidateName}</strong>
                </p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => {
                    if (letterEditing) {
                      setLetterModal(m => ({ ...m, letterText: letterEditText }));
                      setLetterEditing(false);
                    } else {
                      setLetterEditText(letterModal.letterText);
                      setLetterEditing(true);
                    }
                  }}
                  className="px-3 py-1.5 rounded-xl border text-xs font-semibold transition-colors"
                  style={{ borderColor: letterEditing ? C.accent : C.border, color: letterEditing ? C.accent : C.muted }}>
                  {letterEditing ? "Save Edits" : "Edit"}
                </button>
                <button onClick={() => { setLetterModal(null); setLetterEditing(false); }}
                  className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                  <X size={16} style={{ color: C.muted }} />
                </button>
              </div>
            </div>

            {/* Candidate email */}
            <div className="flex items-center gap-3 px-4 py-3 rounded-xl mb-4 shrink-0"
              style={{ backgroundColor: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
              <Mail size={14} style={{ color: C.accent }} />
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wide" style={{ color: C.muted }}>Send to</p>
                <p className="text-sm font-semibold" style={{ color: C.text }}>{letterModal.candidateEmail || "No email on record"}</p>
              </div>
              {letterModal.candidateEmail && (
                <button
                  onClick={() => { navigator.clipboard.writeText(letterModal.candidateEmail); setCopied("lemail"); setTimeout(() => setCopied(false), 2000); }}
                  className="ml-auto text-[10px] font-semibold px-2 py-1 rounded-lg border shrink-0 transition-colors"
                  style={{ borderColor: copied === "lemail" ? C.success : C.border, color: copied === "lemail" ? C.success : C.muted }}>
                  {copied === "lemail" ? "✓ Copied!" : "Copy Email"}
                </button>
              )}
            </div>

            {/* Letter body */}
            <div className="flex-1 overflow-y-auto rounded-xl border p-4 mb-4"
              style={{ borderColor: letterEditing ? C.accent : C.border, backgroundColor: "#FAFAFA" }}>
              {letterEditing ? (
                <textarea
                  className="w-full h-full min-h-[300px] text-sm font-sans leading-relaxed resize-none outline-none bg-transparent"
                  style={{ color: C.text }}
                  value={letterEditText}
                  onChange={e => setLetterEditText(e.target.value)}
                />
              ) : (
                <pre className="text-sm whitespace-pre-wrap font-sans leading-relaxed"
                  style={{ color: C.text }}>{letterModal.letterText}</pre>
              )}
            </div>

            {/* Actions */}
            <div className="flex gap-2 shrink-0">
              <Btn variant="primary" onClick={() => {
                const text = letterEditing ? letterEditText : letterModal.letterText;
                navigator.clipboard.writeText(text);
                setCopied("letter");
                setTimeout(() => setCopied(false), 2000);
              }}>
                {copied === "letter" ? "✓ Copied!" : "Copy Letter"}
              </Btn>
              <button
                onClick={() => {
                  const text = letterEditing ? letterEditText : letterModal.letterText;
                  const blob = new Blob([text], { type: "text/plain" });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = `offer-letter-${letterModal.candidateName.replace(/\s+/g, "-")}.txt`;
                  a.click();
                  URL.revokeObjectURL(url);
                }}
                className="px-4 py-2 rounded-xl border text-sm font-semibold"
                style={{ borderColor: C.border, color: C.muted }}>
                Download .txt
              </button>
              <div className="flex-1" />
              <Btn variant="primary"
                disabled={sendingOfferEmail || offerEmailSent}
                onClick={async () => {
                  const text = letterEditing ? letterEditText : letterModal.letterText;
                  setSendingOfferEmail(true);
                  try {
                    await apiFetch(`/orchestrator/offers/${letterModal.offerId}/send-email`, {
                      method: "POST",
                      body: JSON.stringify({ letterText: text }),
                    });
                    setOfferEmailSent(true);
                    reload();
                    setTimeout(() => { setLetterModal(null); setOfferEmailSent(false); }, 1800);
                  } catch (err) {
                    alert("Failed to send: " + err.message);
                  } finally { setSendingOfferEmail(false); }
                }}
                style={{ backgroundColor: offerEmailSent ? C.success : C.accent }}>
                {offerEmailSent ? <><CheckCircle2 size={14} /> Sent!</> : sendingOfferEmail ? <><Spinner size={14} /> Sending…</> : <><Mail size={14} /> Send to Candidate</>}
              </Btn>
              <Btn variant="secondary" onClick={() => { setLetterModal(null); setLetterEditing(false); }}>Cancel</Btn>
            </div>
          </Card>
        </div>
      )}

      {/* Offer Modal */}
      {offerModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6">
            <div className="flex items-start justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Create Offer</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>{offerModal.candidateName}</p>
              </div>
              <button onClick={() => setOfferModal(null)} className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            <OfferDebriefPanel key={`${offerModal.requisitionId ?? reqId}-${offerModal.candidateId}`}
              requisitionId={offerModal.requisitionId ?? reqId} candidateId={offerModal.candidateId} />

            <div className="space-y-4">
              {/* Salary */}
              <div className="flex gap-2">
                <div className="flex-1">
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Salary / Package</label>
                  <input type="number" placeholder="e.g. 8500"
                    value={offerForm.salaryAmount}
                    onChange={e => setOfferForm(f => ({ ...f, salaryAmount: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div style={{ width: 90 }}>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Currency</label>
                  <select value={offerForm.currency} onChange={e => setOfferForm(f => ({ ...f, currency: e.target.value }))}
                    className="w-full px-2 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}>
                    {["MYR","USD","SGD","GBP","EUR"].map(c => <option key={c}>{c}</option>)}
                  </select>
                </div>
              </div>

              {/* Dates */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Start Date</label>
                  <input type="date" value={offerForm.startDate}
                    onChange={e => setOfferForm(f => ({ ...f, startDate: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
                <div>
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Offer Expires</label>
                  <input type="date" value={offerForm.expiryDate}
                    onChange={e => setOfferForm(f => ({ ...f, expiryDate: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
              </div>

              {/* Notes */}
              <div>
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Notes <span style={{ color: C.border }}>(optional)</span></label>
                <textarea rows={2} placeholder="e.g. Includes performance bonus, remote-first..."
                  value={offerForm.notes}
                  onChange={e => setOfferForm(f => ({ ...f, notes: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
            </div>

            {offerError && (
              <p className="text-xs mt-3 px-3 py-2 rounded-xl" style={{ color: C.danger, backgroundColor: `${C.danger}10`, border: `1px solid ${C.danger}33` }}>
                {offerError}
              </p>
            )}

            <div className="flex gap-2 mt-5">
              <Btn variant="primary" onClick={handleCreateOffer} disabled={submittingOffer}>
                {submittingOffer ? <><Spinner size={13} /> Saving…</> : "Create & review letter"}
              </Btn>
              <Btn variant="secondary" onClick={() => setOfferModal(null)}>Cancel</Btn>
            </div>
            <p className="text-[11px] mt-3" style={{ color: C.muted }}>
              Nothing is sent to the candidate until you press Send on the letter.
            </p>
          </Card>
        </div>
      )}

      {/* Record Candidate Response Modal */}
      {recordResponseModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6">
            <div className="flex items-start justify-between mb-4">
              <div>
                <h2 className="font-bold text-base" style={{ color: C.text }}>Record Candidate Reply</h2>
                <p className="text-xs mt-0.5" style={{ color: C.muted }}>R{recordResponseModal.roundNumber} — paste the candidate's actual email reply</p>
              </div>
              <button onClick={() => setRecordResponseModal(null)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>
            <textarea
              rows={6}
              placeholder="Paste the candidate's reply here…"
              value={recordReplyText}
              onChange={e => setRecordReplyText(e.target.value)}
              className="w-full px-3 py-2 rounded-xl border text-sm resize-none mb-4"
              style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
            />
            <div className="flex gap-2">
              <Btn variant="primary" onClick={handleRecordResponse} disabled={!recordReplyText.trim() || recordingResponse}>
                {recordingResponse ? <><Spinner size={13} /> Saving…</> : "Save Reply"}
              </Btn>
              <Btn variant="secondary" onClick={() => setRecordResponseModal(null)}>Cancel</Btn>
            </div>
          </Card>
        </div>
      )}

      {/* Reschedule Modal */}
      {rescheduleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-sm p-6">
            <div className="flex items-start justify-between mb-4">
              <div>
                <h2 className="font-bold text-base" style={{ color: C.text }}>Reschedule Interview</h2>
                <p className="text-xs mt-0.5" style={{ color: C.muted }}>
                  R{rescheduleModal.roundNumber} · {ROUND_LABELS[rescheduleModal.interviewType] || rescheduleModal.interviewType}
                </p>
              </div>
              <button onClick={() => setRescheduleModal(null)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>
            <div className="mb-4">
              <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>New Date & Time</label>
              <input
                type="datetime-local"
                value={rescheduleSlot}
                onChange={e => setRescheduleSlot(e.target.value)}
                className="w-full px-3 py-2 rounded-xl border text-sm"
                style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
              />
            </div>
            <div className="flex gap-2">
              <Btn variant="primary" onClick={handleReschedule} disabled={!rescheduleSlot || rescheduling}>
                {rescheduling ? <><Spinner size={13} /> Rescheduling…</> : <><Calendar size={13} /> Confirm & Resend Invite</>}
              </Btn>
              <Btn variant="secondary" onClick={() => setRescheduleModal(null)}>Cancel</Btn>
            </div>
          </Card>
        </div>
      )}

      {/* Schedule Interview Modal */}
      {scheduleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6" style={{ maxHeight: "90vh", display: "flex", flexDirection: "column" }}>
            <div className="flex items-start justify-between mb-5 shrink-0">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>
                  {scheduleModal.confirmed ? "Interview Scheduled" : "Schedule Interview"}
                </h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>{scheduleModal.candidateName}</p>
              </div>
              <button onClick={() => { setScheduleModal(null); reload(); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            {scheduleModal.confirmed ? (
              /* ── Step 2: show invitation email that was sent ── */
              <div className="flex flex-col flex-1 overflow-hidden">
                <div className="flex items-center gap-2 px-3 py-2 rounded-xl mb-3 shrink-0"
                  style={{ background: `${C.success}10`, border: `1px solid ${C.success}33` }}>
                  <span style={{ color: C.success }}>✓</span>
                  <span className="text-xs font-semibold" style={{ color: C.success }}>Booking link sent — awaiting candidate to choose a time</span>
                </div>
                <div className="flex items-center gap-2 px-3 py-2 rounded-xl mb-3 shrink-0"
                  style={{ background: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
                  <Mail size={13} style={{ color: C.accent }} />
                  <span className="text-xs" style={{ color: C.muted }}>To:</span>
                  <span className="text-xs font-semibold" style={{ color: C.text }}>{scheduleModal.candidateEmail || "—"}</span>
                </div>
                <div className="overflow-y-auto flex-1 rounded-xl p-4" style={{ background: C.surface, border: `1px solid ${C.border}` }}>
                  <pre className="text-sm whitespace-pre-wrap leading-relaxed" style={{ color: C.text, fontFamily: "inherit" }}>
                    {scheduleModal.invitationEmail}
                  </pre>
                </div>
                <div className="flex gap-2 mt-4 shrink-0">
                  <Btn variant="primary" onClick={() => { setScheduleModal(null); reload(); }}>Done</Btn>
                  <Btn variant="secondary" onClick={async () => { try { await navigator.clipboard.writeText(scheduleModal.invitationEmail); } catch { const ta = document.createElement("textarea"); ta.value = scheduleModal.invitationEmail; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta); } }}>Copy email</Btn>
                </div>
              </div>
            ) : (
              /* ── Step 1: scheduling form ── */
              <>
                <div className="space-y-4">
                  {/* Interview type */}
                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Interview Type</label>
                    <div className="flex gap-2">
                      {INTERVIEW_ROUND_TYPES.map(t => (
                        <button key={t}
                          onClick={() => setScheduleForm(f => ({ ...f, interviewType: t }))}
                          className="flex-1 py-2 rounded-xl text-xs font-semibold border transition-colors"
                          style={{
                            borderColor: scheduleForm.interviewType === t ? C.accent : C.border,
                            backgroundColor: scheduleForm.interviewType === t ? `${C.accent}15` : "transparent",
                            color: scheduleForm.interviewType === t ? C.accent : C.muted,
                          }}>
                          {ROUND_LABELS[t]}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Interviewer</label>
                    <Select value={scheduleForm.interviewerId} onChange={e => setScheduleForm(f => ({ ...f, interviewerId: e.target.value }))}>
                      <option value="">Choose who will run this interview…</option>
                      {(interviewers || []).map(u => <option key={u.id} value={u.id}>{u.fullName}</option>)}
                    </Select>
                  </div>

                  <p className="text-xs leading-relaxed" style={{ color: C.muted }}>
                    The candidate will receive a link to choose a time from the shared <strong>Calendar</strong>.
                    Make sure you have published availability there first.
                  </p>

                  {/* Notes */}
                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Notes <span style={{ color: C.border }}>(optional)</span></label>
                    <textarea
                      rows={2}
                      placeholder="e.g. Focus on system design, bring portfolio..."
                      value={scheduleForm.notes}
                      onChange={e => setScheduleForm(f => ({ ...f, notes: e.target.value }))}
                      className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                      style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
                    />
                  </div>
                </div>

                <div className="flex gap-2 mt-5">
                  <Btn variant="primary" onClick={handleScheduleInterview} disabled={scheduling || !scheduleForm.interviewerId}>
                    {scheduling ? <><Spinner size={13} /> Sending…</> : <><Calendar size={13} /> Send Booking Link</>}
                  </Btn>
                  <Btn variant="secondary" onClick={() => setScheduleModal(null)}>Cancel</Btn>
                </div>
              </>
            )}
          </Card>
        </div>
      )}

      {scorecardModal && (
        <ScorecardModal title={`Scorecard · ${scorecardModal.title}`}
          criteria={(scorecardCriteria?.types?.[scorecardModal.interviewType] || []).map(k => [k, scorecardCriteria.labels[k] || k])}
          isPhone={scorecardModal.interviewType === "PHONE_SCREEN"}
          form={scorecardForm} setForm={setScorecardForm}
          onSubmit={submitScorecard} onCancel={() => setScorecardModal(null)}
          submitting={scorecardSaving} error={scorecardError} />
      )}

      {debriefModal && (
        <DebriefModal {...debriefModal} onClose={() => setDebriefModal(null)} />
      )}

      {rejectModal && (
        <RejectReasonModal candidateName={rejectModal.candidateName} form={rejectForm} setForm={setRejectForm}
          onConfirm={confirmReject} onCancel={() => setRejectModal(null)} />
      )}

      <OutreachModal outreach={outreach} onClose={() => setOutreach(null)} onSent={reload} />

      {/* Candidate History Modal */}
      {historyModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6 max-h-[85vh] flex flex-col">
            <div className="flex items-start justify-between mb-5 shrink-0">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Application History</h2>
                <p className="text-sm mt-0.5" style={{ color: C.muted }}>{historyModal.candidateName}</p>
              </div>
              <button onClick={() => setHistoryModal(null)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            <div className="overflow-y-auto flex-1 pr-1">
              {historyLoading && (
                <div className="flex items-center justify-center py-12">
                  <Spinner size={20} />
                </div>
              )}
              {!historyLoading && historyData && historyData.events.length === 0 && (
                <p className="text-sm text-center py-10" style={{ color: C.muted }}>No history found for this candidate.</p>
              )}
              {!historyLoading && historyData && historyData.events.length > 0 && (
                <div className="relative">
                  {/* Timeline line */}
                  <div className="absolute left-4 top-2 bottom-2 w-0.5" style={{ backgroundColor: C.border }} />
                  <div className="space-y-4">
                    {historyData.events.map((ev, idx) => {
                      const isStage = ev.type === "STAGE";
                      const isInterview = ev.type === "INTERVIEW";
                      const isNotification = ev.type === "NOTIFICATION";

                      const dotColor = isStage
                        ? ({ REJECTED: C.danger, HIRED: C.success }[ev.stage] || C.accent)
                        : isInterview ? "#f59e0b"
                        : C.success;

                      const ivStatusColor = { COMPLETED: C.success, CANCELLED: C.danger, CONFIRMED: "#3b82f6", PROPOSED: C.muted }[ev.interviewStatus] || C.muted;

                      return (
                        <div key={idx} className="flex gap-4 pl-2">
                          {/* Dot */}
                          <div className="w-5 h-5 rounded-full border-2 border-white flex items-center justify-center flex-shrink-0 mt-0.5 z-10"
                            style={{ backgroundColor: dotColor }}>
                            {isStage && <Activity size={9} color="#fff" />}
                            {isInterview && <Calendar size={9} color="#fff" />}
                            {isNotification && <Mail size={9} color="#fff" />}
                          </div>
                          {/* Content */}
                          <div className="flex-1 pb-1">
                            <div className="flex items-start justify-between gap-2">
                              <div>
                                {isStage && (
                                  <>
                                    <p className="text-xs font-bold" style={{ color: dotColor }}>{STAGE_LABELS[ev.stage] || ev.stage}</p>
                                    <p className="text-[10px]" style={{ color: C.muted }}>{ev.requisitionTitle}</p>
                                  </>
                                )}
                                {isInterview && (
                                  <>
                                    <p className="text-xs font-bold" style={{ color: "#f59e0b" }}>
                                      {ev.interviewType} Interview
                                      <span className="ml-1.5 px-1.5 py-0.5 rounded text-[9px] font-bold" style={{ backgroundColor: ivStatusColor + "20", color: ivStatusColor }}>
                                        {ev.interviewStatus}
                                      </span>
                                    </p>
                                    <p className="text-[10px]" style={{ color: C.muted }}>{ev.requisitionTitle}</p>
                                    {ev.confirmedSlot && (
                                      <p className="text-[10px]" style={{ color: C.muted }}>
                                        Scheduled: {new Date(ev.confirmedSlot).toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" })}
                                      </p>
                                    )}
                                  </>
                                )}
                                {isInterview && ev.verdict && (
                                  <>
                                    <p className="text-[10px] font-semibold" style={{ color: verdictColor(ev.verdict) }}>
                                      Feedback: {VERDICT_LABELS[ev.verdict]} · {ev.averageRating}/5
                                    </p>
                                    {ev.feedbackNotes && <p className="text-[10px] leading-snug" style={{ color: C.muted }}>{ev.feedbackNotes}</p>}
                                  </>
                                )}
                                {isNotification && (
                                  <>
                                    <p className="text-xs font-bold" style={{ color: C.success }}>Notification sent</p>
                                    <p className="text-[10px]" style={{ color: C.muted }}>via {ev.channel}</p>
                                  </>
                                )}
                              </div>
                              <p className="text-[10px] flex-shrink-0" style={{ color: C.muted }}>
                                {ev.timestamp ? new Date(ev.timestamp).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "—"}
                              </p>
                            </div>
                            {isStage && ev.agent && (
                              <p className="text-[10px] font-semibold mt-0.5" style={{ color: AGENT_META[ev.agent]?.color || C.muted }}>
                                {actorLabel(ev.agent)}
                              </p>
                            )}
                            {isInterview && ev.notes && (
                              <p className="text-[11px] mt-1 leading-relaxed" style={{ color: C.text }}>{ev.notes}</p>
                            )}
                            {isStage && ev.notes && (
                              <p className="text-[11px] mt-1 leading-relaxed" style={{ color: C.text }}>{ev.notes}</p>
                            )}
                            {isNotification && ev.message && (
                              <p className="text-[11px] mt-1 leading-relaxed line-clamp-3" style={{ color: C.text }}>{ev.message}</p>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>

            <div className="mt-4 pt-4 shrink-0" style={{ borderTop: `1px solid ${C.border}` }}>
              <Btn variant="secondary" onClick={() => setHistoryModal(null)}>Close</Btn>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// SOURCING AGENT
// ============================================================
const CSV_TEMPLATE = `fullName,email,headline,skills,yearsExperience,profileUrl,sourceChannel
John Doe,john@email.com,Senior Java Engineer,"Java,Spring Boot,AWS",6,https://linkedin.com/in/johndoe,LINKEDIN
Jane Smith,jane@email.com,Data Analyst,"SQL,Python,Power BI",3,,JOBSTREET`;

function ResumeSources() {
  const { data: sources, reload: reloadSources } = useApi("/sourcing/resume-sources");
  const [showAdd, setShowAdd] = useState(false);
  const [addForm, setAddForm] = useState({ name: "", url: "", sourceType: "CUSTOM" });
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState({}); // sourceId -> bool

  async function handleAdd(e) {
    e.preventDefault();
    setSaving(true);
    try {
      await apiFetch("/sourcing/resume-sources", { method: "POST", body: JSON.stringify(addForm) });
      setShowAdd(false);
      setAddForm({ name: "", url: "", sourceType: "CUSTOM" });
      reloadSources();
    } finally { setSaving(false); }
  }

  // Sync runs async on the backend, so instead of guessing a fixed delay before
  // refreshing (which can show a stale "0 added" if AI calls retry/take longer),
  // poll until each source's lastSyncAt actually moves past its pre-sync value.
  async function pollUntilSynced(ids, beforeMap, { maxAttempts = 20, intervalMs = 1000 } = {}) {
    for (let i = 0; i < maxAttempts; i++) {
      await new Promise(r => setTimeout(r, intervalMs));
      let latest;
      try {
        latest = await apiFetch("/sourcing/resume-sources");
      } catch {
        continue;
      }
      const allDone = ids.every(id => {
        const src = latest?.find(s => s.id === id);
        return src && src.lastSyncAt !== beforeMap[id];
      });
      if (allDone) return;
    }
  }

  async function syncOne(id) {
    const before = sources?.find(s => s.id === id)?.lastSyncAt;
    setSyncing(s => ({ ...s, [id]: true }));
    await apiFetch(`/sourcing/resume-sources/${id}/sync`, { method: "POST" });
    await pollUntilSynced([id], { [id]: before });
    setSyncing(s => ({ ...s, [id]: false }));
    reloadSources();
  }

  async function syncAll() {
    const activeSources = (sources || []).filter(s => s.active);
    const beforeMap = {};
    activeSources.forEach(s => { beforeMap[s.id] = s.lastSyncAt; });
    await apiFetch("/sourcing/resume-sources/sync-all", { method: "POST" });
    activeSources.forEach(s => setSyncing(prev => ({ ...prev, [s.id]: true })));
    await pollUntilSynced(activeSources.map(s => s.id), beforeMap);
    setSyncing({});
    reloadSources();
  }

  async function toggleActive(id) {
    await apiFetch(`/sourcing/resume-sources/${id}/toggle`, { method: "PATCH" });
    reloadSources();
  }

  async function deleteSource(id) {
    await apiFetch(`/sourcing/resume-sources/${id}`, { method: "DELETE" });
    reloadSources();
  }

  const TYPE_COLOR = { LINKEDIN: "#0A66C2", JOBSTREET: "#E8341C", CUSTOM: "#8B5CF6" };
  const TYPE_LABEL = { LINKEDIN: "LinkedIn", JOBSTREET: "JobStreet", CUSTOM: "Custom" };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-bold" style={{ color: C.text }}>Resume Sources</p>
          <p className="text-xs" style={{ color: C.muted }}>Configure job board integrations to auto-import candidates</p>
        </div>
        <div className="flex gap-2">
          <Btn variant="secondary" onClick={syncAll}><RefreshCw size={13} /> Sync All</Btn>
          <Btn variant="primary" onClick={() => setShowAdd(true)}><Plus size={13} /> Add Source</Btn>
        </div>
      </div>

      {(sources || []).length === 0 && (
        <Card className="p-8 text-center">
          <p className="text-sm" style={{ color: C.muted }}>No sources configured. Add a job board URL to start importing candidates.</p>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-3">
        {(sources || []).map(src => (
          <Card key={src.id} className="p-4 flex items-center gap-4" style={{ opacity: src.active ? 1 : 0.5 }}>
            <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: `${TYPE_COLOR[src.sourceType] || "#8B5CF6"}15` }}>
              <Globe size={18} style={{ color: TYPE_COLOR[src.sourceType] || "#8B5CF6" }} />
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{src.name}</p>
                <span className="text-xs px-2 py-0.5 rounded-full font-semibold flex-shrink-0"
                  style={{ backgroundColor: `${TYPE_COLOR[src.sourceType] || "#8B5CF6"}15`, color: TYPE_COLOR[src.sourceType] || "#8B5CF6" }}>
                  {TYPE_LABEL[src.sourceType] || src.sourceType}
                </span>
                {!src.active && <span className="text-xs px-2 py-0.5 rounded-full" style={{ backgroundColor: C.border, color: C.muted }}>Paused</span>}
              </div>
              <p className="text-xs truncate mt-0.5" style={{ color: C.muted }}>{src.url}</p>
              {src.lastSyncAt && (
                <p className="text-xs mt-0.5" style={{ color: C.muted }}>
                  Last sync: {new Date(src.lastSyncAt).toLocaleString()} ·{" "}
                  {src.lastSyncCount === -1
                    ? <span style={{ color: C.error }}>failed</span>
                    : <span style={{ color: C.success }}>{src.lastSyncCount} added</span>}
                </p>
              )}
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <Btn variant="secondary" onClick={() => syncOne(src.id)} disabled={syncing[src.id]}>
                {syncing[src.id] ? <Spinner size={13} /> : <RefreshCw size={13} />}
                {syncing[src.id] ? "Syncing…" : "Sync"}
              </Btn>
              <Btn variant="secondary" onClick={() => toggleActive(src.id)}>
                {src.active ? "Pause" : "Resume"}
              </Btn>
              <button onClick={() => deleteSource(src.id)} className="p-1.5 rounded-lg transition-colors hover:opacity-70"
                style={{ color: C.error }}>
                <X size={14} />
              </button>
            </div>
          </Card>
        ))}
      </div>

      {showAdd && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ backgroundColor: "rgba(0,0,0,0.4)" }}>
          <Card className="w-full max-w-md p-6 space-y-4">
            <div className="flex items-center justify-between">
              <p className="font-bold" style={{ color: C.text }}>Add Resume Source</p>
              <button onClick={() => setShowAdd(false)}><X size={16} style={{ color: C.muted }} /></button>
            </div>
            <form onSubmit={handleAdd} className="space-y-3">
              <div>
                <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Source Name</label>
                <input required className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
                  placeholder="e.g. JobStreet Malaysia"
                  value={addForm.name} onChange={e => setAddForm(f => ({ ...f, name: e.target.value }))} />
              </div>
              <div>
                <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Source URL</label>
                <input required className="w-full px-3 py-2 rounded-xl border text-sm"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
                  placeholder="http://localhost:8080/mock-jobboard/jobstreet/candidates"
                  value={addForm.url} onChange={e => setAddForm(f => ({ ...f, url: e.target.value }))} />
              </div>
              <div>
                <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Type</label>
                <div className="flex gap-2">
                  {["LINKEDIN","JOBSTREET","CUSTOM"].map(t => (
                    <button type="button" key={t} onClick={() => setAddForm(f => ({ ...f, sourceType: t }))}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold border"
                      style={{ borderColor: addForm.sourceType === t ? (TYPE_COLOR[t]||C.accent) : C.border, backgroundColor: addForm.sourceType === t ? `${TYPE_COLOR[t]||C.accent}15` : "transparent", color: addForm.sourceType === t ? (TYPE_COLOR[t]||C.accent) : C.muted }}>
                      {TYPE_LABEL[t]}
                    </button>
                  ))}
                </div>
              </div>
              <div className="flex gap-2 pt-1">
                <Btn type="submit" variant="primary" disabled={saving}>{saving ? <><Spinner size={13} /> Adding…</> : "Add Source"}</Btn>
                <Btn type="button" variant="secondary" onClick={() => setShowAdd(false)}>Cancel</Btn>
              </div>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

function SourcingView({ setActive }) {
  const { data: reqs } = useApi("/requisitions?status=OPEN");
  const [reqId, setReqId] = useState("ALL"); // "ALL" or a numeric requisition id
  const [sourcingTab, setSourcingTab] = useState("matches"); // "matches" | "sources"
  const [showForm, setShowForm] = useState(false);
  const [inputMode, setInputMode] = useState("manual");
  const [submitting, setSubmitting] = useState(false);
  const [csvResult, setCsvResult] = useState(null);

  // "all" = match against every open role; "selected" = match only the role picked in the dropdown above
  const [matchScope, setMatchScope] = useState("all");

  // Manual form state
  const [form, setForm] = useState({ fullName:"", email:"", headline:"", skills:"", resumeText:"", sourceChannel:"LINKEDIN", hasResume: true });

  // File upload state
  const [fileForm, setFileForm] = useState({ candidateName:"", email:"", sourceChannel:"LINKEDIN" });
  const [uploadFile, setUploadFile] = useState(null);
  const [parsing, setParsing] = useState(false);


  // Outreach draft modal state
  const [outreach, setOutreach] = useState(null); // null | { loading, matchId, candidateName, requisitionTitle, draft }
  const [copied, setCopied] = useState(false);
  const [sendingOutreach, setSendingOutreach] = useState(false);
  const [outreachSent, setOutreachSent] = useState(false);

  // Which match is currently being screened (shows inline spinner)
  const [screeningMatchId, setScreeningMatchId] = useState(null);

  // Expanded screening report per match: { [matchId]: "loading" | { full result } | undefined }
  const [screeningReports, setScreeningReports] = useState({});

  async function toggleScreeningReport(m) {
    const current = screeningReports[m.id];
    if (current && current !== "loading") {
      // collapse
      setScreeningReports(r => ({ ...r, [m.id]: undefined }));
      return;
    }
    setScreeningReports(r => ({ ...r, [m.id]: "loading" }));
    try {
      const data = await apiFetch(`/screening/results/${m.screeningResultId}`);
      setScreeningReports(r => ({ ...r, [m.id]: data }));
    } catch {
      setScreeningReports(r => ({ ...r, [m.id]: undefined }));
    }
  }

  // CSV import state
  const [csvFile, setCsvFile] = useState(null);
  const [submitError, setSubmitError] = useState(null);

  const isAllRoles = reqId === "ALL";
  const singleMatchesApi = useApi(!isAllRoles && reqId ? `/sourcing/requisition/${reqId}/matches` : null, [reqId]);
  const allMatchPaths = isAllRoles && reqs ? reqs.map(r => `/sourcing/requisition/${r.id}/matches`) : null;
  const allMatchesApi = useApiMulti(allMatchPaths, [isAllRoles, reqs?.length]);

  const matches = isAllRoles
    ? (allMatchesApi.data ? allMatchesApi.data.flat().sort((a, b) => (b.matchScore ?? -1) - (a.matchScore ?? -1)) : null)
    : singleMatchesApi.data;
  const loading = isAllRoles ? allMatchesApi.loading : singleMatchesApi.loading;
  const error = isAllRoles ? allMatchesApi.error : singleMatchesApi.error;
  const reload = isAllRoles ? allMatchesApi.reload : singleMatchesApi.reload;

  function closeModal() {
    setShowForm(false);
    setInputMode("manual");
    setForm({ fullName:"", email:"", headline:"", skills:"", resumeText:"", sourceChannel:"LINKEDIN", hasResume: true });
    setFileForm({ candidateName:"", email:"", sourceChannel:"LINKEDIN" });
    setUploadFile(null);
    setParsing(false);
    setCsvFile(null);
    setCsvResult(null);
    setSubmitError(null);
    setMatchScope("all");
  }

  async function handleFileSelect(file) {
    setUploadFile(file);
    if (!file) return;
    setParsing(true);
    try {
      const fd = new FormData();
      fd.append("resume", file);
      const result = await apiFetch("/sourcing/talent-pool/parse-resume", { method: "POST", body: fd });
      setFileForm(f => ({
        ...f,
        candidateName: result.name || f.candidateName,
        email: result.email || f.email,
      }));
    } catch (e) {
      // silent — user fills manually if parsing fails
    } finally {
      setParsing(false);
    }
  }

  async function handleDismiss(matchId) {
    await apiFetch(`/sourcing/matches/${matchId}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status: "DISMISSED" }),
    });
    reload();
  }

  async function handleSourcingShortlist(match) {
    await apiFetch(`/orchestrator/pipeline/${match.requisitionId}/candidates/${match.candidateId}/stage`, {
      method: "PATCH",
      body: JSON.stringify({ stage: "SHORTLISTED" }),
    });
    reload();
  }

  async function handleSourcingReject(screeningResultId) {
    await apiFetch(`/screening/${screeningResultId}/review`, {
      method: "POST",
      body: JSON.stringify({ decision: "REJECT", notes: "Rejected by recruiter in sourcing review" }),
    });
    reload();
  }

  async function handleScreenResume(match) {
    setScreeningMatchId(match.id);
    try {
      await apiFetch(`/sourcing/matches/${match.id}/screen`, { method: "POST" });
      reload();
    } catch (e) {
      // error shown via reload's error state
    } finally {
      setScreeningMatchId(null);
    }
  }

  async function handleDraftOutreach(match) {
    setOutreach({ loading: true, matchId: match.id, candidateName: match.candidateName, requisitionTitle: match.requisitionTitle });
    try {
      const result = await apiFetch(`/sourcing/matches/${match.id}/draft-outreach`, { method: "POST" });
      setOutreach({ loading: false, ...result });
      reload(); // refresh status badge (NEW → REVIEWED)
    } catch (e) {
      setOutreach(null);
    }
  }

  function handleCopy(text) {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  async function handleManualSubmit(e) {
    e.preventDefault();
    setSubmitting(true);
    setSubmitError(null);
    try {
      const targetRequisitionId = matchScope === "selected" && !isAllRoles ? reqId : null;
      await apiFetch("/sourcing/talent-pool", { method: "POST", body: JSON.stringify({ ...form, targetRequisitionId }) });
      closeModal();
      reload();
    } catch (e) {
      setSubmitError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleFileSubmit(e) {
    e.preventDefault();
    if (!uploadFile) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const fd = new FormData();
      fd.append("candidateName", fileForm.candidateName);
      if (fileForm.email) fd.append("candidateEmail", fileForm.email);
      fd.append("sourceChannel", fileForm.sourceChannel);
      if (matchScope === "selected" && reqId && !isAllRoles) fd.append("targetRequisitionId", reqId);
      fd.append("resume", uploadFile);
      await apiFetch("/sourcing/talent-pool/upload", { method: "POST", body: fd });
      closeModal();
      reload();
    } catch (e) {
      setSubmitError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCsvSubmit(e) {
    e.preventDefault();
    if (!csvFile) return;
    setSubmitting(true);
    try {
      const fd = new FormData();
      fd.append("file", csvFile);
      if (matchScope === "selected" && reqId && !isAllRoles) fd.append("targetRequisitionId", reqId);
      const result = await apiFetch("/sourcing/talent-pool/csv", { method: "POST", body: fd });
      setCsvResult(result);
      reload();
    } finally {
      setSubmitting(false);
    }
  }

  function downloadCsvTemplate() {
    const blob = new Blob([CSV_TEMPLATE], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = "talent_pool_template.csv"; a.click();
    URL.revokeObjectURL(url);
  }

  const TABS = [
    { id: "manual",   label: "Manual Entry" },
    { id: "file",     label: "Resume File" },
    { id: "csv",      label: "CSV Import" },
  ];

  return (
    <div>
      <TopBar title="Sourcing Agent"
        subtitle="Proactively matches the talent pool against open roles"
        action={
          <div className="flex gap-2">
            {sourcingTab === "matches" && reqs && (
              <Select value={reqId} onChange={e => setReqId(e.target.value === "ALL" ? "ALL" : Number(e.target.value))}>
                <option value="ALL">All Roles</option>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
            {sourcingTab === "matches" && <Btn onClick={() => setShowForm(true)}><Search size={14} /> Add to Talent Pool</Btn>}
          </div>
        }
      />

      {/* Tab switcher */}
      <div className="flex gap-1 mb-4 p-1 rounded-xl w-fit" style={{ backgroundColor: C.surface }}>
        {[["matches","Talent Matches"],["sources","Resume Sources"]].map(([id, label]) => (
          <button key={id} onClick={() => setSourcingTab(id)}
            className="px-4 py-1.5 rounded-lg text-sm font-semibold transition-colors"
            style={{ backgroundColor: sourcingTab === id ? C.accent : "transparent", color: sourcingTab === id ? "#fff" : C.muted }}>
            {label}
          </button>
        ))}
      </div>

      {sourcingTab === "sources" && <ResumeSources />}

      {sourcingTab === "matches" && error && <ErrorBanner message={error} onRetry={reload} />}

      {sourcingTab === "matches" && loading && <div className="space-y-3">{[1,2,3].map(i => <LoadingCard key={i} />)}</div>}

      {sourcingTab === "matches" && !loading && (
        <div className="space-y-3">
          {(matches || []).length === 0 && (
            <Card className="p-12 text-center">
              <p style={{ color: C.muted }}>No matches yet for this role. Add candidates to the talent pool.</p>
            </Card>
          )}
          {(matches || []).map((m) => (
            <Card key={m.id} className="p-5 flex items-start gap-4" style={m.status === "DISMISSED" ? { opacity: 0.45 } : {}}>
              <div className="text-center shrink-0">
                <ScoreRing score={m.matchScore} />
                <p className="text-[10px] mt-0.5" style={{ color: C.muted }}>profile match</p>
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-start justify-between gap-2 mb-1">
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-semibold" style={{ color: C.text }}>{m.candidateName}</p>
                      {/* Only claim HIGH FIT when the deeper screening doesn't contradict it */}
                      {m.matchScore >= 70 && (!m.screeningRecommendation || m.screeningRecommendation === "ADVANCE") &&
                        <Badge color={C.warning}>HIGH FIT</Badge>}
                      <Badge color={m.status === "DISMISSED" ? C.muted : C.accent}>{m.status}</Badge>
                    </div>
                    {m.candidateHeadline && (
                      <p className="text-xs mt-0.5" style={{ color: C.muted }}>{m.candidateHeadline}</p>
                    )}
                    {isAllRoles && m.requisitionTitle && (
                      <p className="text-[10px] mt-0.5 font-medium" style={{ color: C.accent }}>{m.requisitionTitle}</p>
                    )}
                  </div>
                </div>
                <p className="text-sm mt-2" style={{ color: C.text }}>{m.matchRationale}</p>

                {/* Screening result chip + view toggle */}
                {m.screeningRecommendation && (() => {
                  const rec = m.screeningRecommendation;
                  const chipColor = rec === "ADVANCE" ? C.success : rec === "REJECT" ? C.danger : C.warning;
                  const label = rec === "ADVANCE" ? "Advance" : rec === "REJECT" ? "Not a fit" : "Needs Review";
                  const report = screeningReports[m.id];
                  const expanded = report && report !== "loading";
                  return (
                    <>
                      <div className="flex items-center gap-2 mt-2">
                        <span className="text-xs font-semibold" style={{ color: C.muted }}>Resume screening:</span>
                        <ScoreRing score={m.screeningScore} size={28} />
                        <Badge color={chipColor}>{label}</Badge>
                        <button
                          onClick={() => toggleScreeningReport(m)}
                          className="text-xs flex items-center gap-1 px-2 py-0.5 rounded-lg border transition-colors"
                          style={{ color: C.accent, borderColor: C.accent + "55", background: expanded ? C.accent + "15" : "transparent" }}
                        >
                          {report === "loading" ? <><Spinner size={10} /> Loading…</> : expanded ? <><ChevronUp size={11} /> Hide report</> : <><ChevronDown size={11} /> View report</>}
                        </button>
                      </div>

                      {/* Inline expanded screening report */}
                      {expanded && (
                        <div className="mt-3 rounded-xl p-4 space-y-3" style={{ background: C.surface, border: `1px solid ${C.border}` }}>
                          {/* Score breakdown */}
                          <div className="grid grid-cols-3 gap-3">
                            {[
                              { label: "Skills", score: report.skillsScore },
                              { label: "Experience", score: report.experienceScore },
                              { label: "Culture Fit", score: report.cultureFitScore },
                            ].map(({ label, score }) => (
                              <div key={label} className="text-center">
                                <ScoreRing score={score} size={36} />
                                <p className="text-xs mt-1" style={{ color: C.muted }}>{label}</p>
                              </div>
                            ))}
                          </div>

                          {/* Rationale */}
                          {report.rationale && (
                            <p className="text-xs leading-relaxed" style={{ color: C.text }}>{report.rationale}</p>
                          )}

                          {/* Strengths & Gaps */}
                          {(() => {
                            // Split on newlines or bullet markers; fall back to sentence
                            // boundaries. Never split on commas — that fragments prose.
                            const toList = v => {
                              if (!v) return [];
                              if (Array.isArray(v)) return v;
                              if (typeof v !== "string") return [];
                              const strip = s => s.trim().replace(/^[•\-–]\s*/, "");
                              const lines = v.split(/\n+/).map(strip).filter(Boolean);
                              if (lines.length > 1) return lines;
                              return v.split(/(?<=\.)\s+(?=[A-Z•])/).map(strip).filter(Boolean);
                            };
                            const strengths = toList(report.strengths);
                            const gaps = toList(report.gaps);
                            return (strengths.length > 0 || gaps.length > 0) && (
                              <div className="grid grid-cols-2 gap-3">
                                {strengths.length > 0 && (
                                  <div>
                                    <p className="text-xs font-semibold mb-1" style={{ color: C.success }}>Strengths</p>
                                    <ul className="space-y-0.5">
                                      {strengths.map((s, i) => (
                                        <li key={i} className="text-xs flex gap-1" style={{ color: C.text }}>
                                          <span style={{ color: C.success }}>✓</span> {s}
                                        </li>
                                      ))}
                                    </ul>
                                  </div>
                                )}
                                {gaps.length > 0 && (
                                  <div>
                                    <p className="text-xs font-semibold mb-1" style={{ color: C.danger }}>Gaps</p>
                                    <ul className="space-y-0.5">
                                      {gaps.map((g, i) => (
                                        <li key={i} className="text-xs flex gap-1" style={{ color: C.text }}>
                                          <span style={{ color: C.danger }}>✗</span> {g}
                                        </li>
                                      ))}
                                    </ul>
                                  </div>
                                )}
                              </div>
                            );
                          })()}
                        </div>
                      )}
                    </>
                  );
                })()}

                <div className="flex gap-2 mt-3">
                  {m.status === "DISMISSED" ? (
                    <span className="text-xs px-2 py-1 rounded-lg" style={{ color: C.muted }}>Dismissed — no further action</span>
                  ) : screeningMatchId === m.id ? (
                    <Btn variant="secondary" disabled><Spinner size={13} /> Screening…</Btn>
                  ) : m.pipelineStage === "SHORTLISTED" ? (() => {
                    const isApplied = m.status === "APPLIED" || m.sourceChannel === "APPLIED";
                    const outreachDone = m.status === "REVIEWED" || m.status === "ADVANCED";
                    if (isApplied) {
                      // Inbound applicant — skip outreach, go straight to interview
                      return (
                        <Btn variant="primary" onClick={() => setActive("pipeline")}>
                          <Calendar size={13} /> Schedule Interview
                        </Btn>
                      );
                    }
                    // Proactive — outreach first, interview unlocks after
                    return (<>
                      {!outreachDone && (
                        <Btn variant="primary" onClick={() => handleDraftOutreach(m)}>
                          <Mail size={13} /> Outreach
                        </Btn>
                      )}
                      {outreachDone && (
                        <Btn variant="primary" onClick={() => setActive("pipeline")}>
                          <Calendar size={13} /> Schedule Interview
                        </Btn>
                      )}
                      {!outreachDone && (
                        <Btn variant="secondary" disabled title="Draft outreach first">
                          <Calendar size={13} /> Schedule Interview
                        </Btn>
                      )}
                    </>);
                  })() : ["INTERVIEW_SCHEDULED","OFFER","HIRED"].includes(m.pipelineStage) ? (
                    <span className="text-xs font-semibold px-2 py-1 rounded-lg" style={{ color: C.success, backgroundColor: `${C.success}15` }}>
                      {m.pipelineStage === "INTERVIEW_SCHEDULED" ? "Interview scheduled" : m.pipelineStage === "OFFER" ? "Offer extended" : "Hired"}
                    </span>
                  ) : m.pipelineStage === "SCREENED" ? (() => {
                    const rec = m.screeningRecommendation;
                    return (
                      <div className="flex items-center gap-1.5 flex-wrap">
                        <span className="text-xs font-semibold px-2 py-1 rounded-lg" style={{ color: C.warning, backgroundColor: `${C.warning}15` }}>
                          AI: {rec === "REJECT" ? "Not a fit" : rec === "ADVANCE" ? "Advance" : "Needs review"}
                        </span>
                        <button
                          onClick={() => handleSourcingShortlist(m)}
                          className="text-[11px] font-semibold px-2 py-1 rounded-lg text-white"
                          style={{ backgroundColor: C.success }}>
                          {rec === "REJECT" ? "Override & Shortlist" : "Shortlist"}
                        </button>
                        {(rec === "REJECT" || rec === "REVIEW") && m.screeningResultId && (
                          <button
                            onClick={() => handleSourcingReject(m.screeningResultId)}
                            className="text-[11px] font-semibold px-2 py-1 rounded-lg"
                            style={{ color: C.danger, border: `1px solid ${C.danger}55`, backgroundColor: `${C.danger}08` }}>
                            Reject
                          </button>
                        )}
                      </div>
                    );
                  })() : null}
                  {m.status !== "DISMISSED" && (
                    <Btn variant="secondary" onClick={() => handleDismiss(m.id)}>Dismiss</Btn>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {outreach && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6">
            <div className="flex items-start justify-between mb-4">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Draft Outreach</h2>
                {outreach.candidateName && (
                  <p className="text-sm mt-0.5" style={{ color: C.muted }}>
                    {outreach.candidateName} · {outreach.requisitionTitle}
                  </p>
                )}
              </div>
              <button onClick={() => { setOutreach(null); setCopied(false); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            {outreach.loading ? (
              <div className="flex flex-col items-center justify-center py-12 gap-3">
                <Spinner size={24} />
                <p className="text-sm" style={{ color: C.muted }}>Crafting a personalized message…</p>
              </div>
            ) : (
              <div className="space-y-4">
                {/* Candidate email */}
                {outreach.candidateEmail && (
                  <div className="flex items-center gap-3 px-4 py-2.5 rounded-xl"
                    style={{ backgroundColor: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
                    <Mail size={13} style={{ color: C.accent }} />
                    <div className="flex-1 min-w-0">
                      <p className="text-[10px] font-bold uppercase tracking-wide" style={{ color: C.muted }}>Send to</p>
                      <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{outreach.candidateEmail}</p>
                    </div>
                    <button onClick={() => { navigator.clipboard.writeText(outreach.candidateEmail); setCopied("email"); setTimeout(() => setCopied(false), 2000); }}
                      className="text-[10px] font-semibold px-2 py-1 rounded-lg border shrink-0 transition-colors"
                      style={{ borderColor: copied === "email" ? C.success : C.border, color: copied === "email" ? C.success : C.muted }}>
                      {copied === "email" ? "✓ Copied!" : "Copy"}
                    </button>
                  </div>
                )}
                <div>
                  <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                    AI-generated draft — edit before sending
                  </label>
                  <textarea
                    rows={7}
                    className="w-full text-sm px-4 py-3 rounded-xl border outline-none resize-none leading-relaxed"
                    style={{ borderColor: C.border, color: C.text }}
                    value={outreach.draft}
                    onChange={e => setOutreach(o => ({ ...o, draft: e.target.value }))}
                  />
                </div>
                <div className="flex gap-2">
                  <Btn variant="primary" className="flex-1 justify-center"
                    disabled={sendingOutreach || outreachSent}
                    onClick={async () => {
                      setSendingOutreach(true);
                      try {
                        await apiFetch(`/sourcing/matches/${outreach.matchId}/send-outreach`, {
                          method: "POST",
                          body: JSON.stringify({ emailBody: outreach.draft }),
                        });
                        setOutreachSent(true);
                        reload();
                        setTimeout(() => { setOutreach(null); setCopied(false); setOutreachSent(false); }, 1800);
                      } catch (err) {
                        alert("Failed to send: " + err.message);
                      } finally {
                        setSendingOutreach(false);
                      }
                    }}>
                    {outreachSent ? <CheckCircle2 size={14} /> : sendingOutreach ? <Spinner size={14} /> : <Mail size={14} />}
                    {outreachSent ? "Sent!" : sendingOutreach ? "Sending…" : "Send Email"}
                  </Btn>
                  <Btn variant="secondary"
                    onClick={() => { navigator.clipboard.writeText(outreach.draft); setCopied("msg"); setTimeout(() => setCopied(false), 2000); }}>
                    {copied === "msg" ? "✓ Copied" : "Copy"}
                  </Btn>
                  <Btn variant="secondary" onClick={() => { setOutreach(null); setCopied(false); }}>
                    Close
                  </Btn>
                </div>
              </div>
            )}
          </Card>
        </div>
      )}

      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-bold text-lg" style={{ color: C.text }}>Add to Talent Pool</h2>
              <button onClick={closeModal}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>

            {/* Tab switcher */}
            <div className="flex gap-1 p-1 rounded-xl mb-5" style={{ background: C.surface }}>
              {TABS.map(({ id, label }) => (
                <button key={id} onClick={() => { setInputMode(id); setCsvResult(null); }}
                  className="flex-1 text-xs font-semibold py-1.5 px-2 rounded-lg transition-all"
                  style={{
                    background: inputMode === id ? "#fff" : "transparent",
                    color: inputMode === id ? C.accent : C.muted,
                    boxShadow: inputMode === id ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
                  }}>
                  {label}
                </button>
              ))}
            </div>

            {/* Match scope toggle */}
            <div className="mb-5">
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Match against</label>
              <div className="flex gap-1 p-1 rounded-xl" style={{ background: C.surface }}>
                <button type="button" onClick={() => setMatchScope("all")}
                  className="flex-1 text-xs font-semibold py-1.5 px-2 rounded-lg transition-all"
                  style={{
                    background: matchScope === "all" ? "#fff" : "transparent",
                    color: matchScope === "all" ? C.accent : C.muted,
                    boxShadow: matchScope === "all" ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
                  }}>
                  All Open Roles
                </button>
                <button type="button" disabled={isAllRoles} onClick={() => setMatchScope("selected")}
                  className="flex-1 text-xs font-semibold py-1.5 px-2 rounded-lg transition-all"
                  style={{
                    background: matchScope === "selected" ? "#fff" : "transparent",
                    color: matchScope === "selected" ? C.accent : C.muted,
                    boxShadow: matchScope === "selected" ? "0 1px 3px rgba(0,0,0,0.1)" : "none",
                    opacity: isAllRoles ? 0.5 : 1,
                    cursor: isAllRoles ? "not-allowed" : "pointer",
                  }}>
                  This Role Only
                </button>
              </div>
              <p className="text-xs mt-1.5" style={{ color: C.muted }}>
                {isAllRoles
                  ? 'Select a specific role above (instead of "All Roles") to scope matching to just that role.'
                  : matchScope === "selected" && reqs?.find(r => r.id === reqId)
                  ? `Candidate will be matched only against "${reqs.find(r => r.id === reqId).title}".`
                  : "Candidate will be matched against every currently open role."}
              </p>
            </div>

            {/* Manual Entry */}
            {inputMode === "manual" && (
              <form onSubmit={handleManualSubmit} className="space-y-3">
                <Input label="Full Name" required value={form.fullName}
                  onChange={e => setForm(f => ({...f, fullName: e.target.value}))} />
                <Input label="Email" type="email" value={form.email}
                  onChange={e => setForm(f => ({...f, email: e.target.value}))} />
                <Input label="Headline" placeholder="e.g., Senior Java Developer, 7 yrs"
                  value={form.headline}
                  onChange={e => setForm(f => ({...f, headline: e.target.value}))} />
                <Input label="Skills (comma-separated)" placeholder="Java, Spring Boot, AWS"
                  value={form.skills}
                  onChange={e => setForm(f => ({...f, skills: e.target.value}))} />
                <div>
                  <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Source Channel</label>
                  <Select value={form.sourceChannel} onChange={e => setForm(f => ({...f, sourceChannel: e.target.value}))}>
                    {["LINKEDIN","JOBSTREET","MANUAL","APPLIED"].map(s => <option key={s} value={s}>{s === "APPLIED" ? "Applied (Inbound)" : s}</option>)}
                  </Select>
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Resume / Profile Summary</label>
                  <textarea rows={4} className="w-full text-sm px-4 py-2.5 rounded-xl border outline-none resize-none"
                    style={{ borderColor: C.border }}
                    value={form.resumeText}
                    onChange={e => setForm(f => ({...f, resumeText: e.target.value}))}
                    placeholder="Background, experience, key achievements..." />
                </div>
                {submitError && <p className="text-xs font-semibold px-3 py-2 rounded-lg" style={{ color: C.danger, backgroundColor: `${C.danger}10` }}>{submitError}</p>}
                <Btn type="submit" variant="primary" className="w-full justify-center py-2.5" disabled={submitting}>
                  {submitting ? <Spinner size={14} /> : <Sparkles size={14} />}
                  Add &amp; Auto-Match
                </Btn>
              </form>
            )}

            {/* Resume File Upload */}
            {inputMode === "file" && (
              <form onSubmit={handleFileSubmit} className="space-y-3">
                <div>
                  <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Resume File (PDF or TXT)</label>
                  <label className="flex flex-col items-center justify-center gap-2 w-full py-6 rounded-xl border-2 border-dashed cursor-pointer transition-colors"
                    style={{ borderColor: uploadFile ? C.accent : C.border, background: uploadFile ? "#E6F3FB" : "transparent" }}>
                    <UploadCloud size={22} style={{ color: uploadFile ? C.accent : C.muted }} />
                    <span className="text-xs font-medium" style={{ color: uploadFile ? C.accent : C.muted }}>
                      {uploadFile ? uploadFile.name : "Click to select PDF or TXT file"}
                    </span>
                    <input type="file" accept=".pdf,.txt" className="hidden"
                      onChange={e => handleFileSelect(e.target.files[0] || null)} />
                  </label>
                </div>
                <div className="relative">
                  <Input label="Full Name" required value={fileForm.candidateName}
                    placeholder={parsing ? "Extracting from file…" : ""}
                    onChange={e => setFileForm(f => ({...f, candidateName: e.target.value}))} />
                  {parsing && (
                    <span className="absolute right-3 top-8">
                      <Spinner size={12} />
                    </span>
                  )}
                </div>
                <div className="relative">
                  <Input label="Email" type="email" value={fileForm.email}
                    placeholder={parsing ? "Extracting from file…" : ""}
                    onChange={e => setFileForm(f => ({...f, email: e.target.value}))} />
                  {parsing && (
                    <span className="absolute right-3 top-8">
                      <Spinner size={12} />
                    </span>
                  )}
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Source Channel</label>
                  <Select value={fileForm.sourceChannel} onChange={e => setFileForm(f => ({...f, sourceChannel: e.target.value}))}>
                    {["LINKEDIN","JOBSTREET","MANUAL","APPLIED"].map(s => <option key={s} value={s}>{s === "APPLIED" ? "Applied (Inbound)" : s}</option>)}
                  </Select>
                </div>
                {submitError && <p className="text-xs font-semibold px-3 py-2 rounded-lg" style={{ color: C.danger, backgroundColor: `${C.danger}10` }}>{submitError}</p>}
                <Btn type="submit" variant="primary" className="w-full justify-center py-2.5"
                  disabled={submitting || !uploadFile || !fileForm.candidateName || parsing}>
                  {submitting ? <Spinner size={14} /> : <Sparkles size={14} />}
                  Upload &amp; Auto-Match
                </Btn>
              </form>
            )}

            {/* CSV Bulk Import */}
            {inputMode === "csv" && (
              <form onSubmit={handleCsvSubmit} className="space-y-3">
                {csvResult ? (
                  <div className="space-y-3">
                    <div className="p-4 rounded-xl" style={{ background: csvResult.failureCount === 0 ? "#ECFDF5" : "#FEF3C7" }}>
                      <p className="font-semibold text-sm" style={{ color: csvResult.failureCount === 0 ? C.success : C.warning }}>
                        {csvResult.successCount} of {csvResult.totalRows} candidates imported
                      </p>
                      {csvResult.failureCount > 0 && (
                        <p className="text-xs mt-1" style={{ color: C.muted }}>{csvResult.failureCount} rows failed</p>
                      )}
                    </div>
                    {csvResult.errors.length > 0 && (
                      <div className="space-y-1 max-h-32 overflow-y-auto">
                        {csvResult.errors.map((err, i) => (
                          <p key={i} className="text-xs px-2 py-1 rounded" style={{ color: C.danger, background: "#FEF2F2" }}>{err}</p>
                        ))}
                      </div>
                    )}
                    <Btn variant="secondary" className="w-full justify-center" onClick={() => { setCsvFile(null); setCsvResult(null); }}>
                      Import Another File
                    </Btn>
                    <Btn variant="primary" className="w-full justify-center" onClick={closeModal}>
                      Done
                    </Btn>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between">
                      <label className="text-xs font-semibold" style={{ color: C.muted }}>CSV File</label>
                      <button type="button" onClick={downloadCsvTemplate}
                        className="text-xs font-medium underline" style={{ color: C.accent }}>
                        Download template
                      </button>
                    </div>
                    <label className="flex flex-col items-center justify-center gap-2 w-full py-6 rounded-xl border-2 border-dashed cursor-pointer transition-colors"
                      style={{ borderColor: csvFile ? C.accent : C.border, background: csvFile ? "#E6F3FB" : "transparent" }}>
                      <UploadCloud size={22} style={{ color: csvFile ? C.accent : C.muted }} />
                      <span className="text-xs font-medium" style={{ color: csvFile ? C.accent : C.muted }}>
                        {csvFile ? csvFile.name : "Click to select CSV file"}
                      </span>
                      <input type="file" accept=".csv" className="hidden"
                        onChange={e => setCsvFile(e.target.files[0] || null)} />
                    </label>
                    <p className="text-xs" style={{ color: C.muted }}>
                      Columns: fullName, email, headline, skills, yearsExperience, profileUrl, sourceChannel
                    </p>
                    <Btn type="submit" variant="primary" className="w-full justify-center py-2.5"
                      disabled={submitting || !csvFile}>
                      {submitting ? <Spinner size={14} /> : <Sparkles size={14} />}
                      {submitting ? "Importing & Matching…" : "Import & Auto-Match All"}
                    </Btn>
                  </>
                )}
              </form>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// SHARED OUTREACH MODAL
// Used by Screening page, Applicants panel, and Pipeline board/list.
// Props:
//   outreach: null | { loading, matchId, candidateName, requisitionTitle, draft, candidateEmail }
//   onClose: () => void
//   onSent: () => void  (called after email is sent, so parent can reload)
// ============================================================
function OutreachModal({ outreach, onClose, onSent }) {
  const [draft, setDraft] = useState(outreach?.draft || "");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [copied, setCopied] = useState(false);

  // Sync draft text when outreach prop changes (e.g. after loading completes)
  useEffect(() => { setDraft(outreach?.draft || ""); }, [outreach?.draft]);

  if (!outreach) return null;

  async function send() {
    setSending(true);
    try {
      await apiFetch(`/sourcing/matches/${outreach.matchId}/send-outreach`, {
        method: "POST",
        body: JSON.stringify({ emailBody: draft }),
      });
      setSent(true);
      onSent?.();
      setTimeout(() => { onClose(); setSent(false); }, 1800);
    } catch (err) {
      alert("Failed to send: " + err.message);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-lg p-6">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="font-bold text-lg" style={{ color: C.text }}>Draft Outreach</h2>
            {outreach.candidateName && (
              <p className="text-sm mt-0.5" style={{ color: C.muted }}>
                {outreach.candidateName}{outreach.requisitionTitle ? ` · ${outreach.requisitionTitle}` : ""}
              </p>
            )}
          </div>
          <button onClick={onClose}
            className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
            <X size={16} style={{ color: C.muted }} />
          </button>
        </div>

        {outreach.loading ? (
          <div className="flex flex-col items-center justify-center py-12 gap-3">
            <Spinner size={24} />
            <p className="text-sm" style={{ color: C.muted }}>Crafting a personalised message…</p>
          </div>
        ) : (
          <div className="space-y-4">
            {outreach.candidateEmail && (
              <div className="flex items-center gap-3 px-4 py-2.5 rounded-xl"
                style={{ backgroundColor: `${C.accent}08`, border: `1px solid ${C.accent}22` }}>
                <Mail size={13} style={{ color: C.accent }} />
                <div className="flex-1 min-w-0">
                  <p className="text-[10px] font-bold uppercase tracking-wide" style={{ color: C.muted }}>Send to</p>
                  <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{outreach.candidateEmail}</p>
                </div>
                <button onClick={() => { navigator.clipboard.writeText(outreach.candidateEmail); setCopied("email"); setTimeout(() => setCopied(false), 2000); }}
                  className="text-[10px] font-semibold px-2 py-1 rounded-lg border shrink-0"
                  style={{ borderColor: copied === "email" ? C.success : C.border, color: copied === "email" ? C.success : C.muted }}>
                  {copied === "email" ? "✓ Copied!" : "Copy"}
                </button>
              </div>
            )}
            <div>
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                AI-generated draft — edit before sending
              </label>
              <textarea rows={7}
                className="w-full text-sm px-4 py-3 rounded-xl border outline-none resize-none leading-relaxed"
                style={{ borderColor: C.border, color: C.text }}
                value={draft}
                onChange={e => setDraft(e.target.value)}
                disabled={sent}
              />
            </div>
            <div className="flex gap-2">
              <Btn variant="primary" className="flex-1 justify-center" disabled={sending || sent} onClick={send}>
                {sent ? <CheckCircle2 size={14} /> : sending ? <Spinner size={14} /> : <Mail size={14} />}
                {sent ? "Sent!" : sending ? "Sending…" : "Send Email"}
              </Btn>
              <Btn variant="secondary"
                onClick={() => { navigator.clipboard.writeText(draft); setCopied("msg"); setTimeout(() => setCopied(false), 2000); }}>
                {copied === "msg" ? "✓ Copied" : "Copy"}
              </Btn>
              <Btn variant="secondary" onClick={onClose}>Close</Btn>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}

// ============================================================
// SCREENING AGENT
// ============================================================
function ScreeningDetailModal({ result, onClose, onDecision }) {
  const [decision, setDecision] = useState("");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (!result) return null;

  async function handleDecision(d) {
    setSubmitting(true);
    try {
      await onDecision(result.id, d, notes);
      onClose();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-2xl max-h-[88vh] overflow-y-auto">
        <div className="px-6 py-5 border-b flex items-start justify-between sticky top-0 bg-white rounded-t-2xl z-10"
          style={{ borderColor: C.border }}>
          <div>
            <h2 className="text-lg font-bold" style={{ color: C.text }}>{result.candidateName}</h2>
            <p className="text-sm" style={{ color: C.muted }}>{result.requisitionTitle}</p>
          </div>
          <button onClick={onClose}
            className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
            <X size={16} />
          </button>
        </div>

        <div className="p-6 space-y-5">
          {/* Score breakdown */}
          <div className="grid grid-cols-4 gap-3">
            {[
              { label: "Overall",      value: result.overallScore },
              { label: "Skills",       value: result.skillsScore },
              { label: "Experience",   value: result.experienceScore },
              { label: "Culture Fit",  value: result.cultureFitScore },
            ].map((s) => (
              <div key={s.label} className="text-center p-3 rounded-xl border" style={{ borderColor: C.border }}>
                <ScoreRing score={s.value} size={48} />
                <p className="text-xs mt-2 font-medium" style={{ color: C.muted }}>{s.label}</p>
              </div>
            ))}
          </div>

          {/* Strengths */}
          <div>
            <p className="text-xs font-bold uppercase tracking-wide mb-2" style={{ color: C.success }}>Strengths</p>
            <div className="space-y-1.5">
              {(result.strengths || "").split("\n").filter(Boolean).map((line, i) => (
                <div key={i} className="flex items-start gap-2 text-sm" style={{ color: C.text }}>
                  <CheckCircle2 size={14} className="mt-0.5 shrink-0" style={{ color: C.success }} />
                  {line}
                </div>
              ))}
            </div>
          </div>

          {/* Gaps */}
          <div>
            <p className="text-xs font-bold uppercase tracking-wide mb-2" style={{ color: C.danger }}>Gaps</p>
            <div className="space-y-1.5">
              {(result.gaps || "").split("\n").filter(Boolean).map((line, i) => (
                <div key={i} className="flex items-start gap-2 text-sm" style={{ color: C.text }}>
                  <AlertCircle size={14} className="mt-0.5 shrink-0" style={{ color: C.danger }} />
                  {line}
                </div>
              ))}
            </div>
          </div>

          {/* Rationale */}
          <div>
            <p className="text-xs font-bold uppercase tracking-wide mb-2" style={{ color: C.muted }}>AI Rationale</p>
            <p className="text-sm leading-relaxed p-3 rounded-xl" style={{ color: C.text, backgroundColor: C.bg }}>
              {result.rationale}
            </p>
          </div>

          {/* Human-in-the-loop decision */}
          {result.isEdgeCase && !result.reviewerDecision && (
            <div className="rounded-2xl border-2 p-5"
              style={{ borderColor: C.warning, backgroundColor: "#FFFBEB" }}>
              <p className="text-sm font-bold mb-1 flex items-center gap-2" style={{ color: C.text }}>
                <AlertCircle size={16} style={{ color: C.warning }} /> Human Decision Required
              </p>
              <p className="text-xs mb-4" style={{ color: C.muted }}>
                This candidate was flagged as an edge case. Your decision will update the pipeline stage.
              </p>
              <div className="mb-3">
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Notes (optional)</label>
                <textarea rows={2} className="w-full text-sm px-3 py-2 rounded-xl border outline-none resize-none"
                  style={{ borderColor: C.border }}
                  value={notes} onChange={e => setNotes(e.target.value)}
                  placeholder="Add your reasoning..." />
              </div>
              <div className="flex gap-2">
                <Btn variant="success" className="flex-1 justify-center"
                  disabled={submitting} onClick={() => handleDecision("ADVANCE")}>
                  {submitting ? <Spinner size={13} /> : <CheckCircle2 size={13} />} Advance
                </Btn>
                <Btn variant="danger" className="flex-1 justify-center"
                  disabled={submitting} onClick={() => handleDecision("REJECT")}>
                  <XCircle size={13} /> Reject
                </Btn>
              </div>
            </div>
          )}

          {result.rejectionReason && !result.reviewerNotes && (
            <div className="p-4 rounded-2xl" style={{ backgroundColor: `${C.danger}08`, border: `1px solid ${C.danger}33` }}>
              <p className="text-sm font-semibold" style={{ color: C.danger }}>Rejection reason</p>
              <p className="text-xs mt-1" style={{ color: C.text }}>{result.rejectionReason}</p>
            </div>
          )}

          {result.reviewerDecision && (
            <div className="p-4 rounded-2xl"
              style={result.reviewerDecision === "REJECT"
                ? { backgroundColor: `${C.danger}08`, border: `1px solid ${C.danger}33` }
                : { backgroundColor: "#F0FDF4", border: "1px solid #A7F3D0" }}>
              <p className="text-sm font-semibold" style={{ color: result.reviewerDecision === "REJECT" ? C.danger : C.success }}>
                {result.reviewerDecision === "REJECT" ? "✗" : "✓"} Human decision recorded: {result.reviewerDecision}
              </p>
              {result.reviewerNotes && (
                <p className="text-xs mt-1" style={{ color: C.muted }}>{result.reviewerNotes}</p>
              )}
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

function ScreeningView() {
  const { data: reqs } = useApi("/requisitions?status=OPEN");
  const [reqId, setReqId] = useState("ALL"); // "ALL" or a numeric requisition id
  const [selected, setSelected] = useState(null);
  const [showUpload, setShowUpload] = useState(false);
  const [uploadForm, setUploadForm] = useState({ candidateName:"", candidateEmail:"" });
  const [uploadFile, setUploadFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [shortlisting, setShortlisting] = useState(null);
  const [rejecting, setRejecting] = useState(null);
  const [sendingEmail, setSendingEmail] = useState(null);
  const [uploadError, setUploadError] = useState(null);
  const [parsing, setParsing] = useState(false);
  const fileRef = useRef();
  const [outreach, setOutreach] = useState(null);

  const isAllRoles = reqId === "ALL";
  const singleResultsApi = useApi(!isAllRoles && reqId ? `/screening/requisition/${reqId}` : null, [reqId]);
  const allResultsPaths = isAllRoles && reqs ? reqs.map(r => `/screening/requisition/${r.id}`) : null;
  const allResultsApi = useApiMulti(allResultsPaths, [isAllRoles, reqs?.length]);

  // Newest screened first by default, or highest score first; same for one role or all of them.
  const [sortMode, setSortMode] = useState("newest"); // "newest" | "score"
  const orderResults = list => !list ? null : [...list].sort(sortMode === "score"
    ? (a, b) => b.overallScore - a.overallScore || new Date(b.createdAt) - new Date(a.createdAt)
    : (a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  const results = isAllRoles
    ? orderResults(allResultsApi.data?.flat())
    : orderResults(singleResultsApi.data);
  const loading = isAllRoles ? allResultsApi.loading : singleResultsApi.loading;
  const error = isAllRoles ? allResultsApi.error : singleResultsApi.error;
  const reload = isAllRoles ? allResultsApi.reload : singleResultsApi.reload;

  // Applications still being matched/screened have no result row yet — poll for them
  const [inProgress, setInProgress] = useState([]);
  const prevInProgress = useRef(0);
  const reloadRef = useRef(reload);
  reloadRef.current = reload;
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const list = await apiFetch("/screening/in-progress");
        if (!alive) return;
        setInProgress(list);
        if (list.length < prevInProgress.current) reloadRef.current();
        prevInProgress.current = list.length;
      } catch { /* keep the last known state */ }
    };
    tick();
    const id = setInterval(tick, 3000);
    return () => { alive = false; clearInterval(id); };
  }, []);
  const pending = inProgress.filter(p => isAllRoles || String(p.requisitionId) === String(reqId));

  async function handleDecision(screeningId, decision, notes) {
    await apiFetch(`/screening/${screeningId}/review`, {
      method: "POST",
      body: JSON.stringify({ decision, notes }),
    });
    reload();
  }

  async function handleShortlist(candidateId, candidateReqId) {
    setShortlisting(candidateId);
    try {
      await apiFetch(`/orchestrator/pipeline/${candidateReqId ?? reqId}/candidates/${candidateId}/stage`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stage: "SHORTLISTED" }),
      });
      reload();
    } finally {
      setShortlisting(null);
    }
  }

  const [rejectTarget, setRejectTarget] = useState(null); // { id, name }
  const [rejectForm, setRejectForm] = useState({ preset: "", note: "" });

  async function handleReject(screeningId, reason) {
    setRejecting(screeningId);
    try {
      await apiFetch(`/screening/${screeningId}/review`, {
        method: "POST",
        body: JSON.stringify({ decision: "REJECT", notes: reason }),
      });
      reload();
    } finally {
      setRejecting(null);
    }
  }

  async function handleSendRejectionEmail(screeningResultId) {
    setSendingEmail(screeningResultId);
    try {
      await apiFetch(`/screening/${screeningResultId}/send-rejection-email`, { method: "POST" });
      reload();
    } catch (e) {
      alert("Failed to send rejection email: " + (e.message ?? "unknown error"));
    } finally {
      setSendingEmail(null);
    }
  }

  async function handleDraftOutreach(r) {
    setOutreach({ loading: true, matchId: r.matchId, candidateName: r.candidateName, requisitionTitle: r.requisitionTitle });
    try {
      const url = r.matchId
        ? `/sourcing/matches/${r.matchId}/draft-outreach`
        : `/sourcing/outreach/draft-by-candidate?candidateId=${r.candidateId}&requisitionId=${r.requisitionId}`;
      const result = await apiFetch(url, { method: "POST" });
      setOutreach({ loading: false, ...result });
    } catch { setOutreach(null); }
  }

  async function handleUpload(e) {
    e.preventDefault();
    if (!uploadFile) return;
    setUploading(true);
    setUploadError(null);
    const fd = new FormData();
    fd.append("candidateName", uploadForm.candidateName);
    fd.append("candidateEmail", uploadForm.candidateEmail);
    if (!isAllRoles) fd.append("requisitionId", String(reqId));
    fd.append("resume", uploadFile);
    try {
      await apiFetch("/screening/evaluate-upload", { method: "POST", body: fd });
      setShowUpload(false);
      setUploadForm({ candidateName:"", candidateEmail:"" });
      setUploadFile(null);
      reload();
    } catch (e) {
      setUploadError(e.message);
    } finally {
      setUploading(false);
    }
  }

  const sortedResults = results || [];

  return (
    <div>
      <TopBar title="Screening Agent"
        subtitle="Standardized resume scoring — consistent across every recruiter"
        action={
          <div className="flex gap-2">
            {reqs && (
              <Select value={reqId} onChange={e => setReqId(e.target.value === "ALL" ? "ALL" : Number(e.target.value))}>
                <option value="ALL">All Roles</option>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
            <Select value={sortMode} onChange={e => setSortMode(e.target.value)}>
              <option value="newest">Newest first</option>
              <option value="score">Highest score</option>
            </Select>
            <Btn onClick={() => setShowUpload(true)}>
              <UploadCloud size={14} /> Screen Resume
            </Btn>
          </div>
        }
      />

      {isAllRoles && (
        <p className="text-xs mb-3" style={{ color: C.muted }}>
          "All Roles" is selected — this candidate will be screened against every currently open role.
        </p>
      )}

      {error && <ErrorBanner message={error} onRetry={reload} />}

      <Card className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ backgroundColor: C.bg }}>
              {["Candidate","Score","Recommendation","Status",""].map(h => (
                <th key={h} className="text-left px-5 py-3 font-semibold" style={{ color: C.muted }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y" style={{ borderColor: C.border }}>
            {pending.map((p, i) => (
              <tr key={`pending-${i}`} style={{ backgroundColor: `${C.accent}06` }}>
                <td className="px-5 py-4">
                  <div className="flex items-center gap-2.5">
                    <Avatar name={p.candidateName} size={34} />
                    <div>
                      <p className="font-semibold" style={{ color: C.text }}>{p.candidateName}</p>
                      {isAllRoles && p.requisitionTitle && (
                        <p className="text-xs" style={{ color: C.muted }}>{p.requisitionTitle}</p>
                      )}
                    </div>
                  </div>
                </td>
                <td className="px-5 py-4" colSpan={4}>
                  <span className="inline-flex items-center gap-2 text-xs font-semibold" style={{ color: C.accent }}>
                    <Spinner size={13} /> Screening in progress — the AI is reading the resume…
                  </span>
                </td>
              </tr>
            ))}
            {loading
              ? Array.from({ length: 4 }).map((_, i) => (
                  <tr key={i}>
                    {Array.from({ length: 5 }).map((_, j) => (
                      <td key={j} className="px-5 py-4">
                        <div className="h-3 bg-slate-100 rounded animate-pulse" style={{ width: "70%" }} />
                      </td>
                    ))}
                  </tr>
                ))
              : sortedResults.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2.5">
                        <Avatar name={r.candidateName} size={34} />
                        <div>
                          <p className="font-semibold" style={{ color: C.text }}>{r.candidateName}</p>
                          {isAllRoles && r.requisitionTitle && (
                            <p className="text-xs" style={{ color: C.muted }}>{r.requisitionTitle}</p>
                          )}
                        </div>
                      </div>
                    </td>
                    <td className="px-5 py-4"><ScoreRing score={r.overallScore} size={40} /></td>
                    <td className="px-5 py-4"><RecBadge rec={r.recommendation} /></td>
                    <td className="px-5 py-4">
                      {(() => {
                        const stage = r.pipelineStage;
                        if (stage === "SHORTLISTED") return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.success }}>Shortlisted</span>;
                        if (stage === "REJECTED")    return (
                          <div>
                            <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.danger }}>Rejected</span>
                            {r.rejectionReason && (
                              <p className="text-[11px] mt-1 max-w-[200px] truncate" title={r.rejectionReason} style={{ color: C.muted }}>
                                {r.rejectionReason}
                              </p>
                            )}
                          </div>
                        );
                        if (stage === "INTERVIEW_SCHEDULED") return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.accent }}>Interview</span>;
                        if (stage === "OFFER")       return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.accent }}>Offer</span>;
                        if (stage === "HIRED")       return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.success }}>Hired</span>;
                        if (stage === "NO_SHOW")     return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.warning }}>No Show</span>;
                        return <span className="text-xs font-semibold" style={{ color: C.warning }}>Awaiting review</span>;
                      })()}
                    </td>
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2 flex-wrap">
                        {/* Auto-rejected: recruiter can override and shortlist */}
                        {r.pipelineStage === "REJECTED" && !r.reviewerDecision && (
                          <button
                            disabled={shortlisting === r.candidateId}
                            onClick={() => handleShortlist(r.candidateId, r.requisitionId)}
                            className="text-xs font-semibold px-3 py-1.5 rounded-lg text-white"
                            style={{ backgroundColor: C.accent, opacity: shortlisting === r.candidateId ? 0.5 : 1 }}>
                            {shortlisting === r.candidateId ? "…" : "Override & Shortlist"}
                          </button>
                        )}
                        {/* Pending review: shortlist or reject */}
                        {!["SHORTLISTED","INTERVIEW_SCHEDULED","OFFER","HIRED","REJECTED","NO_SHOW"].includes(r.pipelineStage) && (() => {
                          const rec = r.recommendation;
                          return (<>
                            <button
                              disabled={shortlisting === r.candidateId}
                              onClick={() => handleShortlist(r.candidateId, r.requisitionId)}
                              className="text-xs font-semibold px-3 py-1.5 rounded-lg text-white"
                              style={{ backgroundColor: C.success, opacity: shortlisting === r.candidateId ? 0.5 : 1 }}>
                              {shortlisting === r.candidateId ? "…" : rec === "REJECT" ? "Override & Shortlist" : "Shortlist"}
                            </button>
                            {rec === "REVIEW" && (
                              <button
                                disabled={rejecting === r.id}
                                onClick={() => { setRejectForm({ preset: "", note: "" }); setRejectTarget({ id: r.id, name: r.candidateName }); }}
                                className="text-xs font-semibold px-3 py-1.5 rounded-lg"
                                style={{ color: C.danger, border: `1px solid ${C.danger}55`, backgroundColor: `${C.danger}08`, opacity: rejecting === r.id ? 0.5 : 1 }}>
                                {rejecting === r.id ? "…" : "Reject"}
                              </button>
                            )}
                            {rec === "REJECT" && (<>
                              {(
                                r.rejectionEmailSent
                                  ? <span className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1" style={{ color: C.success, border: `1px solid ${C.success}55`, backgroundColor: `${C.success}08` }}>
                                      <Mail size={11} /> Email Sent
                                    </span>
                                  : <button
                                      disabled={sendingEmail === r.id}
                                      onClick={() => handleSendRejectionEmail(r.id)}
                                      className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1"
                                      style={{ color: C.muted, border: `1px solid ${C.border}`, backgroundColor: "transparent", opacity: sendingEmail === r.id ? 0.5 : 1 }}>
                                      <Mail size={11} />{sendingEmail === r.id ? "Sending…" : "Send Rejection Email"}
                                    </button>
                              )}
                            </>)}
                          </>);
                        })()}
                        {r.pipelineStage === "SHORTLISTED" && (
                          r.outreachSent
                            ? <span className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1" style={{ color: C.success, border: `1px solid ${C.success}55`, backgroundColor: `${C.success}08` }}>
                                <Mail size={11} /> Outreach Sent
                              </span>
                            : <button
                                onClick={() => handleDraftOutreach(r)}
                                className="text-xs font-semibold px-3 py-1.5 rounded-lg"
                                style={{ color: C.accent, border: `1px solid ${C.accent}55`, backgroundColor: `${C.accent}08` }}>
                                <span className="flex items-center gap-1"><Mail size={11} /> Send Outreach</span>
                              </button>
                        )}
                        {r.pipelineStage === "REJECTED" && (
                          r.rejectionEmailSent
                            ? <span className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1" style={{ color: C.success, border: `1px solid ${C.success}55`, backgroundColor: `${C.success}08` }}>
                                <Mail size={11} /> Email Sent
                              </span>
                            : <button
                                disabled={sendingEmail === r.id}
                                onClick={() => handleSendRejectionEmail(r.id)}
                                className="text-xs font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1"
                                style={{ color: C.muted, border: `1px solid ${C.border}`, backgroundColor: "transparent", opacity: sendingEmail === r.id ? 0.5 : 1 }}>
                                <Mail size={11} />{sendingEmail === r.id ? "Sending…" : "Send Rejection Email"}
                              </button>
                        )}
                        <Btn variant="secondary" onClick={() => setSelected(r)}>View</Btn>
                      </div>
                    </td>
                  </tr>
                ))}
          </tbody>
        </table>
        {!loading && sortedResults.length === 0 && pending.length === 0 && (
          <p className="text-center py-12 text-sm" style={{ color: C.muted }}>
            No screening results yet. Upload a resume to screen.
          </p>
        )}
      </Card>

      {rejectTarget && (
        <RejectReasonModal candidateName={rejectTarget.name} form={rejectForm} setForm={setRejectForm}
          onConfirm={() => { const t = rejectTarget; setRejectTarget(null); handleReject(t.id, joinRejectReason(rejectForm)); }}
          onCancel={() => setRejectTarget(null)} />
      )}

      <ScreeningDetailModal
        result={selected}
        onClose={() => setSelected(null)}
        onDecision={handleDecision}
      />
      <OutreachModal
        outreach={outreach}
        onClose={() => setOutreach(null)}
        onSent={reload}
      />

      {showUpload && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6">
            <div className="flex items-center justify-between mb-1">
              <h2 className="font-bold text-lg" style={{ color: C.text }}>Screen a Resume</h2>
              <button onClick={() => { setShowUpload(false); setUploadError(null); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} />
              </button>
            </div>
            <p className="text-xs mb-4" style={{ color: C.muted }}>
              {isAllRoles
                ? "Will be screened against every currently open role."
                : `Will be screened against "${reqs?.find(r => r.id === reqId)?.title || "the selected role"}".`}
            </p>
            <form onSubmit={handleUpload} className="space-y-3">
              <div>
                <Input label="Candidate Name" required value={uploadForm.candidateName}
                  onChange={e => setUploadForm(f => ({...f, candidateName: e.target.value}))} />
                {parsing && <p className="text-[10px] mt-1" style={{ color: C.accent }}>Extracting from resume…</p>}
              </div>
              <Input label="Candidate Email" type="email" value={uploadForm.candidateEmail}
                onChange={e => setUploadForm(f => ({...f, candidateEmail: e.target.value}))} />
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                  Resume File (PDF or TXT)
                </label>
                <div
                  className="border-2 border-dashed rounded-xl p-6 text-center cursor-pointer hover:border-blue-300 transition-colors"
                  style={{ borderColor: uploadFile ? C.accent : C.border }}
                  onClick={() => fileRef.current?.click()}>
                  <UploadCloud size={24} className="mx-auto mb-2" style={{ color: C.muted }} />
                  <p className="text-sm" style={{ color: C.muted }}>
                    {parsing ? "Parsing resume…" : uploadFile ? uploadFile.name : "Click to upload PDF or TXT"}
                  </p>
                  <input ref={fileRef} type="file" accept=".pdf,.txt" className="hidden"
                    onChange={async e => {
                      const file = e.target.files[0];
                      if (!file) return;
                      setUploadFile(file);
                      setParsing(true);
                      try {
                        const fd = new FormData();
                        fd.append("resume", file);
                        const parsed = await apiFetch("/screening/parse-resume", { method: "POST", body: fd });
                        setUploadForm(f => ({
                          candidateName: parsed.name || f.candidateName,
                          candidateEmail: parsed.email || f.candidateEmail,
                        }));
                      } catch { /* silent — user can fill manually */ }
                      finally { setParsing(false); }
                    }} />
                </div>
              </div>
              {uploadError && <p className="text-xs font-semibold px-3 py-2 rounded-lg" style={{ color: C.danger, backgroundColor: `${C.danger}10` }}>{uploadError}</p>}
              <Btn type="submit" variant="primary" className="w-full justify-center py-2.5"
                disabled={uploading || !uploadFile || !uploadForm.candidateName}>
                {uploading ? <Spinner size={14} /> : <Sparkles size={14} />}
                Screen with AI
              </Btn>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// REFERRAL AGENT
// ============================================================
function ReferralsView({ currentUser = {} }) {
  const { data: referrals, loading, error, reload } = useApi("/referrals");
  const { data: reqs } = useApi("/requisitions?status=OPEN");
  const [showForm, setShowForm] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({
    candidateName:"", candidateEmail:"", resumeText:"", relationshipNotes:"", requisitionId:"",
    referrerName:"", referrerPosition:""
  });
  const [referralParsingResume, setReferralParsingResume] = useState(false);

  async function handleReferralResumeUpload(e) {
    const file = e.target.files[0];
    if (!file) return;
    setReferralParsingResume(true);
    try {
      const fd = new FormData();
      fd.append("resume", file);
      const parsed = await apiFetch("/screening/parse-resume", { method: "POST", body: fd });
      setForm(f => ({
        ...f,
        candidateName: parsed.name || "",
        candidateEmail: parsed.email || "",
        resumeText: parsed.resumeText || f.resumeText,
      }));
    } catch (err) {
      console.error("Resume parse failed", err);
    } finally {
      setReferralParsingResume(false);
    }
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await apiFetch("/referrals", {
        method: "POST",
        body: JSON.stringify({
          ...form,
          requisitionId: form.requisitionId ? Number(form.requisitionId) : null,
        }),
      });
      setShowForm(false);
      setForm({ candidateName:"", candidateEmail:"", resumeText:"", relationshipNotes:"", requisitionId:"", referrerName:"", referrerPosition:"" });
      reload();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <TopBar title="Referral Agent"
        subtitle="Submit referrals and let the agent auto-match against open roles"
        action={
          <Btn onClick={() => {
            setForm({ candidateName:"", candidateEmail:"", resumeText:"", relationshipNotes:"", requisitionId:"", referrerName: currentUser.fullName || "", referrerPosition:"" });
            setShowForm(true);
          }}>
            <UserPlus size={14} /> Submit Referral
          </Btn>
        }
      />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      <Card className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ backgroundColor: C.bg }}>
              {["Candidate","Matched Role","Match Score","Referred By","Position / Email","Status","Submitted"].map(h => (
                <th key={h} className="text-left px-5 py-3 font-semibold" style={{ color: C.muted }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y" style={{ borderColor: C.border }}>
            {loading
              ? Array.from({ length: 3 }).map((_, i) => (
                  <tr key={i}>
                    {Array.from({ length: 6 }).map((_, j) => (
                      <td key={j} className="px-5 py-4">
                        <div className="h-3 bg-slate-100 rounded animate-pulse" />
                      </td>
                    ))}
                  </tr>
                ))
              : (referrals || []).map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2.5">
                        <Avatar name={r.candidateName} size={34} />
                        <span className="font-semibold" style={{ color: C.text }}>{r.candidateName}</span>
                      </div>
                    </td>
                    <td className="px-5 py-4" style={{ color: C.text }}>
                      {r.matchedRequisitionTitle || r.requisitionTitle || "—"}
                    </td>
                    <td className="px-5 py-4">
                      {r.matchScore != null ? <ScoreRing score={r.matchScore} size={36} /> : "—"}
                    </td>
                    <td className="px-5 py-4" style={{ color: C.text }}>
                      <div className="font-medium">{r.referrerName || r.referredByUsername || "—"}</div>
                    </td>
                    <td className="px-5 py-4 text-xs" style={{ color: C.muted }}>
                      {r.referrerPosition && <div>{r.referrerPosition}</div>}
                      {r.referrerEmail && <div style={{ color: C.accent }}>{r.referrerEmail}</div>}
                      {!r.referrerPosition && !r.referrerEmail && "—"}
                    </td>
                    <td className="px-5 py-4"><Badge color={C.success}>{r.status}</Badge></td>
                    <td className="px-5 py-4 font-mono text-xs" style={{ color: C.muted }}>
                      {formatDateTime(r.submittedAt)}
                    </td>
                  </tr>
                ))}
          </tbody>
        </table>
        {!loading && (referrals || []).length === 0 && (
          <p className="text-center py-12 text-sm" style={{ color: C.muted }}>No referrals yet.</p>
        )}
      </Card>

      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-lg p-6">
            <div className="flex items-center justify-between mb-5">
              <div>
                <h2 className="font-bold text-lg" style={{ color: C.text }}>Submit a Referral</h2>
                <p className="text-sm" style={{ color: C.muted }}>Agent will auto-match to best open role</p>
              </div>
              <button onClick={() => setShowForm(false)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
            </div>
            <form onSubmit={handleSubmit} className="space-y-3">
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                  Upload Resume (PDF/TXT) — auto-fills fields below
                </label>
                <label className="flex items-center gap-2.5 px-4 py-2.5 rounded-xl border cursor-pointer hover:bg-slate-50 transition-colors text-sm"
                  style={{ borderColor: C.border, color: C.muted }}>
                  <UploadCloud size={14} />
                  {referralParsingResume ? "Parsing resume…" : "Choose file…"}
                  <input type="file" accept=".pdf,.txt" className="hidden"
                    onChange={handleReferralResumeUpload} disabled={referralParsingResume} />
                </label>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Input label="Your Name (Referrer)" required value={form.referrerName}
                  onChange={e => setForm(f => ({...f, referrerName: e.target.value}))} />
                <Input label="Your Position / Dept" value={form.referrerPosition}
                  placeholder="e.g. Senior Engineer"
                  onChange={e => setForm(f => ({...f, referrerPosition: e.target.value}))} />
              </div>
              <div className="border-t pt-3" style={{ borderColor: "var(--border, #E2E8F0)" }}>
                <p className="text-xs font-semibold mb-2" style={{ color: "var(--muted, #64748B)" }}>Candidate Details</p>
              </div>
              <Input label="Candidate Name" required value={form.candidateName}
                onChange={e => setForm(f => ({...f, candidateName: e.target.value}))} />
              <Input label="Email" type="email" value={form.candidateEmail}
                onChange={e => setForm(f => ({...f, candidateEmail: e.target.value}))} />
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                  Target Role (optional)
                </label>
                <Select value={form.requisitionId}
                  onChange={e => setForm(f => ({...f, requisitionId: e.target.value}))}>
                  <option value="">Let agent decide best fit</option>
                  {(reqs || []).map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
                </Select>
              </div>
              <Input label="Relationship Notes (optional)" placeholder="e.g., Former teammate"
                value={form.relationshipNotes}
                onChange={e => setForm(f => ({...f, relationshipNotes: e.target.value}))} />
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                  Background / Resume Summary
                </label>
                <textarea rows={4}
                  className="w-full text-sm px-4 py-2.5 rounded-xl border outline-none resize-none"
                  style={{ borderColor: C.border }}
                  value={form.resumeText}
                  onChange={e => setForm(f => ({...f, resumeText: e.target.value}))}
                  placeholder="Skills, experience, key background..." />
              </div>
              <div className="p-3.5 rounded-xl flex items-start gap-2.5"
                style={{ background: "linear-gradient(135deg,#E6F3FB,#EAF0F7)", border: "1px solid #B8DCF2" }}>
                <Sparkles size={14} className="shrink-0 mt-0.5" style={{ color: C.accent }} />
                <p className="text-xs" style={{ color: C.muted }}>
                  The Referral Agent will auto-match this candidate against all open roles
                  and route them to the best fit.
                </p>
              </div>
              <Btn type="submit" variant="primary" className="w-full justify-center py-2.5" disabled={submitting}>
                {submitting ? <Spinner size={14} /> : null} Submit Referral
              </Btn>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// COORDINATION / ADMIN AGENT
// ============================================================
function SchedulingView() {
  const { data: reqs } = useApi("/requisitions?status=OPEN");
  const [reqId, setReqId] = useState(null);
  const [showForm, setShowForm] = useState(false);
  const [proposeForm, setProposeForm] = useState({ candidateId:"", interviewType:"SCREENING" });
  const [candidates, setCandidates] = useState([]);
  const [proposing, setProposing] = useState(false);

  useEffect(() => {
    if (reqs && reqs.length > 0 && !reqId) setReqId(reqs[0].id);
  }, [reqs, reqId]);

  const { data: schedules, loading, error, reload } = useApi(
    reqId ? `/admin/interviews/requisition/${reqId}` : null,
    [reqId]
  );

  useEffect(() => {
    apiFetch("/candidates").then(setCandidates).catch(() => {});
  }, []);

  async function handlePropose(e) {
    e.preventDefault();
    setProposing(true);
    try {
      await apiFetch("/admin/interviews/propose", {
        method: "POST",
        body: JSON.stringify({
          candidateId: Number(proposeForm.candidateId),
          requisitionId: reqId,
          interviewType: proposeForm.interviewType,
        }),
      });
      setShowForm(false);
      setProposeForm({ candidateId:"", interviewType:"SCREENING" });
      reload();
    } finally {
      setProposing(false);
    }
  }

  async function handleConfirm(scheduleId, slot) {
    await apiFetch(`/admin/interviews/${scheduleId}/confirm`, {
      method: "PATCH",
      body: JSON.stringify({ confirmedSlot: slot }),
    });
    reload();
  }

  return (
    <div>
      <TopBar title="Coordination Agent"
        subtitle="Proposes interview slots and drafts scheduling messages for recruiter approval"
        action={
          <div className="flex gap-2">
            {reqs && (
              <Select value={reqId || ""} onChange={e => setReqId(Number(e.target.value))}>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
            <Btn onClick={() => setShowForm(true)}><Calendar size={14} /> Propose Interview</Btn>
          </div>
        }
      />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {loading && <div className="space-y-4">{[1,2].map(i => <LoadingCard key={i} rows={4} />)}</div>}

      <div className="space-y-4">
        {!loading && (schedules || []).length === 0 && (
          <Card className="p-12 text-center">
            <p style={{ color: C.muted }}>No interviews scheduled yet for this role.</p>
          </Card>
        )}
        {(schedules || []).map((s) => (
          <Card key={s.id} className="p-5">
            <div className="flex items-start justify-between mb-4">
              <div className="flex items-center gap-3">
                <Avatar name={s.candidateName} size={42} />
                <div>
                  <p className="font-semibold" style={{ color: C.text }}>{s.candidateName}</p>
                  <p className="text-xs" style={{ color: C.muted }}>{s.requisitionTitle}</p>
                  <Badge color={C.accent} className="mt-1">{s.interviewType} Interview</Badge>
                </div>
              </div>
              <span className="px-3 py-1 rounded-full text-xs font-bold"
                style={{
                  backgroundColor: s.status === "CONFIRMED" ? "#ECFDF5" : "#FFFBEB",
                  color: s.status === "CONFIRMED" ? C.success : C.warning,
                }}>
                {s.status}
              </span>
            </div>

            {s.status === "CONFIRMED" ? (
              <div className="flex items-center gap-2.5 p-4 rounded-2xl"
                style={{ backgroundColor: "#ECFDF5", border: "1px solid #A7F3D0" }}>
                <CheckCircle2 size={18} style={{ color: C.success }} />
                <div>
                  <p className="text-sm font-semibold" style={{ color: C.text }}>Interview Confirmed</p>
                  <p className="text-xs font-mono" style={{ color: C.muted }}>
                    {formatDateTime(s.confirmedSlot)}
                  </p>
                </div>
              </div>
            ) : (
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider mb-3" style={{ color: C.muted }}>
                  Proposed Slots — click to confirm
                </p>
                <div className="flex gap-2 flex-wrap mb-4">
                  {(s.proposedSlots || []).map((slot) => (
                    <button key={slot}
                      onClick={() => handleConfirm(s.id, slot)}
                      className="text-xs font-mono px-4 py-2 rounded-xl border hover:border-blue-300 hover:bg-blue-50 transition-colors flex items-center gap-1.5"
                      style={{ borderColor: C.border, color: C.text }}>
                      <Clock size={11} style={{ color: C.accent }} />
                      {formatDateTime(slot)}
                    </button>
                  ))}
                </div>
                {s.notes && (
                  <div className="p-4 rounded-2xl flex items-start gap-2.5 mb-4"
                    style={{ backgroundColor: C.bg, border: `1px solid ${C.border}` }}>
                    <Mail size={14} className="shrink-0 mt-0.5" style={{ color: C.muted }} />
                    <div>
                      <p className="text-xs font-semibold mb-1" style={{ color: C.text }}>
                        Draft Message (pending approval)
                      </p>
                      <p className="text-xs leading-relaxed" style={{ color: C.muted }}>
                        {s.notes.replace("Draft outreach message: ", "")}
                      </p>
                    </div>
                  </div>
                )}
                <div className="flex gap-2">
                  <Btn variant="primary"><CheckCircle2 size={13} />Approve &amp; Send</Btn>
                  <Btn variant="secondary">Edit Message</Btn>
                </div>
              </div>
            )}
          </Card>
        ))}
      </div>

      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6">
            <div className="flex items-center justify-between mb-5">
              <h2 className="font-bold text-lg" style={{ color: C.text }}>Propose Interview</h2>
              <button onClick={() => setShowForm(false)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} />
              </button>
            </div>
            <form onSubmit={handlePropose} className="space-y-3">
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Candidate</label>
                <Select value={proposeForm.candidateId}
                  onChange={e => setProposeForm(f => ({...f, candidateId: e.target.value}))}>
                  <option value="">Select candidate</option>
                  {candidates.map(c => <option key={c.id} value={c.id}>{c.fullName}</option>)}
                </Select>
              </div>
              <div>
                <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Interview Type</label>
                <Select value={proposeForm.interviewType}
                  onChange={e => setProposeForm(f => ({...f, interviewType: e.target.value}))}>
                  {["SCREENING","TECHNICAL","FINAL"].map(t => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </Select>
              </div>
              <div className="p-3.5 rounded-xl flex items-start gap-2.5"
                style={{ background: "linear-gradient(135deg,#E6F3FB,#EAF0F7)", border: "1px solid #B8DCF2" }}>
                <Sparkles size={14} className="shrink-0 mt-0.5" style={{ color: C.accent }} />
                <p className="text-xs" style={{ color: C.muted }}>
                  Claude will generate 3 interview slot suggestions and a draft candidate
                  outreach message for your review.
                </p>
              </div>
              <Btn type="submit" variant="primary" className="w-full justify-center py-2.5"
                disabled={proposing || !proposeForm.candidateId}>
                {proposing ? <Spinner size={14} /> : <Sparkles size={14} />}
                Generate Slots with AI
              </Btn>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

// ============================================================
// PUBLIC REFERRAL PAGE — no login required
// ============================================================
function PublicReferralPage() {
  const { data: reqs } = useApi("/public/jobs");
  const [form, setForm] = useState({
    referrerName: "", referrerEmail: "", referrerPosition: "",
    candidateName: "", candidateEmail: "", resumeText: "", relationshipNotes: "", requisitionId: "",
  });
  const [parsing, setParsing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState("");

  async function handleResumeUpload(e) {
    const file = e.target.files[0];
    if (!file) return;
    setParsing(true);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const parsed = await apiFetch("/public/parse-resume", { method: "POST", body: fd });
      setForm(f => ({
        ...f,
        candidateName: parsed.fullName || f.candidateName,
        candidateEmail: parsed.email || f.candidateEmail,
        resumeText: parsed.resumeText || f.resumeText,
      }));
    } catch { /* silent */ } finally { setParsing(false); }
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await apiFetch("/referrals/public", {
        method: "POST",
        body: JSON.stringify({ ...form, requisitionId: form.requisitionId ? Number(form.requisitionId) : null }),
      });
      setSubmitted(true);
    } catch (err) {
      setError(err.message || "Submission failed. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <div className="min-h-screen flex items-center justify-center p-4" style={{ backgroundColor: C.bg }}>
        <div className="w-full max-w-md text-center">
          <div className="w-16 h-16 rounded-full flex items-center justify-center mx-auto mb-4"
            style={{ backgroundColor: "#DCFCE7" }}>
            <CheckCircle2 size={32} style={{ color: C.success }} />
          </div>
          <h2 className="text-xl font-bold mb-2" style={{ color: C.text }}>Referral Submitted!</h2>
          <p className="text-sm mb-6" style={{ color: C.muted }}>
            Thanks {form.referrerName}! Our AI agent is matching {form.candidateName} against open roles.
            The recruiting team will be in touch.
          </p>
          <Btn variant="outline" onClick={() => { setSubmitted(false); setForm({ referrerName:"", referrerEmail:"", referrerPosition:"", candidateName:"", candidateEmail:"", resumeText:"", relationshipNotes:"", requisitionId:"" }); }}>
            Submit Another Referral
          </Btn>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-6" style={{ backgroundColor: C.bg }}>
      <div className="w-full max-w-2xl">
        <div className="flex items-center gap-3 justify-center mb-6">
          <div className="w-10 h-10 rounded-2xl flex items-center justify-center"
            style={{ background: "#248EC2" }}>
            <Sparkles size={20} color="#fff" />
          </div>
          <div>
            <p className="font-bold text-xl tracking-tight" style={{ color: C.text }}>TalentAcquisition AI</p>
            <p className="text-xs" style={{ color: C.muted }}>Employee Referral Portal</p>
          </div>
        </div>

        <Card className="p-6">
          <h2 className="font-bold text-lg mb-1" style={{ color: C.text }}>Refer Someone</h2>
          <p className="text-sm mb-5" style={{ color: C.muted }}>
            Know someone great? Submit a referral and our AI agent will match them to the best open role.
          </p>

          {error && (
            <div className="mb-4 p-3 rounded-xl text-sm" style={{ backgroundColor: "#FEF2F2", color: C.danger }}>
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-3">
            {/* Referrer section */}
            <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.accent }}>Your Details</p>
            <Input label="Your Full Name" required value={form.referrerName}
              onChange={e => setForm(f => ({...f, referrerName: e.target.value}))} />
            <div className="grid grid-cols-2 gap-3">
              <Input label="Your Work Email" type="email" required value={form.referrerEmail}
                onChange={e => setForm(f => ({...f, referrerEmail: e.target.value}))} />
              <Input label="Your Position / Dept" value={form.referrerPosition}
                placeholder="e.g. Senior Engineer"
                onChange={e => setForm(f => ({...f, referrerPosition: e.target.value}))} />
            </div>

            {/* Candidate section */}
            <div className="border-t pt-3" style={{ borderColor: C.border }}>
              <p className="text-xs font-bold uppercase tracking-wide mb-3" style={{ color: C.accent }}>Candidate Details</p>
            </div>
            <div>
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                Upload Resume (PDF/TXT) — auto-fills fields below
              </label>
              <label className="flex items-center gap-2.5 px-4 py-2.5 rounded-xl border cursor-pointer hover:bg-slate-50 transition-colors text-sm"
                style={{ borderColor: C.border, color: C.muted }}>
                <UploadCloud size={14} />
                {parsing ? "Parsing resume…" : "Choose file…"}
                <input type="file" accept=".pdf,.txt" className="hidden"
                  onChange={handleResumeUpload} disabled={parsing} />
              </label>
            </div>
            <Input label="Candidate Name" required value={form.candidateName}
              onChange={e => setForm(f => ({...f, candidateName: e.target.value}))} />
            <Input label="Candidate Email" type="email" value={form.candidateEmail}
              onChange={e => setForm(f => ({...f, candidateEmail: e.target.value}))} />
            <div>
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                Target Role (optional — AI will find best fit if left blank)
              </label>
              <Select value={form.requisitionId}
                onChange={e => setForm(f => ({...f, requisitionId: e.target.value}))}>
                <option value="">Let AI decide best fit</option>
                {(reqs || []).map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            </div>
            <Input label="Your Relationship (optional)" placeholder="e.g. Former teammate, worked together 3 yrs"
              value={form.relationshipNotes}
              onChange={e => setForm(f => ({...f, relationshipNotes: e.target.value}))} />
            <div>
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>
                Candidate Background / Skills
              </label>
              <textarea rows={3}
                className="w-full text-sm px-4 py-2.5 rounded-xl border outline-none resize-none"
                style={{ borderColor: C.border }}
                value={form.resumeText}
                onChange={e => setForm(f => ({...f, resumeText: e.target.value}))}
                placeholder="Key skills, experience, why you're recommending them…" />
            </div>

            <div className="p-3.5 rounded-xl flex items-start gap-2.5"
              style={{ background: "linear-gradient(135deg,#E6F3FB,#EAF0F7)", border: "1px solid #B8DCF2" }}>
              <Sparkles size={14} className="shrink-0 mt-0.5" style={{ color: C.accent }} />
              <p className="text-xs" style={{ color: C.muted }}>
                Our AI agent will automatically match this candidate to the best open role and notify the recruiting team.
              </p>
            </div>

            <Btn type="submit" variant="primary" className="w-full justify-center py-2.5" disabled={submitting}>
              {submitting ? <Spinner size={14} /> : <UserPlus size={14} />}
              {submitting ? "Submitting…" : "Submit Referral"}
            </Btn>
          </form>
        </Card>
      </div>
    </div>
  );
}

// ============================================================
// RESUME DROP ZONE (SFTP / Google Drive folder AI pre-screening dashboard)
// ============================================================
function CvInboxView() {
  const [tab, setTab] = useState("considered");
  const [processing, setProcessing] = useState(false);
  const [processResult, setProcessResult] = useState(null);
  const [selectedItem, setSelectedItem] = useState(null);
  const [checkedIds, setCheckedIds] = useState(() => new Set());
  const [archiving, setArchiving] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const { data: connInfo, reload: reloadConnInfo } = useApi("/cv-inbox/connection-info");
  const considered = useApi("/cv-inbox/items?decision=CONSIDERED");
  const excluded = useApi("/cv-inbox/items?decision=EXCLUDED");
  const current = tab === "considered" ? considered : excluded;

  function switchTab(id) {
    setTab(id);
    setCheckedIds(new Set());
  }

  function toggleOne(id) {
    setCheckedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    const rows = current.data || [];
    setCheckedIds(prev =>
      prev.size === rows.length ? new Set() : new Set(rows.map(r => r.id))
    );
  }

  async function handleArchiveSelected() {
    if (checkedIds.size === 0) return;
    setArchiving(true);
    try {
      await apiFetch("/cv-inbox/items/archive", {
        method: "POST",
        body: JSON.stringify({ ids: [...checkedIds] }),
      });
      setCheckedIds(new Set());
      current.reload();
    } finally {
      setArchiving(false);
    }
  }

  async function handleProcessNow() {
    setProcessing(true);
    setProcessResult(null);
    try {
      const result = await apiFetch("/cv-inbox/process-now", { method: "POST" });
      setProcessResult(result);
      setCheckedIds(new Set());
      considered.reload();
      excluded.reload();
    } finally {
      setProcessing(false);
    }
  }

  function scoreColor(score) {
    if (score == null) return C.muted;
    if (score >= 70) return C.success;
    if (score >= 40) return C.warning;
    return C.danger;
  }

  return (
    <div>
      <TopBar title="Resume Drop Zone" subtitle="AI pre-screens resumes dropped via SFTP or a Google Drive folder before they reach your pipeline"
        action={
          <div className="flex gap-2">
            <Btn variant="secondary" onClick={() => setSettingsOpen(true)}>
              <Settings size={13} /> Settings
            </Btn>
            <Btn onClick={handleProcessNow} disabled={processing}>
              {processing ? <Spinner size={13} /> : <RefreshCw size={13} />}
              {processing ? "Processing…" : "Process now"}
            </Btn>
          </div>
        }
      />

      {connInfo && (
        <Card className="p-4 mb-5 flex items-center gap-3">
          <Globe size={16} style={{ color: C.accent }} />
          {connInfo.mode === "GOOGLE_DRIVE" ? (
            <div>
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Google Drive Folder</p>
              <p className="text-sm" style={{ color: C.text }}>
                {connInfo.googleFolderId
                  ? <>folder {connInfo.googleFolderId} &nbsp;·&nbsp; shared with {connInfo.googleServiceAccountEmail || "no service account key yet"}</>
                  : "Not set up yet. Open Settings to add the folder and service account key."}
              </p>
            </div>
          ) : (
            <div>
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>SFTP Connection</p>
              <p className="text-sm" style={{ color: C.text }}>
                sftp://{connInfo.host}:{connInfo.port} &nbsp;·&nbsp; user: {connInfo.username}
              </p>
            </div>
          )}
        </Card>
      )}

      {processResult && (
        <div className="mb-5 p-3 rounded-xl text-sm" style={{ backgroundColor: C.surface, color: C.text }}>
          Processed {processResult.processed} resume{processResult.processed === 1 ? "" : "s"} ({processResult.filesFound} found) —{" "}
          {processResult.considered} considered, {processResult.excluded} excluded
          {processResult.failed ? `, ${processResult.failed} failed` : ""}.
        </div>
      )}

      <div className="flex items-center justify-between mb-4">
        <div className="flex gap-1 p-1 rounded-xl w-fit" style={{ backgroundColor: C.surface }}>
          {[["considered", "Considered"], ["excluded", "Excluded"]].map(([id, label]) => (
            <button key={id} onClick={() => switchTab(id)}
              className="px-4 py-1.5 rounded-lg text-sm font-semibold transition-colors"
              style={{ backgroundColor: tab === id ? C.accent : "transparent", color: tab === id ? "#fff" : C.muted }}>
              {label}
            </button>
          ))}
        </div>

        {checkedIds.size > 0 && (
          <Btn variant="secondary" onClick={handleArchiveSelected} disabled={archiving}>
            {archiving ? <Spinner size={13} /> : <X size={13} />}
            {archiving ? "Archiving…" : `Archive ${checkedIds.size} selected`}
          </Btn>
        )}
      </div>

      {current.error && <ErrorBanner message={current.error} onRetry={current.reload} />}

      {current.loading && <div className="space-y-3">{[1, 2, 3].map(i => <LoadingCard key={i} />)}</div>}

      {!current.loading && !current.error && (
        <Card className="overflow-hidden">
          {(current.data || []).length === 0 ? (
            <div className="p-12 text-center"><p style={{ color: C.muted }}>No {tab} resumes yet.</p></div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b" style={{ borderColor: C.border }}>
                  <th className="px-4 py-3" style={{ width: "1%" }}>
                    <input type="checkbox"
                      checked={current.data.length > 0 && checkedIds.size === current.data.length}
                      onChange={() => toggleAll()}
                      onClick={e => e.stopPropagation()} />
                  </th>
                  <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>File</th>
                  <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Candidate</th>
                  <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Best Match</th>
                  <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Score</th>
                  <th className="text-left px-4 py-3 font-semibold" style={{ color: C.muted }}>Processed</th>
                </tr>
              </thead>
              <tbody>
                {current.data.map(item => (
                  <tr key={item.id} onClick={() => setSelectedItem(item)}
                    className="border-b cursor-pointer hover:bg-slate-50 transition-colors" style={{ borderColor: C.border }}>
                    <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
                      <input type="checkbox" checked={checkedIds.has(item.id)} onChange={() => toggleOne(item.id)} />
                    </td>
                    <td className="px-4 py-3" style={{ color: C.text }}>
                      {item.fileName}
                      {item.status === "FAILED" && <span className="ml-2 text-xs" style={{ color: C.danger }}>(failed to parse)</span>}
                    </td>
                    <td className="px-4 py-3" style={{ color: C.text }}>{item.candidateName || "—"}</td>
                    <td className="px-4 py-3" style={{ color: C.text }}>{item.bestRequisitionTitle || "—"}</td>
                    <td className="px-4 py-3">
                      <Badge color={scoreColor(item.bestMatchScore)}>{item.bestMatchScore != null ? item.bestMatchScore : "—"}</Badge>
                    </td>
                    <td className="px-4 py-3" style={{ color: C.muted }}>
                      {item.processedAt ? new Date(item.processedAt).toLocaleString() : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {selectedItem && <CvInboxDetailModal item={selectedItem} onClose={() => setSelectedItem(null)} />}

      {settingsOpen && (
        <CvInboxSettingsDrawer
          onClose={() => setSettingsOpen(false)}
          onSaved={() => { setSettingsOpen(false); reloadConnInfo(); }}
        />
      )}
    </div>
  );
}

function CvInboxDetailModal({ item, onClose }) {
  function scoreColor(score) {
    if (score == null) return C.muted;
    if (score >= 70) return C.success;
    if (score >= 40) return C.warning;
    return C.danger;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
      <Card className="w-full max-w-2xl max-h-[88vh] overflow-y-auto">
        <div className="px-6 py-5 border-b flex items-start justify-between sticky top-0 bg-white rounded-t-2xl z-10"
          style={{ borderColor: C.border }}>
          <div>
            <h2 className="text-lg font-bold" style={{ color: C.text }}>{item.candidateName || item.fileName}</h2>
            <p className="text-sm" style={{ color: C.muted }}>
              {item.fileName}{item.candidateEmail ? ` · ${item.candidateEmail}` : ""}
            </p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
            <X size={16} />
          </button>
        </div>

        <div className="p-6 space-y-4">
          <Badge color={item.decision === "CONSIDERED" ? C.success : C.danger}>
            {item.decision === "CONSIDERED" ? "Considered — placed in pipeline" : "Excluded"}
          </Badge>

          {item.status === "FAILED" && (
            <div className="p-3 rounded-xl text-sm" style={{ backgroundColor: "#FEF2F2", color: C.danger }}>
              Could not process this file: {item.errorMessage}
            </div>
          )}

          {(item.roleMatches || []).length > 0 && (
            <div className="space-y-3">
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>
                Scored against {item.roleMatches.length} open role{item.roleMatches.length === 1 ? "" : "s"}
              </p>
              {item.roleMatches.map(rm => (
                <div key={rm.requisitionId} className="p-4 rounded-xl border" style={{ borderColor: C.border }}>
                  <div className="flex items-center justify-between mb-1">
                    <p className="text-sm font-semibold" style={{ color: C.text }}>{rm.requisitionTitle}</p>
                    <Badge color={scoreColor(rm.matchScore)}>{rm.matchScore}</Badge>
                  </div>
                  {rm.pipelineStage && (
                    <p className="text-xs mb-1" style={{ color: C.success }}>✓ Placed in pipeline at {rm.pipelineStage}</p>
                  )}
                  <p className="text-sm" style={{ color: C.muted }}>{rm.rationale}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

function CvInboxSettingsDrawer({ onClose, onSaved }) {
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({
    mode: "EMBEDDED",
    embeddedPort: 2222, embeddedUsername: "hr", embeddedPassword: "",
    externalHost: "", externalPort: 22, externalUsername: "", externalPassword: "", externalRootPath: "",
    proxyEnabled: false, proxyHost: "", proxyPort: 80,
    googleFolderId: "", googleServiceAccountJson: "",
  });
  const [embeddedPasswordSet, setEmbeddedPasswordSet] = useState(false);
  const [externalPasswordSet, setExternalPasswordSet] = useState(false);
  const [googleKeySet, setGoogleKeySet] = useState(false);
  const [googleEmail, setGoogleEmail] = useState(""); // the address the Drive folder must be shared with
  const [keyFileName, setKeyFileName] = useState("");
  const [keyError, setKeyError] = useState("");
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const s = await apiFetch("/cv-inbox/settings");
        setForm(f => ({
          ...f,
          mode: s.mode || "EMBEDDED",
          embeddedPort: s.embeddedPort ?? f.embeddedPort,
          embeddedUsername: s.embeddedUsername ?? f.embeddedUsername,
          externalHost: s.externalHost || "",
          externalPort: s.externalPort ?? f.externalPort,
          externalUsername: s.externalUsername || "",
          externalRootPath: s.externalRootPath || "",
          proxyEnabled: !!s.proxyEnabled,
          proxyHost: s.proxyHost || "",
          proxyPort: s.proxyPort ?? f.proxyPort,
          googleFolderId: s.googleFolderId || "",
        }));
        setEmbeddedPasswordSet(!!s.embeddedPasswordSet);
        setExternalPasswordSet(!!s.externalPasswordSet);
        setGoogleKeySet(!!s.googleKeySet);
        setGoogleEmail(s.googleServiceAccountEmail || "");
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  function set(field, value) {
    setForm(f => ({ ...f, [field]: value }));
    setTestResult(null);
  }

  // The key never leaves this machine except to be saved; read it here so the folder-sharing address can be shown straight away.
  async function handleKeyFile(file) {
    setKeyError("");
    if (!file) return;
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      if (!parsed.client_email || !parsed.private_key) throw new Error("not a service account key");
      set("googleServiceAccountJson", text);
      setGoogleEmail(parsed.client_email);
      setKeyFileName(file.name);
    } catch {
      setKeyError("That file is not a Google service account key (JSON). Download it from Google Cloud: Service Accounts, Keys, Add key, JSON.");
    }
  }

  async function handleTestConnection() {
    setTesting(true);
    setTestResult(null);
    try {
      const result = await apiFetch("/cv-inbox/settings/test-connection", {
        method: "POST",
        body: JSON.stringify(form),
      });
      setTestResult(result);
    } finally {
      setTesting(false);
    }
  }

  async function handleSave() {
    setSaving(true);
    try {
      await apiFetch("/cv-inbox/settings", { method: "PUT", body: JSON.stringify(form) });
      onSaved();
    } finally {
      setSaving(false);
    }
  }

  const inputStyle = "w-full px-3 py-2 rounded-xl border text-sm";
  const inputColors = { borderColor: C.border, color: C.text, backgroundColor: C.bg };

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0" style={{ backgroundColor: "rgba(15,23,42,0.4)" }} onClick={onClose} />
      <div className="absolute inset-y-0 right-0 w-full max-w-md bg-white shadow-2xl overflow-y-auto">
        <div className="px-6 py-5 border-b flex items-center justify-between sticky top-0 bg-white z-10"
          style={{ borderColor: C.border }}>
          <h2 className="text-lg font-bold" style={{ color: C.text }}>Resume Drop Zone Settings</h2>
          <button onClick={onClose} className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
            <X size={16} />
          </button>
        </div>

        {loading ? (
          <div className="p-6"><LoadingCard /></div>
        ) : (
          <div className="p-6 space-y-5">
            <div>
              <label className="text-xs font-semibold block mb-1.5" style={{ color: C.muted }}>Source</label>
              <div className="flex gap-1 p-1 rounded-xl w-fit" style={{ backgroundColor: C.surface }}>
                {[["EMBEDDED", "Embedded Server"], ["EXTERNAL", "External SFTP"], ["GOOGLE_DRIVE", "Google Drive"]].map(([id, label]) => (
                  <button key={id} onClick={() => set("mode", id)}
                    className="px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors"
                    style={{ backgroundColor: form.mode === id ? C.accent : "transparent", color: form.mode === id ? "#fff" : C.muted }}>
                    {label}
                  </button>
                ))}
              </div>
            </div>

            {form.mode === "EMBEDDED" ? (
              <div className="space-y-3">
                <p className="text-xs" style={{ color: C.muted }}>
                  This app hosts its own SFTP server. HR connects into it with these credentials.
                </p>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Port</label>
                  <input type="number" className={inputStyle} style={inputColors}
                    value={form.embeddedPort} onChange={e => set("embeddedPort", Number(e.target.value))} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Username</label>
                  <input className={inputStyle} style={inputColors}
                    value={form.embeddedUsername} onChange={e => set("embeddedUsername", e.target.value)} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Password</label>
                  <input type="password" className={inputStyle} style={inputColors}
                    placeholder={embeddedPasswordSet ? "Leave blank to keep unchanged" : ""}
                    value={form.embeddedPassword} onChange={e => set("embeddedPassword", e.target.value)} />
                </div>
              </div>
            ) : form.mode === "EXTERNAL" ? (
              <div className="space-y-3">
                <p className="text-xs" style={{ color: C.muted }}>
                  Connect out to an SFTP server HR already has. The embedded server is stopped while this mode is active.
                </p>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Host</label>
                  <input className={inputStyle} style={inputColors}
                    value={form.externalHost} onChange={e => set("externalHost", e.target.value)} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Port</label>
                  <input type="number" className={inputStyle} style={inputColors}
                    value={form.externalPort} onChange={e => set("externalPort", Number(e.target.value))} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Username</label>
                  <input className={inputStyle} style={inputColors}
                    value={form.externalUsername} onChange={e => set("externalUsername", e.target.value)} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Password</label>
                  <input type="password" className={inputStyle} style={inputColors}
                    placeholder={externalPasswordSet ? "Leave blank to keep unchanged" : ""}
                    value={form.externalPassword} onChange={e => set("externalPassword", e.target.value)} />
                </div>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Remote Folder Path</label>
                  <input className={inputStyle} style={inputColors} placeholder="/home/hr/cv-inbox"
                    value={form.externalRootPath} onChange={e => set("externalRootPath", e.target.value)} />
                </div>

                <div className="pt-2 border-t" style={{ borderColor: C.border }}>
                  <label className="flex items-center gap-2 text-xs font-semibold cursor-pointer" style={{ color: C.text }}>
                    <input type="checkbox" checked={form.proxyEnabled}
                      onChange={e => set("proxyEnabled", e.target.checked)} />
                    Use HTTP proxy
                  </label>
                  <div className="overflow-hidden transition-all" style={{ maxHeight: form.proxyEnabled ? 200 : 0 }}>
                    <div className="space-y-3 pt-3">
                      <div>
                        <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Proxy Host</label>
                        <input className={inputStyle} style={inputColors}
                          value={form.proxyHost} onChange={e => set("proxyHost", e.target.value)} />
                      </div>
                      <div>
                        <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Proxy Port</label>
                        <input type="number" className={inputStyle} style={inputColors}
                          value={form.proxyPort} onChange={e => set("proxyPort", Number(e.target.value))} />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                <p className="text-xs" style={{ color: C.muted }}>
                  Drop CVs (PDF, Word or text) into a Google Drive folder and click Process now. Each one is screened;
                  processed files move into .CONSIDERED or .REJECTED inside the folder. The embedded SFTP server is stopped while this mode is active.
                </p>
                <ol className="text-xs space-y-1 list-decimal pl-4" style={{ color: C.muted }}>
                  <li>In Google Cloud, enable the <b>Google Drive API</b> and create a <b>service account</b>.</li>
                  <li>On the service account, add a key (<b>Keys, Add key, JSON</b>) and upload that file below.</li>
                  <li>Share your Drive folder with the service account's email as <b>Editor</b>.</li>
                  <li>Paste the folder link below, then Test Connection.</li>
                </ol>
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Service account key (JSON)</label>
                  <input type="file" accept=".json,application/json" className={inputStyle} style={inputColors}
                    onChange={e => handleKeyFile(e.target.files?.[0])} />
                  {keyFileName && <p className="text-xs mt-1" style={{ color: C.success }}>✓ {keyFileName} ready to save</p>}
                  {!keyFileName && googleKeySet && (
                    <p className="text-xs mt-1" style={{ color: C.muted }}>A key is saved. Upload a new file only to replace it.</p>
                  )}
                  {keyError && <p className="text-xs mt-1" style={{ color: C.danger }}>{keyError}</p>}
                </div>
                {googleEmail && (
                  <div className="p-3 rounded-xl text-xs" style={{ backgroundColor: C.surface, color: C.text }}>
                    Share the Drive folder with this address (Editor):
                    <p className="font-semibold mt-1 break-all select-all">{googleEmail}</p>
                  </div>
                )}
                <div>
                  <label className="text-xs font-semibold block mb-1" style={{ color: C.muted }}>Drive folder link or ID</label>
                  <input className={inputStyle} style={inputColors} placeholder="https://drive.google.com/drive/folders/..."
                    value={form.googleFolderId} onChange={e => set("googleFolderId", e.target.value)} />
                </div>
              </div>
            )}

            {testResult && (
              <div className="p-3 rounded-xl text-sm" style={{
                backgroundColor: testResult.success ? "#F0FDF4" : "#FEF2F2",
                color: testResult.success ? C.success : C.danger,
              }}>
                {testResult.success ? "✓ " : "✗ "}{testResult.message}
              </div>
            )}

            <div className="flex gap-2 pt-2">
              <Btn variant="secondary" onClick={handleTestConnection} disabled={testing} className="flex-1 justify-center">
                {testing ? <Spinner size={13} /> : null} {testing ? "Testing…" : "Test Connection"}
              </Btn>
              <Btn onClick={handleSave} disabled={saving} className="flex-1 justify-center">
                {saving ? <Spinner size={13} /> : null} {saving ? "Saving…" : "Save"}
              </Btn>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ============================================================
// CANDIDATE SEARCH + PROFILE
// ============================================================
function CandidateSearchBox({ onOpen }) {
  const [text, setText] = useState("");
  const [hits, setHits] = useState(null); // null = nothing searched yet
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [open, setOpen] = useState(false);
  const boxRef = useRef(null);
  const latest = useRef(0);

  useEffect(() => {
    function closeOnOutside(event) {
      if (boxRef.current && !boxRef.current.contains(event.target)) setOpen(false);
    }
    document.addEventListener("mousedown", closeOnOutside);
    return () => document.removeEventListener("mousedown", closeOnOutside);
  }, []);

  useEffect(() => {
    const q = text.trim();
    if (q.length < 2) { setHits(null); setLoading(false); setError(null); return; }
    const ticket = ++latest.current;
    setLoading(true);
    const timer = setTimeout(async () => {
      try {
        const result = await apiFetch(`/candidates/search?q=${encodeURIComponent(q)}`);
        if (ticket !== latest.current) return; // a newer search superseded this one
        setHits(result); setError(null);
      } catch (e) {
        if (ticket === latest.current) setError(e.message);
      } finally {
        if (ticket === latest.current) setLoading(false);
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [text]);

  function pick(hit) {
    setOpen(false);
    setText("");
    setHits(null);
    onOpen(hit.id);
  }

  function onKeyDown(e) {
    if (e.key === "Escape") setOpen(false);
    if (e.key === "Enter" && hits && hits.length > 0) pick(hits[0]);
  }

  const q = text.trim();
  return (
    <div ref={boxRef} className="relative w-96">
      <div className="flex items-center gap-2 h-8 px-3 rounded-xl border bg-slate-50 focus-within:bg-white focus-within:ring-2 focus-within:ring-sky-200"
        style={{ borderColor: C.border }}>
        <Search size={14} style={{ color: C.muted }} />
        <input
          value={text}
          onChange={e => { setText(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder="Search all candidates by email, name, phone…"
          aria-label="Search candidates"
          className="flex-1 min-w-0 bg-transparent outline-none text-xs"
          style={{ color: C.text }}
        />
        {loading && <Spinner size={12} />}
      </div>
      {open && q.length > 0 && (
        <div className="absolute left-0 right-0 top-10 z-30 rounded-xl border bg-white shadow-lg overflow-hidden"
          style={{ borderColor: C.border }}>
          {q.length < 2 && <p className="px-4 py-3 text-xs" style={{ color: C.muted }}>Type at least 2 characters.</p>}
          {q.length >= 2 && error && <p className="px-4 py-3 text-xs" style={{ color: C.danger }}>{error}</p>}
          {q.length >= 2 && !error && hits && hits.length === 0 && !loading && (
            <p className="px-4 py-3 text-xs" style={{ color: C.muted }}>No candidate matches “{q}”.</p>
          )}
          {!error && hits && hits.map(h => (
            <button key={h.id} onClick={() => pick(h)}
              className="w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-sky-50 border-b last:border-b-0"
              style={{ borderColor: C.border }}>
              <Avatar name={h.fullName} size={30} />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{h.fullName}</p>
                <p className="text-xs truncate" style={{ color: C.muted }}>{h.email}</p>
                <p className="text-xs truncate" style={{ color: C.muted }}>
                  {h.applicationCount === 0 ? "No applications"
                    : `${h.latestRole || "Role"} · ${STAGE_LABELS[h.latestStage] || h.latestStage}${h.applicationCount > 1 ? ` · +${h.applicationCount - 1} more` : ""}`}
                </p>
              </div>
              <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded"
                style={{ color: C.muted, backgroundColor: "#F1F5F9" }}>{h.matchedOn}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const VERDICT_META = {
  STRONG_YES: { label: "Strong yes", color: C.success },
  YES:        { label: "Yes",        color: C.success },
  NO:         { label: "No",         color: C.danger },
  STRONG_NO:  { label: "Strong no",  color: C.danger },
};

function titleCase(s) {
  return (s || "").toLowerCase().replace(/_/g, " ").replace(/^\w/, c => c.toUpperCase());
}

function formatFullDateTime(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-GB", {
    day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
  });
}

function ProfileSection({ title, count, children }) {
  return (
    <Card className="p-5">
      <h2 className="text-sm font-bold mb-3" style={{ color: C.text }}>
        {title}{count !== undefined && <span className="font-normal ml-1.5" style={{ color: C.muted }}>({count})</span>}
      </h2>
      {children}
    </Card>
  );
}

function CandidateProfileView({ candidateId, onBack, onOpenCandidate }) {
  const { data: p, loading, error, reload } = useApi(`/candidates/${candidateId}/profile`, [candidateId]);
  const { data: history } = useApi(`/candidates/${candidateId}/history`, [candidateId]);
  const [showCv, setShowCv] = useState(false);
  const [openEmail, setOpenEmail] = useState(null);
  const [debrief, setDebrief] = useState(null); // { requisitionId, candidateName }

  const back = (
    <button onClick={onBack} className="inline-flex items-center gap-1 text-xs font-semibold mb-4 hover:underline"
      style={{ color: C.accent }}>
      <ChevronRight size={13} style={{ transform: "rotate(180deg)" }} /> Back
    </button>
  );

  if (loading && !p) return <div>{back}<LoadingCard rows={5} /></div>;
  if (error) return <div>{back}<ErrorBanner message={error} onRetry={reload} /></div>;
  if (!p) return null;

  const events = (history?.events || []);

  return (
    <div>
      {back}
      <Card className="p-6 mb-5">
        <div className="flex items-start gap-4">
          <Avatar name={p.fullName} size={56} />
          <div className="flex-1 min-w-0">
            <h1 className="text-2xl font-bold tracking-tight" style={{ color: C.text }}>{p.fullName}</h1>
            {p.headline && <p className="text-sm mt-0.5" style={{ color: C.muted }}>{p.headline}</p>}
            <div className="flex flex-wrap gap-x-6 gap-y-1 mt-3 text-sm" style={{ color: C.text }}>
              <span className="inline-flex items-center gap-1.5"><Mail size={13} style={{ color: C.muted }} />{p.email || "No email"}</span>
              {p.phone && <span>{p.phone}</span>}
              <span style={{ color: C.muted }}>Source: {titleCase(p.sourceChannel)}</span>
              {p.yearsExperience != null && <span style={{ color: C.muted }}>{Number(p.yearsExperience)} yrs experience</span>}
              <span style={{ color: C.muted }}>Added {formatFullDateTime(p.createdAt)}</span>
            </div>
            {p.skills && (
              <div className="flex flex-wrap gap-1.5 mt-3">
                {p.skills.split(",").map(s => s.trim()).filter(Boolean).map(s => (
                  <span key={s} className="text-xs px-2 py-0.5 rounded-full" style={{ backgroundColor: "#EFF9FE", color: "#1479A8" }}>{s}</span>
                ))}
              </div>
            )}
          </div>
        </div>
        {p.sharedEmailWith.length > 0 && (
          <div className="mt-4 p-3 rounded-xl border text-sm flex items-start gap-2"
            style={{ backgroundColor: "#FFFBEB", borderColor: "#FDE68A", color: "#92400E" }}>
            <AlertCircle size={15} className="mt-0.5 shrink-0" />
            <span>
              This email address is also used by{" "}
              {p.sharedEmailWith.map((o, i) => (
                <span key={o.id}>
                  {i > 0 && ", "}
                  <button onClick={() => onOpenCandidate(o.id)} className="font-semibold underline">{o.fullName}</button>
                </span>
              ))}
              . These may be different people (shared inbox) or the same person entered twice.
            </span>
          </div>
        )}
      </Card>

      <div className="grid gap-5">
        <ProfileSection title="Applications" count={p.applications.length}>
          {p.applications.length === 0 && <p className="text-sm" style={{ color: C.muted }}>No applications yet.</p>}
          <div className="grid gap-3">
            {p.applications.map(a => (
              <div key={a.requisitionId} className="rounded-xl border p-4" style={{ borderColor: C.border }}>
                <div className="flex items-center gap-3 flex-wrap">
                  <p className="text-sm font-semibold flex-1 min-w-0" style={{ color: C.text }}>
                    {a.roleTitle || `Role ${a.requisitionId}`}
                    {a.department && <span className="font-normal" style={{ color: C.muted }}> · {a.department}</span>}
                  </p>
                  {a.screeningScore != null && <ScoreRing score={Number(a.screeningScore)} size={40} />}
                  {a.recommendation && <RecBadge rec={a.recommendation} />}
                  <Badge color={a.stage === "REJECTED" ? C.danger : a.stage === "HIRED" ? C.success : C.accent}>
                    {STAGE_LABELS[a.stage] || a.stage}
                  </Badge>
                  {p.interviews.some(i => i.requisitionId === a.requisitionId && i.status === "COMPLETED") && (
                    <button onClick={() => setDebrief({ requisitionId: a.requisitionId })}
                      className="inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1 rounded-lg"
                      style={{ color: C.accent, border: `1px solid ${C.accent}55`, background: `${C.accent}0D` }}>
                      <Sparkles size={11} /> AI Debrief
                    </button>
                  )}
                </div>
                <p className="text-xs mt-1" style={{ color: C.muted }}>In this stage since {formatFullDateTime(a.stageEnteredAt)}</p>
                {a.reviewerDecision && (
                  <p className="text-xs mt-2" style={{ color: C.text }}>
                    <span className="font-semibold">Reviewer decision:</span> {titleCase(a.reviewerDecision)}
                    {a.reviewerNotes && <span style={{ color: C.muted }}> — {a.reviewerNotes}</span>}
                  </p>
                )}
                {a.stage === "REJECTED" && a.stageNotes && (
                  <p className="text-xs mt-1" style={{ color: C.text }}>
                    <span className="font-semibold">Rejection reason:</span> <span style={{ color: C.muted }}>{a.stageNotes}</span>
                  </p>
                )}
                {a.rationale && <p className="text-xs mt-2 leading-relaxed" style={{ color: C.muted }}>{a.rationale}</p>}
              </div>
            ))}
          </div>
        </ProfileSection>

        <ProfileSection title="Interviews" count={p.interviews.length}>
          {p.interviews.length === 0 && <p className="text-sm" style={{ color: C.muted }}>No interviews.</p>}
          <div className="grid gap-3">
            {p.interviews.map(i => {
              const v = i.scorecard ? VERDICT_META[i.scorecard.verdict] : null;
              return (
                <div key={i.id} className="rounded-xl border p-4" style={{ borderColor: C.border }}>
                  <div className="flex items-center gap-3 flex-wrap">
                    <p className="text-sm font-semibold flex-1 min-w-0" style={{ color: C.text }}>
                      {titleCase(i.interviewType)} interview
                      <span className="font-normal" style={{ color: C.muted }}> · {i.roleTitle}</span>
                    </p>
                    <Badge color={i.status === "COMPLETED" ? C.success : i.status === "CANCELLED" ? C.danger : C.accent}>{titleCase(i.status)}</Badge>
                  </div>
                  <p className="text-xs mt-1" style={{ color: C.muted }}>
                    {i.confirmedSlot ? formatFullDateTime(i.confirmedSlot) : "No confirmed time"}
                    {i.interviewerName && ` · with ${i.interviewerName}`}
                  </p>
                  {i.scorecard && (
                    <div className="mt-3 pt-3 border-t" style={{ borderColor: C.border }}>
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-xs font-semibold" style={{ color: C.text }}>Scorecard</span>
                        {v && <Badge color={v.color}>{v.label}</Badge>}
                        {Object.entries(i.scorecard.ratings || {}).map(([k, r]) => (
                          <span key={k} className="text-xs px-2 py-0.5 rounded-full" style={{ backgroundColor: "#F1F5F9", color: C.muted }}>
                            {titleCase(k)}: {r}/5
                          </span>
                        ))}
                      </div>
                      {i.scorecard.notes && <p className="text-xs mt-2 leading-relaxed" style={{ color: C.muted }}>{i.scorecard.notes}</p>}
                      {(i.scorecard.noticePeriod || i.scorecard.expectedSalary) && (
                        <p className="text-xs mt-1" style={{ color: C.muted }}>
                          {i.scorecard.noticePeriod && `Notice period: ${i.scorecard.noticePeriod}`}
                          {i.scorecard.noticePeriod && i.scorecard.expectedSalary && " · "}
                          {i.scorecard.expectedSalary && `Expected salary: ${i.scorecard.expectedSalary}`}
                        </p>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </ProfileSection>

        {p.offers.length > 0 && (
          <ProfileSection title="Offers" count={p.offers.length}>
            <div className="grid gap-2">
              {p.offers.map(o => (
                <div key={o.id} className="flex items-center gap-3 flex-wrap text-sm">
                  <span className="font-semibold flex-1 min-w-0" style={{ color: C.text }}>{o.roleTitle}</span>
                  <span style={{ color: C.muted }}>{o.currency} {Number(o.salaryAmount).toLocaleString()}</span>
                  <span className="text-xs" style={{ color: C.muted }}>Start {o.startDate || "—"} · expires {o.expiryDate || "—"}</span>
                  <Badge color={o.status === "ACCEPTED" ? C.success : o.status === "DECLINED" ? C.danger : C.accent}>{titleCase(o.status)}</Badge>
                </div>
              ))}
            </div>
          </ProfileSection>
        )}

        <ProfileSection title="Emails sent" count={p.emails.length}>
          {p.emails.length === 0 && <p className="text-sm" style={{ color: C.muted }}>No emails recorded.</p>}
          <div className="grid gap-1">
            {p.emails.map(m => (
              <div key={m.id}>
                <button onClick={() => setOpenEmail(openEmail === m.id ? null : m.id)}
                  className="w-full flex items-center gap-3 py-1.5 text-left text-sm hover:bg-slate-50 rounded-lg px-2">
                  <span className="text-xs w-32 shrink-0" style={{ color: C.muted }}>{formatFullDateTime(m.createdAt)}</span>
                  <span className="flex-1 min-w-0 truncate" style={{ color: C.text }}>{m.subject || titleCase(m.type)}</span>
                  <Badge color={m.status === "SENT" ? C.success : C.danger}>{titleCase(m.status)}</Badge>
                </button>
                {openEmail === m.id && (
                  <div className="ml-2 mb-2 p-3 rounded-xl text-xs whitespace-pre-wrap leading-relaxed"
                    style={{ backgroundColor: "#F8FAFC", color: C.text }}>
                    <p className="mb-2" style={{ color: C.muted }}>To: {m.recipientEmail}</p>
                    {m.body}
                  </div>
                )}
              </div>
            ))}
          </div>
        </ProfileSection>

        <ProfileSection title="Timeline" count={events.length}>
          {events.length === 0 && <p className="text-sm" style={{ color: C.muted }}>No activity recorded.</p>}
          <div className="grid gap-2">
            {events.map((e, idx) => (
              <div key={idx} className="flex gap-3 text-sm">
                <span className="text-xs w-32 shrink-0 pt-0.5" style={{ color: C.muted }}>{formatFullDateTime(e.timestamp)}</span>
                <span style={{ color: C.text }}>
                  {e.type === "STAGE" && <>Moved to <b>{STAGE_LABELS[e.stage] || e.stage}</b>{e.requisitionTitle && ` · ${e.requisitionTitle}`}{e.notes && <span style={{ color: C.muted }}> — {e.notes}</span>}</>}
                  {e.type === "INTERVIEW" && <>{titleCase(e.interviewType)} interview · {titleCase(e.interviewStatus)}</>}
                  {e.type === "NOTIFICATION" && <>Email: {e.message || e.channel}</>}
                  {!["STAGE", "INTERVIEW", "NOTIFICATION"].includes(e.type) && titleCase(e.type)}
                </span>
              </div>
            ))}
          </div>
        </ProfileSection>

        <ProfileSection title="Resume">
          {p.resumeText ? (
            <>
              <div className="flex items-center gap-3 mb-2">
                {p.resumeFilename && <span className="text-xs inline-flex items-center gap-1" style={{ color: C.muted }}><FileText size={12} />{p.resumeFilename}</span>}
                <button onClick={() => setShowCv(s => !s)} className="text-xs font-semibold" style={{ color: C.accent }}>
                  {showCv ? "Hide text" : "Show text"}
                </button>
              </div>
              {showCv && (
                <pre className="text-xs whitespace-pre-wrap leading-relaxed p-4 rounded-xl max-h-96 overflow-y-auto"
                  style={{ backgroundColor: "#F8FAFC", color: C.text, fontFamily: "inherit" }}>{p.resumeText}</pre>
              )}
            </>
          ) : <p className="text-sm" style={{ color: C.muted }}>No resume text on file.</p>}
        </ProfileSection>

        {p.recentViews && (
          <ProfileSection title="Who has viewed this profile" count={p.recentViews.length}>
            {p.recentViews.length === 0
              ? <p className="text-sm" style={{ color: C.muted }}>Nobody before you.</p>
              : <div className="grid gap-1">
                  {p.recentViews.map((v, i) => (
                    <p key={i} className="text-xs" style={{ color: C.muted }}>
                      <span className="font-semibold" style={{ color: C.text }}>{v.username}</span> · {formatFullDateTime(v.at)}
                    </p>
                  ))}
                </div>}
            <p className="text-xs mt-2" style={{ color: C.muted }}>Latest 10 views, visible to admins only.</p>
          </ProfileSection>
        )}
      </div>
      {debrief && (
        <DebriefModal requisitionId={debrief.requisitionId} candidateId={p.id} candidateName={p.fullName}
          onClose={() => setDebrief(null)} />
      )}
    </div>
  );
}

// ============================================================
// APP SHELL
// ============================================================
export default function App() {
  if (window.location.pathname === "/refer") return <PublicReferralPage />;
  if (window.location.pathname === "/offer-respond") return <OfferRespondPage />;
  if (window.location.pathname === "/interview-book") return <InterviewBookPage />;

  const [user, setUser] = useState(() => {
    const token = sessionStorage.getItem("jwt");
    if (!token) return null;
    // Parse the JWT payload to get user info stored at login
    try {
      const payload = JSON.parse(atob(token.split(".")[1]));
      return { username: payload.sub };
    } catch { return null; }
  });
  const [userMeta, setUserMeta] = useState(null);
  const [active, setActive] = useState("dashboard");
  const [candidateId, setCandidateId] = useState(null);
  const [cameFrom, setCameFrom] = useState("dashboard");
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const accountMenuRef = useRef(null);

  useEffect(() => {
    function closeAccountMenu(event) {
      if (accountMenuRef.current && !accountMenuRef.current.contains(event.target)) {
        setAccountMenuOpen(false);
      }
    }

    document.addEventListener("mousedown", closeAccountMenu);
    return () => document.removeEventListener("mousedown", closeAccountMenu);
  }, []);

  function handleLogin(userData) {
    setUserMeta(userData);
    setUser({ username: userData.username });
  }

  function handleLogout() {
    clearToken();
    setUser(null);
    setUserMeta(null);
  }

  if (!user) return <LoginScreen onLogin={handleLogin} />;

  const displayUser = userMeta || { fullName: user.username, role: "" };

  function openCandidate(id) {
    if (active !== "candidate") setCameFrom(active);
    setCandidateId(id);
    setActive("candidate");
  }

  const views = {
    dashboard:    <Dashboard setActive={setActive} />,
    intelligence: <IntelligenceView />,
    activity:     <ActivityView setActive={setActive} />,
    roles:        <RolesView setActive={setActive} />,
    pipeline:     <PipelineView />,
    screening:    <ScreeningView />,
    screeningQuality: <ScreeningQualityView />,
    calendar:     <CalendarView />,
    referrals:    <ReferralsView currentUser={displayUser} />,
    scheduling:   null,
    integrations: (
      <div>
        <TopBar title="Integrations" subtitle="Manage job board connections and candidate import sources" />
        <div className="p-8"><ResumeSources /></div>
      </div>
    ),
    cvInbox: <CvInboxView />,
    candidate: candidateId && (
      <CandidateProfileView candidateId={candidateId} onBack={() => setActive(cameFrom)} onOpenCandidate={openCandidate} />
    ),
  };

  return (
    <div className="flex h-screen overflow-hidden"
      style={{ backgroundColor: C.bg, fontFamily: "'Inter',ui-sans-serif,system-ui,sans-serif" }}>
      <Sidebar active={active} setActive={setActive} user={displayUser} />
      <div className="flex-1 flex flex-col overflow-hidden">
        <div className="h-14 shrink-0 flex items-center px-8 border-b bg-white gap-3"
          style={{ borderColor: C.border }}>
          <CandidateSearchBox onOpen={openCandidate} />
          <div className="flex-1" />
          <button
            onClick={() => setAssistantOpen(open => !open)}
            className="h-8 px-3 rounded-xl flex items-center gap-1.5 hover:bg-indigo-50 transition-colors"
            style={{
              color: assistantOpen ? "#1479A8" : C.accent,
              backgroundColor: assistantOpen ? "#DDF3FC" : "#EFF9FE",
            }}
            title="AI assistance"
            aria-label="Open AI assistance"
          >
            <Sparkles size={16} />
            <span className="text-xs font-semibold">AI Assistance</span>
          </button>
          <button className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100 transition-colors relative">
            <Bell size={16} style={{ color: C.muted }} />
            <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full"
              style={{ backgroundColor: C.warning }} />
          </button>
          <div ref={accountMenuRef} className="relative">
            <button
              onClick={() => setAccountMenuOpen(open => !open)}
              className="rounded-full focus:outline-none focus:ring-2 focus:ring-sky-200"
              aria-label="Open account menu"
            >
              <Avatar name={displayUser.fullName} size={32} />
            </button>
            {accountMenuOpen && (
              <div className="absolute right-0 top-11 z-30 w-52 rounded-xl border bg-white p-2 shadow-lg"
                style={{ borderColor: C.border }}>
                <div className="px-3 py-2 border-b mb-1" style={{ borderColor: C.border }}>
                  <p className="text-sm font-semibold truncate" style={{ color: C.text }}>{displayUser.fullName}</p>
                  <p className="text-xs truncate" style={{ color: C.muted }}>{displayUser.role}</p>
                </div>
                <button
                  onClick={handleLogout}
                  className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left hover:bg-red-50"
                  style={{ color: C.danger }}
                >
                  <LogOut size={14} /> Sign out
                </button>
              </div>
            )}
          </div>
        </div>
        <div className="flex-1 flex min-h-0 overflow-hidden">
          <main className="flex-1 overflow-y-auto min-w-0" style={{ padding: active === "pipeline" ? "1.5rem" : "2rem" }}>
            <div className={active === "pipeline" ? "" : "max-w-6xl mx-auto"}>
              {views[active]}
            </div>
          </main>
          <AssistantPanel active={active} open={assistantOpen} onClose={() => setAssistantOpen(false)} />
        </div>
      </div>
    </div>
  );
}
