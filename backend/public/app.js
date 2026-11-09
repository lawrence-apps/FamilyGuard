/* FamilyGuard web dashboard — vanilla JS, no build step. */
"use strict";

const S = {
  token: localStorage.getItem("fg_token") || "",
  origin: location.origin,
  children: [],
  cid: null,
  ws: null,
  wsTimer: null,
  pollTimer: null,
  rules: null,
  usage: null,
  apps: [],            // installed apps reported by child (WS "apps")
  mirrorOn: false,
  frameCount: 0,
  lastFpsAt: 0,
  map: null,
  marker: null,
  track: [],
  trackLine: null,
  fences: [],
  fenceLayer: null,
  addingFence: false,
  events: [],
  notifs: [],
};

const $ = (id) => document.getElementById(id);

/* ---------------- helpers ---------------- */
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}
function timeAgo(ts) {
  if (!ts) return "—";
  const d = Math.floor((Date.now() - ts) / 1000);
  if (d < 60) return d + "s ago";
  if (d < 3600) return Math.floor(d / 60) + " min ago";
  if (d < 86400) return Math.floor(d / 3600) + " h ago";
  return Math.floor(d / 86400) + " d ago";
}
function hhmm(ts) { return new Date(ts).toLocaleString(); }

async function api(path, method, body) {
  const res = await fetch(S.origin + path, {
    method: method || "GET",
    headers: {
      "Content-Type": "application/json",
      ...(S.token ? { Authorization: "Bearer " + S.token } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { raw: text }; }
  if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
  return data;
}

function dialog(title, bodyHtml, onOk) {
  $("dlg-title").textContent = title;
  $("dlg-body").innerHTML = bodyHtml;
  $("dlg").classList.remove("hidden");
  $("dlg-ok").onclick = async () => {
    if (onOk) {
      try { await onOk(); } catch (e) { alert(e.message); return; }
    }
    $("dlg").classList.add("hidden");
  };
  $("dlg-cancel").onclick = () => $("dlg").classList.add("hidden");
}
function dlgVal(id) { const el = $("dlg-body").querySelector("#" + id); return el ? el.value : ""; }

function toast(msg) {
  $("cmd-status").textContent = msg;
  $("cmd-status").classList.remove("hidden");
  $("media-card").classList.remove("hidden");
  setTimeout(() => { $("cmd-status").textContent = ""; }, 6000);
}

/* ---------------- auth ---------------- */
$("a-origin").textContent = S.origin;
$("about-origin").textContent = S.origin;

$("a-login").onclick = async () => {
  $("a-err").textContent = "";
  try {
    const r = await api("/api/parent/login", "POST", { username: $("a-user").value.trim(), password: $("a-pass").value });
    S.token = r.token; localStorage.setItem("fg_token", S.token);
    enterApp();
  } catch (e) { $("a-err").textContent = e.message; }
};
$("a-reg").onclick = async () => {
  $("a-err").textContent = "";
  try {
    const r = await api("/api/parent/register", "POST", { username: $("a-user").value.trim(), password: $("a-pass").value });
    S.token = r.token; localStorage.setItem("fg_token", S.token);
    enterApp();
  } catch (e) { $("a-err").textContent = e.message; }
};
$("logout").onclick = () => {
  localStorage.removeItem("fg_token");
  location.reload();
};

/* ---------------- app boot ---------------- */
async function enterApp() {
  $("auth").classList.add("hidden");
  $("app").classList.remove("hidden");
  await refreshChildren();
  connectWs();
  S.pollTimer = setInterval(refreshChildren, 15000);
  loadAlerts();
  loadApk();
  if (!S.children.length) showCreateChild();
}

/* ---------------- children ---------------- */
async function refreshChildren() {
  try {
    const r = await api("/api/children");
    S.children = r.children || [];
    if (!S.children.some((c) => c.id === S.cid)) {
      S.cid = S.children.length ? S.children[0].id : null;
    }
    renderChildSelect();
    renderStatus();
    renderPairCard();
    renderAdultCard();
    if ($("tab-apps").classList.contains("hidden") === false) loadApps();
  } catch (e) {
    if (String(e.message).includes("401") || String(e.message).includes("bad token")) {
      localStorage.removeItem("fg_token"); location.reload();
    }
  }
}

function renderChildSelect() {
  const sel = $("child-sel");
  sel.innerHTML = S.children.map((c) =>
    '<option value="' + esc(c.id) + '"' + (c.id === S.cid ? " selected" : "") + ">" +
    esc(c.name) + (c.online ? " 🟢" : "") + "</option>").join("");
  sel.onchange = () => {
    S.cid = sel.value;
    renderStatus(); renderPairCard(); renderAdultCard();
    loadRules(); loadApps(); loadLocations(); loadFences(); loadAlerts();
  };
}

function activeChild() { return S.children.find((c) => c.id === S.cid) || null; }

function renderStatus() {
  const c = activeChild();
  if (!c) {
    $("s-online").textContent = "—"; $("s-batt").textContent = "—";
    $("s-app").textContent = "—"; $("s-seen").textContent = "—";
    return;
  }
  $("s-online").innerHTML = c.online
    ? '<span class="dot on"></span> Online' : '<span class="dot off"></span> Offline';
  $("s-batt").textContent = c.battery >= 0 ? c.battery + "%" + (c.charging ? " ⚡" : "") : "—";
  $("s-app").textContent = c.currentApp || "—";
  $("s-seen").textContent = timeAgo(c.lastSeen);
  const h = c.health;
  const labels = {
    service: "Background service", accessibility: "Accessibility", admin: "Device admin",
    location: "Location", usageAccess: "Usage access", overlay: "Overlay",
    camera: "Camera", notifListener: "Notifications"
  };
  const broken = h ? Object.keys(labels).filter((k) => h[k] === false).map((k) => labels[k]) : [];
  const el = $("s-health");
  if (broken.length) {
    el.classList.remove("hidden");
    el.innerHTML = "<b>Needs repair:</b> " + broken.join(", ") +
      ' — the child device self-heals; <button class="btn small" id="heal-btn">Auto-Repair</button>';
    const hb = document.getElementById("heal-btn");
    if (hb) hb.onclick = () => sendCmd("heal", {});
  } else {
    el.classList.add("hidden");
  }
}

function renderPairCard() {
  const c = activeChild();
  const show = !c || !c.paired;
  $("pair-card").classList.toggle("hidden", !show);
  const qrEl = $("pair-qr");
  if (!show || !c) { qrEl.classList.add("hidden"); if (show) $("pair-code").textContent = "Create a child first"; return; }
  $("pair-code").textContent = c.pairingCode;
  // QR setup link: scanning it opens the child app with server+code prefilled and pairs automatically.
  try {
    const link = S.origin + "/s/" + c.pairingCode;
    const q = qrcode(0, "M");
    q.addData(link);
    q.make();
    qrEl.classList.remove("hidden");
    qrEl.innerHTML =
      '<p class="muted small" style="margin:2px 0">⚡ <b>Child phone se ye QR scan karo</b> — app khud khul kar server + code daal kar pair ho jayegi</p>' +
      q.createSvgTag({ cellSize: 4, margin: 2, scalable: true }) +
      '<p class="muted small" style="word-break:break-all">' + esc(link) + "</p>";
  } catch (e) { qrEl.classList.add("hidden"); }
}

function renderAdultCard() {
  const c = activeChild();
  const age = c ? ageYears(c.dob) : -1;
  $("adult-card").classList.toggle("hidden", !(age >= 18));
}

function ageYears(dob) {
  if (!dob || !/^\d{4}-\d{2}-\d{2}$/.test(dob)) return -1;
  const b = new Date(dob + "T00:00:00");
  if (isNaN(b)) return -1;
  const now = new Date();
  let a = now.getFullYear() - b.getFullYear();
  const m = now.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < b.getDate())) a--;
  return a;
}

function showCreateChild() {
  dialog("Add child", `
    <label>Name</label><input id="dc-name" placeholder="Child name">
    <label>Date of birth (YYYY-MM-DD)</label><input id="dc-dob" placeholder="2008-06-15">
    <p class="muted small">18 hone par protections auto-release ho jayengi.</p>`,
    async () => {
      const name = dlgVal("dc-name").trim();
      const dob = dlgVal("dc-dob").trim();
      if (!name) throw new Error("Name required");
      const r = await api("/api/children", "POST", { name, dob });
      S.cid = r.child.id;
      await refreshChildren();
      loadRules(); loadApps(); loadLocations(); loadFences(); loadAlerts();
    });
}
$("new-child").onclick = showCreateChild;
$("new-child-2").onclick = showCreateChild;

$("regen-code").onclick = async () => {
  if (!S.cid) return;
  const r = await api("/api/children/" + S.cid + "/regenerate-code", "POST", {});
  $("pair-code").textContent = r.pairingCode;
};

function renderChildrenList() {
  $("children-list").innerHTML = S.children.map((c) =>
    '<li><div><b>' + esc(c.name) + '</b> <span class="meta">' + (c.paired ? esc(c.deviceName || "paired") : "code: " + esc(c.pairingCode)) +
    '</span></div><button class="x" data-del="' + esc(c.id) + '">🗑</button></li>').join("") ||
    '<li class="muted">No children yet</li>';
  $("children-list").querySelectorAll("[data-del]").forEach((b) => {
    b.onclick = () => dialog("Delete child?", "<p>This removes all synced data.</p>", async () => {
      await api("/api/children/" + b.dataset.del, "DELETE");
      S.cid = null;
      await refreshChildren();
    });
  });
}

/* ---------------- child apk share ---------------- */
async function loadApk() {
  try {
    renderApk(await api("/api/apk"));
  } catch (e) {}
}

function renderApk(st) {
  S.apk = st;
  const has = !!(st && st.uploaded);
  const isGh = has && st.source === "github";
  $("apk-upload").classList.toggle("hidden", has);
  ["apk-replace", "apk-copy", "apk-share"].forEach((id) => $(id).classList.toggle("hidden", !has));
  $("apk-sync").classList.remove("hidden");
  // GitHub-synced APK is auto-managed — Remove sirf manually-uploaded APK ke liye
  $("apk-delete").classList.toggle("hidden", !has || isGh);
  if (has) {
    const url = S.origin + st.url;
    const mb = st.size ? " · " + (st.size / 1048576).toFixed(1) + " MB" : "";
    if (isGh) {
      $("apk-hint").textContent = "✅ GitHub se auto-sync: " + (st.releaseTag || "latest") + mb + ". Har naye build ke baad APK khud update ho jati hai — link/QR same rehta hai, kuch karne ki zarurat nahi.";
    } else {
      $("apk-hint").textContent = "📦 Manually uploaded" + mb + ". Auto-sync isko replace nahi karega — GitHub build wapas chahiye to \"Sync now\" dabao.";
    }
    $("apk-link").classList.remove("hidden");
    $("apk-link").textContent = url;
    try {
      const q = qrcode(0, "M");
      q.addData(url);
      q.make();
      $("apk-qr").classList.remove("hidden");
      $("apk-qr").innerHTML = q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
    } catch (e) { $("apk-qr").classList.add("hidden"); }
  } else {
    $("apk-hint").textContent = (st && st.githubTokenSet === false)
      ? "APK GitHub se auto-sync hogi, par server par GITHUB_TOKEN set nahi hai — Coolify → Environment me GITHUB_TOKEN (GitHub PAT) add karke Redeploy karo. Tab tak \"Upload APK\" se manually de sakte ho."
      : "APK GitHub se apne aap aayegi — CI build ke baad khud sync ho jati hai (har 10 min check). \"Sync now\" se turant try karo, ya fallback me manually upload karo.";
    $("apk-link").classList.add("hidden");
    $("apk-qr").classList.add("hidden");
  }
}

$("apk-sync").onclick = async () => {
  $("apk-sync").disabled = true;
  $("apk-hint").textContent = "🔄 GitHub se sync kar rahe hain…";
  try {
    // raw fetch (api() 502 par throw karta hai aur sync.reason kho deta hai)
    const res = await fetch(S.origin + "/api/apk/sync", {
      method: "POST",
      headers: { Authorization: "Bearer " + S.token }
    });
    const st = await res.json().catch(() => ({}));
    renderApk(st);
    if (st.sync && st.sync.ok) {
      toast("GitHub se sync ho gaya ✓" + (st.releaseTag ? " (" + st.releaseTag + ")" : ""));
    } else {
      const why = (st.sync && st.sync.reason) || "HTTP " + res.status;
      $("apk-hint").textContent = "⚠ Sync nahi hua: " + why;
    }
  } catch (e) {
    $("apk-hint").textContent = "Sync failed: " + e.message;
  } finally {
    $("apk-sync").disabled = false;
  }
};

$("apk-upload").onclick = () => $("apk-file").click();
$("apk-replace").onclick = () => $("apk-file").click();
$("apk-file").onchange = async () => {
  const f = $("apk-file").files[0];
  if (!f) return;
  $("apk-hint").textContent = "Uploading " + f.name + " …";
  try {
    const fd = new FormData();
    fd.append("file", f);
    const res = await fetch(S.origin + "/api/apk", {
      method: "POST",
      headers: { Authorization: "Bearer " + S.token },
      body: fd
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || "HTTP " + res.status);
    renderApk(data);
    toast("Child APK uploaded ✓");
  } catch (e) {
    $("apk-hint").textContent = "Upload failed: " + e.message;
  } finally {
    $("apk-file").value = "";
  }
};
$("apk-copy").onclick = async () => {
  const url = S.origin + (S.apk && S.apk.url ? S.apk.url : "");
  try { await navigator.clipboard.writeText(url); toast("Link copied ✓"); }
  catch (e) { prompt("Copy this link:", url); }
};
$("apk-share").onclick = async () => {
  const url = S.origin + (S.apk && S.apk.url ? S.apk.url : "");
  if (navigator.share) {
    try { await navigator.share({ title: "FamilyGuard Child App", text: "FamilyGuard child app install karo — ye link child phone par kholo:", url }); } catch (e) {}
  } else {
    window.open("https://wa.me/?text=" + encodeURIComponent("FamilyGuard child app install karo — ye link child phone par kholo: " + url), "_blank");
  }
};
$("apk-delete").onclick = () => {
  dialog("Remove uploaded APK?", "<p>Install link band ho jayega. Child phone par pehle se installed app par koi asar nahi hoga. Next auto-sync par GitHub build wapas aa sakta hai.</p>", async () => {
    await api("/api/apk", "DELETE");
    renderApk({ uploaded: false });
  });
};

/* ---------------- websocket ---------------- */
function connectWs() {
  if (!S.token) return;
  try { if (S.ws) { S.ws.onclose = null; S.ws.close(); } } catch (e) {}
  const proto = S.origin.startsWith("https") ? "wss://" : "ws://";
  const host = S.origin.replace(/^https?:\/\//, "");
  const ws = new WebSocket(proto + host + "/ws?token=" + encodeURIComponent(S.token));
  S.ws = ws;
  ws.binaryType = "arraybuffer";

  ws.onopen = () => { $("conn").className = "dot on"; };
  ws.onclose = () => {
    $("conn").className = "dot off";
    clearTimeout(S.wsTimer);
    S.wsTimer = setTimeout(connectWs, 3000);
  };
  ws.onmessage = (ev) => {
    if (ev.data instanceof ArrayBuffer) { onMirrorFrame(ev.data); return; }
    let m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    handleWs(m);
  };
}

function handleWs(m) {
  switch (m.type) {
    case "child_status": {
      const c = S.children.find((x) => x.id === m.cid);
      if (c) {
        c.online = m.online; c.battery = m.battery; c.charging = m.charging; c.currentApp = m.currentApp;
        if (m.health) c.health = m.health;
      }
      renderStatus(); renderChildSelect();
      break;
    }
    case "location": {
      if (m.cid !== S.cid || !m.point) break;
      addMapPoint(m.point);
      break;
    }
    case "notification": {
      if (m.cid === S.cid && m.notif) { S.notifs.unshift(m.notif); renderNotifs(); }
      break;
    }
    case "event": {
      if (m.cid === S.cid && m.event) { S.events.unshift(m.event); renderEvents(); }
      break;
    }
    case "command_result": {
      if (m.cid !== S.cid) break;
      const d = m.data || {};
      if (m.ok && d.url) {
        $("media-card").classList.remove("hidden");
        $("media-img").src = S.origin + d.url;
        toast("Capture received ✓");
      } else if (m.ok && d.lat != null && d.lng != null) {
        toast("Command done ✓ (" + Number(d.lat).toFixed(5) + ", " + Number(d.lng).toFixed(5) + ")");
      } else if (m.ok) {
        toast("Command done ✓");
      } else {
        toast("Command failed: " + (d.error || "unknown"));
      }
      break;
    }
    case "apps": {
      if (m.cid === S.cid) { S.apps = m.apps || []; renderApps(); }
      break;
    }
    case "pairing": { refreshChildren(); break; }
    default: break;
  }
}

/* ---------------- commands ---------------- */
async function sendCmd(action, params) {
  if (!S.cid) { alert("Create / select a child first"); return; }
  try {
    await api("/api/commands", "POST", { cid: S.cid, action, params: params || {} });
    toast("Sent: " + action);
  } catch (e) { alert(e.message); }
}

$("quick-grid").querySelectorAll("[data-cmd]").forEach((b) => {
  b.onclick = () => {
    const c = b.dataset.cmd;
    if (c === "lock") {
      dialog("Lock device", '<label>Message shown on child screen</label><input id="dl-msg" value="Locked by parent">',
        () => sendCmd("lock", { message: dlgVal("dl-msg") }));
    } else if (c === "message") {
      dialog("Send message", '<label>Title</label><input id="dm-title"><label>Text</label><input id="dm-text">',
        () => sendCmd("message", { title: dlgVal("dm-title"), text: dlgVal("dm-text") }));
    } else if (c === "cam-front") {
      sendCmd("camera", { facing: "front" });
    } else if (c === "cam-back") {
      sendCmd("camera", { facing: "back" });
    } else {
      sendCmd(c);
    }
  };
});

function releaseProtections() {
  dialog("Release protections?",
    "<p>Child device par accessibility + admin disable hoga, monitoring band hogi aur system uninstall dialog khulega. Continue?</p>",
    () => sendCmd("release_protections"));
}
$("release-btn").onclick = releaseProtections;
$("release-btn-2").onclick = releaseProtections;

/* ---------------- rules (screen time) ---------------- */
async function loadRules() {
  if (!S.cid) return;
  try {
    S.rules = await api("/api/rules/" + S.cid);
  } catch (e) { return; }
  const r = S.rules;
  $("r-daily").value = r.dailyScreenTimeMin || 0;
  $("r-bed-en").checked = !!(r.bedtime && r.bedtime.enabled);
  $("r-bed-start").value = (r.bedtime && r.bedtime.start) || "21:30";
  $("r-bed-end").value = (r.bedtime && r.bedtime.end) || "07:00";
  renderChipList("r-blocked", r.blockedApps || [], (pkg) => { r.blockedApps = r.blockedApps.filter((x) => x !== pkg); });
  renderLimits();
  renderChipList("r-allowed", r.alwaysAllowed || [], (pkg) => { r.alwaysAllowed = r.alwaysAllowed.filter((x) => x !== pkg); });
  loadUsage();
}

function renderChipList(id, arr, onRemove) {
  $(id).innerHTML = arr.map((pkg) =>
    "<li><span>" + esc(pkg) + '</span><button class="x">✕</button></li>').join("") ||
    '<li class="muted">Nothing added</li>';
  $(id).querySelectorAll(".x").forEach((btn, i) => { btn.onclick = () => { onRemove(arr[i]); renderChipList(id, arr, onRemove); }; });
}

function renderLimits() {
  const r = S.rules;
  const keys = Object.keys(r.appLimits || {});
  $("r-limits").innerHTML = keys.map((pkg) =>
    "<li><span>" + esc(pkg) + ' <span class="meta">' + r.appLimits[pkg].dailyMin + " min/day</span></span>" +
    '<button class="x">✕</button></li>').join("") || '<li class="muted">No limits</li>';
  $("r-limits").querySelectorAll(".x").forEach((btn, i) => {
    btn.onclick = () => { delete r.appLimits[keys[i]]; renderLimits(); };
  });
}

$("r-blocked-add").onclick = () => {
  const v = $("r-blocked-in").value.trim();
  if (v && !S.rules.blockedApps.includes(v)) S.rules.blockedApps.push(v);
  $("r-blocked-in").value = "";
  renderChipList("r-blocked", S.rules.blockedApps, (pkg) => { S.rules.blockedApps = S.rules.blockedApps.filter((x) => x !== pkg); });
};
$("r-limit-add").onclick = () => {
  const pkg = $("r-limit-pkg").value.trim();
  const min = parseInt($("r-limit-min").value, 10) || 0;
  if (pkg && min > 0) S.rules.appLimits[pkg] = { dailyMin: min };
  $("r-limit-pkg").value = ""; $("r-limit-min").value = "";
  renderLimits();
};
$("r-allowed-add").onclick = () => {
  const v = $("r-allowed-in").value.trim();
  if (v && !S.rules.alwaysAllowed.includes(v)) S.rules.alwaysAllowed.push(v);
  $("r-allowed-in").value = "";
  renderChipList("r-allowed", S.rules.alwaysAllowed, (pkg) => { S.rules.alwaysAllowed = S.rules.alwaysAllowed.filter((x) => x !== pkg); });
};

$("save-rules").onclick = async () => {
  const r = S.rules;
  r.dailyScreenTimeMin = parseInt($("r-daily").value, 10) || 0;
  r.bedtime = {
    enabled: $("r-bed-en").checked,
    start: $("r-bed-start").value || "21:30",
    end: $("r-bed-end").value || "07:00"
  };
  try {
    await api("/api/rules/" + S.cid, "PUT", r);
    toast("Rules saved & pushed ✓");
  } catch (e) { alert(e.message); }
};

async function loadUsage() {
  if (!S.cid) return;
  try {
    S.usage = await api("/api/usage/" + S.cid);
  } catch (e) { return; }
  const used = S.usage.totalMin || 0;
  const limit = (S.rules && S.rules.dailyScreenTimeMin) || 0;
  $("usage-text").textContent = used + " min used" + (limit > 0 ? " / " + limit + " min limit" : " (no limit)");
  $("usage-fill").style.width = (limit > 0 ? Math.min(100, (used / limit) * 100) : (used > 0 ? 12 : 0)) + "%";
}

/* ---------------- apps ---------------- */
async function loadApps() {
  if (!S.cid) return;
  let usageApps = {};
  try {
    S.usage = await api("/api/usage/" + S.cid);
    usageApps = S.usage.apps || {};
  } catch (e) {}
  const pkgs = new Set([...Object.keys(usageApps), ...S.apps.map((a) => a.pkg)]);
  const names = {};
  S.apps.forEach((a) => { names[a.pkg] = a.name; });
  const rules = S.rules;
  const blocked = new Set(rules ? rules.blockedApps : []);
  const limits = rules ? rules.appLimits : {};
  $("apps-list").innerHTML = [...pkgs].sort((a, b) => ((usageApps[b] && usageApps[b].min) || 0) - ((usageApps[a] && usageApps[a].min) || 0))
    .map((pkg) => {
      const u = usageApps[pkg] || { min: 0, label: "" };
      const name = names[pkg] || u.label || pkg;
      return '<div class="app-row"><div class="av">' + esc((name[0] || "?").toUpperCase()) + '</div>' +
        '<div class="nm"><b>' + esc(name) + "</b><span>" + esc(pkg) + (limits[pkg] ? " · limit " + limits[pkg].dailyMin + "m" : "") + '</span></div>' +
        '<div class="min">' + (u.min || 0) + "m</div>" +
        '<input type="checkbox" data-blk="' + esc(pkg) + '"' + (blocked.has(pkg) ? " checked" : "") + ' title="block"></div>';
    }).join("") || '<p class="muted">No data yet — child device connect hone par apps sync hongi.</p>';
  $("apps-list").querySelectorAll("[data-blk]").forEach((cb) => {
    cb.onchange = async () => {
      if (!S.rules) S.rules = await api("/api/rules/" + S.cid);
      const pkg = cb.dataset.blk;
      if (cb.checked) {
        if (!S.rules.blockedApps.includes(pkg)) S.rules.blockedApps.push(pkg);
      } else {
        S.rules.blockedApps = S.rules.blockedApps.filter((x) => x !== pkg);
      }
      await api("/api/rules/" + S.cid, "PUT", S.rules);
      toast(cb.checked ? "Blocked: " + pkg : "Unblocked: " + pkg);
    };
  });
}

/* ---------------- map + geofences ---------------- */
function initMap() {
  if (S.map) return;
  S.map = L.map("map").setView([20.5937, 78.9629], 4);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: "© OpenStreetMap", maxZoom: 19
  }).addTo(S.map);
  S.fenceLayer = L.layerGroup().addTo(S.map);
  S.map.on("click", (e) => {
    if (!S.addingFence) return;
    S.addingFence = false;
    dialog("New safe zone", `
      <label>Name</label><input id="gf-name" placeholder="Home / School">
      <label>Radius (m): <span id="gf-rv">150</span></label>
      <input id="gf-radius" type="range" min="50" max="1000" value="150" style="width:100%">
      <label>Type</label>
      <select id="gf-type"><option value="in">Alert when ENTERS</option><option value="out">Alert when EXITS</option></select>`,
      async () => {
        const radius = parseInt(dlgVal("gf-radius"), 10) || 150;
        const r = await api("/api/geofences/" + S.cid, "POST", {
          name: dlgVal("gf-name") || "Zone", lat: e.latlng.lat, lng: e.latlng.lng,
          radius, type: dlgVal("gf-type")
        });
        S.fences.push(r.fence);
        drawFences();
      });
    $("gf-radius").oninput = () => { $("gf-rv").textContent = $("gf-radius").value; };
  });
}

async function loadLocations() {
  if (!S.cid || !S.map) return;
  try {
    const r = await api("/api/location/" + S.cid + "?limit=300");
    S.track = (r.points || []).slice().reverse();
    drawTrack();
  } catch (e) {}
}

function drawTrack() {
  if (!S.map) return;
  const latlngs = S.track.filter((p) => p.lat && p.lng).map((p) => [p.lat, p.lng]);
  if (S.trackLine) S.trackLine.remove();
  if (latlngs.length) {
    S.trackLine = L.polyline(latlngs, { color: "#2563eb", weight: 4, opacity: 0.7 }).addTo(S.map);
    S.map.fitBounds(S.trackLine.getBounds().pad(0.2), { maxZoom: 16 });
  }
  const last = latlngs[latlngs.length - 1];
  if (last) {
    if (S.marker) S.marker.setLatLng(last);
    else S.marker = L.circleMarker(last, { radius: 9, color: "#fff", weight: 3, fillColor: "#2563eb", fillOpacity: 1 }).addTo(S.map);
  }
}

function addMapPoint(p) {
  S.track.push(p);
  drawTrack();
}

async function loadFences() {
  if (!S.cid) return;
  try {
    const r = await api("/api/geofences/" + S.cid);
    S.fences = r.fences || [];
    drawFences();
  } catch (e) {}
}

function drawFences() {
  if (!S.fenceLayer) return;
  S.fenceLayer.clearLayers();
  S.fences.forEach((f) => {
    L.circle([f.lat, f.lng], {
      radius: f.radius, color: f.type === "in" ? "#16a34a" : "#ea580c", weight: 2, fillOpacity: 0.12
    }).bindTooltip(f.name + " (" + f.type + ", " + f.radius + "m)").addTo(S.fenceLayer);
  });
  renderFenceList();
}

function renderFenceList() {
  $("fence-list").innerHTML = S.fences.map((f) =>
    "<li><span><b>" + esc(f.name) + '</b> <span class="meta">' + (f.type === "in" ? "entry" : "exit") + " · " + f.radius + "m</span></span>" +
    '<button class="x" data-fid="' + esc(f.id) + '">🗑</button></li>').join("") ||
    '<li class="muted">No zones — map par tap karke banao</li>';
  $("fence-list").querySelectorAll("[data-fid]").forEach((b) => {
    b.onclick = async () => {
      await api("/api/geofences/" + S.cid + "/" + b.dataset.fid, "DELETE");
      S.fences = S.fences.filter((f) => f.id !== b.dataset.fid);
      drawFences();
    };
  });
}

/* ---------------- mirror ---------------- */
function onMirrorFrame(buf) {
  const blob = new Blob([buf], { type: "image/jpeg" });
  $("mirror-img").src = URL.createObjectURL(blob);
  S.frameCount++;
  const now = Date.now();
  if (now - S.lastFpsAt > 1000) {
    $("mirror-fps").textContent = S.frameCount + " fps";
    S.frameCount = 0; S.lastFpsAt = now;
  }
}

$("mirror-start").onclick = () => {
  if (!S.cid) return;
  if (S.ws && S.ws.readyState === 1) {
    S.ws.send(JSON.stringify({ type: "mirror_start", cid: S.cid }));
    S.mirrorOn = true;
    $("mirror-start").classList.add("hidden");
    $("mirror-stop").classList.remove("hidden");
  } else alert("Not connected");
};
$("mirror-stop").onclick = () => {
  if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify({ type: "mirror_stop", cid: S.cid }));
  S.mirrorOn = false;
  $("mirror-start").classList.remove("hidden");
  $("mirror-stop").classList.add("hidden");
  $("mirror-fps").textContent = "";
};

/* ---------------- alerts ---------------- */
async function loadAlerts() {
  if (!S.cid) return;
  try {
    const ev = await api("/api/events/" + S.cid + "?limit=200");
    S.events = ev.events || [];
    const nt = await api("/api/notifications/" + S.cid + "?limit=200");
    S.notifs = nt.notifications || [];
    renderEvents(); renderNotifs();
  } catch (e) {}
}

function prettyType(t) {
  return String(t || "").replace(/_/g, " ");
}
function tagClass(t) {
  if (t === "geofence_enter") return "green";
  if (t === "geofence_exit") return "orange";
  if (t === "screentime_over" || t === "app_blocked") return "red";
  if (t === "released") return "blue";
  return "grey";
}

function renderEvents() {
  $("events-list").innerHTML = S.events.map((e) =>
    "<li><span class=\"when\">" + hhmm(e.t) + '</span><span class="tag ' + tagClass(e.type) + '">' +
    esc(prettyType(e.type)) + "</span><div class='d'>" + esc(JSON.stringify(e.data || {}).slice(0, 140)) + "</div></li>").join("") ||
    '<li class="muted">No events yet</li>';
}
function renderNotifs() {
  $("notifs-list").innerHTML = S.notifs.map((n) =>
    "<li><span class=\"when\">" + hhmm(n.date) + '</span><div class="t">' + esc(n.title || n.pkg) + "</div>" +
    "<div class='d'>" + esc(n.text || "") + " <span class='meta'>" + esc(n.pkg) + "</span></div></li>").join("") ||
    '<li class="muted">Nothing captured yet</li>';
}

/* ---------------- logs ---------------- */
async function loadLogs() {
  if (!S.cid) return;
  try {
    const c = await api("/api/calls/" + S.cid);
    $("calls-list").innerHTML = (c.calls || []).map((x) =>
      "<li><span class=\"when\">" + hhmm(x.date) + '</span><div class="t"><span class="tag ' +
      (x.type === "missed" ? "red" : x.type === "in" ? "green" : "blue") + '">' + esc(x.type) + "</span>" +
      esc(x.number) + (x.name ? " (" + esc(x.name) + ")" : "") + "</div><div class='d'>" + x.duration + "s</div></li>").join("") ||
      '<li class="muted">No call log synced (permission optional)</li>';
    const s = await api("/api/sms/" + S.cid);
    $("sms-list").innerHTML = (s.sms || []).map((x) =>
      "<li><span class=\"when\">" + hhmm(x.date) + '</span><div class="t">' + esc(x.number) + "</div>" +
      "<div class='d'>" + esc(x.body || "") + "</div></li>").join("") ||
      '<li class="muted">No SMS synced (permission optional)</li>';
  } catch (e) {}
}

/* ---------------- tabs ---------------- */
$("nav").querySelectorAll("button[data-tab]").forEach((b) => {
  b.onclick = () => {
    $("nav").querySelectorAll("button").forEach((x) => x.classList.remove("on"));
    b.classList.add("on");
    document.querySelectorAll(".tab").forEach((t) => t.classList.add("hidden"));
    $("tab-" + b.dataset.tab).classList.remove("hidden");
    const t = b.dataset.tab;
    if (t === "time") loadRules();
    if (t === "apps") loadApps();
    if (t === "map") { initMap(); setTimeout(() => S.map && S.map.invalidateSize(), 50); loadLocations(); loadFences(); }
    if (t === "alerts") loadAlerts();
    if (t === "logs") loadLogs();
    if (t === "more") renderChildrenList();
  };
});

/* ---------------- boot ---------------- */
if (S.token) {
  enterApp().catch(() => {
    localStorage.removeItem("fg_token");
    location.reload();
  });
}
