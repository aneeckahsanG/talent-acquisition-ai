import { useEffect, useState } from "react";

const API = import.meta.env.VITE_API_BASE ?? "http://localhost:8080/api";

async function apiFetch(path, opts = {}) {
  const res = await fetch(API + path, {
    headers: { "Content-Type": "application/json", ...(opts.headers ?? {}) },
    ...opts,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json().catch(() => null);
}

function Logo() {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 32 }}>
      <div style={{
        width: 44, height: 44, borderRadius: 10,
        background: "linear-gradient(135deg,#6366F1,#8B5CF6)",
        display: "flex", alignItems: "center", justifyContent: "center",
        color: "#fff", fontWeight: "bold", fontSize: 18,
      }}>TA</div>
      <div>
        <div style={{ fontWeight: "bold", fontSize: 17, color: "#1E293B" }}>TalentAcquisition AI</div>
        <div style={{ fontSize: 12, color: "#64748B" }}>Agentic Talent Sourcing Platform</div>
      </div>
    </div>
  );
}

function Card({ children }) {
  return (
    <div style={{
      background: "#fff", borderRadius: 16, padding: "36px 40px",
      boxShadow: "0 4px 24px rgba(0,0,0,0.08)", maxWidth: 520, width: "100%",
      boxSizing: "border-box",
    }}>
      {children}
    </div>
  );
}

function Btn({ children, onClick, color = "#6366F1", disabled, style }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      style={{
        padding: "12px 24px", borderRadius: 10, border: "none", cursor: disabled ? "not-allowed" : "pointer",
        background: disabled ? "#CBD5E1" : color, color: "#fff", fontWeight: 700, fontSize: 14,
        transition: "opacity 0.15s", opacity: disabled ? 0.7 : 1, ...style,
      }}>
      {children}
    </button>
  );
}

// ── Negotiate form ──────────────────────────────────────────
function NegotiateForm({ token, offerInfo, onDone }) {
  const [salary, setSalary] = useState(offerInfo?.salaryAmount ?? "");
  const [startDate, setStartDate] = useState(offerInfo?.startDate ?? "");
  const [notes, setNotes] = useState("");
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setLoading(true);
    setErr(null);
    try {
      const res = await apiFetch("/public/offer-respond/negotiate", {
        method: "POST",
        body: JSON.stringify({
          token,
          counterSalary: salary ? parseFloat(salary) : null,
          counterStartDate: startDate || null,
          notes: notes || null,
        }),
      });
      if (res?.success) onDone(res.message);
      else setErr(res?.error ?? "Submission failed.");
    } catch (ex) {
      setErr(ex.message);
    } finally {
      setLoading(false);
    }
  }

  const labelStyle = { fontSize: 12, fontWeight: 700, color: "#64748B", marginBottom: 4, display: "block", textTransform: "uppercase", letterSpacing: "0.05em" };
  const inputStyle = { width: "100%", padding: "10px 14px", borderRadius: 8, border: "1.5px solid #E2E8F0", fontSize: 14, color: "#1E293B", boxSizing: "border-box", outline: "none" };

  return (
    <form onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <div>
        <label style={labelStyle}>Counter Salary ({offerInfo?.currency ?? "MYR"})</label>
        <input type="number" step="100" value={salary} onChange={e => setSalary(e.target.value)}
          placeholder={offerInfo?.salaryAmount ? String(offerInfo.salaryAmount) : "e.g. 8500"}
          style={inputStyle} />
        {offerInfo?.salaryAmount && (
          <p style={{ fontSize: 11, color: "#94A3B8", marginTop: 4 }}>Current offer: {offerInfo.currency} {Number(offerInfo.salaryAmount).toLocaleString()}</p>
        )}
      </div>
      <div>
        <label style={labelStyle}>Preferred Start Date</label>
        <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)}
          style={inputStyle} />
        {offerInfo?.startDate && (
          <p style={{ fontSize: 11, color: "#94A3B8", marginTop: 4 }}>Proposed start: {offerInfo.startDate}</p>
        )}
      </div>
      <div>
        <label style={labelStyle}>Additional Notes</label>
        <textarea rows={4} value={notes} onChange={e => setNotes(e.target.value)}
          placeholder="Any other requests or questions for the recruiting team…"
          style={{ ...inputStyle, resize: "vertical" }} />
      </div>
      {err && <p style={{ color: "#EF4444", fontSize: 13, margin: 0 }}>{err}</p>}
      <Btn disabled={loading} color="#6366F1" style={{ alignSelf: "flex-start" }}>
        {loading ? "Submitting…" : "Submit Counter-Offer"}
      </Btn>
    </form>
  );
}

// ── Main page ────────────────────────────────────────────────
export default function OfferRespondPage() {
  const params = new URLSearchParams(window.location.search);
  const token = params.get("token");
  const action = params.get("action"); // accept | reject | negotiate

  const [status, setStatus] = useState("loading"); // loading | success | error | negotiate-form
  const [message, setMessage] = useState("");
  const [offerInfo, setOfferInfo] = useState(null);

  useEffect(() => {
    if (!token) { setStatus("error"); setMessage("No token found in this link."); return; }

    if (action === "negotiate") {
      // Load offer info for the form, then show form
      apiFetch("/public/offer-respond/info?token=" + token)
        .then(info => {
          if (info?.error) { setStatus("error"); setMessage(info.error); }
          else { setOfferInfo(info); setStatus("negotiate-form"); }
        })
        .catch(ex => { setStatus("error"); setMessage(ex.message); });
    } else if (action === "accept" || action === "reject") {
      apiFetch("/public/offer-respond?token=" + token + "&action=" + action)
        .then(res => {
          if (res?.success) { setStatus("success"); setMessage(res.message); }
          else { setStatus("error"); setMessage(res?.error ?? "Something went wrong."); }
        })
        .catch(ex => { setStatus("error"); setMessage(ex.message); });
    } else {
      setStatus("error");
      setMessage("Unknown action in this link.");
    }
  }, []);

  const iconFor = {
    success: { emoji: action === "accept" ? "🎉" : "👋", bg: action === "accept" ? "#F0FDF4" : "#FFF7ED", border: action === "accept" ? "#BBF7D0" : "#FED7AA", text: action === "accept" ? "#16A34A" : "#EA580C" },
    error: { emoji: "⚠️", bg: "#FFF1F2", border: "#FECDD3", text: "#DC2626" },
  };

  return (
    <div style={{ minHeight: "100vh", background: "#F1F5F9", display: "flex", alignItems: "center", justifyContent: "center", padding: "40px 16px", boxSizing: "border-box" }}>
      <Card>
        <Logo />

        {status === "loading" && (
          <div style={{ textAlign: "center", color: "#64748B", padding: "24px 0" }}>
            <div style={{ fontSize: 32, marginBottom: 12 }}>⏳</div>
            <p style={{ margin: 0 }}>Processing your response…</p>
          </div>
        )}

        {status === "success" && (() => {
          const i = iconFor.success;
          return (
            <div style={{ textAlign: "center" }}>
              <div style={{ width: 64, height: 64, borderRadius: "50%", background: i.bg, border: `2px solid ${i.border}`, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 28, margin: "0 auto 20px" }}>{i.emoji}</div>
              <h2 style={{ margin: "0 0 12px", fontSize: 20, color: "#1E293B" }}>
                {action === "accept" ? "Offer Accepted!" : "Response Recorded"}
              </h2>
              <p style={{ margin: 0, color: "#475569", fontSize: 15, lineHeight: 1.6 }}>{message}</p>
            </div>
          );
        })()}

        {status === "error" && (
          <div style={{ textAlign: "center" }}>
            <div style={{ width: 64, height: 64, borderRadius: "50%", background: "#FFF1F2", border: "2px solid #FECDD3", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 28, margin: "0 auto 20px" }}>⚠️</div>
            <h2 style={{ margin: "0 0 12px", fontSize: 20, color: "#1E293B" }}>Unable to Process</h2>
            <p style={{ margin: 0, color: "#EF4444", fontSize: 14 }}>{message}</p>
            <p style={{ marginTop: 16, color: "#64748B", fontSize: 13 }}>Please contact the recruiting team directly if you need assistance.</p>
          </div>
        )}

        {status === "negotiate-form" && (
          <div>
            <h2 style={{ margin: "0 0 6px", fontSize: 20, color: "#1E293B" }}>Submit Counter-Offer</h2>
            <p style={{ margin: "0 0 24px", color: "#64748B", fontSize: 14 }}>Let us know your preferred terms and we'll get back to you shortly.</p>
            <NegotiateForm token={token} offerInfo={offerInfo} onDone={msg => { setStatus("success"); setMessage(msg); }} />
          </div>
        )}

        <p style={{ marginTop: 32, fontSize: 11, color: "#CBD5E1", textAlign: "center" }}>
          TalentAcquisition AI · This page is for the exclusive use of the offer recipient.
        </p>
      </Card>
    </div>
  );
}
