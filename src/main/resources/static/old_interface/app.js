// ── state ────────────────────────────────────────────────────────────
let adminToken = '';

// ── show/hide password toggle ─────────────────────────────────────────
function togglePassVis(inputId, btn) {
  const input = document.getElementById(inputId);
  const isText = input.type === 'text';
  input.type = isText ? 'password' : 'text';
  btn.textContent = isText ? '👁' : '🙈';
}

// ── login ────────────────────────────────────────────────────────────
function applyToken(e) {
  e.preventDefault();
  adminToken = document.getElementById('adminToken').value.trim();
  refresh();
}

function authHdr() {
  return adminToken ? { 'Authorization': 'Bearer ' + adminToken } : {};
}

// ── data loading ─────────────────────────────────────────────────────
async function refresh() {
  const statusEl = document.getElementById('loginStatus');
  try {
    // health (no auth needed)
    const h = await fetch('/health').then(r => r.json());
    document.getElementById('nodeInfo').textContent =
      'node: ' + h.nodeId + ' · uptime: ' + Math.round(h.uptimeMs / 1000) + 's · probes: ' + h.probes;

    // probes list (needs auth)
    const res = await fetch('/api/probes', { headers: authHdr() });
    if (res.status === 401) {
      statusEl.textContent = '⚠ Wrong token';
      statusEl.style.color = '#fca5a5';
      renderProbes([]);
      return;
    }
    const list = await res.json();
    statusEl.textContent = adminToken ? '✓ Connected' : '✓ (no auth)';
    statusEl.style.color = '#6ee7b7';
    renderProbes(list);

    // also load available source types into select
    loadSourceTypes();
  } catch (e) {
    statusEl.textContent = '✗ ' + e.message;
    statusEl.style.color = '#fca5a5';
  }
}

async function loadSourceTypes() {
  try {
    const types = await fetch('/api/sources', { headers: authHdr() }).then(r => r.json());
    const sel = document.getElementById('cSrcType');
    const current = sel.value;
    // keep "— none —" option, repopulate the rest
    while (sel.options.length > 1) sel.remove(1);
    types.forEach(t => {
      const o = document.createElement('option');
      o.value = t; o.text = t;
      sel.add(o);
    });
    if (current) sel.value = current;
  } catch (_) {}
}

// ── probes table ─────────────────────────────────────────────────────
function renderProbes(list) {
  const tbody = document.querySelector('#probesTbl tbody');
  if (!list.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">No probes defined. Create one below ↓</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(p => {
    const sc = (p.state || 'created').toLowerCase();
    const isRunning = p.state === 'RUNNING';
    const toggleBtn = isRunning
      ? `<button class="btn btn-yellow" onclick="act('${p.id}','stop')">⏹ Stop</button>`
      : `<button class="btn btn-green" onclick="act('${p.id}','start')">▶ Start</button>`;
    const ingest = p.ingestEnabled
      ? `<code>/ingest/${p.ingestPath}</code>`
      : '<span style="color:#475569">—</span>';
    const src = p.sourceEnabled
      ? `<code>${p.sourceType}</code>`
      : '<span style="color:#475569">—</span>';
    return `<tr>
      <td><code style="font-size:11px">${p.id}</code></td>
      <td style="color:#93c5fd">${p.probeType || ''}</td>
      <td><span class="badge ${sc}">${p.state}</span></td>
      <td>${ingest}</td>
      <td>${src}</td>
      <td>${p.sentCount}</td>
      <td style="color:${p.failedCount>0?'#fca5a5':'inherit'}">${p.failedCount}</td>
      <td style="color:${p.bufferedCount>0?'#fcd34d':'inherit'}">${p.bufferedCount}</td>
      <td style="white-space:nowrap">
        ${toggleBtn}
        <button class="btn btn-red" onclick="del('${p.id}')">✕ Delete</button>
      </td>
    </tr>`;
  }).join('');
}

// ── lifecycle actions ─────────────────────────────────────────────────
async function act(id, op) {
  try {
    await fetch('/api/probes/' + id + '/' + op, { method: 'POST', headers: authHdr() });
    await refresh();
  } catch (e) { alert('Error: ' + e.message); }
}

async function del(id) {
  if (!confirm('Delete probe "' + id + '"?')) return;
  try {
    await fetch('/api/probes/' + id, { method: 'DELETE', headers: authHdr() });
    await refresh();
  } catch (e) { alert('Error: ' + e.message); }
}

// ── create probe form ─────────────────────────────────────────────────
function toggleIngest() {
  const on = document.getElementById('cIngestEnabled').checked;
  document.getElementById('ingestFields').style.opacity = on ? '1' : '.4';
  document.getElementById('ingestTokenField').style.opacity = on ? '1' : '.4';
}

function toggleSource(btn) {
  const body = document.getElementById('sourceBody');
  const arrow = document.getElementById('srcArrow');
  const open = body.classList.toggle('open');
  arrow.textContent = open ? '▲' : '▼';
}

const SOURCE_HINTS = {
  synthetic: 'intervalMs (ms between events), valueMin, valueMax',
  'csv-file': 'path (required), hasHeader, delimiter, loop, perRowDelayMs, columnFilter',
  'tail-file': 'path (required), mode ("tail"|"poll"), pollIntervalMs, fromBeginning',
  'http-poll': 'url (required), intervalMs, method, headers, responseFormat, jsonPath, timeoutMs',
};
function updateSourceHint() {
  const t = document.getElementById('cSrcType').value;
  document.getElementById('srcHint').textContent = t ? '⚙ Params: ' + (SOURCE_HINTS[t] || '') : '';
}

async function createProbe() {
  const resultEl = document.getElementById('createResult');
  resultEl.textContent = '';
  resultEl.className = 'result';

  // Helper: returns the typed value, or the placeholder as default if empty.
  // Use for fields whose placeholder IS a real sensible default.
  const fi  = id => document.getElementById(id).value.trim()
                 || document.getElementById(id).placeholder;
  // For password: don't trim (preserves spaces), fall back to placeholder.
  const fip = id => document.getElementById(id).value
                 || document.getElementById(id).placeholder;

  const id       = document.getElementById('cId').value.trim();   // empty = server generates
  const type     = document.getElementById('cType').value.trim(); // required — no placeholder default
  const name     = document.getElementById('cName').value.trim() || type;

  const url      = fi('cUrl');    // default: tcp://activemq:61616
  const topic    = fi('cTopic'); // default: DROOLS-InstanceOne
  const user     = fi('cUser');  // default: system
  const pass     = fip('cPass'); // default: manager

  const ingestOn    = document.getElementById('cIngestEnabled').checked;
  const ingestPath  = document.getElementById('cIngestPath').value.trim() || type;
  const ingestToken = document.getElementById('cIngestToken').value.trim();

  const evName  = fi('cEvName');    // default: ProbeEvent
  const cep     = document.getElementById('cCep').value;
  const dataFld = document.getElementById('cDataField').value.trim(); // optional

  const srcType   = document.getElementById('cSrcType').value;
  const srcRaw    = document.getElementById('cSrcParams').value.trim();
  const bufOn     = document.getElementById('cBuffer').checked;
  const autoStart = document.getElementById('cAutoStart').checked;

  // Only probeType is truly required — everything else has a placeholder default.
  if (!type) { setResult(resultEl, '⚠ Probe Type is required', false); return; }

  // parse optional source params
  let srcParams = {};
  if (srcType && srcRaw) {
    try { srcParams = JSON.parse(srcRaw); }
    catch(_) { setResult(resultEl, 'Source params: invalid JSON', false); return; }
  }

  // show exactly what we're about to send (helps debug autofill issues)
  const passMask = pass ? pass.substring(0, 2) + '*'.repeat(Math.max(0, pass.length - 2)) : '(empty)';
  console.log('[createProbe] broker credentials → user:', user || '(empty)', 'pass:', passMask);
  setResult(resultEl, `Sending → user: "${user || '(empty)'}", pass: "${passMask}" …`, false);

  const def = {
    ...(id ? {id} : {}),
    name,
    probeType: type,
    broker: { url, topic, username: user || undefined, password: pass || undefined },
    ...(ingestOn ? { ingest: {
      enabled: true,
      path: ingestPath,
      authToken: ingestToken || undefined,
      payloadMode: 'passthrough'
    }} : {}),
    eventTemplate: {
      name: evName,
      cepType: cep,
      dataField: dataFld || undefined
    },
    ...(srcType ? { source: { type: srcType, config: srcParams } } : {}),
    buffer: { enabled: bufOn },
    autoStart
  };

  try {
    const res = await fetch('/api/probes', {
      method: 'POST',
      headers: { ...authHdr(), 'Content-Type': 'application/json' },
      body: JSON.stringify(def)
    });
    const json = await res.json();
    if (res.ok) {
      setResult(resultEl, `✓ Probe "${json.id || type}" created — state: ${json.state}`, true);
      await refresh();
    } else {
      setResult(resultEl, `✗ ${json.error || res.statusText}`, false);
    }
  } catch(e) {
    setResult(resultEl, '✗ ' + e.message, false);
  }
}

function setResult(el, msg, ok) {
  el.textContent = msg;
  el.className = 'result ' + (ok ? 'ok' : 'err');
}

// ── send test event ───────────────────────────────────────────────────
async function sendTest() {
  const name = document.getElementById('tProbe').value.trim();
  const tok  = document.getElementById('tToken').value.trim();
  const body = document.getElementById('tPayload').value;
  const out  = document.getElementById('testResult');
  if (!name) { setResult(out, 'Probe name required', false); return; }
  let parsed;
  try { parsed = JSON.parse(body); }
  catch(e) { setResult(out, 'Invalid JSON: ' + e.message, false); return; }
  const hdrs = { 'Content-Type': 'application/json' };
  if (tok) hdrs.Authorization = 'Bearer ' + tok;
  try {
    const r = await fetch('/ingest/' + encodeURIComponent(name), {
      method: 'POST', headers: hdrs, body: JSON.stringify(parsed)
    });
    const j = await r.json();
    setResult(out, 'HTTP ' + r.status + ' → ' + JSON.stringify(j), r.ok);
    if (r.ok) refresh();
  } catch(e) { setResult(out, '✗ ' + e.message, false); }
}

// ── auto-refresh ─────────────────────────────────────────────────────
refresh();
setInterval(refresh, 5000);
