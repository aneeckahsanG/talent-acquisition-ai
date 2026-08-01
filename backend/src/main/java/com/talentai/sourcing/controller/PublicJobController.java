package com.talentai.sourcing.controller;

import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.sourcing.dto.SourcingDtos.*;
import com.talentai.sourcing.service.SourcingAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PublicJobController {

    private final JobRequisitionRepository jobRequisitionRepository;
    private final SourcingAgentService sourcingAgentService;
    private final PdfTextExtractor pdfTextExtractor;
    private final com.talentai.common.client.ClaudeApiClient claudeApiClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // ── Public Careers HTML ───────────────────────────────────────────────────

    @GetMapping(value = {"/careers", "/careers/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String careersPage() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>Careers — TalentAcquisition AI</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f8fafc; color: #1e293b; }
  a { color: inherit; text-decoration: none; }

  /* NAV */
  nav { background: #0f172a; padding: 0 2rem; display: flex; align-items: center; justify-content: space-between; height: 64px; }
  .logo { color: #fff; font-size: 1.1rem; font-weight: 700; display: flex; align-items: center; gap: 10px; }
  .logo-icon { background: #6d28d9; border-radius: 8px; width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; font-size: 1rem; }
  .nav-right { color: #94a3b8; font-size: 0.85rem; }

  /* HERO */
  .hero { background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%); color: #fff; padding: 72px 2rem 60px; text-align: center; }
  .hero-tag { display: inline-flex; align-items: center; gap: 6px; background: rgba(109,40,217,.25); border: 1px solid rgba(139,92,246,.4); color: #c4b5fd; border-radius: 20px; padding: 4px 14px; font-size: 0.8rem; font-weight: 500; margin-bottom: 20px; }
  .hero h1 { font-size: 2.8rem; font-weight: 800; margin-bottom: 14px; line-height: 1.15; }
  .hero h1 span { color: #a78bfa; }
  .hero p { font-size: 1.05rem; color: #94a3b8; max-width: 540px; margin: 0 auto 36px; line-height: 1.6; }
  .search-row { display: flex; max-width: 580px; margin: 0 auto; gap: 10px; }
  .search-row input { flex: 1; border: none; border-radius: 10px; padding: 13px 18px; font-size: 0.95rem; outline: none; }
  .search-row select { border: none; border-radius: 10px; padding: 13px 14px; font-size: 0.875rem; outline: none; background: #fff; min-width: 160px; }

  /* STATS BAR */
  .stats-bar { background: #fff; border-bottom: 1px solid #e2e8f0; display: flex; justify-content: center; gap: 48px; padding: 18px 2rem; }
  .stat { text-align: center; }
  .stat-n { font-size: 1.4rem; font-weight: 700; color: #6d28d9; }
  .stat-l { font-size: 0.78rem; color: #64748b; margin-top: 2px; }

  /* LAYOUT */
  .layout { max-width: 1080px; margin: 0 auto; padding: 40px 2rem; display: grid; grid-template-columns: 220px 1fr; gap: 28px; }
  @media(max-width:768px){.layout{grid-template-columns:1fr}}

  /* SIDEBAR */
  .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px; margin-bottom: 14px; }
  .card h3 { font-size: 0.75rem; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; color: #94a3b8; margin-bottom: 12px; }
  .chip { display: inline-flex; align-items: center; padding: 4px 12px; border-radius: 20px; font-size: 0.78rem; background: #f1f5f9; color: #475569; margin: 3px; cursor: pointer; border: 1.5px solid transparent; transition: all .12s; }
  .chip:hover, .chip.on { background: #ede9fe; color: #6d28d9; border-color: #c4b5fd; }

  /* JOB LIST */
  .list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
  .list-header h2 { font-size: 1.1rem; font-weight: 700; }
  .count { font-size: 0.82rem; color: #64748b; }
  .job { background: #fff; border: 1.5px solid #e2e8f0; border-radius: 14px; padding: 22px; margin-bottom: 14px; transition: all .15s; }
  .job:hover { border-color: #a78bfa; box-shadow: 0 4px 20px rgba(109,40,217,.07); transform: translateY(-1px); }
  .job-top { display: flex; justify-content: space-between; align-items: flex-start; }
  .job-left { display: flex; gap: 14px; align-items: flex-start; }
  .jicon { width: 46px; height: 46px; border-radius: 11px; display: flex; align-items: center; justify-content: center; font-size: 1.3rem; flex-shrink: 0; }
  .jtitle { font-size: 1rem; font-weight: 700; margin-bottom: 3px; }
  .jdept { font-size: 0.82rem; color: #6d28d9; font-weight: 500; }
  .jmeta { display: flex; gap: 18px; margin-top: 10px; flex-wrap: wrap; }
  .jmeta span { font-size: 0.78rem; color: #64748b; }
  .jdesc { font-size: 0.85rem; color: #475569; margin-top: 10px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .jfooter { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; padding-top: 14px; border-top: 1px solid #f1f5f9; }
  .badge { display: inline-flex; align-items: center; padding: 3px 10px; border-radius: 20px; font-size: 0.72rem; font-weight: 600; }
  .badge-green { background: #dcfce7; color: #16a34a; }
  .badge-purple { background: #ede9fe; color: #6d28d9; }
  .badge-applied { background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; }
  .applied-btn { background: #f0fdf4; color: #16a34a; border: 1.5px solid #bbf7d0; padding: 9px 22px; border-radius: 8px; font-size: 0.85rem; font-weight: 600; cursor: default; }
  .apply-btn { background: #6d28d9; color: #fff; border: none; padding: 9px 22px; border-radius: 8px; font-size: 0.85rem; font-weight: 600; cursor: pointer; transition: background .15s; }
  .apply-btn:hover { background: #5b21b6; }

  /* MODAL */
  .overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 200; align-items: center; justify-content: center; padding: 16px; }
  .overlay.open { display: flex; }
  .modal { background: #fff; border-radius: 16px; width: 100%; max-width: 600px; max-height: 92vh; overflow-y: auto; box-shadow: 0 24px 64px rgba(0,0,0,.2); }
  .mhead { padding: 24px 24px 0; display: flex; justify-content: space-between; align-items: flex-start; }
  .mhead h2 { font-size: 1.1rem; font-weight: 700; }
  .msub { font-size: 0.82rem; color: #64748b; margin-top: 4px; }
  .xbtn { background: #f1f5f9; border: none; border-radius: 8px; width: 30px; height: 30px; cursor: pointer; font-size: 0.9rem; flex-shrink: 0; }
  .xbtn:hover { background: #e2e8f0; }
  .mbody { padding: 18px 24px 8px; }
  .fg { margin-bottom: 14px; }
  .fg label { display: block; font-size: 0.78rem; font-weight: 600; color: #374151; margin-bottom: 5px; }
  .req { color: #dc2626; }
  .fg input, .fg textarea { width: 100%; border: 1.5px solid #e2e8f0; border-radius: 8px; padding: 9px 12px; font-size: 0.875rem; outline: none; font-family: inherit; }
  .fg input:focus, .fg textarea:focus { border-color: #a78bfa; box-shadow: 0 0 0 3px #ede9fe; }
  .frow { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  .fg textarea { min-height: 90px; resize: vertical; }
  .mfoot { padding: 14px 24px 24px; display: flex; flex-direction: column; align-items: flex-end; gap: 0; }
  .btn-outline { background: transparent; border: 1.5px solid #e2e8f0; color: #64748b; border-radius: 8px; padding: 9px 18px; font-size: 0.85rem; font-weight: 500; cursor: pointer; }
  .btn-outline:hover { border-color: #cbd5e1; background: #f8fafc; }
  .succ { background: #f0fdf4; border: 1px solid #bbf7d0; color: #16a34a; border-radius: 8px; padding: 12px 16px; font-size: 0.875rem; display: none; margin-bottom: 12px; }
  .err { background: #fef2f2; border: 1px solid #fecaca; color: #dc2626; border-radius: 8px; padding: 12px 16px; font-size: 0.875rem; display: none; margin-bottom: 12px; }
  .upload-zone { border: 2px dashed #c4b5fd; border-radius: 10px; padding: 16px 20px; text-align: center; cursor: pointer; transition: all .15s; background: #faf5ff; margin-bottom: 14px; position: relative; }
  .upload-zone:hover { border-color: #7c3aed; background: #f5f3ff; }
  .upload-zone input[type=file] { position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%; }
  .upload-zone .uz-icon { font-size: 1.4rem; margin-bottom: 4px; }
  .upload-zone .uz-label { font-size: 0.82rem; font-weight: 600; color: #6d28d9; }
  .upload-zone .uz-sub { font-size: 0.75rem; color: #94a3b8; margin-top: 2px; }
  .parsing-bar { display: none; align-items: center; gap: 8px; background: #ede9fe; border: 1px solid #c4b5fd; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 0.82rem; color: #6d28d9; font-weight: 500; }
  .parse-succ { display: none; align-items: center; gap: 8px; background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 0.82rem; color: #16a34a; font-weight: 500; }
  .jd-section { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px; margin-bottom: 14px; }
  .jd-section h4 { font-size: 0.8rem; font-weight: 700; text-transform: uppercase; color: #94a3b8; margin-bottom: 8px; }
  .jd-section p { font-size: 0.875rem; color: #475569; line-height: 1.6; }
  .empty { text-align: center; padding: 60px 20px; color: #94a3b8; }
  .empty .i { font-size: 3rem; margin-bottom: 12px; }

  /* FOOTER */
  footer { text-align: center; padding: 40px 20px; color: #94a3b8; font-size: 0.8rem; border-top: 1px solid #e2e8f0; margin-top: 20px; }
</style>
</head>
<body>

<nav>
  <div class="logo">
    <div class="logo-icon">✦</div>
    TalentAcquisition AI &nbsp;·&nbsp; Careers
  </div>
  <div class="nav-right">We're hiring — come build with us</div>
</nav>

<div class="hero">
  <div class="hero-tag">🌟 We're Hiring</div>
  <h1>Join a team building<br/><span>AI-powered</span> hiring</h1>
  <p>Help us shape the future of talent acquisition. We believe great teams are built by great people — and we'd love for you to be one of them.</p>
  <div class="search-row">
    <input type="text" id="searchInput" placeholder="Search roles, skills, or keywords…" oninput="filter()"/>
    <select id="deptFilter" onchange="filter()"><option value="">All Departments</option></select>
  </div>
</div>

<div class="stats-bar">
  <div class="stat"><div class="stat-n" id="sJobs">—</div><div class="stat-l">Open Roles</div></div>
  <div class="stat"><div class="stat-n">Remote OK</div><div class="stat-l">Flexible Work</div></div>
  <div class="stat"><div class="stat-n">Fast</div><div class="stat-l">Interview Process</div></div>
</div>

<div class="layout">
  <div>
    <div class="card">
      <h3>Department</h3>
      <div id="deptChips"></div>
    </div>
    <div class="card">
      <h3>Location</h3>
      <div id="locChips"></div>
    </div>
    <div class="card">
      <h3>Experience</h3>
      <div id="expChips"></div>
    </div>
  </div>

  <div>
    <div class="list-header">
      <h2>Open Positions</h2>
      <span class="count" id="jobCount"></span>
    </div>
    <div id="jobList"><div class="empty"><div class="i">⏳</div><p>Loading open roles…</p></div></div>
  </div>
</div>

<footer>© 2025 TalentAcquisition AI · All rights reserved · <a href="/careers" style="color:#6d28d9">Careers</a></footer>

<!-- JOB DETAIL + APPLY MODAL -->
<div class="overlay" id="applyOverlay">
  <div class="modal">
    <div class="mhead">
      <div><h2 id="mTitle">Apply</h2><div class="msub" id="mSub"></div></div>
      <button class="xbtn" onclick="close_()">✕</button>
    </div>
    <div class="mbody">
      <div class="succ" id="succ">✅ Application submitted! We'll be in touch at the email you provided.</div>
      <div id="jobDetail"></div>
      <hr style="border:none;border-top:1px solid #e2e8f0;margin:16px 0"/>
      <p style="font-size:.9rem;font-weight:700;margin-bottom:12px">Your Application</p>

      <div class="upload-zone" id="uploadZone" onclick="document.getElementById('resumeFile').click()">
        <input type="file" id="resumeFile" accept=".pdf,.txt,.doc,.docx" onchange="handleFileUpload(event)"/>
        <div class="uz-icon">📄</div>
        <div class="uz-label" id="uzLabel">Upload Resume to Auto-Fill</div>
        <div class="uz-sub">PDF, DOCX, or TXT — fields will be filled automatically</div>
      </div>
      <div class="parsing-bar" id="parsingBar">
        <span style="display:inline-block;animation:spin 1s linear infinite">⏳</span> Parsing resume with AI…
      </div>
      <div class="parse-succ" id="parseSucc">✅ Resume parsed — review and edit the fields below before submitting.</div>
      <style>@keyframes spin{to{transform:rotate(360deg)}}</style>

      <div class="frow">
        <div class="fg"><label>Full Name <span class="req">*</span></label><input id="aName" placeholder="Ahmad Farid Hassan"/></div>
        <div class="fg"><label>Email <span class="req">*</span></label><input type="email" id="aEmail" placeholder="you@email.com"/></div>
      </div>
      <div class="frow">
        <div class="fg"><label>Current Title / Headline</label><input id="aHead" placeholder="Senior Engineer at Grab"/></div>
        <div class="fg"><label>Years of Experience</label><input type="number" id="aYrs" placeholder="5" min="0" max="50" step="0.5"/></div>
      </div>
      <div class="fg"><label>Key Skills <span class="req">*</span></label><input id="aSkills" placeholder="Java, Spring Boot, AWS, Docker"/></div>
      <div class="fg"><label>Resume / Work Summary <span class="req">*</span></label><textarea id="aResume" placeholder="Paste your resume or summarise your background, key achievements, and why you're interested in this role…"></textarea></div>
      <div class="fg"><label>Cover Letter <span style="color:#94a3b8">(optional)</span></label><textarea id="aCover" placeholder="Anything else you'd like us to know…" style="min-height:70px"></textarea></div>
    </div>
    <div class="mfoot">
      <div class="err" id="err" style="width:100%;margin-bottom:8px"></div>
      <div style="display:flex;gap:10px;width:100%">
        <button class="btn-outline" onclick="close_()">Cancel</button>
        <button class="apply-btn" onclick="submit()">Submit Application</button>
      </div>
    </div>
  </div>
</div>

<script>
let jobs = [], filters = {dept:null,loc:null,exp:null}, currentJobId = null;
function appliedKey(id) { return `ta_applied_${id}`; }
function hasApplied(id) { return !!localStorage.getItem(appliedKey(id)); }
function markApplied(id) { localStorage.setItem(appliedKey(id), '1'); }
const bgColors = ['#6d28d9','#0369a1','#065f46','#9a3412','#1e40af','#7c3aed'];
const icons = ['🏗️','🧑‍💻','📊','🎨','🚀','💡','🔬','📱','🌐','⚙️'];

async function load() {
  const res = await fetch('/api/public/jobs');
  jobs = await res.json();
  document.getElementById('sJobs').textContent = jobs.length;
  buildSidebar();
  render(jobs);
  // populate dept select
  const depts = [...new Set(jobs.map(j=>j.department).filter(Boolean))];
  const sel = document.getElementById('deptFilter');
  depts.forEach(d=>{ const o=document.createElement('option'); o.value=d; o.textContent=d; sel.appendChild(o); });
}

function buildSidebar() {
  chips('deptChips', [...new Set(jobs.map(j=>j.department).filter(Boolean))], 'dept');
  chips('locChips', [...new Set(jobs.map(j=>j.location).filter(Boolean))], 'loc');
  chips('expChips', [...new Set(jobs.map(j=>j.experienceLevel).filter(Boolean))], 'exp');
}

function chips(id, vals, type) {
  document.getElementById(id).innerHTML = vals.map(v =>
    `<span class="chip" onclick="toggle('${type}','${e(v)}',this)">${e(v)}</span>`).join('');
}

function toggle(type, val, el) {
  if(filters[type]===val){filters[type]=null;el.classList.remove('on');}
  else{document.querySelectorAll(`#${type==='dept'?'deptChips':type==='loc'?'locChips':'expChips'} .chip`).forEach(c=>c.classList.remove('on'));filters[type]=val;el.classList.add('on');}
  filter();
}

function filter() {
  const q = document.getElementById('searchInput').value.toLowerCase();
  const dept = document.getElementById('deptFilter').value;
  render(jobs.filter(j => {
    const t = `${j.title} ${j.department} ${j.description} ${j.requiredSkills} ${j.experienceLevel}`.toLowerCase();
    return (!q || t.includes(q))
      && (!dept || j.department === dept)
      && (!filters.dept || j.department === filters.dept)
      && (!filters.loc || j.location === filters.loc)
      && (!filters.exp || j.experienceLevel === filters.exp);
  }));
}

function render(list) {
  document.getElementById('jobCount').textContent = `${list.length} role${list.length!==1?'s':''} open`;
  if (!list.length) { document.getElementById('jobList').innerHTML='<div class="empty"><div class="i">🔍</div><p>No roles match your search. Try different keywords.</p></div>'; return; }
  document.getElementById('jobList').innerHTML = list.map((j,i) => `
    <div class="job">
      <div class="job-top">
        <div class="job-left">
          <div class="jicon" style="background:${bgColors[i%bgColors.length]}20;font-size:1.3rem">${icons[i%icons.length]}</div>
          <div>
            <div class="jtitle">${e(j.title)}</div>
            <div class="jdept">${e(j.department||'General')}</div>
          </div>
        </div>
        <span class="badge badge-green">● Open</span>
      </div>
      <div class="jmeta">
        ${j.location?`<span>📍 ${e(j.location)}</span>`:''}
        ${j.experienceLevel?`<span>🎯 ${e(j.experienceLevel)}</span>`:''}
        <span>🕐 Full-time</span>
      </div>
      ${j.description?`<div class="jdesc">${e(j.description)}</div>`:''}
      <div class="jfooter">
        <span class="badge badge-purple">AI-Assisted Screening</span>
        ${hasApplied(j.id)
          ? `<span class="applied-btn">✓ Applied</span>`
          : `<button class="apply-btn" onclick="open_(${j.id})">Apply Now →</button>`}
      </div>
    </div>`).join('');
}

function e(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'&#39;');}

async function open_(id) {
  if (hasApplied(id)) return;
  currentJobId = id;
  const job = jobs.find(j=>j.id===id);
  document.getElementById('mTitle').textContent = job.title;
  document.getElementById('mSub').textContent = [job.department, job.location].filter(Boolean).join(' · ');
  document.getElementById('succ').style.display='none';
  document.getElementById('err').style.display='none';
  ['aName','aEmail','aHead','aYrs','aSkills','aResume','aCover'].forEach(x=>document.getElementById(x).value='');
  document.getElementById('jobDetail').innerHTML = `
    ${job.description?`<div class="jd-section"><h4>About the Role</h4><p>${e(job.description)}</p></div>`:''}
    ${job.requiredSkills?`<div class="jd-section"><h4>What We're Looking For</h4><p>${e(job.requiredSkills)}</p></div>`:''}`;
  document.getElementById('applyOverlay').classList.add('open');
}
function close_() {
  document.getElementById('applyOverlay').classList.remove('open');
  document.getElementById('resumeFile').value = '';
  document.getElementById('uzLabel').textContent = 'Upload Resume to Auto-Fill';
  document.getElementById('parsingBar').style.display = 'none';
  document.getElementById('parseSucc').style.display = 'none';
}

async function handleFileUpload(event) {
  const file = event.target.files[0];
  if (!file) return;
  document.getElementById('uzLabel').textContent = file.name;
  document.getElementById('parsingBar').style.display = 'flex';
  document.getElementById('parseSucc').style.display = 'none';
  document.getElementById('err').style.display = 'none';

  const formData = new FormData();
  formData.append('file', file);
  try {
    const res = await fetch('/api/public/parse-resume', { method: 'POST', body: formData });
    if (!res.ok) { throw new Error(await res.text()); }
    const data = await res.json();
    if (data.fullName)        document.getElementById('aName').value = data.fullName;
    if (data.email)           document.getElementById('aEmail').value = data.email;
    if (data.headline)        document.getElementById('aHead').value = data.headline;
    if (data.yearsExperience) document.getElementById('aYrs').value = data.yearsExperience;
    if (data.skills)          document.getElementById('aSkills').value = data.skills;
    if (data.resumeText)      document.getElementById('aResume').value = data.resumeText;
    document.getElementById('parsingBar').style.display = 'none';
    document.getElementById('parseSucc').style.display = 'flex';
  } catch(ex) {
    document.getElementById('parsingBar').style.display = 'none';
    document.getElementById('err').textContent = 'Could not parse resume: ' + ex.message;
    document.getElementById('err').style.display = 'block';
  }
}

async function submit() {
  const name=document.getElementById('aName').value.trim(), email=document.getElementById('aEmail').value.trim(),
    skills=document.getElementById('aSkills').value.trim(), resume=document.getElementById('aResume').value.trim();
  const errEl=document.getElementById('err');
  errEl.style.display='none';
  if(!name||!email||!skills||!resume){errEl.textContent='Please fill in all required fields.';errEl.style.display='block';return;}
  const payload = {
    fullName:name, email, headline:document.getElementById('aHead').value.trim(),
    yearsExperience:parseFloat(document.getElementById('aYrs').value)||null,
    skills, resumeText:resume, coverLetter:document.getElementById('aCover').value.trim()
  };
  try {
    const res = await fetch(`/api/public/jobs/${currentJobId}/apply`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    if(res.ok){markApplied(currentJobId);document.getElementById('succ').style.display='block';setTimeout(()=>{close_();render(jobs.filter(j=>filt(j)));},3000);}
    else{const t=await res.text();errEl.textContent=t||'Submission failed. Please try again.';errEl.style.display='block';}
  } catch(ex){errEl.textContent='Network error. Please try again.';errEl.style.display='block';}
}

load();
</script>
</body>
</html>
""";
    }

    // ── Public REST API ───────────────────────────────────────────────────────

    @GetMapping("/api/public/jobs")
    public List<PublicJobResponse> listOpenJobs() {
        return jobRequisitionRepository.findByStatus("OPEN").stream()
                .map(this::toPublicResponse)
                .toList();
    }

    @GetMapping("/api/public/jobs/{id}")
    public ResponseEntity<PublicJobResponse> getJob(@PathVariable Long id) {
        return jobRequisitionRepository.findById(id)
                .filter(j -> "OPEN".equals(j.getStatus()))
                .map(j -> ResponseEntity.ok(toPublicResponse(j)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/public/jobs/{id}/apply")
    public ResponseEntity<?> apply(@PathVariable Long id, @RequestBody DirectApplyRequest request) {
        try {
            DirectApplyResponse result = sourcingAgentService.directApply(id, request);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── Recruiter: applicant list + actions ───────────────────────────────────

    @GetMapping("/api/requisitions/{id}/applicants")
    public List<com.talentai.sourcing.dto.SourcingDtos.SourcingMatchResponse> getApplicants(@PathVariable Long id) {
        return sourcingAgentService.getApplicantsForRequisition(id);
    }

    @PostMapping("/api/requisitions/{id}/applicants/{matchId}/shortlist")
    public ResponseEntity<com.talentai.sourcing.dto.SourcingDtos.SourcingMatchResponse> shortlist(
            @PathVariable Long id, @PathVariable Long matchId) {
        return ResponseEntity.ok(sourcingAgentService.shortlistApplicant(matchId));
    }

    @PostMapping("/api/requisitions/{id}/applicants/{matchId}/reject")
    public ResponseEntity<com.talentai.sourcing.dto.SourcingDtos.SourcingMatchResponse> reject(
            @PathVariable Long id, @PathVariable Long matchId) {
        return ResponseEntity.ok(sourcingAgentService.rejectApplicant(matchId));
    }

    // ── Public Resume Parse ───────────────────────────────────────────────────

    @PostMapping(value = "/api/public/parse-resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseResume(@RequestParam("file") MultipartFile file) {
        try {
            String text = pdfTextExtractor.extractText(file);
            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body("Could not extract text from the uploaded file.");
            }

            String systemPrompt = """
                    You are a resume parser. Extract the following fields from the resume text.
                    Respond with ONLY a JSON object, no markdown, no explanation:
                    {
                      "fullName": "string or null",
                      "email": "string or null",
                      "headline": "current job title and company, e.g. 'Senior Engineer at Grab', or null",
                      "skills": "comma-separated key technical and soft skills, or null",
                      "yearsExperience": number or null,
                      "resumeText": "a concise 3-5 sentence professional summary of the candidate"
                    }
                    """;
            String userPrompt = "Resume:\n" + text.substring(0, Math.min(text.length(), 4000));

            String raw = claudeApiClient.sendPrompt(systemPrompt, userPrompt);
            String json = claudeApiClient.stripJsonFences(raw);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("fullName", node.path("fullName").isNull() ? null : node.path("fullName").asText(null));
            result.put("email", node.path("email").isNull() ? null : node.path("email").asText(null));
            result.put("headline", node.path("headline").isNull() ? null : node.path("headline").asText(null));
            result.put("skills", node.path("skills").isNull() ? null : node.path("skills").asText(null));
            result.put("yearsExperience", node.path("yearsExperience").isNull() ? null : node.path("yearsExperience").asDouble(0));
            result.put("resumeText", node.path("resumeText").isNull() ? null : node.path("resumeText").asText(null));
            result.put("rawText", text.substring(0, Math.min(text.length(), 3000)));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Resume parse failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Failed to parse resume: " + e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private PublicJobResponse toPublicResponse(JobRequisition j) {
        return PublicJobResponse.builder()
                .id(j.getId())
                .title(j.getTitle())
                .department(j.getDepartment())
                .location(j.getLocation())
                .description(j.getDescription())
                .requiredSkills(j.getRequiredSkills())
                .experienceLevel(j.getExperienceLevel())
                .createdAt(j.getCreatedAt())
                .build();
    }
}
