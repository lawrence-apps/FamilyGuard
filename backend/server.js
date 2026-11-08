// FamilyGuard — self-hosted backend (personal use)
// Node >= 18, pure-JS deps only. Data in ./data/db.json, media in ./data/media.
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const multer = require('multer');
const bcrypt = require('bcryptjs');

const store = require('./src/store');
const { sign, verify, auth, parentOnly, childOrParent, childOnly, rid, code6 } = require('./src/auth');
const wsHub = require('./src/ws');

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
  res.json({ ok: true, service: 'familyguard-backend', children: db.children.length, parents: db.parents.length, uptime: process.uptime() });
});

// Web dashboard (works on iPhone/Android/desktop browsers — no app needed)
app.use('/app', express.static(path.join(__dirname, 'public')));
app.get('/', (req, res) => res.redirect('/app/'));

// ---------------------------------------------------------------- parent auth
app.post('/api/parent/register', async (req, res) => {
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
  purgeChild(req.params.id);
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
  'locate_now', 'ring', 'lock', 'unlock', 'screenshot', 'camera',
  'mirror_start', 'mirror_stop', 'mirror_request_permission',
  'show_app_icon', 'hide_app_icon', 'get_apps', 'message', 'release_protections'
]);

app.post('/api/commands', auth, parentOnly, (req, res) => {
  const { cid, action, params } = req.body || {};
  if (!cid || !action) return res.status(400).json({ error: 'cid and action required' });
  if (!ALLOWED_ACTIONS.has(action)) return res.status(400).json({ error: 'unknown action' });
  const c = db.children.find(x => x.id === cid);
  if (!c) return res.status(404).json({ error: 'child not found' });
  const cmd = { id: rid(), cid, action, params: params || {}, t: Date.now() };
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
  const fs = require('fs');
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

// ---------------------------------------------------------------- start
const PORT = Number(process.env.PORT) || 8080;
const server = http.createServer(app);
wsHub.attach(server);

server.listen(PORT, () => {
  console.log(`[familyguard] backend listening on :${PORT}  (${db.children.length} children, ${db.parents.length} parents)`);
});

process.on('uncaughtException', (e) => console.error('[uncaught]', e));
process.on('unhandledRejection', (e) => console.error('[unhandled]', e));
