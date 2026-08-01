package com.talentai.sourcing.controller;

import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.sourcing.entity.MockApplication;
import com.talentai.sourcing.entity.MockJobRole;
import com.talentai.sourcing.repository.MockApplicationRepository;
import com.talentai.sourcing.repository.MockJobRoleRepository;
import com.talentai.sourcing.service.ResumeSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mock-jobboard")
@RequiredArgsConstructor
@Slf4j
public class MockJobBoardController {

    private final MockJobRoleRepository mockJobRoleRepository;
    private final MockApplicationRepository mockApplicationRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ResumeSyncService resumeSyncService;
    private final WebClient.Builder webClientBuilder;

    @Value("${server.port:8080}")
    private String serverPort;

    // ── Portal HTML ──────────────────────────────────────────────────────────

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String portal() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>TalentBoard — Find Your Next Role</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f8fafc; color: #1e293b; }
  nav { background: #fff; border-bottom: 1px solid #e2e8f0; padding: 0 2rem; display: flex; align-items: center; justify-content: space-between; height: 60px; position: sticky; top: 0; z-index: 100; }
  .logo { font-size: 1.25rem; font-weight: 700; color: #6d28d9; display: flex; align-items: center; gap: 8px; }
  .btn { padding: 8px 18px; border-radius: 8px; font-size: 0.875rem; font-weight: 500; cursor: pointer; border: none; transition: all .15s; }
  .btn-outline { background: transparent; border: 1.5px solid #6d28d9; color: #6d28d9; }
  .btn-outline:hover { background: #f5f3ff; }
  .btn-primary { background: #6d28d9; color: #fff; }
  .btn-primary:hover { background: #5b21b6; }
  .btn-sm { padding: 6px 14px; font-size: 0.8rem; }
  .btn-danger { background: #fee2e2; color: #dc2626; border: 1px solid #fecaca; }
  .btn-danger:hover { background: #fecaca; }
  .hero { background: linear-gradient(135deg, #6d28d9 0%, #4f46e5 100%); color: #fff; text-align: center; padding: 60px 2rem 50px; }
  .hero h1 { font-size: 2.5rem; font-weight: 800; margin-bottom: 12px; }
  .hero p { font-size: 1.1rem; opacity: .85; margin-bottom: 32px; }
  .search-bar { display: flex; max-width: 560px; margin: 0 auto; background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 24px rgba(0,0,0,.15); }
  .search-bar input { flex: 1; border: none; padding: 14px 18px; font-size: 1rem; outline: none; color: #1e293b; }
  .search-bar button { background: #6d28d9; color: #fff; border: none; padding: 14px 24px; font-size: 0.95rem; font-weight: 600; cursor: pointer; }
  .stats { display: flex; justify-content: center; gap: 48px; padding: 24px 2rem; background: #fff; border-bottom: 1px solid #e2e8f0; }
  .stat { text-align: center; }
  .stat-num { font-size: 1.5rem; font-weight: 700; color: #6d28d9; }
  .stat-label { font-size: 0.8rem; color: #64748b; margin-top: 2px; }
  .main { max-width: 1100px; margin: 0 auto; padding: 40px 2rem; display: grid; grid-template-columns: 240px 1fr; gap: 32px; }
  @media(max-width:768px){.main{grid-template-columns:1fr}}
  .sidebar-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px; margin-bottom: 16px; }
  .sidebar-card h3 { font-size: 0.8rem; font-weight: 600; text-transform: uppercase; letter-spacing: .05em; color: #64748b; margin-bottom: 12px; }
  .company-logo { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 1.2rem; font-weight: 800; color: #fff; flex-shrink: 0; }
  .company-name { font-size: 0.8rem; color: #64748b; font-weight: 500; margin-top: 2px; }
  .filter-chip { display: inline-flex; align-items: center; padding: 5px 12px; border-radius: 20px; font-size: 0.8rem; background: #f1f5f9; color: #475569; margin: 3px; cursor: pointer; border: 1.5px solid transparent; }
  .filter-chip:hover,.filter-chip.active { background: #ede9fe; color: #6d28d9; border-color: #c4b5fd; }
  .jobs-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
  .jobs-header h2 { font-size: 1.2rem; font-weight: 700; }
  .jobs-count { font-size: 0.85rem; color: #64748b; }
  .job-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 24px; margin-bottom: 16px; transition: all .15s; }
  .job-card:hover { border-color: #a78bfa; box-shadow: 0 4px 20px rgba(109,40,217,.08); transform: translateY(-1px); }
  .job-card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
  .job-icon { width: 48px; height: 48px; border-radius: 12px; background: linear-gradient(135deg,#ede9fe,#ddd6fe); display: flex; align-items: center; justify-content: center; font-size: 1.4rem; flex-shrink: 0; }
  .job-title { font-size: 1.05rem; font-weight: 700; margin-bottom: 4px; }
  .job-dept { font-size: 0.85rem; color: #6d28d9; font-weight: 500; }
  .job-meta { display: flex; gap: 16px; margin-top: 12px; flex-wrap: wrap; }
  .job-meta-item { font-size: 0.8rem; color: #64748b; }
  .job-desc { font-size: 0.875rem; color: #475569; margin-top: 12px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .job-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; padding-top: 14px; border-top: 1px solid #f1f5f9; }
  .badge { display: inline-flex; align-items: center; padding: 3px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 500; }
  .badge-green { background: #dcfce7; color: #16a34a; }
  .badge-purple { background: #ede9fe; color: #6d28d9; }
  .linked-tag { font-size: 0.72rem; background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; padding: 2px 8px; border-radius: 12px; margin-left: 8px; }
  .apply-btn { background: #6d28d9; color: #fff; border: none; padding: 9px 22px; border-radius: 8px; font-size: 0.875rem; font-weight: 600; cursor: pointer; }
  .apply-btn:hover { background: #5b21b6; }
  .overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 200; align-items: center; justify-content: center; padding: 20px; }
  .overlay.open { display: flex; }
  .modal { background: #fff; border-radius: 16px; width: 100%; max-width: 560px; max-height: 90vh; overflow-y: auto; box-shadow: 0 20px 60px rgba(0,0,0,.2); }
  .modal-lg { max-width: 700px; }
  .modal-header { padding: 24px 24px 0; display: flex; align-items: flex-start; justify-content: space-between; }
  .modal-header h2 { font-size: 1.15rem; font-weight: 700; }
  .modal-subtitle { font-size: 0.85rem; color: #64748b; margin-top: 4px; }
  .close-btn { background: #f1f5f9; border: none; border-radius: 8px; width: 32px; height: 32px; cursor: pointer; font-size: 1rem; flex-shrink: 0; }
  .modal-body { padding: 20px 24px 24px; }
  .form-group { margin-bottom: 16px; }
  .form-group label { display: block; font-size: 0.8rem; font-weight: 600; color: #374151; margin-bottom: 6px; }
  .req { color: #dc2626; }
  .form-group input, .form-group textarea, .form-group select { width: 100%; border: 1.5px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; font-size: 0.875rem; outline: none; font-family: inherit; background: #fff; }
  .form-group input:focus,.form-group textarea:focus { border-color: #a78bfa; box-shadow: 0 0 0 3px #ede9fe; }
  .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  .form-group textarea { min-height: 100px; resize: vertical; }
  .modal-footer { padding: 0 24px 24px; display: flex; gap: 10px; justify-content: flex-end; }
  .cv-upload { border: 2px dashed #c4b5fd; border-radius: 10px; padding: 16px 20px; text-align: center; cursor: pointer; background: #faf5ff; transition: border-color .15s, background .15s; margin-bottom: 14px; }
  .cv-upload:hover { border-color: #7c3aed; background: #f5f0ff; }
  .cv-upload input[type=file] { display: none; }
  .cv-upload-icon { font-size: 1.5rem; margin-bottom: 4px; }
  .cv-upload-label { font-size: .85rem; font-weight: 600; color: #6d28d9; }
  .cv-upload-sub { font-size: .78rem; color: #94a3b8; margin-top: 2px; }
  .cv-parsing { display: none; align-items: center; gap: 8px; font-size: .82rem; color: #6d28d9; padding: 6px 0; }
  .cv-parsed { display: none; background: #f0fdf4; border: 1px solid #bbf7d0; color: #16a34a; border-radius: 6px; padding: 7px 12px; font-size: .82rem; margin-bottom: 10px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .spin { display: inline-block; animation: spin 1s linear infinite; }
  .success-msg { background: #f0fdf4; border: 1px solid #bbf7d0; color: #16a34a; border-radius: 8px; padding: 12px 16px; font-size: 0.875rem; display: none; margin-bottom: 16px; }
  .error-msg { background: #fef2f2; border: 1px solid #fecaca; color: #dc2626; border-radius: 8px; padding: 12px 16px; font-size: 0.875rem; display: none; margin-bottom: 16px; }
  .admin-section { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px; margin-bottom: 16px; }
  .admin-section h3 { font-size: 0.8rem; font-weight: 600; text-transform: uppercase; color: #64748b; margin-bottom: 12px; }
  .list-item { padding: 10px 0; border-bottom: 1px solid #f1f5f9; font-size: 0.85rem; display: flex; justify-content: space-between; align-items: center; }
  .list-item:last-child { border-bottom: none; }
  .item-name { font-weight: 600; }
  .item-meta { color: #64748b; font-size: 0.78rem; margin-top: 2px; }
  .empty { text-align: center; padding: 60px 20px; color: #94a3b8; }
  .empty .icon { font-size: 3rem; margin-bottom: 12px; }
</style>
</head>
<body>
<nav>
  <div class="logo">💼 TalentBoard</div>
  <div style="display:flex;gap:10px">
    <button class="btn btn-outline btn-sm" onclick="openAdmin()">⚙️ Admin</button>
    <button class="btn btn-primary btn-sm" onclick="openPostRole()">+ Post a Role</button>
  </div>
</nav>

<div class="hero">
  <h1>Find Your Next Opportunity</h1>
  <p>Explore open roles and apply in minutes. Applications sync directly into our ATS.</p>
  <div class="search-bar">
    <input type="text" id="searchInput" placeholder="Search job title, skill, or keyword…" oninput="filterJobs()"/>
    <button onclick="filterJobs()">Search</button>
  </div>
</div>

<div class="stats">
  <div class="stat"><div class="stat-num" id="statJobs">—</div><div class="stat-label">Open Roles</div></div>
  <div class="stat"><div class="stat-num" id="statApps">—</div><div class="stat-label">Applications Received</div></div>
  <div class="stat"><div class="stat-num">24h</div><div class="stat-label">Avg Response Time</div></div>
</div>

<div class="main">
  <div>
    <div class="sidebar-card">
      <h3>Company</h3>
      <div id="companyFilters"></div>
    </div>
    <div class="sidebar-card">
      <h3>Department</h3>
      <div id="deptFilters"></div>
    </div>
    <div class="sidebar-card">
      <h3>Location</h3>
      <div id="locFilters"></div>
    </div>
  </div>
  <div>
    <div class="jobs-header">
      <h2>Open Positions</h2>
      <span class="jobs-count" id="jobsCount"></span>
    </div>
    <div id="jobList"><div class="empty"><div class="icon">⏳</div><p>Loading roles…</p></div></div>
  </div>
</div>

<!-- APPLY MODAL -->
<div class="overlay" id="applyOverlay">
  <div class="modal">
    <div class="modal-header">
      <div><h2 id="applyTitle">Apply</h2><div class="modal-subtitle" id="applySubtitle"></div></div>
      <button class="close-btn" onclick="closeApply()">✕</button>
    </div>
    <div class="modal-body">
      <div class="success-msg" id="applySuccess">✅ Application submitted! Our team will be in touch soon.</div>
      <div class="error-msg" id="applyError"></div>
      <div class="cv-upload" id="cvUploadZone" onclick="document.getElementById('cvFile').click()">
        <input type="file" id="cvFile" accept=".pdf,.doc,.docx,.txt" onchange="handleCvUpload(event)"/>
        <div class="cv-upload-icon">📄</div>
        <div class="cv-upload-label" id="cvLabel">Upload CV / Resume to Auto-Fill</div>
        <div class="cv-upload-sub">PDF, DOCX or TXT — fields filled automatically</div>
      </div>
      <div class="cv-parsing" id="cvParsing"><span class="spin">⏳</span> Parsing with AI…</div>
      <div class="cv-parsed" id="cvParsed">✅ CV parsed — review fields below before submitting.</div>
      <div class="form-row">
        <div class="form-group"><label>Full Name <span class="req">*</span></label><input id="aName" placeholder="Ahmad Farid Hassan"/></div>
        <div class="form-group"><label>Email <span class="req">*</span></label><input type="email" id="aEmail" placeholder="you@email.com"/></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label>Headline</label><input id="aHeadline" placeholder="Senior Engineer at Grab"/></div>
        <div class="form-group"><label>Years of Experience</label><input type="number" id="aYears" placeholder="5" min="0" max="40" step="0.5"/></div>
      </div>
      <div class="form-group"><label>Key Skills <span class="req">*</span></label><input id="aSkills" placeholder="Java, Spring Boot, AWS, Docker"/></div>
      <div class="form-group"><label>Resume / Cover Letter <span class="req">*</span></label><textarea id="aResume" placeholder="Paste your resume or write a short introduction…"></textarea></div>
    </div>
    <div class="modal-footer">
      <button class="btn btn-outline" onclick="closeApply()">Cancel</button>
      <button class="btn btn-primary" onclick="submitApply()">Submit Application</button>
    </div>
  </div>
</div>

<!-- POST ROLE MODAL -->
<div class="overlay" id="postOverlay">
  <div class="modal">
    <div class="modal-header">
      <div><h2>Post a New Role</h2><div class="modal-subtitle">Role appears on the board immediately</div></div>
      <button class="close-btn" onclick="closePostRole()">✕</button>
    </div>
    <div class="modal-body">
      <div class="success-msg" id="postSuccess">✅ Role posted successfully!</div>
      <div class="error-msg" id="postError"></div>
      <div class="form-row">
        <div class="form-group"><label>Job Title <span class="req">*</span></label><input id="pTitle" placeholder="Senior Software Engineer"/></div>
        <div class="form-group"><label>Company Name <span class="req">*</span></label><input id="pCompany" placeholder="Acme Sdn Bhd"/></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label>Department</label><input id="pDept" placeholder="Engineering"/></div>
        <div class="form-group"><label>Location</label><input id="pLoc" value="Kuala Lumpur, Malaysia" placeholder="Kuala Lumpur, Malaysia"/></div>
      </div>
      <div class="form-group"><label>Job Description</label><textarea id="pDesc" placeholder="What will the candidate be working on?"></textarea></div>
      <div class="form-group"><label>Requirements</label><textarea id="pReqs" placeholder="Key skills and qualifications required…" style="min-height:80px"></textarea></div>
    </div>
    <div class="modal-footer">
      <button class="btn btn-outline" onclick="closePostRole()">Cancel</button>
      <button class="btn btn-primary" onclick="submitPost()">Post Role</button>
    </div>
  </div>
</div>

<!-- ADMIN MODAL -->
<div class="overlay" id="adminOverlay">
  <div class="modal modal-lg">
    <div class="modal-header">
      <div><h2>Admin Panel</h2><div class="modal-subtitle">View applications and manage job roles</div></div>
      <button class="close-btn" onclick="closeAdmin()">✕</button>
    </div>
    <div class="modal-body">
      <div class="admin-section">
        <h3>📨 Applications (<span id="adminAppCount">0</span>)</h3>
        <div id="adminApps"><p style="color:#94a3b8;font-size:.85rem">Loading…</p></div>
      </div>
      <div class="admin-section">
        <h3>💼 Job Roles (<span id="adminRoleCount">0</span>)</h3>
        <div id="adminRoles"><p style="color:#94a3b8;font-size:.85rem">Loading…</p></div>
      </div>
    </div>
    <div class="modal-footer" style="display:flex;justify-content:space-between;align-items:center">
      <button id="syncBtn" class="btn" style="background:#6d28d9;color:#fff;border:none" onclick="triggerSync()">🔄 Sync to TalentAI</button>
      <button class="btn btn-outline" onclick="closeAdmin()">Close</button>
    </div>
  </div>
</div>

<!-- Link to ATS modal -->
<div class="overlay" id="linkModal">
  <div class="modal" style="max-width:480px">
    <div class="modal-header">
      <div><h2>Link to TalentAI</h2><div class="modal-subtitle" id="linkRoleTitle"></div></div>
      <button class="close-btn" onclick="closeLinkModal()">✕</button>
    </div>
    <div class="modal-body">
      <p style="font-size:.88rem;color:#475569;margin-bottom:14px">When a candidate applies to this role, TalentBoard will POST their application to the registered webhook URL — just like LinkedIn Apply Connect.</p>
      <label style="font-size:.85rem;font-weight:600;color:#374151;display:block;margin-bottom:6px">TalentAI Requisition</label>
      <select id="reqSelect" style="width:100%;padding:9px 12px;border:1.5px solid #e2e8f0;border-radius:8px;font-size:.9rem;color:#1e293b;background:#fff;margin-bottom:12px">
        <option value="">Loading…</option>
      </select>
      <div id="webhookPreview" style="display:none;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:10px 12px;margin-bottom:12px">
        <div style="font-size:.75rem;font-weight:600;color:#6d28d9;margin-bottom:4px">⚡ Webhook URL (auto-generated)</div>
        <code id="webhookPreviewUrl" style="font-size:.78rem;color:#1e293b;word-break:break-all"></code>
      </div>
      <label style="font-size:.85rem;font-weight:600;color:#374151;display:block;margin-bottom:6px">Custom Webhook URL <span style="color:#94a3b8;font-weight:400">(optional — overrides auto-generated)</span></label>
      <input id="customWebhook" placeholder="https://your-ats.com/api/inbound/applications/123" style="width:100%;padding:9px 12px;border:1.5px solid #e2e8f0;border-radius:8px;font-size:.85rem;font-family:monospace;box-sizing:border-box"/>
      <div id="linkStatus" style="margin-top:10px;font-size:.85rem;display:none"></div>
    </div>
    <div class="modal-footer" style="display:flex;justify-content:flex-end;gap:10px">
      <button class="btn btn-outline" onclick="closeLinkModal()">Cancel</button>
      <button class="btn" style="background:#6d28d9;color:#fff;border:none" onclick="saveLink()">Save Link</button>
    </div>
  </div>
</div>

<script>
let allJobs = [], activeFilters = {company:null,dept:null,loc:null}, currentJobId = null;
const icons = ['🏗️','🧑‍💻','📊','🎨','🚀','💡','🔬','📱','🌐','⚙️','🔧','📈'];
const companyColors = ['#6d28d9','#0369a1','#065f46','#9a3412','#1e40af','#7c3aed','#0f766e','#b45309'];

async function load() {
  const [jr, ar] = await Promise.all([fetch('/mock-jobboard/jobs'), fetch('/mock-jobboard/applications')]);
  allJobs = await jr.json();
  const apps = await ar.json();
  document.getElementById('statJobs').textContent = allJobs.length;
  document.getElementById('statApps').textContent = apps.length;
  renderJobs(allJobs);
  buildFilters();
}

const filterKeys = {company:'companyFilters',dept:'deptFilters',loc:'locFilters'};

function buildFilters() {
  const companies = [...new Set(allJobs.map(j=>j.company).filter(Boolean))];
  const depts = [...new Set(allJobs.map(j=>j.department).filter(Boolean))];
  const locs = [...new Set(allJobs.map(j=>j.location).filter(Boolean))];
  document.getElementById('companyFilters').innerHTML = companies.map(c=>`<span class="filter-chip" onclick="toggleFilter('company','${e(c)}',this)">${e(c)}</span>`).join('');
  document.getElementById('deptFilters').innerHTML = depts.map(d=>`<span class="filter-chip" onclick="toggleFilter('dept','${e(d)}',this)">${e(d)}</span>`).join('');
  document.getElementById('locFilters').innerHTML = locs.map(l=>`<span class="filter-chip" onclick="toggleFilter('loc','${e(l)}',this)">${e(l)}</span>`).join('');
}

function toggleFilter(type, val, el) {
  const key = filterKeys[type];
  if(activeFilters[type]===val){activeFilters[type]=null;el.classList.remove('active');}
  else{document.querySelectorAll('#'+key+' .filter-chip').forEach(c=>c.classList.remove('active'));activeFilters[type]=val;el.classList.add('active');}
  filterJobs();
}

function filterJobs() {
  const q = document.getElementById('searchInput').value.toLowerCase();
  renderJobs(allJobs.filter(j=>{
    const t=`${j.title} ${j.company} ${j.department} ${j.description} ${j.requirements}`.toLowerCase();
    return(!q||t.includes(q))&&(!activeFilters.company||j.company===activeFilters.company)&&(!activeFilters.dept||j.department===activeFilters.dept)&&(!activeFilters.loc||j.location===activeFilters.loc);
  }));
}

function renderJobs(jobs) {
  document.getElementById('jobsCount').textContent = `${jobs.length} role${jobs.length!==1?'s':''} found`;
  if(!jobs.length){document.getElementById('jobList').innerHTML='<div class="empty"><div class="icon">🔍</div><p>No roles match your search.</p></div>';return;}
  // build a stable color map per company
  const allCompanies = [...new Set(allJobs.map(j=>j.company).filter(Boolean))];
  const colorMap = {};
  allCompanies.forEach((c,i)=>colorMap[c]=companyColors[i%companyColors.length]);

  document.getElementById('jobList').innerHTML = jobs.map((j,i)=>{
    const company = j.company || 'Company';
    const color = colorMap[company] || '#6d28d9';
    const initial = company.charAt(0).toUpperCase();
    return `
    <div class="job-card">
      <div class="job-card-top">
        <div style="display:flex;gap:14px;align-items:flex-start">
          <div class="company-logo" style="background:${color}">${initial}</div>
          <div>
            <div class="job-title">${e(j.title)}${j.requisitionId?'<span class="linked-tag">✓ Linked to ATS</span>':''}</div>
            <div class="company-name">🏢 ${e(company)}</div>
            <div class="job-dept" style="margin-top:2px">${e(j.department||'General')}</div>
          </div>
        </div>
        <span class="badge badge-green">Hiring</span>
      </div>
      <div class="job-meta">
        <span class="job-meta-item">📍 ${e(j.location||'Malaysia')}</span>
        <span class="job-meta-item">🕐 Full-time</span>
        ${j.requisitionId?'<span class="job-meta-item">🔗 Synced with ATS</span>':''}
      </div>
      ${j.description?`<div class="job-desc">${e(j.description)}</div>`:''}
      ${j.requirements?`<div style="margin-top:10px;font-size:.8rem;color:#64748b"><strong>Requirements:</strong> ${e(j.requirements.substring(0,120))}${j.requirements.length>120?'…':''}</div>`:''}
      <div class="job-footer">
        <span class="badge badge-purple">⚡ Fast Response</span>
        <button class="apply-btn" onclick="openApply(${j.id},'${e(j.title)}','${e(company)} · ${e(j.department||'')}')">Apply Now →</button>
      </div>
    </div>`;
  }).join('');
}

function e(s){return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'&#39;');}

function openApply(id,title,dept){
  currentJobId=id;
  document.getElementById('applyTitle').textContent='Apply — '+title;
  document.getElementById('applySubtitle').textContent=dept;
  ['aName','aEmail','aHeadline','aYears','aSkills','aResume'].forEach(x=>document.getElementById(x).value='');
  document.getElementById('applySuccess').style.display='none';
  document.getElementById('applyError').style.display='none';
  document.getElementById('cvFile').value='';
  document.getElementById('cvLabel').textContent='Upload CV / Resume to Auto-Fill';
  document.getElementById('cvParsing').style.display='none';
  document.getElementById('cvParsed').style.display='none';
  document.getElementById('applyOverlay').classList.add('open');
}
function closeApply(){document.getElementById('applyOverlay').classList.remove('open');}

async function handleCvUpload(event){
  const file=event.target.files[0];
  if(!file)return;
  document.getElementById('cvLabel').textContent=file.name;
  document.getElementById('cvParsing').style.display='flex';
  document.getElementById('cvParsed').style.display='none';
  document.getElementById('applyError').style.display='none';
  const fd=new FormData();
  fd.append('file',file);
  try{
    const res=await fetch('/api/public/parse-resume',{method:'POST',body:fd});
    if(!res.ok)throw new Error(await res.text());
    const d=await res.json();
    if(d.fullName)    document.getElementById('aName').value=d.fullName;
    if(d.email)       document.getElementById('aEmail').value=d.email;
    if(d.headline)    document.getElementById('aHeadline').value=d.headline;
    if(d.yearsExperience) document.getElementById('aYears').value=d.yearsExperience;
    if(d.skills)      document.getElementById('aSkills').value=d.skills;
    if(d.resumeText)  document.getElementById('aResume').value=d.resumeText;
    document.getElementById('cvParsing').style.display='none';
    document.getElementById('cvParsed').style.display='block';
  }catch(ex){
    document.getElementById('cvParsing').style.display='none';
    document.getElementById('applyError').textContent='Could not parse CV: '+ex.message;
    document.getElementById('applyError').style.display='block';
  }
}

async function submitApply(){
  const name=document.getElementById('aName').value.trim(), email=document.getElementById('aEmail').value.trim(),
    skills=document.getElementById('aSkills').value.trim(), resume=document.getElementById('aResume').value.trim();
  const err=document.getElementById('applyError');
  if(!name||!email||!skills||!resume){err.textContent='Please fill in all required fields.';err.style.display='block';return;}
  err.style.display='none';
  const res=await fetch('/mock-jobboard/apply',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
    mockJobRoleId:currentJobId,fullName:name,email,
    headline:document.getElementById('aHeadline').value.trim(),
    yearsExperience:parseFloat(document.getElementById('aYears').value)||null,
    skills,resumeText:resume
  })});
  if(res.ok){document.getElementById('applySuccess').style.display='block';load();setTimeout(closeApply,2500);}
  else{err.textContent='Submission failed. Try again.';err.style.display='block';}
}

function openPostRole(){
  ['pTitle','pCompany','pDept','pDesc','pReqs'].forEach(x=>document.getElementById(x).value='');
  document.getElementById('pLoc').value='Kuala Lumpur, Malaysia';
  document.getElementById('postSuccess').style.display='none';
  document.getElementById('postError').style.display='none';
  document.getElementById('postOverlay').classList.add('open');
}
function closePostRole(){document.getElementById('postOverlay').classList.remove('open');}

async function submitPost(){
  const title=document.getElementById('pTitle').value.trim();
  const company=document.getElementById('pCompany').value.trim();
  if(!title||!company){document.getElementById('postError').textContent='Title and company are required.';document.getElementById('postError').style.display='block';return;}
  const res=await fetch('/mock-jobboard/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
    title,company,department:document.getElementById('pDept').value.trim(),
    location:document.getElementById('pLoc').value.trim(),
    description:document.getElementById('pDesc').value.trim(),
    requirements:document.getElementById('pReqs').value.trim()
  })});
  if(res.ok){document.getElementById('postSuccess').style.display='block';await load();setTimeout(closePostRole,1800);}
  else{document.getElementById('postError').textContent='Failed.';document.getElementById('postError').style.display='block';}
}

async function openAdmin(){
  document.getElementById('adminOverlay').classList.add('open');
  const [ar,jr]=await Promise.all([fetch('/mock-jobboard/applications'),fetch('/mock-jobboard/jobs')]);
  const apps=await ar.json(), roles=await jr.json();
  document.getElementById('adminAppCount').textContent=apps.length;
  document.getElementById('adminRoleCount').textContent=roles.length;
  document.getElementById('adminApps').innerHTML=apps.length
    ?apps.map(a=>`<div class="list-item"><div><div class="item-name">${e(a.fullName)} <span style="color:#6d28d9;font-size:.78rem">${e(a.email)}</span></div><div class="item-meta">${e(a.headline||'')} · ${new Date(a.createdAt).toLocaleDateString('en-MY')}</div></div></div>`).join('')
    :'<p style="color:#94a3b8;font-size:.85rem;padding:8px 0">No applications yet.</p>';
  document.getElementById('adminRoles').innerHTML=roles.map(r=>`
    <div class="list-item" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <div>
          <div class="item-name">${e(r.title)} <span style="color:#64748b;font-weight:400">@ ${e(r.company||'—')}</span></div>
          <div class="item-meta">${e(r.department||'—')} · ${e(r.location||'—')}</div>
        </div>
        <div style="display:flex;gap:6px;flex-shrink:0;margin-left:12px">
          <button class="btn btn-sm" style="background:#ede9fe;color:#6d28d9;border:none" onclick="openLinkModal(${r.id},'${e(r.title)}',${r.requisitionId||'null'},'${e(r.webhookUrl||'')}')">🔗 Webhook</button>
          <button class="btn btn-danger btn-sm" onclick="deleteRole(${r.id})">Remove</button>
        </div>
      </div>
      ${r.webhookUrl?`<div style="font-size:.75rem;background:#f1f5f9;border-radius:6px;padding:5px 10px;color:#475569;word-break:break-all">
        <span style="color:#16a34a;font-weight:600">⚡ Webhook active</span> → <code>${e(r.webhookUrl)}</code>
      </div>`:'<div style="font-size:.75rem;color:#94a3b8;padding:2px 0">No webhook — applications saved to board only</div>'}
    </div>`).join('');
}
function closeAdmin(){document.getElementById('adminOverlay').classList.remove('open');}

async function deleteRole(id){
  if(!confirm('Remove this role from the job board?'))return;
  await fetch('/mock-jobboard/jobs/'+id,{method:'DELETE'});
  openAdmin();load();
}

async function triggerSync(){
  const btn=document.getElementById('syncBtn');
  btn.textContent='Syncing…';btn.disabled=true;
  const res=await fetch('/mock-jobboard/api/sync',{method:'POST'});
  const data=await res.json();
  btn.textContent='✓ Sync Done';
  setTimeout(()=>{btn.textContent='🔄 Sync to TalentAI';btn.disabled=false;},3000);
  openAdmin();
}

let linkRoleId=null;
async function openLinkModal(roleId,roleTitle,currentReqId,currentWebhook){
  linkRoleId=roleId;
  document.getElementById('linkRoleTitle').textContent=roleTitle;
  document.getElementById('linkStatus').style.display='none';
  document.getElementById('customWebhook').value='';
  document.getElementById('webhookPreview').style.display='none';
  const sel=document.getElementById('reqSelect');
  sel.innerHTML='<option value="">Loading…</option>';
  document.getElementById('linkModal').classList.add('open');
  const res=await fetch('/mock-jobboard/api/ats-requisitions');
  const reqs=await res.json();
  sel.innerHTML='<option value="">— Remove webhook —</option>'+
    reqs.map(r=>`<option value="${r.id}" ${r.id==currentReqId?'selected':''}>#${r.id} ${e(r.title)} (${e(r.experienceLevel||r.department||'')})</option>`).join('');
  if(currentReqId) updateWebhookPreview(currentReqId, currentWebhook);
}
function updateWebhookPreview(reqId, customUrl){
  const preview=document.getElementById('webhookPreview');
  const urlEl=document.getElementById('webhookPreviewUrl');
  if(!reqId){preview.style.display='none';return;}
  const url=customUrl||('http://localhost:8080/api/inbound/applications/'+reqId);
  urlEl.textContent=url;
  preview.style.display='block';
}
document.addEventListener('DOMContentLoaded',()=>{
  document.getElementById('reqSelect').addEventListener('change',function(){
    updateWebhookPreview(this.value, document.getElementById('customWebhook').value);
  });
  document.getElementById('customWebhook').addEventListener('input',function(){
    const reqId=document.getElementById('reqSelect').value;
    if(reqId) updateWebhookPreview(reqId, this.value);
  });
});
function closeLinkModal(){document.getElementById('linkModal').classList.remove('open');}
async function saveLink(){
  const reqId=document.getElementById('reqSelect').value;
  const customWebhook=document.getElementById('customWebhook').value.trim();
  const res=await fetch('/mock-jobboard/jobs/'+linkRoleId+'/link',{
    method:'PATCH',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({requisitionId:reqId||null, webhookUrl:customWebhook||null})
  });
  const st=document.getElementById('linkStatus');
  if(res.ok){st.textContent='✓ Webhook registered';st.style.color='#16a34a';st.style.display='block';setTimeout(()=>{closeLinkModal();openAdmin();},1200);}
  else{st.textContent='Failed to save.';st.style.color='#dc2626';st.style.display='block';}
}

load();
</script>
</body>
</html>
""";
    }

    // ── Job Roles API ─────────────────────────────────────────────────────────

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs() {
        return mockJobRoleRepository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .map(this::jobToMap).toList();
    }

    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> createJob(@RequestBody Map<String, Object> body) {
        MockJobRole role = MockJobRole.builder()
                .title((String) body.get("title"))
                .company((String) body.get("company"))
                .department((String) body.get("department"))
                .location(body.getOrDefault("location", "Kuala Lumpur, Malaysia").toString())
                .description((String) body.get("description"))
                .requirements((String) body.get("requirements"))
                .requisitionId(body.get("requisitionId") != null ? Long.valueOf(body.get("requisitionId").toString()) : null)
                .build();
        return ResponseEntity.ok(jobToMap(mockJobRoleRepository.save(role)));
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        mockJobRoleRepository.findById(id).ifPresent(r -> {
            r.setActive(false);
            mockJobRoleRepository.save(r);
        });
        return ResponseEntity.noContent().build();
    }

    // ── Applications API ──────────────────────────────────────────────────────

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        if (email == null || email.isBlank()) return ResponseEntity.badRequest().body("Email is required.");

        Long mockJobRoleId = body.get("mockJobRoleId") != null ? Long.valueOf(body.get("mockJobRoleId").toString()) : null;
        String fullName = (String) body.get("fullName");
        String headline = (String) body.get("headline");
        String skills = (String) body.get("skills");
        String resumeText = (String) body.get("resumeText");
        BigDecimal yearsExp = body.get("yearsExperience") != null ? new BigDecimal(body.get("yearsExperience").toString()) : null;

        // Save to mock_application for the admin view regardless
        MockApplication app = MockApplication.builder()
                .mockJobRoleId(mockJobRoleId)
                .fullName(fullName)
                .email(email.trim().toLowerCase())
                .headline(headline)
                .skills(skills)
                .yearsExperience(yearsExp)
                .resumeText(resumeText)
                .sourceChannel("JOBBOARD")
                .build();
        mockApplicationRepository.save(app);

        // If the role has a webhook URL registered, POST the application to it
        if (mockJobRoleId != null) {
            mockJobRoleRepository.findById(mockJobRoleId).ifPresent(role -> {
                if (role.getWebhookUrl() != null && !role.getWebhookUrl().isBlank()) {
                    try {
                        Map<String, Object> webhookPayload = new HashMap<>();
                        webhookPayload.put("fullName", fullName);
                        webhookPayload.put("email", email.trim().toLowerCase());
                        webhookPayload.put("headline", headline);
                        webhookPayload.put("skills", skills);
                        webhookPayload.put("resumeText", resumeText);
                        webhookPayload.put("yearsExperience", yearsExp);
                        webhookPayload.put("source", "TALENTBOARD");
                        webhookPayload.put("jobTitle", role.getTitle());
                        webhookPayload.put("company", role.getCompany());

                        String webhookResponse = webClientBuilder.build()
                                .post()
                                .uri(role.getWebhookUrl())
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(webhookPayload)
                                .retrieve()
                                .bodyToMono(String.class)
                                .timeout(Duration.ofSeconds(10))
                                .block();

                        log.info("Webhook delivered for '{}' → {} | response: {}", fullName, role.getWebhookUrl(), webhookResponse);
                    } catch (Exception e) {
                        log.warn("Webhook delivery failed for '{}' → {}: {}", fullName, role.getWebhookUrl(), e.getMessage());
                    }
                }
            });
        }

        return ResponseEntity.ok(Map.of("status", "submitted"));
    }

    /** Sync endpoint — returns all submitted applications in the candidate sync format. */
    @GetMapping("/applications")
    public List<Map<String, Object>> allApplications() {
        return mockApplicationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::appToSyncMap).toList();
    }

    /** Returns open TalentAI requisitions so the admin can link a board role to one. */
    @GetMapping("/api/ats-requisitions")
    public List<Map<String, Object>> atsRequisitions() {
        return jobRequisitionRepository.findByStatus("OPEN").stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("title", r.getTitle());
            m.put("department", r.getDepartment() != null ? r.getDepartment() : "");
            m.put("experienceLevel", r.getExperienceLevel() != null ? r.getExperienceLevel() : "");
            return m;
        }).toList();
    }

    /** Links a board job role to a TalentAI requisition and registers the webhook URL. */
    @PatchMapping("/jobs/{id}/link")
    public ResponseEntity<?> linkToRequisition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return mockJobRoleRepository.findById(id).map(role -> {
            Object reqId = body.get("requisitionId");
            Object customWebhook = body.get("webhookUrl");

            if (reqId != null && !reqId.toString().isBlank()) {
                Long requisitionId = Long.valueOf(reqId.toString());
                role.setRequisitionId(requisitionId);
                // Auto-generate TalentAI inbound webhook URL for this requisition
                String webhookUrl = customWebhook != null && !customWebhook.toString().isBlank()
                        ? customWebhook.toString()
                        : "http://localhost:" + serverPort + "/api/inbound/applications/" + requisitionId;
                role.setWebhookUrl(webhookUrl);
            } else {
                // Unlink — clear both
                role.setRequisitionId(null);
                role.setWebhookUrl(null);
            }
            mockJobRoleRepository.save(role);
            return ResponseEntity.ok(jobToMap(role));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Manually triggers a sync of the TalentBoard applications source into TalentAI. */
    @PostMapping("/api/sync")
    public ResponseEntity<Map<String, Object>> triggerSync() {
        // Source ID 3 is the TalentBoard applications source seeded in V11
        resumeSyncService.syncSource(3L);
        long total = mockApplicationRepository.count();
        log.info("Manual sync triggered from mock job board admin. {} applications in board.", total);
        return ResponseEntity.ok(Map.of("status", "sync started", "applicationCount", total));
    }

    // ── Legacy hardcoded endpoints (also include submitted applications) ───────

    @GetMapping("/linkedin/candidates")
    public List<Map<String, Object>> linkedinCandidates() {
        List<Map<String, Object>> list = new ArrayList<>(hardcodedLinkedIn());
        mockApplicationRepository.findAllByOrderByCreatedAtDesc().stream().map(this::appToSyncMap).forEach(list::add);
        return list;
    }

    @GetMapping("/jobstreet/candidates")
    public List<Map<String, Object>> jobstreetCandidates() {
        List<Map<String, Object>> list = new ArrayList<>(hardcodedJobStreet());
        mockApplicationRepository.findAllByOrderByCreatedAtDesc().stream().map(this::appToSyncMap).forEach(list::add);
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> jobToMap(MockJobRole r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle());
        m.put("company", r.getCompany() != null ? r.getCompany() : "");
        m.put("department", r.getDepartment() != null ? r.getDepartment() : "");
        m.put("location", r.getLocation() != null ? r.getLocation() : "");
        m.put("description", r.getDescription() != null ? r.getDescription() : "");
        m.put("requirements", r.getRequirements() != null ? r.getRequirements() : "");
        m.put("requisitionId", r.getRequisitionId() != null ? r.getRequisitionId() : "");
        m.put("webhookUrl", r.getWebhookUrl() != null ? r.getWebhookUrl() : "");
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
        return m;
    }

    private Map<String, Object> appToSyncMap(MockApplication a) {
        Map<String, Object> m = new HashMap<>();
        m.put("fullName", a.getFullName());
        m.put("email", a.getEmail());
        m.put("headline", a.getHeadline() != null ? a.getHeadline() : "");
        m.put("skills", a.getSkills() != null ? a.getSkills() : "");
        m.put("yearsExperience", a.getYearsExperience() != null ? a.getYearsExperience() : 0);
        m.put("resumeText", a.getResumeText() != null ? a.getResumeText() : "");
        m.put("sourceChannel", "JOBBOARD");
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");
        // Look up the linked TalentAI requisition from the job role
        if (a.getMockJobRoleId() != null) {
            mockJobRoleRepository.findById(a.getMockJobRoleId()).ifPresent(role -> {
                if (role.getRequisitionId() != null) {
                    m.put("requisitionId", role.getRequisitionId());
                    m.put("jobTitle", role.getTitle());
                }
            });
        }
        return m;
    }

    private List<Map<String, Object>> hardcodedLinkedIn() {
        return List.of(
            Map.of("fullName","Ahmad Farid Hassan","email","ahmad.farid@email.com","headline","Senior Java Engineer at CIMB Bank","skills","Java, Spring Boot, Microservices, AWS, Docker","yearsExperience",7,"resumeText","Senior Java Engineer with 7 years experience in financial technology. Led architecture of core banking API at CIMB serving 2M daily requests. Expert in Spring Boot, microservices, and cloud-native development on AWS.","sourceChannel","LINKEDIN"),
            Map.of("fullName","Priya Nair","email","priya.nair@email.com","headline","Data Scientist | ML Engineer at Axiata","skills","Python, TensorFlow, SQL, Spark, Machine Learning, NLP","yearsExperience",5,"resumeText","Data Scientist at Axiata with 5 years building ML models for telecom churn prediction and customer segmentation. Reduced churn 18% using ensemble models. Strong in Python, TensorFlow and Spark.","sourceChannel","LINKEDIN"),
            Map.of("fullName","Tan Wei Liang","email","tan.weiliang@email.com","headline","DevOps Engineer at Grab","skills","Kubernetes, Terraform, CI/CD, AWS, Python, Go","yearsExperience",6,"resumeText","DevOps Engineer at Grab managing infrastructure for ride-hailing platform. Built zero-downtime deployment pipeline handling 50k deployments/year. Expert in Kubernetes, Terraform and AWS.","sourceChannel","LINKEDIN"),
            Map.of("fullName","Nurul Izzah Malik","email","nurul.izzah@email.com","headline","Product Manager at Shopee","skills","Product Strategy, Agile, SQL, User Research, Roadmapping","yearsExperience",4,"resumeText","Product Manager at Shopee owning seller tools used by 300k merchants. Launched 8 features in 2023 driving 22% GMV increase. Background in engineering gives strong technical credibility.","sourceChannel","LINKEDIN"),
            Map.of("fullName","Rajesh Kumar Pillai","email","rajesh.pillai@email.com","headline","Full Stack Developer at Maybank","skills","React, Node.js, Java, PostgreSQL, Redis, TypeScript","yearsExperience",5,"resumeText","Full Stack Developer at Maybank building digital banking features. Delivered mobile-first loan application reducing drop-off 35%. Strong in React, TypeScript, Node.js and Java backends.","sourceChannel","LINKEDIN")
        );
    }

    private List<Map<String, Object>> hardcodedJobStreet() {
        return List.of(
            Map.of("fullName","Melissa Ong Xiu Ying","email","melissa.ong@email.com","headline","Business Analyst at PwC Malaysia","skills","Business Analysis, SQL, Tableau, JIRA, Stakeholder Management","yearsExperience",4,"resumeText","Business Analyst at PwC supporting digital transformation for banking clients. Mapped 40+ business processes and delivered ERP migration. Strong in requirements gathering and SQL reporting.","sourceChannel","JOBSTREET"),
            Map.of("fullName","Hafiz Zulkifli","email","hafiz.zulkifli@email.com","headline","Cloud Architect at TM One","skills","AWS, Azure, Cloud Architecture, Networking, Security, Terraform","yearsExperience",9,"resumeText","Cloud Architect at TM One designing hybrid cloud solutions for enterprise clients. AWS Solutions Architect Professional certified. Led RM12M cloud migration reducing infrastructure cost 40%.","sourceChannel","JOBSTREET"),
            Map.of("fullName","Stephanie Lee Mei Lin","email","stephanie.lee@email.com","headline","UX Designer at AirAsia","skills","Figma, User Research, Design Systems, Prototyping, Usability Testing","yearsExperience",5,"resumeText","UX Designer at AirAsia redesigning booking flows used by 50M users annually. Reduced booking abandonment 28% through research-driven redesign. Built and maintain the AirAsia design system.","sourceChannel","JOBSTREET"),
            Map.of("fullName","Mohammed Azri Roslan","email","mohd.azri@email.com","headline","Android Developer at Astro","skills","Kotlin, Android SDK, MVVM, REST APIs, Firebase, Jetpack Compose","yearsExperience",4,"resumeText","Android Developer at Astro building streaming app with 3M+ downloads. Led migration from Java to Kotlin and adoption of Jetpack Compose. Achieved 4.6 Play Store rating.","sourceChannel","JOBSTREET"),
            Map.of("fullName","Chong Yin Fong","email","chong.yinfong@email.com","headline","Cybersecurity Analyst at PETRONAS","skills","Penetration Testing, SIEM, ISO 27001, Network Security, Python","yearsExperience",6,"resumeText","Cybersecurity Analyst at PETRONAS protecting critical energy infrastructure. Conducted 20+ penetration tests. Implemented SIEM reducing incident response time 60%. CISSP certified.","sourceChannel","JOBSTREET"),
            Map.of("fullName","Siti Norbaya Hamzah","email","siti.norbaya@email.com","headline","HR Business Partner at Telekom Malaysia","skills","HR Strategy, Talent Management, HRMS, Employee Engagement, Recruitment","yearsExperience",8,"resumeText","HR Business Partner at Telekom Malaysia supporting 2,000-person engineering division. Reduced attrition 15% through structured L&D programs. Expert in talent management and succession planning.","sourceChannel","JOBSTREET")
        );
    }
}
