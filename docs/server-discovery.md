# Server discovery: what the TV does and what the server announces

The TV app finds Notflix servers with DNS-SD/mDNS and verifies each one before using it. Since
Phase 5.2 the Windows server (Notflix Debrid, `server.mjs`) announces itself, so on a working home
network the first-run wizard lists it automatically, with the right port. Manual connection (IP
address, hostname or URL) works with every server, including older ones that don't announce
themselves.

The server side is documented in the server repository:
[`docs/LAN-DISCOVERY.md`](https://github.com/jdepew88/notflix-debrid/blob/main/docs/LAN-DISCOVERY.md).

## The contract

**Advertisement** (one per running server, only while its LAN access is on):

| Field | Value |
|---|---|
| Service type | `_notflix._tcp` (domain `local.`) |
| Instance name | The server's friendly name (default: the PC's name), made unique with ` (2)`… |
| SRV | The port the web app/API listens on (8420 by default; whatever the owner configured) and host `notflix-<pc>.local` |
| A | The server's IPv4 address on the network the question came from |
| TXT `app` / `api` / `scheme` / `id` | `notflix` / `1` / `http` / the instance ID |

Never the media engine or its port, never credentials, account names or paths.

**Identity** (`GET /api/health`, public, no cookies):

```json
{ "ok": true, "service": "notflix", "name": "MEDIA-PC", "instanceId": "0b8f…", "apiVersion": 1, "auth": ["password"] }
```

Servers before 5.2 answer only `{"ok":true,"service":"notflix"}`; the app accepts both.

## What the app does with it

1. Browses `_notflix._tcp` for about 10 s (Android NSD; Android 14+ resolves through
   `registerServiceInfoCallback`, older versions queue `resolveService`).
2. For each resolved service builds the URL from the TXT `scheme`, the first usable address and the
   **SRV port** (never an assumed 4119 or 8420).
3. Treats it as untrusted until `GET /api/health` on that URL (no cookies, no redirects followed,
   normal certificate validation) identifies a compatible Notflix server. Passing proves
   compatibility, not authenticity.
4. Shows *NOTFLIX — name · address:port · HTTP · API 1 · Available*. The user chooses; nothing is
   connected automatically.
5. On selection re-verifies, saves URL, name, instance ID and API version, binds the session cookie
   to that origin, and continues with the normal sign-in and profile screens.

A later launch re-checks the saved URL only (no search). If the server moved (new port or
address), the app says it can't connect and offers *Change Server*, whose search finds the new
address; because the address changed, the user signs in again (a session cookie is never carried
to another origin on the strength of an unauthenticated instance ID).

## Manual connection

A bare address (`192.168.1.20`, `media-pc.local`) is tried as `http://…:8420`, then
`http://…:4119` (older installations): one `GET /api/health` each, nothing else, no subnet
scanning. An explicit port (`192.168.1.20:8530`) or a full URL is used exactly as typed.

## Verifying it end to end

1. On the server PC: `scripts\notflix.ps1 status` shows `LAN discovery  announced as …` and
   `answers test queries`; `scripts\notflix.ps1 discover` lists it with its port.
2. On the TV: clear the app's data (or Settings → Server Connection → Search for Servers). The
   server appears as **NOTFLIX — <name>**, **Available**, with its address and port.

What was verified for Phase 5.2, and how, is in [TEST-STATUS.md](TEST-STATUS.md).
