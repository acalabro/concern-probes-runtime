// ══════════════════════════════════════════════════════════════════════════════
// Concern Probes Runtime — Dashboard JS
// ══════════════════════════════════════════════════════════════════════════════

// ── State ─────────────────────────────────────────────────────────────
let adminToken  = '';
let probesList  = [];
let chartBar    = null;
let chartLine   = null;
let chartDonut  = null;
let lineHistory = [];          // [{ts, total}] for throughput sparkline
let lastTotal   = 0;
let currentTab  = 'dashboard';
let tickerVals  = new Array(20).fill(0);
const MAX_LINE  = 30;          // data points in throughput chart

// ── Chart.js global defaults ───────────────────────────────────────────
Chart.defaults.color          = '#4a6080';
Chart.defaults.borderColor    = '#1e3050';
Chart.defaults.font.family    = "'JetBrains Mono', monospace";
Chart.defaults.font.size      = 10;

// ── Tab navigation ─────────────────────────────────────────────────────
function showTab(name, navEl) {
  document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  if (navEl) navEl.classList.add('active');
  currentTab = name;
  const titles = { dashboard: 'DASHBOARD', probes: 'PROBES', create: 'NEW PROBE', test: 'SEND EVENT' };
  document.getElementById('topbarTitle').textContent = titles[name] || name.toUpperCase();
  if (name === 'probes' || name === 'test') renderProbes(probesList);
}

// ── Auth ───────────────────────────────────────────────────────────────
function applyToken() {
  adminToken = document.getElementById('adminToken').value.trim();
  refresh();
}

function authHdr() {
  return adminToken ? { 'Authorization': 'Bearer ' + adminToken } : {};
}

function togglePassVis(id, btn) {
  const el = document.getElementById(id);
  el.type = el.type === 'text' ? 'password' : 'text';
  btn.textContent = el.type === 'text' ? '🙈' : '👁';
}

// ── Clock ──────────────────────────────────────────────────────────────
function updateClock() {
  const now = new Date();
  document.getElementById('topbarTime').textContent =
    now.toISOString().replace('T', ' ').substring(0, 19) + ' UTC';
}
setInterval(updateClock, 1000);
updateClock();

// ── Main refresh ───────────────────────────────────────────────────────
async function refresh() {
  const btn = document.getElementById('refreshBtn');
  btn.classList.add('spinning');

  try {
    // Health
    const h = await fetch('/health').then(r => r.json());
    document.getElementById('nodeIdVal').textContent    = h.nodeId || '—';
    document.getElementById('uptimeVal').textContent    = formatUptime(h.uptimeMs);
    document.getElementById('probeCountVal').textContent = h.probes ?? '—';
    if (h.brokerUrl) document.getElementById('brokerVal').textContent = h.brokerUrl;

    // Auth status
    const authLabel = document.getElementById('authLabel');
    const res = await fetch('/api/probes', { headers: authHdr() });
    if (res.status === 401) {
      authLabel.textContent = 'AUTH TOKEN — REJECTED';
      authLabel.className = 'auth-label err';
      renderProbes([]);
      btn.classList.remove('spinning');
      return;
    }
    authLabel.textContent = adminToken ? 'AUTH TOKEN — ACCEPTED' : 'AUTH TOKEN — NONE';
    authLabel.className = 'auth-label ok';

    probesList = await res.json();
    document.getElementById('probesBadge').textContent = probesList.length;

    // Update all views
    updateStatCards(probesList);
    updateCharts(probesList);
    updateTicker(probesList);
    if (currentTab === 'probes' || currentTab === 'test') renderProbes(probesList);
    renderDashProbes(probesList);
    renderQuickfire(probesList);

    // Source types
    loadSourceTypes();
  } catch (e) {
    console.error('refresh error:', e);
  }
  btn.classList.remove('spinning');
}

// ── Stat cards ─────────────────────────────────────────────────────────
function updateStatCards(list) {
  const sent     = list.reduce((s, p) => s + p.sentCount,     0);
  const failed   = list.reduce((s, p) => s + p.failedCount,   0);
  const buffered = list.reduce((s, p) => s + p.bufferedCount, 0);
  const running  = list.filter(p => p.state === 'RUNNING').length;

  document.getElementById('statSent').textContent     = fmt(sent);
  document.getElementById('statFailed').textContent   = fmt(failed);
  document.getElementById('statBuffered').textContent  = fmt(buffered);
  document.getElementById('statRunning').textContent  = running;
  document.getElementById('statTotal').textContent    = list.length;

  document.getElementById('cardFailed').className   = 'stat-card' + (failed   > 0 ? ' danger' : '');
  document.getElementById('cardBuffered').className  = 'stat-card' + (buffered > 0 ? ' warn'   : '');
}

// ── Charts ─────────────────────────────────────────────────────────────
function updateCharts(list) {
  updateBarChart(list);
  updateLineChart(list);
  updateDonutChart(list);
}

function updateBarChart(list) {
  const labels  = list.map(p => shorten(p.id, 14));
  const sent    = list.map(p => p.sentCount);
  const failed  = list.map(p => p.failedCount);
  const buffered = list.map(p => p.bufferedCount);

  const data = {
    labels,
    datasets: [
      { label: 'Sent',     data: sent,     backgroundColor: 'rgba(16,185,129,.7)', borderRadius: 2 },
      { label: 'Failed',   data: failed,   backgroundColor: 'rgba(239,68,68,.7)',  borderRadius: 2 },
      { label: 'Buffered', data: buffered, backgroundColor: 'rgba(245,158,11,.7)', borderRadius: 2 },
    ]
  };

  if (chartBar) {
    chartBar.data = data;
    chartBar.update('none');
  } else {
    const ctx = document.getElementById('chartBar').getContext('2d');
    chartBar = new Chart(ctx, {
      type: 'bar',
      data,
      options: {
        responsive: true, maintainAspectRatio: false,
        animation: { duration: 300 },
        plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, padding: 10 } } },
        scales: {
          x: { grid: { color: 'rgba(30,48,80,.5)' }, ticks: { maxRotation: 30 } },
          y: { grid: { color: 'rgba(30,48,80,.5)' }, beginAtZero: true,
               ticks: { precision: 0 } }
        }
      }
    });
  }
}

function updateLineChart(list) {
  const total = list.reduce((s, p) => s + p.sentCount, 0);
  const delta = Math.max(0, total - lastTotal);
  lastTotal = total;

  const now = new Date().toLocaleTimeString('en', { hour12: false,
    hour: '2-digit', minute: '2-digit', second: '2-digit' });
  lineHistory.push({ ts: now, v: delta });
  if (lineHistory.length > MAX_LINE) lineHistory.shift();

  const data = {
    labels:   lineHistory.map(d => d.ts),
    datasets: [{
      label: 'events/refresh',
      data:  lineHistory.map(d => d.v),
      borderColor: '#00c8ff',
      backgroundColor: 'rgba(0,200,255,.06)',
      borderWidth: 1.5,
      pointRadius: 2,
      pointBackgroundColor: '#00c8ff',
      fill: true,
      tension: 0.4,
    }]
  };

  if (chartLine) {
    chartLine.data = data;
    chartLine.update('none');
  } else {
    const ctx = document.getElementById('chartLine').getContext('2d');
    chartLine = new Chart(ctx, {
      type: 'line',
      data,
      options: {
        responsive: true, maintainAspectRatio: false,
        animation: { duration: 200 },
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { color: 'rgba(30,48,80,.5)' }, ticks: { maxTicksLimit: 6 } },
          y: { grid: { color: 'rgba(30,48,80,.5)' }, beginAtZero: true, ticks: { precision: 0 } }
        }
      }
    });
  }
}

function updateDonutChart(list) {
  const running  = list.filter(p => p.state === 'RUNNING').length;
  const stopped  = list.filter(p => p.state === 'STOPPED').length;
  const created  = list.filter(p => p.state === 'CREATED').length;
  const error    = list.filter(p => p.state === 'ERROR').length;
  const total    = list.length;

  const data = {
    labels:   ['Running', 'Stopped', 'Created', 'Error'],
    datasets: [{
      data:            [running, stopped, created, error],
      backgroundColor: ['rgba(16,185,129,.8)', 'rgba(74,96,128,.5)', 'rgba(0,200,255,.5)', 'rgba(239,68,68,.8)'],
      borderColor:     ['#10b981', '#2a3d5a', '#00c8ff', '#ef4444'],
      borderWidth: 1,
      hoverOffset: 4,
    }]
  };

  if (chartDonut) {
    chartDonut.data = data;
    chartDonut.update('none');
  } else {
    const ctx = document.getElementById('chartDonut').getContext('2d');
    chartDonut = new Chart(ctx, {
      type: 'doughnut',
      data,
      options: {
        responsive: true, maintainAspectRatio: false,
        cutout: '65%',
        animation: { duration: 400 },
        plugins: {
          legend: { position: 'bottom', labels: { boxWidth: 10, padding: 8 } },
          tooltip: {
            callbacks: {
              label: ctx => ` ${ctx.label}: ${ctx.raw} (${total ? Math.round(ctx.raw/total*100) : 0}%)`
            }
          }
        }
      }
    });
  }
}

// ── Ticker (sidebar activity indicator) ───────────────────────────────
function updateTicker(list) {
  const total = list.reduce((s, p) => s + p.sentCount, 0);
  const delta = Math.max(0, total - (tickerVals[tickerVals.length - 1] || 0));
  tickerVals.push(delta);
  if (tickerVals.length > 20) tickerVals.shift();
  const max = Math.max(...tickerVals, 1);
  const wrap = document.getElementById('ticker');
  wrap.innerHTML = tickerVals.map(v =>
    `<div class="ticker-bar" style="height:${Math.max(2, Math.round(v/max*16))}px;opacity:${v > 0 ? 1 : .3}"></div>`
  ).join('');
}

// ── Dashboard probe overview ────────────────────────────────────────────
function renderDashProbes(list) {
  const tb = document.getElementById('dashProbeBody');
  if (!list.length) {
    tb.innerHTML = '<tr class="empty-row"><td colspan="7">No probes. Create one in New Probe ↗</td></tr>';
    return;
  }
  tb.innerHTML = list.map(p => {
    const sc = (p.state || 'created').toLowerCase();
    const err = p.lastError
      ? `<span style="color:var(--danger);font-size:10px">${esc(shorten(p.lastError, 40))}</span>`
      : '<span style="color:var(--text-faint)">—</span>';
    return `<tr>
      <td><code>${esc(p.id)}</code></td>
      <td style="color:#93c5fd;font-size:11px">${esc(p.probeType || '')}</td>
      <td><span class="badge ${sc}">${p.state}</span></td>
      <td style="color:var(--success);font-family:var(--font-mono)">${fmt(p.sentCount)}</td>
      <td style="color:${p.failedCount > 0 ? 'var(--danger)' : 'var(--text-faint)'}">${fmt(p.failedCount)}</td>
      <td style="color:${p.bufferedCount > 0 ? 'var(--amber)' : 'var(--text-faint)'}">${fmt(p.bufferedCount)}</td>
      <td>${err}</td>
    </tr>`;
  }).join('');
}

// ── Probes management table ────────────────────────────────────────────
function renderProbes(list) {
  const tb = document.getElementById('probesBody');
  if (!list.length) {
    tb.innerHTML = '<tr class="empty-row"><td colspan="9">No probes defined — go to New Probe ↗</td></tr>';
    return;
  }
  tb.innerHTML = list.map(p => {
    const sc = (p.state || 'created').toLowerCase();
    const isRunning = p.state === 'RUNNING';
    const toggleBtn = isRunning
      ? `<button class="btn btn-amber" onclick="act('${esc(p.id)}','stop')">⏹ Stop</button>`
      : `<button class="btn btn-green" onclick="act('${esc(p.id)}','start')">▶ Start</button>`;
    const ingest = p.ingestEnabled
      ? `<code>/ingest/${esc(p.ingestPath)}</code>`
      : '<span style="color:var(--text-faint)">—</span>';
    const src = p.sourceEnabled
      ? `<code>${esc(p.sourceType)}</code>`
      : '<span style="color:var(--text-faint)">—</span>';
    return `<tr>
      <td><code style="font-size:10px">${esc(p.id)}</code></td>
      <td style="color:#93c5fd;font-size:11px">${esc(p.probeType || '')}</td>
      <td><span class="badge ${sc}">${p.state}</span></td>
      <td>${ingest}</td>
      <td>${src}</td>
      <td style="font-family:var(--font-mono);color:var(--success)">${fmt(p.sentCount)}</td>
      <td style="font-family:var(--font-mono);color:${p.failedCount > 0 ? 'var(--danger)' : 'var(--text-dim)'}">${fmt(p.failedCount)}</td>
      <td style="font-family:var(--font-mono);color:${p.bufferedCount > 0 ? 'var(--amber)' : 'var(--text-dim)'}">${fmt(p.bufferedCount)}</td>
      <td style="white-space:nowrap">
        ${toggleBtn}
        <button class="btn btn-red" onclick="del('${esc(p.id)}')">✕</button>
      </td>
    </tr>`;
  }).join('');
}

// ── Quickfire table (test tab) ─────────────────────────────────────────
function renderQuickfire(list) {
  const tb = document.getElementById('quickfireBody');
  const withIngest = list.filter(p => p.ingestEnabled);
  if (!withIngest.length) {
    tb.innerHTML = '<tr class="empty-row"><td colspan="4">No probes with ingest enabled</td></tr>';
    return;
  }
  tb.innerHTML = withIngest.map(p => {
    const sc = (p.state || 'created').toLowerCase();
    return `<tr>
      <td><code style="font-size:10px">${esc(p.id)}</code></td>
      <td><code>/ingest/${esc(p.ingestPath)}</code></td>
      <td><span class="badge ${sc}">${p.state}</span></td>
      <td>
        <button class="btn btn-accent" onclick="quickSend('${esc(p.ingestPath)}')">⚡ Fire</button>
      </td>
    </tr>`;
  }).join('');
}

// ── Lifecycle ──────────────────────────────────────────────────────────
async function act(id, op) {
  try {
    await fetch(`/api/probes/${encodeURIComponent(id)}/${op}`, { method: 'POST', headers: authHdr() });
    await refresh();
  } catch (e) { alert('Error: ' + e.message); }
}

async function del(id) {
  if (!confirm(`Delete probe "${id}"?`)) return;
  try {
    await fetch(`/api/probes/${encodeURIComponent(id)}`, { method: 'DELETE', headers: authHdr() });
    await refresh();
  } catch (e) { alert('Error: ' + e.message); }
}

// ── Create probe ───────────────────────────────────────────────────────
function toggleIngest() {
  const on = document.getElementById('cIngestEnabled').checked;
  document.getElementById('ingestPathField').style.opacity  = on ? '1' : '.4';
  document.getElementById('ingestTokenField').style.opacity = on ? '1' : '.4';
}

function toggleSource(btn) {
  const body  = document.getElementById('sourceBody');
  const arrow = document.getElementById('srcArrow');
  const open  = body.classList.toggle('open');
  arrow.textContent = open ? '▲' : '▼';
}

const SOURCE_HINTS = {
  'synthetic': 'intervalMs, valueMin, valueMax',
  'csv-file':  'path (required), hasHeader, delimiter, loop, perRowDelayMs, columnFilter',
  'tail-file': 'path (required), mode ("tail"|"poll"), pollIntervalMs, fromBeginning',
  'http-poll': 'url (required), intervalMs, method, headers, responseFormat, jsonPath, timeoutMs',
};
function updateSourceHint() {
  const t = document.getElementById('cSrcType').value;
  document.getElementById('srcHint').textContent = t ? '⚙ ' + (SOURCE_HINTS[t] || '') : '';
}

async function loadSourceTypes() {
  try {
    const types = await fetch('/api/sources', { headers: authHdr() }).then(r => r.json());
    const sel = document.getElementById('cSrcType');
    const cur = sel.value;
    while (sel.options.length > 1) sel.remove(1);
    types.forEach(t => { const o = new Option(t, t); sel.add(o); });
    if (cur) sel.value = cur;
  } catch (_) {}
}

async function createProbe() {
  const resEl = document.getElementById('createResult');
  resEl.className = ''; resEl.textContent = '';

  const fi  = id => document.getElementById(id).value.trim() || document.getElementById(id).placeholder;
  const fip = id => document.getElementById(id).value        || document.getElementById(id).placeholder;

  const id       = document.getElementById('cId').value.trim();
  const type     = document.getElementById('cType').value.trim();
  const name     = document.getElementById('cName').value.trim() || type;
  const url      = fi('cUrl');
  const topic    = fi('cTopic');
  const user     = fi('cUser');
  const pass     = fip('cPass');
  const ingestOn    = document.getElementById('cIngestEnabled').checked;
  const ingestPath  = document.getElementById('cIngestPath').value.trim() || type;
  const ingestToken = document.getElementById('cIngestToken').value.trim();
  const evName   = fi('cEvName');
  const cep      = document.getElementById('cCep').value;
  const dataFld  = document.getElementById('cDataField').value.trim();
  const srcType  = document.getElementById('cSrcType').value;
  const srcRaw   = document.getElementById('cSrcParams').value.trim();
  const bufOn    = document.getElementById('cBuffer').checked;
  const autoStart = document.getElementById('cAutoStart').checked;

  if (!type) { showResult(resEl, '⚠ Probe Type is required', false); return; }

  let srcParams = {};
  if (srcType && srcRaw) {
    try { srcParams = JSON.parse(srcRaw); }
    catch (_) { showResult(resEl, '✗ Source params: invalid JSON', false); return; }
  }

  const def = {
    ...(id ? { id } : {}),
    name, probeType: type,
    broker: { url, topic, username: user || undefined, password: pass || undefined },
    ...(ingestOn ? { ingest: { enabled: true, path: ingestPath,
                               authToken: ingestToken || undefined,
                               payloadMode: 'passthrough' } } : {}),
    eventTemplate: { name: evName, cepType: cep, dataField: dataFld || undefined },
    ...(srcType ? { source: { type: srcType, config: srcParams } } : {}),
    buffer: { enabled: bufOn },
    autoStart,
  };

  try {
    const r = await fetch('/api/probes', {
      method: 'POST',
      headers: { ...authHdr(), 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    });
    const json = await r.json();
    if (r.ok) {
      showResult(resEl, `✓ Probe "${json.id || type}" created — ${json.state}`, true);
      await refresh();
    } else {
      showResult(resEl, `✗ ${json.error || r.statusText}`, false);
    }
  } catch (e) { showResult(resEl, '✗ ' + e.message, false); }
}

function showResult(el, msg, ok) {
  el.className = 'result-msg ' + (ok ? 'ok' : 'err');
  el.textContent = msg;
}

// ── Send event ─────────────────────────────────────────────────────────
async function sendTest() {
  const name = document.getElementById('tProbe').value.trim();
  const tok  = document.getElementById('tToken').value.trim();
  const body = document.getElementById('tPayload').value;
  const out  = document.getElementById('testResponse');

  if (!name) { out.className = 'response-box err'; out.textContent = '✗ Probe name required'; return; }

  let parsed;
  try { parsed = JSON.parse(body); }
  catch (e) { out.className = 'response-box err'; out.textContent = '✗ Invalid JSON: ' + e.message; return; }

  const hdrs = { 'Content-Type': 'application/json' };
  if (tok) hdrs.Authorization = 'Bearer ' + tok;

  try {
    const r = await fetch('/ingest/' + encodeURIComponent(name), {
      method: 'POST', headers: hdrs, body: JSON.stringify(parsed)
    });
    const j = await r.json();
    out.className = 'response-box ' + (r.ok ? 'ok' : 'err');
    out.textContent = `HTTP ${r.status}\n${JSON.stringify(j, null, 2)}`;
    if (r.ok) refresh();
  } catch (e) {
    out.className = 'response-box err';
    out.textContent = '✗ ' + e.message;
  }
}

async function quickSend(path) {
  const body = document.getElementById('tPayload').value;
  const tok  = document.getElementById('tToken').value.trim();
  let parsed;
  try { parsed = JSON.parse(body); }
  catch (e) { alert('Fix the JSON payload in the form above first'); return; }
  const hdrs = { 'Content-Type': 'application/json' };
  if (tok) hdrs.Authorization = 'Bearer ' + tok;
  try {
    const r = await fetch('/ingest/' + encodeURIComponent(path), {
      method: 'POST', headers: hdrs, body: JSON.stringify(parsed)
    });
    const j = await r.json();
    const out = document.getElementById('testResponse');
    out.className = 'response-box ' + (r.ok ? 'ok' : 'err');
    out.textContent = `[quickfire → ${path}]  HTTP ${r.status}\n${JSON.stringify(j, null, 2)}`;
    if (r.ok) refresh();
  } catch (e) { alert('Error: ' + e.message); }
}

// ── Helpers ────────────────────────────────────────────────────────────
function fmt(n)        { return n >= 1000 ? (n/1000).toFixed(1)+'k' : String(n); }
function shorten(s, n) { return s && s.length > n ? s.substring(0, n) + '…' : (s || ''); }
function esc(s)        { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

function formatUptime(ms) {
  if (!ms) return '—';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${ss}s`;
  return `${ss}s`;
}

// ── Bootstrap ──────────────────────────────────────────────────────────
refresh();
setInterval(refresh, 5000);
