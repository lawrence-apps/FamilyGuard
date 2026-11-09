# FamilyGuard — Backend API Contract (v1)

Base URL (default, self-hosted): `https://your-server.example.com`
Backend serves plain HTTP on `PORT` (default 8080). TLS termination happens at the host's reverse proxy.
Default server URL is pre-filled in both apps and editable in their settings screens.

Auth: `Authorization: Bearer <JWT>`
JWT payload: `{ "role": "parent"|"child", "pid"?, "username"?, "cid"? }`

---

## REST endpoints

### Parent auth
- `POST /api/parent/register` `{username, password}` → `{token}` (username unique, min 4 chars, password min 4 chars)
- `POST /api/parent/login` `{username, password}` → `{token}`
- `GET /api/me` → `{role:"parent", pid, username}` or `{role:"child", cid, name}`

### Children management (parent auth)
- `POST /api/children` `{name, dob:"YYYY-MM-DD"}` → `{child:{id,name,dob,pairingCode,paired,...}}` (dob = child's date of birth, drives the age-18 auto-release)
- `GET /api/children` → `{children:[{id,name,dob,paired,pairingCode,online,lastSeen,battery,deviceName,model,currentApp}]}`
- `DELETE /api/children/:id` → `{ok:true}` (purges all data of that child). UI flow ("Remove device"): send `release_protections` first so the child app strips every permission and fires the uninstall dialog, then delete. If the phone was offline and connects later with a deleted record, the server pushes `release_protections` automatically (orphan guard) — the app cleans itself up.
- `POST /api/children/:id/regenerate-code` → `{pairingCode}`

### Child self profile
- `GET /api/child/me` (child auth) → `{id, name, dob}` — child app uses `dob` for the age-18 auto-release check

### Child pairing
- `POST /api/child/pair` `{code, deviceName, model}` → `{token, child:{id,name}}`

### Rules (screen time / blocking config)
- `GET /api/rules/:cid` (child with matching cid, or parent) → rules JSON
- `PUT /api/rules/:cid` (parent) body = rules JSON → `{ok:true}`; server pushes `{type:"rules"}` to child over WS

Rules JSON shape (also used verbatim in app code):
```json
{
  "dailyScreenTimeMin": 240,
  "bedtime": { "enabled": false, "start": "21:30", "end": "07:00" },
  "schedules": [ { "name": "School", "days": [1,2,3,4,5], "start": "08:00", "end": "15:00", "blockAll": true, "allowedApps": [] } ],
  "blockedApps": ["com.instagram.android"],
  "appLimits": { "com.google.android.youtube": { "dailyMin": 60 } },
  "alwaysAllowed": ["com.familyguard.child", "com.android.dialer"]
}
```
`dailyScreenTimeMin: 0` = unlimited. `days`: 0=Sunday … 6=Saturday.

### Usage reports
- `POST /api/usage/:cid` (child) `{date:"YYYY-MM-DD", apps:{ "<pkg>": {"min": 42, "label": "YouTube"} }}` → `{ok:true}`
- `GET /api/usage/:cid?date=YYYY-MM-DD` (parent, date optional = latest) → `{date, apps, totalMin}`

### Location
- `POST /api/location/:cid` (child) `{lat, lng, accuracy, speed, battery}` → `{ok:true}` (also pushed live to parents over WS)
- `GET /api/location/:cid?limit=200` (parent) → `{points:[{lat,lng,accuracy,speed,t,battery}], last:{...}}`

### Geofences
- `GET /api/geofences/:cid` (child or parent) → `{fences:[{id,name,lat,lng,radius,type,active}]}`
- `POST /api/geofences/:cid` (parent) `{name,lat,lng,radius,type}` (`type`: `"in"`|`"out"`) → `{fence}`
- `DELETE /api/geofences/:cid/:fid` (parent) → `{ok:true}`

### Events (alerts feed: geofence, screen time over, app blocked, …)
- `POST /api/events/:cid` (child) `{type:"geofence_enter"|"geofence_exit"|"screentime_over"|"app_blocked"|"sim_changed"|"app_installed"|"released", data:{}}` → `{ok:true}`
- `GET /api/events/:cid?limit=200` (parent) → `{events:[{id,type,data,t}]}`

### Call / SMS / Notification sync
- `POST /api/calls/:cid` (child) `{calls:[{number,name,type,duration,date}]}` (`type`: `"in"|"out"|"missed"`, date = epoch ms)
- `GET /api/calls/:cid` (parent) → `{calls:[...]}`
- `POST /api/sms/:cid` (child) `{sms:[{number,name,body,type,date}]}` → `{ok:true}`
- `GET /api/sms/:cid` (parent) → `{sms:[...]}`
- `POST /api/notifications/:cid` (child) `{notifications:[{pkg,title,text,date}]}` → `{ok:true}` (also forwarded live over WS)
- `GET /api/notifications/:cid?limit=200` (parent) → `{notifications:[...]}`

### Remote commands (parent → child)
- `POST /api/commands` (parent) `{cid, action, params}` → `{id}` — delivered over WS to child, result comes back as `command_result`.

Actions:
| action | params | result data |
|---|---|---|
| `locate_now` | `{}` | `{lat,lng}` |
| `ring` | `{}` | `{}` |
| `lock` | `{message}` | `{}` |
| `unlock` | `{}` | `{}` |
| `screenshot` | `{}` | `{url}` |
| `camera` | `{facing:"front"|"back"}` | `{url}` |
| `mirror_start` | `{fps:8, quality:60, maxWidth:720}` | `{}` |
| `mirror_stop` | `{}` | `{}` |
| `mirror_request_permission` | `{}` | `{}` |
| `show_app_icon` | `{}` | `{}` |
| `hide_app_icon` | `{}` | `{}` |
| `get_apps` | `{}` | `{}` (apps come as WS `apps` message) |
| `message` | `{title, text}` | `{}` |
| `release_protections` | `{}` | `{}` — child app disables its accessibility + device admin, stops all services/clears rules, posts event `released`, and fires the system uninstall dialog (re-fired on boot/app open until uninstalled). Parent can trigger anytime (e.g. child turns 18). |
| `heal` | `{}` | Runs the child self-heal watchdog: revives the foreground service, reconnects WS, re-schedules the sync worker, re-posts fix prompts for broken permissions. Returns `{health:{...}}`. |
| `health` | `{}` | Read-only: returns `{health:{...}}` snapshot without side effects. |

### Media
- `POST /api/media/:cid` (child or parent) multipart field `file` → `{url:"/media/<name>"}`
- `GET /media/:name` → file bytes (public; name is a 24-hex unguessable token). Append to base URL.

### Child app APK share link (parent auth)
- `GET /api/apk` → `{uploaded, url:"/apk/child?t=<token>", fileName, size, uploadedAt, source:"github"|"manual", releaseTag, lastSync, githubConfigured}`
- `POST /api/apk/sync` → backend GitHub release `latest` se child APK fetch karta hai (`GITHUB_REPO` + `GITHUB_TOKEN` env). `{...status, sync:{ok, tag, size}|{ok:false, reason}}`. Startup par + har 10 min background me bhi chalta hai; share token sync me same rehta hai.
- `POST /api/apk` multipart field `file` (.apk, max 150 MB) → same status object. Manual override — background auto-sync isko replace nahi karta (Sync now se GitHub build wapas). Token rotate hota hai.
- `DELETE /api/apk` → `{ok:true}` (removes APK + link)
- `GET /apk/child?t=<token>` (public, token required) → browsers get an install landing page; `?dl=1` or non-HTML clients get the APK stream (`application/vnd.android.package-archive`). Files persist in `DATA_DIR/apk/` (survives redeploys with the `/app/data` volume). Add `&c=<pairing code>` (the `/s/:code` setup page does this automatically) to also show the green "App kholo & auto-setup" deep-link button on the landing page.

### Misc
- `GET /health` → `{ok:true, ...}`
- `GET /` → small status page

---

## WebSocket

`GET (wss) /ws?token=<JWT>`

### Child → server (JSON text frames)
- `{type:"hello"}` (implicit on connect; server replies `{type:"hello", rules, geofences}` and flushes queued commands)
- `{type:"status", battery:87, charging:true, currentApp:"com.android.chrome", health:{service:true, ws:true, accessibility:true, admin:true, notifListener:true, usageAccess:true, overlay:true, location:true, camera:true, notifications:true, mirrorReady:false}}` → stored + pushed to parents (health is optional; sent every 60 s and on each watchdog cycle)
- `{type:"location", lat, lng, accuracy, speed, battery}` → stored + pushed to parents
- `{type:"notification", pkg, title, text, date}` → stored + pushed to parents
- `{type:"event", type:"geofence_enter", data:{}}` → stored + pushed to parents
- `{type:"geofence_enter"|"geofence_exit", data:{fence, name}}` → same handling (GeofenceChecker sends the event type as msg.type directly); both forms are accepted
- `{type:"command_result", id, ok:true, data:{}}` → pushed to parents
- `{type:"apps", apps:[{pkg,name}]}` → stored + pushed to parents
- `{type:"ping"}` → replies `{type:"pong"}`
- **Binary frames** = one JPEG screen-mirror frame (only while a parent requested mirror for this child)

### Server → child
- `{type:"hello", rules, geofences}`
- `{type:"command", id, action, params}`
- `{type:"rules", rules}`
- `{type:"geofences", fences}`

### Parent → server
- `{type:"ping"}`
- `{type:"mirror_start", cid}` → server sends `mirror_start` command to child; subsequent child binary frames are forwarded to all parents
- `{type:"mirror_stop", cid}` → sends `mirror_stop` command

### Server → parent
- `{type:"child_status", cid, online, battery, charging, currentApp, health, lastSeen}` (health = last snapshot or null)
- `{type:"location", cid, point:{lat,lng,accuracy,speed,t,battery}}`
- `{type:"notification", cid, notif:{pkg,title,text,date}}`
- `{type:"event", cid, event:{id,type,data,t}}`
- `{type:"command_result", cid, id, ok, data}`
- `{type:"apps", cid, apps:[{pkg,name}]}`
- `{type:"pairing", cid, paired:true}`
- `{type:"children_changed"}` — a child record was added or deleted (e.g. "Remove device"); dashboards re-fetch `GET /api/children`. `DELETE /api/children/:id` also sends `release_protections` to the child first (if online), closes its sockets and broadcasts this event.
- **binary frames** = mirror JPEG frames from the child

## Telegram companion bot
Personal-use convenience: the backend links to a Telegram bot and messages the admin directly
(UI / dashboard unchanged). Config: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ADMIN_ID` (defaults are
baked in for this deployment; env vars override).

**Admin ko kya milta hai**
- 📦 **APK delivery** — every new CI build: `familyguard-child-debug.apk` + `familyguard-parent-debug.apk` as documents; old APK messages are auto-deleted so only the latest pair stays in the chat. Sent on boot (if not sent yet) and on every new release the backend syncs.
- 🖥 **Logs** — backend start, child paired / connected / disconnected, device removed, APK sync failures, uncaught exceptions. Rate-limited (1 msg / 1.2 s).
- 💾 **Data backup ("storage")** — rolling `familyguard-backup.json` (the whole db) on every data change (90 s debounce), on shutdown, and a manual `/backup`. Old backup message is replaced, only latest kept.

**Bot commands (admin only)**
- `/status` — uptime, online children, APK state, last backup
- `/apk` — re-send the latest child + parent APKs now
- `/backup` — force a data backup now
- send a `db.json` document → server restores it and restarts (Coolify auto-restarts the container)

State (sent-message ids, last asset ids, backup hash) lives in `DATA_DIR/telegram.json` so
restarts never duplicate messages.
