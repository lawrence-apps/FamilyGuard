const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const store = require('./store');

// JWT secret: env override > persisted file > generated once
let SECRET = process.env.JWT_SECRET;
if (!SECRET) {
  fs.mkdirSync(store.DATA_DIR, { recursive: true });
  const f = path.join(store.DATA_DIR, 'jwt.secret');
  try {
    SECRET = fs.readFileSync(f, 'utf8').trim();
  } catch (e) {
    SECRET = crypto.randomBytes(48).toString('hex');
    fs.writeFileSync(f, SECRET, { mode: 0o600 });
  }
}

function sign(payload) {
  return jwt.sign(payload, SECRET, { expiresIn: '365d' });
}

function verify(token) {
  return jwt.verify(token, SECRET);
}

function auth(req, res, next) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : null;
  if (!t) return res.status(401).json({ error: 'no token' });
  try {
    req.auth = verify(t);
    next();
  } catch (e) {
    return res.status(401).json({ error: 'bad token' });
  }
}

function parentOnly(req, res, next) {
  if (req.auth.role !== 'parent') return res.status(403).json({ error: 'parent only' });
  next();
}

// Child with matching cid, or any parent
function childOrParent(req, res, next) {
  const cid = req.params.cid || req.params.id;
  if (req.auth.role === 'parent') return next();
  if (req.auth.role === 'child' && req.auth.cid === cid) return next();
  return res.status(403).json({ error: 'forbidden' });
}

function childOnly(cid, authInfo) {
  return authInfo.role === 'child' && authInfo.cid === cid;
}

function rid() {
  return crypto.randomBytes(8).toString('hex');
}

function code6() {
  return String(crypto.randomInt(0, 1000000)).padStart(6, '0');
}

module.exports = { sign, verify, auth, parentOnly, childOrParent, childOnly, rid, code6, SECRET };
