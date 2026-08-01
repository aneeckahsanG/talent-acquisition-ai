import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  LayoutGrid, Search, FileCheck, UserPlus, Calendar, Activity,
  ChevronRight, ChevronUp, ChevronDown, Sparkles, CheckCircle2, XCircle, AlertCircle, Clock,
  Briefcase, Mail, X, ArrowRight, Bell, LogOut, Loader2, UploadCloud,
  RefreshCw, Plus, Globe,
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
    clearToken();
    window.location.reload();
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

// ============================================================
// DESIGN SYSTEM
// ============================================================
const C = {
  sidebar: "#0F172A",
  accent: "#6366F1",
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
};

const STAGE_LABELS = {
  SOURCED: "Sourced", SCREENED: "Screened", SHORTLISTED: "Shortlisted",
  INTERVIEW_SCHEDULED: "Interview", OFFER: "Offer", HIRED: "Hired", REJECTED: "Rejected",
};

// Linear progression — REFERRAL_MATCHED is a source tag, not a stage
const PIPELINE_STAGES = [
  "SOURCED","SCREENED","SHORTLISTED","INTERVIEW_SCHEDULED","OFFER","HIRED","REJECTED",
];

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
  PENDING:     { color: "#F59E0B", label: "Pending" },
  SENT:        { color: "#6366F1", label: "Sent" },
  ACCEPTED:    { color: "#10B981", label: "Accepted" },
  DECLINED:    { color: "#EF4444", label: "Declined" },
  NEGOTIATING: { color: "#F97316", label: "Negotiating" },
};

const INTERVIEW_ROUND_TYPES = ["PHONE_SCREEN", "TECHNICAL", "FINAL"];
const ROUND_LABELS = { PHONE_SCREEN: "Phone Screen", TECHNICAL: "Technical", FINAL: "Final Round" };
const ROUND_COLORS = { COMPLETED: "#10B981", CONFIRMED: "#6366F1", PROPOSED: "#F59E0B", CANCELLED: "#94A3B8" };

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
  const colors = ["#6366F1","#8B5CF6","#EC4899","#F59E0B","#10B981","#3B82F6","#EF4444"];
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
    primary:   { background: "linear-gradient(135deg,#6366F1,#8B5CF6)", color: "#fff", border: "none" },
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
        className="w-full text-sm px-4 py-2.5 rounded-xl border outline-none focus:ring-2 focus:ring-indigo-200 transition-shadow"
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
            style={{ background: "linear-gradient(135deg,#6366F1,#8B5CF6)" }}>
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
// SIDEBAR
// ============================================================
function Sidebar({ active, setActive, user, onLogout }) {
  const nav = [
    { id: "dashboard",  label: "Dashboard",         icon: LayoutGrid },
    { id: "roles",      label: "Open Roles",         icon: Briefcase  },
    { id: "pipeline",   label: "Pipeline",           icon: Activity   },
    { id: "sourcing",   label: "Sourcing Agent",     icon: Search     },
    { id: "screening",  label: "Screening Agent",    icon: FileCheck  },
    { id: "referrals",  label: "Referral Agent",     icon: UserPlus   },
    { id: "scheduling", label: "Coordination Agent", icon: Calendar   },
  ];

  return (
    <aside className="w-64 shrink-0 flex flex-col" style={{ backgroundColor: C.sidebar }}>
      <div className="px-6 py-6 flex items-center gap-3">
        <div className="w-8 h-8 rounded-xl flex items-center justify-center"
          style={{ background: "linear-gradient(135deg,#6366F1,#8B5CF6)" }}>
          <Sparkles size={16} color="#fff" />
        </div>
        <div>
          <p className="text-white font-bold text-sm tracking-tight">TalentAcquisition AI</p>
          <p className="text-xs" style={{ color: "#475569" }}>Agentic recruiting</p>
        </div>
      </div>

      <nav className="flex-1 px-3 space-y-0.5">
        {nav.map(({ id, label, icon: Icon }) => {
          const active_ = active === id;
          return (
            <button key={id} onClick={() => setActive(id)}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all text-left"
              style={{
                backgroundColor: active_ ? "#1E293B" : "transparent",
                color: active_ ? "#fff" : "#94A3B8",
              }}>
              <Icon size={16} style={{ color: active_ ? "#818CF8" : "#475569" }} />
              {label}
              {active_ && <ChevronRight size={14} className="ml-auto" style={{ color: "#818CF8" }} />}
            </button>
          );
        })}
      </nav>

      <div className="px-4 py-4 border-t" style={{ borderColor: "#1E293B" }}>
        <div className="flex items-center gap-3 px-2 mb-3">
          <Avatar name={user?.fullName || "User"} size={34} />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-white truncate">{user?.fullName}</p>
            <p className="text-xs truncate" style={{ color: "#64748B" }}>{user?.role}</p>
          </div>
        </div>
        <button onClick={onLogout}
          className="w-full flex items-center gap-2 px-3 py-2 rounded-xl text-xs font-medium transition-colors"
          style={{ color: "#64748B" }}>
          <LogOut size={13} /> Sign out
        </button>
      </div>
    </aside>
  );
}

// ============================================================
// DASHBOARD
// ============================================================
function Dashboard({ setActive }) {
  const { data: summary, loading, error, reload } = useApi("/orchestrator/dashboard");

  const stats = summary ? [
    { label: "Open Requisitions",        value: summary.openRequisitions,        icon: Briefcase,     color: C.accent,  nav: "roles"    },
    { label: "Candidates in Pool",        value: summary.totalCandidates,          icon: Activity,      color: "#8B5CF6", nav: "sourcing" },
    { label: "Edge Cases Awaiting Review",value: summary.edgeCasesAwaitingReview,  icon: AlertCircle,   color: C.warning, nav: "screening"},
    { label: "Interviews Scheduled",      value: summary.interviewsScheduled,      icon: Calendar,      color: C.success, nav: "scheduling"},
  ] : [];

  return (
    <div>
      <TopBar title="Dashboard"
        subtitle="Real-time view of agent activity across talent acquisition"
        action={
          <button onClick={reload} className="flex items-center gap-1.5 text-sm font-medium px-3 py-2 rounded-xl border"
            style={{ borderColor: C.border, color: C.muted }}>
            <RefreshCw size={13} /> Refresh
          </button>
        }
      />

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {/* KPI cards */}
      <div className="grid grid-cols-4 gap-4 mb-8">
        {loading
          ? Array.from({ length: 4 }).map((_, i) => (
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
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center mb-3"
                    style={{ backgroundColor: `${s.color}14` }}>
                    <Icon size={18} style={{ color: s.color }} />
                  </div>
                  <p className="text-3xl font-bold font-mono" style={{ color: C.text }}>{s.value}</p>
                  <p className="text-xs mt-1" style={{ color: C.muted }}>{s.label}</p>
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
            <span className="ml-auto flex items-center gap-1.5 text-xs font-medium" style={{ color: C.success }}>
              <span className="w-1.5 h-1.5 rounded-full animate-pulse" style={{ backgroundColor: C.success }} />
              Live
            </span>
          </div>
          <div className="divide-y" style={{ borderColor: C.border }}>
            {loading
              ? Array.from({ length: 5 }).map((_, i) => (
                  <div key={i} className="px-5 py-3.5 flex items-center gap-3">
                    <div className="h-5 w-20 bg-slate-100 rounded-full animate-pulse" />
                    <div className="flex-1 h-3 bg-slate-100 rounded animate-pulse" />
                  </div>
                ))
              : (summary?.recentActivity || []).map((entry) => (
                  <div key={entry.id}
                    className="px-5 py-3.5 flex items-start gap-3 hover:bg-slate-50 transition-colors">
                    <div className="mt-0.5"><AgentPill agent={entry.agentName} /></div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm" style={{ color: C.text }}>
                        <span className="font-semibold">{entry.candidateName || "—"}</span>
                        {entry.requisitionTitle && (
                          <span style={{ color: C.muted }}> · {entry.requisitionTitle}</span>
                        )}
                      </p>
                      <p className="text-xs mt-0.5" style={{ color: C.muted }}>{entry.details}</p>
                    </div>
                    <span className="text-xs font-mono shrink-0" style={{ color: C.muted }}>
                      {timeAgo(entry.createdAt)}
                    </span>
                  </div>
                ))}
          </div>
        </Card>

        {/* Edge cases */}
        <Card className="overflow-hidden">
          <div className="px-5 py-4 border-b flex items-center gap-2" style={{ borderColor: C.border }}>
            <AlertCircle size={16} style={{ color: C.warning }} />
            <h2 className="font-semibold text-sm" style={{ color: C.text }}>Needs Your Decision</h2>
          </div>
          <EdgeCasesList onViewAll={() => setActive("screening")} />
        </Card>
      </div>
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
  const [updatingStatus, setUpdatingStatus] = useState(null);

  const filtered = (roles || []).filter(r => filter === "ALL" || r.status === filter);

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
                <p className="text-sm leading-relaxed" style={{ color: C.text }}>{detail.description}</p>
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
          </div>
          <div className="space-y-4">
            <Card className="p-5 space-y-3">
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Role Settings</p>
              <div className="flex items-center justify-between">
                <span className="text-xs" style={{ color: C.muted }}>Interview Rounds</span>
                <span className="text-sm font-bold" style={{ color: C.accent }}>{detail.requiredInterviewRounds ?? 2}</span>
              </div>
            </Card>
            <Card className="p-5 space-y-3">
              <p className="text-xs font-bold uppercase tracking-wide" style={{ color: C.muted }}>Actions</p>
              <button onClick={() => { setActive("pipeline"); setDetail(null); }}
                className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-white"
                style={{ backgroundColor: C.accent }}>
                <Activity size={14} /> View Pipeline
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
                  <span className="text-[10px] font-bold px-2 py-0.5 rounded-full"
                    style={{ backgroundColor: st.bg, color: st.color }}>{st.label}</span>
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
                  <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Job Description <span style={{ color: C.border }}>(optional)</span></label>
                  <textarea rows={3} placeholder="Responsibilities, team context, what success looks like…" value={roleForm.description}
                    onChange={e => setRoleForm(f => ({ ...f, description: e.target.value }))}
                    className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                    style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
                </div>
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
// ============================================================
// APPLICANTS PANEL — direct applications from public careers page
// ============================================================
function ApplicantsPanel({ requisitionId }) {
  const { data, loading, reload } = useApi(`/requisitions/${requisitionId}/applicants`, [requisitionId]);
  const [acting, setActing] = useState(null);

  const applicants = data || [];

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
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-medium"
                    style={{ backgroundColor: rec.bg, color: rec.color }}>
                    <Sparkles size={12} />
                    AI Screening: <strong>{rec.label}</strong>
                    {a.screeningScore != null && <span className="ml-1 opacity-70">({Math.round(a.screeningScore)}/100)</span>}
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
                  <p className="text-xs" style={{ color: C.success }}>✓ Added to pipeline at Screening stage</p>
                )}
                {a.status === "REJECTED" && (
                  <p className="text-xs" style={{ color: "#dc2626" }}>✕ Rejected — email notification sent</p>
                )}
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}

function PipelineView() {
  const { data: reqs } = useApi("/requisitions");
  const [reqId, setReqId] = useState(null);
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
  const [scheduleModal, setScheduleModal] = useState(null);
  const [scheduleForm, setScheduleForm] = useState({ interviewType: "PHONE_SCREEN", confirmedSlot: "", notes: "" });
  const [scheduling, setScheduling] = useState(false);
  const [rescheduleModal, setRescheduleModal] = useState(null); // { interviewId, roundNumber, interviewType }
  const [rescheduleSlot, setRescheduleSlot] = useState("");
  const [rescheduling, setRescheduling] = useState(false);
  const [recordResponseModal, setRecordResponseModal] = useState(null); // { interviewId, roundNumber }
  const [recordReplyText, setRecordReplyText] = useState("");
  const [recordingResponse, setRecordingResponse] = useState(false);
  const [offerModal, setOfferModal] = useState(null);
  const [offerForm, setOfferForm] = useState({ salaryAmount: "", currency: "MYR", startDate: "", expiryDate: "", notes: "" });
  const [submittingOffer, setSubmittingOffer] = useState(false);
  const [updatingOfferId, setUpdatingOfferId] = useState(null);
  const [negotiateForm, setNegotiateForm] = useState({}); // offerId -> { salaryAmount, startDate, notes, changedBy }
  const [savingTerms, setSavingTerms] = useState(null);
  const [letterModal, setLetterModal] = useState(null); // null | { offerId, candidateName, candidateEmail, letterText }
  const [generatingLetter, setGeneratingLetter] = useState(null); // offerId being generated
  const [sendingOfferEmail, setSendingOfferEmail] = useState(false);
  const [offerEmailSent, setOfferEmailSent] = useState(false);
  const [copied, setCopied] = useState(false);
  const [newRoleModal, setNewRoleModal] = useState(false);
  const [roleForm, setRoleForm] = useState({ title: "", department: "", location: "", experienceLevel: "MID", description: "", requiredSkills: "", requiredInterviewRounds: 2 });
  const [savingRole, setSavingRole] = useState(false);

  useEffect(() => {
    if (reqs && reqs.length > 0 && !reqId) setReqId(reqs[0].id);
  }, [reqs, reqId]);

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

  const { data: board, loading, error, reload } = useApi(
    reqId ? `/orchestrator/pipeline/${reqId}` : null,
    [reqId]
  );

  async function handleMove(candidateId, nextStage) {
    setMovingId(candidateId);
    try {
      await apiFetch(`/orchestrator/pipeline/${reqId}/candidates/${candidateId}/stage`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stage: nextStage }),
      });
      reload();
    } finally {
      setMovingId(null);
    }
  }

  async function handleCompleteRound(interviewId) {
    setCompletingRoundId(interviewId);
    try {
      await apiFetch(`/orchestrator/interviews/${interviewId}/complete`, { method: "PATCH" });
      reload();
    } finally {
      setCompletingRoundId(null);
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
    try {
      await apiFetch(`/orchestrator/pipeline/${reqId}/candidates/${offerModal.candidateId}/offer`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...offerForm, salaryAmount: offerForm.salaryAmount ? Number(offerForm.salaryAmount) : null }),
      });
      setOfferModal(null);
      setOfferForm({ salaryAmount: "", currency: "MYR", startDate: "", expiryDate: "", notes: "" });
      reload();
    } finally {
      setSubmittingOffer(false);
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
      const result = await apiFetch(`/orchestrator/pipeline/${reqId}/candidates/${scheduleModal.candidateId}/schedule-interview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(scheduleForm),
      });
      setScheduleForm({ interviewType: "PHONE_SCREEN", confirmedSlot: "", notes: "" });
      // Stay in modal to show the invitation email that was sent
      setScheduleModal(prev => ({ ...prev, confirmed: true, invitationEmail: result.invitationEmail, candidateEmail: result.candidateEmail }));
      reload();
    } finally {
      setScheduling(false);
    }
  }

  const visibleStages = PIPELINE_STAGES.filter(s => s !== "REJECTED");

  return (
    <div>
      <TopBar title="Pipeline Orchestrator"
        subtitle="Unified candidate flow across all agents"
        action={
          <div className="flex items-center gap-2">
            {reqs && (
              <Select value={reqId || ""} onChange={e => setReqId(Number(e.target.value))}>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
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

      {error && <ErrorBanner message={error} onRetry={reload} />}

      {loading && (
        <div className="flex items-center justify-center py-20">
          <Spinner size={24} />
        </div>
      )}

      {board && (
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
                      <div key={c.candidateId} className="p-3 rounded-xl border"
                        style={{ borderColor: C.border, backgroundColor: "#F8FAFC" }}>
                        <div className="flex items-center gap-2 mb-1.5">
                          <Avatar name={c.candidateName} size={28} />
                          <p className="text-xs font-semibold truncate" style={{ color: C.text }}>
                            {c.candidateName}
                          </p>
                        </div>
                        {(c.screeningScore ?? c.sourcingMatchScore) != null && (
                          <ScoreRing score={c.screeningScore ?? c.sourcingMatchScore} size={30} />
                        )}
                        {c.updatedByAgent && (
                          <p className="text-[10px] font-semibold mt-1"
                            style={{ color: AGENT_META[c.updatedByAgent]?.color || C.muted }}>
                            {AGENT_META[c.updatedByAgent]?.label || c.updatedByAgent}
                          </p>
                        )}
                        {/* Interview history summary — shown on OFFER cards */}
                        {stage === "OFFER" && c.interviewRounds?.length > 0 && (
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
                              </div>
                            )}

                            {/* Negotiation history */}
                            {c.offer?.history?.length > 0 && (
                              <div className="space-y-1">
                                <p className="text-[9px] font-semibold uppercase tracking-wide" style={{ color: C.muted }}>Negotiation History</p>
                                {c.offer.history.map(h => (
                                  <div key={h.roundNumber} className="flex gap-1.5 items-start">
                                    <div className="w-3.5 h-3.5 rounded-full flex items-center justify-center shrink-0 mt-0.5 text-[7px] font-bold text-white"
                                      style={{ backgroundColor: h.changedBy === "CANDIDATE" ? C.accent : C.warning }}>
                                      {h.roundNumber}
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

                                  {/* Status actions */}
                                  {offer.status === "PENDING" && (
                                    <button
                                      disabled={generatingLetter === offer.id}
                                      onClick={() => handleGenerateLetter(offer.id)}
                                      className="w-full text-[10px] font-semibold py-1 rounded-lg text-white flex items-center justify-center gap-1"
                                      style={{ backgroundColor: C.accent, opacity: generatingLetter === offer.id ? 0.5 : 1 }}>
                                      {generatingLetter === offer.id ? <><Spinner size={10} /> Generating…</> : <><Sparkles size={10} /> Generate & Send</>}
                                    </button>
                                  )}
                                  {offer.status === "SENT" && (
                                    <div className="grid grid-cols-3 gap-1">
                                      {[["ACCEPTED","Accept",C.success],["NEGOTIATING","Negotiate",C.warning],["DECLINED","Decline",C.danger]].map(([s,l,col]) => (
                                        <button key={s} disabled={isUpdating} onClick={() => handleOfferStatus(offer.id, s)}
                                          className="text-[9px] font-semibold py-1 rounded-lg text-white"
                                          style={{ backgroundColor: col, opacity: isUpdating ? 0.5 : 1 }}>
                                          {l}
                                        </button>
                                      ))}
                                    </div>
                                  )}
                                  {/* Negotiation history — always visible when history exists */}
                                  {offer.history?.length > 0 && (
                                    <div className="space-y-1 pt-1" style={{ borderTop: `1px solid ${C.border}` }}>
                                      <p className="text-[9px] font-bold uppercase tracking-wide" style={{ color: C.muted }}>Negotiation History</p>
                                      {offer.history.map(h => (
                                        <div key={h.roundNumber} className="flex gap-1.5 items-start">
                                          <div className="w-3.5 h-3.5 rounded-full flex items-center justify-center shrink-0 mt-0.5 text-[7px] font-bold text-white"
                                            style={{ backgroundColor: h.changedBy === "CANDIDATE" ? C.accent : C.warning }}>
                                            {h.roundNumber}
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
                                          <p className="text-[9px] font-semibold" style={{ color: C.muted }}>Update Terms</p>
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
                                          <div className="flex gap-1">
                                            {["RECRUITER","CANDIDATE"].map(who => (
                                              <button key={who} onClick={() => setNf({ changedBy: who })}
                                                className="flex-1 text-[9px] py-0.5 rounded-md border font-semibold"
                                                style={{
                                                  borderColor: (nf.changedBy || "RECRUITER") === who ? C.accent : C.border,
                                                  color: (nf.changedBy || "RECRUITER") === who ? C.accent : C.muted,
                                                  backgroundColor: (nf.changedBy || "RECRUITER") === who ? `${C.accent}10` : "transparent"
                                                }}>{who === "RECRUITER" ? "By Us" : "By Candidate"}</button>
                                            ))}
                                          </div>
                                          <button disabled={savingTerms === offer.id} onClick={() => handleUpdateTerms(offer.id)}
                                            className="w-full text-[9px] font-semibold py-1 rounded-md text-white"
                                            style={{ backgroundColor: C.warning, opacity: savingTerms === offer.id ? 0.5 : 1 }}>
                                            {savingTerms === offer.id ? "Saving…" : "Save Counter Offer"}
                                          </button>
                                        </div>

                                        {/* Resolve buttons */}
                                        <div className="grid grid-cols-2 gap-1 pt-1">
                                          {[["ACCEPTED","✓ Accept",C.success],["DECLINED","✗ Decline",C.danger]].map(([s,l,col]) => (
                                            <button key={s} disabled={isUpdating} onClick={() => handleOfferStatus(offer.id, s)}
                                              className="text-[9px] font-semibold py-1.5 rounded-lg text-white"
                                              style={{ backgroundColor: col, opacity: isUpdating ? 0.5 : 1 }}>
                                              {l}
                                            </button>
                                          ))}
                                        </div>
                                      </div>
                                    );
                                  })()}
                                </div>
                              ) : (
                                <button onClick={() => setOfferModal({ candidateId: c.candidateId, candidateName: c.candidateName })}
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
                                      <div className="flex items-center gap-2 p-2"
                                        style={{ background: `${ROUND_COLORS[r.status]}15` }}>
                                        <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: ROUND_COLORS[r.status] }} />
                                        <div className="flex-1 min-w-0">
                                          <p className="text-[10px] font-semibold" style={{ color: C.text }}>
                                            R{r.roundNumber} · {ROUND_LABELS[r.interviewType] || r.interviewType}
                                          </p>
                                          {r.confirmedSlot && (
                                            <p className="text-[9px]" style={{ color: C.muted }}>
                                              {new Date(r.confirmedSlot).toLocaleDateString("en-GB", { day:"numeric", month:"short", hour:"2-digit", minute:"2-digit" })}
                                            </p>
                                          )}
                                        </div>
                                        <span className="text-[9px] font-bold uppercase" style={{ color: ROUND_COLORS[r.status] }}>
                                          {r.status === "COMPLETED" ? "✓ Done" : r.status === "CANCELLED" ? "✗" : r.status.toLowerCase()}
                                        </span>
                                        {(r.status === "CONFIRMED" || r.status === "PROPOSED") && (
                                          <div className="flex gap-1 flex-shrink-0">
                                            <button
                                              disabled={completingRoundId === r.id}
                                              onClick={() => handleCompleteRound(r.id)}
                                              className="text-[9px] font-semibold px-1.5 py-0.5 rounded-md text-white"
                                              style={{ backgroundColor: C.success, opacity: completingRoundId === r.id ? 0.5 : 1 }}>
                                              {completingRoundId === r.id ? "…" : "Done"}
                                            </button>
                                            <button
                                              onClick={() => { setRescheduleModal({ interviewId: r.id, roundNumber: r.roundNumber, interviewType: r.interviewType }); setRescheduleSlot(""); }}
                                              className="text-[9px] font-semibold px-1.5 py-0.5 rounded-md"
                                              style={{ color: C.warning, border: `1px solid ${C.warning}55`, background: `${C.warning}10` }}>
                                              Reschedule
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
                                {/* Schedule next round — only if there's a round type left and no active round, OR no rounds yet */}
                                {(!activeRound || rounds.length === 0) && !allCompleted && (
                                  <button
                                    onClick={() => {
                                      setScheduleForm(f => ({ ...f, interviewType: nextType, confirmedSlot: "", notes: "" }));
                                      setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, nextType });
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
                                          setScheduleForm(f => ({ ...f, interviewType: nextType, confirmedSlot: "", notes: "" }));
                                          setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, nextType });
                                        }}
                                        className="w-full text-[10px] font-semibold py-1 rounded-lg"
                                        style={{ color: C.accent, border: `1px solid ${C.accent}55` }}>
                                        + Add Round {rounds.length + 1}
                                      </button>
                                    )}
                                    <button
                                      onClick={() => setOfferModal({ candidateId: c.candidateId, candidateName: c.candidateName })}
                                      className="w-full text-[10px] font-semibold py-1 rounded-lg text-white"
                                      style={{ backgroundColor: C.success }}>
                                      Advance to Offer
                                    </button>
                                  </>
                                )}
                                <button disabled={isMoving}
                                  onClick={() => handleMove(c.candidateId, "REJECTED")}
                                  className="w-full text-[10px] font-semibold py-1 rounded-lg transition-opacity"
                                  style={{ color: C.danger, border: `1px solid ${C.danger}33`, opacity: isMoving ? 0.5 : 1 }}>
                                  Reject
                                </button>
                              </div>
                            </div>
                          );
                        })()}

                        {/* Stage action buttons for non-interview stages */}
                        {stage !== "INTERVIEW_SCHEDULED" && actions.length > 0 && (
                          <div className="mt-2 space-y-1">
                            {actions.map(({ label, next }) => (
                              <button key={next} disabled={isMoving}
                                onClick={() => next === "INTERVIEW_SCHEDULED"
                                  ? setScheduleModal({ candidateId: c.candidateId, candidateName: c.candidateName, nextType: "PHONE_SCREEN" })
                                  : handleMove(c.candidateId, next)
                                }
                                className="w-full text-[10px] font-semibold py-1 rounded-lg text-white transition-opacity"
                                style={{ backgroundColor: C.accent, opacity: isMoving ? 0.5 : 1 }}>
                                {isMoving ? "Moving…" : label}
                              </button>
                            ))}
                            {stage !== "REJECTED" && (
                              <button disabled={isMoving}
                                onClick={() => handleMove(c.candidateId, "REJECTED")}
                                className="w-full text-[10px] font-semibold py-1 rounded-lg transition-opacity"
                                style={{ color: C.danger, border: `1px solid ${C.danger}33`, opacity: isMoving ? 0.5 : 1 }}>
                                Reject
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
                <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>
                  Job Description <span style={{ color: C.border }}>(optional)</span>
                </label>
                <textarea rows={3} placeholder="Responsibilities, team context, what success looks like…"
                  value={roleForm.description}
                  onChange={e => setRoleForm(f => ({ ...f, description: e.target.value }))}
                  className="w-full px-3 py-2 rounded-xl border text-sm resize-none"
                  style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }} />
              </div>
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
              <button onClick={() => setLetterModal(null)}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} style={{ color: C.muted }} />
              </button>
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
              style={{ borderColor: C.border, backgroundColor: "#FAFAFA" }}>
              <pre className="text-sm whitespace-pre-wrap font-sans leading-relaxed"
                style={{ color: C.text }}>{letterModal.letterText}</pre>
            </div>

            {/* Actions */}
            <div className="flex gap-2 shrink-0">
              <Btn variant="primary" onClick={() => {
                navigator.clipboard.writeText(letterModal.letterText);
                setCopied("letter");
                setTimeout(() => setCopied(false), 2000);
              }}>
                {copied === "letter" ? "✓ Copied!" : "Copy Letter"}
              </Btn>
              <button
                onClick={() => {
                  const blob = new Blob([letterModal.letterText], { type: "text/plain" });
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
                  setSendingOfferEmail(true);
                  try {
                    await apiFetch(`/orchestrator/offers/${letterModal.offerId}/send-email`, {
                      method: "POST",
                      body: JSON.stringify({ letterText: letterModal.letterText }),
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
              <Btn variant="secondary" onClick={() => setLetterModal(null)}>Cancel</Btn>
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

            <div className="flex gap-2 mt-5">
              <Btn variant="primary" onClick={handleCreateOffer} disabled={submittingOffer}>
                {submittingOffer ? <><Spinner size={13} /> Saving…</> : "Extend Offer"}
              </Btn>
              <Btn variant="secondary" onClick={() => setOfferModal(null)}>Cancel</Btn>
            </div>
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
                  <span className="text-xs font-semibold" style={{ color: C.success }}>Interview confirmed &amp; invitation sent</span>
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

                  {/* Date & time */}
                  <div>
                    <label className="block text-xs font-semibold mb-1.5" style={{ color: C.muted }}>Date & Time</label>
                    <input
                      type="datetime-local"
                      value={scheduleForm.confirmedSlot}
                      onChange={e => setScheduleForm(f => ({ ...f, confirmedSlot: e.target.value }))}
                      className="w-full px-3 py-2 rounded-xl border text-sm"
                      style={{ borderColor: C.border, color: C.text, backgroundColor: C.bg }}
                    />
                  </div>

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
                  <Btn variant="primary" onClick={handleScheduleInterview} disabled={!scheduleForm.confirmedSlot || scheduling}>
                    {scheduling ? <><Spinner size={13} /> Scheduling…</> : <><Calendar size={13} /> Confirm & Send Invite</>}
                  </Btn>
                  <Btn variant="secondary" onClick={() => setScheduleModal(null)}>Cancel</Btn>
                </div>
              </>
            )}
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

  async function syncOne(id) {
    setSyncing(s => ({ ...s, [id]: true }));
    await apiFetch(`/sourcing/resume-sources/${id}/sync`, { method: "POST" });
    setTimeout(() => { setSyncing(s => ({ ...s, [id]: false })); reloadSources(); }, 4000);
  }

  async function syncAll() {
    await apiFetch("/sourcing/resume-sources/sync-all", { method: "POST" });
    sources?.forEach(s => setSyncing(prev => ({ ...prev, [s.id]: true })));
    setTimeout(() => { setSyncing({}); reloadSources(); }, 5000);
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
  const [reqId, setReqId] = useState(null);
  const [sourcingTab, setSourcingTab] = useState("matches"); // "matches" | "sources"
  const [showForm, setShowForm] = useState(false);
  const [inputMode, setInputMode] = useState("manual");
  const [submitting, setSubmitting] = useState(false);
  const [csvResult, setCsvResult] = useState(null);

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

  useEffect(() => {
    if (reqs && reqs.length > 0 && !reqId) setReqId(reqs[0].id);
  }, [reqs, reqId]);

  const { data: matches, loading, error, reload } = useApi(
    reqId ? `/sourcing/requisition/${reqId}/matches` : null,
    [reqId]
  );

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
      await apiFetch("/sourcing/talent-pool", { method: "POST", body: JSON.stringify(form) });
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
              <Select value={reqId || ""} onChange={e => setReqId(Number(e.target.value))}>
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
              <ScoreRing score={m.matchScore} />
              <div className="flex-1 min-w-0">
                <div className="flex items-start justify-between gap-2 mb-1">
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-semibold" style={{ color: C.text }}>{m.candidateName}</p>
                      {m.matchScore >= 70 && <Badge color={C.warning}>HIGH FIT</Badge>}
                      <Badge color={m.status === "DISMISSED" ? C.muted : C.accent}>{m.status}</Badge>
                    </div>
                    {m.candidateHeadline && (
                      <p className="text-xs mt-0.5" style={{ color: C.muted }}>{m.candidateHeadline}</p>
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
                        <span className="text-xs font-semibold" style={{ color: C.muted }}>Screening:</span>
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
                            const toList = v => !v ? [] : Array.isArray(v) ? v : typeof v === "string" ? v.split(/,\s*/).filter(Boolean) : [];
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
                    style={{ borderColor: uploadFile ? C.accent : C.border, background: uploadFile ? "#EEF2FF" : "transparent" }}>
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
                      style={{ borderColor: csvFile ? C.accent : C.border, background: csvFile ? "#EEF2FF" : "transparent" }}>
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

          {result.reviewerDecision && (
            <div className="p-4 rounded-2xl" style={{ backgroundColor: "#F0FDF4", border: "1px solid #A7F3D0" }}>
              <p className="text-sm font-semibold" style={{ color: C.success }}>
                ✓ Human decision recorded: {result.reviewerDecision}
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
  const [reqId, setReqId] = useState(null);
  const [selected, setSelected] = useState(null);
  const [showUpload, setShowUpload] = useState(false);
  const [uploadForm, setUploadForm] = useState({ candidateName:"", candidateEmail:"" });
  const [uploadFile, setUploadFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [shortlisting, setShortlisting] = useState(null);
  const [rejecting, setRejecting] = useState(null);
  const [uploadError, setUploadError] = useState(null);
  const [parsing, setParsing] = useState(false);
  const fileRef = useRef();

  useEffect(() => {
    if (reqs && reqs.length > 0 && !reqId) setReqId(reqs[0].id);
  }, [reqs, reqId]);

  const { data: results, loading, error, reload } = useApi(
    reqId ? `/screening/requisition/${reqId}` : null,
    [reqId]
  );

  async function handleDecision(screeningId, decision, notes) {
    await apiFetch(`/screening/${screeningId}/review`, {
      method: "POST",
      body: JSON.stringify({ decision, notes }),
    });
    reload();
  }

  async function handleShortlist(candidateId) {
    setShortlisting(candidateId);
    try {
      await apiFetch(`/orchestrator/pipeline/${reqId}/candidates/${candidateId}/stage`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stage: "SHORTLISTED" }),
      });
      reload();
    } finally {
      setShortlisting(null);
    }
  }

  async function handleReject(screeningId) {
    setRejecting(screeningId);
    try {
      await apiFetch(`/screening/${screeningId}/review`, {
        method: "POST",
        body: JSON.stringify({ decision: "REJECT", notes: "Rejected by recruiter after screening review" }),
      });
      reload();
    } finally {
      setRejecting(null);
    }
  }

  async function handleUpload(e) {
    e.preventDefault();
    if (!uploadFile) return;
    setUploading(true);
    setUploadError(null);
    const fd = new FormData();
    fd.append("candidateName", uploadForm.candidateName);
    fd.append("candidateEmail", uploadForm.candidateEmail);
    fd.append("requisitionId", String(reqId));
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

  const sortedResults = [...(results || [])].sort((a, b) => b.overallScore - a.overallScore);

  return (
    <div>
      <TopBar title="Screening Agent"
        subtitle="Standardized resume scoring — consistent across every recruiter"
        action={
          <div className="flex gap-2">
            {reqs && (
              <Select value={reqId || ""} onChange={e => setReqId(Number(e.target.value))}>
                {reqs.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
              </Select>
            )}
            <Btn onClick={() => setShowUpload(true)}>
              <UploadCloud size={14} /> Screen Resume
            </Btn>
          </div>
        }
      />

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
                        </div>
                      </div>
                    </td>
                    <td className="px-5 py-4"><ScoreRing score={r.overallScore} size={40} /></td>
                    <td className="px-5 py-4"><RecBadge rec={r.recommendation} /></td>
                    <td className="px-5 py-4">
                      {(() => {
                        const stage = r.pipelineStage;
                        if (stage === "SHORTLISTED") return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.success }}>Shortlisted</span>;
                        if (stage === "REJECTED")    return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.danger }}>Rejected</span>;
                        if (stage === "INTERVIEW_SCHEDULED") return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.accent }}>Interview</span>;
                        if (stage === "OFFER")       return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.accent }}>Offer</span>;
                        if (stage === "HIRED")       return <span className="text-xs font-semibold px-2 py-0.5 rounded-full text-white" style={{ backgroundColor: C.success }}>Hired</span>;
                        return <span className="text-xs font-semibold" style={{ color: C.warning }}>Awaiting review</span>;
                      })()}
                    </td>
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2 flex-wrap">
                        {!["SHORTLISTED","INTERVIEW_SCHEDULED","OFFER","HIRED","REJECTED"].includes(r.pipelineStage) && (() => {
                          const rec = r.recommendation;
                          return (<>
                            {/* Always show Shortlist — even for REJECT recommendation the recruiter can override */}
                            <button
                              disabled={shortlisting === r.candidateId}
                              onClick={() => handleShortlist(r.candidateId)}
                              className="text-xs font-semibold px-3 py-1.5 rounded-lg text-white"
                              style={{ backgroundColor: C.success, opacity: shortlisting === r.candidateId ? 0.5 : 1 }}>
                              {shortlisting === r.candidateId ? "…" : rec === "REJECT" ? "Override & Shortlist" : "Shortlist"}
                            </button>
                            {/* Show Reject button — only for REJECT or REVIEW recommendations */}
                            {(rec === "REJECT" || rec === "REVIEW") && (
                              <button
                                disabled={rejecting === r.id}
                                onClick={() => handleReject(r.id)}
                                className="text-xs font-semibold px-3 py-1.5 rounded-lg"
                                style={{ color: C.danger, border: `1px solid ${C.danger}55`, backgroundColor: `${C.danger}08`, opacity: rejecting === r.id ? 0.5 : 1 }}>
                                {rejecting === r.id ? "…" : "Reject"}
                              </button>
                            )}
                          </>);
                        })()}
                        <Btn variant="secondary" onClick={() => setSelected(r)}>View</Btn>
                      </div>
                    </td>
                  </tr>
                ))}
          </tbody>
        </table>
        {!loading && sortedResults.length === 0 && (
          <p className="text-center py-12 text-sm" style={{ color: C.muted }}>
            No screening results yet. Upload a resume to screen.
          </p>
        )}
      </Card>

      <ScreeningDetailModal
        result={selected}
        onClose={() => setSelected(null)}
        onDecision={handleDecision}
      />

      {showUpload && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backgroundColor: "rgba(15,23,42,0.5)", backdropFilter: "blur(4px)" }}>
          <Card className="w-full max-w-md p-6">
            <div className="flex items-center justify-between mb-5">
              <h2 className="font-bold text-lg" style={{ color: C.text }}>Screen a Resume</h2>
              <button onClick={() => { setShowUpload(false); setUploadError(null); }}
                className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100">
                <X size={16} />
              </button>
            </div>
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
                  className="border-2 border-dashed rounded-xl p-6 text-center cursor-pointer hover:border-indigo-300 transition-colors"
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
        candidateName: parsed.name || f.candidateName,
        candidateEmail: parsed.email || f.candidateEmail,
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
            setForm(f => ({ ...f, referrerName: currentUser.fullName || f.referrerName }));
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
                style={{ background: "linear-gradient(135deg,#EEF2FF,#F5F3FF)", border: "1px solid #C7D2FE" }}>
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
                      className="text-xs font-mono px-4 py-2 rounded-xl border hover:border-indigo-300 hover:bg-indigo-50 transition-colors flex items-center gap-1.5"
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
                style={{ background: "linear-gradient(135deg,#EEF2FF,#F5F3FF)", border: "1px solid #C7D2FE" }}>
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
  const { data: reqs } = useApi("/requisitions?status=OPEN");
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
      fd.append("resume", file);
      const parsed = await apiFetch("/screening/parse-resume", { method: "POST", body: fd });
      setForm(f => ({
        ...f,
        candidateName: parsed.name || f.candidateName,
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
    <div className="min-h-screen flex items-center justify-center p-4" style={{ backgroundColor: C.bg }}>
      <div className="w-full max-w-lg">
        <div className="flex items-center gap-3 justify-center mb-6">
          <div className="w-10 h-10 rounded-2xl flex items-center justify-center"
            style={{ background: "linear-gradient(135deg,#6366F1,#8B5CF6)" }}>
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
              style={{ background: "linear-gradient(135deg,#EEF2FF,#F5F3FF)", border: "1px solid #C7D2FE" }}>
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
// APP SHELL
// ============================================================
export default function App() {
  if (window.location.pathname === "/refer") return <PublicReferralPage />;

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

  const views = {
    dashboard:  <Dashboard setActive={setActive} />,
    roles:      <RolesView setActive={setActive} />,
    pipeline:   <PipelineView />,
    sourcing:   <SourcingView setActive={setActive} />,
    screening:  <ScreeningView />,
    referrals:  <ReferralsView currentUser={displayUser} />,
    scheduling: <SchedulingView />,
  };

  return (
    <div className="flex h-screen overflow-hidden"
      style={{ backgroundColor: C.bg, fontFamily: "'Inter',ui-sans-serif,system-ui,sans-serif" }}>
      <Sidebar active={active} setActive={setActive} user={displayUser} onLogout={handleLogout} />
      <div className="flex-1 flex flex-col overflow-hidden">
        <div className="h-14 shrink-0 flex items-center px-8 border-b bg-white gap-3"
          style={{ borderColor: C.border }}>
          <div className="flex-1" />
          <button className="w-8 h-8 rounded-xl flex items-center justify-center hover:bg-slate-100 transition-colors relative">
            <Bell size={16} style={{ color: C.muted }} />
            <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full"
              style={{ backgroundColor: C.warning }} />
          </button>
          <Avatar name={displayUser.fullName} size={32} />
        </div>
        <main className="flex-1 overflow-y-auto" style={{ padding: active === "pipeline" ? "1.5rem" : "2rem" }}>
          <div className={active === "pipeline" ? "" : "max-w-6xl mx-auto"}>
            {views[active]}
          </div>
        </main>
      </div>
    </div>
  );
}
