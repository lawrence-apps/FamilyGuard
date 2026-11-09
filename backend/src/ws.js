const WebSocket = require('ws');
const store = require('./store');
const { verify } = require('./auth');

const db = () => store.db;
let wss = null;

const childSockets = new Map(); // cid -> Set<ws>
const parentSockets = new Set();

function isOnline(cid) {
  const s = childSockets.get(cid);
  return !!(s && s.size > 0);
}

function sendToChild(cid, obj) {
  const s = childSockets.get(cid);
  if (!s) return false;
  const text = JSON.stringify(obj);
  let ok = false;
  for (const ws of s) {
    if (ws.readyState === WebSocket.OPEN) { ws.send(text); ok = true; }
  }
  return ok;
}

// Device record delete hone par child ke sab sockets band kar do — app ka
// WsChild turant reconnect karega aur orphan guard use release_protections
// bhej dega (offline delete ka instant cleanup).
function closeChild(cid) {
  const s = childSockets.get(cid);
  if (!s) return;
  for (const ws of s) { try { ws.close(4002, 'removed'); } catch (e) {} }
  childSockets.delete(cid);
}

function broadcastToParents(obj) {
  const text = JSON.stringify(obj);
  for (const ws of parentSockets) {
    if (ws.readyState === WebSocket.OPEN) ws.send(text);
  }
}

function publicChild(c) {
  return {
    id: c.id, name: c.name, dob: c.dob || '', paired: !!c.paired,
    pairingCode: c.pairingCode, online: isOnline(c.id), lastSeen: c.lastSeen || 0,
    battery: c.battery == null ? -1 : c.battery, charging: !!c.charging,
    deviceName: c.deviceName || '', model: c.model || '', currentApp: c.currentApp || '',
    health: c.health || null
  };
}

function defaultRules() {
  return {
    dailyScreenTimeMin: 0,
    bedtime: { enabled: false, start: '21:30', end: '07:00' },
    schedules: [],
    blockedApps: [],
    appLimits: {},
    alwaysAllowed: ['com.familyguard.child', 'com.android.dialer', 'com.android.phone', 'com.android.settings']
  };
}

// REST helpers shared with server.js -----------------------------------------
function storeLocation(cid, p) {
  if (!db().locations[cid]) db().locations[cid] = [];
  const arr = db().locations[cid];
  arr.push(p);
  if (arr.length > 1000) arr.splice(0, arr.length - 1000);
  const c = db().children.find(x => x.id === cid);
  if (c) { c.lastSeen = p.t; if (typeof p.battery === 'number') c.battery = p.battery; }
  store.save();
  broadcastToParents({ type: 'location', cid, point: p });
}

function storeNotification(cid, n) {
  store.pushList(db().notifications, cid, n, 500);
  const c = db().children.find(x => x.id === cid);
  if (c) c.lastSeen = n.date || Date.now();
  store.save();
  broadcastToParents({ type: 'notification', cid, notif: n });
}

function storeEvent(cid, ev) {
  store.pushList(db().events, cid, ev, 500);
  const c = db().children.find(x => x.id === cid);
  if (c) c.lastSeen = ev.t;
  store.save();
  broadcastToParents({ type: 'event', cid, event: ev });
}

function handleChildMessage(cid, msg, raw) {
  const c = db().children.find(x => x.id === cid);
  if (!c) return;
  switch (msg.type) {
    case 'status': {
      c.lastSeen = Date.now();
      if (typeof msg.battery === 'number') c.battery = msg.battery;
      if (typeof msg.charging === 'boolean') c.charging = msg.charging;
      if (typeof msg.currentApp === 'string') c.currentApp = msg.currentApp;
      if (msg.health && typeof msg.health === 'object' && !Array.isArray(msg.health)) c.health = msg.health;
      store.save();
      broadcastToParents({ type: 'child_status', cid, online: true, battery: c.battery, charging: c.charging, currentApp: c.currentApp, health: c.health || null, lastSeen: c.lastSeen });
      break;
    }
    case 'location': {
      storeLocation(cid, { lat: msg.lat, lng: msg.lng, accuracy: msg.accuracy || 0, speed: msg.speed || 0, t: Date.now(), battery: msg.battery == null ? -1 : msg.battery });
      break;
    }
    case 'notification': {
      storeNotification(cid, { pkg: msg.pkg || '', title: msg.title || '', text: msg.text || '', date: msg.date || Date.now() });
      break;
    }
    case 'event':
    // GeofenceChecker sends the event type directly as msg.type over WS
    // (e.g. "geofence_enter" / "geofence_exit") — without these cases the
    // messages hit default and were silently dropped (no live parent alert).
    case 'geofence_enter':
    case 'geofence_exit': {
      const type = msg.type === 'event' ? (msg.eventType || 'unknown') : msg.type;
      storeEvent(cid, { id: require('./auth').rid(), type, data: msg.data || {}, t: msg.t || Date.now() });
      break;
    }
    case 'command_result': {
      broadcastToParents({ type: 'command_result', cid, id: msg.id, ok: !!msg.ok, data: msg.data || {} });
      break;
    }
    case 'apps': {
      broadcastToParents({ type: 'apps', cid, apps: Array.isArray(msg.apps) ? msg.apps : [] });
      break;
    }
    case 'mirror_state': {
      // Real mirroring state from the child device (streaming started/stopped,
      // consent granted/lost). Parents use it to show a truthful LIVE indicator.
      broadcastToParents({ type: 'mirror_state', cid, streaming: !!msg.streaming });
      break;
    }
    default:
      break;
  }
}

function handleParentMessage(ws, msg) {
  switch (msg.type) {
    case 'ping':
      ws.send(JSON.stringify({ type: 'pong', t: Date.now() }));
      break;
    case 'watch_mirror': {
      // Parent declares which child's mirror frames it wants. null/'' = all
      // (back-compat with older clients that never subscribe).
      ws.watchCid = msg.cid || null;
      break;
    }
    case 'mirror_start': {
      const cid = msg.cid;
      if (!cid) return;
      const cmd = { id: require('./auth').rid(), action: 'mirror_start', params: { fps: 8, quality: 60, maxWidth: 720 } };
      if (!sendToChild(cid, { type: 'command', id: cmd.id, action: cmd.action, params: cmd.params })) {
        if (!db().commandQueue[cid]) db().commandQueue[cid] = [];
        db().commandQueue[cid].push(cmd);
        store.save();
      }
      break;
    }
    case 'mirror_stop': {
      const cid = msg.cid;
      if (!cid) return;
      sendToChild(cid, { type: 'command', id: require('./auth').rid(), action: 'mirror_stop', params: {} });
      break;
    }
    default:
      break;
  }
}

function attach(server) {
  wss = new WebSocket.Server({ server, path: '/ws' });

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://x');
    const token = url.searchParams.get('token') || '';
    let info;
    try {
      info = verify(token);
    } catch (e) {
      ws.close(4001, 'bad token');
      return;
    }

    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    if (info.role === 'child') {
      const cid = info.cid;
      if (!childSockets.has(cid)) childSockets.set(cid, new Set());
      childSockets.get(cid).add(ws);
      ws.cid = cid;
      console.log(`[ws] child ${cid} connected`);

      const c = db().children.find(x => x.id === cid);
      if (!c) {
        // ORPHAN GUARD: "Remove device" deleted this record while the phone was
        // offline, so it never got the release command. Push it now — the child
        // app strips every permission and fires the uninstall dialog by itself.
        ws.send(JSON.stringify({ type: 'command', id: require('./auth').rid(), action: 'release_protections', params: {} }));
      }
      const rules = db().rules[cid] || defaultRules();
      const fences = db().geofences[cid] || [];
      ws.send(JSON.stringify({ type: 'hello', rules, geofences: fences, child: c ? { id: c.id, name: c.name, dob: c.dob || '' } : null }));

      // flush queued commands
      const q = db().commandQueue[cid] || [];
      for (const cmd of q) {
        ws.send(JSON.stringify({ type: 'command', id: cmd.id, action: cmd.action, params: cmd.params || {} }));
      }
      if (q.length) { db().commandQueue[cid] = []; store.save(); }

      broadcastToParents({ type: 'child_status', cid, online: true, battery: c ? c.battery : -1, charging: c ? !!c.charging : false, currentApp: c ? c.currentApp : '', lastSeen: c ? c.lastSeen : 0 });

      ws.on('message', (data, isBinary) => {
        if (isBinary) {
          // Mirror frame -> forward raw to parents watching THIS child. Without the
          // watch filter, two children mirroring at once would mix frames on screen.
          // Parents that never subscribed (watchCid == null) still get everything,
          // so older parent apps / dashboards keep working.
          for (const p of parentSockets) {
            if (p.readyState === WebSocket.OPEN && (p.watchCid == null || p.watchCid === cid)) {
              p.send(data);
            }
          }
          return;
        }
        let msg;
        try { msg = JSON.parse(data.toString()); } catch (e) { return; }
        try { handleChildMessage(cid, msg, msg); } catch (e) { console.error('[ws] child msg error:', e.message); }
      });

      ws.on('close', () => {
        const set = childSockets.get(cid);
        if (set) {
          set.delete(ws);
          if (set.size === 0) {
            childSockets.delete(cid);
            const c2 = db().children.find(x => x.id === cid);
            broadcastToParents({ type: 'child_status', cid, online: false, battery: c2 ? c2.battery : -1, charging: false, currentApp: '', lastSeen: Date.now() });
            console.log(`[ws] child ${cid} disconnected`);
          }
        }
      });
    } else if (info.role === 'parent') {
      parentSockets.add(ws);
      console.log('[ws] parent connected');
      ws.on('message', (data) => {
        let msg;
        try { msg = JSON.parse(data.toString()); } catch (e) { return; }
        try { handleParentMessage(ws, msg); } catch (e) { console.error('[ws] parent msg error:', e.message); }
      });
      ws.on('close', () => { parentSockets.delete(ws); console.log('[ws] parent disconnected'); });
    } else {
      ws.close(4001, 'bad role');
    }
  });

  const interval = setInterval(() => {
    if (!wss) return;
    wss.clients.forEach(ws => {
      if (ws.isAlive === false) { try { ws.terminate(); } catch (e) {} return; }
      ws.isAlive = false;
      try { ws.ping(); } catch (e) {}
    });
  }, 30000);
  wss.on('close', () => clearInterval(interval));
}

module.exports = { attach, isOnline, sendToChild, closeChild, broadcastToParents, publicChild, defaultRules, storeLocation, storeNotification, storeEvent, handleChildMessage };
