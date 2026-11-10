// FamilyGuard — self-hosted backend (personal use)
// Node >= 18, pure-JS deps only. Data in ./data/db.json, media in ./data/media.
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const multer = require('multer');
const bcrypt = require('bcryptjs');

const store = require('./src/store');
const { sign, verify, auth, parentOnly, childOrParent, childOnly, rid, code6 } = require('./src/auth');
const wsHub = require('./src/ws');
const tg = require('./src/telegram');
const { apkVersionFromBuffer } = require('./apkver');

store.load();
const db = store.db;

const app = express();
app.use(express.json({ limit: '8mb' }));

// CORS (personal deployment; both apps call this API directly)
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

const upload = multer({
  dest: store.MEDIA_DIR,
  limits: { fileSize: 15 * 1024 * 1024 }
});

const EXT_BY_TYPE = { 'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp' };

// ---------------------------------------------------------------- health/root
app.get('/health', (req, res) => {
  // Counts expose karna public server par theek nahi — sirf status batao
  res.json({ ok: true, service: 'familyguard-backend', uptime: process.uptime() });
});

// Web dashboard (works on iPhone/Android/desktop browsers — no app needed)
// no-cache: deploy ke turant baad browser fresh app.js/index.html utha le
// (purana cacheserve = "remove device ni dikha" wali complaints se bachav)
app.use('/app', express.static(path.join(__dirname, 'public'), {
  setHeaders: (res) => res.setHeader('Cache-Control', 'no-cache')
}));
app.get('/', (req, res) => res.redirect('/app/'));

// ---------------------------------------------------------------- parent auth
app.post('/api/parent/register', async (req, res) => {
  // Public-repo safety: pehla parent account khud ban sakta hai; uske baad
  // registration band — jo server ka URL jaan bhi le, naya account nahi bana sakta.
  // (Family ka naya parent chahiye to apne existing account se batao.)
  if (db.parents.length) {
    return res.status(403).json({ error: 'Registration locked — is server par family account already hai. Sirf existing parent login kar sakta hai.' });
  }
  const { username, password } = req.body || {};
  if (!username || !password || String(username).length < 4 || String(password).length < 4) {
    return res.status(400).json({ error: 'username and password required (min 4 chars)' });
  }
  if (db.parents.some(p => p.username === username)) return res.status(409).json({ error: 'username taken' });
  const p = { id: rid(), username, passHash: await bcrypt.hash(String(password), 10), createdAt: Date.now() };
  db.parents.push(p);
  store.save();
  res.json({ token: sign({ role: 'parent', pid: p.id, username }) });
});

app.post('/api/parent/login', async (req, res) => {
  const { username, password } = req.body || {};
  const p = db.parents.find(x => x.username === username);
  if (!p || !(await bcrypt.compare(String(password || ''), p.passHash))) {
    return res.status(401).json({ error: 'invalid credentials' });
  }
  res.json({ token: sign({ role: 'parent', pid: p.id, username: p.username }) });
});

app.get('/api/me', auth, (req, res) => {
  if (req.auth.role === 'parent') return res.json({ role: 'parent', pid: req.auth.pid, username: req.auth.username });
  const c = db.children.find(x => x.id === req.auth.cid);
  res.json({ role: 'child', cid: req.auth.cid, name: c ? c.name : '' });
});

// ---------------------------------------------------------------- children mgmt
app.post('/api/children', auth, parentOnly, (req, res) => {
  const { name, dob } = req.body || {};
  if (!name) return res.status(400).json({ error: 'name required' });
  const dobOk = /^\d{4}-\d{2}-\d{2}$/.test(String(dob || ''));
  const c = {
    id: rid(), name: String(name).slice(0, 60), dob: dobOk ? dob : '',
    pairingCode: code6(), paired: false, lastSeen: 0, battery: -1, charging: false,
    deviceName: '', model: '', currentApp: '', createdAt: Date.now()
  };
  db.children.push(c);
  store.save();
  res.json({ child: wsHub.publicChild(c) });
});

app.get('/api/children', auth, parentOnly, (req, res) => {
  res.json({ children: db.children.map(c => wsHub.publicChild(c)) });
});

function purgeChild(cid) {
  store.purgeChild(cid);
  store.save();
}

app.delete('/api/children/:id', auth, parentOnly, (req, res) => {
  const cid = req.params.id;
  const c = db.children.find(x => x.id === cid);
  if (c) {
    // Child abhi online hai to pehle release command — permissions strip +
    // uninstall dialog turant. (Web dashboard khud bhi bhejta hai, par yahan
    // bhejna guarantee karta hai ki koi bhi client se delete par child saaf ho.)
    wsHub.sendToChild(cid, { type: 'command', id: rid(), action: 'release_protections', params: {} });
    tg.log(`🗑 Remove device: "${c.name}" ka record purge hua (parent ne delete kiya)`);
  }
  purgeChild(cid);
  // Sockets band — child app turant reconnect karega, orphan guard use
  // release_protections bhej kar app ko khud clean karwa dega.
  wsHub.closeChild(cid);
  // Sab parent dashboards (web) turant refresh — removed device
  // list me na dikhe.
  wsHub.broadcastToParents({ type: 'children_changed' });
  res.json({ ok: true });
});

app.post('/api/children/:id/regenerate-code', auth, parentOnly, (req, res) => {
  const c = db.children.find(x => x.id === req.params.id);
  if (!c) return res.status(404).json({ error: 'not found' });
  c.pairingCode = code6();
  store.save();
  res.json({ pairingCode: c.pairingCode });
});

// ---------------------------------------------------------------- child self
app.get('/api/child/me', auth, (req, res) => {
  if (req.auth.role !== 'child') return res.status(403).json({ error: 'child only' });
  const c = db.children.find(x => x.id === req.auth.cid);
  if (!c) return res.status(404).json({ error: 'not found' });
  res.json({ id: c.id, name: c.name, dob: c.dob || '' });
});

app.post('/api/child/pair', (req, res) => {
  const { code, deviceName, model } = req.body || {};
  const c = db.children.find(x => x.pairingCode === String(code || '').trim());
  if (!c) return res.status(404).json({ error: 'invalid code' });
  c.paired = true;
  c.deviceName = String(deviceName || '').slice(0, 80);
  c.model = String(model || '').slice(0, 80);
  c.lastSeen = Date.now();
  store.save();
  wsHub.broadcastToParents({ type: 'pairing', cid: c.id, paired: true });
  tg.log(`📱 Child paired: "${c.name}" — ${c.deviceName} (${c.model})`);
  res.json({ token: sign({ role: 'child', cid: c.id }), child: { id: c.id, name: c.name } });
});

// ---------------------------------------------------------------- rules
app.get('/api/rules/:cid', auth, childOrParent, (req, res) => {
  res.json(db.rules[req.params.cid] || wsHub.defaultRules());
});

app.put('/api/rules/:cid', auth, parentOnly, (req, res) => {
  const c = db.children.find(x => x.id === req.params.cid);
  if (!c) return res.status(404).json({ error: 'not found' });
  const rules = req.body || {};
  db.rules[c.id] = {
    dailyScreenTimeMin: Number(rules.dailyScreenTimeMin) || 0,
    bedtime: rules.bedtime || { enabled: false, start: '21:30', end: '07:00' },
    schedules: Array.isArray(rules.schedules) ? rules.schedules : [],
    blockedApps: Array.isArray(rules.blockedApps) ? rules.blockedApps : [],
    appLimits: rules.appLimits && typeof rules.appLimits === 'object' ? rules.appLimits : {},
    alwaysAllowed: Array.isArray(rules.alwaysAllowed) ? rules.alwaysAllowed : []
  };
  store.save();
  wsHub.sendToChild(c.id, { type: 'rules', rules: db.rules[c.id] });
  res.json({ ok: true });
});

// ---------------------------------------------------------------- usage
app.post('/api/usage/:cid', auth, childOrParent, (req, res) => {
  const { date, apps } = req.body || {};
  if (!date || !apps || typeof apps !== 'object') return res.status(400).json({ error: 'date and apps required' });
  if (!db.usage[req.params.cid]) db.usage[req.params.cid] = {};
  db.usage[req.params.cid][date] = apps;
  // keep last 60 days
  const keys = Object.keys(db.usage[req.params.cid]).sort();
  while (keys.length > 60) { delete db.usage[req.params.cid][keys.shift()]; }
  const c = db.children.find(x => x.id === req.params.cid);
  if (c) c.lastSeen = Date.now();
  store.save();
  res.json({ ok: true });
});

app.get('/api/usage/:cid', auth, parentOnly, (req, res) => {
  const all = db.usage[req.params.cid] || {};
  let date = req.query.date;
  if (!date) date = Object.keys(all).sort().pop() || '';
  const apps = all[date] || {};
  let totalMin = 0;
  for (const k of Object.keys(apps)) totalMin += Number(apps[k] && apps[k].min) || 0;
  res.json({ date, apps, totalMin });
});

// ---------------------------------------------------------------- location
app.post('/api/location/:cid', auth, childOrParent, (req, res) => {
  const { lat, lng, accuracy, speed, battery } = req.body || {};
  if (typeof lat !== 'number' || typeof lng !== 'number') return res.status(400).json({ error: 'lat/lng required' });
  wsHub.storeLocation(req.params.cid, { lat, lng, accuracy: accuracy || 0, speed: speed || 0, t: Date.now(), battery: battery == null ? -1 : battery });
  res.json({ ok: true });
});

app.get('/api/location/:cid', auth, parentOnly, (req, res) => {
  const arr = db.locations[req.params.cid] || [];
  const limit = Math.min(Number(req.query.limit) || 200, 1000);
  const points = arr.slice(-limit);
  res.json({ points, last: arr.length ? arr[arr.length - 1] : null });
});

// ---------------------------------------------------------------- geofences
app.get('/api/geofences/:cid', auth, childOrParent, (req, res) => {
  res.json({ fences: db.geofences[req.params.cid] || [] });
});

app.post('/api/geofences/:cid', auth, parentOnly, (req, res) => {
  const { name, lat, lng, radius, type } = req.body || {};
  if (typeof lat !== 'number' || typeof lng !== 'number') return res.status(400).json({ error: 'lat/lng required' });
  const f = {
    id: rid(), name: String(name || 'Zone').slice(0, 60), lat, lng,
    radius: Math.max(30, Number(radius) || 150),
    type: type === 'out' ? 'out' : 'in', active: true
  };
  if (!db.geofences[req.params.cid]) db.geofences[req.params.cid] = [];
  db.geofences[req.params.cid].push(f);
  store.save();
  wsHub.sendToChild(req.params.cid, { type: 'geofences', fences: db.geofences[req.params.cid] });
  res.json({ fence: f });
});

app.delete('/api/geofences/:cid/:fid', auth, parentOnly, (req, res) => {
  db.geofences[req.params.cid] = (db.geofences[req.params.cid] || []).filter(f => f.id !== req.params.fid);
  store.save();
  wsHub.sendToChild(req.params.cid, { type: 'geofences', fences: db.geofences[req.params.cid] });
  res.json({ ok: true });
});

// ---------------------------------------------------------------- events
app.post('/api/events/:cid', auth, childOrParent, (req, res) => {
  const { type, data } = req.body || {};
  if (!type) return res.status(400).json({ error: 'type required' });
  wsHub.storeEvent(req.params.cid, { id: rid(), type: String(type).slice(0, 40), data: data || {}, t: Date.now() });
  res.json({ ok: true });
});

app.get('/api/events/:cid', auth, parentOnly, (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 200, 500);
  res.json({ events: (db.events[req.params.cid] || []).slice(0, limit) });
});

// ---------------------------------------------------------------- calls / sms / notifications
app.post('/api/calls/:cid', auth, childOrParent, (req, res) => {
  const { calls } = req.body || {};
  if (!Array.isArray(calls)) return res.status(400).json({ error: 'calls array required' });
  for (const c of calls.slice(0, 300)) {
    store.pushList(db.calls, req.params.cid, c, 1000);
  }
  const ch = db.children.find(x => x.id === req.params.cid);
  if (ch) ch.lastSeen = Date.now();
  store.save();
  res.json({ ok: true });
});

app.get('/api/calls/:cid', auth, parentOnly, (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 300, 1000);
  res.json({ calls: (db.calls[req.params.cid] || []).slice(0, limit) });
});

app.post('/api/sms/:cid', auth, childOrParent, (req, res) => {
  const { sms } = req.body || {};
  if (!Array.isArray(sms)) return res.status(400).json({ error: 'sms array required' });
  for (const s of sms.slice(0, 300)) {
    store.pushList(db.sms, req.params.cid, s, 1000);
  }
  const ch = db.children.find(x => x.id === req.params.cid);
  if (ch) ch.lastSeen = Date.now();
  store.save();
  res.json({ ok: true });
});

app.get('/api/sms/:cid', auth, parentOnly, (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 300, 1000);
  res.json({ sms: (db.sms[req.params.cid] || []).slice(0, limit) });
});

app.post('/api/notifications/:cid', auth, childOrParent, (req, res) => {
  const { notifications } = req.body || {};
  if (!Array.isArray(notifications)) return res.status(400).json({ error: 'notifications array required' });
  for (const n of notifications.slice(0, 100)) {
    wsHub.storeNotification(req.params.cid, { pkg: n.pkg || '', title: n.title || '', text: n.text || '', date: n.date || Date.now() });
  }
  res.json({ ok: true });
});

app.get('/api/notifications/:cid', auth, parentOnly, (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 200, 500);
  res.json({ notifications: (db.notifications[req.params.cid] || []).slice(0, limit) });
});

// ---------------------------------------------------------------- commands
const ALLOWED_ACTIONS = new Set([
  'locate_now', 'ring', 'ring_stop', 'lock', 'unlock', 'screenshot', 'camera',
  'mirror_start', 'mirror_stop', 'mirror_request_permission',
  'audio_start', 'audio_stop',
  'show_app_icon', 'hide_app_icon', 'get_apps', 'message', 'release_protections',
  'sync_usage', // Report tab "Sync now" — usage + call log + SMS turant sync
  'heal', 'health', // self-heal trigger + health check (dashboard use these)
  'update_apk', // silent self-update abhi check (dashboard "Child app update" button)
  // CRITICAL FIX (v1.11): ye 3 actions dashboard se BHEJE jaate the par whitelist
  // me the hi nahi — server har baar 400 "unknown action" deta tha. Isliye
  // Gallery (gallery_list/gallery_full) aur 🔍 Phone check (mirror_diag) KABHI
  // kaam nahi kiye the, chahe child app sab handle karta tha. Mirror frames
  // parent-WS se jaate hain (bypass), isliye mirror "dead" lagta tha sirf isliye
  // ki Phone check diagnosis khud 400 kha rahi thi.
  'gallery_list', // Live tab "📸 Gallery kholo" — MediaStore page + thumbs
  'gallery_full', // gallery tile tap — full photo/video
  'mirror_diag' // 🔍 Phone check — bachche ke phone ka mirror/permission snapshot
]);

app.post('/api/commands', auth, parentOnly, (req, res) => {
  const { cid, action, params } = req.body || {};
  if (!cid || !action) return res.status(400).json({ error: 'cid and action required' });
  if (!ALLOWED_ACTIONS.has(action)) return res.status(400).json({ error: 'unknown action' });
  const c = db.children.find(x => x.id === cid);
  if (!c) return res.status(404).json({ error: 'child not found' });
  const cmd = { id: rid(), cid, action, params: params || {}, t: Date.now() };
  // update_apk ke results child record par persist hote hain (ws.js) — banner
  // "aakhri update try" isi se dikhta hai.
  if (action === 'update_apk') wsHub.markUpdateCommand(cmd.id);
  if (!wsHub.sendToChild(cid, { type: 'command', id: cmd.id, action, params: cmd.params })) {
    if (!db.commandQueue[cid]) db.commandQueue[cid] = [];
    db.commandQueue[cid].push({ id: cmd.id, action, params: cmd.params });
    if (db.commandQueue[cid].length > 50) db.commandQueue[cid].shift();
    store.save();
  }
  res.json({ id: cmd.id });
});

// ---------------------------------------------------------------- media
app.post('/api/media/:cid', auth, childOrParent, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file required' });
  // Ext must always match the media GET regex: [a-f0-9]{24}\.(jpg|png|webp|bin)
  const ext = EXT_BY_TYPE[req.file.mimetype]
    || (String(req.file.mimetype || '').startsWith('image/') ? '.jpg' : '.bin');
  const name = crypto.randomBytes(12).toString('hex') + ext;
  fs.renameSync(req.file.path, path.join(store.MEDIA_DIR, name));
  store.pushList(db.media, req.params.cid, { name, t: Date.now() }, 500);
  store.save();
  res.json({ url: '/media/' + name });
});

app.get('/media/:name', (req, res) => {
  const name = String(req.params.name || '');
  if (!/^[a-f0-9]{24}\.(jpg|png|webp|bin)$/.test(name)) return res.status(400).json({ error: 'bad name' });
  res.sendFile(path.join(store.MEDIA_DIR, name));
});

// ---------------------------------------------------------------- child app apk share link
// The child APK normally comes straight from GitHub: CI publishes every main-branch
// build to a rolling release (tag "latest") and the backend syncs it automatically.
// Manual upload from the dashboard still works as a fallback.
const APK_DIR = path.join(store.DATA_DIR, 'apk');
const APK_FILE = path.join(APK_DIR, 'child.apk');
const APK_META = path.join(APK_DIR, 'meta.json');
fs.mkdirSync(APK_DIR, { recursive: true });

const GH_REPO = String(process.env.GITHUB_REPO || 'lawrence-apps/FamilyGuard');
const GH_TOKEN = String(process.env.GITHUB_TOKEN || '');
const GH_CHILD_ASSET = String(process.env.GITHUB_CHILD_ASSET || 'familyguard-child-debug.apk');
const GH_CHILD_META = String(process.env.GITHUB_CHILD_META || 'familyguard-child-meta.json');
const GH_SYNC_MS = Number(process.env.GITHUB_SYNC_MINUTES || 10) * 60 * 1000;

function apkMeta() {
  try { return JSON.parse(fs.readFileSync(APK_META, 'utf8')); } catch (e) { return null; }
}

// Sync health — /apk/info + website "Sync now" ke paas dikhane ke liye. Blind
// debugging ("server par kaunsa APK hai, sync chal raha hai ya nahi") ka
// saral jawab: lastAttempt/lastOk/lastError.
const apkSyncState = { lastAttempt: 0, lastOk: 0, lastError: '', lastReason: '' };

function apkStatus() {
  const m = apkMeta();
  return {
    uploaded: !!m,
    url: m ? `/apk/child?t=${m.shareToken}` : '',
    fileName: m ? m.originalName : '',
    size: m ? m.size : 0,
    uploadedAt: m ? m.uploadedAt : 0,
    source: m ? (m.source || 'manual') : '',
    releaseTag: m ? (m.releaseTag || '') : '',
    lastSync: m ? (m.lastSync || 0) : 0,
    // CI ki meta json asset se — server par KAUNSA build hai, banner/diag isse
    // dikhati hai ("Server par APK: v1.8 (build 9)"). Purane sync par 0/''.
    vc: m ? (m.versionCode || 0) : 0,
    vName: m ? (m.versionName || '') : '',
    buildCommit: m ? (m.buildCommit || '') : '',
    lastSyncError: apkSyncState.lastError || '',
    githubConfigured: !!GH_REPO,
    githubTokenSet: !!GH_TOKEN
  };
}

// PUBLIC (no auth): server par abhi kaunsa child APK pada hai — sirf version
// info, koi shareToken/URL nahi. "Child update kyun nahi ho raha" ka diagnosis
// ab ek curl se ho jayega: /apk/info → {vc, source, lastSync, sync.lastError}.
app.get('/apk/info', (req, res) => {
  const s = apkStatus();
  res.json({
    vc: s.vc,
    vName: s.vName,
    source: s.source,
    releaseTag: s.releaseTag,
    size: s.size,
    uploadedAt: s.uploadedAt,
    lastSync: s.lastSync,
    githubConfigured: s.githubConfigured,
    githubTokenSet: s.githubTokenSet,
    sync: apkSyncState,
    serverTime: Date.now()
  });
});

// Pull the latest CI-built child APK from the GitHub release. Keeps the existing
// share token so old install links/QRs keep working across syncs.
async function syncApkFromGithub({ manual = false } = {}) {
  const base = { manual, repo: GH_REPO, asset: GH_CHILD_ASSET };
  if (!GH_REPO) return { ok: false, ...base, reason: 'GITHUB_REPO not set' };
  try {
    const headers = {
      'User-Agent': 'familyguard-backend',
      'Accept': 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28'
    };
    if (GH_TOKEN) headers.Authorization = `Bearer ${GH_TOKEN}`;
    const rel = await fetch(`https://api.github.com/repos/${GH_REPO}/releases/latest`, { headers });
    if (!rel.ok) {
      let why = `release lookup failed (HTTP ${rel.status})`;
      if (rel.status === 404 && !GH_TOKEN) why = 'repo private hai aur server par GITHUB_TOKEN set nahi hai — Coolify → Environment me GITHUB_TOKEN (GitHub PAT) add karke redeploy karo';
      else if (rel.status === 404) why = 'release "latest" nahi mila (token sahi hai) — CI build complete hua? Actions tab check karo';
      else if (rel.status === 401) why = 'GITHUB_TOKEN invalid ya expired hai — naya PAT bana ke Coolify me update karo';
      else if (rel.status === 403 && !GH_TOKEN) why = 'GitHub ne bina-token request block ki (private repo/rate limit) — Coolify → Environment me GITHUB_TOKEN (GitHub PAT) add karke redeploy karo';
      else if (rel.status === 403) why = 'GITHUB_TOKEN ko is repo ka read access nahi hai (fine-grained PAT me Contents: read on karo)';
      console.warn(`[apk] sync failed: ${why}`);
      tg.error('APK sync failed: ' + why);
      return { ok: false, ...base, reason: why };
    }
    const data = await rel.json();
    const asset = (data.assets || []).find(a => a.name === GH_CHILD_ASSET);
    if (!asset) return { ok: false, ...base, reason: `asset "${GH_CHILD_ASSET}" release me nahi mila` };

    // CI ki build-info meta asset PEHLE padh lo — override ka faisla VERSION
    // ke hisaab se bhi ho sake (timestamp ke saath manual-upload trick band).
    let buildInfo = null;
    try {
      const mi = (data.assets || []).find(a => a.name === GH_CHILD_META);
      if (mi) {
        const mr = await fetch(`https://api.github.com/repos/${GH_REPO}/releases/assets/${mi.id}`, {
          headers: { ...headers, Accept: 'application/octet-stream' }
        });
        if (mr.ok) buildInfo = JSON.parse(Buffer.from(await mr.arrayBuffer()).toString('utf8').trim());
      }
    } catch (e) {}
    const relVc = Number((buildInfo && buildInfo.versionCode) || 0);

    const cur = apkMeta();
    // Manual upload override — par SIRF jab tak wo GitHub release se nayi hai.
    // Do comparisons: (a) TIME — release asset upload humari manual se naya ho;
    // (b) VERSION — release ka versionCode humari manual APK se bada ho. (b)
    // sabse bharosemand hai: pehle sirf time comparison tha, to user ne jab
    // galti se PURANI APK naye time par upload ki (1.6 manual upload ke baad
    // v1.7 release aya) to override kabhi nahi hata — server purana APK hi
    // baant-ta raha aur child ka auto-update kabhi trigger hi nahi hota tha.
    if (!manual && cur && (!cur.source || cur.source === 'manual')) {
      const relMs = Date.parse(asset.updated_at || '') || 0;
      const upMs = Number(cur.uploadedAt) || 0;
      const curVc = Number(cur.versionCode) || 0;
      const relNewer = upMs < relMs || (relVc > 0 && curVc > 0 && relVc > curVc);
      if (!relNewer) {
        return { ok: true, skipped: true, ...base, reason: 'manual upload release se nayi/naya-version hai — sync ne nahi chhua', releaseVc: relVc, serverVc: curVc };
      }
    }
    const fresh = cur && cur.source === 'github' && cur.assetId === asset.id &&
      cur.assetUpdatedAt === asset.updated_at && cur.size === asset.size && fs.existsSync(APK_FILE);
    if (fresh && !manual) return { ok: true, skipped: true, ...base, tag: data.tag_name, size: asset.size };

    const dl = await fetch(`https://api.github.com/repos/${GH_REPO}/releases/assets/${asset.id}`, {
      headers: { ...headers, Accept: 'application/octet-stream' }
    });
    if (!dl.ok) {
      const why = `APK download failed (HTTP ${dl.status})${dl.status === 403 ? ' — PAT ko repo read access nahi mila' : ''}`;
      console.warn(`[apk] sync failed: ${why}`);
      tg.error('APK download failed: ' + why);
      return { ok: false, ...base, reason: why };
    }
    const buf = Buffer.from(await dl.arrayBuffer());
    if (buf.length < 1000 || buf[0] !== 0x50 || buf[1] !== 0x4b) {
      return { ok: false, ...base, reason: 'downloaded file APK jaisi nahi lag rahi' };
    }
    const tmp = APK_FILE + '.tmp';
    fs.writeFileSync(tmp, buf);
    fs.renameSync(tmp, APK_FILE);
    // buildInfo upar hi padh liya tha (override decision ke liye) — yahi reuse.
    // Missing ho (purane release) to APK ke manifest se khud parse kar lo.
    let ver = {
      versionCode: relVc || 0,
      versionName: (buildInfo && buildInfo.versionName) || '',
      commit: (buildInfo && buildInfo.commit) || ''
    };
    if (!ver.versionCode || !ver.versionName) {
      const fromApk = apkVersionFromBuffer(buf);
      if (fromApk) {
        if (!ver.versionCode) ver.versionCode = fromApk.versionCode;
        if (!ver.versionName) ver.versionName = fromApk.versionName;
      }
    }
    const meta = {
      shareToken: (cur && cur.shareToken) || crypto.randomBytes(8).toString('hex'),
      originalName: asset.name,
      size: buf.length,
      uploadedAt: (cur && cur.uploadedAt) || Date.now(),
      source: 'github',
      releaseTag: data.tag_name,
      assetId: asset.id,
      assetUpdatedAt: asset.updated_at,
      lastSync: Date.now(),
      versionCode: ver.versionCode,
      versionName: ver.versionName,
      buildCommit: ver.commit
    };
    fs.writeFileSync(APK_META, JSON.stringify(meta));
    console.log(`[apk] synced from GitHub: ${data.tag_name}${meta.versionCode ? ` (v${meta.versionName}, build ${meta.versionCode})` : ''} (${(buf.length / 1048576).toFixed(1)} MB)`);
    // Naye build par admin ko child + parent APK Telegram par bhejo (purane delete)
    tg.notifyNewRelease(data, buf);
    // Online children ko batao — child app ka silent self-update turant shuru
    // ho jayega (bina popup, accessibility install dialog khud confirm karta hai).
    try {
      for (const c of db.children) {
        if (wsHub.isOnline(c.id)) {
          wsHub.sendToChild(c.id, { type: 'apk_update', releaseTag: data.tag_name, size: buf.length });
        }
      }
    } catch (e) {}
    return { ok: true, ...base, tag: data.tag_name, size: buf.length };
  } catch (e) {
    console.warn(`[apk] sync failed: ${e.message}`);
    return { ok: false, ...base, reason: e.message };
  }
}

// apkSyncState wrapper — har attempt/ok/error record hota hai (/apk/info isse
// batata hai). Blind debugging khatam: ek curl me sync zinda hai ya nahi.
async function syncApkFromGithubTracked(opts = {}) {
  apkSyncState.lastAttempt = Date.now();
  try {
    const r = await syncApkFromGithub(opts);
    if (r && r.ok) {
      apkSyncState.lastOk = Date.now();
      apkSyncState.lastReason = r.skipped ? (r.reason || 'skip') : 'synced';
      apkSyncState.lastError = '';
    } else {
      apkSyncState.lastError = (r && r.reason) || 'sync failed';
    }
    return r;
  } catch (e) {
    apkSyncState.lastError = (e && e.message) || 'sync error';
    throw e;
  }
}

let ghSyncTimer = null;
function startGithubSync() {
  if (ghSyncTimer) return;
  ghSyncTimer = setInterval(() => syncApkFromGithubTracked().catch(() => {}), GH_SYNC_MS);
  // first sync shortly after boot so the link is live almost immediately
  setTimeout(() => syncApkFromGithubTracked().catch(() => {}), 4000);
}

const apkUpload = multer({ dest: APK_DIR, limits: { fileSize: 150 * 1024 * 1024 } });

app.get('/api/apk', auth, parentOnly, (req, res) => {
  res.json(apkStatus());
});

// Child app ka silent self-update check: bachche ka apna JWT isko poll karta hai
// (aur WS apk_update push bhi isi meta par based hai).
app.get('/api/child/apk', auth, (req, res) => {
  if (req.auth.role !== 'child') return res.status(403).json({ error: 'child only' });
  const m = apkMeta();
  const s = apkStatus();
  res.json({
    uploaded: s.uploaded,
    url: s.url,
    size: s.size,
    releaseTag: s.releaseTag,
    assetUpdatedAt: (m && m.assetUpdatedAt) || ''
  });
});

// Manual re-sync from GitHub (dashboard "Sync now" button).
app.post('/api/apk/sync', auth, parentOnly, async (req, res) => {
  const result = await syncApkFromGithubTracked({ manual: true });
  res.status(result.ok ? 200 : 502).json({ ...apkStatus(), sync: result });
});

app.post('/api/apk', auth, parentOnly, apkUpload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file required (.apk)' });
  const name = String(req.file.originalname || '');
  const isApk = name.toLowerCase().endsWith('.apk') ||
    req.file.mimetype === 'application/vnd.android.package-archive';
  if (!isApk) {
    fs.unlinkSync(req.file.path);
    return res.status(400).json({ error: 'only .apk files allowed' });
  }
  fs.renameSync(req.file.path, APK_FILE);
  // APK ka version manifest se padho — version-based sync compare + website
  // display ke liye. Purana manual upload iske bina "invisible" ho jata tha
  // (na stamp, na version — child ka check sirf "no apk on server" dekh pata tha).
  let ver = { versionCode: 0, versionName: '' };
  try { ver = apkVersionFromBuffer(fs.readFileSync(APK_FILE)) || ver; } catch (e) {}
  const meta = {
    shareToken: crypto.randomBytes(8).toString('hex'),
    originalName: name.slice(0, 120),
    size: req.file.size,
    uploadedAt: Date.now(),
    source: 'manual',
    versionCode: ver.versionCode || 0,
    versionName: ver.versionName || ''
  };
  fs.writeFileSync(APK_META, JSON.stringify(meta));
  console.log(`[apk] manual upload: ${meta.originalName}${meta.versionCode ? ` (v${meta.versionName}, build ${meta.versionCode})` : ''} — agar GitHub release isse naya build ho to agli sync me replace ho jayega`);
  res.json(apkStatus());
});

app.delete('/api/apk', auth, parentOnly, (req, res) => {
  try { fs.unlinkSync(APK_FILE); } catch (e) {}
  try { fs.unlinkSync(APK_META); } catch (e) {}
  res.json({ ok: true });
});

function escHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Public (token-protected): browser gets a landing page, ?dl=1 streams the APK.
// ?c=<pairing code> (added by the /s/:code setup page) shows the green
// "open app & auto-setup" button here too, so the zero-touch loop is closed
// even if the user loses the original QR page after installing.
app.get('/apk/child', (req, res) => {
  // no-store: browser cached PURANA APK/page kabhi na de — naya download hamesha
  // server par pada CURRENT build hi ho (stale-download confusion band).
  res.setHeader('Cache-Control', 'no-store');
  const m = apkMeta();
  if (!m || !fs.existsSync(APK_FILE)) {
    return res.status(404).send('<h2>FamilyGuard</h2><p>Child app abhi available nahi hui. Thodi der baad try karo — GitHub build ke baad ye link apne aap live ho jayega.</p>');
  }
  if (String(req.query.t || '') !== m.shareToken) {
    return res.status(403).send('<h2>FamilyGuard</h2><p>Invalid or expired link — parent dashboard se fresh link lo.</p>');
  }
  const wantsPage = String(req.headers.accept || '').includes('text/html') && req.query.dl !== '1';
  if (wantsPage) {
    const sizeMb = (m.size / (1024 * 1024)).toFixed(1);
    const name = escHtml(m.originalName);
    const host = String(req.get('host') || '');
    const origin = /^[a-zA-Z0-9.\-:\[\]]+$/.test(host)
      ? `${String(req.get('x-forwarded-proto') || req.protocol || 'https')}://${host}` : '';
    const code = String(req.query.c || '').trim();
    const validCode = /^\d{6}$/.test(code) && db.children.some(x => x.pairingCode === code);
    const deep = validCode && origin
      ? `familyguard://pair?server=${encodeURIComponent(origin)}&code=${code}` : '';
    const openBtn = deep
      ? `<a class="btn green" href="${deep}">✅ App installed hai? — App kholo &amp; auto-setup</a>
<p class="mut">Ye button app ko khud khol dega — server + code pehle se bhare honge, pair <b>khud ho jayega</b>. Pair hote hi permissions + hide bhi automatic.</p>`
      : '';
    res.send(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FamilyGuard — Child App</title>
<style>body{font-family:system-ui,sans-serif;background:#0b1220;color:#e5e7eb;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;padding:20px}
.c{max-width:430px;text-align:center;background:#111a2e;padding:28px;border-radius:16px}
.btn{display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:14px 34px;border-radius:10px;font-size:17px;font-weight:600;margin:14px 0}
.btn.green{background:#16a34a}
.mut{color:#94a3b8;font-size:13px;line-height:1.6}h1{font-size:20px;margin:6px 0}</style></head>
<body><div class="c"><div style="font-size:44px">🛡️</div><h1>FamilyGuard — Child App</h1>
<p class="mut">${name} &middot; ${sizeMb} MB &middot; ${new Date(m.uploadedAt).toLocaleString()}</p>
${m.versionCode ? `<p style="font-size:17px;margin:6px 0 2px">APK version: <b>v${escHtml(m.versionName || "?")} (build ${m.versionCode})</b></p>
<p class="mut" style="margin:0 0 4px;font-size:12px">Install ke baad parent website ke Phone check me yahi build number dikhna chahiye — tabhi confirm hoga ki naya version laga hai.</p>` : ""}
<a class="btn" href="?t=${m.shareToken}&amp;dl=1${validCode ? '&amp;c=' + code : ''}">⬇ Download APK</a>
${openBtn}
<p class="mut">Install karte waqt agar <b>"unknown apps blocked"</b> aaye to:<br><b>Settings → Allow from this source</b> enable karo → wapas aao → install.</p>
<p class="mut">Install ke baad <b>green button dabao</b> — pairing + permissions + hide SAB khud ho jayega (sirf 1 accessibility toggle ON karna hai).</p>
</div></body></html>`);
    return;
  }
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  // Filename me VERSION — Downloads folder me sari purani copies (familyguard-
  // child.apk, (1), (2)…) same naam ki hoti thi, aur user galat purani file
  // install kar baithe tha (asli hua — Phone check "unknown action" de raha
  // tha). Ab file ka naam hi batayega kaunsi build hai: familyguard-child-v1.10.apk
  const vSafe = String(m.versionName || '').replace(/[^a-zA-Z0-9.\-]/g, '') || 'latest';
  res.setHeader('Content-Disposition', `attachment; filename="familyguard-child-v${vSafe}.apk"`);
  res.setHeader('Content-Length', String(m.size));
  fs.createReadStream(APK_FILE).pipe(res);
});

// Public: QR setup link — scanning it opens the child app with server + code
// prefilled and pairs automatically (falls back to APK download + manual code).
app.get('/s/:code', (req, res) => {
  res.setHeader('Cache-Control', 'no-store'); // setup page hamesha fresh — purana cached HTML par galat version/link na dikhe
  const code = String(req.params.code || '').trim();
  const c = db.children.find(x => x.pairingCode === code);
  if (!c) return res.status(404).send('<h2>FamilyGuard</h2><p>Invalid setup link — parent dashboard se naya QR/link lo.</p>');
  const host = String(req.get('host') || '');
  if (!/^[a-zA-Z0-9.\-:\[\]]+$/.test(host)) return res.status(400).send('bad host');
  const proto = String(req.get('x-forwarded-proto') || req.protocol || 'https');
  const origin = `${proto}://${host}`;
  const apk = apkMeta();
  // dl goes into an href attribute only — use &amp; for valid HTML
  const dl = apk ? `${origin}/apk/child?t=${apk.shareToken}&amp;c=${c.pairingCode}` : '';
  const deep = `familyguard://pair?server=${encodeURIComponent(origin)}&code=${c.pairingCode}`;
  res.send(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FamilyGuard — Child Setup</title>
<style>body{font-family:system-ui,sans-serif;background:#0b1220;color:#e5e7eb;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;padding:20px}
.c{max-width:430px;text-align:center;background:#111a2e;padding:28px;border-radius:16px}
.btn{display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:14px 30px;border-radius:10px;font-size:16px;font-weight:600;margin:8px 4px}
.btn.green{background:#16a34a}
.mut{color:#94a3b8;font-size:13px;line-height:1.6}h1{font-size:20px;margin:6px 0}
.code{font-size:34px;letter-spacing:6px;font-weight:800;color:#93C5FD;margin:8px 0}</style></head>
<body><div class="c"><div style="font-size:44px">🛡️</div><h1>FamilyGuard — Child Setup</h1>
<p class="mut">App khul rahi hai… agar na khule to niche dekho.</p>
<div id="fb" style="display:none">
  ${dl ? `<a class="btn" href="${dl}">1) App download karo</a>` : ''}
  <a class="btn green" href="${deep}">2) App kholo &amp; auto-setup</a>
  <p class="mut">Naya phone pehli baar: <b>1)</b> App download → install karo, <b>2)</b> wapas isi page par aakar green button dabao (ya download page ka green button). Uske baad phone ko chhune ki zarurat nahi — pairing, permissions, hide sab apne aap.</p>
  <p class="mut" style="margin-top:14px">Ya <b>Device Health</b> app kholo aur ye code daalo:</p>
  <div class="code">${c.pairingCode}</div>
  <p class="mut">Server: ${origin}</p>
</div>
<script>
  var link = ${JSON.stringify(deep)};
  var isAndroid = /android/i.test(navigator.userAgent);
  var isChrome = /chrome/i.test(navigator.userAgent) && !/edg\\//i.test(navigator.userAgent);
  var fbUrl = location.href;
  if (fbUrl.indexOf('fb=1') === -1) fbUrl += (fbUrl.indexOf('?') === -1 ? '?' : '&') + 'fb=1';
  function showFb() { document.getElementById('fb').style.display = 'block'; }
  if (!isAndroid) {
    // Desktop/iOS: child app sirf Android par chalti hai — isi page se download/link lo
    showFb();
  } else if (location.search.indexOf('fb=1') !== -1) {
    // Chrome wapas isi page par aya (app nahi mili) — ab redirect loop mat lagao
    showFb();
  } else if (isChrome) {
    // intent:// + browser_fallback_url: app ho to khul jayegi, na ho to Chrome
    // isi page par wapas aa jayega (fb=1) — ERR_UNKNOWN_URL_SCHEME error page kabhi nahi
    var intent = link.replace('familyguard://', 'intent://') +
      '#Intent;scheme=familyguard;package=com.familyguard.child;S.browser_fallback_url=' +
      encodeURIComponent(fbUrl) + ';end';
    window.location = intent;
    setTimeout(function () { if (!document.hidden) showFb(); }, 1800);
  } else {
    // Baaki Android browsers: direct try + timer fallback
    window.location = link;
    setTimeout(function () { if (!document.hidden) showFb(); }, 1600);
  }
</script>
</div></body></html>`);
});

// ---------------------------------------------------------------- start
const PORT = Number(process.env.PORT) || 8080;
const server = http.createServer(app);
wsHub.attach(server);

server.listen(PORT, () => {
  console.log(`[familyguard] backend listening on :${PORT}  (${db.children.length} children, ${db.parents.length} parents)`);
  startGithubSync(); // auto-pull child APK from GitHub releases
  // Telegram companion: logs + APK delivery + data backup admin ko (UI me koi change nahi)
  tg.init({
    ghRepo: GH_REPO,
    ghToken: GH_TOKEN,
    childAsset: GH_CHILD_ASSET,
    parentAsset: process.env.GITHUB_PARENT_ASSET || 'familyguard-parent-debug.apk',
    getStatus: () => ({
      uptime: process.uptime(),
      children: db.children.map(c => ({ id: c.id, name: c.name, online: wsHub.isOnline(c.id) })),
      apk: apkStatus(),
      origin: process.env.PUBLIC_ORIGIN || `http://localhost:${PORT}`
    })
  });
});

process.on('uncaughtException', (e) => { console.error('[uncaught]', e); tg.error('uncaughtException: ' + (e && e.message || e)); });
process.on('unhandledRejection', (e) => { console.error('[unhandled]', e); tg.error('unhandledRejection: ' + (e && (e.message || String(e)) || e)); });

// Coolify graceful shutdown — jaate waqt fresh data backup Telegram par
let shuttingDown = false;
process.on('SIGTERM', () => {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log('[familyguard] SIGTERM — backup + exit');
  tg.backupNow('shutdown').catch(() => {}).finally(() => setTimeout(() => process.exit(0), 800));
  setTimeout(() => process.exit(0), 6000).unref();
});
