// Tiny JSON-file store — zero native deps, perfect for a single-family self-hosted box.
const fs = require('fs');
const path = require('path');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const DB_PATH = path.join(DATA_DIR, 'db.json');
const MEDIA_DIR = path.join(DATA_DIR, 'media');

const DEFAULTS = {
  parents: [],
  children: [],
  rules: {},        // cid -> rules
  usage: {},        // cid -> { date -> {pkg:{min,label}} }
  locations: {},    // cid -> [points]
  geofences: {},    // cid -> [fences]
  events: {},       // cid -> [events]
  calls: {},        // cid -> [calls]
  sms: {},          // cid -> [sms]
  notifications: {},// cid -> [notifs]
  commandQueue: {}, // cid -> [commands]
  media: []
};

let db = null;

function load() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
  if (fs.existsSync(DB_PATH)) {
    try {
      db = JSON.parse(fs.readFileSync(DB_PATH, 'utf8'));
    } catch (e) {
      console.error('[store] db.json corrupt, starting fresh:', e.message);
      db = null;
    }
  }
  if (!db) db = {};
  for (const k of Object.keys(DEFAULTS)) {
    if (db[k] === undefined) db[k] = JSON.parse(JSON.stringify(DEFAULTS[k]));
  }
  return db;
}

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const tmp = DB_PATH + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(db));
      fs.renameSync(tmp, DB_PATH);
    } catch (e) {
      console.error('[store] save failed:', e.message);
    }
  }, 250);
}

// Housekeeping helpers -------------------------------------------------------
function pushList(map, cid, item, cap = 500) {
  if (!map[cid]) map[cid] = [];
  map[cid].unshift(item);
  if (map[cid].length > cap) map[cid].length = cap;
}

function purgeChild(cid) {
  delete db.rules[cid];
  delete db.usage[cid];
  delete db.locations[cid];
  delete db.geofences[cid];
  delete db.events[cid];
  delete db.calls[cid];
  delete db.sms[cid];
  delete db.notifications[cid];
  delete db.commandQueue[cid];
  db.children = db.children.filter(c => c.id !== cid);
}

module.exports = { load, save, purgeChild, pushList, DATA_DIR, MEDIA_DIR, get db() { return db; } };
