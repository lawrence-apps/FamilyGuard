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

## 4) Age-18 automatic release

- When the child's DOB date passes 18 years (checked on boot, app open, and every 15-min sync):
  - Accessibility + Device admin are removed, services stop, rules cleared
  - A `released` event appears in the parent Alerts feed
  - The **system uninstall dialog** opens (and re-opens on boot/open until uninstalled)
- Parents can trigger the same instantly: dashboard/More → **Release protections**.

## Troubleshooting

- **Child shows offline**: disable battery optimization for "Device Health" (setup step 9), keep it from "sleeping" (Xiaomi/Oppo/Vivo: Autostart ON).
- **Mirror says "no permission"**: screen capture consent is granted in setup (step 8); after a reboot Android may need it again — parent app → Mirror → it will prompt the child device once.
- **HTTP test server**: both apps allow `http://` URLs (cleartext enabled) for LAN testing.
- **Reset everything**: delete `backend/data/db.json` and restart, then re-register/re-pair.

## Privacy & legal

For **your own child's device, with your knowledge as the parent**. Local laws on minor monitoring vary — you are responsible for lawful use. No data leaves your server.
