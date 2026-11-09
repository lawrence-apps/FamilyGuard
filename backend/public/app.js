/* BachaGuard web dashboard — vanilla JS, no build step. v5 */
"use strict";

const S = {
  token: localStorage.getItem("fg_token") || "",
  origin: location.origin,
  children: [],
  cid: null,
  ws: null,
  wsTimer: null,
  pollTimer: null,
  usageTimer: null,
  rules: null,
  usage: null,
  apps: [],            // installed apps reported by child (WS "apps")
  mirrorOn: false,
  frameCount: 0,
  lastFpsAt: 0,
  lastFrameAt: 0,      // ms of last mirror frame received (status watchdog)
  lastBlobUrl: null,   // previous mirror frame blob URL (revoked on next frame)
  streaming: false,    // child-reported mirroring state (mirror_state)
  mirrorChildReason: null,     // bachche ke phone se aaya HONEST stall reason
  mirrorChildReasonAt: 0,      // us reason ka timestamp (25 s tak "fresh")
  gallery: { items: [], total: 0, offset: 0, loading: false },
  ringOn: false,       // ring command chalu hai (button toggle + ring_state sync)
  ringTimer: null,     // child 30s me khud band karta hai — UI safety reset
  audioOn: false,      // live audio listening chalu hai
  audioCtx: null,      // WebAudio context (audio chunks playback)
  audioNextAt: 0,      // next chunk schedule time (gapless playback)
  map: null,
  marker: null,
  track: [],
  trackLine: null,
  fences: [],
  fenceLayer: null,
  addingFence: false,
  events: [],
  notifs: [],
  synth: [],           // pairing / unpair synthetic activity entries
  chat: [],            // messages sent to child (session history)
};

const $ = (id) => document.getElementById(id);
const RING_C = 326.73; // 2 * PI * 52

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
// Hinglish time — "13 ghante pehle", "24 min pehle"
function timeAgoHi(ts) {
  if (!ts) return "—";
  const d = Math.floor((Date.now() - ts) / 1000);
  if (d < 60) return "abhi abhi";
  if (d < 3600) return Math.floor(d / 60) + " min pehle";
  if (d < 86400) {
    const h = Math.floor(d / 3600);
    return h === 1 ? "1 ghanta pehle" : h + " ghante pehle";
  }
  const dd = Math.floor(d / 86400);
  return dd + (dd === 1 ? " din pehle" : " din pehle");
}
function fmtMin(m) {
  m = Math.max(0, Math.round(m || 0));
  if (m >= 60) return Math.floor(m / 60) + "h " + (m % 60) + "m";
  return m + "m";
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
  $("dlg-ok").disabled = false;
  $("dlg-ok").onclick = async () => {
    // double-click guard: OK dobara dabane se double DELETE/command na jaye
    if ($("dlg-ok").disabled) return;
    $("dlg-ok").disabled = true;
    if (onOk) {
      try { await onOk(); } catch (e) { alert(e.message); $("dlg-ok").disabled = false; return; }
    }
    $("dlg").classList.add("hidden");
    $("dlg-ok").disabled = false;
  };
  $("dlg-cancel").onclick = () => $("dlg").classList.add("hidden");
}
function dlgVal(id) { const el = $("dlg-body").querySelector("#" + id); return el ? el.value : ""; }

function toast(msg) {
  $("cmd-status").textContent = msg;
  $("cmd-status").classList.remove("hidden");
  setTimeout(() => { $("cmd-status").textContent = ""; $("cmd-status").classList.add("hidden"); }, 6000);
}

/* ---------------- auth ---------------- */
$("a-origin").textContent = S.origin;
$("about-origin").textContent = S.origin;

$("role-parent").onclick = () => {
  $("role-parent").classList.add("on"); $("role-child").classList.remove("on");
  $("parent-auth").classList.remove("hidden"); $("child-auth").classList.add("hidden");
};
$("role-child").onclick = () => {
  $("role-child").classList.add("on"); $("role-parent").classList.remove("on");
  $("child-auth").classList.remove("hidden"); $("parent-auth").classList.add("hidden");
};
$("go-parent").onclick = () => $("role-parent").click();

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
// Enter key se login/register — phone/desktop dono par natural feel
for (const id of ["a-user", "a-pass"]) {
  $(id).addEventListener("keydown", (ev) => { if (ev.key === "Enter") $("a-login").click(); });
}
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
  // Aaj tab khula ho to ring har 30s me fresh — usage badhne par turant dikhe
  S.usageTimer = setInterval(() => {
    if (S.cid && !$("tab-aaj").classList.contains("hidden")) loadRules();
  }, 30000);
  loadAlerts();
  loadApk();
  loadRules();
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
    renderStatus();
    renderPairCard();
    renderChildrenList();
    if ($("tab-report").classList.contains("hidden") === false) loadApps();
  } catch (e) {
    if (String(e.message).includes("401") || String(e.message).includes("bad token")) {
      localStorage.removeItem("fg_token"); location.reload();
    }
  }
}

const AVATARS = ["🧒", "👦", "👧", "🐣", "🦄", "🌟", "🐯", "🐨", "🐰", "🦁"];
function avatarFor(name) {
  let h = 0;
  for (const ch of String(name || "")) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return AVATARS[h % AVATARS.length];
}

function renderHeader() {
  const c = activeChild();
  const pill = $("h-live");
  if (!c) {
    $("h-avatar").textContent = "🧒";
    $("h-name").textContent = "Bachcha select karo";
    $("h-sub").textContent = "Header par tap karke add karo";
    pill.textContent = "OFFLINE"; pill.className = "pill off";
    return;
  }
  $("h-avatar").textContent = avatarFor(c.name);
  $("h-name").textContent = c.name;
  $("h-sub").textContent = c.online ? "Abhi online hai" : "Last seen " + timeAgoHi(c.lastSeen);
  pill.textContent = c.online ? "LIVE" : "OFFLINE";
  pill.className = "pill " + (c.online ? "on" : "off");
}

// Header tap → bachcha switch / add dialog
$("h-switch").onclick = () => {
  const items = S.children.map((c) =>
    '<li><button class="' + (c.id === S.cid ? "cur" : "") + '" data-pick="' + esc(c.id) + '">' +
    '<span style="font-size:20px">' + avatarFor(c.name) + "</span><span><b>" + esc(c.name) + "</b> " +
    (c.paired ? (c.online ? "🟢 online" : "⚫ offline") : "⚪ unpaired") + "</span></button></li>").join("");
  dialog("Kaunsa bachcha?",
    '<ul class="pick-list">' + (items || '<li class="muted">Koi bachcha nahi — pehle add karo</li>') + "</ul>" +
    '<button id="pick-add" class="btn wide">➕ Naya bachcha add karo</button>',
    null);
  $("dlg-body").querySelectorAll("[data-pick]").forEach((b) => {
    b.onclick = () => { $("dlg").classList.add("hidden"); switchChild(b.dataset.pick); };
  });
  $("pick-add").onclick = () => { $("dlg").classList.add("hidden"); showCreateChild(); };
};

function switchChild(cid) {
  S.cid = cid;
  S.apps = []; // purane child ke apps naye child ki list me na ghus jayein
  S.gallery = { items: [], total: 0, offset: 0, loading: false }; // gallery bhi naye child ki
  // Toggle buttons naye child ki real state par reset — purana child ka
  // mirror/audio status naye child ke UI me na ghus jaye
  setMirrorBtn(false); setAudioBtn(false); setRingBtn(false);
  S.streaming = false;
  $("mirror-fps").textContent = "";
  mirrorStatus("", false); audioStatus("", false);
  const vz = $("audio-viz"); if (vz) vz.classList.add("hidden");
  const gz = $("gallery-grid"); if (gz) gz.innerHTML = "";
  const gm = $("gallery-more-row"); if (gm) gm.classList.add("hidden");
  galleryStatus("", false);
  renderStatus(); renderPairCard();
  loadRules(); loadApps(); loadLocations(); loadFences(); loadAlerts(); renderChildrenList();
}

function activeChild() { return S.children.find((c) => c.id === S.cid) || null; }

function renderStatus() {
  const c = activeChild();
  renderHeader();
  if (!c) {
    $("s-batt").textContent = "🔋 —";
    $("s-app").textContent = "📱 —";
    $("danger-child-name").textContent = "Device: —";
    renderUninstallBanner();
    updateRing();
    return;
  }
  $("s-batt").textContent = c.battery >= 0 ? "🔋 " + c.battery + "%" + (c.charging ? " ⚡" : "") : "🔋 —";
  $("s-app").textContent = "📱 " + (c.currentApp || "—");
  const h = c.health;
  const labels = {
    service: "Background service", accessibility: "Accessibility", admin: "Device admin",
    location: "Location", usageAccess: "Usage access", overlay: "Overlay",
    camera: "Camera", notifListener: "Notifications", batteryOk: "Battery exemption", mic: "Microphone"
  };
  const broken = h ? Object.keys(labels).filter((k) => h[k] === false).map((k) => labels[k]) : [];
  const el = $("s-health");
  if (broken.length) {
    el.classList.remove("hidden");
    el.innerHTML = "<b>Repair chahiye:</b> " + broken.join(", ") +
      ' — bachche ka device khud theek hone ki koshish karega; <button class="btn small" id="heal-btn">🔧 Auto-Repair</button>';
    const hb = document.getElementById("heal-btn");
    if (hb) hb.onclick = () => sendCmd("heal", {});
  } else {
    el.classList.add("hidden");
  }
  $("danger-child-name").textContent = "Device: " + c.name + (c.model ? " · " + c.model : (c.deviceName ? " · " + c.deviceName : ""));
  renderUninstallBanner();
  renderAdultCard();
  updateRing();
}

// Manually-uninstall / phone-off detection: paired child jo 30+ min se server
// se nahi mila. App uninstall ho gayi ho to record yahin se remove kar sakte ho.
function renderUninstallBanner() {
  const c = activeChild();
  const card = $("offline-card");
  if (!card) return;
  if (!c || !c.paired || c.online) { card.classList.add("hidden"); return; }
  const idle = Date.now() - (c.lastSeen || 0);
  if (idle < 30 * 60 * 1000) { card.classList.add("hidden"); return; }
  $("offline-text").innerHTML = "⚠️ <b>" + esc(c.name) + "</b> ka app <b>" + timeAgoHi(c.lastSeen) + "</b> se server se connect nahi hua. Agar child phone se app uninstall kar di gayi hai (ya phone band/offline hai) to ye normal hai. <b>Recent apps se clear kiya tha?</b> Child phone ke dialer me <b>*#*#2724#*#*</b> dial karke app ek baar khol do — 1 min me wapas online aa jayega. App wapas chahiye to child phone par dobara install karke same pairing code daalo — nahi chahiye to neeche se device remove kar do.";
  card.classList.remove("hidden");
}

function renderPairCard() {
  const c = activeChild();
  const show = !c || !c.paired;
  $("pair-card").classList.toggle("hidden", !show);
  const qrEl = $("pair-qr");
  if (!show || !c) { qrEl.classList.add("hidden"); if (show) $("pair-code").textContent = "Pehle bachcha add karo"; return; }
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
  dialog("Naya bachcha add karo", `
    <label>Name</label><input id="dc-name" placeholder="Bachche ka naam">
    <label>Date of birth (YYYY-MM-DD)</label><input id="dc-dob" placeholder="2008-06-15">
    <p class="muted small">18 hone par protections auto-release ho jayengi.</p>`,
    async () => {
      const name = dlgVal("dc-name").trim();
      const dob = dlgVal("dc-dob").trim();
      if (!name) throw new Error("Name required");
      const r = await api("/api/children", "POST", { name, dob });
      S.cid = r.child.id;
      S.synth.unshift({ t: Date.now(), ico: "🧒", txt: "Naya bachcha add hua: " + name });
      renderActivity();
      await refreshChildren();
      loadRules(); loadApps(); loadLocations(); loadFences(); loadAlerts();
    });
}
$("new-child").onclick = showCreateChild;
$("new-child-2").onclick = showCreateChild;

$("regen-code").onclick = async () => {
  if (!S.cid) return;
  const r = await api("/api/children/" + S.cid + "/regenerate-code", "POST", {});
  const c = S.children.find((x) => x.id === S.cid);
  if (c) c.pairingCode = r.pairingCode;
  renderPairCard(); // code + QR dono fresh ho jayein
};

function renderChildrenList() {
  $("children-list").innerHTML = S.children.map((c) =>
    '<li><div><b>' + esc(c.name) + "</b> " + (c.online
      ? '<span class="dot on"></span> <span class="meta">online</span>'
      : '<span class="dot off"></span> <span class="meta">offline · last seen ' + timeAgoHi(c.lastSeen) + "</span>") +
    '<br><span class="meta">' + (c.paired ? esc(c.deviceName || "paired") : "code: " + esc(c.pairingCode)) + "</span></div>" +
    '<button class="btn small danger" data-del="' + esc(c.id) + '" style="margin-top:0">Remove device</button></li>').join("") ||
    '<li class="muted">Abhi koi bachcha nahi — upar wale button se add karo</li>';
  $("children-list").querySelectorAll("[data-del]").forEach((b) => {
    b.onclick = () => removeDeviceFull(b.dataset.del);
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
      $("apk-hint").textContent = "✅ GitHub se auto-sync: " + (st.releaseTag || "latest") + mb + ". Naya build aate hi bachche ka app KHUD update ho jayegi (bina popup/notification) — link/QR same rehta hai, kuch karne ki zarurat nahi.";
    } else {
      $("apk-hint").textContent = "📦 Manually uploaded" + mb + ". Auto-sync isko replace nahi karega — GitHub build wapas chahiye to \"Sync now\" dabao.";
    }
    $("apk-force-update").classList.remove("hidden");
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
    $("apk-force-update").classList.add("hidden");
  }
}

// Child app silent update: server par jo APK hai wahi child phone par push karo
$("apk-force-update").onclick = () => {
  if (!S.cid) { alert("Pehle bachcha select karo"); return; }
  const c = activeChild();
  if (c && !c.online) {
    toast("Bachcha offline hai — command queue me hai, connect hote hi update ho jayegi");
  }
  sendCmd("update_apk");
};

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
    toast("Child APK upload ho gayi ✓");
  } catch (e) {
    $("apk-hint").textContent = "Upload failed: " + e.message;
  } finally {
    $("apk-file").value = "";
  }
};
$("apk-copy").onclick = async () => {
  const url = S.origin + (S.apk && S.apk.url ? S.apk.url : "");
  try { await navigator.clipboard.writeText(url); toast("Link copy ho gaya ✓"); }
  catch (e) { prompt("Ye link copy karo:", url); }
};
$("apk-share").onclick = async () => {
  const url = S.origin + (S.apk && S.apk.url ? S.apk.url : "");
  if (navigator.share) {
    try { await navigator.share({ title: "BachaGuard Child App", text: "Ye link bachche ke phone par kholo — app install ho jayegi:", url }); } catch (e) {}
  } else {
    window.open("https://wa.me/?text=" + encodeURIComponent("Ye link bachche ke phone par kholo — app install ho jayegi: " + url), "_blank");
  }
};
$("apk-delete").onclick = () => {
  dialog("Uploaded APK hatani hai?", "<p>Install link band ho jayega. Child phone par pehle se installed app par koi asar nahi hoga. Next auto-sync par GitHub build wapas aa sakta hai.</p>", async () => {
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
  ws.onclose = (ev) => {
    $("conn").className = "dot off";
    // 4001 = bad/expired token — infinite reconnect loop me na phase, login par wapas
    if (ev && ev.code === 4001) {
      localStorage.removeItem("fg_token");
      location.reload();
      return;
    }
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
        if (m.lastSeen) c.lastSeen = m.lastSeen; // banner/status ko fresh rakhta hai polls ke beech
        if (m.health) c.health = m.health;
        if (typeof m.permCalls === "boolean") c.permCalls = m.permCalls;
        if (typeof m.permSms === "boolean") c.permSms = m.permSms;
      }
      // Mirroring ke beech child offline ho gaya to UI stuck na rahe
      if (m.online === false && m.cid === S.cid) {
        if (S.mirrorOn) {
          setMirrorBtn(false); S.streaming = false;
          $("mirror-fps").textContent = "";
          mirrorStatus("Child offline ho gaya — mirroring band. Child wapas connect hote hi dobara Start karo.", true);
        }
        // Ring chalu tha to button reset karo (30s auto-stop bhi hai)
        setRingBtn(false);
        if (S.ringTimer) { clearTimeout(S.ringTimer); S.ringTimer = null; }
        // Live audio bhi band kar do
        if (S.audioOn) stopAudioUi();
      }
      // Child ABHI online hua — uska UsageWorker connect par turant calls/SMS
      // sync kick karta hai. Report tab par data bina refresh ke aane ke liye
      // do baar logs reload (sync ka pehla batch ~5-20 s me pahunchta hai).
      if (m.online === true && m.cid === S.cid) {
        setTimeout(() => { if (S.cid === m.cid) loadLogs(); }, 5000);
        setTimeout(() => { if (S.cid === m.cid) loadLogs(); }, 20000);
      }
      if (m.cid === S.cid && !$("tab-report").classList.contains("hidden")) permDiag();
      if (m.cid === S.cid && !$("tab-live").classList.contains("hidden")) renderLiveDiag();
      renderStatus();
      break;
    }
    case "location": {
      if (m.cid !== S.cid || !m.point) break;
      addMapPoint(m.point);
      break;
    }
    case "notification": {
      if (m.cid === S.cid && m.notif) { S.notifs.unshift(m.notif); renderActivity(); }
      break;
    }
    case "event": {
      if (m.cid === S.cid && m.event) { S.events.unshift(m.event); renderActivity(); }
      break;
    }
    case "command_result": {
      if (m.cid !== S.cid) break;
      const d = m.data || {};
      if (m.ok && d.status === "synced") {
        // "Sync now" ka INLINE result — real counts + current permission flags.
        // Perm flags YAHIN update karo: warna 60 s tak purani (galat) state
        // dikhti aur diag banner permission ON hone ke baad bhi Off dikhata.
        const c = activeChild();
        if (c && typeof d.permCalls === "boolean") c.permCalls = d.permCalls;
        if (c && typeof d.permSms === "boolean") c.permSms = d.permSms;
        loadLogs();
        permDiag();
        if (d.permCalls === false || d.permSms === false) {
          toast("Permission abhi bhi OFF hai — upar ⚠️ diag me exact fix padho");
        } else {
          toast((d.calls || 0) + " calls · " + (d.sms || 0) + " SMS synced ✓");
        }
      } else if (m.ok && d.status === "gallery") {
        // Gallery page — bachche ke phone se thumbs upload hoke aa gaye
        const items = d.items || [];
        for (const it of items) S.gallery.items.push(it);
        S.gallery.total = d.total || S.gallery.items.length;
        S.gallery.offset = (d.offset || 0) + items.length;
        S.gallery.loading = false;
        renderGallery();
        if (S.gallery.items.length === 0) {
          galleryStatus("Gallery me kuch nahi mila — ya bachche ne sirf kuch photos 'Allow' ki hain (Android 14 partial access)", true);
        } else {
          galleryStatus("", false);
          if (items.length) toast("🖼️ " + items.length + " items aur aa gaye (total " + S.gallery.items.length + "/" + S.gallery.total + ")");
        }
      } else if (m.ok && d.galleryFull && d.url) {
        // Gallery tile tap — bada view naye tab me
        galleryStatus("", false);
        try { window.open(S.origin + d.url, "_blank"); } catch (e) { location.href = S.origin + d.url; }
      } else if (!m.ok && typeof d.error === "string" && d.error.indexOf("gallery permission") === 0) {
        // Gallery permission pehli baar auto-request hui — a11y 5-10 s me
        // Allow click kar degi, parent dobara tap karega
        S.gallery.loading = false;
        galleryStatus("Gallery permission bachche ke phone par auto-request ho gayi — 5–10 sec me khud Allow ho jayegi. Phir dobara “📸 Gallery kholo” dabao.", true);
      } else if (m.ok && d.url) {
        $("media-card").classList.remove("hidden");
        $("media-img").src = S.origin + d.url;
        toast("Capture mil gaya ✓ (Live tab dekho)");
      } else if (m.ok && d.lat != null && d.lng != null) {
        toast("Ho gaya ✓ (" + Number(d.lat).toFixed(5) + ", " + Number(d.lng).toFixed(5) + ")");
      } else if (m.ok) {
        toast("Ho gaya ✓");
      } else {
        // Child ke command results me kabhi-kabhi error NESTED hota hai
        // (audio_start ka {audio:{error:...}}) — sirf d.error padhne par
        // dashboard hamesha "Fail hua: unknown" dikha raha tha.
        toast("Fail hua: " + (d.error || (d.audio && d.audio.error) || "unknown"));
      }
      break;
    }
    case "apps": {
      if (m.cid === S.cid) { S.apps = m.apps || []; renderApps(); }
      break;
    }
    case "mirror_state": {
      // Child's real mirroring state — LIVE badge aur status hint isi se chalte hain.
      // Same-button toggle bhi isi se sahi state par atakta hai.
      if (m.cid === S.cid) {
        S.streaming = !!m.streaming;
        if (S.streaming) {
          setMirrorBtn(true);
          S.lastFrameAt = Date.now(); // grace window for the "no frames" watchdog
          mirrorStatus("Bachche ki screen mirroring chalu ho gayi ✓", false);
        } else if (S.mirrorOn) {
          setMirrorBtn(true);
          // Child se HONEST reason aaye (consent fail / a11y off / OEM block /
          // screen off / capture stall) to generic "consent confirm ho raha hai"
          // ki jagah wahi dikhao — aur 12 s watchdog bhi isko prefer kare.
          S.mirrorChildReason = m.reason || null;
          S.mirrorChildReasonAt = S.mirrorChildReason ? Date.now() : 0;
          mirrorStatus(m.reason || "Consent dialog auto-confirm ho raha hai… (5–20 sec)", true);
        } else {
          setMirrorBtn(false);
          mirrorStatus("", false);
        }
      }
      break;
    }
    case "ring_state": {
      // Child device ki real ring state — same button toggle isi se sync hota hai
      if (m.cid === S.cid) {
        setRingBtn(!!m.playing);
        if (!m.playing && S.ringTimer) { clearTimeout(S.ringTimer); S.ringTimer = null; }
      }
      break;
    }
    case "audio": {
      // Live audio chunk (base64 PCM 16kHz mono) — sirf tab jab humne Start dabaya
      if (m.cid === S.cid && S.audioOn && m.data) onAudioChunk(m.data);
      break;
    }
    case "audio_state": {
      if (m.cid === S.cid) {
        if (m.streaming) {
          setAudioBtn(true);
          audioStatus("Bachche ka mic live hai 🎧", false);
        } else if (m.error) {
          // Child ne saaf bataya: mic system me blocked hai (Android 14 while-in-use)
          stopAudioUi();
          audioStatus(m.error, true);
        } else if (S.audioOn) {
          stopAudioUi();
          toast("Mic band ho gaya (child side se)");
        }
      }
      break;
    }
    case "pairing": {
      S.synth.unshift({ t: Date.now(), ico: "✅", txt: "Bachche ka device pair ho gaya 🎉" });
      renderActivity();
      refreshChildren();
      break;
    }
    case "children_changed": {
      // Kaunsa child gayab hua? (remove/unpair) — activity me synthetic entry
      const prevIds = S.children.map((c) => c.id);
      Promise.resolve(refreshChildren()).then(() => {
        const gone = prevIds.filter((id) => !S.children.some((c) => c.id === id));
        gone.forEach((id) => {
          const c = S.children.find((x) => x.id === id); // never true, sirf naam ke liye
          S.synth.unshift({ t: Date.now(), ico: "🔌", txt: "Bachche ka device unpair/remove ho gaya" + (c ? " (" + c.name + ")" : "") });
        });
        if (gone.length) renderActivity();
      });
      break;
    }
    default: break;
  }
}

/* ---------------- commands ---------------- */
async function sendCmd(action, params) {
  if (!S.cid) { alert("Pehle bachcha select karo"); return; }
  try {
    await api("/api/commands", "POST", { cid: S.cid, action, params: params || {} });
    toast("✅ " + action + " bheja gaya" + (activeChild() && !activeChild().online ? " (offline — queue me hai, connect hone par chalega)" : ""));
  } catch (e) { alert(e.message); }
}

/* ---------------- ring toggle (same button = bajao + band) ---------------- */
function setRingBtn(on) {
  S.ringOn = on;
  const b = $("qa-ring");
  if (b) b.innerHTML = on ? "🔕 Ring band karo" : "🔔 Ring bajao";
}
function toggleRing() {
  if (S.ringOn) {
    sendCmd("ring_stop");
    setRingBtn(false);
    if (S.ringTimer) { clearTimeout(S.ringTimer); S.ringTimer = null; }
  } else {
    sendCmd("ring");
    setRingBtn(true);
    if (S.ringTimer) clearTimeout(S.ringTimer);
    // child 30s me khud band karta hai — ring_state na aaye to bhi UI reset
    S.ringTimer = setTimeout(() => setRingBtn(false), 40000);
  }
}

/* ---------------- live audio (mic) ---------------- */
function audioStatus(msg, warn) {
  const el = $("audio-status");
  if (!el) return;
  if (!msg) { el.classList.add("hidden"); return; }
  el.textContent = msg;
  el.style.color = warn ? "#B45309" : "#166534";
  el.style.background = warn ? "#FFFBEB" : "#F0FDF4";
  el.style.borderColor = warn ? "#FCD34D" : "#86EFAC";
  el.classList.remove("hidden");
}

// base64 PCM (16kHz mono int16) -> AudioBuffer (context ke sampleRate par resample)
// -> destination par gapless schedule. Int16 LE -> Float32 manual conversion.
function onAudioChunk(b64) {
  try {
    if (!S.audioCtx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      try { S.audioCtx = new AC({ sampleRate: 16000 }); }
      catch (e) { S.audioCtx = new AC(); } // purane browsers: default rate par resample
    }
    const ctx = S.audioCtx;
    if (ctx.state === "suspended") { ctx.resume(); }
    const bin = atob(b64);
    const n16 = bin.length >> 1;
    if (!n16) return;
    const from = 16000, to = ctx.sampleRate;
    const outN = Math.max(1, Math.round(n16 * to / from));
    const buf = ctx.createBuffer(1, outN, to);
    const ch = buf.getChannelData(0);
    const step = outN > 1 ? (n16 - 1) / (outN - 1) : 0;
    for (let i = 0; i < outN; i++) {
      const p = i * step, i0 = Math.floor(p), i1 = Math.min(n16 - 1, i0 + 1), f = p - i0;
      const v0 = (((bin.charCodeAt(i0 * 2 + 1) << 8) | bin.charCodeAt(i0 * 2)) << 16 >> 16) / 32768;
      const v1 = (((bin.charCodeAt(i1 * 2 + 1) << 8) | bin.charCodeAt(i1 * 2)) << 16 >> 16) / 32768;
      ch[i] = v0 + (v1 - v0) * f;
    }
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(ctx.destination);
    const now = ctx.currentTime;
    if (S.audioNextAt < now + 0.02) S.audioNextAt = now + 0.06;
    if (S.audioNextAt - now > 0.8) S.audioNextAt = now + 0.06; // drift resync
    src.start(S.audioNextAt);
    S.audioNextAt += buf.duration;
  } catch (e) {}
}

function stopAudioUi() {
  setAudioBtn(false);
  if (S.audioCtx) { try { S.audioCtx.close(); } catch (e) {} S.audioCtx = null; }
  S.audioNextAt = 0;
  const vz = $("audio-viz");
  if (vz) vz.classList.add("hidden");
  audioStatus("", false);
}

// ---- same-button toggles (ring jaisa hi pattern) ----
function setMirrorBtn(on) {
  S.mirrorOn = on;
  const b = $("mirror-toggle");
  if (!b) return;
  b.innerHTML = on ? "■ Mirror band karo ✋" : "▶ Mirror shuru karo";
  b.classList.toggle("danger", on);
  b.classList.toggle("primary", !on);
}
function setAudioBtn(on) {
  S.audioOn = on;
  const b = $("audio-toggle");
  if (!b) return;
  b.innerHTML = on ? "■ Sunna band karo ✋" : "▶ Sunna shuru karo";
  b.classList.toggle("danger", on);
  b.classList.toggle("primary", !on);
}

$("audio-toggle").onclick = () => {
  if (!S.cid) return;
  if (S.audioOn) {
    sendCmd("audio_stop");
    stopAudioUi();
    return;
  }
  if (S.ws && S.ws.readyState === 1) {
    // audio chunks bhi watch_mirror subscription se filter hote hain
    S.ws.send(JSON.stringify({ type: "watch_mirror", cid: S.cid }));
    sendCmd("audio_start");
    setAudioBtn(true);
    $("audio-viz").classList.remove("hidden");
    const c = activeChild();
    audioStatus(c && !c.online ? "Bachcha abhi offline hai — connect hote hi mic khud chalu ho jayega." : "Mic connect ho raha hai…", true);
  } else alert("Server se connect nahi hai");
};

$("quick-grid").querySelectorAll("[data-cmd]").forEach((b) => {
  b.onclick = () => {
    const c = b.dataset.cmd;
    if (c === "ring") {
      toggleRing();
    } else if (c === "lock") {
      dialog("Phone lock karo", '<label>Bachche ki screen par message</label><input id="dl-msg" value="Time up! Phone band karo 😊">',
        () => sendCmd("lock", { message: dlgVal("dl-msg") }));
    } else if (c === "message") {
      dialog("Message bhejo", '<label>Title</label><input id="dm-title"><label>Text</label><input id="dm-text">',
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

$("lock-now").onclick = () => {
  dialog("Lock Now — phone abhi lock karo?", '<label>Bachche ki screen par message</label><input id="dl-msg2" value="Time up! Phone band karo 😊">',
    () => sendCmd("lock", { message: dlgVal("dl-msg2") }));
};

async function bonusTime(n) {
  if (!S.cid || !S.rules) { alert("Pehle bachcha select karo"); return; }
  const used = (S.usage && S.usage.totalMin) || 0;
  const cur = S.rules.dailyScreenTimeMin || 0;
  S.rules.dailyScreenTimeMin = cur > 0 ? cur + n : used + n;
  try {
    await api("/api/rules/" + S.cid, "PUT", S.rules);
    toast("+" + n + " min bonus de diya ✓ (limit ab " + fmtMin(S.rules.dailyScreenTimeMin) + ")");
    updateRing();
  } catch (e) { alert(e.message); }
}
$("bonus15").onclick = () => bonusTime(15);
$("bonus30").onclick = () => bonusTime(30);

function releaseProtections() {
  dialog("Sirf uninstall — record rahega?",
    "<p>Child phone par app saari permissions khud hata kar system uninstall dialog khud khol degi aur use <b>khud hi confirm bhi kar degi</b> — wahan <b>ek bhi tap nahi lagega</b>, app apne aap uninstall ho jayegi. <b>Dashboard me device record rahega</b> — dobara same pairing code se pair kar sakte ho. Continue?</p>",
    () => sendCmd("release_protections"));
}

// FULL Remove device: child phone se saari permissions + uninstall dialog, aur
// dashboard se record data samet hamesha ke liye delete. Offline phone agle
// connect par orphan guard se khud ko clean kar lega.
function removeDeviceFull(cid) {
  const c = S.children.find((x) => x.id === cid);
  if (!c) { alert("Pehle bachcha select karo"); return; }
  dialog("Remove device — " + c.name + "?",
    "<p><b>" + esc(c.name) + "</b> ke phone se app saari permissions khud hata kar system uninstall dialog khud khol kar <b>khud hi confirm kar degi</b> — phone par <b>ek bhi tap nahi lagega</b>, app apne aap uninstall ho jayegi. Monitoring abhi turant band ho jayegi.</p><p>Dashboard se ye device <b>data samet hamesha ke liye delete</b> ho jayega. Agar phone us waqt offline hai to app agle connect par khud clean ho jayegi. <b>Ye undo nahi hota</b> — dobara monitoring ke liye naya pairing code lena hoga.</p>",
    async () => {
      // 1) child online hai to abhi permissions strip + uninstall dialog fire karo
      try { await api("/api/commands", "POST", { cid, action: "release_protections", params: {} }); } catch (e) {}
      // 2) record purge — orphan guard offline child ko next connect par release karega
      await api("/api/children/" + cid, "DELETE");
      if (S.cid === cid) S.cid = null;
      await refreshChildren();
      toast("Device remove ho gaya ✓");
    });
}
$("release-btn").onclick = releaseProtections;
$("remove-device-btn").onclick = () => { S.cid ? removeDeviceFull(S.cid) : alert("Pehle bachcha select karo"); };
$("offline-remove").onclick = () => { if (S.cid) removeDeviceFull(S.cid); };

/* ---------------- rules (screen time) ---------------- */
async function loadRules() {
  const cid = S.cid;
  if (!cid) return;
  try {
    const r = await api("/api/rules/" + cid);
    if (S.cid !== cid) return; // beech me child switch ho gaya to purana data render mat karo
    S.rules = r;
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
    '<li class="muted">Kuch add nahi hai</li>';
  $(id).querySelectorAll(".x").forEach((btn, i) => { btn.onclick = () => { onRemove(arr[i]); renderChipList(id, arr, onRemove); }; });
}

function renderLimits() {
  const r = S.rules;
  const keys = Object.keys(r.appLimits || {});
  $("r-limits").innerHTML = keys.map((pkg) =>
    "<li><span>" + esc(pkg) + ' <span class="meta">' + r.appLimits[pkg].dailyMin + " min/day</span></span>" +
    '<button class="x">✕</button></li>').join("") || '<li class="muted">Koi limit nahi</li>';
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
  if (!S.cid || !S.rules) { alert("Pehle bachcha select karo"); return; }
  const r = S.rules;
  r.dailyScreenTimeMin = parseInt($("r-daily").value, 10) || 0;
  r.bedtime = {
    enabled: $("r-bed-en").checked,
    start: $("r-bed-start").value || "21:30",
    end: $("r-bed-end").value || "07:00"
  };
  try {
    await api("/api/rules/" + S.cid, "PUT", r);
    toast("Rules save ho gaye, bachche ko push ho gaye ✓");
    updateRing();
  } catch (e) { alert(e.message); }
};

/* ---------------- usage + ring ---------------- */
function bedtimeActive() {
  const b = S.rules && S.rules.bedtime;
  if (!b || !b.enabled) return false;
  const parse = (s) => {
    const [h, m] = String(s || "").split(":").map((x) => parseInt(x, 10) || 0);
    return h * 60 + m;
  };
  const now = new Date();
  const cur = now.getHours() * 60 + now.getMinutes();
  const s = parse(b.start), e = parse(b.end);
  return s <= e ? (cur >= s && cur < e) : (cur >= s || cur < e);
}

function updateRing() {
  if (!$("ring-fg")) return;
  const limit = (S.rules && S.rules.dailyScreenTimeMin) || 0;
  const used = (S.usage && S.usage.totalMin) || 0;
  const remaining = limit > 0 ? Math.max(0, limit - used) : null;
  const frac = limit > 0 ? Math.min(1, Math.max(0, 1 - used / limit)) : 1;
  const fg = $("ring-fg");
  fg.style.strokeDashoffset = String(RING_C * (1 - frac));
  fg.style.stroke = (limit > 0 && remaining === 0) ? "#DC2626"
    : (limit > 0 && remaining < limit * 0.25) ? "#F59E0B" : "#22C55E";
  $("ring-label").textContent = limit > 0
    ? (remaining > 0 ? fmtMin(remaining) + " bache hai" : "Limit poori!")
    : "Unlimited";
  $("usage-text").textContent = fmtMin(used) + " use hua" + (limit > 0 ? " / " + fmtMin(limit) + " limit" : " — koi limit nahi");
  const badge = $("st-badge");
  if (bedtimeActive()) { badge.className = "badge warn"; badge.textContent = "🌙 Sone ka time — phone band ho jayega"; }
  else if (limit > 0 && remaining === 0) { badge.className = "badge bad"; badge.textContent = "Limit poori ho gayi 🚫"; }
  else { badge.className = "badge ok"; badge.textContent = "App khul sakta hai ✅"; }
}

async function loadUsage() {
  if (!S.cid) return;
  try {
    S.usage = await api("/api/usage/" + S.cid);
  } catch (e) { return; }
  updateRing();
}

/* ---------------- apps ---------------- */
async function loadApps() {
  const cid = S.cid;
  if (!cid) return;
  let usageApps = {};
  try {
    const u = await api("/api/usage/" + cid);
    if (S.cid !== cid) return;
    S.usage = u;
    usageApps = u.apps || {};
    if ($("tab-aaj").classList.contains("hidden") === false) updateRing();
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
    }).join("") || '<p class="muted">Abhi data nahi — bachche ka device connect hone par apps sync hongi.</p>';
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
      toast(cb.checked ? "🚫 Block kar diya: " + pkg : "✅ Block hata diya: " + pkg);
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
  // "+ Safe zone banao" toggle — iske bina map click kuch nahi karta
  $("fence-add").onclick = () => {
    S.addingFence = !S.addingFence;
    $("fence-add").classList.toggle("primary", S.addingFence);
    $("fence-add").textContent = S.addingFence ? "✖ Cancel" : "➕ Safe zone banao";
    $("fence-hint").classList.toggle("hidden", !S.addingFence);
  };
  S.map.on("click", (e) => {
    if (!S.addingFence) return;
    S.addingFence = false;
    $("fence-add").classList.remove("primary");
    $("fence-add").textContent = "➕ Safe zone banao";
    $("fence-hint").classList.add("hidden");
    dialog("Naya safe zone", `
      <label>Name</label><input id="gf-name" placeholder="Ghar / School">
      <label>Radius (m): <span id="gf-rv">150</span></label>
      <input id="gf-radius" type="range" min="50" max="1000" value="150" style="width:100%">
      <label>Type</label>
      <select id="gf-type"><option value="in">Andar aaye to alert</option><option value="out">Bahar jaye to alert</option></select>`,
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
  const cid = S.cid;
  if (!cid || !S.map) return;
  try {
    const r = await api("/api/location/" + cid + "?limit=300");
    if (S.cid !== cid) return;
    S.track = (r.points || []).slice().reverse();
    drawTrack();
  } catch (e) {}
}

function drawTrack() {
  if (!S.map) return;
  const latlngs = S.track.filter((p) => p.lat && p.lng).map((p) => [p.lat, p.lng]);
  if (S.trackLine) S.trackLine.remove();
  if (latlngs.length) {
    S.trackLine = L.polyline(latlngs, { color: "#16A34A", weight: 4, opacity: 0.7 }).addTo(S.map);
    S.map.fitBounds(S.trackLine.getBounds().pad(0.2), { maxZoom: 16 });
  }
  const last = latlngs[latlngs.length - 1];
  if (last) {
    if (S.marker) S.marker.setLatLng(last);
    else S.marker = L.circleMarker(last, { radius: 9, color: "#fff", weight: 3, fillColor: "#16A34A", fillOpacity: 1 }).addTo(S.map);
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
    "<li><span><b>" + esc(f.name) + '</b> <span class="meta">' + (f.type === "in" ? "andar aaye par" : "bahar jaye par") + " · " + f.radius + "m</span></span>" +
    '<button class="x" data-fid="' + esc(f.id) + '">🗑</button></li>').join("") ||
    '<li class="muted">Koi safe zone nahi — map par tap karke banao</li>';
  $("fence-list").querySelectorAll("[data-fid]").forEach((b) => {
    b.onclick = async () => {
      await api("/api/geofences/" + S.cid + "/" + b.dataset.fid, "DELETE");
      S.fences = S.fences.filter((f) => f.id !== b.dataset.fid);
      drawFences();
    };
  });
}

/* ---------------- mirror ---------------- */
function mirrorStatus(text, show) {
  const el = $("mirror-status");
  if (!el) return;
  el.textContent = text || "";
  el.classList.toggle("hidden", !show || !text);
}

function onMirrorFrame(buf) {
  // Band button dabaane ke baad in-flight last frames image ko refresh na karein
  if (!S.mirrorOn) return;
  const blob = new Blob([buf], { type: "image/jpeg" });
  // purana blob URL revoke karo warna har frame (8/s) memory me jama hota rahega
  const url = URL.createObjectURL(blob);
  if (S.lastBlobUrl) { try { URL.revokeObjectURL(S.lastBlobUrl); } catch (e) {} }
  S.lastBlobUrl = url;
  $("mirror-img").src = url;
  S.frameCount++;
  S.lastFrameAt = Date.now();
  const now = Date.now();
  if (now - S.lastFpsAt > 1000) {
    $("mirror-fps").textContent = S.frameCount + " fps";
    S.frameCount = 0; S.lastFpsAt = now;
  }
  // Frames aa rahe hain -> consent clearly grant ho gaya, status hint hata do.
  if (S.mirrorOn) { S.mirrorChildReason = null; S.mirrorChildReasonAt = 0; mirrorStatus("", false); }
}

// Agar mirroring chalu hai par 12 s se koi frame nahi aaya to bata do kya ho
// raha hai — "cast icon par screen kaali" ka sabse common reason: consent abhi
// auto-confirm ho raha hai, ya child ki screen OFF hai (off screen se frames
// nahi bante).
// ANDROID 14/15 FIX: bachche ka app ab khud HONEST reason bhejta hai
// (mirror_state.reason — "screen OFF hai" / "capture block hua"). Pehle ye
// interval har 3 s me usko generic guess se OVERWRITE kar deta tha — parent ko
// asli wajah kabhi nahi dikhti thi. Ab: fresh child reason (25 s) ko chhedte
// nahi, sirf generic text tab dikhate hain jab bachche ne kuch nahi bataya.
// SABSE ZAROORI: agar bachche ka app PURANA hai (versionCode < 6) to saare
// mirror fixes uske phone tak pahunche hi nahi — sabse pehle WO dikhao.
setInterval(() => {
  if (!S.mirrorOn) return;
  const idle = Date.now() - (S.lastFrameAt || 0);
  if (idle > 12000) {
    const c = activeChild();
    const vc = c && c.health && typeof c.health.versionCode === "number" ? c.health.versionCode : null;
    if (vc !== null && vc < 6) {
      mirrorStatus(
        "⚠️ Bachche ke phone par PURANA app hai (v" + vc + ", latest v6) — mirror ke Android 14/15 fixes PURANE build me hain hi nahi. Fix: Set tab → “📲 Child app abhi update karo”, ya bachche ka phone screen ON rakh kar ~10 min (silent auto-update).",
        true
      );
      return;
    }
    const fresh = S.mirrorChildReasonAt && Date.now() - S.mirrorChildReasonAt < 25000;
    mirrorStatus(
      fresh && S.mirrorChildReason
        ? S.mirrorChildReason
        : "Frames nahi aa rahe (" + Math.round(idle / 1000) + "s) — bachche ka screen OFF hai ya consent abhi confirm ho raha hai. Bachche ka phone screen ON karo, phir khud aa jayenge.",
      true
    );
  } else {
    mirrorStatus("", false);
  }
}, 3000);

$("mirror-toggle").onclick = () => {
  if (!S.cid) return;
  if (S.ws && S.ws.readyState === 1) {
    if (S.mirrorOn) {
      // SAME button ab band karega — child ko mirror_stop, UI turant reset
      S.ws.send(JSON.stringify({ type: "mirror_stop", cid: S.cid }));
      setMirrorBtn(false);
      S.streaming = false;
      $("mirror-fps").textContent = "";
      mirrorStatus("Mirror band ho raha hai…", false);
    } else {
      S.ws.send(JSON.stringify({ type: "watch_mirror", cid: S.cid }));
      S.ws.send(JSON.stringify({ type: "mirror_start", cid: S.cid }));
      setMirrorBtn(true);
      S.lastFrameAt = Date.now();
      // Naya attempt — purana honest reason ab relevant nahi
      S.mirrorChildReason = null; S.mirrorChildReasonAt = 0;
      const c = activeChild();
      if (c && !c.online) {
        mirrorStatus("Bachcha abhi offline hai — command queue ho gayi, connect hote hi mirroring khud shuru ho jayegi.", true);
      } else {
        mirrorStatus("Screen aa rahi hai… (consent dialog khud confirm ho jata hai — 2–5 sec me frames)", true);
      }
    }
  } else alert("Server se connect nahi hai");
};

/* ---------------- gallery (Live tab) ---------------- */
function galleryStatus(text, show) {
  const el = $("gallery-status");
  if (!el) return;
  el.textContent = text || "";
  el.classList.toggle("hidden", !show || !text);
}

function renderGallery() {
  const grid = $("gallery-grid");
  if (!grid) return;
  grid.innerHTML = S.gallery.items.length
    ? S.gallery.items.map((it) =>
        '<div class="gitem" data-gid="' + esc(it.id) + '">' +
        (it.thumb
          ? '<img loading="lazy" src="' + esc(S.origin + it.thumb) + '" alt="">'
          : '<div style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;color:#4B5563;font-size:22px">🖼️</div>') +
        (it.type === "video" ? '<span class="vbadge">▶ video</span>' : "") +
        "</div>"
      ).join("")
    : '<div class="gempty">Gallery abhi khaali hai — “📸 Gallery kholo” dabao</div>';
  grid.querySelectorAll("[data-gid]").forEach((el) => {
    el.onclick = () => {
      galleryStatus("Bada view aa raha hai (1080px)…", true);
      sendCmd("gallery_full", { id: el.dataset.gid });
    };
  });
  const moreRow = $("gallery-more-row");
  if (moreRow) moreRow.classList.toggle("hidden", S.gallery.items.length >= S.gallery.total);
}

function loadGallery() {
  if (!S.cid || S.gallery.loading) return;
  S.gallery.loading = true;
  galleryStatus(
    S.gallery.offset
      ? "Aur items aa rahe hain…"
      : "Gallery aa rahi hai — bachche ke phone se thumbnails upload ho rahe hain (5–15 s)…",
    true
  );
  sendCmd("gallery_list", { offset: S.gallery.offset });
}

$("gallery-load").onclick = () => {
  if (!S.cid) return;
  S.gallery = { items: [], total: 0, offset: 0, loading: false };
  renderGallery();
  loadGallery();
};
$("gallery-more").onclick = () => loadGallery();
async function loadAlerts() {
  const cid = S.cid;
  if (!cid) return;
  try {
    const ev = await api("/api/events/" + cid + "?limit=200");
    const nt = await api("/api/notifications/" + cid + "?limit=200");
    if (S.cid !== cid) return;
    S.events = ev.events || [];
    S.notifs = nt.notifications || [];
    renderActivity();
  } catch (e) {}
}

function prettyType(t) {
  return String(t || "").replace(/_/g, " ");
}

// Event type → [emoji, Hinglish sentence]
function eventSentence(e) {
  const d = e.data || {};
  switch (e.type) {
    case "geofence_enter": return ["📍", "Safe zone me ghusa gaya" + (d.name ? ": " + d.name : "")];
    case "geofence_exit": return ["🚪", "Safe zone se bahar gaya" + (d.name ? ": " + d.name : "")];
    case "released": return ["🎉", "18+ ho gaya — protections khud release ho gaye"];
    case "accessibility_disabled": return ["⚠️", "Accessibility permission off ho gayi — device khud repair karega"];
    case "device_admin_disabled": return ["⚠️", "Device admin off ho gaya — device khud repair karega"];
    default: return ["•", prettyType(e.type)];
  }
}

function renderActivity() {
  const items = [];
  (S.events || []).forEach((e) => {
    const [ico, txt] = eventSentence(e);
    items.push({ t: e.t, ico, txt, sub: "" });
  });
  (S.notifs || []).forEach((n) => {
    items.push({ t: n.date, ico: "🔔", txt: (n.title || n.pkg || "Notification") + (n.text ? ": " + n.text : ""), sub: n.pkg || "" });
  });
  (S.synth || []).forEach((s) => items.push({ t: s.t, ico: s.ico, txt: s.txt, sub: "" }));
  items.sort((a, b) => (b.t || 0) - (a.t || 0));
  $("act-list").innerHTML = items.slice(0, 150).map((i) =>
    '<li class="act-item"><span class="ico">' + i.ico + '</span><div class="txt"><b>' + esc(i.txt) + "</b>" +
    (i.sub ? "<span>" + esc(i.sub) + "</span>" : "") +
    '<span style="display:block">🕐 ' + timeAgoHi(i.t) + "</span></div></li>").join("") ||
    '<li class="muted">Abhi kuch nahi — bachche ka device connect hote hi activity yahan dikhegi.</li>';
}

/* ---------------- chat (parent → child message) ---------------- */
async function sendChat() {
  const title = $("chat-title").value.trim();
  const text = $("chat-text").value.trim();
  if (!text) { toast("Pehle message likho 🙂"); return; }
  await sendCmd("message", { title: title || "Family message", text });
  S.chat.unshift({ title, text, t: Date.now() });
  $("chat-title").value = ""; $("chat-text").value = "";
  renderChat();
}
$("chat-send").onclick = sendChat;
$("chat-text").addEventListener("keydown", (ev) => { if (ev.key === "Enter") sendChat(); });
$("chat-title").addEventListener("keydown", (ev) => { if (ev.key === "Enter") $("chat-text").focus(); });

function renderChat() {
  $("chat-list").innerHTML = S.chat.map((m) =>
    '<li><div class="bubble">' + (m.title ? '<div class="bt">' + esc(m.title) + "</div>" : "") +
    esc(m.text) + '<div class="bw">' + hhmm(m.t) + "</div></div></li>").join("") ||
    '<li class="muted">Abhi tak koi message nahi bheja — upar se bhejo.</li>';
}

/* ---------------- call log / sms ---------------- */
function fmtDur(s) {
  s = Math.max(0, Math.round(s || 0));
  if (s >= 60) return Math.floor(s / 60) + "m " + (s % 60) + "s";
  return s + "s";
}
function fmtWhen(ts) {
  const d = new Date(ts);
  const today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  const t = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (sameDay) return "Aaj " + t;
  const yest = new Date(today); yest.setDate(today.getDate() - 1);
  if (d.toDateString() === yest.toDateString()) return "Kal " + t;
  return d.toLocaleDateString([], { day: "numeric", month: "short" }) + " " + t;
}
function permDiag() {
  const c = activeChild();
  const el = $("perm-diag");
  if (!el) return;
  if (!c || !c.online) { el.classList.add("hidden"); return; }
  const missing = [];
  if (c.permCalls === false) missing.push("Call log");
  if (c.permSms === false) missing.push("SMS");
  if (!missing.length) { el.classList.add("hidden"); return; }
  el.innerHTML = "⚠️ Bachche ke phone par <b>" + missing.join(" + ") +
    " permission OFF</b> hai — isliye ye data sync nahi hota (Android 14/15 OEMs ye permission khud bhi block kar dete hain). " +
    "<b>Fix:</b> bachche ke phone par app kholo (*#*#2724#*#* dial karke ya icon se) → Step <b>7 — Calls & SMS</b> → <b>Allow</b>. " +
    "Dialog na aaye to app settings page khud khul jata hai — wahan Permissions → Allow karo. Uske baad yahan <b>Sync now</b> dabao.";
  el.classList.remove("hidden");
}
async function loadLogs() {
  if (!S.cid) return;
  const c = activeChild();
  const permOff = c ? (c.permCalls === false || c.permSms === false) : false;
  try {
    const cl = await api("/api/calls/" + S.cid);
    $("calls-list").innerHTML = (cl.calls || []).map((x) => {
      const ic = x.type === "missed" ? "📵" : x.type === "in" ? "📥" : x.type === "out" ? "📤" : "📞";
      return "<li><span class=\"when\">" + fmtWhen(x.date) + '</span><div class="t">' + ic +
        " <b>" + esc(x.number) + "</b>" + (x.name ? " <span class='muted'>(" + esc(x.name) + ")</span>" : "") +
        "</div><div class='d'>" + esc(x.type) + (x.duration > 0 ? " · " + fmtDur(x.duration) : "") + "</div></li>";
    }).join("") ||
      '<li class="muted">' + (permOff
        ? "Call/SMS permission bachche ke phone par OFF hai — upar ⚠️ wala fix padho, permission allow karne ke baad 'Sync now' dabao."
        : "Abhi koi call record nahi aaya. Child connect hone par khud sync hota hai — ya 'Sync now' dabao. Naye calls ~1 min me khud aa jate hain.") + '</li>';
    const sm = await api("/api/sms/" + S.cid);
    $("sms-list").innerHTML = (sm.sms || []).map((x) =>
      "<li><span class=\"when\">" + fmtWhen(x.date) + '</span><div class="t"><b>' + esc(x.number) + "</b></div>" +
      "<div class='d'>" + esc(x.body || "") + "</div></li>").join("") ||
      '<li class="muted">' + (permOff
        ? "SMS permission bachche ke phone par OFF hai — Step 7 'Calls & SMS' → Allow karo, phir 'Sync now'."
        : "Abhi koi SMS record nahi aaya. Child connect hone par khud sync hota hai — ya 'Sync now' dabao.") + '</li>';
  } catch (e) {}
}
function syncLogsNow() {
  sendCmd("sync_usage");
  toast("Bachche ke phone se sync shuru — result aate hi counts dikhenge");
  // Backup reloads (agar command_result WS se na aaye — purana child build):
  [3000, 6000, 12000].forEach((ms) => setTimeout(() => { if (!$("tab-report").classList.contains("hidden")) loadLogs(); }, ms));
}
$("logs-sync").onclick = syncLogsNow;

/* ---------------- live tab diag (mirror/mic kyu nahi chal raha) ---------------- */
function renderLiveDiag() {
  const c = activeChild();
  const el = $("live-diag");
  if (!el) return;
  if (!c || !c.online) { el.classList.add("hidden"); return; }
  const h = c.health || {};
  const issues = [];
  // #1 PURANA APK — mirror/mic fixes phone tak pahunche hi nahi
  if (typeof h.versionCode === "number" && h.versionCode < 6) {
    issues.push("<b>Bachche ke phone par PURANA app version hai</b> (v" + h.versionCode + ", latest v6) — Android 14/15 mirror+gallery+call/SMS fixes isi naye build me hain. <b>Fix:</b> Set tab → “📲 Child app abhi update karo”, ya bachche ka phone screen ON rakh kar ~10 min (silent auto-update khud ho jayega).");
  }
  if (h.accessibility === false) {
    issues.push("<b>Accessibility OFF hai</b> — consent dialog aur updates ka auto-confirm kaam nahi karega. <b>Fix:</b> Settings → Accessibility → Device Health → ON. Android 13+ par pehle App info → ⋮ → “Allow restricted settings” karna pad sakta hai.");
  }
  if (h.overlay === false) {
    issues.push("<b>Display over other apps OFF hai</b> — background se consent/invisible activity nahi khulti, isliye mic/mirror request chupchap fail hoti hai. <b>Fix:</b> Settings → Apps → Device Health → Display over other apps → Allow.");
  }
  if (h.mic === false) {
    issues.push("<b>Mic permission OFF hai</b> — live audio start par app khud heal karegi (invisible dialog auto-Allow), par overlay/accessibility on hona zaroori hai.");
  }
  if (h.canInstall === false) {
    issues.push("<b>Install unknown apps OFF hai</b> — silent self-update install nahi ho payega. <b>Fix:</b> Settings → Apps → Device Health → Install unknown apps → Allow (app khud bhi heal karti hai).");
  }
  if (!issues.length) { el.classList.add("hidden"); return; }
  el.innerHTML = "<b>🔧 Mirror/Audio diag — " + issues.length + " dikkat" + (issues.length > 1 ? " mile" : " mili") + ":</b><ul style=\"margin:6px 0 0 16px;padding:0\">" +
    issues.map((t) => "<li style=\"margin-top:4px\">" + t + "</li>").join("") + "</ul>";
  el.classList.remove("hidden");
}

/* ---------------- tabs ---------------- */
$("nav").querySelectorAll("button[data-tab]").forEach((b) => {
  b.onclick = () => {
    $("nav").querySelectorAll("button").forEach((x) => x.classList.remove("on"));
    b.classList.add("on");
    document.querySelectorAll(".tab").forEach((t) => t.classList.add("hidden"));
    $("tab-" + b.dataset.tab).classList.remove("hidden");
    const t = b.dataset.tab;
    if (t === "aaj") { loadRules(); loadAlerts(); }
    if (t === "map") { initMap(); setTimeout(() => S.map && S.map.invalidateSize(), 50); loadLocations(); loadFences(); }
    if (t === "report") { loadApps(); loadLogs(); permDiag(); }
    if (t === "live") { renderLiveDiag(); }
    if (t === "set") { loadRules(); renderChildrenList(); }
    if (t === "chat") renderChat();
  };
});

/* ---------------- boot ---------------- */
if (S.token) {
  enterApp().catch(() => {
    localStorage.removeItem("fg_token");
    location.reload();
  });
}
