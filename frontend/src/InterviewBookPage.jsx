import { useEffect, useMemo, useState } from "react";

const API = import.meta.env.VITE_API_BASE ?? "/api";

async function call(path, opts = {}) {
  const res = await fetch(API + path, {
    headers: { "Content-Type": "application/json" },
    ...opts,
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
  return data;
}

const fmtDay = d => new Date(d).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
const fmtTime = d => new Date(d).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
const dayKey = d => d.slice(0, 10);

export default function InterviewBookPage() {
  const interviewId = new URLSearchParams(window.location.search).get("interviewId");
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const [picked, setPicked] = useState(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(null);

  function load() {
    if (!interviewId) { setError("This booking link is not valid."); return; }
    call(`/public/interview/${interviewId}/slots`).then(setInfo).catch(e => setError(e.message));
  }
  useEffect(load, []);

  const days = useMemo(() => {
    const g = {};
    (info?.slots ?? []).forEach(s => { (g[dayKey(s.start)] ??= []).push(s); });
    return Object.entries(g);
  }, [info]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const res = await call(`/public/interview/${interviewId}/book`, { method: "POST", body: JSON.stringify({ start: picked.start }) });
      setDone(res.display);
    } catch (e) {
      setError(e.message);
      setPicked(null);
      load();
    } finally {
      setBusy(false);
    }
  }

  const typeLabel = (info?.interviewType ?? "").replace(/_/g, " ").toLowerCase();
  const card = { background: "#fff", borderRadius: 16, padding: "32px 36px", boxShadow: "0 4px 24px rgba(0,0,0,0.08)", maxWidth: 560, width: "100%", boxSizing: "border-box" };

  return (
    <div style={{ minHeight: "100vh", background: "#F1F5F9", display: "flex", alignItems: "center", justifyContent: "center", padding: "40px 16px", boxSizing: "border-box", fontFamily: "Arial,sans-serif" }}>
      <div style={card}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 24 }}>
          <div style={{ width: 44, height: 44, borderRadius: 10, background: "linear-gradient(135deg,#6366F1,#8B5CF6)", display: "flex", alignItems: "center", justifyContent: "center", color: "#fff", fontWeight: "bold", fontSize: 18 }}>TA</div>
          <div>
            <div style={{ fontWeight: "bold", fontSize: 17, color: "#1E293B" }}>TalentAcquisition AI</div>
            <div style={{ fontSize: 12, color: "#64748B" }}>Interview scheduling</div>
          </div>
        </div>

        {done ? (
          <div style={{ textAlign: "center" }}>
            <div style={{ fontSize: 44, marginBottom: 12 }}>✅</div>
            <h2 style={{ margin: "0 0 10px", color: "#1E293B" }}>Interview confirmed</h2>
            <p style={{ margin: 0, color: "#475569", lineHeight: 1.6 }}>
              Your {typeLabel} interview for <strong>{info?.roleTitle}</strong> is booked for<br /><strong>{done}</strong>.<br />
              A confirmation email is on its way.
            </p>
          </div>
        ) : !info ? (
          <p style={{ color: error ? "#DC2626" : "#64748B", textAlign: "center" }}>{error ?? "Loading available times…"}</p>
        ) : info.status === "CONFIRMED" || info.status === "COMPLETED" ? (
          <div style={{ textAlign: "center" }}>
            <h2 style={{ margin: "0 0 10px", color: "#1E293B" }}>Already booked</h2>
            <p style={{ margin: 0, color: "#475569", lineHeight: 1.6 }}>
              Your {typeLabel} interview for <strong>{info.roleTitle}</strong> is already scheduled
              {info.confirmedSlot ? <> for <strong>{fmtDay(info.confirmedSlot)} at {fmtTime(info.confirmedSlot)}</strong></> : null}.
            </p>
          </div>
        ) : (
          <>
            <h2 style={{ margin: "0 0 6px", color: "#1E293B", fontSize: 20 }}>Choose your interview time</h2>
            <p style={{ margin: "0 0 20px", color: "#64748B", fontSize: 14, lineHeight: 1.5 }}>
              Hi {info.candidateName.split(" ")[0]}, please pick a time for your {typeLabel} interview for <strong>{info.roleTitle}</strong> ({info.durationMinutes} minutes).
            </p>
            {error && <p style={{ color: "#DC2626", fontSize: 13, margin: "0 0 14px" }}>{error}</p>}
            {days.length === 0 ? (
              <p style={{ color: "#64748B", fontSize: 14 }}>No times are available right now. Please check back soon or contact the recruiting team.</p>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 16, maxHeight: 360, overflowY: "auto", paddingRight: 4 }}>
                {days.map(([k, slots]) => (
                  <div key={k}>
                    <div style={{ fontSize: 12, fontWeight: 700, color: "#64748B", textTransform: "uppercase", letterSpacing: "0.05em", marginBottom: 8 }}>{fmtDay(slots[0].start)}</div>
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                      {slots.map(s => {
                        const on = picked?.start === s.start;
                        return (
                          <button key={s.start} onClick={() => setPicked(s)}
                            style={{ padding: "8px 14px", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer",
                              border: `1.5px solid ${on ? "#6366F1" : "#E2E8F0"}`, background: on ? "#6366F1" : "#fff", color: on ? "#fff" : "#1E293B" }}>
                            {fmtTime(s.start)}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
            <button onClick={confirm} disabled={!picked || busy}
              style={{ marginTop: 24, padding: "12px 24px", borderRadius: 10, border: "none", fontWeight: 700, fontSize: 14, color: "#fff",
                background: !picked || busy ? "#CBD5E1" : "#6366F1", cursor: !picked || busy ? "not-allowed" : "pointer" }}>
              {busy ? "Booking…" : picked ? `Confirm ${fmtDay(picked.start).split(",")[0]} ${fmtTime(picked.start)}` : "Select a time"}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
