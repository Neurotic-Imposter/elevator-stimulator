'use strict';
/* Browser side. This file NEVER simulates anything: it sends commands to the Java server and
   draws the state and the recorded events that Java sends back. */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));

let S = null;            // latest state from Java
let EV = [];             // all trace events received (max 3000)
let epoch = 0, speed = 700, running = false, follow = true, filter = 'ALL';
let selAction = null, selEv = null, builtKey = '', tab = 'sim', online = true;
let fh = 56, lastEventSeen = 0;
const openIds = new Set(), srcCache = {};
const CATS = ['ALL', 'PASSENGER', 'REQUEST', 'SCHEDULER', 'MOVEMENT', 'DOORS', 'FAULT', 'EXCEPTION', 'SYSTEM'];
const CAT_LABEL = { ALL: 'All', PASSENGER: 'Passenger', REQUEST: 'Requests', SCHEDULER: 'Scheduler', MOVEMENT: 'Movement', DOORS: 'Doors', FAULT: 'Faults', EXCEPTION: 'Exceptions', SYSTEM: 'System' };
const ECOL = i => `var(--e${i % 6})`;

/* ---------------- network ---------------- */
async function api(method, path, params) {
  const q = params ? '?' + new URLSearchParams(params) : '';
  try {
    const r = await fetch('/api/' + path + q, { method });
    const d = await r.json();
    setOnline(true);
    return d;
  } catch (e) { setOnline(false); return null; }
}
function setOnline(v) { online = v; $('#banner').hidden = v; }

// Send a command to Java, then draw what comes back (state + new events).
async function act(path, params, animate = true) {
  const d = await api('POST', path, params);
  if (!d) return null;
  if (d.state) applyResponse(d, animate);
  if (!d.ok) toast(d.error || 'Request failed', true);
  return d;
}
function applyResponse(d, animate) {
  const st = d.state;
  if (st.epoch !== epoch) { EV = []; epoch = st.epoch; selAction = null; selEv = null; builtKey = ''; openIds.clear(); }
  const fresh = d.events || [];
  EV.push(...fresh);
  if (EV.length > 3000) EV.splice(0, EV.length - 3000);
  S = st;
  lastEventSeen = st.lastEventId;
  renderAll(animate ? fresh : []);
}
async function loadAll() {
  const d = await api('GET', 'state');
  if (!d) return;
  epoch = -1;
  applyResponse({ state: d, events: d.events }, false);
}

/* ---------------- rendering entry ---------------- */
function renderAll(fresh) {
  renderHeader();
  renderBuilding(fresh);
  renderExec();
  renderTimeline();
  renderElevStats();
  if (tab === 'flow') renderFlow();
  if (tab === 'algo') renderAlgoLive();
  if (tab === 'reports') loadReport();
}

function renderHeader() {
  const st = $('#k-status');
  let txt = 'Idle', cls = 'pill';
  if (S.faults > 0 && !running) { txt = S.faults + ' fault' + (S.faults > 1 ? 's' : ''); cls = 'pill bad'; }
  if (running) { txt = 'Running'; cls = 'pill run'; }
  else if (S.done && S.stats.total > 0) { txt = 'All done'; cls = 'pill done'; }
  else if (S.faults === 0) txt = S.done ? 'Idle · nothing to do' : 'Paused';
  st.textContent = txt; st.className = cls;
  $('#k-tick').textContent = S.clock;
  $('#k-pending').textContent = S.pending;
  $('#k-faults').textContent = S.faults;
  $('#k-sched').value = S.schedulerKey;
  $('#b-run').textContent = running ? '⏸ Pause' : '▶ Run';
  ['c-src', 'c-dst', 'c-stopf', 'w-floor'].forEach(id => $('#' + id).max = S.floors - 1);
  $('#c-w').max = S.capacity;
  $('#status-line').textContent = 'Last scheduler reason: ' + S.lastReason + ' · ' + S.stats.completed + '/' + S.stats.total + ' passengers delivered';
}

/* ---------------- glass building ---------------- */
function buildBuilding() {
  const n = S.floors, m = S.elevators.length;
  fh = Math.max(34, Math.min(60, Math.floor(560 / n)));
  const bld = $('#bld');
  bld.style.setProperty('--fh', fh + 'px');
  bld.style.setProperty('--n', n);
  let h = `<div class="bld-head"><div></div><div class="cars" style="grid-template-columns:repeat(${m},1fr)">`;
  for (let i = 0; i < m; i++) h += `<div class="car-card" id="cc${i}" style="--c:${ECOL(i)}"></div>`;
  h += `</div></div><div class="bld-body" id="bbody"><div id="bfloors">`;
  for (let f = n - 1; f >= 0; f--) h += `<div class="floor"><span class="flabel">${f}</span><div class="wait" id="w${f}"></div></div>`;
  h += `</div><div class="shafts" id="bshafts" style="grid-template-columns:repeat(${m},1fr)">`;
  for (let i = 0; i < m; i++) {
    h += `<div class="shaft"><div class="cabin s-IDLE" id="cab${i}" style="--ec:${ECOL(i)}"><div class="pax"></div>
      <div class="door l"></div><div class="door r"></div><div class="fx">✕</div><div class="tag">E${i}</div></div></div>`;
  }
  bld.innerHTML = h + '</div></div>';
  const sel = $('#c-elev');
  sel.innerHTML = S.elevators.map(e => `<option value="${e.id}">E${e.id}</option>`).join('');
  bld.style.setProperty('--move', Math.round(speed * 0.85));
}
function renderBuilding(fresh) {
  const key = S.epoch + ':' + S.floors + ':' + S.elevators.length;
  if (key !== builtKey) { buildBuilding(); builtKey = key; }
  const bld = $('#bld');
  bld.style.setProperty('--move', Math.round(speed * 0.85));
  const sym = { UP: '▲', DOWN: '▼', NONE: '–' };
  S.elevators.forEach(e => {
    const cab = $('#cab' + e.id);
    cab.style.setProperty('--f', e.floor);
    cab.className = 'cabin s-' + e.state;
    const served = fresh.some(v => v.eid === e.id && v.cls === 'Elevator' && (v.method === 'board' || v.method === 'exit'));
    const open = e.state === 'DOORS_OPEN';
    cab.classList.toggle('open', open || served);
    if (served && !open) setTimeout(() => cab.classList.remove('open'), Math.min(900, speed * 0.65)); // doors close after boarding/exit
    $('.pax', cab).innerHTML = e.passengers.slice(0, 8).map(() => '<i></i>').join('');
    $('.tag', cab).textContent = `E${e.id} ${sym[e.direction]} ${e.passengers.length}p`;
    const card = $('#cc' + e.id);
    card.classList.toggle('fault', e.state === 'FAULT');
    card.innerHTML = `<div class="r1"><b class="id">E${e.id}</b><span class="disp">${String(e.floor).padStart(2, '0')}</span><span class="mono">${sym[e.direction]}</span><span class="st ${e.state}">${e.state.replace('_', ' ')}</span></div>
      <div class="lb"><i style="width:${Math.min(100, 100 * e.load / e.capacity)}%"></i></div>
      <small>${e.load}/${e.capacity} kg · ${e.passengers.length} pax</small><small>stops: ${e.stops.length ? e.stops.join(', ') : '—'}</small>`;
  });
  for (let f = 0; f < S.floors; f++) {
    $('#w' + f).innerHTML = S.waiting[f].slice(0, 5).map(p =>
      `<span class="chip ${p.elevator < 0 ? 'none' : ''}" style="--c:${p.elevator < 0 ? '#9aa8c4' : ECOL(p.elevator)}" title="P${p.id}, ${p.weight} kg, ${p.elevator < 0 ? 'not assigned yet' : 'assigned to E' + p.elevator}">P${p.id}→${p.dest}</span>`).join('')
      + (S.waiting[f].length > 5 ? `<span class="chip none">+${S.waiting[f].length - 5}</span>` : '');
  }
  fresh.filter(v => v.cls === 'Elevator' && (v.method === 'board' || v.method === 'exit')).forEach((v, i) => setTimeout(() => walker(v), i * 140));
}
// A little passenger that walks between the waiting area and the cabin (placed from real board/exit events).
function walker(v) {
  const body = $('#bbody'), cab = $('#cab' + v.eid);
  if (!body || !cab) return;
  const br = body.getBoundingClientRect(), cr = cab.getBoundingClientRect();
  const lw = $('#bfloors').getBoundingClientRect().width;
  const y0 = (S.floors - 1 - v.floor) * fh + fh / 2 - 8;
  const cx = cr.left - br.left + cr.width / 2 - 10, cy = cr.top - br.top + cr.height / 2 - 8;
  const w = document.createElement('div');
  w.className = 'walker'; w.textContent = 'P' + v.pid;
  const inward = v.method === 'board';
  const from = inward ? [lw - 30, y0] : [cx, cy], to = inward ? [cx, cy] : [lw - 30, y0 - 0];
  w.style.left = from[0] + 'px'; w.style.top = from[1] + 'px';
  body.appendChild(w);
  const t = Math.max(350, speed * 0.7);
  const a = w.animate([{ transform: 'translate(0,0)', opacity: 1 }, { transform: `translate(${to[0] - from[0]}px,${to[1] - from[1]}px)`, opacity: inward ? 0.2 : 1 }], { duration: t, easing: 'ease-in-out' });
  a.onfinish = () => w.remove();
}

/* ---------------- execution steps (shared by Simulation and Execution Flow) ---------------- */
function hl(code) {
  const kw = 'public|private|protected|static|final|abstract|class|interface|enum|extends|implements|new|return|if|else|for|while|throw|throws|try|catch|finally|void|int|double|boolean|String|true|false|null|this|import|continue|break|switch|case';
  const re = new RegExp(`(\\/\\/.*)|("(?:\\\\.|[^"\\\\])*")|\\b(${kw})\\b|\\b(\\d+)\\b|\\b([A-Z]\\w*)\\b`, 'g');
  return esc(code).replace(/&quot;/g, '"').replace(re, (m, c, s, k, n, t) => c ? `<span class="c">${c}</span>` : s ? `<span class="s">${s}</span>` : k ? `<span class="k">${k}</span>` : n ? `<span class="n">${n}</span>` : `<span class="t">${t}</span>`);
}
function srcHtml(d, hlLine) {
  const lines = d.code.replace(/\n$/, '').split('\n');
  return `<div class="src"><pre>` + lines.map((l, i) => {
    const no = d.start + i;
    return `<span class="ln ${no === hlLine ? 'hl' : ''}"><i>${no}</i>${hl(l)}</span>`;
  }).join('') + `</pre></div><small>${esc(d.file)} lines ${d.start}–${d.end}${hlLine ? ' · red line = where it was thrown' : ''}</small>`;
}
async function fetchSource(cls, method) {
  const k = cls + '|' + (method || '');
  if (!srcCache[k]) srcCache[k] = await api('GET', 'source', { cls, method: method || '' });
  return srcCache[k];
}
function splitCaught(c) { const p = (c || '').split('.'); return [p[0], p[1]]; }

function candTable(ev) {
  return `<table class="tbl"><tr><th>Elevator</th><th>Floor</th><th>Dir</th><th>State</th><th>Distance</th><th>Eligible</th><th>Why</th></tr>` +
    ev.candidates.map(c => `<tr class="${c.id === ev.chosen ? 'chosen' : c.eligible ? '' : 'no'}"><td>E${c.id}${c.id === ev.chosen ? ' ✔ chosen' : ''}</td><td>${c.floor}</td><td>${c.direction}</td><td>${c.state}</td><td>${c.distance}</td><td>${c.eligible ? 'yes' : 'no'}</td><td>${esc(c.note)}</td></tr>`).join('') + '</table>';
}
function stepBody(ev) {
  let h = ev.exType ? `<div class="kv"><b>Exception</b><span class="mono">${esc(ev.exType)}</span><b>Declared in</b><span class="mono">${esc(ev.file)}</span><b>Message</b><span class="mono">${esc(ev.result)}</span><b>Time</b><span>tick ${ev.tick} · action #${ev.action}</span></div><div class="why">${esc(ev.explain)}</div>` : `<div class="kv"><b>Class</b><span class="mono">${esc(ev.cls)}</span><b>Method</b><span class="mono">${esc(ev.method)}</span><b>Source file</b><span class="mono">${esc(ev.file)}</span>
    <b>Input</b><span class="mono">${esc(ev.args) || '(none)'}</span><b>Result</b><span class="mono">${esc(ev.result)}</span><b>Time</b><span>tick ${ev.tick} · action #${ev.action}</span></div>
    <div class="why">${esc(ev.explain)}</div>`;
  if (ev.change) h += `<div class="chg">State change: ${esc(ev.change)}</div>`;
  if (ev.candidates) h += `<div style="margin-top:6px"><b>Scheduler comparison</b> (from <code>${esc(ev.cls)}.explain()</code>)</div>${candTable(ev)}`;
  if (ev.exType) {
    const [cc, cm] = splitCaught(ev.caughtIn);
    h += `<div class="exc"><b>${esc(ev.exType)}</b>: ${esc(ev.result)}<br>Thrown at: <span class="link" data-src="${esc(ev.exCls)}|${esc(ev.exMethod)}|${ev.exLine}">${esc(ev.thrownAt)}</span><br>Caught in: <span class="link" data-src="${esc(cc)}|${esc(cm)}|0">${esc(ev.caughtIn)}</span></div>`;
  } else {
    h += `<button class="mini" data-src="${esc(ev.cls)}|${esc(ev.method)}|0">Show Java source</button>`;
  }
  return h + '<div class="srcbox"></div>';
}
function evName(ev) { return ev.exType ? `${ev.exType} thrown at ${ev.thrownAt}` : `${ev.cls}.${ev.method}(${ev.args})`; }
function evRes(ev) { return ev.exType ? `caught in ${ev.caughtIn}` : ev.result; }
function stepsHtml(list) {
  if (!list.length) return '<div class="hint">Nothing yet. Add a passenger or step the simulation: every real method call will appear here.</div>';
  return list.map(ev => `<div class="step cat-${ev.category} ${openIds.has(ev.id) ? 'open' : ''} ${selEv === ev.id ? 'sel' : ''}" data-id="${ev.id}" style="margin-left:${ev.depth * 16}px">
    <div class="sh"><span class="badge">${ev.category}</span><span class="nm">${esc(evName(ev))}</span><span class="rs">→ ${esc(evRes(ev))}</span></div>
    <div class="body">${openIds.has(ev.id) ? stepBody(ev) : ''}</div></div>`).join('');
}
function wireSteps(box) {
  if (box._wired) return; box._wired = true;
  box.addEventListener('click', async e => {
    const link = e.target.closest('[data-src]');
    const step = e.target.closest('.step');
    if (link && step) {
      const [cls, method, line] = link.dataset.src.split('|');
      const d = await fetchSource(cls, method);
      const target = $('.srcbox', step);
      target.innerHTML = d && d.ok ? srcHtml(d, +line) : `<small>${esc(d ? d.error : 'server unreachable')}</small>`;
      return;
    }
    if (step && e.target.closest('.sh')) {
      const id = +step.dataset.id;
      if (openIds.has(id)) openIds.delete(id); else openIds.add(id);
      selEv = id;
      renderExec(); if (tab === 'flow') renderFlow();
    }
  });
}
function actionEvents(a) { return EV.filter(e => e.action === a); }
function latestAction() { return EV.length ? EV[EV.length - 1].action : null; }
function actionLabel(a) {
  const evs = actionEvents(a), head = evs[0];
  return head ? `${head.cls}.${head.method}(${head.args})` : '';
}
function renderExec() {
  const a = follow ? latestAction() : selAction;
  const box = $('#exec-steps');
  wireSteps(box);
  const evs = a == null ? [] : actionEvents(a);
  $('#exec-title').textContent = evs.length ? `action #${a} · ${actionLabel(a)}` : 'waiting for an action';
  box.innerHTML = stepsHtml(evs);
}

/* ---------------- timeline ---------------- */
function mmss(t) { return String(Math.floor(t / 60)).padStart(2, '0') + ':' + String(t % 60).padStart(2, '0'); }
function renderTimeline() {
  const f = $('#filters');
  if (!f.children.length) {
    f.innerHTML = CATS.map(c => `<button data-c="${c}" class="${c === filter ? 'on' : ''}">${CAT_LABEL[c]}</button>`).join('');
    f.onclick = e => { const b = e.target.closest('button'); if (!b) return; filter = b.dataset.c; $$('button', f).forEach(x => x.classList.toggle('on', x === b)); renderTimeline(); };
  }
  const list = EV.filter(e => filter === 'ALL' || e.category === filter).slice(-250).reverse();
  $('#tl').innerHTML = list.length ? list.map(e => `<div class="r cat-${e.category} ${selEv === e.id ? 'sel' : ''}" data-id="${e.id}" title="${esc(e.explain)}"><span class="t0">${mmss(e.tick)}</span><span><span class="badge">${e.category}</span></span><span><span class="mono">${esc(e.exType ? e.exType : e.cls + '.' + e.method)}</span> <span class="rs">${esc(e.exType ? e.result + ' · caught in ' + e.caughtIn : e.result)}</span></span></div>`).join('')
    : '<div class="hint">No events for this filter yet.</div>';
}
function selectEvent(id) {
  const ev = EV.find(e => e.id === id); if (!ev) return;
  selEv = id; selAction = ev.action; follow = false; $('#follow').checked = false; openIds.add(id);
  renderExec(); renderTimeline(); if (tab === 'flow') renderFlow();
  $('#exec-steps').querySelector(`[data-id="${id}"]`)?.scrollIntoView({ block: 'nearest' });
}

/* ---------------- elevator table + log ---------------- */
function renderElevStats() {
  $('#estats').innerHTML = '<tr><th>Car</th><th>Floor</th><th>Dir</th><th>State</th><th>Load</th><th>Stops</th><th>Util</th></tr>' +
    S.elevators.map((e, i) => { const p = S.stats.perElevator[i]; return `<tr><td style="color:${ECOL(i)}"><b>E${e.id}</b></td><td>${e.floor}</td><td>${e.direction}</td><td><span class="st ${e.state} badge" style="background:var(--${e.state})">${e.state}</span></td><td>${e.load}/${e.capacity}</td><td>${e.stops.join(', ') || '—'}</td><td>${p.utilisation}%</td></tr>`; }).join('');
}
async function refreshLog() {
  try { const t = await (await fetch('/api/log.txt')).text(); const l = t.trim().split('\n'); const box = $('#log'); box.textContent = l.slice(-150).join('\n'); box.scrollTop = box.scrollHeight; } catch (e) { setOnline(false); }
}

/* ---------------- Execution Flow tab ---------------- */
function renderFlow() {
  const acts = [...new Set(EV.map(e => e.action))].reverse().slice(0, 200);
  const cur = follow ? latestAction() : selAction;
  $('#flow-actions').innerHTML = acts.map(a => {
    const evs = actionEvents(a), err = evs.some(e => e.category === 'EXCEPTION');
    return `<div class="a ${a === cur ? 'sel' : ''} ${err ? 'err' : ''}" data-a="${a}"><b>#${a} ${esc(actionLabel(a))}</b><small>tick ${evs[0].tick} · ${evs.length} calls${err ? ' · exception' : ''}</small></div>`;
  }).join('') || '<div class="hint">No actions yet.</div>';
  const box = $('#flow-steps'); wireSteps(box);
  $('#flow-title').textContent = cur != null ? `action #${cur}` : '';
  box.innerHTML = stepsHtml(cur == null ? [] : actionEvents(cur));
}

/* ---------------- Configuration tab ---------------- */
async function loadConfig() {
  const d = await api('GET', 'config'); if (!d) return;
  $('#cfg-table').innerHTML = '<tr><th>Property</th><th>Value in file</th><th>Meaning</th><th>Read by</th><th>Used by</th><th>Effect of changing it</th></tr>' + d.props.map(p => `<tr><td class="mono"><b>${esc(p.key)}</b></td><td class="mono">${esc(p.value)}</td><td>${esc(p.meaning)}</td>
    <td>${p.readerClass ? `<span class="link" data-sc="${p.readerClass}|${p.readerMethod}">${p.readerClass}.${p.readerMethod}()</span>` : '—'}</td>
    <td>${p.usedClass ? `<span class="link" data-sc="${p.usedClass}|${p.usedMethod}">${p.usedClass}.${p.usedMethod}()</span>` : '—'}</td><td>${esc(p.effect)}</td></tr>`).join('');
  const r = d.running, num = (k, lo, hi) => `<label>${k}<input name="${k}" type="number" min="${lo}" max="${hi}" value="${r[k]}" required></label>`;
  $('#cfg-form').innerHTML = num('floors', 2, 30) + num('elevators', 1, 6) + num('capacity', 1, 5000) + num('tickMillis', 50, 5000) + num('seed', -2e9, 2e9) + num('port', 1024, 65535) +
    `<label>scheduler<select name="scheduler"><option ${r.scheduler === 'NEAREST' ? 'selected' : ''}>NEAREST</option><option ${r.scheduler === 'SCAN' ? 'selected' : ''}>SCAN</option></select></label><button class="primary">Apply</button>`;
}
async function applyConfig(e) {
  e.preventDefault();
  stopRun();
  const p = Object.fromEntries(new FormData(e.target));
  const d = await act('config', p, false);
  if (!d) return;
  if (d.ok) {
    speed = S.tickMillis; $('#c-speed').value = speed; $('#c-speed-v').textContent = speed + ' ms';
    $('#cfg-result').innerHTML = d.changed.length ? '<b>Applied and saved to config.txt.</b> Changed: ' + d.changed.map(esc).join('; ') + '. The simulation was reset with the new settings (<code>Main.loadConfig()</code> then <code>Main.reset()</code>).' : 'Nothing changed.';
    loadConfig();
  } else $('#cfg-result').textContent = 'Rejected by Java: ' + d.error;
}

/* ---------------- Algorithms tab ---------------- */
function liveDecision() { for (let i = EV.length - 1; i >= 0; i--) if (EV[i].candidates) return EV[i]; return null; }
function renderAlgoLive() {
  const ev = liveDecision();
  $('#algo-live').innerHTML = ev ? `<p><b>${esc(ev.cls)}.selectElevator(${esc(ev.args)})</b> at tick ${ev.tick} chose <b>${ev.chosen < 0 ? 'nobody' : 'E' + ev.chosen}</b>.<br>Java's own explanation: <i>${esc(ev.explain)}</i></p>${candTable(ev)}` : 'No hall call has been scheduled yet. Add a passenger and step one tick.';
}
function whatifTable(r) {
  return `<div><b>${esc(r.scheduler)}</b> → <b>${r.chosen < 0 ? 'nobody' : 'E' + r.chosen}</b><br><small>${esc(r.reason)}</small>${candTable({ candidates: r.candidates, chosen: r.chosen })}</div>`;
}
async function whatIf() {
  const d = await api('GET', 'whatif', { floor: $('#w-floor').value, dir: $('#w-dir').value });
  if (!d) return;
  $('#algo-whatif').innerHTML = d.ok ? d.results.map(whatifTable).join('') : `<div class="hint">${esc(d.error)}</div>`;
}
async function compare() {
  const d = await api('GET', 'compare', { count: $('#cmp-n').value });
  if (!d) return;
  if (!d.ok) { $('#algo-cmp').innerHTML = `<div class="hint">${esc(d.error)}</div>`; return; }
  const best = k => Math.min(...d.runs.map(r => r[k]));
  const cell = (r, k, f = v => v) => `<td class="${r[k] === best(k) ? 'mono' : ''}" style="${r[k] === best(k) ? 'color:#7ef0b3;font-weight:700' : ''}">${f(r[k])}</td>`;
  $('#algo-cmp').innerHTML = `<table class="tbl"><tr><th>Scheduler</th><th>Avg wait</th><th>Max wait</th><th>Avg trip</th><th>Ticks to finish</th><th>Completed</th></tr>` +
    d.runs.map(r => `<tr><td><b>${esc(r.scheduler)}</b></td>${cell(r, 'avgWait', v => v + ' s')}${cell(r, 'maxWait', v => v + ' s')}${cell(r, 'avgTrip', v => v + ' s')}${cell(r, 'ticks')}<td>${r.completed}/${d.count}</td></tr>`).join('') +
    `</table><p class="hint">Both runs used the same ${d.count} passengers (seed ${d.seed}), generated by <code>Main.addRandom()</code>. Green = best value.</p>`;
}
async function loadAlgoSource() {
  const box = $('#algo-src'); if (box.dataset.done) return;
  const parts = await Promise.all([fetchSource('NearestCarScheduler', 'selectElevator'), fetchSource('ScanScheduler', 'selectElevator')]);
  box.innerHTML = parts.map((d, i) => `<div><b>${['NearestCarScheduler', 'ScanScheduler'][i]}.selectElevator</b>${d && d.ok ? srcHtml(d, 0) : ''}</div>`).join('');
  box.dataset.done = 1;
}

/* ---------------- Class Architecture tab ---------------- */
const POS = { Main: [0, 0], WebServer: [1, 0], Explain: [2, 0], Trace: [3, 0], TraceEvent: [4, 0],
  Building: [0, 1], Elevator: [1, 1], Direction: [3, 1], State: [4, 1],
  Scheduler: [0, 2], Passenger: [1, 2], Request: [4, 2],
  NearestCarScheduler: [0, 3], ScanScheduler: [1, 3], ExternalRequest: [3, 3], InternalRequest: [4, 3],
  InvalidFloorException: [0, 4], OverloadException: [1, 4], ElevatorFaultException: [2, 4] };
const EDGE = { extends: ['#fbbf24', 'extends'], implements: ['#a78bfa', 'implements'], has: ['#38bdf8', 'has-a (field)'], creates: ['#34d399', 'creates (new)'] };
let CL = null, edgeOn = { extends: 1, implements: 1, has: 1, creates: 0 }, clsSel = 'Building';
async function loadClasses() {
  if (!CL) { CL = await api('GET', 'classes'); if (!CL) return; }
  const ef = $('#edge-filters');
  if (!ef.children.length) {
    ef.innerHTML = Object.keys(EDGE).map(k => `<button data-k="${k}" class="${edgeOn[k] ? 'on' : ''}" style="border-color:${EDGE[k][0]}">${EDGE[k][1]}</button>`).join('');
    ef.onclick = e => { const b = e.target.closest('button'); if (!b) return; edgeOn[b.dataset.k] ^= 1; b.classList.toggle('on'); drawDiagram(); };
  }
  drawDiagram(); showClass(clsSel);
  $('#oop').innerHTML = CL.concepts.map(c => `<div class="o"><h3>${esc(c.concept)}</h3><p>${esc(c.text)}</p>${c.classes.map(n => `<span class="chipc" data-cls="${n}">${n}</span>`).join('')}</div>`).join('');
}
function drawDiagram() {
  const W = 230, H = 150, d = $('#diagram'), box = {};
  CL.classes.forEach(c => { const p = POS[c.name] || [4, 2]; box[c.name] = { x: 12 + p[0] * W, y: 14 + p[1] * H, w: 200, h: 62 }; });
  const edges = [];
  CL.classes.forEach(c => {
    if (c.extends && box[c.extends]) edges.push(['extends', c.name, c.extends]);
    c.implements.forEach(i => box[i] && edges.push(['implements', c.name, i]));
    c.has.forEach(h => box[h] && edges.push(['has', c.name, h]));
    c.creates.forEach(h => box[h] && edges.push(['creates', c.name, h]));
  });
  const mid = b => [b.x + b.w / 2, b.y + b.h / 2];
  const clip = (a, b) => { const [ax, ay] = mid(a), [bx, by] = mid(b), dx = bx - ax, dy = by - ay; const t = Math.min(dx ? (b.w / 2) / Math.abs(dx) : 9, dy ? (b.h / 2) / Math.abs(dy) : 9); return [bx - dx * t, by - dy * t]; };
  let svg = `<svg width="1150" height="760"><defs>` + Object.entries(EDGE).map(([k, v]) => `<marker id="m-${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0,0 L10,5 L0,10 ${k === 'extends' || k === 'implements' ? 'z' : ''}" fill="${k === 'extends' || k === 'implements' ? '#0b1220' : 'none'}" stroke="${v[0]}" stroke-width="1.5"/></marker>`).join('') + '</defs>';
  edges.filter(e => edgeOn[e[0]]).forEach(([k, from, to]) => {
    const [x1, y1] = mid(box[from]), [x2, y2] = clip(box[from], box[to]);
    const on = from === clsSel || to === clsSel;
    svg += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${EDGE[k][0]}" stroke-width="${on ? 2.4 : 1.2}" opacity="${on ? 1 : .45}" ${k === 'implements' ? 'stroke-dasharray="6 4"' : k === 'creates' ? 'stroke-dasharray="2 3"' : ''} marker-end="url(#m-${k})"/>`;
  });
  d.innerHTML = svg + '</svg>' + CL.classes.map(c => {
    const b = box[c.name];
    return `<div class="node ${c.kind === 'interface' ? 'interface' : ''} ${/Exception/.test(c.name) ? 'exc' : ''} ${c.name === clsSel ? 'sel' : ''}" data-cls="${c.name}" style="left:${b.x}px;top:${b.y}px"><b>${c.name}</b><small>${c.kind}${c.extends ? ' extends ' + c.extends : ''}${c.implements.length ? ' implements ' + c.implements.join(', ') : ''}</small><small>${c.file}</small></div>`;
  }).join('');
}
function chips(arr) { return arr.length ? arr.map(n => `<span class="chipc" data-cls="${n}">${n}</span>`).join('') : '<small>none</small>'; }
function showClass(name) {
  const c = CL.classes.find(x => x.name === name); if (!c) return;
  clsSel = name; drawDiagram();
  const oops = CL.concepts.filter(o => o.classes.includes(name)).map(o => o.concept);
  $('#cls-detail').className = 'panel cd';
  $('#cls-detail').innerHTML = `<div class="ph"><h3>${c.name}</h3><span class="tag">${c.kind} · ${c.file}</span><button class="mini" data-sc="${c.name}|">View full source</button></div>
    <p>${esc(c.role)}</p>
    <div class="cols"><div><b>Fields</b><ul>${c.fields.map(f => `<li>${esc(f.mod)} ${esc(f.type)} ${esc(f.name)}</li>`).join('') || '<li>none</li>'}</ul></div>
    <div><b>Methods</b><ul>${c.methods.map(m => `<li><span class="link" data-sc="${c.name}|${esc(m.sig.split('(')[0])}">${esc(m.mod)} ${esc(m.ret)} ${esc(m.sig)}</span></li>`).join('')}</ul></div></div>
    <p><b>Extends:</b> ${c.extends ? chips([c.extends]) : '<small>none</small>'} <b>Implements:</b> ${chips(c.implements)}</p>
    <p><b>Has fields of:</b> ${chips(c.has)}</p><p><b>Creates objects of:</b> ${chips(c.creates)}</p>
    <p><b>Mentions in its code:</b> ${chips(c.uses)}</p><p><b>Used by:</b> ${chips(c.usedBy)}</p>
    <p><b>OOP concepts here:</b> ${oops.length ? oops.map(esc).join(', ') : '<small>none highlighted</small>'}</p>`;
}

/* ---------------- Reports tab ---------------- */
async function loadReport() {
  const d = await api('GET', 'report'); if (!d) return;
  const s = d.stats, tot = a => s.perElevator.reduce((x, e) => x + e[a], 0);
  const card = (t, v) => `<div class="card"><small>${t}</small><b>${v}</b></div>`;
  $('#rep-cards').innerHTML = card('Total passengers', s.total) + card('Completed trips', s.completed) + card('Not finished', s.unfinished) + card('Average wait', s.avgWait + ' s') + card('Maximum wait', s.maxWait + ' s') +
    card('Average trip time', s.avgTrip + ' s') + card('Floors travelled', tot('floorsTravelled')) + card('Elapsed time', d.clock + ' s') + card('Scheduler', d.scheduler.replace('Scheduler', ''));
  $('#rep-elev').innerHTML = '<tr><th>Car</th><th>Utilisation</th><th>Busy ticks</th><th>Floors travelled</th><th>Passengers served</th></tr>' + s.perElevator.map(e => `<tr><td style="color:${ECOL(e.id)}"><b>E${e.id}</b></td><td><div class="bar"><i style="width:${e.utilisation}%"></i></div>${e.utilisation}%</td><td>${e.busyTicks}</td><td>${e.floorsTravelled}</td><td>${e.served}</td></tr>`).join('');
  $('#rep-cfg').innerHTML = Object.entries(d.config).map(([k, v]) => `<tr><td class="mono">${k}</td><td class="mono">${esc(v)}</td></tr>`).join('');
  $('#rep-text').textContent = d.text;
}

/* ---------------- source modal ---------------- */
async function openSource(cls, method) {
  const d = await fetchSource(cls, method);
  $('#m-title').textContent = cls + (method ? '.' + method + '()' : '');
  $('#m-body').innerHTML = d && d.ok ? srcHtml(d, 0) : `<div class="hint">${esc(d ? d.error : 'server unreachable')}</div>`;
  $('#modal').hidden = false;
}

/* ---------------- running + controls ---------------- */
function toast(msg, err) {
  const t = document.createElement('div'); t.className = 'toast' + (err ? ' err' : ''); t.textContent = msg;
  $('#toasts').appendChild(t); setTimeout(() => t.remove(), err ? 6000 : 3500);
}
function stopRun() { running = false; if (S) renderHeader(); refreshLog(); }
async function runLoop() {
  if (S.done) { toast('Nothing to run: add a passenger first.'); return; }
  running = true; renderHeader();
  let n = 0;
  while (running) {
    const t0 = Date.now();
    const d = await act('step', { n: 1 });
    if (!d || !d.ok || S.done || ++n >= S.maxTicks) { if (n >= S.maxTicks) toast('Stopped after ' + S.maxTicks + ' ticks (same limit as the original project).'); break; }
    await sleep(Math.max(0, speed - (Date.now() - t0)));
  }
  stopRun();
}
const addPassenger = (s, d, w) => act('passenger', { source: s, dest: d, weight: w });

function bind() {
  $('#tabs').onclick = e => { const b = e.target.closest('button'); if (b) showTab(b.dataset.tab); };
  $('#b-add').onclick = () => addPassenger($('#c-src').value, $('#c-dst').value, $('#c-w').value);
  $('#b-demo').onclick = () => { $('#c-src').value = 2; $('#c-dst').value = 8; $('#c-w').value = 70; addPassenger(2, 8, 70); };
  $('#b-badfloor').onclick = () => addPassenger(99, 3, 70);
  $('#b-overload').onclick = () => addPassenger(1, 3, S.capacity + 100);
  $('#b-step').onclick = () => { if (!running) act('step', { n: 1 }).then(refreshLog); };
  $('#b-run').onclick = () => { if (running) stopRun(); else runLoop(); };
  $('#b-rand').onclick = () => act('random', { count: $('#c-rand').value });
  $('#b-reset').onclick = () => { stopRun(); act('reset').then(refreshLog); };
  $('#b-break').onclick = () => act('fault', { id: $('#c-elev').value, action: 'break' });
  $('#b-repair').onclick = () => act('fault', { id: $('#c-elev').value, action: 'repair' });
  $('#b-stopcmd').onclick = () => act('stop', { id: $('#c-elev').value, floor: $('#c-stopf').value });
  $('#k-sched').onchange = e => act('scheduler', { name: e.target.value });
  $('#c-speed').oninput = e => { speed = +e.target.value; $('#c-speed-v').textContent = speed + ' ms'; if (S) $('#bld').style.setProperty('--move', Math.round(speed * 0.85)); };
  $('#follow').onchange = e => { follow = e.target.checked; renderExec(); };
  $('#b-log').onclick = refreshLog;
  $('#b-report').onclick = loadReport;
  $('#b-whatif').onclick = whatIf;
  $('#b-compare').onclick = compare;
  $('#cfg-form').onsubmit = applyConfig;
  $('#tl').onclick = e => { const r = e.target.closest('.r'); if (r) selectEvent(+r.dataset.id); };
  $('#flow-actions').onclick = e => { const a = e.target.closest('.a'); if (a) { selAction = +a.dataset.a; follow = false; $('#follow').checked = false; renderFlow(); renderExec(); } };
  $('#m-close').onclick = () => $('#modal').hidden = true;
  $('#modal').onclick = e => { if (e.target.id === 'modal') $('#modal').hidden = true; };
  document.addEventListener('click', e => {
    const sc = e.target.closest('[data-sc]'); if (sc) { const [c, m] = sc.dataset.sc.split('|'); openSource(c, m); return; }
    const ch = e.target.closest('[data-cls]'); if (ch && CL && tab === 'classes') showClass(ch.dataset.cls);
  });
  addEventListener('resize', fixSticky);
}
function fixSticky() { $('#tabs').style.top = $('.top').offsetHeight + 'px'; }
function showTab(t) {
  tab = t;
  $$('.tab').forEach(s => s.classList.toggle('on', s.id === 'tab-' + t));
  $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === t));
  if (!S) return;
  if (t === 'flow') renderFlow();
  if (t === 'config') loadConfig();
  if (t === 'algo') { renderAlgoLive(); loadAlgoSource(); }
  if (t === 'classes') loadClasses();
  if (t === 'reports') loadReport();
  if (t === 'sim') renderBuilding([]);
}

/* Cheap heartbeat: detects a stopped server, or changes made by another browser tab. */
setInterval(async () => {
  if (running) return;
  const d = await api('GET', 'ping');
  if (d && (d.epoch !== epoch || d.lastEventId !== lastEventSeen)) loadAll();
}, 4000);

(async function init() {
  bind(); fixSticky();
  await loadAll();
  if (S) { speed = S.tickMillis; $('#c-speed').value = speed; $('#c-speed-v').textContent = speed + ' ms'; renderBuilding([]); refreshLog(); }
})();
