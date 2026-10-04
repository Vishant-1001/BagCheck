/* ==========================================================================
 * Baggage Journey Integrity Checker - dashboard logic
 *
 * This file contains NO validation logic and NO graph algorithms. All of that
 * lives in the Java engine (Graph, CustomQueue, CustomHashTable,
 * BaggageValidator); the browser only sends scan events and renders the JSON
 * the engine returns. The graph picture is drawn from the nodes and edges the
 * engine reports, so there is exactly one graph in the system.
 * ========================================================================== */

const API = '/api';
const SVG_NS = 'http://www.w3.org/2000/svg';
const NODE_W = 150;
const NODE_H = 62;
const LOG_LIMIT = 60;

const state = {
  graph: null,
  queue: null,
  stats: null,
  bags: [],
  demos: [],
  referenceProcess: [],
  referenceProcessName: '',
  selected: null,
  selectedCheckpoint: null,
  playing: false,
  activeDemo: null
};

let pathByKey = new Map();   // "FROM->TO" -> SVG path element of the current drawing

// --------------------------------------------------------------------------
// API helpers
// --------------------------------------------------------------------------

async function request(path, body) {
  const options = body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  };
  let response;
  try {
    response = await fetch(API + path, options);
  } catch (networkError) {
    throw new Error('Cannot reach the Java engine. Start it with: ./run.sh serve');
  }
  const data = await response.json().catch(() => ({ ok: false, error: 'The engine returned invalid JSON' }));
  if (!data.ok) {
    throw new Error(data.error || 'Request failed');
  }
  return data;
}

function applyState(next) {
  if (!next) return;
  state.graph = next.graph;
  state.queue = next.queue;
  state.stats = next.stats;
  state.bags = next.bags || [];
  state.demos = next.demos || [];
  state.referenceProcess = next.referenceProcess || [];
  state.referenceProcessName = next.referenceProcessName || '';
  if (!state.selected || !state.bags.some(bag => bag.bagId === state.selected)) {
    state.selected = state.bags.length ? state.bags[0].bagId : null;
  }
}

function applyAndRender(next) {
  applyState(next);
  render();
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function refresh() {
  const data = await request('/state');
  applyAndRender(data);
}

// --------------------------------------------------------------------------
// Actions
// --------------------------------------------------------------------------

async function actionEnqueue() {
  const bagId = document.getElementById('bag-input').value.trim();
  if (!bagId) return feedback('Enter a bag ID first.');
  if (!state.selectedCheckpoint) return feedback('Click a checkpoint chip first.');
  try {
    const data = await request('/scan', { bagId: bagId, checkpoint: state.selectedCheckpoint });
    applyAndRender(data.state);
    feedback('Enqueued ' + bagId + ' \u2192 ' + state.selectedCheckpoint + '. The event waits in the FIFO queue.');
  } catch (error) {
    feedback(error.message);
  }
}

async function processNext(animate) {
  const data = await request('/process', { all: false });
  if (!data.results || data.results.length === 0) {
    applyAndRender(data.state);
    return null;
  }
  const result = data.results[0];
  state.selected = result.bagId;
  if (animate) {
    await animateTransition(result);
  }
  applyAndRender(data.state);
  return result;
}

async function actionStep() {
  if (state.playing) return;
  try {
    const result = await processNext(true);
    if (!result) feedback('The queue is empty - enqueue a scan or load a demo.');
  } catch (error) {
    feedback(error.message);
  }
}

async function actionProcessAll() {
  if (state.playing) return;
  try {
    const data = await request('/process', { all: true });
    const count = data.results ? data.results.length : 0;
    if (count && data.results[count - 1]) {
      state.selected = data.results[count - 1].bagId;
    }
    applyAndRender(data.state);
    feedback(count ? 'Processed ' + count + ' event(s) in FIFO order.' : 'The queue is already empty.');
  } catch (error) {
    feedback(error.message);
  }
}

async function actionSample() {
  if (state.playing) return;
  try {
    const data = await request('/sample', {});
    applyAndRender(data.state);
    feedback('Loaded ' + data.queued + ' sample events on the standard profile. Validating them now...');
    state.playing = true;
    let result;
    while ((result = await processNext(true)) !== null) {
      await sleep(160);
    }
    state.playing = false;
    render();
    feedback('Sample stream validated: ' + (state.stats.bags) + ' bags, '
      + state.stats.validTransitions + ' valid transitions, '
      + state.stats.duplicateScans + ' duplicate scans, '
      + state.stats.invalidTransitions + ' invalid transitions.');
  } catch (error) {
    state.playing = false;
    feedback(error.message);
  }
}

async function actionDemo(demoId) {
  if (state.playing) return;
  try {
    state.playing = true;
    state.activeDemo = demoId;
    render();
    const start = await request('/demo', { id: demoId });
    state.selected = start.demo.bagId;
    applyAndRender(start.state);
    feedback(start.demo.explanation || '');
    await sleep(320);

    let result;
    while ((result = await processNext(true)) !== null) {
      await sleep(220);
    }
    feedback('Expected: ' + (start.demo.expected || '') + '   |   Observed: '
      + (state.bags[0] ? state.bags[0].journeyStatus : '-'));
  } catch (error) {
    feedback(error.message);
  }
  state.playing = false;
  state.activeDemo = null;
  render();
}

async function actionReset() {
  if (state.playing) return;
  try {
    const data = await request('/reset', {});
    applyAndRender(data.state);
    feedback('Queue and journey state cleared.');
  } catch (error) {
    feedback(error.message);
  }
}

async function actionProfile(profile) {
  if (state.playing || !profile) return;
  try {
    const data = await request('/profile', { profile: profile });
    applyAndRender(data.state);
    feedback(data.message);
  } catch (error) {
    feedback(error.message);
  }
}

function feedback(message) {
  document.getElementById('sim-feedback').textContent = message || '';
}

// --------------------------------------------------------------------------
// Animation helpers (pure presentation)
// --------------------------------------------------------------------------

function nodeById(id) {
  if (!state.graph) return null;
  return state.graph.nodes.find(node => node.id === id) || null;
}

async function animateTransition(result) {
  const from = result.fromCheckpoint;
  const to = result.checkpoint;
  if (!from) return;                                   // first scan of the bag
  if (from === to) return pulseNode(to);               // duplicate scan
  if (!result.accepted) return flashRejected(from, to);
  return moveDot(from, to);
}

async function moveDot(fromId, toId) {
  const path = pathByKey.get(fromId + '->' + toId);
  if (!path) return;
  const overlay = document.getElementById('graph-overlay');
  if (!overlay) return;
  const length = path.getTotalLength();
  const dot = document.createElementNS(SVG_NS, 'circle');
  dot.setAttribute('r', '7');
  dot.setAttribute('class', 'moving-dot');
  overlay.appendChild(dot);
  const duration = 380;
  const start = performance.now();
  await new Promise(resolve => {
    function frame(now) {
      const t = Math.min(1, (now - start) / duration);
      const point = path.getPointAtLength(length * t);
      dot.setAttribute('cx', point.x);
      dot.setAttribute('cy', point.y);
      if (t < 1) requestAnimationFrame(frame); else resolve();
    }
    requestAnimationFrame(frame);
  });
  dot.remove();
}

async function flashRejected(fromId, toId) {
  const overlay = document.getElementById('graph-overlay');
  const from = nodeById(fromId);
  const to = nodeById(toId);
  if (!overlay || !from || !to) return;
  const a = anchors(from, to);
  const line = document.createElementNS(SVG_NS, 'path');
  line.setAttribute('d', edgePath(from, to, a));
  line.setAttribute('class', 'edge rejected');
  line.setAttribute('marker-end', 'url(#arrow-rejected)');
  overlay.appendChild(line);
  const cross = document.createElementNS(SVG_NS, 'text');
  cross.setAttribute('x', String((a.x1 + a.x2) / 2));
  cross.setAttribute('y', String((a.y1 + a.y2) / 2 - 10));
  cross.setAttribute('class', 'edge-label bad');
  cross.setAttribute('text-anchor', 'middle');
  cross.textContent = '\u2715 rejected';
  overlay.appendChild(cross);
  await sleep(900);
  line.remove();
  cross.remove();
}

async function pulseNode(nodeId) {
  const node = document.getElementById('node-' + nodeId);
  if (!node) return;
  node.classList.add('rejected-flash');
  await sleep(700);
  node.classList.remove('rejected-flash');
}

// --------------------------------------------------------------------------
// Rendering
// --------------------------------------------------------------------------

function selectedBag() {
  return state.bags.find(bag => bag.bagId === state.selected) || null;
}

function lastResultOf(bag) {
  if (!bag || !bag.results || bag.results.length === 0) return null;
  return bag.results[bag.results.length - 1];
}

function render() {
  renderProfileSelect();
  renderCheckpoints();
  renderBagSuggestions();
  renderBagSelect();
  renderDemos();
  renderQueue();
  renderStatus();
  renderJourney();
  renderGraph();
  renderStats();
  renderReference();
  renderLog();
}

function renderProfileSelect() {
  const select = document.getElementById('profile-select');
  if (!state.graph) return;
  const profiles = state.graph.profiles || [];
  const wanted = state.graph.profile;
  const options = profiles.map(profile =>
    '<option value="' + profile.id + '"' + (profile.id === wanted ? ' selected' : '') + '>'
    + escapeHtml(profile.name) + ' \u00b7 ' + profile.edgeCount + ' edges</option>').join('');
  if (select.dataset.signature !== options) {
    select.innerHTML = options;
    select.dataset.signature = options;
  } else {
    select.value = wanted;
  }
}

function renderCheckpoints() {
  const container = document.getElementById('checkpoint-buttons');
  if (!state.graph) return;
  container.innerHTML = '';
  state.graph.nodes.forEach(node => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'chip' + (state.selectedCheckpoint === node.id ? ' active' : '');
    button.textContent = node.id;
    button.title = node.description;
    button.addEventListener('click', () => {
      state.selectedCheckpoint = node.id;
      renderCheckpoints();
    });
    container.appendChild(button);
  });
}

function renderBagSuggestions() {
  const list = document.getElementById('bag-suggestions');
  list.innerHTML = state.bags
    .map(bag => '<option value="' + escapeHtml(bag.bagId) + '">' + escapeHtml(bag.journeyStatus) + '</option>')
    .join('');
}

function renderBagSelect() {
  const select = document.getElementById('bag-select');
  if (!state.bags.length) {
    select.innerHTML = '<option>no bags yet</option>';
    select.disabled = true;
    return;
  }
  select.disabled = false;
  select.innerHTML = state.bags.map(bag =>
    '<option value="' + escapeHtml(bag.bagId) + '"' + (bag.bagId === state.selected ? ' selected' : '') + '>'
    + escapeHtml(bag.bagId) + ' \u2014 ' + escapeHtml(bag.journeyStatus) + '</option>').join('');
}

function renderDemos() {
  const container = document.getElementById('demo-list');
  container.innerHTML = '';
  state.demos.forEach(demo => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'demo' + (state.activeDemo === demo.id ? ' running' : '');
    button.innerHTML = '<strong>' + escapeHtml(demo.title) + '</strong>'
      + '<span>' + escapeHtml(demo.scans.join(' \u2192 ')) + '</span>'
      + '<span>expected: ' + escapeHtml(demo.expected) + '</span>';
    button.disabled = state.playing;
    button.addEventListener('click', () => actionDemo(demo.id));
    container.appendChild(button);
  });
}

function renderQueue() {
  const container = document.getElementById('queue-list');
  const sizePill = document.getElementById('queue-size');
  const events = state.queue ? state.queue.events : [];
  sizePill.textContent = String(state.queue ? state.queue.size : 0);
  if (!events.length) {
    container.innerHTML = '<p class="empty">Queue is empty.</p>';
    return;
  }
  container.innerHTML = events.map((event, index) =>
    '<div class="queue-item' + (index === 0 ? ' front' : '') + '">'
    + '<span class="seq">#' + event.sequence + '</span>'
    + '<span>' + escapeHtml(event.bagId) + '</span>'
    + '<span class="arrow">\u2192</span>'
    + '<span>' + escapeHtml(event.checkpoint) + '</span>'
    + (index === 0 ? '<span class="seq" style="margin-left:auto">next</span>' : '')
    + '</div>').join('');
}

function renderStatus() {
  const card = document.getElementById('status-card');
  const bag = selectedBag();
  const result = lastResultOf(bag);

  if (!bag || !result) {
    card.className = 'card status-card';
    card.innerHTML = '<div class="status-head"><div class="status-title">WAITING FOR SCANS</div>'
      + '<div class="status-tag">queue: ' + (state.queue ? state.queue.size : 0) + '</div></div>'
      + '<p class="status-detail">Load a demo scenario or enqueue scans, then press <strong>Process next</strong>. '
      + 'Each event is validated against the graph by the Java engine.</p>';
    return;
  }

  card.className = 'card status-card ' + statusClass(result.journeyStatus);
  const reachable = result.arrivalReachable;
  const remaining = (result.remainingPath || []).join(' \u2192 ');
  const skipped = (result.skippedCheckpoints || []).join(', ');

  let html = '<div class="status-head">'
    + '<div class="status-title">' + escapeHtml(result.journeyStatus) + '</div>'
    + (result.anomalyType ? '<div class="status-tag">' + escapeHtml(result.anomalyType) + '</div>' : '')
    + '<div class="status-tag">' + escapeHtml(bag.bagId) + ' \u00b7 event #' + result.sequence + ' \u00b7 ' + escapeHtml(result.eventResult) + '</div>'
    + '</div>';

  html += '<p class="status-detail">' + escapeHtml(result.detail) + '</p>';

  if (result.advisory) {
    html += '<div class="status-advisory">\u26a0 ' + escapeHtml(result.advisory) + '</div>';
  }

  html += '<div class="status-meta">'
    + meta('Current checkpoint', result.currentCheckpoint || '\u2014')
    + meta('ARRIVAL reachable', reachable ? 'yes' : 'no')
    + meta('Remaining BFS route', remaining || (reachable ? 'already there' : 'none exists'))
    + meta('Scans / anomalies', bag.scanCount + ' / ' + bag.anomalyCount)
    + (skipped ? meta('Skipped (reference)', skipped) : '')
    + '</div>';

  card.innerHTML = html;
}

function meta(label, value) {
  return '<div class="meta"><span>' + escapeHtml(label) + '</span><strong>' + escapeHtml(String(value)) + '</strong></div>';
}

function statusClass(status) {
  if (status === 'VALID JOURNEY') return 'valid';
  if (status === 'ANOMALY DETECTED') return 'anomaly';
  if (status === 'INCOMPLETE JOURNEY') return 'incomplete';
  if (status === 'ARRIVAL UNREACHABLE') return 'unreachable';
  return '';
}

function renderJourney() {
  const container = document.getElementById('journey');
  const bag = selectedBag();
  if (!bag || !bag.acceptedPath || bag.acceptedPath.length === 0) {
    container.innerHTML = '<p class="empty">No accepted checkpoints yet for this bag.</p>';
    return;
  }
  let html = '';
  bag.acceptedPath.forEach((checkpoint, index) => {
    if (index > 0) html += '<span class="journey-arrow">\u2192</span>';
    const classes = ['journey-chip'];
    if (checkpoint === bag.currentCheckpoint) classes.push('current');
    if (!state.graph.finalCheckpoint || checkpoint === state.graph.finalCheckpoint) classes.push('final');
    html += '<span class="' + classes.join(' ') + '">' + escapeHtml(checkpoint)
      + '<small>step ' + (index + 1) + '</small></span>';
  });
  container.innerHTML = html + findings(bag);
}

function findings(bag) {
  const rows = [];
  (bag.anomalies || []).forEach(anomaly => {
    const kind = anomaly.type === 'DUPLICATE CHECKPOINT' ? 'dup'
      : anomaly.type === 'ARRIVAL UNREACHABLE' ? 'unreachable'
      : anomaly.type === 'UNKNOWN CHECKPOINT' ? 'unknown' : 'invalid';
    rows.push('<div class="finding ' + kind + '"><strong>' + escapeHtml(anomaly.type) + '</strong> '
      + 'at <code>' + escapeHtml(anomaly.checkpoint) + '</code> (event #' + anomaly.sequence + ') \u2014 '
      + escapeHtml(anomaly.detail) + '</div>');
  });
  (bag.advisories || []).forEach(advisory => {
    rows.push('<div class="finding advisory"><strong>ADVISORY</strong> ' + escapeHtml(advisory) + '</div>');
  });
  return rows.length ? '<div class="findings">' + rows.join('') + '</div>' : '';
}

// --------------------------------------------------------------------------
// Graph drawing (SVG, no library)
// --------------------------------------------------------------------------

function renderGraph() {
  const svg = document.getElementById('graph');
  if (!state.graph) return;
  svg.innerHTML = '';
  svg.setAttribute('viewBox', '0 0 1040 340');

  const bag = selectedBag();
  const result = lastResultOf(bag);
  const graph = state.graph;

  const traversed = new Set(pairs(bag ? bag.acceptedPath : []));
  const remaining = new Set(pairs(result && result.arrivalReachable ? result.remainingPath : []));
  const rejected = result && !result.accepted
    ? (result.fromCheckpoint ? result.fromCheckpoint + '->' + result.checkpoint : null)
    : null;
  const visited = new Set(bag ? bag.acceptedPath : []);

  const defs = document.createElementNS(SVG_NS, 'defs');
  defs.innerHTML =
      '<marker id="arrow-default" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#3c4a6b"/></marker>'
    + '<marker id="arrow-traversed" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#34d399"/></marker>'
    + '<marker id="arrow-remaining" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#22d3ee"/></marker>'
    + '<marker id="arrow-rejected" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#fb7185"/></marker>';
  svg.appendChild(defs);

  const edgesGroup = document.createElementNS(SVG_NS, 'g');
  const nodesGroup = document.createElementNS(SVG_NS, 'g');
  const overlay = document.createElementNS(SVG_NS, 'g');
  overlay.setAttribute('id', 'graph-overlay');
  svg.appendChild(edgesGroup);
  svg.appendChild(nodesGroup);
  svg.appendChild(overlay);

  pathByKey = new Map();

  graph.edges.forEach(edge => {
    const from = nodeById(edge.from);
    const to = nodeById(edge.to);
    if (!from || !to) return;
    const key = edge.from + '->' + edge.to;
    const anchor = anchors(from, to);
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', edgePath(from, to, anchor));
    path.setAttribute('id', 'edge-' + edge.from + '-' + edge.to);

    // Only one style may win: a traversed edge must not keep the dashed
    // "reference process" look, otherwise the picture becomes ambiguous.
    let cls = 'edge';
    let marker = 'arrow-default';
    if (rejected === key) { cls += ' rejected'; marker = 'arrow-rejected'; }
    else if (traversed.has(key)) { cls += ' traversed'; marker = 'arrow-traversed'; }
    else if (remaining.has(key)) { cls += ' remaining'; marker = 'arrow-remaining'; }
    else if (edge.onReferenceProcess) { cls += ' reference'; }
    path.setAttribute('class', cls);
    path.setAttribute('marker-end', 'url(#' + marker + ')');
    edgesGroup.appendChild(path);
    pathByKey.set(key, path);
  });

  graph.nodes.forEach(node => {
    const group = document.createElementNS(SVG_NS, 'g');
    const classes = ['node'];
    if (node.isFinal) classes.push('final');
    if (!node.reachesFinal) classes.push('stuck');
    if (visited.has(node.id)) classes.push('visited');
    if (bag && bag.currentCheckpoint === node.id) classes.push('current');
    group.setAttribute('class', classes.join(' '));
    group.setAttribute('id', 'node-' + node.id);

    const rect = document.createElementNS(SVG_NS, 'rect');
    rect.setAttribute('x', String(node.x - NODE_W / 2));
    rect.setAttribute('y', String(node.y - NODE_H / 2));
    rect.setAttribute('width', String(NODE_W));
    rect.setAttribute('height', String(NODE_H));
    rect.setAttribute('rx', '12');
    group.appendChild(rect);

    const label = document.createElementNS(SVG_NS, 'text');
    label.setAttribute('x', String(node.x));
    label.setAttribute('y', String(node.y - 3));
    label.setAttribute('class', 'label');
    label.textContent = node.label;
    group.appendChild(label);

    const id = document.createElementNS(SVG_NS, 'text');
    id.setAttribute('x', String(node.x));
    id.setAttribute('y', String(node.y + 14));
    id.setAttribute('class', 'id');
    id.textContent = node.id;
    group.appendChild(id);

    const title = document.createElementNS(SVG_NS, 'title');
    title.textContent = node.description || node.id;
    group.appendChild(title);

    nodesGroup.appendChild(group);
  });

  // The last rejected attempt stays visible until the next event, so an invalid
  // transition or a duplicate scan can be pointed at during the demo.
  if (result && !result.accepted && result.fromCheckpoint) {
    const from = nodeById(result.fromCheckpoint);
    const to = nodeById(result.checkpoint);
    if (from && to && result.fromCheckpoint === result.checkpoint) {
      const ring = document.createElementNS(SVG_NS, 'circle');
      ring.setAttribute('cx', String(from.x));
      ring.setAttribute('cy', String(from.y));
      ring.setAttribute('r', '48');
      ring.setAttribute('class', 'rejected-ring');
      overlay.appendChild(ring);
    } else if (from && to) {
      const anchor = anchors(from, to);
      const line = document.createElementNS(SVG_NS, 'path');
      line.setAttribute('d', edgePath(from, to, anchor));
      line.setAttribute('class', 'edge rejected');
      line.setAttribute('marker-end', 'url(#arrow-rejected)');
      overlay.appendChild(line);
      const mid = line.getPointAtLength(line.getTotalLength() / 2);
      const cross = document.createElementNS(SVG_NS, 'text');
      cross.setAttribute('x', String(mid.x));
      cross.setAttribute('y', String(mid.y - 8));
      cross.setAttribute('class', 'edge-label bad');
      cross.setAttribute('text-anchor', 'middle');
      cross.textContent = '\u2715 rejected';
      overlay.appendChild(cross);
    }
  }

  const meta = document.getElementById('graph-meta');
  meta.textContent = graph.profileName + ' \u00b7 ' + graph.nodes.length + ' nodes \u00b7 '
    + graph.edges.length + ' edges \u00b7 final: ' + graph.finalCheckpoint;
}

/** Consecutive checkpoint pairs of a path, used to highlight edges. */
function pairs(path) {
  const out = [];
  for (let i = 0; i + 1 < path.length; i++) {
    out.push(path[i] + '->' + path[i + 1]);
  }
  return out;
}

function anchors(from, to) {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  if (Math.abs(dx) >= Math.abs(dy)) {
    const sign = dx >= 0 ? 1 : -1;
    return { x1: from.x + sign * NODE_W / 2, y1: from.y, x2: to.x - sign * NODE_W / 2, y2: to.y };
  }
  const sign = dy >= 0 ? 1 : -1;
  return { x1: from.x, y1: from.y + sign * NODE_H / 2, x2: to.x, y2: to.y - sign * NODE_H / 2 };
}

/**
 * Straight line when nothing is in the way, otherwise a curve that bows around
 * the checkpoint in between (e.g. LOADING -> ARRIVAL skips past TRANSFER).
 */
function edgePath(from, to, anchor) {
  if (Math.abs(from.y - to.y) < 6 && skipsOverANode(from, to)) {
    const midX = (anchor.x1 + anchor.x2) / 2;
    return 'M ' + anchor.x1 + ' ' + anchor.y1 + ' Q ' + midX + ' ' + (from.y + 160)
      + ' ' + anchor.x2 + ' ' + anchor.y2;
  }
  return 'M ' + anchor.x1 + ' ' + anchor.y1 + ' L ' + anchor.x2 + ' ' + anchor.y2;
}

function skipsOverANode(from, to) {
  const left = Math.min(from.x, to.x);
  const right = Math.max(from.x, to.x);
  return state.graph.nodes.some(node =>
    node.id !== from.id && node.id !== to.id
    && node.x > left + NODE_W / 2 && node.x < right - NODE_W / 2
    && Math.abs(node.y - from.y) < 40);
}

// --------------------------------------------------------------------------
// Stats, reference process, event log
// --------------------------------------------------------------------------

function renderStats() {
  const stats = state.stats;
  if (!stats) return;
  const tiles = [
    tile('Bags tracked', stats.bags, ''),
    tile('Scans processed', stats.scansProcessed, ''),
    tile('Events queued', stats.queued, stats.queued ? 'warn' : ''),
    tile('Valid transitions', stats.validTransitions, 'ok'),
    tile('Duplicate scans', stats.duplicateScans, stats.duplicateScans ? 'bad' : 'ok'),
    tile('Invalid transitions', stats.invalidTransitions, stats.invalidTransitions ? 'bad' : 'ok'),
    tile('Unknown checkpoints', stats.unknownCheckpoints, stats.unknownCheckpoints ? 'bad' : ''),
    tile('Valid journeys', stats.validJourneys, 'ok'),
    tile('Anomalies', stats.anomalyJourneys, stats.anomalyJourneys ? 'bad' : ''),
    tile('Incomplete', stats.incompleteJourneys, stats.incompleteJourneys ? 'warn' : ''),
    tile('Arrival unreachable', stats.unreachableJourneys, stats.unreachableJourneys ? 'info' : ''),
    tile('Last batch', stats.lastBatchSize ? (stats.lastBatchSize + ' in ' + stats.lastBatchMillis + ' ms') : '\u2014', '')
  ];
  document.getElementById('stats').innerHTML = tiles.join('');

  document.getElementById('hash-stats').innerHTML = [
    tile('capacity', stats.hashCapacity, ''),
    tile('stored keys', stats.hashSize, ''),
    tile('load factor', stats.hashLoadFactor, ''),
    tile('collisions', stats.hashCollisions, stats.hashCollisions ? 'warn' : ''),
    tile('longest chain', stats.hashLongestChain, ''),
    tile('rehashes', stats.hashRehashes, '')
  ].join('');

  document.getElementById('graph-stats').innerHTML = [
    tile('checkpoints', stats.graphNodes, ''),
    tile('legal transitions', stats.graphEdges, ''),
    tile('profile', state.graph ? state.graph.profile : '\u2014', '')
  ].join('');
}

function tile(label, value, tone) {
  return '<div class="stat ' + (tone || '') + '"><span>' + escapeHtml(label) + '</span><strong>'
    + escapeHtml(String(value)) + '</strong></div>';
}

function renderReference() {
  const container = document.getElementById('reference');
  if (!state.referenceProcess.length) {
    container.innerHTML = '<p class="empty">No reference process configured.</p>';
    return;
  }
  container.innerHTML = state.referenceProcess.map((checkpoint, index) =>
    (index > 0 ? '<span class="ref-arrow">\u2192</span>' : '')
    + '<span class="ref-chip">' + escapeHtml(checkpoint) + '</span>').join('');
}

function renderLog() {
  const rows = [];
  state.bags.forEach(bag => (bag.results || []).forEach(result => rows.push(result)));
  rows.sort((a, b) => a.sequence - b.sequence);
  const recent = rows.slice(Math.max(0, rows.length - LOG_LIMIT));

  const container = document.getElementById('log');
  if (!recent.length) {
    container.innerHTML = '<p class="empty">Nothing validated yet.</p>';
    return;
  }
  container.innerHTML = recent.map(result => {
    const tone = !result.accepted ? 'bad'
      : result.advisory ? 'warn'
      : result.journeyStatus === 'ARRIVAL UNREACHABLE' ? 'info'
      : 'ok';
    const path = result.fromCheckpoint
      ? result.fromCheckpoint + ' \u2192 ' + result.checkpoint
      : '\u25cf ' + result.checkpoint + ' (first scan)';
    return '<div class="log-row">'
      + '<span class="seq">#' + result.sequence + '</span>'
      + '<span>' + escapeHtml(result.bagId) + '</span>'
      + '<span class="path">' + escapeHtml(path) + '</span>'
      + '<span class="tag ' + tone + '">' + escapeHtml(result.eventResult) + '</span>'
      + '</div>';
  }).join('');
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// --------------------------------------------------------------------------
// Wiring
// --------------------------------------------------------------------------

document.getElementById('btn-enqueue').addEventListener('click', actionEnqueue);
document.getElementById('btn-step').addEventListener('click', actionStep);
document.getElementById('btn-process-all').addEventListener('click', actionProcessAll);
document.getElementById('btn-sample').addEventListener('click', actionSample);
document.getElementById('btn-reset').addEventListener('click', actionReset);
document.getElementById('profile-select').addEventListener('change', event => actionProfile(event.target.value));
document.getElementById('bag-select').addEventListener('change', event => {
  state.selected = event.target.value;
  render();
});

refresh().catch(error => {
  feedback(error.message);
  document.getElementById('status-card').innerHTML =
    '<div class="status-head"><div class="status-title">ENGINE NOT REACHABLE</div></div>'
    + '<p class="status-detail">' + escapeHtml(error.message) + '</p>';
});
