// Telegram companion bot — logs, APK delivery, data backup + restore.
// UI me koi change nahi (user requirement) — ye sirf backend-side channel hai.
//
// Admin ko kya jata hai:
//  - 🚀 start / ⚠️ error / 🟢⚫ child connect-disconnect / 🗑 remove device / 📱 pairing logs
//  - 📦 har naye build par child + parent APK documents (purane APK messages auto-delete —
//    bot chat me hamesha sirf latest APKs rehte hain)
//  - 💾 db.json ka rolling backup (data = "storage") — naya aaye to purana message delete
//  - commands: /status /apk /backup /help + db.json document bhejo = restore (restart khud)
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const store = require('./store');

const DATA_DIR = store.DATA_DIR;
const STATE_FILE = path.join(DATA_DIR, 'telegram.json');

// Personal deployment — values user-provided; env override se rotate kar sakte ho.
const TOKEN = String(process.env.TELEGRAM_BOT_TOKEN || '9190724328:AAHxFKQzqrxJ0_UmKQqdlELHT6tl8PugIVk');
const ADMIN = String(process.env.TELEGRAM_ADMIN_ID || '1345802943');
const API = 'https://api.telegram.org/bot' + TOKEN;

let cfg = { ghRepo: '', ghToken: '', childAsset: 'familyguard-child-debug.apk', parentAsset: 'familyguard-parent-debug.apk', getStatus: () => ({}) };
let state = { apkMsgIds: [], backupMsgId: null, apkAssets: {}, lastBackupHash: '' };
let enabled = false;
let logQueue = [];
let logWorker = null;
let saveTimer = null;
let pollOffset = 0;
let pollerStarted = false;
let apkSendInFlight = false;

function loadState() {
  try { state = Object.assign(state, JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))); } catch (e) {}
}
function saveState() {
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(state)); } catch (e) {}
}

async function call(method, body, timeoutMs = 35000) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const res = await fetch(API + '/' + method, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: ac.signal
    });
    return await res.json();
  } finally { clearTimeout(t); }
}

async function sendDocument(buf, filename, caption) {
  const fd = new FormData();
  fd.append('chat_id', ADMIN);
  if (caption) fd.append('caption', String(caption).slice(0, 900));
  fd.append('document', new Blob([buf]), filename);
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), 240000); // bade APKs ke liye lamba timeout
  try {
    const res = await fetch(API + '/sendDocument', { method: 'POST', body: fd, signal: ac.signal });
    const j = await res.json();
    if (j && j.ok && j.result) return j.result.message_id;
    throw new Error(j && j.description ? j.description : 'sendDocument failed');
  } finally { clearTimeout(t); }
}

async function deleteMsg(id) {
  if (!id) return;
  try { await call('deleteMessage', { chat_id: ADMIN, message_id: id }); } catch (e) {}
}

// ---------------------------------------------------------------- log relay
// Rate-limit friendly: ek waqt me ek message, 1.2s spacing, 4000 char cap.
function log(msg) { enqueue('🖥 ' + msg); }
function error(msg) { enqueue('⚠️ ' + msg); }
function enqueue(text) {
  if (!enabled) return;
  logQueue.push(String(text).slice(0, 3800));
  if (logQueue.length > 40) logQueue.splice(0, logQueue.length - 40); // flood guard
}
async function processLogs() {
  if (logWorker) return;
  logWorker = (async () => {
    while (logQueue.length) {
      const text = logQueue.shift();
      try { await call('sendMessage', { chat_id: ADMIN, text }); }
      catch (e) { console.error('[tg] send failed:', e.message); await sleep(4000); }
      await sleep(1200);
    }
    logWorker = null;
  })();
}
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

// ---------------------------------------------------------------- APK delivery
// Naye release assets dikhe to admin ko child + parent APK documents bhejo.
// "Purane hat ke": pehle purane APK messages delete, phir naye bhej — chat me sirf latest.
async function ghRelease() {
  const headers = { 'User-Agent': 'familyguard-backend', 'Accept': 'application/vnd.github+json' };
  if (cfg.ghToken) headers.Authorization = 'Bearer ' + cfg.ghToken;
  const rel = await fetch(`https://api.github.com/repos/${cfg.ghRepo}/releases/latest`, { headers });
  if (!rel.ok) throw new Error('release lookup HTTP ' + rel.status);
  return rel.json();
}
async function ghAsset(asset) {
  const headers = { 'User-Agent': 'familyguard-backend', 'Accept': 'application/octet-stream' };
  if (cfg.ghToken) headers.Authorization = 'Bearer ' + cfg.ghToken;
  const res = await fetch(`https://api.github.com/repos/${cfg.ghRepo}/releases/assets/${asset.id}`, { headers });
  if (!res.ok) throw new Error('asset download HTTP ' + res.status);
  return Buffer.from(await res.arrayBuffer());
}

async function sendApks(tag, childAsset, parentAsset, childBufOpt = null) {
  if (apkSendInFlight) return; // boot-check aur github-sync race -> double send na ho
  apkSendInFlight = true;
  try {
    const old = state.apkMsgIds || [];
    for (const id of old) deleteMsg(id);
    state.apkMsgIds = [];
    const mb = (b) => (b.length / 1048576).toFixed(1) + ' MB';
    const childBuf = childBufOpt || await ghAsset(childAsset);
    if (childBuf.length > 1000 && childBuf[0] === 0x50 && childBuf[1] === 0x4b && childBuf.length <= 50 * 1048576) {
      const id = await sendDocument(childBuf, cfg.childAsset, `👶 Child APK — ${tag} · ${mb(childBuf)}\nNaya update — purana message delete ho gaya. Ye latest hai.`);
      state.apkMsgIds.push(id);
    } else {
      enqueue('⚠️ Child APK valid nahi lagi ya 50MB se badi hai — bheja nahi.');
    }
    await sleep(1500);
    const parentBuf = await ghAsset(parentAsset);
    if (parentBuf.length > 1000 && parentBuf[0] === 0x50 && parentBuf[1] === 0x4b && parentBuf.length <= 50 * 1048576) {
      const id = await sendDocument(parentBuf, cfg.parentAsset, `👮 Parent APK — ${tag} · ${mb(parentBuf)}\nNaya update — purana message delete ho gaya. Ye latest hai.`);
      state.apkMsgIds.push(id);
    } else {
      enqueue('⚠️ Parent APK valid nahi lagi ya 50MB se badi hai — bheja nahi.');
    }
    if (childAsset && childAsset.id) state.apkAssets.child = childAsset.id;
    if (parentAsset && parentAsset.id) state.apkAssets.parent = parentAsset.id;
    state.apkTag = tag;
    saveState();
    enqueue('📦 APKs admin ko bhej diye (' + tag + ') — purane message hat gaye.');
  } catch (e) {
    enqueue('⚠️ APK Telegram par nahi bhej paye: ' + e.message + (cfg.ghRepo ? ` — GitHub release: https://github.com/${cfg.ghRepo}/releases/latest` : ''));
  } finally {
    apkSendInFlight = false;
  }
  processLogs();
}

// syncApkFromGithub ne naya child APK download kiya -> parent bhi utha kar dono bhejo
async function notifyNewRelease(releaseJson, childBuf) {
  if (!enabled || !cfg.ghRepo) return;
  try {
    const parent = (releaseJson.assets || []).find(a => a.name === cfg.parentAsset);
    const child = (releaseJson.assets || []).find(a => a.name === cfg.childAsset);
    // Agar wahi assets pehle bhej chuke hain to dobara spam mat karo
    if (state.apkAssets.child === (child && child.id) && state.apkAssets.parent === (parent && parent.id) && (state.apkMsgIds || []).length) return;
    await sendApks(releaseJson.tag_name, child, parent, childBuf);
  } catch (e) { enqueue('⚠️ Telegram release notify fail: ' + e.message); processLogs(); }
}

// Boot par bhi check — deploy ke turant baad admin ko current APKs mil jayein (ek dafa)
async function bootCheckRelease() {
  if (!enabled || !cfg.ghRepo) return;
  try {
    const rel = await ghRelease();
    const child = (rel.assets || []).find(a => a.name === cfg.childAsset);
    const parent = (rel.assets || []).find(a => a.name === cfg.parentAsset);
    if (state.apkAssets.child === (child && child.id) && state.apkAssets.parent === (parent && parent.id) && (state.apkMsgIds || []).length) return;
    await sendApks(rel.tag_name, child, parent);
  } catch (e) { enqueue('⚠️ Boot APK check fail: ' + e.message); processLogs(); }
}

// ---------------------------------------------------------------- db backup ("storage")
function dbHash() {
  try { return crypto.createHash('sha256').update(fs.readFileSync(path.join(DATA_DIR, 'db.json'))).digest('hex'); }
  catch (e) { return ''; }
}

async function backupNow(reason, force = false) {
  if (!enabled) return;
  try {
    const dbPath = path.join(DATA_DIR, 'db.json');
    if (!fs.existsSync(dbPath)) return;
    const buf = fs.readFileSync(dbPath);
    const h = crypto.createHash('sha256').update(buf).digest('hex');
    if (!force && h === state.lastBackupHash) return; // kuch change nahi — skip
    const old = state.backupMsgId;
    const id = await sendDocument(buf, 'familyguard-backup.json',
      `💾 Data backup (${reason}) — ${new Date().toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}\nIsme poora FamilyGuard data hai (children, rules, locations, events). Restore ke liye ye file bot ko bhej do.`);
    state.backupMsgId = id;
    state.lastBackupHash = h;
    state.lastBackupAt = Date.now();
    saveState();
    if (old) deleteMsg(old); // "purane hat ke" — sirf latest backup message rahe
  } catch (e) {
    enqueue('⚠️ Backup fail: ' + e.message);
  }
  processLogs();
}

// store.save() hook — data change hone par 90s me ek backup
function onDbSaved() {
  if (!enabled) return;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => backupNow('auto'), 90000);
}

// ---------------------------------------------------------------- restore (admin file bhejo)
async function restoreFromTelegram(fileId) {
  try {
    const f = await call('getFile', { file_id: fileId });
    if (!f.ok || !f.result.file_path) throw new Error('getFile failed');
    const url = `https://api.telegram.org/file/bot${TOKEN}/${f.result.file_path}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error('download HTTP ' + res.status);
    const text = await res.text();
    const r = store.restoreFromJson(text);
    if (!r.ok) throw new Error(r.error);
    enqueue('✅ Restore ho gaya: ' + r.parents + ' parents, ' + r.children + ' children. Server restart ho raha hai…');
    await processLogs();
    setTimeout(() => process.exit(0), 2500); // Coolify wapas utha lega, naya db load hoga
  } catch (e) {
    enqueue('❌ Restore fail: ' + e.message);
    processLogs();
  }
}

// ---------------------------------------------------------------- commands + poller
async function handleUpdate(upd) {
  const m = upd.message;
  if (!m || String(m.chat.id) !== ADMIN) return; // sirf admin
  if (m.document && /\.json$/i.test(m.document.file_name || '')) {
    return restoreFromTelegram(m.document.file_id);
  }
  const text = String(m.text || '').trim();
  if (!text.startsWith('/')) return;
  const cmd = text.split(/\s+/)[0].split('@')[0];
  try {
    if (cmd === '/start' || cmd === '/help') {
      await call('sendMessage', { chat_id: ADMIN, text:
        '🛡 FamilyGuard bot linked.\n\n/status — server ki halat\n/apk — latest child+parent APK wapas bhejo\n/backup — abhi data backup bhejo\ndb.json document bhejo — data restore (auto restart)\n\n📦 Har naye build par APK yahin aayega (purane delete).\n💾 Data ka backup bhi yahin rehta hai.' });
    } else if (cmd === '/status') {
      const st = cfg.getStatus() || {};
      const children = st.children || [];
      const online = children.filter(c => c.online).length;
      const apk = st.apk || {};
      await call('sendMessage', { chat_id: ADMIN, text:
        `🖥 FamilyGuard status\nUptime: ${Math.floor((st.uptime || 0) / 60)} min\nChildren: ${online}/${children.length} online\nAPK: ${apk.uploaded ? (apk.source || '') + ' ' + (apk.releaseTag || '') + ' · ' + ((apk.size || 0) / 1048576).toFixed(1) + ' MB' : 'not synced yet'}\nLast backup: ${state.lastBackupAt ? new Date(state.lastBackupAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' }) : '—'}\nServer: ${st.origin || ''}` });
    } else if (cmd === '/apk') {
      const rel = await ghRelease();
      const child = (rel.assets || []).find(a => a.name === cfg.childAsset);
      const parent = (rel.assets || []).find(a => a.name === cfg.parentAsset);
      await sendApks(rel.tag_name, child, parent);
    } else if (cmd === '/backup') {
      await backupNow('manual', true);
      await call('sendMessage', { chat_id: ADMIN, text: '💾 Backup bhej diya.' });
    }
  } catch (e) {
    enqueue('⚠️ Command fail: ' + e.message);
    processLogs();
  }
}

async function poller() {
  while (enabled && pollerStarted) {
    try {
      const j = await call('getUpdates', { timeout: 25, offset: pollOffset, allowed_updates: ['message'] }, 35000);
      if (j && j.ok && Array.isArray(j.result)) {
        for (const u of j.result) { pollOffset = u.update_id + 1; await handleUpdate(u).catch(() => {}); }
      } else if (j && j.error_code === 409) {
        console.error('[tg] getUpdates conflict — poller band');
        return;
      }
    } catch (e) { await sleep(5000); }
  }
}

// ---------------------------------------------------------------- init
async function init(options = {}) {
  cfg = Object.assign(cfg, options);
  if (!TOKEN) { console.error('[tg] TELEGRAM_BOT_TOKEN nahi hai — Telegram off'); return; }
  loadState();
  try {
    const me = await call('getMe', {}, 15000);
    if (!me || !me.ok) throw new Error(me && me.description ? me.description : 'getMe failed');
    enabled = true;
    console.log(`[tg] linked: @${me.result.username} -> admin ${ADMIN}`);
    enqueue(`🚀 FamilyGuard backend online — Telegram linked (@${me.result.username}). APKs, logs aur data backup yahin aayenge.`);
    processLogs();
    if (!pollerStarted) { pollerStarted = true; poller().catch(() => {}); }
    bootCheckRelease();
  } catch (e) {
    enabled = false;
    console.error('[tg] Telegram link fail (server chalta rahega):', e.message);
  }
}

function isEnabled() { return enabled; }

module.exports = { init, log, error, notifyNewRelease, onDbSaved, backupNow, isEnabled, sendApks };
