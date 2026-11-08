# FamilyGuard — Free Self-Hosted Parental Control (AirDroid alternative)

Private, personal-use only. No Play Store, no cloud service of anyone else — **your own backend, your own data.**

Recreates the AirDroid Parental Control feature set for **one parent + their child device(s)**:

| Feature | How it works |
|---|---|
| Screen time limits + bedtime | Rules from parent app → child enforces with a full-screen block overlay |
| Schedules (school hours etc.) | Day-wise blocks, per-schedule allowed apps |
| App blocking + per-app daily limits | Live foreground-app detection (Accessibility) + enforcement |
| Activity / usage reports | UsageStats daily per-app minutes, synced to backend |
| Live location + history | Child uploads location every 60 s; parent sees map (osmdroid, no API key) |
| Geofencing (entry/exit alerts) | Safe zones with radius; child computes enter/exit → alert event |
| **Screen mirroring** | MediaProjection on child → JPEG frames over your own WebSocket → parent app live view |
| Remote camera + screenshot | camera2 still capture / mirror-frame grab → uploaded → shown in parent app |
| Ring / Lock / Message / Unlock | Remote commands; lock uses Device Admin (screen lock) or overlay |
| Notification sync + alerts feed | NotificationListener → backend → parent app + system notifications |
| Call & SMS log sync | Read-only sync of call log + SMS to your own server |
| **One-time setup, then hidden** | After setup the child app hides its launcher icon; everything else is remote |
| **Age-18 auto release** | Parent enters child DOB; at 18 (or manual) the child app removes accessibility/admin, stops services, and fires the system uninstall dialog until removed |
| **Self-healing (no touching the child phone again)** | Watchdog auto-restarts a killed service, reconnects the websocket, re-schedules sync; broken permissions pop a full-screen fix prompt on the child device; parent gets a live health report + Auto-Repair button |

> Note: final uninstall always needs **one tap on the child device** (Android system dialog) — no app can bypass that without root. Everything else (permission release, monitoring stop) is automatic.

## Repo layout

```
backend/     Node.js API + WebSocket relay + JSON storage (self-hosted)
parent-app/  Android (Kotlin + Compose) — your phone
child-app/   Android (Kotlin) — child's phone ("Device Health", hides itself)
API.md       Full REST + WebSocket contract
```

## 1) Deploy the backend (your server)

Default apps point at `https://your-server.example.com` — deploy there (or change the URL in both apps' settings).

**Option A — Docker (recommended)**
```bash
docker compose up -d --build
```

**Option B — plain Node**
```bash
cd backend
npm install
node server.js          # listens on :8080 (PORT env)
```

- Data is stored in `backend/data/db.json` (auto-created). Media in `backend/data/media/`.
- If your proxy terminates TLS, enable **WebSocket passthrough** (`/ws`, Upgrade header) — mirroring, live location and commands run over it.
- Health check: `GET /health`.

### DeployHub par deploy (your-server.example.com)

Login/pairing **har redeploy ke baad banaye rakhne** ke liye ye 2 cheezein zaroori hain:

1. **Persistent volume mount karo: `/app/data`** — isi folder me `db.json` (accounts, children, pairing) aur `jwt.secret` (login tokens) rehte hain. Volume na ho to har redeploy par sab data udd jayega aur aapko dobara register + re-pair karna padega.
2. **(Optional, best) `JWT_SECRET` env set karo** — koi bhi lamba random string. Ye pin ho jayega to token validity data file par depend nahi karegi.

DeployHub panel me:
- **Repo / Dockerfile**: repo root ka `backend/Dockerfile` (build context `backend/`) ya repo root me `docker-compose.yml` use karo.
- **Port**: `8080` (HTTP). Domain `your-server.example.com` point karo; TLS terminate karne wale proxy me **WebSocket upgrade** (`/ws`) enable hona chahiye.
- **Volume**: Host path ya named volume → container path **`/app/data`**.
- **Health check path**: `/health`.

Deploy ke baad verify: `https://your-server.example.com/health` → `{"ok":true,...}`.

## 2) Get the APKs

**Option A — GitHub Actions (no Android Studio needed)**
Push happens automatically on every commit: repo → **Actions → Build APKs → artifacts**
- `familyguard-parent-debug-apk`
- `familyguard-child-debug-apk`

Install both on the respective phones (allow "install unknown apps" for your browser/file manager).

**Option B — locally**
```bash
# needs JDK 17 + Android SDK
gradle assembleDebug -p parent-app
gradle assembleDebug -p child-app
```

## 3) One-time setup (5 minutes)

**Parent phone — FamilyGuard Parent**
1. Open app → server URL is pre-filled → **Register** (username + password, stored on your server).
2. **Create child** → enter name + **date of birth** (DOB powers the 18+ auto-release).
3. A **6-digit pairing code** appears (also visible on the dashboard anytime).

**Child phone — "Device Health"**
1. Open app → enter server URL (pre-filled) + pairing code → **Pair**.
2. Go down the permission checklist: Location, Usage access, Overlay, Notification access, Camera, (optional) Calls/SMS, Screen capture, Battery optimization, Device admin.
3. **Finish & Hide App** → icon disappears, service keeps running.

Done — **you never touch the child phone again.** Everything (limits, blocked apps, geofences, mirror, lock…) is managed from the parent app.

### Re-opening the child app (if ever needed)
- Dial `*#*#2724#*#*` on the child phone, **or**
- Parent app → More → **Show child app icon** (remote command), hide it again anytime.

## 4) Web dashboard for iPhone / any browser (no app needed)

Open **`https://your-server.example.com/app`** (backend serves it automatically) — on iPhone: Safari → Share → **Add to Home Screen** for a full-screen app experience.

Same feature set as the parent app: live status, screen time + bedtime rules, app blocking, live map + geofences, **screen mirroring**, remote camera/screenshot/lock/ring, alerts feed, call/SMS logs, children management and the 18+ release control. Login is the same parent account (register once).

## 5) Age-18 automatic release

- When the child's DOB date passes 18 years (checked on boot, app open, and every 15-min sync):
  - Accessibility + Device admin are removed, services stop, rules cleared
  - A `released` event appears in the parent Alerts feed
  - The **system uninstall dialog** opens (and re-opens on boot/open until uninstalled)
- Parents can trigger the same instantly, anytime: dashboard → **Uninstall** quick action (confirm dialog) or More → **Release protections & uninstall**.

## 6) Self-healing — kuch bhi toote to bacche ka phone dobara nahi lena padta

The child app repairs itself automatically; the parent only sees status and can trigger one extra "Auto-Repair" push:

| Breakage | Automatic fix (no phone touch) |
|---|---|
| Foreground service killed (system / swipe away / crash) | `onDestroy` schedules an exact-alarm restart after 3 s; the 15-min watchdog also revives it |
| Phone rebooted | `BOOT_COMPLETED` receiver starts service + sync worker |
| WebSocket dropped | Exponential-backoff reconnect (3 s → 60 s) + 25 s ping keep-alive |
| WorkManager job cancelled | Re-scheduled on every watchdog cycle |
| Accessibility turned off | Full-screen notification on the child device that opens Accessibility Settings; `accessibility_disabled` event to parent |
| Device admin removed | Full-screen notification that opens the admin activation screen |
| Location / usage / overlay / notification permission revoked | Notification deep-linking to the right settings screen |
| Server redeployed (token dead) | Child auto-unpairs → shows setup wizard again; parent app returns to login once |

**Health reporting:** every 60 s the child sends a full health snapshot (service, ws, accessibility, admin, notifListener, usageAccess, overlay, location, camera, notifications, mirrorReady) → backend → parent app. The dashboard shows a red **Needs repair** banner listing exactly what is broken, with an **Auto-Repair** button (`heal` command). Remote screen also has Auto-Repair + Check health.

> Only one case needs a physical tap: re-granting a permission Android does not allow apps to grant themselves — and even then the child device pops up the exact settings screen as a full-screen prompt, so it is one tap there, not taking the phone to another room.

## Troubleshooting

- **Child shows offline**: disable battery optimization for "Device Health" (setup step 9), keep it from "sleeping" (Xiaomi/Oppo/Vivo: Autostart ON).
- **Mirror says "no permission"**: screen capture consent is granted in setup (step 8); after a reboot Android may need it again — parent app → Mirror → it will prompt the child device once.
- **HTTP test server**: both apps allow `http://` URLs (cleartext enabled) for LAN testing.
- **Reset everything**: delete `backend/data/db.json` and restart, then re-register/re-pair.

### Login / session notes (baar-baar login nahi hoga)

- **Parent app**: token `SharedPreferences` me save hota hai — app kholte hi auto-login. Token 365 din valid rehta hai. Agar server ka DB reset ho jaye (ya JWT secret badal jaye) to app **khud detect** karke ek baar login screen dikhati hai ("Session expired — login again") — blank dashboard pe atak nahi jaata.
- **Web dashboard**: token `localStorage` me — browser band karke wapas khologe to seedha dashboard milega.
- **Child app**: pairing token bhi persist hota hai; server DB reset hone par child khud ko unpair kar deta hai — `*#*#2724#*#*` dial karke 2 minute me naya code daal ke re-pair ho jata hai.
- **Kabhi bhi repeated login na aaye** to DeployHub me `/app/data` volume laga hua check karo (upar dekho) — bina volume ke har redeploy par data reset hota hai.

## Privacy & legal

For **your own child's device, with your knowledge as the parent**. Local laws on minor monitoring vary — you are responsible for lawful use. No data leaves your server.
